/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger.asr;

import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification;
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig;
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs sherpa-onnx on its own low-priority thread. One model is kept loaded at a time and released after
 * {@link #IDLE_UNLOAD_MS} without work, or as soon as the system asks for memory.
 */
public final class EasygramAsrEngine {

    private static final long IDLE_UNLOAD_MS = 90_000;
    /** Offline models are fed segments of at most this length, cut at the quietest moment. */
    private static final int MAX_SEGMENT_SEC = 25, MIN_SEGMENT_SEC = 15;
    /** Gentle mode: shorter segments, each followed by a rest of this part of its decode time. */
    private static final int GENTLE_MAX_SEGMENT_SEC = 10, GENTLE_MIN_SEGMENT_SEC = 6;
    private static final float GENTLE_REST = 0.5f;

    public static final DispatchQueue queue = new DispatchQueue("easygramAsr", true, Process.THREAD_PRIORITY_BACKGROUND);

    // touched only on the queue
    @Nullable
    private static EasygramAsrModels.Model loadedModel;
    private static int loadedThreads;
    @Nullable
    private static OfflineRecognizer offline;
    @Nullable
    private static OnlineRecognizer online;
    @Nullable
    private static SpokenLanguageIdentification lid;

    /** The language identification model looks at most at this much of the start of the message. */
    private static final int LID_MAX_SEC = 20;

    /**
     * Must be called on {@link #queue}. The language of the speech as a Whisper code ("ru", "en", "uk"...), or null
     * when the model is not downloaded or fails.
     */
    @Nullable
    static String identifyLanguage(float[] samples) {
        EasygramAsrModels.Model model = EasygramAsrModels.byId(EasygramAsrModels.LID_MODEL_ID);
        if (model == null || !EasygramAsrModelManager.isReady(model)) {
            return null;
        }
        queue.cancelRunnable(unloadRunnable);
        try {
            long start = SystemClock.elapsedRealtime();
            if (lid == null) {
                File dir = EasygramAsrModelManager.modelDir(model);
                SpokenLanguageIdentificationWhisperConfig whisper = new SpokenLanguageIdentificationWhisperConfig();
                whisper.setEncoder(new File(dir, "tiny-encoder.int8.onnx").getAbsolutePath());
                whisper.setDecoder(new File(dir, "tiny-decoder.int8.onnx").getAbsolutePath());
                SpokenLanguageIdentificationConfig config = new SpokenLanguageIdentificationConfig();
                config.setWhisper(whisper);
                config.setNumThreads(Math.min(2, EasygramAsrSettings.getThreads()));
                config.setProvider("cpu");
                lid = new SpokenLanguageIdentification(null, config);
            }
            int n = Math.min(samples.length, LID_MAX_SEC * EasygramAsrAudio.SAMPLE_RATE);
            float[] head = n == samples.length ? samples : java.util.Arrays.copyOf(samples, n);
            OfflineStream stream = lid.createStream();
            String lang;
            try {
                stream.acceptWaveform(head, EasygramAsrAudio.SAMPLE_RATE);
                lang = lid.compute(stream);
            } finally {
                stream.release();
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("easygram asr: voice language " + lang + " in " + (SystemClock.elapsedRealtime() - start) + "ms");
            }
            return lang;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            queue.postRunnable(unloadRunnable, IDLE_UNLOAD_MS);
        }
    }

    private static final Runnable unloadRunnable = EasygramAsrEngine::release;

    public interface Progress {
        /** 0..1, called on the engine thread. */
        void onProgress(float progress);
    }

    /** Must be called on {@link #queue}. Returns the text (possibly empty) or null when cancelled. */
    @Nullable
    static String transcribe(EasygramAsrModels.Model model, float[] samples, AtomicBoolean cancelled, @Nullable Progress progress) throws Exception {
        queue.cancelRunnable(unloadRunnable);
        try {
            long start = SystemClock.elapsedRealtime();
            ensureLoaded(model);
            long loaded = SystemClock.elapsedRealtime();
            String text;
            if (model.engine == EasygramAsrModels.ENGINE_TONE_CTC) {
                text = runOnline(samples, cancelled, progress);
            } else {
                text = runOffline(samples, cancelled, progress);
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("easygram asr: " + model.id + " " + (samples.length / (float) EasygramAsrAudio.SAMPLE_RATE) + "s audio, load "
                        + (loaded - start) + "ms, decode " + (SystemClock.elapsedRealtime() - loaded) + "ms, threads " + loadedThreads);
            }
            return text == null ? null : postProcess(model, text);
        } finally {
            queue.postRunnable(unloadRunnable, IDLE_UNLOAD_MS);
        }
    }

    private static boolean memoryCallbacksRegistered;

    private static void ensureLoaded(EasygramAsrModels.Model model) {
        if (!memoryCallbacksRegistered) {
            memoryCallbacksRegistered = true;
            ApplicationLoader.applicationContext.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                        onLowMemory();
                    }
                }

                @Override
                public void onConfigurationChanged(@NonNull Configuration newConfig) {
                }

                @Override
                public void onLowMemory() {
                    EasygramAsrEngine.onLowMemory();
                }
            });
        }
        int threads = EasygramAsrSettings.getThreads();
        if (loadedModel == model && loadedThreads == threads && (offline != null || online != null)) {
            return;
        }
        releaseRecognizer(); // the language identification model stays
        File dir = EasygramAsrModelManager.modelDir(model);
        String tokens = new File(dir, "tokens.txt").getAbsolutePath();
        if (model.engine == EasygramAsrModels.ENGINE_TONE_CTC) {
            OnlineModelConfig mc = new OnlineModelConfig();
            mc.setToneCtc(new OnlineToneCtcModelConfig(new File(dir, "model.onnx").getAbsolutePath()));
            mc.setTokens(tokens);
            mc.setNumThreads(threads);
            mc.setProvider("cpu");
            OnlineRecognizerConfig config = new OnlineRecognizerConfig();
            config.setFeatConfig(new FeatureConfig(EasygramAsrAudio.SAMPLE_RATE, 80, 0f));
            config.setModelConfig(mc);
            config.setEnableEndpoint(false);
            config.setDecodingMethod("greedy_search");
            online = new OnlineRecognizer(null, config);
        } else {
            OfflineModelConfig mc = new OfflineModelConfig();
            switch (model.engine) {
                case EasygramAsrModels.ENGINE_NEMO_CTC:
                    mc.setNemo(new OfflineNemoEncDecCtcModelConfig(new File(dir, "model.int8.onnx").getAbsolutePath()));
                    break;
                case EasygramAsrModels.ENGINE_MOONSHINE_V2: {
                    OfflineMoonshineModelConfig moonshine = new OfflineMoonshineModelConfig();
                    moonshine.setEncoder(new File(dir, "encoder_model.ort").getAbsolutePath());
                    moonshine.setMergedDecoder(new File(dir, "decoder_model_merged.ort").getAbsolutePath());
                    mc.setMoonshine(moonshine);
                    break;
                }
                case EasygramAsrModels.ENGINE_NEMO_TRANSDUCER: {
                    OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
                    transducer.setEncoder(new File(dir, "encoder.int8.onnx").getAbsolutePath());
                    transducer.setDecoder(new File(dir, "decoder.int8.onnx").getAbsolutePath());
                    transducer.setJoiner(new File(dir, "joiner.int8.onnx").getAbsolutePath());
                    mc.setTransducer(transducer);
                    mc.setModelType("nemo_transducer");
                    break;
                }
            }
            mc.setTokens(tokens);
            mc.setNumThreads(threads);
            mc.setProvider("cpu");
            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setFeatConfig(new FeatureConfig(EasygramAsrAudio.SAMPLE_RATE, 80, 0f));
            config.setModelConfig(mc);
            config.setDecodingMethod("greedy_search");
            offline = new OfflineRecognizer(null, config);
        }
        loadedModel = model;
        loadedThreads = threads;
    }

    @Nullable
    private static String runOffline(float[] samples, AtomicBoolean cancelled, @Nullable Progress progress) {
        OfflineRecognizer recognizer = offline;
        if (recognizer == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        int rate = EasygramAsrAudio.SAMPLE_RATE;
        int start = 0;
        final boolean gentle = EasygramAsrSettings.isGentle();
        while (start < samples.length) {
            if (cancelled.get()) {
                return null;
            }
            long segmentStart = SystemClock.elapsedRealtime();
            int end = gentle ? segmentEnd(samples, start, GENTLE_MIN_SEGMENT_SEC, GENTLE_MAX_SEGMENT_SEC)
                    : segmentEnd(samples, start, MIN_SEGMENT_SEC, MAX_SEGMENT_SEC);
            float[] segment = new float[end - start];
            System.arraycopy(samples, start, segment, 0, segment.length);
            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(segment, rate);
                recognizer.decode(stream);
                String part = recognizer.getResult(stream).getText().trim();
                if (!part.isEmpty()) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(part);
                }
            } finally {
                stream.release();
            }
            start = end;
            if (progress != null) {
                progress.onProgress(start / (float) samples.length);
            }
            if (gentle && start < samples.length) {
                rest(SystemClock.elapsedRealtime() - segmentStart);
            }
        }
        return text.toString();
    }

    /** Gentle mode: lets the CPU cool down for a part of the time the last piece of work took. */
    private static void rest(long workMs) {
        try {
            Thread.sleep(Math.min(3000, (long) (workMs * GENTLE_REST)));
        } catch (InterruptedException ignore) {
        }
    }

    /** End of the segment starting at {@code start}: the quietest 30 ms between minSec and maxSec in. */
    private static int segmentEnd(float[] samples, int start, int minSec, int maxSec) {
        int rate = EasygramAsrAudio.SAMPLE_RATE;
        if (samples.length - start <= maxSec * rate) {
            return samples.length;
        }
        int frame = rate * 30 / 1000;
        int from = start + minSec * rate, to = start + maxSec * rate - frame;
        int best = to;
        double bestEnergy = Double.MAX_VALUE;
        for (int f = from; f <= to; f += frame / 2) {
            double energy = 0;
            for (int i = f; i < f + frame; i++) {
                energy += samples[i] * samples[i];
            }
            if (energy < bestEnergy) {
                bestEnergy = energy;
                best = f + frame / 2;
            }
        }
        return best;
    }

    @Nullable
    private static String runOnline(float[] samples, AtomicBoolean cancelled, @Nullable Progress progress) {
        OnlineRecognizer recognizer = online;
        if (recognizer == null) {
            return "";
        }
        OnlineStream stream = recognizer.createStream("");
        try {
            int chunk = EasygramAsrAudio.SAMPLE_RATE; // 1 s
            float[] buf = new float[chunk];
            final boolean gentle = EasygramAsrSettings.isGentle();
            long workStart = SystemClock.elapsedRealtime();
            for (int pos = 0; pos < samples.length; pos += chunk) {
                if (cancelled.get()) {
                    return null;
                }
                if (gentle && pos > 0 && pos % (chunk * 5) == 0) {
                    long now = SystemClock.elapsedRealtime();
                    rest(now - workStart); // every 5 seconds of audio
                    workStart = SystemClock.elapsedRealtime();
                }
                int n = Math.min(chunk, samples.length - pos);
                if (n != buf.length) {
                    buf = new float[n];
                }
                System.arraycopy(samples, pos, buf, 0, n);
                stream.acceptWaveform(buf, EasygramAsrAudio.SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }
                if (progress != null) {
                    progress.onProgress((pos + n) / (float) samples.length);
                }
            }
            // a little silence so the model flushes its last frames
            stream.acceptWaveform(new float[EasygramAsrAudio.SAMPLE_RATE / 2], EasygramAsrAudio.SAMPLE_RATE);
            stream.inputFinished();
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            return recognizer.getResult(stream).getText().trim();
        } finally {
            stream.release();
        }
    }

    private static String postProcess(EasygramAsrModels.Model model, String text) {
        text = text.replaceAll("\\s+", " ").trim();
        if (!model.punctuation && !text.isEmpty()) {
            // T-one writes lower case without punctuation: at least start with a capital letter
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        return text;
    }

    /** Releases the loaded recognizer; runs on the queue. */
    private static void release() {
        if (lid != null) {
            lid.release();
            lid = null;
        }
        releaseRecognizer();
    }

    private static void releaseRecognizer() {
        if (offline != null) {
            offline.release();
            offline = null;
        }
        if (online != null) {
            online.release();
            online = null;
        }
        loadedModel = null;
    }

    public static void releaseIfLoaded(EasygramAsrModels.Model model) {
        queue.postRunnable(() -> {
            if (model.engine == EasygramAsrModels.ENGINE_WHISPER_LID) {
                if (lid != null) {
                    lid.release();
                    lid = null;
                }
            } else if (loadedModel == model) {
                releaseRecognizer();
            }
        });
    }

    /** The system is short of memory: drop the model now instead of after the idle timeout. */
    static void onLowMemory() {
        queue.postRunnable(EasygramAsrEngine::release);
    }

    private EasygramAsrEngine() {
    }
}
