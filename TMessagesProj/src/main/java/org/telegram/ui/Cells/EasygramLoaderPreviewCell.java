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
import android.text.TextUtils;
import android.view.View;

import androidx.core.graphics.ColorUtils;


import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.M3ExpressiveLoader;

/** Live preview of the Material 3 Expressive loaders, shown under the M3E switch in "Other settings". */
public class EasygramLoaderPreviewCell extends View {

    private static final long DOWNLOAD_CYCLE_MS = 3600;
    private static final long DOWNLOAD_FILL_MS = 2800;
    private static final long BOUNCE_MS = 600;

    private final M3ExpressiveLoader.Morph bigMorph = new M3ExpressiveLoader.Morph();
    private final M3ExpressiveLoader.Morph buttonMorph = new M3ExpressiveLoader.Morph();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final String[] captions;

    public EasygramLoaderPreviewCell(Context context) {
        super(context);
        textPaint.setTextSize(AndroidUtilities.dp(13));
        textPaint.setTextAlign(Paint.Align.CENTER);
        captions = new String[] {
                LocaleController.getString(R.string.EasygramLoaderPreviewLoading),
                LocaleController.getString(R.string.EasygramLoaderPreviewButton),
                LocaleController.getString(R.string.EasygramLoaderPreviewDownload),
        };
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(108), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
        final float column = getWidth() / 3f;
        final float cy = AndroidUtilities.dp(44);

        // 1. indeterminate spinner (RadialProgressView, dialogs, screens)
        bigMorph.draw(canvas, column * 0.5f, cy, AndroidUtilities.dp(20), accent);

        // 2. spinner inside a button (CircularProgressDrawable)
        float bx = column * 1.5f;
        rect.set(bx - AndroidUtilities.dp(44), cy - AndroidUtilities.dp(20), bx + AndroidUtilities.dp(44), cy + AndroidUtilities.dp(20));
        fillPaint.setColor(accent);
        canvas.drawRoundRect(rect, AndroidUtilities.dp(20), AndroidUtilities.dp(20), fillPaint);
        buttonMorph.draw(canvas, bx, cy, AndroidUtilities.dp(10), onAccent);

        // 3. download button in chats (RadialProgress2): growing shape, stop square, bounce when done
        drawDownloadButton(canvas, column * 2.5f, cy, accent, onAccent);

        textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        float maxWidth = column - AndroidUtilities.dp(8);
        for (int i = 0; i < captions.length; i++) {
            CharSequence text = TextUtils.ellipsize(captions[i], textPaint, maxWidth, TextUtils.TruncateAt.END);
            canvas.drawText(text, 0, text.length(), column * (i + 0.5f), AndroidUtilities.dp(94), textPaint);
        }

        postInvalidateOnAnimation();
    }

    private void drawDownloadButton(Canvas canvas, float cx, float cy, int accent, int onAccent) {
        final float outer = AndroidUtilities.dp(22);
        long t = SystemClock.uptimeMillis() % DOWNLOAD_CYCLE_MS;
        float progress = Math.min(1f, t / (float) DOWNLOAD_FILL_MS);
        progress = (float) (1 - Math.pow(1 - progress, 2)); // ease out, like a download slowing down near the end

        // same success bounce as the chat button once the download is complete
        long sinceDone = t - DOWNLOAD_FILL_MS;
        float bounce = 1f;
        if (sinceDone >= 0 && sinceDone < BOUNCE_MS) {
            float k = sinceDone / (float) BOUNCE_MS;
            bounce = 1f + 0.25f * (float) (Math.exp(-4 * k) * Math.sin(2.5 * Math.PI * k));
        }
        canvas.save();
        canvas.scale(bounce, bounce, cx, cy);
        fillPaint.setColor(ColorUtils.setAlphaComponent(accent, 0x38));
        canvas.drawCircle(cx, cy, outer, fillPaint);
        fillPaint.setColor(accent);
        M3ExpressiveLoader.drawGrowingShape(canvas, path, cx, cy, outer, progress, fillPaint);
        fillPaint.setColor(onAccent);
        M3ExpressiveLoader.drawStop(canvas, rect, cx, cy, AndroidUtilities.dp(5), fillPaint);
        canvas.restore();
    }
}
