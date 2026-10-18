package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.ViewGroup;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;

import java.lang.ref.WeakReference;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent APK updater; Telegram API ID/hash and login are not required. */
public final class EasygramUpdater {
    // Public GitHub feed in this repository; no GitHub access token is required.
    private static final String FEED_BASE =
            "https://raw.githubusercontent.com/webarmour/Easygram/easygram-dev/updates/";
    private static final long CHECK_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 300L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 128 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
    private static WeakReference<UpdateLayout> layoutRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> hostRef = new WeakReference<>(null);
    private static volatile Update pendingUpdate;
    private static volatile boolean downloaded;
    private static volatile int progress;

    private EasygramUpdater() { }

    private static final class Update {
        long versionCode;
        String versionName;
        String packageName;
        String url;
        String sha256;
        String changelog;
    }

    /** Called on the main thread after the activity's main container has been created. */
    public static UpdateLayout attach(Activity activity, ViewGroup host) {
        if (activity == null || host == null || activity.isFinishing()) return null;
        UpdateLayout layout = layoutRef.get();
        if (layout == null || hostRef.get() != host) {
            // Use MainTabsActivity native UpdateLayoutWrapper; do not overlay bottom tabs.
            layout = new UpdateLayout(activity, host);
            layout.setEasygramClickListener(() -> onPanelClicked(activity));
            // Attaching the hidden view keeps the layout alive until an update arrives.
            layout.createUpdateUI(0);
            layoutRef = new WeakReference<>(layout);
            hostRef = new WeakReference<>(host);
        }
        if (pendingUpdate == null) {
            try {
                String saved = activity.getApplicationContext()
                        .getSharedPreferences("easygram_updater", Context.MODE_PRIVATE)
                        .getString("last_manifest", null);
                if (!TextUtils.isEmpty(saved)) {
                    Update cached = parseUpdate(new JSONObject(saved));
                    if (activity.getPackageName().equals(cached.packageName)
                            && cached.versionCode > currentVersionCode(activity)
                            && validUpdate(cached)) {
                        pendingUpdate = cached;
                    }
                }
            } catch (Exception ignored) {
                // Ignore an invalid or outdated cached manifest.
            }
        }
        render();
        return layout;
    }

    private static Update parseUpdate(JSONObject json) throws Exception {
        Update update = new Update();
        update.versionCode = json.getLong("version_code");
        update.versionName = json.optString("version_name", "");
        update.packageName = json.optString("package_name", "");
        update.url = json.optString("apk_url", "");
        update.sha256 = json.optString("sha256", "").toLowerCase(Locale.ROOT);
        update.changelog = json.optString("changelog", "");
        return update;
    }

    private static boolean validUpdate(Update update) {
        return update.url.startsWith("https://") && update.sha256.matches("[0-9a-f]{64}");
    }

    private static void render() {
        UpdateLayout layout = layoutRef.get();
        if (layout != null) {
            layout.updateEasygramState(pendingUpdate != null, DOWNLOADING.get(), progress, downloaded);
        }
    }

    private static void onPanelClicked(Activity activity) {
        if (DOWNLOADING.get()) {
            CANCEL.set(true);
            return;
        }
        Update update = pendingUpdate;
        if (update == null) return;
        if (downloaded) {
            install(activity, targetFile(activity.getApplicationContext(), update));
        } else {
            download(activity, update);
        }
    }

    private static File targetFile(Context app, Update update) {
        return new File(new File(app.getFilesDir(), "cache/updates"),
                "easygram-" + update.versionCode + ".apk");
    }

    /** Called on LaunchActivity.onResume() and from SettingsActivity. */
    public static void check(Activity activity, boolean manual) {
        if (activity == null || activity.isFinishing()) return;
        Context app = activity.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("easygram_updater", Context.MODE_PRIVATE);
        if (!manual && System.currentTimeMillis() - prefs.getLong("checked_at", 0) < CHECK_INTERVAL_MS) {
            return;
        }
        if (!CHECKING.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            try {
                String name = app.getPackageName();
                String channel = name.endsWith(".beta") ? "beta" : name.endsWith(".web") ? "web" : "stable";
                String response = fetchText(FEED_BASE + channel + ".json");
                Update update = parseUpdate(new JSONObject(response));
                if (!name.equals(update.packageName)) {
                    throw new IllegalStateException("Канал обновлений настроен для другого пакета: " + update.packageName);
                }
                if (update.versionCode > currentVersionCode(app) && !validUpdate(update)) {
                    throw new IllegalStateException("Манифест новой версии неполный или небезопасный.");
                }
                prefs.edit().putLong("checked_at", System.currentTimeMillis())
                        .putString("last_manifest", response).apply();
                if (update.versionCode <= currentVersionCode(app)) {
                    pendingUpdate = null;
                    downloaded = false;
                    onUi(activity, () -> {
                        render();
                        if (manual) info(activity, "Обновления Easygram", "Установлена актуальная версия.");
                    });
                    return;
                }
                if (pendingUpdate == null || pendingUpdate.versionCode != update.versionCode
                        || !pendingUpdate.sha256.equals(update.sha256)) {
                    downloaded = false;
                }
                pendingUpdate = update;
                onUi(activity, EasygramUpdater::render);
            } catch (Exception e) {
                android.util.Log.w("EasygramUpdater", "Failed to check for updates", e);
                if (manual) onUi(activity, () -> info(activity, "Не удалось проверить обновления", reason(e)));
            } finally {
                CHECKING.set(false);
            }
        });
    }

    private static void download(Activity activity, Update update) {
        if (!DOWNLOADING.compareAndSet(false, true)) return;
        CANCEL.set(false);
        progress = 0;
        render();
        // Only application Context is held during the long-running network request.
        Context app = activity.getApplicationContext();
        WeakReference<Activity> activityRef = new WeakReference<>(activity);
        EXECUTOR.execute(() -> {
            try {
                File target = targetFile(app, update);
                File dir = target.getParentFile();
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Не удалось создать папку обновлений.");
                if (!target.isFile() || !update.sha256.equals(sha256(target))) {
                    File temp = new File(dir, "incoming.part");
                    try {
                        fetchApk(update.url, temp);
                        if (CANCEL.get()) throw new CancellationException();
                        if (!update.sha256.equals(sha256(temp))) {
                            throw new SecurityException("SHA-256 скачанного APK не совпадает с релизом.");
                        }
                        if (target.exists() && !target.delete()) {
                            throw new IllegalStateException("Не удалось заменить старый файл.");
                        }
                        if (!temp.renameTo(target)) {
                            throw new IllegalStateException("Не удалось сохранить APK.");
                        }
                    } finally {
                        temp.delete();
                    }
                }
                if (CANCEL.get()) throw new CancellationException();
                verifyApk(app, target, update.versionCode);
                downloaded = true;
                Activity current = activityRef.get();
                if (current != null) onUi(current, () -> install(current, target));
            } catch (CancellationException ignored) {
                // Cancellation is not an error.
            } catch (Exception e) {
                android.util.Log.e("EasygramUpdater", "Unable to download/update", e);
                Activity current = activityRef.get();
                if (current != null) onUi(current, () -> info(current, "Ошибка обновления", reason(e)));
            } finally {
                DOWNLOADING.set(false);
                MAIN.post(EasygramUpdater::render);
            }
        });
    }

    private static void install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            info(activity, "Разрешение на установку", "Разреши Easygram устанавливать приложения, затем снова открой проверку обновлений. Файл уже скачан.");
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try { activity.startActivity(settings); }
            catch (Exception ignored) { }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            info(activity, "Ошибка установки", reason(e));
        }
    }

    private static void verifyApk(Context context, File apk, long expectedVersion) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("Неверный пакет внутри APK.");
        }
        long version = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode() : archive.versionCode;
        if (version != expectedVersion || version <= currentVersionCode(context)) {
            throw new SecurityException("Version Code APK не совпадает с опубликованной версией.");
        }
        Signature[] source = signatures(installed);
        Signature[] destination = signatures(archive);
        if (source == null || destination == null || source.length != destination.length || source.length == 0) {
            throw new SecurityException("Не удалось проверить подпись APK.");
        }
        // Fail closed if signing certificates differ; supports the normal single-signer scenario.
        for (Signature dest : destination) {
            boolean found = false;
            for (Signature src : source) {
                if (MessageDigest.isEqual(dest.toByteArray(), src.toByteArray())) {
                    found = true;
                    break;
                }
            }
            if (!found) throw new SecurityException("Сертификат APK отличается от установленного приложения.");
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.signingInfo == null ? null
                    : packageInfo.signingInfo.getApkContentsSigners();
        }
        return packageInfo.signatures;
    }

    @SuppressWarnings("deprecation")
    private static long currentVersionCode(Context context) throws PackageManager.NameNotFoundException {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static String fetchText(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (bytes.size() + read > MAX_JSON_BYTES) throw new IllegalStateException("Манифест слишком большой.");
                bytes.write(chunk, 0, read);
            }
            return bytes.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private static void fetchApk(String url, File destination) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            long length = connection.getContentLengthLong();
            if (length > MAX_APK_BYTES) throw new IllegalStateException("APK превышает максимальный размер.");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int lastPercent = -1;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (CANCEL.get()) throw new CancellationException();
                    total += read;
                    if (total > MAX_APK_BYTES) throw new IllegalStateException("APK превышает максимальный размер.");
                    output.write(buffer, 0, read);
                    if (length > 0) {
                        int percent = (int) (total * 100 / length);
                        if (percent >= lastPercent + 1) {
                            lastPercent = percent;
                            progress = Math.min(100, percent);
                            MAIN.post(EasygramUpdater::render);
                        }
                    }
                }
                if (total == 0) throw new IllegalStateException("Сервер вернул пустой APK.");
                output.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        if (!url.startsWith("https://")) throw new SecurityException("Обновления доступны только по HTTPS.");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Easygram-Updater/1");
        if (connection.getResponseCode() != 200) {
            int code = connection.getResponseCode();
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code + " при загрузке обновления.");
        }
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            connection.disconnect();
            throw new SecurityException("Сервер перенаправил загрузку на небезопасный адрес.");
        }
        return connection;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void onUi(Activity activity, Runnable action) {
        MAIN.post(() -> {
            if (!activity.isFinishing() && !(Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) action.run();
        });
    }

    private static void info(Activity activity, String title, String message) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Понятно", null)
                .show();
    }
}
