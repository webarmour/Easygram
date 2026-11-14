/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class SeekBarWaveform {

    private static Paint paintInner;
    private static Paint paintOuter;
    private int thumbX = 0;
    private int thumbDX = 0;
    private float progress;
    private float startX;
    private boolean startDraging = false;
    private boolean pressed = false;
    private int width;
    private int height;
    private int fromWidth, toWidth;
    private SeekBar.SeekBarDelegate delegate;
    private byte[] waveformBytes;
    private MessageObject messageObject;
    private View parentView;
    private boolean selected;

    private int innerColor;
    private int outerColor;
    private int selectedColor;
    private float alpha = 1f;

    private float clearProgress = 1f;
    private boolean isUnread;
    private AnimatedFloat appearFloat = new AnimatedFloat(125, 600, CubicBezierInterpolator.EASE_OUT_QUINT);

    private float waveScaling = 1f;

    private Path path;
    private Path alphaPath;

    // Material 3 Expressive waveform: a wavy line whose amplitude follows the loudness.
    private static Paint expressiveStrokePaint;
    private final Path expressiveLinePath = new Path();
    private final Path expressiveGapPath = new Path();
    private boolean loading;
    private long loadingStart;
    private AnimatedFloat loadingFloat = new AnimatedFloat(150, CubicBezierInterpolator.DEFAULT);
    private Paint loadingPaint;
    private float loadingPaintWidth;
    private int loadingPaintColor1, loadingPaintColor2;

    private ArrayList<Float> animatedValues;
    private float[] heights;
    private float[] fromHeights;
    private float[] toHeights;

    public SeekBarWaveform(Context context) {
        if (paintInner == null) {
            paintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintInner.setStyle(Paint.Style.FILL);
            paintOuter.setStyle(Paint.Style.FILL);
        }
    }

    public void setDelegate(SeekBar.SeekBarDelegate seekBarDelegate) {
        delegate = seekBarDelegate;
    }

    public void setColors(int inner, int outer, int selected) {
        innerColor = inner;
        outerColor = outer;
        selectedColor = selected;
    }

    public void setWaveform(byte[] waveform) {
        waveformBytes = waveform;
        heights = calculateHeights((int) (width / AndroidUtilities.dpf2(3)));
        if (!delegate.isSeekBarDragAllowed()) {
            this.progress = 1f;
        }
    }

    public void setSelected(boolean value) {
        selected = value;
    }

    public void setMessageObject(MessageObject object) {
        if (animatedValues != null && messageObject != null && object != null && messageObject.getId() != object.getId()) {
            animatedValues.clear();
        }
        messageObject = object;
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    public void setParentView(View view) {
        parentView = view;
        loadingFloat.setParent(view);
        appearFloat.setParent(view);
    }

    public void invalidate() {
        if (parentView != null) {
            parentView.invalidate();
        }
    }

    public boolean isStartDraging() {
        return startDraging;
    }

    public boolean onTouch(int action, float x, float y) {
        if (!delegate.isSeekBarDragAllowed()) {
            this.progress = 1f;
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (0 <= x && x <= width && y >= 0 && y <= height) {
                startX = x;
                pressed = true;
                thumbDX = (int) (x - thumbX);
                startDraging = false;
                delegate.onSeekBarPressed();
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (pressed) {
                if (action == MotionEvent.ACTION_UP && delegate != null) {
                    delegate.onSeekBarDrag((float) thumbX / (float) width);
                }
                pressed = false;
                delegate.onSeekBarReleased();
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (pressed) {
                if (startDraging) {
                    thumbX = (int) (x - thumbDX);
                    if (thumbX < 0) {
                        thumbX = 0;
                    } else if (thumbX > width) {
                        thumbX = width;
                    }
                    this.progress = thumbX / (float) width;
                }
                if (startX != -1 && Math.abs(x - startX) > AndroidUtilities.getPixelsInCM(0.2f, true)) {
                    if (parentView != null && parentView.getParent() != null) {
                        parentView.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    startDraging = true;
                    startX = -1;
                }
                return true;
            }
        }
        return false;
    }

    public float getProgress() {
        return thumbX / (float) width;
    }

    public void setProgress(float progress) {
        setProgress(progress, false);
    }

    public void setProgress(float progress, boolean animated) {
        if (!delegate.isSeekBarDragAllowed()) {
            this.progress = 1f;
            return;
        }
        this.progress = isUnread ? 1f : progress;
        int currentThumbX = isUnread ? width : thumbX;
        if (animated && currentThumbX != 0 && progress == 0) {
            clearProgress = 0f;
        } else if (!animated) {
            clearProgress = 1f;
        }
        thumbX = (int) Math.ceil(width * progress);
        if (thumbX < 0) {
            thumbX = 0;
        } else if (thumbX > width) {
            thumbX = width;
        }
    }

    public boolean isDragging() {
        return pressed;
    }

    public void setSize(int w, int h) {
        setSize(w, h, w, w);
    }

    public void setSize(int w, int h, int fromW, int toW) {
        int wasWidth = width;
        width = w;
        height = h;
        if (heights == null || heights.length != (int) (width / AndroidUtilities.dpf2(3))) {
            heights = calculateHeights((int) (width / AndroidUtilities.dpf2(3)));
        }
        if (fromW != toW && (fromWidth != fromW || toWidth != toW)) {
            fromWidth = fromW;
            toWidth = toW;
            fromHeights = calculateHeights((int) (fromWidth / AndroidUtilities.dpf2(3)));
            toHeights = calculateHeights((int) (toWidth / AndroidUtilities.dpf2(3)));
        } else if (fromW == toW) {
            fromHeights = toHeights = null;
        }
    }

    public void setSent() {
        appearFloat.set(0, true);
        if (parentView != null) {
            parentView.invalidate();
        }
    }

    private float[] calculateHeights(int count) {
        if (waveformBytes == null || count <= 0) {
            return null;
        }
        float[] heights = new float[count];
        byte value;
        int samplesCount = (waveformBytes.length * 8 / 5);
        float samplesPerBar = samplesCount / (float) count;
        float barCounter = 0;
        int nextBarNum = 0;

        int barNum = 0;
        int lastBarNum;
        int drawBarCount;
        for (int a = 0; a < samplesCount; a++) {
            if (a != nextBarNum) {
                continue;
            }
            drawBarCount = 0;
            lastBarNum = nextBarNum;
            while (lastBarNum == nextBarNum) {
                barCounter += samplesPerBar;
                nextBarNum = (int) barCounter;
                drawBarCount++;
            }

            int bitPointer = a * 5;
            int byteNum = bitPointer / 8;
            int byteBitOffset = bitPointer - byteNum * 8;
            int currentByteCount = 8 - byteBitOffset;
            int nextByteRest = 5 - currentByteCount;
            value = (byte) ((waveformBytes[byteNum] >> byteBitOffset) & ((2 << (Math.min(5, currentByteCount) - 1)) - 1));
            if (nextByteRest > 0 && byteNum + 1 < waveformBytes.length) {
                value <<= nextByteRest;
                value |= waveformBytes[byteNum + 1] & ((2 << (nextByteRest - 1)) - 1);
            }

            for (int b = 0; b < drawBarCount; b++) {
                if (barNum >= heights.length) {
                    return heights;
                }
                heights[barNum++] = Math.max(0, 7 * value / 31.0f);
            }
        }
        return heights;
    }

    private boolean exploding = false;
    public float explodeProgress;
    public void explodeAt(float progress) {
        exploding = true;
        explodeProgress = progress;
        invalidate();
    }
    public float explosionRate;
    public void setExplosionRate(float explosionRate) {
        this.explosionRate = explosionRate;
        invalidate();
    }
    public void stopExploding() {
        exploding = false;
        if (particles != null) {
            particles.clear();
        }
        invalidate();
    }

    private Particles particles;

    public void draw(Canvas canvas, View parentView) {
        if (waveformBytes == null || width == 0 || alpha <= 0) {
            return;
        }
        float totalBarsCount = width / AndroidUtilities.dpf2(3);
        if (totalBarsCount <= 0.1f) {
            return;
        }
        if (clearProgress != 1f) {
            clearProgress += 16 / 150f;
            if (clearProgress > 1f) {
                clearProgress = 1f;
            } else {
                parentView.invalidate();
            }
        }

        float appearProgress = appearFloat.set(1f);

        if (path == null) {
            path = new Path();
        } else {
            path.reset();
        }

        float alpha = 0;
        if (alphaPath == null) {
            alphaPath = new Path();
        } else {
            alphaPath.reset();
        }

        final boolean reverse = delegate != null && delegate.reverseWaveform();
        // `heights` always matches the current width (see setSize), so the continuous wave needs no
        // from/to interpolation; the width animation after sending (transcribe button) stays M3E.
        final boolean expressiveWave = org.telegram.ui.OtherSettingsActivity.isMaterialExpressiveEnabled()
                && heights != null && heights.length > 1;
        float expressiveThumbX = -1;
        if (expressiveWave) {
            expressiveThumbX = buildExpressivePath(totalBarsCount, appearProgress, reverse);
        } else if (fromHeights != null && toHeights != null) {
            float t = (width - fromWidth) / (float) (toWidth - fromWidth);
            int maxlen = Math.max(fromHeights.length, toHeights.length);
            int minlen = Math.min(fromHeights.length, toHeights.length);
            float[] minarr = fromHeights.length < toHeights.length ? fromHeights : toHeights;
            float[] maxarr = fromHeights.length < toHeights.length ? toHeights : fromHeights;
//            t = CubicBezierInterpolator.EASE_OUT.getInterpolation(t);
            float T = fromHeights.length < toHeights.length ? t : 1f - t;
            int k = -1;
            for (int barNum = 0; barNum < maxlen; ++barNum) {
                int l = MathUtils.clamp((int) Math.floor(barNum / (float) maxlen * minlen), 0, minlen - 1);
                if (k < l) {
                    float x = AndroidUtilities.lerp((float) l, (float) barNum, T) * AndroidUtilities.dpf2(3);
                    float h = AndroidUtilities.dpf2(AndroidUtilities.lerp(minarr[reverse ? minarr.length - 1 - l : l], maxarr[reverse ? maxarr.length - 1 - barNum : barNum], T));
                    addBar(path, x, h);
                    k = l;
                } else {
                    float x = AndroidUtilities.lerp((float) l, (float) barNum, T) * AndroidUtilities.dpf2(3);
                    float h = AndroidUtilities.dpf2(AndroidUtilities.lerp(minarr[reverse ? minarr.length - 1 - l : l], maxarr[reverse ? maxarr.length - 1 - barNum : barNum], T));
                    addBar(alphaPath, x, h);
                    alpha = T;
                }
            }
        } else if (heights != null) {
            for (int barNum = 0; barNum < totalBarsCount; barNum++) {
                if (barNum >= heights.length) {
                    break;
                }
                float x = barNum * AndroidUtilities.dpf2(3);
                float bart = MathUtils.clamp(appearProgress * totalBarsCount - barNum, 0, 1);
                float h = AndroidUtilities.dpf2(heights[reverse ? heights.length - 1 - barNum : barNum]) * bart;
                h -= AndroidUtilities.dpf2(1) * (1f - bart);
                addBar(path, x, h);
            }
        }

        if (exploding || explosionRate > 0) {
            canvas.save();
            final float w = totalBarsCount * AndroidUtilities.dpf2(3);
            canvas.clipRect(0, 0, w * (1f - explodeProgress * explosionRate), height);
        }

        if (alpha > 0) {
            canvas.save();
            canvas.clipPath(alphaPath);
            drawFill(canvas, alpha * this.alpha);
            canvas.restore();
        }

        canvas.save();
        canvas.clipPath(path);
        drawFill(canvas, this.alpha);
        canvas.restore();

        if (expressiveThumbX >= 0) {
            drawExpressiveThumb(canvas, expressiveThumbX);
        }

        if (exploding || explosionRate > 0) {
            canvas.restore();
            if (particles == null) {
                particles = new Particles(250, this::invalidate);
            }
            RectF emitArea = null;
            if (explodeProgress < .99f && heights != null) {
                int barNum = (int) (totalBarsCount * (1f - explodeProgress));
                if (reverse) {
                    barNum = (int) (totalBarsCount - 1 - barNum);
                }
                if (barNum >= 0 && barNum < heights.length) {
                    float bart = MathUtils.clamp(appearProgress * totalBarsCount - barNum, 0, 1);
                    float h = AndroidUtilities.dpf2(heights[barNum]) * bart;
                    emitArea = AndroidUtilities.rectTmp;
                    final float x = (totalBarsCount * (1f - explodeProgress)) * AndroidUtilities.dpf2(3);
                    final float strokeWidth = AndroidUtilities.dpf2(2);
                    final int y = (height - dp(14)) / 2;
                    h *= waveScaling;
                    AndroidUtilities.rectTmp.set(
                        x + AndroidUtilities.dpf2(1) - strokeWidth / 2f,
                        y + dp(7) + (-h - strokeWidth / 2f),
                        x + AndroidUtilities.dpf2(1) + strokeWidth / 2f,
                        y + dp(7) + (h + strokeWidth / 2f)
                    );
                }
            }
            particles
                .setColor(outerColor)
                .setEmitArea(emitArea)
                .draw(canvas, explosionRate);
        }
    }

    private void drawFill(Canvas canvas, float alpha) {
        final float strokeWidth = AndroidUtilities.dpf2(2);

        isUnread = messageObject != null && messageObject.isContentUnread() && !messageObject.isOut() && this.progress <= 0;
        paintInner.setColor(isUnread ? outerColor : (selected ? selectedColor : innerColor));
        paintOuter.setColor(outerColor);

        loadingFloat.setParent(parentView);
        boolean isPlaying = MediaController.getInstance().isPlayingMessage(messageObject);
        float loadingT = loadingFloat.set(loading && !isPlaying ? 1f : 0f);
        paintInner.setColor(ColorUtils.blendARGB(paintInner.getColor(), innerColor, loadingT));
        paintOuter.setAlpha((int) (paintOuter.getAlpha() * (1f - loadingT) * alpha));
        paintInner.setAlpha((int) (paintInner.getAlpha() * alpha));

        canvas.drawRect(0, 0, width + strokeWidth, height, paintInner);
        if (loadingT < 1f) {
            canvas.drawRect(0, 0, (this.progress * (width + strokeWidth)) * (1f - loadingT), height, paintOuter);
        }

        if (loadingT > 0f) {
            if (loadingPaint == null || Math.abs(loadingPaintWidth - width) > dp(8) || loadingPaintColor1 != innerColor || loadingPaintColor2 != outerColor) {
                if (loadingPaint == null) {
                    loadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                }
                loadingPaintColor1 = innerColor;
                loadingPaintColor2 = outerColor;
                loadingPaint.setShader(new LinearGradient(0, 0, loadingPaintWidth = width, 0, new int[]{ loadingPaintColor1, loadingPaintColor2, loadingPaintColor1 }, new float[]{0, 0.2f, 0.4f}, Shader.TileMode.CLAMP));
            }
            loadingPaint.setAlpha((int) (255 * loadingT * alpha));
            canvas.save();
            float t = (SystemClock.elapsedRealtime() - loadingStart) / 270f;
            t = (float) Math.pow(t, 0.75f);
            float dx = (t % 1.6f - .6f) * loadingPaintWidth;
            canvas.translate(dx, 0);
            canvas.drawRect(-dx, 0, width + 5 - dx, height, loadingPaint);
            canvas.restore();

            if (parentView != null) {
                parentView.invalidate();
            }
        }
    }

    /**
     * Fills {@link #path} with the outline of the M3E voice wave: the shared {@link M3ExpressiveTrack} loudness
     * line (same look and audio reactivity as music tracks), with per-message contrast stretching so quiet and
     * loud parts are clearly different. Returns the x of the M3E thumb, or -1 when no thumb should be drawn.
     */
    private float buildExpressivePath(float totalBarsCount, float appearProgress, boolean reverse) {
        float[] levels = getVoiceLevels(reverse);
        if (levels == null) {
            return -1;
        }
        // appear animation (after sending): the wave grows in from the left, like the bars did
        if (appearProgress < 1f) {
            if (appearLevels == null || appearLevels.length != levels.length) {
                appearLevels = new float[levels.length];
            }
            int n = levels.length;
            for (int i = 0; i < n; i++) {
                appearLevels[i] = levels[i] * MathUtils.clamp(appearProgress * n - i, 0, 1);
            }
            levels = appearLevels;
        }

        if (voiceTrack == null) {
            voiceTrack = new M3ExpressiveTrack();
            voiceTrack.setReactToBeat(true);
            voiceTrack.setCalm(true); // voice: gentle, smooth response (music / widget keep the lively one)
        }
        boolean playing = messageObject != null && MediaController.getInstance().isPlayingMessage(messageObject)
                && !MediaController.getInstance().isMessagePaused();
        if (voiceTrack.update(playing && !pressed, levels)) {
            M3ExpressiveTrack.invalidateNextFrame(parentView);
        }

        if (expressiveStrokePaint == null) {
            expressiveStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            expressiveStrokePaint.setStyle(Paint.Style.STROKE);
            expressiveStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            expressiveStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        }
        final float stroke = AndroidUtilities.dpf2(2f);
        expressiveStrokePaint.setStrokeWidth(stroke);

        final float barStep = AndroidUtilities.dpf2(3);
        final int count = Math.max(2, Math.min(heights != null ? heights.length : 0, (int) Math.ceil(totalBarsCount)));
        final float centerY = (height - dp(14)) / 2f + dp(7);
        final float startX = AndroidUtilities.dpf2(1);
        final float endX = Math.max(startX + 1, (count - 1) * barStep + AndroidUtilities.dpf2(1));
        // denser than before (7 dp per wave instead of 9) so loudness changes read at a glance
        final float wavelength = AndroidUtilities.dpf2(7);
        // use (almost) the full height of the 30 dp area: with the audio boost (up to ~1.27x) the mirrored peaks
        // just reach the edges, so loud peaks and quiet parts are far apart
        final float maxAmp = Math.min(AndroidUtilities.dpf2(11.5f), (height / 2f - 1) / 1.3f) * waveScaling;
        // mirrored "beads": the upper half of the wave reflected downwards (symmetric peaks, like the classic
        // bars), plus a thin rounded baseline so silent parts stay a visible line
        // plain mirrored loudness envelope: peaks where it's loud, low where it's quiet, no flowing carrier
        if (!voiceTrack.buildMirroredLoudness(path, startX, endX, startX, endX, centerY, maxAmp, wavelength, levels, false)) {
            return -1;
        }
        float half = stroke / 2f;
        expressiveLinePath.rewind();
        expressiveLinePath.addRoundRect(startX - half, centerY - half, endX + half, centerY + half, half, half, Path.Direction.CW);
        path.op(expressiveLinePath, Path.Op.UNION);

        boolean showThumb = delegate != null && delegate.isSeekBarDragAllowed() && progress > 0f && progress < 1f && messageObject != null
                && MediaController.getInstance().isPlayingMessage(messageObject);
        if (!showThumb) {
            return -1;
        }
        float thumbX = startX + (endX - startX) * progress;
        // cut a gap around the thumb, like M3E sliders/progress indicators
        float gap = AndroidUtilities.dpf2(4);
        expressiveGapPath.rewind();
        expressiveGapPath.addRect(thumbX - gap, 0, thumbX + gap, height, Path.Direction.CW);
        path.op(expressiveGapPath, Path.Op.DIFFERENCE);
        return thumbX;
    }

    private M3ExpressiveTrack voiceTrack;
    private float[] voiceLevels, voiceLevelsReversed, appearLevels;
    private byte[] voiceLevelsSource;

    /**
     * Loudness levels 0..1 decoded from the raw 5-bit voice waveform (~100 samples), contrast-stretched per
     * message: between its quiet (15th pct) and loud (95th pct) parts, then a gamma 1.8 curve. Telegram's waveform
     * is already peak-normalised and mostly sits near the top, which is why plain heights looked uniform.
     */
    private float[] getVoiceLevels(boolean reverse) {
        if (waveformBytes == null) {
            return null;
        }
        if (voiceLevelsSource != waveformBytes) {
            voiceLevelsSource = waveformBytes;
            voiceLevels = computeVoiceLevels(waveformBytes);
            voiceLevelsReversed = null;
        }
        if (voiceLevels == null || !reverse) {
            return voiceLevels;
        }
        if (voiceLevelsReversed == null) {
            int n = voiceLevels.length;
            voiceLevelsReversed = new float[n];
            for (int i = 0; i < n; i++) voiceLevelsReversed[i] = voiceLevels[n - 1 - i];
        }
        return voiceLevelsReversed;
    }

    /** Same per-message contrast-stretched levels the M3E chat waveform uses (for the mini player). */
    public static float[] computeVoiceLevels(byte[] bytes) {
        int samples = bytes.length * 8 / 5;
        if (samples < 2) {
            return null;
        }
        float[] raw = new float[samples];
        for (int a = 0; a < samples; a++) {
            int bitPointer = a * 5;
            int byteNum = bitPointer / 8;
            int byteBitOffset = bitPointer - byteNum * 8;
            int currentByteCount = 8 - byteBitOffset;
            int nextByteRest = 5 - currentByteCount;
            int value = (bytes[byteNum] >> byteBitOffset) & ((2 << (Math.min(5, currentByteCount) - 1)) - 1);
            if (nextByteRest > 0 && byteNum + 1 < bytes.length) {
                value <<= nextByteRest;
                value |= bytes[byteNum + 1] & ((2 << (nextByteRest - 1)) - 1);
            }
            raw[a] = value & 31;
        }
        float[] sorted = raw.clone();
        java.util.Arrays.sort(sorted);
        float lo = sorted[(int) (samples * 0.15f)];
        float hi = sorted[Math.min(samples - 1, (int) (samples * 0.95f))];
        if (hi - lo < 6) { // a flat message: keep a sensible span so it doesn't turn into noise
            lo = Math.max(0, hi - 6);
        }
        float[] levels = new float[samples];
        for (int i = 0; i < samples; i++) {
            if (raw[i] <= 1) {
                levels[i] = 0; // silence
                continue;
            }
            float v = MathUtils.clamp((raw[i] - lo) / (hi - lo), 0f, 1f);
            levels[i] = Math.max(0.03f, (float) Math.pow(v, 1.8));
        }
        return levels;
    }

    private void drawExpressiveThumb(Canvas canvas, float x) {
        final float centerY = (height - dp(14)) / 2f + dp(7);
        // same resting size as the music bubble's thumb (SeekBar: 6 dp radius * 1.6, 8 dp when pressed; 2 dp half-width)
        final float halfW = AndroidUtilities.dpf2(2f);
        // follows the voice: grows with how loud the speech is right now and bumps on sharp sounds
        final float beat = voiceTrack != null ? 1f + 0.4f * voiceTrack.getThumbDrive() : 1f;
        final float halfH = Math.min(height / 2f, AndroidUtilities.dpf2(pressed ? 8 * 1.6f : 6 * 1.6f) * beat);
        paintOuter.setColor(outerColor);
        paintOuter.setAlpha((int) (paintOuter.getAlpha() * alpha));
        AndroidUtilities.rectTmp.set(x - halfW, centerY - halfH, x + halfW, centerY + halfH);
        canvas.drawRoundRect(AndroidUtilities.rectTmp, halfW, halfW, paintOuter);
    }

    private void addBar(Path path, float x, float h) {
        final float strokeWidth = AndroidUtilities.dpf2(2);
        final int y = (height - dp(14)) / 2;
        h *= waveScaling;
        AndroidUtilities.rectTmp.set(
            x + AndroidUtilities.dpf2(1) - strokeWidth / 2f,
            y + dp(7) + (-h - strokeWidth / 2f),
            x + AndroidUtilities.dpf2(1) + strokeWidth / 2f,
            y + dp(7) + (h + strokeWidth / 2f)
        );
        path.addRoundRect(AndroidUtilities.rectTmp, strokeWidth, strokeWidth, Path.Direction.CW);
    }

    public void setWaveScaling(float waveScaling) {
        this.waveScaling = waveScaling;
    }

    public void setLoading(boolean loading) {
        if (!this.loading && loading && this.loadingFloat.get() <= 0) {
            loadingStart = SystemClock.elapsedRealtime();
        }
        this.loading = loading;
        if (parentView != null) {
            parentView.invalidate();
        }
    }

    public static class Particles {
        private final int count;
        private Runnable invalidate;
        private final ArrayList<Particle> particles = new ArrayList<>(50);
        private final ArrayList<Particle> deadParticles = new ArrayList<>(50);

        public Particles(int count, Runnable invalidate) {
            this.count = count;
            this.invalidate = invalidate;
            paint.setStrokeWidth(dp(1.33f));
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private class Particle {
            float x, y, v, vx, vy, t, d;
        }

        public Particles setColor(int color) {
            paint.setColor(color);
            return this;
        }

        private RectF emitArea;
        public Particles setEmitArea(RectF emitArea) {
            this.emitArea = emitArea;
            return this;
        }

        public void clear() {
            deadParticles.addAll(particles);
            particles.clear();
        }

        private long lastTime;
        public void draw(Canvas canvas, float alpha) {
            final long now = System.currentTimeMillis();
            final long dt = Math.min(20, (now - lastTime));
            lastTime = now;
            for (int i = 0; i < particles.size(); ++i) {
                Particle p = particles.get(i);
                p.t -= dt / p.d;
                if (p.t < 0) {
                    deadParticles.add(p);
                    particles.remove(i);
                    i--;
                } else {
                    p.x += p.vx * p.v * dt / 500.0f;
                    p.y += p.vy * p.v * dt / 500.0f;
                    p.vy -= dp(0.33f) * dt / 500.0f;
                }
            }
            if (emitArea != null) {
                int count = Math.min(4, this.count - particles.size());
//                double vx = Math.sin(Math.PI / 180.0 * (0 - 90));
//                double vy = -Math.cos(Math.PI / 180.0 * (0 - 90));
                for (int i = 0; i < count; ++i) {
                    Particle p = deadParticles.isEmpty() ? new Particle() :deadParticles.remove(0);
                    p.x = emitArea.left + emitArea.width() * Utilities.random.nextFloat();
                    p.y = emitArea.top + emitArea.height() * Utilities.random.nextFloat();
                    double angle = (Math.PI / 180.0) * (Utilities.random.nextInt(200) - 125);
                    p.vx = (float) (Math.cos(angle) - Math.sin(angle)) * .8f;
                    p.vy = (float) (Math.sin(angle) + Math.cos(angle)) - .2f;
                    p.t = 1f;
                    p.v = AndroidUtilities.dp(10 + Utilities.random.nextFloat() * 7);
                    p.d = AndroidUtilities.lerp(420, 550, Utilities.random.nextFloat());
                    particles.add(p);
                }
            }
            for (int i = 0; i < particles.size(); ++i) {
                Particle p = particles.get(i);
                paint.setAlpha((int) (0xFF * alpha * p.t));
                canvas.drawPoint(p.x, p.y, paint);
            }
            if (invalidate != null) {
                invalidate.run();
            }
        }
    }
}
