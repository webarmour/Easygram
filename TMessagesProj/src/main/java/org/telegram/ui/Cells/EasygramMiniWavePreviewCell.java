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
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.M3ExpressiveTrack;
import org.telegram.ui.OtherSettingsActivity;

/**
 * Both M3E mini player waves side by side: the simple wave and the detailed one (loudness line for music,
 * mirrored loudness for voice messages). The one in use is highlighted; tapping a card selects it.
 */
public class EasygramMiniWavePreviewCell extends View {

    public interface Listener {
        void onSelected(boolean detailed);
    }

    private static final long PLAY_CYCLE_MS = 7000;

    private final M3ExpressiveTrack plainTrack = new M3ExpressiveTrack();
    private final M3ExpressiveTrack detailedTrack = new M3ExpressiveTrack();
    private final float[] levels = new float[64];
    private final RectF rect = new RectF();
    private final android.graphics.Path path = new android.graphics.Path();
    private final boolean voice;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final String plainLabel, detailedLabel;
    private Listener listener;

    public EasygramMiniWavePreviewCell(Context context, boolean voice) {
        super(context);
        this.voice = voice;
        detailedTrack.setCalm(voice);
        for (int i = 0; i < levels.length; i++) {
            double v = Math.abs(Math.sin(i * 0.31 + 0.4) * Math.sin(i * 0.097 + 1.7)) * 0.8 + Math.abs(Math.sin(i * 1.1)) * 0.2;
            levels[i] = (float) Math.min(1, 0.1 + v);
        }
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(AndroidUtilities.dp(2));
        textPaint.setTextSize(AndroidUtilities.dp(13));
        textPaint.setTextAlign(Paint.Align.CENTER);
        plainLabel = LocaleController.getString(R.string.EasygramM3eMiniPlain);
        detailedLabel = LocaleController.getString(R.string.EasygramM3eMiniDetailed);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(104), MeasureSpec.EXACTLY));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            boolean detailed = event.getX() > getWidth() / 2f;
            if (detailed != OtherSettingsActivity.isMiniPlayerDetailedWaveEnabled(voice) && listener != null) {
                listener.onSelected(detailed);
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int trackColor = Theme.multAlpha(accent, 0.28f);
        final boolean detailedSelected = OtherSettingsActivity.isMiniPlayerDetailedWaveEnabled(voice);
        final float progress = (SystemClock.uptimeMillis() % PLAY_CYCLE_MS) / (float) PLAY_CYCLE_MS;
        plainTrack.update(true, null);
        detailedTrack.update(true, levels);

        final float pad = AndroidUtilities.dp(16), between = AndroidUtilities.dp(12);
        final float cardW = (getWidth() - pad * 2 - between) / 2f;
        final float top = AndroidUtilities.dp(8), cardH = AndroidUtilities.dp(64);
        for (int i = 0; i < 2; i++) {
            boolean detailed = i == 1;
            boolean selected = detailed == detailedSelected;
            float l = pad + i * (cardW + between);
            rect.set(l, top, l + cardW, top + cardH);
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(14), AndroidUtilities.dp(14), paint);
            if (selected) {
                borderPaint.setColor(accent);
                rect.inset(AndroidUtilities.dp(1), AndroidUtilities.dp(1));
                canvas.drawRoundRect(rect, AndroidUtilities.dp(13), AndroidUtilities.dp(13), borderPaint);
            }

            float left = l + AndroidUtilities.dp(14), right = l + cardW - AndroidUtilities.dp(14);
            float cy = top + cardH / 2f;
            float x = left + (right - left) * progress;
            float gap = AndroidUtilities.dp(5);
            if (detailed && voice) {
                // voice: mirrored loudness envelope, as in the mini player and the chat bubble
                if (detailedTrack.buildMirroredLoudness(path, left, right, left, right, cy, AndroidUtilities.dp(10), AndroidUtilities.dp(7), levels, false)) {
                    float stroke = AndroidUtilities.dp(2);
                    for (int k = 0; k < 2; k++) {
                        canvas.save();
                        if (k == 0) canvas.clipRect(left - stroke, 0, x - gap, getHeight());
                        else canvas.clipRect(x + gap, 0, right + stroke, getHeight());
                        paint.setColor(k == 0 ? accent : trackColor);
                        canvas.drawPath(path, paint);
                        AndroidUtilities.rectTmp.set(left - stroke / 2f, cy - stroke / 2f, right + stroke / 2f, cy + stroke / 2f);
                        canvas.drawRoundRect(AndroidUtilities.rectTmp, stroke / 2f, stroke / 2f, paint);
                        canvas.restore();
                    }
                }
            } else if (detailed) {
                detailedTrack.drawLoudness(canvas, left, x - gap, left, right, cy, AndroidUtilities.dp(2), AndroidUtilities.dp(8), AndroidUtilities.dp(10), levels, accent);
                detailedTrack.drawLoudness(canvas, x + gap, right, left, right, cy, AndroidUtilities.dp(2), AndroidUtilities.dp(8), AndroidUtilities.dp(10), levels, trackColor);
            } else {
                plainTrack.drawLine(canvas, x + gap, right, cy, AndroidUtilities.dp(2), trackColor);
                plainTrack.drawWavy(canvas, left, x - gap, cy, AndroidUtilities.dp(2), AndroidUtilities.dp(3), AndroidUtilities.dp(20), accent);
            }
            paint.setColor(accent);
            M3ExpressiveTrack.drawThumb(canvas, x, cy, AndroidUtilities.dp(1.5f), AndroidUtilities.dp(7), paint);

            textPaint.setColor(selected ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            textPaint.setFakeBoldText(selected);
            canvas.drawText(detailed ? detailedLabel : plainLabel, l + cardW / 2f, top + cardH + AndroidUtilities.dp(22), textPaint);
        }
        M3ExpressiveTrack.invalidateNextFrame(this);
    }
}
