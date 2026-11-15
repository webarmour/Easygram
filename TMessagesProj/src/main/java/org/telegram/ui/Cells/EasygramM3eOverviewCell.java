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
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.M3ExpressiveTrack;
import org.telegram.ui.Components.M3PlayPauseIcon;

/**
 * What the main M3E switch turns on, as live previews drawn with the same code as the app: the song in a chat
 * (morphing play / pause button + loudness wave), the full-screen player, the mini player and the video player.
 * The loaders and the download button follow in {@link EasygramLoaderPreviewCell}.
 */
public class EasygramM3eOverviewCell extends View {

    private static final long PLAY_CYCLE_MS = 8000;
    private static final long PLAY_PAUSE_TOGGLE_MS = 2200;
    private static final int ROW_DP = 58;

    private final M3ExpressiveTrack chatTrack = new M3ExpressiveTrack();
    private final M3ExpressiveTrack playerTrack = new M3ExpressiveTrack();
    private final M3ExpressiveTrack miniTrack = new M3ExpressiveTrack();
    private final M3ExpressiveTrack videoTrack = new M3ExpressiveTrack();
    private final M3PlayPauseIcon playPause = new M3PlayPauseIcon();
    private final float[] levels = new float[64];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final String[] labels;

    public EasygramM3eOverviewCell(Context context) {
        super(context);
        for (int i = 0; i < levels.length; i++) {
            double v = Math.abs(Math.sin(i * 0.33 + 2.3) * Math.sin(i * 0.105 + 4.8)) * 0.8 + Math.abs(Math.sin(i * 0.95)) * 0.2;
            levels[i] = (float) Math.min(1, 0.1 + v);
        }
        textPaint.setTextSize(AndroidUtilities.dp(13));
        labels = new String[] {
                LocaleController.getString(R.string.EasygramM3eOverviewChatMusic),
                LocaleController.getString(R.string.EasygramM3eOverviewPlayer),
                LocaleController.getString(R.string.EasygramM3eOverviewMiniPlayer),
                LocaleController.getString(R.string.EasygramM3eOverviewVideo),
        };
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(ROW_DP * 4 + 12), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
        final int trackColor = Theme.multAlpha(accent, 0.28f);
        final long now = SystemClock.uptimeMillis();
        final float progress = (now % PLAY_CYCLE_MS) / (float) PLAY_CYCLE_MS;
        final boolean playing = (now / PLAY_PAUSE_TOGGLE_MS) % 2 == 0;
        chatTrack.update(playing, levels);
        playerTrack.update(true, levels);
        miniTrack.update(true, null);
        videoTrack.update(true, null);
        playPause.setPause(playing, true);
        playPause.update();

        final float pad = AndroidUtilities.dp(20);
        final float gap = AndroidUtilities.dp(5);
        textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));

        // 1. song in a chat: morphing play / pause button + loudness wave with a bar thumb
        float top = AndroidUtilities.dp(6);
        float cy = top + AndroidUtilities.dp(34);
        float bx = pad + AndroidUtilities.dp(18);
        paint.setColor(accent);
        playPause.drawContainer(canvas, bx, cy, AndroidUtilities.dp(18), 1f, paint);
        playPause.drawIcon(canvas, bx, cy, AndroidUtilities.dp(18) * 0.46f, onAccent, 255);
        float left = bx + AndroidUtilities.dp(18 + 14), right = getWidth() - pad;
        canvas.drawText(labels[0], left, top + AndroidUtilities.dp(14), textPaint);
        float x = left + (right - left) * progress;
        chatTrack.drawLoudness(canvas, left, x - gap, left, right, cy, AndroidUtilities.dp(2.2f), AndroidUtilities.dp(6), AndroidUtilities.dp(9), levels, accent);
        chatTrack.drawLoudness(canvas, x + gap, right, left, right, cy, AndroidUtilities.dp(2.2f), AndroidUtilities.dp(6), AndroidUtilities.dp(9), levels, trackColor);
        M3ExpressiveTrack.drawThumb(canvas, x, cy, AndroidUtilities.dp(2), AndroidUtilities.dp(9), paint);

        // 2. full-screen player: the bigger loudness wave
        top += AndroidUtilities.dp(ROW_DP);
        left = pad;
        canvas.drawText(labels[1], left, top + AndroidUtilities.dp(14), textPaint);
        cy = top + AndroidUtilities.dp(36);
        x = left + (right - left) * progress;
        playerTrack.drawLoudness(canvas, left, x - gap, left, right, cy, AndroidUtilities.dp(2.5f), AndroidUtilities.dp(8), AndroidUtilities.dp(10), levels, accent);
        playerTrack.drawLoudness(canvas, x + gap, right, left, right, cy, AndroidUtilities.dp(2.5f), AndroidUtilities.dp(8), AndroidUtilities.dp(10), levels, trackColor);
        M3ExpressiveTrack.drawThumb(canvas, x, cy, AndroidUtilities.dp(2), AndroidUtilities.dp(11), paint);

        // 3. mini player at the top of chats: a thin wave under the title
        top += AndroidUtilities.dp(ROW_DP);
        canvas.drawText(labels[2], left, top + AndroidUtilities.dp(14), textPaint);
        cy = top + AndroidUtilities.dp(36);
        miniTrack.drawLine(canvas, x + gap, right, cy, AndroidUtilities.dp(2), trackColor);
        miniTrack.drawWavy(canvas, left, x - gap, cy, AndroidUtilities.dp(2), AndroidUtilities.dp(1.5f), AndroidUtilities.dp(18), accent);
        M3ExpressiveTrack.drawThumb(canvas, x, cy, AndroidUtilities.dp(1.5f), AndroidUtilities.dp(4.5f), paint);

        // 4. video player: wavy played part, flat rest
        top += AndroidUtilities.dp(ROW_DP);
        canvas.drawText(labels[3], left, top + AndroidUtilities.dp(14), textPaint);
        cy = top + AndroidUtilities.dp(36);
        videoTrack.drawLine(canvas, x + gap, right, cy, AndroidUtilities.dp(3), trackColor);
        videoTrack.drawWavy(canvas, left, x - gap, cy, AndroidUtilities.dp(3), AndroidUtilities.dp(2.5f), AndroidUtilities.dp(20), accent);
        M3ExpressiveTrack.drawThumb(canvas, x, cy, AndroidUtilities.dp(2), AndroidUtilities.dp(8), paint);

        M3ExpressiveTrack.invalidateNextFrame(this);
    }
}
