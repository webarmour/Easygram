/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger.asr;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes a voice message (Ogg/Opus, through the opusfile reader in libtmessages) or a round video (its audio
 * track, through MediaCodec) into 16 kHz mono float PCM, the input of every recognizer.
 */
public final class EasygramAsrAudio {

    public static final int SAMPLE_RATE = 16000;

    /** Longest audio transcribed locally; 10 minutes of 16 kHz float PCM take 38 MB. */
    public static final int MAX_SECONDS = 10 * 60;

    static native long nOpenOpus(String path);

    static native long nTotalOpusSamples(long handle);

    static native int nReadOpus(long handle, float[] out);

    static native void nCloseOpus(long handle);

    public static final class TooLongException extends Exception {
    }

    /** Returns null when the file cannot be decoded or decoding was cancelled. */
    @Nullable
    public static float[] decode(File file, AtomicBoolean cancelled) throws TooLongException {
        long opus = nOpenOpus(file.getAbsolutePath());
        if (opus != 0) {
            try {
                return decodeOpus(opus, cancelled);
            } finally {
                nCloseOpus(opus);
            }
        }
        return decodeWithMediaCodec(file, cancelled);
    }

    @Nullable
    private static float[] decodeOpus(long handle, AtomicBoolean cancelled) throws TooLongException {
        long total = nTotalOpusSamples(handle);
        if (total > 48000L * MAX_SECONDS) {
            throw new TooLongException();
        }
        Output out = new Output(48000, total > 0 ? (int) (total / 3) + 16 : SAMPLE_RATE * 30);
        float[] buf = new float[48000];
        while (true) {
            if (cancelled.get()) {
                return null;
            }
            int n = nReadOpus(handle, buf);
            if (n < 0) {
                FileLog.e("easygram asr: opus decode error " + n);
                return out.size > 0 ? out.finish() : null;
            }
            if (n == 0) {
                break;
            }
            out.write(buf, n);
            if (out.size > SAMPLE_RATE * MAX_SECONDS) {
                throw new TooLongException();
            }
        }
        return out.finish();
    }

    @Nullable
    private static float[] decodeWithMediaCodec(File file, AtomicBoolean cancelled) throws TooLongException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
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
            if (track < 0) {
                return null;
            }
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > MAX_SECONDS * 1_000_000L) {
                throw new TooLongException();
            }
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean floatPcm = false;
            Output out = null;
            float[] mono = new float[0];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
                if (cancelled.get()) {
                    return null;
                }
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                    if (out != null && out.size == 0) {
                        out = null; // recreated below with the real rate
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        if (out == null) {
                            out = new Output(rate, SAMPLE_RATE * 30);
                        }
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        ByteBuffer pcm = buffer.slice().order(ByteOrder.nativeOrder());
                        int frames = info.size / ((floatPcm ? 4 : 2) * Math.max(1, channels));
                        if (mono.length < frames) {
                            mono = new float[frames];
                        }
                        for (int i = 0; i < frames; i++) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) {
                                sum += floatPcm ? pcm.getFloat() : pcm.getShort() / 32768f;
                            }
                            mono[i] = sum / channels;
                        }
                        out.write(mono, frames);
                        if (out.size > SAMPLE_RATE * MAX_SECONDS) {
                            throw new TooLongException();
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            return out == null ? null : out.finish();
        } catch (TooLongException e) {
            throw e;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignore) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Resamples to 16 kHz (windowed-sinc low-pass + linear interpolation) into a growing buffer. */
    private static final class Output {
        private final Resampler resampler;
        float[] data;
        int size;

        Output(int inRate, int capacity) {
            resampler = new Resampler(inRate, SAMPLE_RATE);
            data = new float[Math.max(1024, capacity)];
        }

        void write(float[] in, int count) {
            resampler.process(in, count, this);
        }

        void put(float sample) {
            if (size == data.length) {
                float[] grown = new float[data.length + Math.max(SAMPLE_RATE * 10, data.length / 2)];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = sample;
        }

        float[] finish() {
            if (size == data.length) {
                return data;
            }
            float[] exact = new float[size];
            System.arraycopy(data, 0, exact, 0, size);
            return exact;
        }
    }

    private static final class Resampler {
        private final boolean passthrough;
        private final double step; // input samples per output sample
        private final float[] taps;
        private final float[] history;
        private int historyPos;
        private double pos; // position of the next output sample, in input samples since the start
        private long inputIndex;
        private float prevFiltered, curFiltered;

        Resampler(int inRate, int outRate) {
            passthrough = inRate == outRate;
            step = inRate / (double) outRate;
            int n = 63;
            taps = new float[n];
            double cutoff = 0.45 * Math.min(inRate, outRate) / inRate; // relative to the input rate
            double sum = 0;
            for (int i = 0; i < n; i++) {
                int k = i - n / 2;
                double sinc = k == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * k) / (Math.PI * k);
                double window = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (n - 1));
                taps[i] = (float) (sinc * window);
                sum += taps[i];
            }
            for (int i = 0; i < n; i++) {
                taps[i] /= (float) sum;
            }
            history = new float[n];
        }

        void process(float[] in, int count, Output out) {
            if (passthrough) {
                for (int i = 0; i < count; i++) {
                    out.put(in[i]);
                }
                return;
            }
            int n = taps.length;
            for (int i = 0; i < count; i++) {
                history[historyPos] = in[i];
                historyPos = (historyPos + 1) % n;
                float acc = 0;
                int h = historyPos;
                for (int t = 0; t < n; t++) {
                    acc += taps[t] * history[h];
                    h = h + 1 == n ? 0 : h + 1;
                }
                prevFiltered = curFiltered;
                curFiltered = acc;
                // output samples falling between input samples inputIndex - 1 and inputIndex
                while (pos <= inputIndex) {
                    double frac = pos - (inputIndex - 1);
                    out.put((float) (prevFiltered + (curFiltered - prevFiltered) * frac));
                    pos += step;
                }
                inputIndex++;
            }
        }
    }

    private EasygramAsrAudio() {
    }
}
