package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;

/**
 * Material 3 Expressive play/pause: the play triangle splits into two halves that morph into two rounded
 * pause bars (and back) with a springy overshoot, and the container morphs between a circle (paused) and a
 * rounded square (playing). {@link #getMorph()} goes 0 = play .. 1 = pause.
 */
public class M3PlayPauseIcon {

    private static final float STIFFNESS = 420f;   // spring constant
    private static final float DAMPING = 24f;      // a bit under critical -> small overshoot

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float morph;
    private float velocity;
    private float target;
    private long lastFrame;

    public M3PlayPauseIcon() {
        iconPaint.setStyle(Paint.Style.FILL);
    }

    public void setColorFilter(android.graphics.ColorFilter filter) {
        if (iconPaint.getColorFilter() != filter) {
            iconPaint.setColorFilter(filter);
        }
    }

    public void setPause(boolean pause, boolean animated) {
        target = pause ? 1f : 0f;
        if (!animated) {
            morph = target;
            velocity = 0;
        }
    }

    public boolean isPause() {
        return target == 1f;
    }

    /** Steps the spring; returns true while animating. */
    public boolean update() {
        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.032f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (Math.abs(target - morph) < 0.001f && Math.abs(velocity) < 0.01f) {
            morph = target;
            velocity = 0;
            lastFrame = 0;
            return false;
        }
        // two half-steps for stability
        for (int i = 0; i < 2; i++) {
            float h = dt / 2f;
            float force = STIFFNESS * (target - morph) - DAMPING * velocity;
            velocity += force * h;
            morph += velocity * h;
        }
        return true;
    }

    public float getMorph() {
        return morph;
    }

    private final Path containerPath = new Path();

    /**
     * Container: a circle while paused that softly swells into a gentle squircle (superellipse) while playing.
     * During the toggle it squashes and springs back like jelly, driven by the spring's velocity.
     */
    public void drawContainer(Canvas canvas, float cx, float cy, float radius, float scale, Paint paint) {
        canvas.drawPath(buildContainerPath(cx, cy, radius, scale), paint);
    }

    /** The current container outline (reused instance; valid until the next call). */
    public Path buildContainerPath(float cx, float cy, float radius, float scale) {
        float m = Math.max(0f, Math.min(1f, morph));
        float n = 2f + 0.75f * m; // 2 = circle, ~2.75 = soft squircle
        float vn = Math.max(-1f, Math.min(1f, velocity / 9f));
        float rx = radius * scale * (1f + 0.045f * vn);
        float ry = radius * scale * (1f - 0.045f * vn);
        float e = 2f / n;
        containerPath.rewind();
        final int steps = 72;
        for (int i = 0; i < steps; i++) {
            double t = Math.PI * 2 * i / steps;
            double c = Math.cos(t), sn = Math.sin(t);
            float x = cx + rx * (float) (Math.signum(c) * Math.pow(Math.abs(c), e));
            float y = cy + ry * (float) (Math.signum(sn) * Math.pow(Math.abs(sn), e));
            if (i == 0) containerPath.moveTo(x, y); else containerPath.lineTo(x, y);
        }
        containerPath.close();
        return containerPath;
    }

    private final float[] px = new float[4], py = new float[4], pr = new float[4];

    /** Icon fitting a box of half-size {@code size}. */
    public void drawIcon(Canvas canvas, float cx, float cy, float size, int color, int alpha) {
        final float m = morph; // may overshoot slightly, which gives the springy look
        iconPaint.setColor(color);
        iconPaint.setAlpha(alpha);

        // play: a single soft triangle (two halves fused along the middle, inner corners kept sharp)
        final float hh = size * 0.80f;               // half height
        final float tw = hh * 2f * 0.88f;            // width
        final float pl = cx - tw * 0.40f, prx = cx + tw * 0.60f;   // optical centring
        final float pm = (pl + prx) / 2f;
        final float midHalf = hh * (prx - pm) / (prx - pl);
        final float outer = size * 0.20f;            // rounding of the triangle's corners

        // pause: two rounded bars
        final float barW = size * 0.40f;
        final float barGap = size * 0.30f;
        final float bh = size * 0.74f;
        final float barR = barW * 0.42f;
        final float l1 = cx - barGap / 2f - barW, r1 = cx - barGap / 2f;
        final float l2 = cx + barGap / 2f, r2 = cx + barGap / 2f + barW;

        final float inner = Math.max(0f, lerp(0f, barR, m));
        final float outerR = Math.max(0f, lerp(outer, barR, m));
        final float tipHalf = lerp(size * 0.06f, bh, m);

        path.rewind();
        // left half of the triangle <-> left bar
        set(0, lerp(pl, l1, m), lerp(cy - hh, cy - bh, m), outerR);
        set(1, lerp(pm, r1, m), lerp(cy - midHalf, cy - bh, m), inner);
        set(2, lerp(pm, r1, m), lerp(cy + midHalf, cy + bh, m), inner);
        set(3, lerp(pl, l1, m), lerp(cy + hh, cy + bh, m), outerR);
        addRounded();
        // right half (tip) <-> right bar
        set(0, lerp(pm, l2, m), lerp(cy - midHalf, cy - bh, m), inner);
        set(1, lerp(prx, r2, m), cy - tipHalf, outerR);
        set(2, lerp(prx, r2, m), cy + tipHalf, outerR);
        set(3, lerp(pm, l2, m), lerp(cy + midHalf, cy + bh, m), inner);
        addRounded();
        canvas.drawPath(path, iconPaint);
    }

    private void set(int i, float x, float y, float r) {
        px[i] = x;
        py[i] = y;
        pr[i] = r;
    }

    /** Adds the quad (px, py) to {@link #path} with per-corner rounding radii pr. */
    private void addRounded() {
        final int n = 4;
        for (int i = 0; i <= n; i++) {
            int v = i % n, prev = (v + n - 1) % n, next = (v + 1) % n;
            float vx = px[v], vy = py[v];
            float dpx = px[prev] - vx, dpy = py[prev] - vy;
            float dnx = px[next] - vx, dny = py[next] - vy;
            float lp = (float) Math.hypot(dpx, dpy), ln = (float) Math.hypot(dnx, dny);
            float r = pr[v];
            float tp = lp > 0 ? Math.min(0.5f, r / lp) : 0;
            float tn = ln > 0 ? Math.min(0.5f, r / ln) : 0;
            float ax = vx + dpx * tp, ay = vy + dpy * tp;
            float bx = vx + dnx * tn, by = vy + dny * tn;
            if (i == 0) {
                path.moveTo(bx, by);
            } else if (i == n) {
                path.lineTo(ax, ay);
                path.quadTo(vx, vy, bx, by);
            } else {
                path.lineTo(ax, ay);
                path.quadTo(vx, vy, bx, by);
            }
        }
        path.close();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
