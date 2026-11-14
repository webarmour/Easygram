/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.LruCache;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Builds a loudness waveform for music / audio files (Telegram only ships waveforms for voice messages).
 *
 * The file must already be fully downloaded. Instead of decoding the whole track, the decoder seeks to
 * {@link #BUCKETS} evenly spaced points and measures the RMS loudness of a short window at each one, which
 * takes well under a second for a typical song. Levels are mapped to dB and stretched between the quiet and
 * loud percentiles of the track, so dynamics (quiet intro, loud chorus) stay clearly visible even on heavily
 * mastered music. Results are cached in memory and on disk.
 */
public class AudioWaveformGenerator {

    public static final int BUCKETS = 150;

    private static final int WINDOW_SAMPLES = 4096;
    private static final long TOTAL_TIMEOUT_MS = 8000;
    private static final long DEQUEUE_TIMEOUT_US = 5000;

    private static final DispatchQueue queue = new DispatchQueue("easygramWaveformQueue");
    private static final LruCache<String, float[]> memoryCache = new LruCache<>(64);
    private static final HashSet<String> pending = new HashSet<>();
    private static final HashSet<String> failed = new HashSet<>();
    private static final java.util.HashMap<String, Long> retryAfter = new java.util.HashMap<>();
    private static final long NOT_READY_RETRY_MS = 3000;

    /** Cached levels (0..1, {@link #BUCKETS} values) or null. Main thread. */
    public static float[] get(MessageObject messageObject) {
        String key = key(messageObject);
        return key == null ? null : memoryCache.get(key);
    }

    /**
     * Starts generation if the file is downloaded and nothing is cached yet; {@code onReady} runs on the
     * main thread once levels are available. Main thread.
     */
    public static void request(MessageObject messageObject, Runnable onReady) {
        String key = key(messageObject);
        if (key == null || memoryCache.get(key) != null || pending.contains(key) || failed.contains(key)) {
            return;
        }
        Long retry = retryAfter.get(key);
        if (retry != null && SystemClock.elapsedRealtime() < retry) {
            return; // file was not fully downloaded recently; check again a bit later
        }
        pending.add(key);
        final TLRPC.Message message = messageObject.messageOwner;
        final int account = messageObject.currentAccount;
        final long expectedSize = messageObject.getDocument().size;
        final long durationUs = (long) (messageObject.getDuration() * 1_000_000L);
        queue.postRunnable(() -> {
            float[] levels = readDiskCache(key);
            boolean fileReady = levels != null;
            if (levels == null) {
                File file = null;
                if (message.attachPath != null && message.attachPath.length() > 0) {
                    File f = new File(message.attachPath);
                    if (f.exists()) file = f;
                }
                if (file == null) {
                    file = FileLoader.getInstance(account).getPathToMessage(message);
                }
                if (file != null && file.exists() && (expectedSize <= 0 || file.length() >= expectedSize)) {
                    fileReady = true;
                    try {
                        levels = generate(file.getAbsolutePath(), durationUs);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    if (levels != null) {
                        writeDiskCache(key, levels);
                    }
                }
            }
            final float[] result = levels;
            final boolean triedFile = fileReady;
            AndroidUtilities.runOnUIThread(() -> {
                pending.remove(key);
                if (result != null) {
                    memoryCache.put(key, result);
                    if (onReady != null) onReady.run();
                } else if (triedFile) {
                    failed.add(key); // decoding failed: don't retry on every frame
                } else {
                    retryAfter.put(key, SystemClock.elapsedRealtime() + NOT_READY_RETRY_MS);
                }
                // file not fully downloaded yet: a later draw will request again
            });
        });
    }

    private static String key(MessageObject messageObject) {
        if (messageObject == null) return null;
        TLRPC.Document document = messageObject.getDocument();
        if (document == null || document.id == 0) return null;
        return document.id + "_" + document.size;
    }

    private static float[] generate(String path, long fallbackDurationUs) throws Exception {
        long startTime = SystemClock.elapsedRealtime();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) return null;
            extractor.selectTrack(track);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : fallbackDurationUs;
            if (durationUs <= 0) return null;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            boolean floatPcm = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            double[] rms = new double[BUCKETS];
            for (int b = 0; b < BUCKETS; b++) {
                if (SystemClock.elapsedRealtime() - startTime > TOTAL_TIMEOUT_MS) {
                    return null;
                }
                long t = (long) (durationUs * (b + 0.5) / BUCKETS);
                extractor.seekTo(t, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                codec.flush();

                double sumSquares = 0;
                int collected = 0;
                boolean inputDone = false;
                int iterations = 0;
                while (collected < WINDOW_SAMPLES && iterations++ < 200) {
                    if (!inputDone) {
                        int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                        if (inIndex >= 0) {
                            ByteBuffer in = codec.getInputBuffer(inIndex);
                            int size = in == null ? -1 : extractor.readSampleData(in, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outFormat = codec.getOutputFormat();
                        floatPcm = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                && outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    } else if (outIndex >= 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        if (out != null && info.size > 0) {
                            out.position(info.offset);
                            out.limit(info.offset + info.size);
                            ByteBuffer pcm = out.slice().order(ByteOrder.nativeOrder());
                            if (floatPcm) {
                                while (pcm.remaining() >= 4 && collected < WINDOW_SAMPLES) {
                                    double v = pcm.getFloat();
                                    sumSquares += v * v;
                                    collected++;
                                }
                            } else {
                                while (pcm.remaining() >= 2 && collected < WINDOW_SAMPLES) {
                                    double v = pcm.getShort() / 32768.0;
                                    sumSquares += v * v;
                                    collected++;
                                }
                            }
                        }
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(outIndex, false);
                        if (eos) break;
                    }
                }
                rms[b] = collected > 0 ? Math.sqrt(sumSquares / collected) : 0;
            }
            return normalize(rms);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignore) {}
                try { codec.release(); } catch (Throwable ignore) {}
            }
            extractor.release();
        }
    }

    private static float[] normalize(double[] rms) {
        final double silence = 1e-4; // ~ -80 dBFS
        double[] db = new double[rms.length];
        for (int i = 0; i < rms.length; i++) {
            db[i] = rms[i] > silence ? 20 * Math.log10(rms[i]) : Double.NaN;
        }
        int valid = 0;
        for (double v : db) if (!Double.isNaN(v)) valid++;
        double[] sorted = new double[valid];
        valid = 0;
        for (double v : db) if (!Double.isNaN(v)) sorted[valid++] = v;
        Arrays.sort(sorted);
        float[] levels = new float[rms.length];
        if (sorted.length == 0) {
            return levels; // all silence
        }
        // Stretch between the quiet (10th pct) and loud (97th pct) parts of the track, then apply a contrast
        // curve so quiet passages become clearly flat and loud ones clearly tall.
        double lo = sorted[(int) (sorted.length * 0.10)];
        double hi = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.97))];
        // keep at least a 9 dB span so a uniformly loud track does not turn into noise
        lo = Math.min(lo, hi - 9);
        for (int i = 0; i < rms.length; i++) {
            if (Double.isNaN(db[i])) {
                levels[i] = 0;
            } else {
                float v = (float) ((db[i] - lo) / (hi - lo));
                v = Math.max(0f, Math.min(1f, v));
                levels[i] = Math.max(0.04f, (float) Math.pow(v, 1.7));
            }
        }
        return levels;
    }

    private static File cacheFile(String key) {
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "easygram_waveforms_v2");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, key + ".wf");
    }

    private static float[] readDiskCache(String key) {
        File file = cacheFile(key);
        if (!file.exists() || file.length() != BUCKETS) return null;
        byte[] data = new byte[BUCKETS];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(data) != BUCKETS) return null;
        } catch (Exception e) {
            return null;
        }
        float[] levels = new float[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            levels[i] = (data[i] & 0xff) / 255f;
        }
        return levels;
    }

    private static void writeDiskCache(String key, float[] levels) {
        byte[] data = new byte[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            data[i] = (byte) Math.round(Math.max(0, Math.min(1, levels[i])) * 255);
        }
        try (FileOutputStream out = new FileOutputStream(cacheFile(key))) {
            out.write(data);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
