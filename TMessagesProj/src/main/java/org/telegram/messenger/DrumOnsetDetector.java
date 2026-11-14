/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.messenger;

import java.util.Arrays;

/**
 * Per-instrument onset detector for the Material 3 Expressive UI. Pure Java, runs on the audio thread.
 *
 * Reacts only to what is actually in the audio (no tempo grid). Input: the log-magnitude spectrum of each
 * 1024-sample window. For every band it computes the per-bin positive spectral flux (sum over bins of the
 * rise since the previous window) - unlike a band average this still shows a kick on top of a loud,
 * sustained 808 - and fires on local flux peaks that exceed a robust adaptive threshold
 * (median + k * mean absolute deviation over the last ~1.5 s).
 *
 *  - kick:  40..250 Hz (fundamental + punch)
 *  - snare: crack 1.5..6 kHz and body 180..400 Hz rising together (geometric mean)
 *  - clap:  a 1..6 kHz burst on its own (claps have no body), unless the rise is hat-dominated;
 *           reported on the SNARE channel
 *  - hat:   6..12 kHz
 *
 * Peak picking needs one window of look-ahead, so results describe the previous window:
 * {@link #process} returns hits for the window passed in the call before.
 * Tuned on a real track with heavy sustained 808 bass (see the commit description).
 */
public class DrumOnsetDetector {

    public static final int KICK = 0, SNARE = 1, HAT = 2;

    private static final int HISTORY = 65; // ~1.5 s of windows

    private final Band kick = new Band(4.5f, 110);
    private final Band snare = new Band(4.0f, 110);
    private final Band hat = new Band(4.0f, 65);
    // clap: a bright noise burst with no body - detected on the mid band alone, rejected if it is hat-dominated
    private final Band clap = new Band(4.5f, 110);

    private final float[] out = new float[3];
    private float[] prev;
    private float binHz;

    public void reset() {
        kick.reset();
        snare.reset();
        hat.reset();
        clap.reset();
        prev = null;
    }

    /**
     * @param timeMs   media time of this window
     * @param logMag   log1p magnitude per FFT bin of this window (not retained)
     * @param binHz    width of one bin
     * @return strengths {kick, snare, hat} of the PREVIOUS window, each 0 or 0.3..1 (reused array)
     */
    public float[] process(long timeMs, float[] logMag, float binHz) {
        out[0] = out[1] = out[2] = 0;
        if (prev == null || prev.length != logMag.length) {
            prev = logMag.clone();
            this.binHz = binHz;
            return out;
        }
        this.binHz = binHz;
        float fk = flux(logMag, 40, 250);
        float fs = (float) Math.sqrt(flux(logMag, 1500, 6000) * flux(logMag, 180, 400));
        float fh = flux(logMag, 6000, 12000);
        float fm = flux(logMag, 1000, 6000);
        // per-bin normalisation: the hat band has fewer bins than the clap band
        float hatDominance = (fh / Math.max(1, binsOf(6000, 12000, logMag.length))) / (fm / Math.max(1, binsOf(1000, 6000, logMag.length)) + 1e-4f);
        System.arraycopy(logMag, 0, prev, 0, logMag.length);

        out[KICK] = kick.push(timeMs, fk);
        float snareHit = snare.push(timeMs, fs);
        float clapHit = clap.push(timeMs, hatDominance > 1.5f ? 0f : fm);
        out[SNARE] = Math.max(snareHit, clapHit); // snare and clap share the "backbeat" reaction
        out[HAT] = hat.push(timeMs, fh);
        return out;
    }

    private int binsOf(float fromHz, float toHz, int length) {
        int from = Math.max(1, Math.round(fromHz / binHz));
        int to = Math.min(length - 1, Math.round(toHz / binHz));
        return to - from + 1;
    }

    private float flux(float[] m, float fromHz, float toHz) {
        int from = Math.max(1, Math.round(fromHz / binHz));
        int to = Math.min(m.length - 1, Math.round(toHz / binHz));
        float s = 0;
        for (int i = from; i <= to; i++) {
            float d = m[i] - prev[i];
            if (d > 0) s += d;
        }
        return s;
    }

    private static final class Band {
        private final float k;
        private final long refractoryMs;
        private final float[] history = new float[HISTORY];
        private final float[] sorted = new float[HISTORY];
        private int count, pos;
        // the candidate (previous window) waiting for its right neighbour
        private float candFlux, beforeCand;
        private long candTime;
        private boolean hasCand;
        private long lastOnset = -100000;

        Band(float k, long refractoryMs) {
            this.k = k;
            this.refractoryMs = refractoryMs;
        }

        void reset() {
            count = pos = 0;
            hasCand = false;
            beforeCand = 0;
            lastOnset = -100000;
        }

        /** Adds this window's flux; decides whether the previous window was an onset. */
        float push(long time, float flux) {
            float result = 0;
            if (hasCand && count >= HISTORY / 2) {
                // threshold from the history before the candidate
                int n = count;
                System.arraycopy(history, 0, sorted, 0, n);
                Arrays.sort(sorted, 0, n);
                float median = sorted[n / 2];
                float mad = 0;
                for (int i = 0; i < n; i++) mad += Math.abs(sorted[i] - median);
                mad = mad / n + 1e-4f;
                boolean isPeak = candFlux >= beforeCand && candFlux >= flux;
                if (isPeak && candFlux > median + k * mad && candTime - lastOnset >= refractoryMs) {
                    lastOnset = candTime;
                    result = Math.max(0.3f, Math.min(1f, (candFlux - median) / (2f * k * mad)));
                }
            }
            if (hasCand) {
                history[pos] = candFlux;
                pos = (pos + 1) % HISTORY;
                if (count < HISTORY) count++;
                beforeCand = candFlux;
            }
            candFlux = flux;
            candTime = time;
            hasCand = true;
            return result;
        }
    }
}
