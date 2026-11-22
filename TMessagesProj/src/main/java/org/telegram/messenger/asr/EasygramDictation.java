/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger.asr;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures microphone PCM and submits it to the shared local ASR engine. */
public final class EasygramDictation {
    private static final int RATE = EasygramAsrAudio.SAMPLE_RATE;
    private static final int MAX_SAMPLES = 60 * RATE;
    private static final int CHUNK_SAMPLES = 2 * RATE;
    private static final String MODEL = "easygramDictationModel";

    public interface Listener {
        void onText(String text);
        void onAmplitude(double amplitude);
        void onCaptureStopped();
        void onFinished(boolean failed);
    }

    private static SharedPreferences settings() {
        return MessagesController.getGlobalMainSettings();
    }

    @Nullable
    public static EasygramAsrModels.Model getModel() {
        return EasygramAsrModels.byId(settings().getString(MODEL, null));
    }

    public static void setModel(EasygramAsrModels.Model model) {
        settings().edit().putString(MODEL, model.id).apply();
    }

    public static boolean isLive() {
        return false;
    }

    private final EasygramAsrModels.Model model;
    private final boolean live;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean stopped;
    // Accessed only on the recognizer queue.
    private boolean decodeFailed;

    public EasygramDictation(EasygramAsrModels.Model model, boolean live, Listener listener) {
        this.model = model;
        this.live = live;
        this.listener = listener;
    }

    public void start() {
        new Thread(this::capture, "easygramDictation").start();
    }

    public void stop() {
        stopped = true;
    }

    public void cancel() {
        cancelled.set(true);
        stop();
    }

    @SuppressLint("MissingPermission") // The composer checks RECORD_AUDIO before starting.
    private void capture() {
        AudioRecord recorder = null;
        boolean failed = false;
        try {
            if (stopped) {
                return;
            }
            int minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                throw new IllegalStateException("Unsupported dictation audio format");
            }
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, RATE / 5));
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Dictation microphone did not start");
            }
            short[] input = new short[RATE / 10];
            float[] segment = new float[live ? CHUNK_SAMPLES : MAX_SAMPLES];
            int total = 0;
            int size = 0;
            while (!stopped && total < MAX_SAMPLES) {
                int read = recorder.read(input, 0, Math.min(input.length, segment.length - size));
                if (read < 0) {
                    throw new IllegalStateException("Dictation microphone read failed: " + read);
                }
                double energy = 0;
                for (int i = 0; i < read; i++) {
                    short sample = input[i];
                    segment[size++] = sample / 32768f;
                    energy += (double) sample * sample;
                }
                if (read > 0) {
                    double amplitude = Math.sqrt(energy / read);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!stopped && !cancelled.get()) {
                            listener.onAmplitude(amplitude);
                        }
                    });
                }
                total += read;
                if (size == segment.length) {
                    if (!live) {
                        break;
                    }
                    int split = quietSplit(segment);
                    submit(Arrays.copyOf(segment, split));
                    size -= split;
                    System.arraycopy(segment, split, segment, 0, size);
                }
            }
            if (size > 0 && !cancelled.get()) {
                submit(Arrays.copyOf(segment, size));
            }
        } catch (Exception e) {
            FileLog.e(e);
            failed = true;
        } finally {
            if (recorder != null) {
                recorder.release();
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled.get()) {
                    listener.onCaptureStopped();
                }
            });
            final boolean captureFailed = failed;
            EasygramAsrEngine.queue.postRunnable(() -> {
                final boolean error = captureFailed || decodeFailed;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!cancelled.get()) {
                        listener.onFinished(error);
                    }
                });
            });
        }
    }

    // Prefer a quiet frame near the end of a chunk to reduce cuts in the middle of words.
    private static int quietSplit(float[] samples) {
        int frame = RATE / 50;
        int split = samples.length - frame / 2;
        float quietest = Float.MAX_VALUE;
        for (int offset = samples.length - frame; offset >= samples.length - RATE / 2; offset -= frame) {
            float energy = 0;
            for (int i = offset; i < offset + frame; i++) {
                energy += samples[i] * samples[i];
            }
            if (energy < quietest) {
                quietest = energy;
                split = offset + frame / 2;
            }
        }
        return split;
    }

    private void submit(float[] samples) {
        EasygramAsrEngine.queue.postRunnable(() -> {
            if (cancelled.get() || decodeFailed) {
                return;
            }
            try {
                if (!EasygramAsrModelManager.isReady(model)) {
                    decodeFailed = true;
                    return;
                }
                String text = EasygramAsrEngine.transcribe(model, samples, cancelled, null);
                if (text != null && !text.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!cancelled.get()) {
                            listener.onText(text);
                        }
                    });
                }
            } catch (Throwable e) {
                FileLog.e(e);
                decodeFailed = true;
            }
        });
    }
}
