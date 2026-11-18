/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger.asr;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.StatFs;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Downloads, verifies and deletes speech recognition models.
 *
 * <p>Files live in {@code files/easygram/asr/<model id>/}, outside of the cache Telegram clears. A file is
 * downloaded into {@code <name>.part} (resumed with an HTTP Range request after an interruption), checked against
 * its size and SHA-256 and only then renamed; {@code ready} is written last, holding the hashes of the catalog
 * entry, so a model whose catalog entry changed is no longer reported as ready.</p>
 */
public final class EasygramAsrModelManager {

    public static final int STATE_NONE = 0, STATE_QUEUED = 1, STATE_DOWNLOADING = 2, STATE_READY = 3;

    public static final int ERROR_NONE = 0, ERROR_NETWORK = 1, ERROR_NO_SPACE = 2, ERROR_CHECKSUM = 3, ERROR_WIFI_REQUIRED = 4;

    public interface Listener {
        /** Called on the UI thread whenever the state, progress or error of a model changes. */
        void onAsrModelChanged(String modelId);
    }

    private static final class Task {
        final EasygramAsrModels.Model model;
        volatile boolean cancelled;
        volatile long downloaded;
        volatile boolean started;
        volatile boolean allowMetered;

        Task(EasygramAsrModels.Model model) {
            this.model = model;
        }
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final HashMap<String, Task> tasks = new HashMap<>();
    private static final HashMap<String, Integer> lastErrors = new HashMap<>();
    private static final HashMap<String, Boolean> readyCache = new HashMap<>();
    private static Thread worker;

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyChanged(String id) {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) {
                l.onAsrModelChanged(id);
            }
        });
    }

    static File baseDir() {
        return new File(ApplicationLoader.getFilesDirFixed(), "easygram/asr");
    }

    public static File modelDir(EasygramAsrModels.Model m) {
        return new File(baseDir(), m.id);
    }

    private static String readySignature(EasygramAsrModels.Model m) {
        StringBuilder sb = new StringBuilder();
        for (EasygramAsrModels.ModelFile f : m.files) {
            sb.append(f.name).append(' ').append(f.sha256).append('\n');
        }
        return sb.toString();
    }

    public static boolean isReady(EasygramAsrModels.Model m) {
        synchronized (readyCache) {
            Boolean cached = readyCache.get(m.id);
            if (cached != null) {
                return cached;
            }
        }
        boolean ready = false;
        File marker = new File(modelDir(m), "ready");
        if (marker.exists()) {
            try {
                String content = new String(readAll(marker), StandardCharsets.UTF_8);
                ready = content.equals(readySignature(m));
                if (ready) {
                    for (EasygramAsrModels.ModelFile f : m.files) {
                        File file = new File(modelDir(m), f.name);
                        if (file.length() != f.size) {
                            ready = false;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                ready = false;
            }
        }
        synchronized (readyCache) {
            readyCache.put(m.id, ready);
        }
        return ready;
    }

    public static int getState(EasygramAsrModels.Model m) {
        synchronized (tasks) {
            Task t = tasks.get(m.id);
            if (t != null) {
                return t.started ? STATE_DOWNLOADING : STATE_QUEUED;
            }
        }
        return isReady(m) ? STATE_READY : STATE_NONE;
    }

    /** Downloaded part of the model, 0..1, while it downloads. */
    public static float getProgress(EasygramAsrModels.Model m) {
        synchronized (tasks) {
            Task t = tasks.get(m.id);
            if (t == null) {
                return isReady(m) ? 1f : 0f;
            }
            return Math.min(1f, t.downloaded / (float) Math.max(1, m.totalSize()));
        }
    }

    public static int getLastError(EasygramAsrModels.Model m) {
        synchronized (tasks) {
            Integer e = lastErrors.get(m.id);
            return e == null ? ERROR_NONE : e;
        }
    }

    /** Bytes taken by all downloaded and partially downloaded models. */
    public static long getUsedSpace() {
        return dirSize(baseDir());
    }

    private static long dirSize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        long sum = 0;
        for (File f : files) {
            sum += f.isDirectory() ? dirSize(f) : f.length();
        }
        return sum;
    }

    public static boolean isOnMeteredNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Queues the model. Returns {@link #ERROR_WIFI_REQUIRED} without queueing when "Wi-Fi only" is on and the
     * network is metered, unless {@code allowMetered} (the user confirmed the download over mobile data).
     */
    public static int download(EasygramAsrModels.Model m, boolean allowMetered) {
        if (!allowMetered && EasygramAsrSettings.isWifiOnly() && isOnMeteredNetwork()) {
            return ERROR_WIFI_REQUIRED;
        }
        synchronized (tasks) {
            if (tasks.containsKey(m.id) || isReady(m)) {
                return ERROR_NONE;
            }
            Task task = new Task(m);
            task.downloaded = partialBytes(m);
            task.allowMetered = allowMetered;
            tasks.put(m.id, task);
            lastErrors.remove(m.id);
            if (worker == null) {
                worker = new Thread(EasygramAsrModelManager::workLoop, "easygramAsrDownload");
                worker.setPriority(Thread.MIN_PRIORITY);
                worker.start();
            }
        }
        notifyChanged(m.id);
        return ERROR_NONE;
    }

    public static void cancel(EasygramAsrModels.Model m) {
        synchronized (tasks) {
            Task t = tasks.get(m.id);
            if (t != null) {
                t.cancelled = true;
                if (!t.started) {
                    tasks.remove(m.id);
                }
            }
        }
        notifyChanged(m.id);
    }

    public static void delete(EasygramAsrModels.Model m) {
        cancel(m);
        EasygramAsrEngine.releaseIfLoaded(m);
        deleteRecursive(modelDir(m));
        synchronized (readyCache) {
            readyCache.put(m.id, false);
        }
        notifyChanged(m.id);
    }

    public static void deleteAll() {
        for (EasygramAsrModels.Model m : EasygramAsrModels.all()) {
            cancel(m);
            EasygramAsrEngine.releaseIfLoaded(m);
        }
        deleteRecursive(baseDir());
        synchronized (readyCache) {
            readyCache.clear();
        }
        for (EasygramAsrModels.Model m : EasygramAsrModels.all()) {
            notifyChanged(m.id);
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static long partialBytes(EasygramAsrModels.Model m) {
        long sum = 0;
        File dir = modelDir(m);
        for (EasygramAsrModels.ModelFile f : m.files) {
            File done = new File(dir, f.name);
            if (done.length() == f.size) {
                sum += f.size;
            } else {
                sum += Math.min(f.size, new File(dir, f.name + ".part").length());
            }
        }
        return sum;
    }

    // worker ------------------------------------------------------------------------------------------------------

    private static void workLoop() {
        while (true) {
            Task task;
            synchronized (tasks) {
                task = null;
                for (Task t : tasks.values()) {
                    if (!t.cancelled) {
                        task = t;
                        break;
                    }
                }
                if (task == null) {
                    tasks.clear();
                    worker = null;
                    return;
                }
                task.started = true;
            }
            notifyChanged(task.model.id);
            int error = ERROR_NONE;
            try {
                error = run(task);
            } catch (Exception e) {
                FileLog.e(e);
                error = ERROR_NETWORK;
            }
            synchronized (tasks) {
                tasks.remove(task.model.id);
                if (error != ERROR_NONE && !task.cancelled) {
                    lastErrors.put(task.model.id, error);
                }
            }
            notifyChanged(task.model.id);
        }
    }

    private static int run(Task task) throws Exception {
        EasygramAsrModels.Model m = task.model;
        File dir = modelDir(m);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return ERROR_NO_SPACE;
        }
        long remaining = m.totalSize() - partialBytes(m);
        if (new StatFs(dir.getAbsolutePath()).getAvailableBytes() < remaining + 64L * 1024 * 1024) {
            return ERROR_NO_SPACE;
        }
        for (EasygramAsrModels.ModelFile f : m.files) {
            if (task.cancelled) {
                return ERROR_NONE;
            }
            File target = new File(dir, f.name);
            if (target.length() == f.size && sha256(target).equals(f.sha256)) {
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            int error = ERROR_NETWORK;
            for (String url : f.urls) {
                error = downloadFile(task, f, url, new File(dir, f.name + ".part"), target);
                if (error == ERROR_NONE || task.cancelled || error == ERROR_WIFI_REQUIRED) {
                    break;
                }
            }
            if (error != ERROR_NONE || task.cancelled) {
                return task.cancelled ? ERROR_NONE : error;
            }
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, "ready"))) {
            out.write(readySignature(m).getBytes(StandardCharsets.UTF_8));
        }
        synchronized (readyCache) {
            readyCache.put(m.id, true);
        }
        return ERROR_NONE;
    }

    private static int downloadFile(Task task, EasygramAsrModels.ModelFile f, String url, File part, File target) {
        long baseline = task.downloaded - Math.min(f.size, part.length());
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection conn = null;
            try {
                long have = part.length();
                if (have > f.size) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    have = 0;
                }
                if (have < f.size) {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "easygram");
                    if (have > 0) {
                        conn.setRequestProperty("Range", "bytes=" + have + "-");
                    }
                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String location = conn.getHeaderField("Location");
                        if (location == null) {
                            return ERROR_NETWORK;
                        }
                        url = new URL(new URL(url), location).toString();
                        continue;
                    }
                    if (code == 416 && have > 0) {
                        // the server says the range is past the end: the part is complete or broken, check below
                    } else if (code != 200 && code != 206) {
                        FileLog.e("easygram asr: HTTP " + code + " for " + url);
                        return ERROR_NETWORK;
                    } else {
                        boolean append = code == 206;
                        if (!append) {
                            have = 0;
                        }
                        task.downloaded = baseline + have;
                        try (InputStream in = conn.getInputStream();
                             RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                            out.setLength(have);
                            out.seek(have);
                            byte[] buf = new byte[64 * 1024];
                            long lastNotify = 0, lastNetCheck = SystemClock.elapsedRealtime();
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                if (task.cancelled) {
                                    return ERROR_NONE;
                                }
                                out.write(buf, 0, n);
                                have += n;
                                task.downloaded = baseline + have;
                                long now = SystemClock.elapsedRealtime();
                                if (now - lastNotify > 250) {
                                    lastNotify = now;
                                    notifyChanged(task.model.id);
                                }
                                if (now - lastNetCheck > 5000) {
                                    lastNetCheck = now;
                                    if (!task.allowMetered && EasygramAsrSettings.isWifiOnly() && isOnMeteredNetwork()) {
                                        return ERROR_WIFI_REQUIRED;
                                    }
                                }
                            }
                        }
                    }
                }
                if (part.length() != f.size || !sha256(part).equals(f.sha256)) {
                    FileLog.e("easygram asr: checksum mismatch for " + f.name + " from " + url);
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    task.downloaded = baseline;
                    return ERROR_CHECKSUM;
                }
                if (!part.renameTo(target)) {
                    return ERROR_NO_SPACE;
                }
                task.downloaded = baseline + f.size;
                return ERROR_NONE;
            } catch (IOException e) {
                FileLog.e(e);
                return ERROR_NETWORK;
            } catch (Exception e) {
                FileLog.e(e);
                return ERROR_NETWORK;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return ERROR_NETWORK;
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) != -1) {
                off += n;
            }
            return data;
        }
    }

    @Nullable
    public static EasygramAsrModels.Model readyModelFor(String lang) {
        EasygramAsrModels.Model selected = EasygramAsrModels.byId(EasygramAsrSettings.getSelectedModelId(lang));
        if (selected != null && isReady(selected)) {
            return selected;
        }
        // the chosen model is not downloaded (yet): any downloaded one of the language, best first
        EasygramAsrModels.Model best = null;
        for (EasygramAsrModels.Model m : EasygramAsrModels.forLang(lang)) {
            if (isReady(m) && (best == null || m.tier > best.tier)) {
                best = m;
            }
        }
        return best;
    }

    private EasygramAsrModelManager() {
    }
}
