/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.OtherSettingsActivity;

/**
 * Material 3 Expressive loaders, drawn in place of the stock Telegram ones when the M3E setting is on:
 * <ul>
 *     <li>{@link Morph}: the shape-morphing LoadingIndicator, used for indeterminate spinners;</li>
 *     <li>{@link #drawWavyArc}: the wavy circular progress indicator, used for determinate progress arcs.</li>
 * </ul>
 */
public final class M3ExpressiveLoader {

    private M3ExpressiveLoader() { }

    public static boolean enabled() {
        return OtherSettingsActivity.isMaterialExpressiveEnabled();
    }

    // ---------------------------------------------------------------- shape-morphing indicator

    private static final int SAMPLES = 96;
    /** Shape sequence of the M3 LoadingIndicator: soft burst, 9-sided cookie, pentagon, pill, sunny, 4-sided cookie, oval. */
    private static final float[][] SHAPES = new float[7][];
    private static final long MORPH_MS = 650;
    private static final long ROTATION_MS = 4666;

    static {
        SHAPES[0] = polar(10, 0.16f, 0.75f, 0f);   // soft burst
        SHAPES[1] = polar(9, 0.10f, 1.4f, 0f);     // 9-sided cookie
        SHAPES[2] = polar(5, 0.16f, 0.6f, 0f);     // rounded pentagon
        SHAPES[3] = superEllipse(0.6f, 3f);        // pill
        SHAPES[4] = polar(8, 0.09f, 0.8f, 0f);     // sunny
        SHAPES[5] = polar(4, 0.14f, 1.3f, 0f);     // 4-sided cookie
        SHAPES[6] = superEllipse(0.82f, 2f);       // oval
    }

    /** r(θ) = 1 - a·((1 - cos(nθ)) / 2)^p: p < 1 gives rounded corners with flat-ish sides, p > 1 scallops. */
    private static float[] polar(int n, float a, float p, float offset) {
        float[] r = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double theta = 2 * Math.PI * i / SAMPLES + offset;
            double g = (1 - Math.cos(n * theta)) / 2;
            r[i] = (float) (1 - a * Math.pow(g, p));
        }
        return normalize(r);
    }

    /** |x|^p + |y / b|^p = 1, tilted by 45° like the M3 pill and oval. */
    private static float[] superEllipse(float b, float p) {
        float[] r = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double theta = 2 * Math.PI * i / SAMPLES - Math.PI / 4;
            double c = Math.abs(Math.cos(theta)), s = Math.abs(Math.sin(theta)) / b;
            r[i] = (float) Math.pow(Math.pow(c, p) + Math.pow(s, p), -1.0 / p);
        }
        return normalize(r);
    }

    private static float[] normalize(float[] r) {
        float max = 0;
        for (float v : r) max = Math.max(max, v);
        for (int i = 0; i < r.length; i++) r[i] /= max;
        return r;
    }

    /** Spring-like easing with a small overshoot, close to the M3 expressive spatial spring. */
    private static float spring(float t) {
        if (t >= 1f) return 1f;
        double damping = Math.exp(-6.5 * t);
        return (float) (1 - damping * Math.cos(t * Math.PI * 1.15));
    }

    /** One running shape-morphing indicator. Keeps its own clock so every spinner starts from the first shape. */
    public static final class Morph {
        private final Path path = new Path();
        private final float[] xs = new float[SAMPLES];
        private final float[] ys = new float[SAMPLES];
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long start = -1;

        {
            fill.setStyle(Paint.Style.FILL);
        }

        public void reset() {
            start = -1;
        }

        /**
         * Draws the indicator inscribed in a circle of {@code radius} around (cx, cy).
         * The caller keeps invalidating, as it did for the stock spinner.
         */
        public void draw(Canvas canvas, float cx, float cy, float radius, int color) {
            long now = SystemClock.uptimeMillis();
            if (start < 0) start = now;
            long elapsed = now - start;
            long step = elapsed / MORPH_MS;
            float t = spring((elapsed % MORPH_MS) / (float) MORPH_MS);
            float[] from = SHAPES[(int) (step % SHAPES.length)];
            float[] to = SHAPES[(int) ((step + 1) % SHAPES.length)];
            // Constant spin plus an extra quarter turn per morph, as in the M3 LoadingIndicator.
            double rotation = Math.toRadians(360.0 * (elapsed % ROTATION_MS) / ROTATION_MS + 90.0 * ((step % 4) + t));

            for (int i = 0; i < SAMPLES; i++) {
                float r = radius * Math.max(0.4f, from[i] + (to[i] - from[i]) * t);
                double theta = 2 * Math.PI * i / SAMPLES + rotation;
                xs[i] = cx + (float) (r * Math.cos(theta));
                ys[i] = cy + (float) (r * Math.sin(theta));
            }
            path.rewind();
            path.moveTo((xs[SAMPLES - 1] + xs[0]) / 2, (ys[SAMPLES - 1] + ys[0]) / 2);
            for (int i = 0; i < SAMPLES; i++) {
                int j = (i + 1) % SAMPLES;
                path.quadTo(xs[i], ys[i], (xs[i] + xs[j]) / 2, (ys[i] + ys[j]) / 2);
            }
            path.close();
            fill.setColor(color);
            canvas.drawPath(path, fill);
        }
    }

    // ---------------------------------------------------------------- wavy circular progress

    private static final long WAVE_PERIOD_MS = 1600;

    /** Wave amplitude matching the stroke of a stock arc; smaller circles get a smaller wave. */
    public static float amplitudeFor(float radius, float strokeWidth) {
        float a = Math.max(AndroidUtilities.dp(0.8f), Math.min(AndroidUtilities.dp(1.4f), strokeWidth * 0.45f));
        return a * Math.min(1f, radius / AndroidUtilities.dp(14));
    }

    /**
     * Drop-in for {@code canvas.drawArc(oval, startAngle, sweepAngle, false, paint)} with a stroke paint:
     * the arc becomes a travelling wave around the same circle; its ends flatten out so caps stay clean.
     */
    public static void drawWavyArc(Canvas canvas, Path path, RectF oval, float startAngle, float sweepAngle, Paint paint) {
        float radius = Math.min(oval.width(), oval.height()) / 2f;
        float amplitude = amplitudeFor(radius, paint.getStrokeWidth());
        float sweep = Math.abs(sweepAngle);
        if (sweep < 2f || amplitude <= 0 || radius <= 0) {
            canvas.drawArc(oval, startAngle, sweepAngle, false, paint);
            return;
        }
        float cx = oval.centerX(), cy = oval.centerY();
        int waves = Math.max(5, Math.round((float) (2 * Math.PI * radius) / AndroidUtilities.dp(10)));
        float taper = 180f / waves; // half a wavelength, in degrees
        double phase = 2 * Math.PI * (SystemClock.uptimeMillis() % WAVE_PERIOD_MS) / WAVE_PERIOD_MS;
        int steps = Math.max(8, (int) Math.ceil(sweep / 2.5f));

        path.rewind();
        for (int i = 0; i <= steps; i++) {
            float along = sweep * i / steps;
            double angle = Math.toRadians(startAngle + Math.signum(sweepAngle) * along);
            float k = Math.min(1f, Math.min(along, sweep - along) / taper);
            double r = radius + amplitude * k * Math.sin(waves * angle - phase);
            float x = cx + (float) (r * Math.cos(angle));
            float y = cy + (float) (r * Math.sin(angle));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }

    /**
     * M3 wavy circular progress: the wavy active arc plus a flat track for the rest of the circle, separated
     * from it by small gaps on both sides. The track uses the same paint at {@code trackAlpha} of its alpha.
     */
    public static void drawWavyProgress(Canvas canvas, Path path, RectF oval, float startAngle, float sweepAngle,
                                        Paint paint, float trackAlpha) {
        drawWavyArc(canvas, path, oval, startAngle, sweepAngle, paint);
        float radius = Math.min(oval.width(), oval.height()) / 2f;
        if (radius <= 0 || trackAlpha <= 0) return;
        float gap = (float) Math.toDegrees((AndroidUtilities.dp(3) + paint.getStrokeWidth()) / radius);
        float trackSweep = 360f - Math.abs(sweepAngle) - 2 * gap;
        if (trackSweep <= 0) return;
        int alpha = paint.getAlpha();
        paint.setAlpha((int) (alpha * trackAlpha));
        canvas.drawArc(oval, startAngle + sweepAngle + gap, trackSweep, false, paint);
        paint.setAlpha(alpha);
    }

    /** M3 "stop" glyph (rounded square) used in place of the cancel cross while something downloads. */
    public static void drawStop(Canvas canvas, RectF tmp, float cx, float cy, float halfSize, Paint fillPaint) {
        tmp.set(cx - halfSize, cy - halfSize, cx + halfSize, cy + halfSize);
        canvas.drawRoundRect(tmp, halfSize * 0.4f, halfSize * 0.4f, fillPaint);
    }

    // ---------------------------------------------------------------- growing-shape download button

    private static final int GROW_SAMPLES = 180;
    private static final int GROW_WAVES = 7;
    private static final long GROW_SPIN_MS = 4500;

    /**
     * Download progress as the button itself: a small, deeply scalloped shape spinning clockwise that grows and
     * smooths out with {@code progress} until it is the plain round button of radius {@code outer} at 100%
     * (the spin is invisible then, so it just stops). The caller draws the light "not downloaded" disc below.
     */
    public static void drawGrowingShape(Canvas canvas, Path path, float cx, float cy, float outer, float progress, Paint fill) {
        progress = Math.max(0f, Math.min(1f, progress));
        float grow = (float) (1 - Math.pow(1 - progress, 1.6));
        float radius = outer * (15f + 7f * grow) / 22f;
        float depth = outer * 2.6f / 22f * (1f - grow); // soft, rounded scallops
        if (depth < 0.5f) {
            canvas.drawCircle(cx, cy, radius, fill);
            return;
        }
        double spin = 2 * Math.PI * (SystemClock.uptimeMillis() % GROW_SPIN_MS) / GROW_SPIN_MS;
        path.rewind();
        for (int i = 0; i < GROW_SAMPLES; i++) {
            double theta = 2 * Math.PI * i / GROW_SAMPLES; // clockwise from 12 o'clock
            double g = (1 - Math.cos((theta - spin) * GROW_WAVES)) / 2;
            float r = radius - depth * (float) g;
            float x = cx + (float) (r * Math.sin(theta));
            float y = cy - (float) (r * Math.cos(theta));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, fill);
    }
}
