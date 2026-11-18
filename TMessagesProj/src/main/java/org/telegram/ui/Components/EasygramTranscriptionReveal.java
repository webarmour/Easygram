/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.MessageObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * M3E "decoding" reveal of a freshly made voice transcription: the words first flicker through random
 * characters of the same script on a gently travelling wave, then, from left to right, settle into the real
 * text with a springy pop and an accent tint that fades into the text colour.
 *
 * <p>Drawn instead of a caption block of {@link org.telegram.ui.Cells.ChatMessageCell} while it runs; the text
 * layout itself is the final one, so the bubble never changes size during the animation.</p>
 *
 * <p>Cost per frame is kept small for long transcriptions: the geometry of every word is measured once, the
 * part of a line that has already settled is drawn with a single call, the random characters are regenerated
 * only when they change (every {@link #FLICKER_MS}), and there is one draw call per word that is still moving.</p>
 */
public final class EasygramTranscriptionReveal {

    private static final long PENDING_TIMEOUT_MS = 15_000; // started but never drawn: the chat was closed
    private static final long SETTLE_MS = 420; // spring of one word
    private static final long FLICKER_MS = 70;
    private static final int TICK_EVERY_WORDS = 2;
    private static final long TICK_MIN_INTERVAL_MS = 60;

    private static final String CYRILLIC_LOWER = "абвгдежзийклмнопрстуфхцчшщъыьэюя";
    private static final String CYRILLIC_UPPER = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЭЮЯ";
    private static final String LATIN_LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String LATIN_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";

    /** Words of one caption block with their measured positions; built on the first frame. */
    private static final class Geometry {
        boolean rtl;
        int words;
        int[] start, end, line;
        float[] x, width;
        float[] order; // 0..1 position of the word in the whole transcription
        int[] jitter;
        // flickering characters: regenerated when the step changes
        char[] noise;
        float[] noiseWidth;
        long noiseStep = -1;
    }

    private static final class Run {
        final long created = SystemClock.uptimeMillis();
        long start = -1;
        long duration;
        final WeakHashMap<Layout, Geometry> geometry = new WeakHashMap<>();
        // haptics
        long lastFrame = -1, lastTick;
        int tickedWords;
        boolean finishedHaptic;
        int totalWords = -1;
    }

    private static final HashMap<String, Run> runs = new HashMap<>();

    private static String key(MessageObject m) {
        return m.currentAccount + "_" + m.getDialogId() + "_" + m.getId();
    }

    public static boolean enabled() {
        return M3ExpressiveLoader.enabled();
    }

    /** Called when a transcription has just been made: the next time it is drawn it is revealed. */
    public static void start(MessageObject m) {
        if (m == null || !enabled()) {
            return;
        }
        runs.put(key(m), new Run());
    }

    private static boolean expired(Run r, long now) {
        return r.start < 0 ? now - r.created > PENDING_TIMEOUT_MS : now - r.start > r.duration + SETTLE_MS;
    }

    /**
     * Draws {@code layout} (one caption block, canvas already translated to it) with the reveal.
     * {@code charOffset} is the index of the block's first character in the whole text, {@code totalChars} the
     * text length. Returns false when there is nothing to animate (the caller then draws the layout normally).
     */
    public static boolean draw(View parent, Canvas canvas, MessageObject m, Layout layout, int charOffset, int totalChars) {
        if (layout == null || m == null || runs.isEmpty()) {
            return false;
        }
        String key = key(m);
        Run run = runs.get(key);
        if (run == null) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (expired(run, now)) {
            runs.remove(key);
            for (Iterator<Map.Entry<String, Run>> it = runs.entrySet().iterator(); it.hasNext(); ) {
                if (expired(it.next().getValue(), now)) it.remove();
            }
            return false;
        }
        Geometry g = run.geometry.get(layout);
        if (g == null) {
            g = measure(layout, charOffset, totalChars);
            run.geometry.put(layout, g);
        }
        if (g.rtl) {
            return false; // RTL text simply appears
        }
        if (run.start < 0) {
            run.start = now;
            // longer texts take a little longer, but never drag on
            run.duration = Math.max(650, Math.min(2100, 420 + totalChars * 14L));
            run.totalWords = Math.max(1, totalChars / 6);
        }
        float elapsed = now - run.start;
        if (run.lastFrame != now) {
            run.lastFrame = now; // once per frame, however many blocks the text has
            haptics(parent, run, elapsed, now);
        }

        CharSequence text = layout.getText();
        TextPaint paint = layout.getPaint();
        int textColor = paint.getColor();
        int accent = paint.linkColor != 0 ? paint.linkColor : textColor;
        int noiseColor = ColorUtils.blendARGB(textColor, accent, 0.55f);
        int baseAlpha = paint.getAlpha();
        long step = (long) (elapsed / FLICKER_MS);
        if (step != g.noiseStep) {
            regenerateNoise(g, text, paint, step);
        }
        float wavePhase = elapsed / 1000f * 7f;
        float waveAmp = dp(1.6f);
        float appear = Math.min(1f, elapsed / 180f);
        float span = run.duration - 260;

        int w = 0;
        for (int line = 0, lines = layout.getLineCount(); line < lines; line++) {
            float baseline = layout.getLineBaseline(line);
            int lineStart = layout.getLineStart(line);
            // settled prefix of the line: one call
            int prefixEnd = lineStart;
            while (w < g.words && g.line[w] == line && t(g, w, elapsed, span) >= 1f) {
                prefixEnd = g.end[w];
                w++;
            }
            if (prefixEnd > lineStart) {
                paint.setColor(textColor);
                paint.setAlpha(baseAlpha);
                canvas.drawText(text, lineStart, prefixEnd, layout.getLineLeft(line), baseline, paint);
            }
            for (; w < g.words && g.line[w] == line; w++) {
                float t = t(g, w, elapsed, span);
                float x = g.x[w], width = g.width[w];
                if (t >= 1f) {
                    paint.setColor(textColor);
                    paint.setAlpha(baseAlpha);
                    canvas.drawText(text, g.start[w], g.end[w], x, baseline, paint);
                } else if (t >= 0f) {
                    // settling: spring scale-up from the baseline, accent tint fading into the text colour
                    float s = 0.6f + 0.4f * M3ExpressiveLoader.springIn(t);
                    float tint = 1f - CubicBezierInterpolator.EASE_OUT.getInterpolation(Math.min(1f, t * 1.6f));
                    paint.setColor(ColorUtils.blendARGB(textColor, accent, tint));
                    paint.setAlpha((int) (baseAlpha * Math.min(1f, 0.45f + t * 2f)));
                    canvas.save();
                    canvas.scale(s, s, x + width / 2f, baseline);
                    canvas.drawText(text, g.start[w], g.end[w], x, baseline, paint);
                    canvas.restore();
                } else {
                    // still decoding: random characters of the same kind, riding the wave
                    float dy = waveAmp * (float) Math.sin(wavePhase - g.order[w] * 40f);
                    paint.setColor(noiseColor);
                    paint.setAlpha((int) (baseAlpha * 0.42f * appear));
                    int len = g.end[w] - g.start[w];
                    float nw = g.noiseWidth[w];
                    if (nw > 0 && Math.abs(nw - width) > width * 0.04f) {
                        canvas.save();
                        canvas.scale(width / nw, 1f, x, baseline);
                        canvas.drawText(g.noise, g.start[w] - g.start[0], len, x, baseline + dy, paint);
                        canvas.restore();
                    } else {
                        canvas.drawText(g.noise, g.start[w] - g.start[0], len, x, baseline + dy, paint);
                    }
                }
            }
        }
        paint.setColor(textColor);
        paint.setAlpha(baseAlpha);
        parent.invalidate();
        return true;
    }

    private static float t(Geometry g, int w, float elapsed, float span) {
        float resolveAt = 120 + span * g.order[w] + g.jitter[w];
        return (elapsed - resolveAt) / SETTLE_MS;
    }

    private static Geometry measure(Layout layout, int charOffset, int totalChars) {
        Geometry g = new Geometry();
        CharSequence text = layout.getText();
        TextPaint paint = layout.getPaint();
        int lines = layout.getLineCount();
        int capacity = 8;
        g.start = new int[capacity];
        g.end = new int[capacity];
        g.line = new int[capacity];
        g.x = new float[capacity];
        g.width = new float[capacity];
        for (int line = 0; line < lines; line++) {
            if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
                g.rtl = true;
                return g;
            }
            int lineStart = layout.getLineStart(line), lineEnd = layout.getLineEnd(line);
            float left = layout.getLineLeft(line);
            int i = lineStart;
            while (i < lineEnd) {
                while (i < lineEnd && Character.isWhitespace(text.charAt(i))) i++;
                int s = i;
                while (i < lineEnd && !Character.isWhitespace(text.charAt(i))) i++;
                if (s == i) continue;
                if (g.words == capacity) {
                    capacity *= 2;
                    g.start = java.util.Arrays.copyOf(g.start, capacity);
                    g.end = java.util.Arrays.copyOf(g.end, capacity);
                    g.line = java.util.Arrays.copyOf(g.line, capacity);
                    g.x = java.util.Arrays.copyOf(g.x, capacity);
                    g.width = java.util.Arrays.copyOf(g.width, capacity);
                }
                g.start[g.words] = s;
                g.end[g.words] = i;
                g.line[g.words] = line;
                g.x[g.words] = left + paint.measureText(text, lineStart, s);
                g.width[g.words] = paint.measureText(text, s, i);
                g.words++;
            }
        }
        g.order = new float[g.words];
        g.jitter = new int[g.words];
        for (int w = 0; w < g.words; w++) {
            g.order[w] = totalChars <= 1 ? 0f : Math.min(1f, (charOffset + g.start[w]) / (float) (totalChars - 1));
            g.jitter[w] = hash(charOffset + g.start[w]) % 70;
        }
        g.noiseWidth = new float[g.words];
        g.noise = g.words == 0 ? new char[0] : new char[g.end[g.words - 1] - g.start[0]];
        return g;
    }

    /** New random characters for the words still decoding (and their widths, to fit them into the real ones). */
    private static void regenerateNoise(Geometry g, CharSequence text, TextPaint paint, long step) {
        g.noiseStep = step;
        if (g.words == 0) return;
        int base = g.start[0];
        for (int w = 0; w < g.words; w++) {
            for (int i = g.start[w]; i < g.end[w]; i++) {
                char c = text.charAt(i);
                g.noise[i - base] = Character.isLetterOrDigit(c) && !Character.isSurrogate(c) ? scramble(c, i, step) : c;
            }
            g.noiseWidth[w] = paint.measureText(g.noise, g.start[w] - base, g.end[w] - g.start[w]);
        }
    }

    /**
     * Light ticks as the words settle (one per {@link #TICK_EVERY_WORDS}, never closer than
     * {@link #TICK_MIN_INTERVAL_MS}), and a stronger one right after the last word has settled.
     */
    private static void haptics(View parent, Run run, float elapsed, long now) {
        if (run.finishedHaptic || !org.telegram.messenger.asr.EasygramAsrSettings.isRevealHaptic()) {
            return;
        }
        float span = Math.max(1, run.duration - 260);
        if (elapsed >= 120 + span + 70 + 140) {
            run.finishedHaptic = true;
            perform(parent, Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        int settled = (int) Math.max(0, Math.min(run.totalWords, (elapsed - 155) / span * run.totalWords));
        if (settled - run.tickedWords >= TICK_EVERY_WORDS && now - run.lastTick >= TICK_MIN_INTERVAL_MS) {
            run.tickedWords = settled;
            run.lastTick = now;
            perform(parent, Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private static void perform(View parent, int constant) {
        try {
            parent.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Exception ignore) {
        }
    }

    private static char scramble(char real, int index, long step) {
        String set;
        if (real >= 'а' && real <= 'я' || real == 'ё') set = CYRILLIC_LOWER;
        else if (real >= 'А' && real <= 'Я' || real == 'Ё') set = CYRILLIC_UPPER;
        else if (real >= 'a' && real <= 'z') set = LATIN_LOWER;
        else if (real >= 'A' && real <= 'Z') set = LATIN_UPPER;
        else if (real >= '0' && real <= '9') set = DIGITS;
        else if (Character.isUpperCase(real)) set = LATIN_UPPER;
        else set = Character.UnicodeBlock.of(real) == Character.UnicodeBlock.CYRILLIC ? CYRILLIC_LOWER : LATIN_LOWER;
        return set.charAt(hash(index * 31 + (int) step * 7919) % set.length());
    }

    private static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x & 0x7fffffff;
    }

    private EasygramTranscriptionReveal() {
    }
}
