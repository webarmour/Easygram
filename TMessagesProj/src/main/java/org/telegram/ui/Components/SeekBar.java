/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Collections;

public class SeekBar {

    public interface SeekBarDelegate {
        void onSeekBarDrag(float progress);
        default void onSeekBarContinuousDrag(float progress) {

        }
        default void onSeekBarPressed() {}
        default void onSeekBarReleased() {}

        default boolean isSeekBarDragAllowed() {
            return true;
        }

        default boolean reverseWaveform() {
            return false;
        }
    }

    private static Paint paint;
    private static int thumbWidth;
    private float thumbProgress;
    private int thumbX = 0;
    private int draggingThumbX = 0;
    private int thumbDX = 0;
    private boolean pressed = false;
    private int width;
    private int height;
    private SeekBarDelegate delegate;
    private int backgroundColor;
    private int cacheColor;
    private int circleColor;
    private int progressColor;
    private int backgroundSelectedColor;
    private RectF rect = new RectF();
    private int lineHeight = AndroidUtilities.dp(2);
    private boolean selected;
    private float bufferedProgress;
    private float currentRadius;
    private long lastUpdateTime;
    private View parentView;
    private float alpha = 1f;

    // Material 3 Expressive style: wavy active track, gaps around a vertical bar thumb, flat inactive track.
    private boolean expressive;
    private boolean playing;
    private M3ExpressiveTrack track;

    public void setExpressiveStyle(boolean expressive) {
        if (this.expressive == expressive) {
            return;
        }
        this.expressive = expressive;
        if (expressive && track == null) {
            track = new M3ExpressiveTrack();
            track.setReactToBeat(true);
        }
        if (parentView != null) {
            parentView.invalidate();
        }
    }

    private float[] waveformLevels;

    /** Loudness levels (0..1) of the track; in expressive mode the whole track is drawn as a loudness wave. */
    public void setWaveformLevels(float[] levels) {
        if (waveformLevels != levels) {
            waveformLevels = levels;
            if (parentView != null) {
                parentView.invalidate();
            }
        }
    }

    public void setPlaying(boolean playing) {
        if (this.playing != playing) {
            this.playing = playing;
            if (expressive && parentView != null) {
                parentView.invalidate();
            }
        }
    }

    public SeekBar(View parent) {
        if (paint == null) {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        parentView = parent;
        thumbWidth = AndroidUtilities.dp(24);
        currentRadius = AndroidUtilities.dp(6);
    }

    public void setParent(View parent) {
        parentView = parent;
    }

    public void setDelegate(SeekBarDelegate seekBarDelegate) {
        delegate = seekBarDelegate;
    }

    public boolean onTouch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) {
            int additionWidth = (height - thumbWidth) / 2;
            if (x >= -additionWidth && x <= width + additionWidth && y >= 0 && y <= height) {
                if (!(thumbX - additionWidth <= x && x <= thumbX + thumbWidth + additionWidth)) {
                    thumbX = (int) x - thumbWidth / 2;
                    if (thumbX < 0) {
                        thumbX = 0;
                    } else if (thumbX > width - thumbWidth) {
                        thumbX = width - thumbWidth;
                    }
                }
                pressed = true;
                draggingThumbX = thumbX;
                thumbDX = (int) (x - thumbX);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (pressed) {
                thumbX = draggingThumbX;
                if (action == MotionEvent.ACTION_UP && delegate != null) {
                    delegate.onSeekBarDrag((float) thumbX / (float) (width - thumbWidth));
                }
                pressed = false;
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (pressed) {
                draggingThumbX = (int) (x - thumbDX);
                if (draggingThumbX < 0) {
                    draggingThumbX = 0;
                } else if (draggingThumbX > width - thumbWidth) {
                    draggingThumbX = width - thumbWidth;
                }
                if (delegate != null) {
                    delegate.onSeekBarContinuousDrag((float) draggingThumbX / (float) (width - thumbWidth));
                }
                return true;
            }
        }
        return false;
    }

    public void setColors(int background, int cache, int progress, int circle, int selected) {
        backgroundColor = background;
        cacheColor = cache;
        circleColor = circle;
        progressColor = progress;
        backgroundSelectedColor = selected;
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    public void setProgress(float progress) {
        thumbProgress = progress;
        thumbX = (int) Math.ceil((width - thumbWidth) * thumbProgress);
        if (thumbX < 0) {
            thumbX = 0;
        } else if (thumbX > width - thumbWidth) {
            thumbX = width - thumbWidth;
        }
    }

    public void setBufferedProgress(float value) {
        bufferedProgress = value;
    }

    public float getProgress() {
        return (float) thumbX / (float) (width - thumbWidth);
    }

    public int getThumbX() {
        return (pressed ? draggingThumbX : thumbX) + thumbWidth / 2;
    }

    public boolean isDragging() {
        return pressed;
    }

    public void setSelected(boolean value) {
        selected = value;
    }

    public void setSize(int w, int h) {
        if (width == w && height == h) {
            return;
        }
        width = w;
        height = h;
        setProgress(thumbProgress);
    }

    public int getWidth() {
        return width - thumbWidth;
    }

    public void setLineHeight(int value) {
        lineHeight = value;
    }

    public void draw(Canvas canvas) {
        if (alpha <= 0) {
            return;
        }
        if (alpha < 1) {
            canvas.saveLayerAlpha(0, 0, width, height, (int) (255 * alpha), Canvas.ALL_SAVE_FLAG);
        }
        final float thumbCenterX = (pressed ? draggingThumbX : thumbX) + thumbWidth / 2f;
        final float thumbGap = expressive ? AndroidUtilities.dp(6) : 0;
        final float inactiveLeft = expressive ? Math.max(thumbWidth / 2f, thumbCenterX + thumbGap) : thumbWidth / 2f;
        if (expressive) {
            boolean levelsOk = waveformLevels != null && waveformLevels.length > 1;
            if (track.update(playing && !pressed, levelsOk ? waveformLevels : null)) {
                M3ExpressiveTrack.invalidateNextFrame(parentView);
            }
        }
        if (expressive && waveformLevels != null && waveformLevels.length > 1) {
            drawLoudnessTrack(canvas, thumbCenterX, thumbGap);
            if (alpha < 1) {
                canvas.restore();
            }
            updateTimestampAnimation();
            return;
        }

        rect.set(inactiveLeft, height / 2 - lineHeight / 2, width - thumbWidth / 2, height / 2 + lineHeight / 2);
        paint.setColor(selected ? backgroundSelectedColor : backgroundColor);
        if (rect.right > rect.left) {
            drawProgressBar(canvas, rect, paint);
        }
        if (bufferedProgress > 0) {
            paint.setColor(selected ? backgroundSelectedColor : cacheColor);
            rect.set(inactiveLeft, height / 2 - lineHeight / 2, thumbWidth / 2 + bufferedProgress * (width - thumbWidth), height / 2 + lineHeight / 2);
            if (rect.right > rect.left) {
                drawProgressBar(canvas, rect, paint);
            }
        }
        rect.set(thumbWidth / 2, height / 2 - lineHeight / 2, thumbWidth / 2 + (pressed ? draggingThumbX : thumbX), height / 2 + lineHeight / 2);
        paint.setColor(progressColor);
        if (expressive) {
            float activeRight = thumbCenterX - thumbGap;
            if (activeRight > rect.left) {
                track.drawWavy(canvas, rect.left, activeRight, height / 2f, AndroidUtilities.dp(3), AndroidUtilities.dp(2.5f), AndroidUtilities.dp(20), paint.getColor());
            }
        } else {
            drawProgressBar(canvas, rect, paint);
        }
        paint.setColor(circleColor);

        int newRad = AndroidUtilities.dp(pressed ? 8 : 6);
        if (currentRadius != newRad) {
            long newUpdateTime = SystemClock.elapsedRealtime();
            long dt = newUpdateTime - lastUpdateTime;
            if (dt > 18) {
                dt = 16;
            }
            if (currentRadius < newRad) {
                currentRadius += AndroidUtilities.dp(1) * (dt / 60.0f);
                if (currentRadius > newRad) {
                    currentRadius = newRad;
                }
            } else {
                currentRadius -= AndroidUtilities.dp(1) * (dt / 60.0f);
                if (currentRadius < newRad) {
                    currentRadius = newRad;
                }
            }
            if (parentView != null) {
                parentView.invalidate();
            }
        }

        if (expressive) {
            // M3E slider thumb: a vertical rounded bar instead of a circle.
            float halfW = AndroidUtilities.dp(2);
            float halfH = currentRadius * 1.35f * (1f + 0.5f * track.getBeatPulse());
            AndroidUtilities.rectTmp.set(thumbCenterX - halfW, height / 2f - halfH, thumbCenterX + halfW, height / 2f + halfH);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, halfW, halfW, paint);
        } else {
            canvas.drawCircle((pressed ? draggingThumbX : thumbX) + thumbWidth / 2, height / 2, currentRadius, paint);
        }

        if (alpha < 1) {
            canvas.restore();
        }

        updateTimestampAnimation();
    }

    protected void onTimestampUpdate(URLSpanNoUnderline link) {

    }

    private ArrayList<Pair<Float, URLSpanNoUnderline>> timestamps;
    private CharSequence lastCaption;
    private long lastVideoDuration;

    private float timestampsAppearing = 0;
    private long lastTimestampsAppearingUpdate;
    private final float TIMESTAMP_GAP = 1f;
    private static float[] tmpRadii;
    private static Path tmpPath;

    private int currentTimestamp = -1, lastTimestamp = -1;
    private StaticLayout[] timestampLabel;
    private TextPaint timestampLabelPaint;
    private float timestampChangeT = 1;
    private int timestampChangeDirection;
    private long lastTimestampUpdate;
    private float lastWidth = -1;

    public void clearTimestamps() {
        timestamps = null;
        currentTimestamp = -1;
        timestampsAppearing = 0;
        if (timestampLabel != null) {
            timestampLabel[0] = timestampLabel[1] = null;
        }
        lastCaption = null;
        lastVideoDuration = -1;
    }

    public void updateTimestamps(MessageObject messageObject, Long videoDuration) {
        if (messageObject == null) {
            clearTimestamps();
            return;
        }
        if (videoDuration == null) {
            videoDuration = (long) messageObject.getDuration() * 1000L;
        }
        if (videoDuration == null || videoDuration < 0) {
            clearTimestamps();
            return;
        }
        CharSequence text = messageObject.caption;
        if (messageObject.isYouTubeVideo()) {
            if (messageObject.youtubeDescription == null && messageObject.messageOwner.media.webpage.description != null) {
                messageObject.youtubeDescription = SpannableString.valueOf(messageObject.messageOwner.media.webpage.description);
                MessageObject.addUrlsByPattern(messageObject.isOut(), messageObject.youtubeDescription, false, 3, (int) (long) videoDuration, false);
            }
            text = messageObject.youtubeDescription;
        }
        if (text == lastCaption && lastVideoDuration == videoDuration) {
            return;
        }
        lastCaption = text;
        lastVideoDuration = videoDuration;
        if (!(text instanceof Spanned)) {
            timestamps = null;
            currentTimestamp = -1;
            timestampsAppearing = 0;
            if (timestampLabel != null) {
                timestampLabel[0] = timestampLabel[1] = null;
            }
            return;
        }
        Spanned spanned = (Spanned) text;
        URLSpanNoUnderline[] links;
        try {
            links = spanned.getSpans(0, spanned.length(), URLSpanNoUnderline.class);
        } catch (Exception e) {
            FileLog.e(e);
            timestamps = null;
            currentTimestamp = -1;
            timestampsAppearing = 0;
            if (timestampLabel != null) {
                timestampLabel[0] = timestampLabel[1] = null;
            }
            return;
        }
        timestamps = new ArrayList<>();
        timestampsAppearing = 0;
        if (timestampLabelPaint == null) {
            timestampLabelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            timestampLabelPaint.setTextSize(AndroidUtilities.dp(12));
            timestampLabelPaint.setColor(0xffffffff);
        }
        for (int i = 0; i < links.length; ++i) {
            try {
                URLSpanNoUnderline link = links[i];
                if (link != null && link.getURL() != null && link.label != null && link.getURL().startsWith("audio?")) {
                    Integer seconds = Utilities.parseInt(link.getURL().substring(6));
                    if (seconds != null && seconds >= 0) {
                        float position = seconds * 1000L / (float) videoDuration;
                        String label = link.label;
                        SpannableStringBuilder builder = new SpannableStringBuilder(label);
                        Emoji.replaceEmoji(builder, timestampLabelPaint.getFontMetricsInt(), false);
                        timestamps.add(new Pair<>(position, link));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        Collections.sort(timestamps, (a, b) -> {
            if (a.first > b.first) {
                return 1;
            } else if (b.first > a.first) {
                return -1;
            } else {
                return 0;
            }
        });
    }

    /** Whole track as a wavy line whose amplitude follows loudness, with a gap around a bar thumb. */
    private void drawLoudnessTrack(Canvas canvas, float thumbCenterX, float thumbGap) {
        final float left = thumbWidth / 2f;
        final float right = width - thumbWidth / 2f;
        final float centerY = height / 2f;
        final float bufferedX = left + bufferedProgress * (right - left);
        final float stroke = AndroidUtilities.dp(2.2f);
        final float amp = AndroidUtilities.dp(6);
        final float wl = AndroidUtilities.dp(9);

        // played part
        track.drawLoudness(canvas, left, thumbCenterX - thumbGap, left, right, centerY, stroke, amp, wl, waveformLevels, progressColor);
        // buffered part
        float inactiveLeft = thumbCenterX + thumbGap;
        if (bufferedProgress > 0 && bufferedX > inactiveLeft) {
            track.drawLoudness(canvas, inactiveLeft, bufferedX, left, right, centerY, stroke, amp, wl, waveformLevels, selected ? backgroundSelectedColor : cacheColor);
            inactiveLeft = bufferedX;
        }
        // rest of the track
        track.drawLoudness(canvas, inactiveLeft, right, left, right, centerY, stroke, amp, wl, waveformLevels, selected ? backgroundSelectedColor : backgroundColor);

        // thumb (bar) + its press animation; bumps on beats
        int newRad = AndroidUtilities.dp(pressed ? 8 : 6);
        if (currentRadius != newRad) {
            currentRadius += (newRad > currentRadius ? 1 : -1) * AndroidUtilities.dp(1) * (16 / 60.0f);
            if (Math.abs(currentRadius - newRad) < AndroidUtilities.dp(1) * (16 / 60.0f)) currentRadius = newRad;
            if (parentView != null) parentView.invalidate();
        }
        paint.setColor(circleColor);
        float halfH = Math.min(height / 2f, currentRadius * 1.6f * (1f + 0.45f * track.getBeatPulse()));
        M3ExpressiveTrack.drawThumb(canvas, thumbCenterX, centerY, AndroidUtilities.dp(2), halfH, paint);
    }

    private void drawProgressBar(Canvas canvas, RectF rect, Paint paint) {
        float radius = thumbWidth / 2f;
        if (timestamps == null || timestamps.isEmpty()) {
            canvas.drawRoundRect(rect, radius, radius, paint);
        } else {
            float lineWidth = rect.bottom - rect.top;
            float left = thumbWidth / 2f;
            float right = width - thumbWidth / 2f;
            AndroidUtilities.rectTmp.set(rect);
            float halfGap = AndroidUtilities.dp(TIMESTAMP_GAP * timestampsAppearing) / 2f;
            if (tmpPath == null) {
                tmpPath = new Path();
            }
            tmpPath.reset();
            float minDur = AndroidUtilities.dp(4) / (right - left);
            int start = -1, end = -1;
            for (int i = 0; i < timestamps.size(); ++i) {
                if (timestamps.get(i).first >= minDur) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                start = 0;
            }
            for (int i = timestamps.size() - 1; i >= 0; --i) {
                if (1f - timestamps.get(i).first >= minDur) {
                    end = i + 1;
                    break;
                }
            }
            if (end < 0) {
                end = timestamps.size();
            }
            boolean first = true;
            for (int i = start; i <= end; ++i) {
                float from = i == start ? 0 : timestamps.get(i - 1).first;
                float to = i == end ? 1 : timestamps.get(i).first;
                while (i != end && i != 0 && i < timestamps.size() - 1 && timestamps.get(i).first - from <= minDur) {
                    i++;
                    to = timestamps.get(i).first;
                }

                AndroidUtilities.rectTmp.left = AndroidUtilities.lerp(left, right, from) + (i > 0 ? halfGap : 0);
                AndroidUtilities.rectTmp.right = AndroidUtilities.lerp(left, right, to) - (i < end ? halfGap : 0);

                boolean last;
                if (last = AndroidUtilities.rectTmp.right > rect.right) {
                    AndroidUtilities.rectTmp.right = rect.right;
                }
                if (AndroidUtilities.rectTmp.right < rect.left) {
                    continue;
                }
                if (AndroidUtilities.rectTmp.left < rect.left) {
                    AndroidUtilities.rectTmp.left = rect.left;
                }

                if (tmpRadii == null) {
                    tmpRadii = new float[8];
                }
                if (i == start || last && AndroidUtilities.rectTmp.left >= rect.left) {
                    tmpRadii[0] = tmpRadii[1] = tmpRadii[6] = tmpRadii[7] = radius;
                    tmpRadii[2] = tmpRadii[3] = tmpRadii[4] = tmpRadii[5] = radius * 0.7f * timestampsAppearing;
                } else if (i >= end) {
                    tmpRadii[0] = tmpRadii[1] = tmpRadii[6] = tmpRadii[7] = radius * 0.7f * timestampsAppearing;
                    tmpRadii[2] = tmpRadii[3] = tmpRadii[4] = tmpRadii[5] = radius;
                } else {
                    tmpRadii[0] = tmpRadii[1] = tmpRadii[6] = tmpRadii[7] =
                    tmpRadii[2] = tmpRadii[3] = tmpRadii[4] = tmpRadii[5] = radius * 0.7f * timestampsAppearing;
                }
                tmpPath.addRoundRect(AndroidUtilities.rectTmp, tmpRadii, Path.Direction.CW);

                if (last) {
                    break;
                }
            }
            canvas.drawPath(tmpPath, paint);
        }
    }

    private void updateTimestampAnimation() {
        if (timestamps == null || timestamps.isEmpty()) {
            return;
        }

        float progress = (pressed ? draggingThumbX : thumbX) / (float) (width - thumbWidth);

        int timestampIndex = -1;
        for (int i = timestamps.size() - 1; i >= 0; --i) {
            if (timestamps.get(i).first - 0.001f <= progress) {
                timestampIndex = i;
                break;
            }
        }

        if (timestampLabel == null) {
            timestampLabel = new StaticLayout[2];
        }

        float left = thumbWidth / 2f;
        float right = width - thumbWidth / 2f;
        float rightPadded = right;
        float width = Math.abs(left - rightPadded) - AndroidUtilities.dp(16 + 50);

        lastWidth = width;

        if (timestampIndex != currentTimestamp) {
            if (pressed) {
                AndroidUtilities.vibrateCursor(parentView);
            }
            currentTimestamp = timestampIndex;
            if (currentTimestamp >= 0 && currentTimestamp < timestamps.size()) {
                onTimestampUpdate(timestamps.get(currentTimestamp).second);
            }
        }
        if (timestampChangeT < 1f) {
            long tx = Math.min(17, Math.abs(SystemClock.elapsedRealtime() - lastTimestampUpdate));
            float duration = timestamps.size() > 8 ? 160f : 220f;
            timestampChangeT = Math.min(timestampChangeT + tx / duration, 1);
            if (parentView != null) {
                parentView.invalidate();
            }
            lastTimestampUpdate = SystemClock.elapsedRealtime();
        }
        if (timestampsAppearing < 1f) {
            long tx = Math.min(17, Math.abs(SystemClock.elapsedRealtime() - lastTimestampUpdate));
            timestampsAppearing = Math.min(timestampsAppearing + tx / 200f, 1);
            if (parentView != null) {
                parentView.invalidate();
            }
            lastTimestampsAppearingUpdate = SystemClock.elapsedRealtime();
        }
    }
}
