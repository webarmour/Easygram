package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;

/**
 * Shared Material 3 Expressive track renderer: a travelling wavy line (constant amplitude, flattens when
 * inactive) or a loudness wave (amplitude follows per-track levels), plus the vertical bar thumb.
 */
public class M3ExpressiveTrack {

    private static final float WAVE_SPEED_RAD_PER_SEC = (float) (Math.PI * 2 * 0.8);
    private static final long AMPLITUDE_ANIM_MS = 300;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float amplitudeFactor;
    private float phase;
    private long lastFrameTime;
    private float levelsAppear = 1f;
    private float[] lastLevels;
    private float beatPulse;
    private float beatEnergy = 1f;
    private boolean reactToBeat;

    /** Makes the wave and thumb react to the playing audio (kick / percussion bumps, tempo of the wave). */
    public void setReactToBeat(boolean react) {
        reactToBeat = react;
        if (!react) {
            beatPulse = 0;
            beatEnergy = 1f;
        }
    }

    /** 0..1 current beat pulse, for thumbs / buttons. */
    public float getBeatPulse() {
        return beatPulse;
    }

    private float smoothedBoost = 1f;

    // "calm" response (voice messages only): small, slow amplitude changes and a steady drift instead of
    // beat-driven jumps. Music / player / widget keep the default (lively) response.
    private boolean calm;
    private float calmSpeed = 0.5f;
    private float thumbDrive; // calm mode: fast drive for the thumb only (voice loudness + onsets)

    /** Calm mode: 0..~1 fast-reacting drive meant for the thumb, so it visibly follows the voice. */
    public float getThumbDrive() {
        return calm ? thumbDrive : beatPulse;
    }

    public void setCalm(boolean calm) {
        this.calm = calm;
    }
    // bass "buzz": a small sawtooth ripple riding on the wave, running fast, sized by the bass level
    private float bassTremble;
    private float ripplePhase;
    private static final float RIPPLE_HZ = 9f;

    /** Sawtooth ripple offset at x, in px (0 when there is no heavy bass). */
    private float ripple(float x, float wavelength, float maxPx) {
        if (bassTremble <= 0.01f) return 0f;
        float rw = wavelength / 3f;                       // three ripple teeth per wave period
        float u = (x / rw + ripplePhase) % 1f;            // 0..1 along a tooth
        if (u < 0) u += 1f;
        float saw = 2f * u - 1f;                          // -1..1 rising saw
        return saw * maxPx * bassTremble;
    }

    private float amplitudeBoost() {
        return smoothedBoost;
    }

    /**
     * Audio-driven look shared by all M3E tracks: the whole wave widens slightly with loudness and bass hits,
     * and only travels while there is sound (faster on beats). Returns the phase speed multiplier.
     */
    static float audioSpeed(float energy, float pulse) {
        return 0.12f + 0.7f * energy + 2.2f * pulse;
    }

    static float audioBoost(float energy, float pulse) {
        return 0.88f + 0.14f * energy + 0.25f * pulse;
    }

    /** M3E animations run at ~60 fps even on 90/120 Hz screens: they are subtle and this halves the redraw cost. */
    private static final long FRAME_DELAY_MS = 16;

    public static void invalidateNextFrame(android.view.View view) {
        if (view != null) {
            view.postInvalidateDelayed(FRAME_DELAY_MS);
        }
    }

    public M3ExpressiveTrack() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Advances animations; returns true if another frame is needed. */
    public boolean update(boolean active, float[] levels) {
        long now = SystemClock.elapsedRealtime();
        long dt = lastFrameTime == 0 ? 16 : Math.min(64, now - lastFrameTime);
        lastFrameTime = now;
        boolean needInvalidate = false;

        if (levels != lastLevels) {
            if (lastLevels == null && levels != null) {
                levelsAppear = 0f;
            }
            lastLevels = levels;
        }
        if (levelsAppear < 1f) {
            levelsAppear = Math.min(1f, levelsAppear + dt / 300f);
            needInvalidate = true;
        }

        if (reactToBeat && active) {
            org.telegram.messenger.MusicBeatTracker tracker = org.telegram.messenger.MusicBeatTracker.getInstance();
            tracker.markInterest();
            if (calm) {
                // heavily low-passed: syllables / plosives no longer make the wave jump
                float rawPulse = tracker.getPulse(), rawEnergy = tracker.getEnergy();
                float drive = Math.min(1f, 0.55f * rawEnergy + 0.6f * rawPulse);
                thumbDrive += (drive - thumbDrive) * (drive > thumbDrive ? 0.5f : 0.25f);
                beatPulse += (rawPulse * 0.35f - beatPulse) * 0.12f;
                beatEnergy += (tracker.getEnergy() - beatEnergy) * 0.06f;
                smoothedBoost += ((1f + 0.06f * beatEnergy + 0.034f * beatPulse) - smoothedBoost) * 0.05f; // never shrinks
                bassTremble += (tracker.getBassTremble() * 0.255f - bassTremble) * 0.1f;
            } else {
                beatPulse = tracker.getPulse();
                beatEnergy = tracker.getEnergy();
                smoothedBoost += (audioBoost(beatEnergy, beatPulse) - smoothedBoost) * 0.35f;
                bassTremble += (tracker.getBassTremble() - bassTremble) * 0.4f;
            }
            needInvalidate = true;
        } else {
            bassTremble += (0f - bassTremble) * 0.25f;
            thumbDrive += (0f - thumbDrive) * 0.25f;
            beatPulse += (0f - beatPulse) * 0.2f;
            beatEnergy += (1f - beatEnergy) * 0.2f;
            smoothedBoost += (1f - smoothedBoost) * 0.2f;
        }

        float target = active ? 1f : 0f;
        if (amplitudeFactor != target) {
            float step = dt / (float) AMPLITUDE_ANIM_MS;
            amplitudeFactor = target > amplitudeFactor ? Math.min(target, amplitudeFactor + step) : Math.max(target, amplitudeFactor - step);
        }
        if (amplitudeFactor > 0f) {
            // the wave travels faster in loud passages and gets a kick on every beat
            float speed;
            if (reactToBeat && calm) {
                // steady drift that only slowly follows the loudness
                calmSpeed += ((0.3f + 0.3f * beatEnergy) - calmSpeed) * 0.04f;
                speed = calmSpeed;
            } else {
                speed = reactToBeat ? audioSpeed(beatEnergy, beatPulse) : 1f;
            }
            phase = (phase + WAVE_SPEED_RAD_PER_SEC * speed * dt / 1000f) % (float) (Math.PI * 2);
            ripplePhase = (ripplePhase + RIPPLE_HZ * dt / 1000f) % 1f;
            needInvalidate = true;
        } else if (!active && !needInvalidate) {
            lastFrameTime = 0;
        }
        return needInvalidate;
    }

    /** Straight rounded line. */
    public void drawLine(Canvas canvas, float left, float right, float centerY, float thickness, int color) {
        if (right - left < 0.5f) return;
        float half = thickness / 2f;
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(thickness);
        if (right - left <= thickness) {
            strokePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle((left + right) / 2f, centerY, half, strokePaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            return;
        }
        canvas.drawLine(left + half, centerY, right - half, centerY, strokePaint);
    }

    /** Wavy progress with constant amplitude (scaled by the play/pause animation). */
    public void drawWavy(Canvas canvas, float left, float right, float centerY, float thickness, float maxAmplitude, float wavelength, int color) {
        float smooth = amplitudeFactor * amplitudeFactor * (3f - 2f * amplitudeFactor);
        float amplitude = maxAmplitude * smooth * amplitudeBoost();
        float half = thickness / 2f;
        float startX = left + half, endX = right - half;
        if (amplitude < 0.1f || endX - startX < 1f) {
            drawLine(canvas, left, right, centerY, thickness, color);
            return;
        }
        float k = (float) (Math.PI * 2 / wavelength);
        float taper = wavelength / 2f;
        float step = AndroidUtilities.dp(1.5f);
        path.rewind();
        path.moveTo(startX, centerY);
        for (float x = startX + step; ; x += step) {
            if (x > endX) x = endX;
            float edge = Math.min(1f, Math.min(x - startX, endX - x) / taper);
            float buzz = ripple(x - startX, wavelength, AndroidUtilities.dp(0.9f));
            path.lineTo(x, centerY + amplitude * edge * (float) Math.sin(k * (x - startX) - phase) + buzz * edge);
            if (x >= endX) break;
        }
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(thickness);
        canvas.drawPath(path, strokePaint);
    }

    /** Segment [from, to] of a track [trackLeft, trackRight] whose wave amplitude follows {@code levels}. */
    public void drawLoudness(Canvas canvas, float from, float to, float trackLeft, float trackRight, float centerY,
                             float thickness, float maxAmplitude, float wavelength, float[] levels, int color) {
        float half = thickness / 2f;
        if (!buildLoudnessLine(path, Math.max(from, trackLeft) + half, Math.min(to, trackRight) - half,
                trackLeft, trackRight, centerY, maxAmplitude, wavelength, levels)) {
            return;
        }
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(thickness);
        canvas.drawPath(path, strokePaint);
    }

    /**
     * Writes the centre line of the loudness wave for [from, to] of the track [trackLeft, trackRight] into
     * {@code out} (amplitude follows {@code levels}, plus the audio-driven boost / phase / bass buzz).
     * Callers that need custom colouring can stroke or clip it themselves. Returns false if nothing to draw.
     */
    public boolean buildLoudnessLine(Path out, float from, float to, float trackLeft, float trackRight, float centerY,
                                     float maxAmplitude, float wavelength, float[] levels) {
        out.rewind();
        if (to - from < 1f || levels == null || levels.length < 2) return false;
        int n = levels.length;
        float maxAmp = maxAmplitude * levelsAppear * amplitudeBoost();
        float minAmp = AndroidUtilities.dp(0.5f);
        float k = (float) (Math.PI * 2 / wavelength);
        float taper = wavelength / 2f;
        float step = Math.min(AndroidUtilities.dp(1f), wavelength / 10f);
        for (float x = from; ; x += step) {
            if (x > to) x = to;
            float pos = (x - trackLeft) / (trackRight - trackLeft) * (n - 1);
            int i0 = Math.max(0, Math.min(n - 1, (int) pos));
            int i1 = Math.min(n - 1, i0 + 1);
            float f = pos - i0;
            float level = levels[i0] * (1f - f) + levels[i1] * f;
            float amp = level > 0.01f ? Math.max(minAmp, level * maxAmp) : 0;
            float edge = Math.min(1f, Math.min(x - from, to - x) / taper);
            // the buzz follows the local loudness too: loud parts tremble more, silence stays calm
            float buzz = ripple(x - trackLeft, wavelength, AndroidUtilities.dp(0.6f) + 0.25f * amp);
            float y = centerY + amp * edge * (float) Math.sin(k * (x - trackLeft) - phase) + buzz * edge * (level > 0.01f ? 1f : 0f);
            if (x == from) out.moveTo(x, y); else out.lineTo(x, y);
            if (x >= to) break;
        }
        return true;
    }

    /**
     * Mirrored variant of {@link #buildLoudnessLine}: a closed, filled shape symmetric around centerY whose
     * half-height is amp * |sin| - the upper half of the wave reflected downwards, so every peak appears above
     * and below at the same time (reads like classic waveform bars, with the same audio reactivity).
     * {@code out} gets the shape; callers usually union it with a thin baseline for silent parts.
     */
    public boolean buildMirroredLoudness(Path out, float from, float to, float trackLeft, float trackRight, float centerY,
                                         float maxAmplitude, float wavelength, float[] levels) {
        return buildMirroredLoudness(out, from, to, trackLeft, trackRight, centerY, maxAmplitude, wavelength, levels, true);
    }

    /**
     * @param carrier true: travelling |sin| beads (music-like); false: the plain mirrored loudness envelope -
     *                every point is exactly as tall as its loudness, nothing flows or dips (voice messages)
     */
    public boolean buildMirroredLoudness(Path out, float from, float to, float trackLeft, float trackRight, float centerY,
                                         float maxAmplitude, float wavelength, float[] levels, boolean carrier) {
        out.rewind();
        if (to - from < 1f || levels == null || levels.length < 2) return false;
        int n = levels.length;
        float maxAmp = maxAmplitude * levelsAppear * amplitudeBoost();
        float minAmp = AndroidUtilities.dp(0.5f);
        // |sin| repeats every half period: use the full wavelength for the carrier so bead spacing = wavelength / 2
        float k = (float) (Math.PI * 2 / wavelength);
        float taper = wavelength / 4f;
        float step = Math.min(AndroidUtilities.dp(0.75f), wavelength / 16f);
        int count = (int) Math.ceil((to - from) / step) + 1;
        if (mirrorBuf == null || mirrorBuf.length < count * 2) {
            mirrorBuf = new float[count * 2 + 16];
        }
        int m = 0;
        for (int i = 0; i < count; i++) {
            float x = Math.min(to, from + i * step);
            float pos = (x - trackLeft) / (trackRight - trackLeft) * (n - 1);
            int i0 = Math.max(0, Math.min(n - 1, (int) pos));
            int i1 = Math.min(n - 1, i0 + 1);
            float f = pos - i0;
            float level = levels[i0] * (1f - f) + levels[i1] * f;
            float amp = level > 0.01f ? Math.max(minAmp, level * maxAmp) : 0;
            float edge = Math.min(1f, Math.min(x - from, to - x) / taper);
            float d = carrier ? amp * edge * Math.abs((float) Math.sin(k * (x - trackLeft) - phase)) : amp * edge;
            if (carrier && level > 0.01f) {
                // the bass buzz shimmers the bead outline (kept positive so the shape never inverts)
                d += Math.abs(ripple(x - trackLeft, wavelength, AndroidUtilities.dp(0.4f) + 0.15f * amp)) * edge;
            }
            mirrorBuf[m++] = x;
            mirrorBuf[m++] = d;
        }
        int points = m / 2;
        out.moveTo(mirrorBuf[0], centerY - mirrorBuf[1]);
        for (int i = 1; i < points; i++) out.lineTo(mirrorBuf[i * 2], centerY - mirrorBuf[i * 2 + 1]);
        for (int i = points - 1; i >= 0; i--) out.lineTo(mirrorBuf[i * 2], centerY + mirrorBuf[i * 2 + 1]);
        out.close();
        return true;
    }

    private float[] mirrorBuf;

    /** M3E slider thumb: vertical rounded bar. */
    public static void drawThumb(Canvas canvas, float centerX, float centerY, float halfWidth, float halfHeight, Paint paint) {
        AndroidUtilities.rectTmp.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight);
        canvas.drawRoundRect(AndroidUtilities.rectTmp, halfWidth, halfWidth, paint);
    }
}
