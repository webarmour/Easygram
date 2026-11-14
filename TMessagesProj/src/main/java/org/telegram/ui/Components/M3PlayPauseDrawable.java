/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MusicBeatTracker;
import org.telegram.ui.OtherSettingsActivity;

/**
 * Drop-in replacement for {@link PlayPauseDrawable}: with the Material 3 Expressive setting on it draws the
 * morphing {@link M3PlayPauseIcon} (optionally bumping in time with the music), otherwise the stock drawable.
 */
public class M3PlayPauseDrawable extends PlayPauseDrawable {

    private final M3PlayPauseIcon icon = new M3PlayPauseIcon();
    private final int sizePx;
    private final boolean reactToBeat;
    private View parentView;
    private int color = Color.WHITE;
    private int alpha = 255;
    private ColorFilter colorFilter;

    public M3PlayPauseDrawable(int size, boolean reactToBeat) {
        super(size);
        this.sizePx = AndroidUtilities.dp(size);
        this.reactToBeat = reactToBeat;
    }

    @Override
    public void setPause(boolean pause, boolean animated) {
        super.setPause(pause, animated);
        icon.setPause(pause, animated);
        if (parentView != null) parentView.invalidate();
        invalidateSelf();
    }

    @Override
    public void setParent(View parent) {
        super.setParent(parent);
        parentView = parent;
    }

    @Override
    public void setColor(int color) {
        super.setColor(color);
        this.color = color;
    }

    @Override
    public void setAlpha(int alpha) {
        super.setAlpha(alpha);
        this.alpha = alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        super.setColorFilter(colorFilter);
        this.colorFilter = colorFilter;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!OtherSettingsActivity.isMaterialExpressiveEnabled()) {
            super.draw(canvas);
            return;
        }
        boolean animating = icon.update();
        float scale = 1f;
        if (reactToBeat && icon.isPause()) {
            MusicBeatTracker tracker = MusicBeatTracker.getInstance();
            tracker.markInterest();
            scale += 0.12f * tracker.getPulse();
            animating = true;
        }
        Rect b = getBounds();
        // ImageView tints arrive as a color filter (MULTIPLY on white), like the stock drawable
        icon.setColorFilter(colorFilter);
        icon.drawIcon(canvas, b.centerX(), b.centerY(), sizePx * 0.5f * scale, colorFilter != null ? Color.WHITE : color, alpha);
        if (animating) {
            Callback cb = getCallback();
            if (cb instanceof View) {
                M3ExpressiveTrack.invalidateNextFrame((View) cb);
            } else {
                invalidateSelf();
            }
            if (parentView != null && parentView != cb) M3ExpressiveTrack.invalidateNextFrame(parentView);
        }
    }
}
