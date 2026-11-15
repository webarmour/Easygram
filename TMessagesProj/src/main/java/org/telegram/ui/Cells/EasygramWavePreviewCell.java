/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.M3ExpressiveTrack;
import org.telegram.ui.OtherSettingsActivity;

/**
 * Live preview of an M3E voice message (mirrored loudness wave, as in the chat bubble). Dimmed while the M3E
 * look of voice messages is turned off.
 */
public class EasygramWavePreviewCell extends View {

    private static final long PLAY_CYCLE_MS = 7000;

    private final M3ExpressiveTrack voiceTrack = new M3ExpressiveTrack();
    private final float[] voiceLevels = demoLevels(48, 0.9f);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final String voiceLabel;

    public EasygramWavePreviewCell(Context context) {
        super(context);
        voiceTrack.setCalm(true);
        textPaint.setTextSize(AndroidUtilities.dp(13));
        voiceLabel = LocaleController.getString(R.string.EasygramM3eVoicePreview);
    }

    /** Smooth, speech / music-like loudness envelope, the same for every run. */
    private static float[] demoLevels(int n, float seed) {
        float[] levels = new float[n];
        for (int i = 0; i < n; i++) {
            double v = Math.abs(Math.sin(i * 0.37 + seed) * Math.sin(i * 0.113 + seed * 2.1)) * 0.75
                    + Math.abs(Math.sin(i * 0.9 + seed * 0.5)) * 0.25;
            levels[i] = (float) Math.min(1, 0.12 + v);
        }
        return levels;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
        final int trackColor = Theme.multAlpha(accent, 0.3f);
        final float progress = (SystemClock.uptimeMillis() % PLAY_CYCLE_MS) / (float) PLAY_CYCLE_MS;
        voiceTrack.update(true, voiceLevels);

        final float buttonX = AndroidUtilities.dp(16 + 20);
        final float left = AndroidUtilities.dp(16 + 40 + 14);
        final float right = getWidth() - AndroidUtilities.dp(20);
        final float x = left + (right - left) * progress;
        final float gap = AndroidUtilities.dp(5);
        textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));

        // voice message: mirrored loudness envelope, played part in the accent colour
        boolean voiceOn = OtherSettingsActivity.isM3eVoiceEnabled();
        float cy = AndroidUtilities.dp(38);
        int saved = voiceOn ? -1 : canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 90);
        drawPlayButton(canvas, buttonX, cy, accent, onAccent);
        canvas.drawText(voiceLabel, left, cy - AndroidUtilities.dp(16), textPaint);
        if (voiceTrack.buildMirroredLoudness(path, left, right, left, right, cy + AndroidUtilities.dp(4), AndroidUtilities.dp(9), AndroidUtilities.dp(7), voiceLevels, false)) {
            float stroke = AndroidUtilities.dp(2);
            for (int i = 0; i < 2; i++) {
                canvas.save();
                if (i == 0) canvas.clipRect(left - stroke, 0, x, getHeight());
                else canvas.clipRect(x, 0, right + stroke, getHeight());
                paint.setColor(i == 0 ? accent : trackColor);
                canvas.drawPath(path, paint);
                rect.set(left - stroke / 2f, cy + AndroidUtilities.dp(4) - stroke / 2f, right + stroke / 2f, cy + AndroidUtilities.dp(4) + stroke / 2f);
                canvas.drawRoundRect(rect, stroke / 2f, stroke / 2f, paint);
                canvas.restore();
            }
        }
        if (saved != -1) canvas.restoreToCount(saved);

        M3ExpressiveTrack.invalidateNextFrame(this);
    }

    private void drawPlayButton(Canvas canvas, float cx, float cy, int accent, int onAccent) {
        paint.setColor(accent);
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(20), paint);
        paint.setColor(onAccent);
        float s = AndroidUtilities.dp(7);
        path.rewind();
        path.moveTo(cx - s * 0.6f, cy - s);
        path.lineTo(cx + s, cy);
        path.lineTo(cx - s * 0.6f, cy + s);
        path.close();
        canvas.drawPath(path, paint);
    }
}
