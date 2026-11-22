/*
 * This file is part of easygram, a fork of Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright webarmour, 2026.
 */

package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.asr.EasygramAsrModelManager;
import org.telegram.messenger.asr.EasygramAsrModels;
import org.telegram.messenger.asr.EasygramAsrSettings;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.M3ExpressiveLoader;
import org.telegram.ui.Components.RadioButton;

/**
 * One speech recognition model in Settings → Local AI → Voice transcription: choice, name, status and an
 * M3 Expressive download button (shape-morphing loader while queued, a growing scalloped shape while it
 * downloads, a bouncing check when it is ready, then the delete button), with a wavy progress line below.
 */
public class EasygramAsrModelCell extends FrameLayout {

    public interface Delegate {
        void onAction(EasygramAsrModels.Model model, int state);
    }

    private final RadioButton radio;
    private final TextView title, subtitle;
    private final DownloadButton button;
    private EasygramAsrModels.Model model;
    private boolean divider;
    private int state = -1;
    private float targetProgress, shownProgress;
    private float lineAlpha; // wavy line visibility, animated
    private long lastFrame;
    private Delegate delegate;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    public EasygramAsrModelCell(Context context) {
        super(context);
        radio = new RadioButton(context);
        radio.setSize(dp(20));
        radio.setColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_radioBackgroundChecked));
        addView(radio, LayoutHelper.createFrame(22, 22, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 20, 0, 20, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 72 : 64, 12, LocaleController.isRTL ? 64 : 72, 14));

        button = new DownloadButton(context);
        button.setOnClickListener(v -> {
            if (delegate != null && model != null) {
                delegate.onAction(model, state);
            }
        });
        addView(button, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 12, 0, 12, 0));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeWidth(dp(3));
        setWillNotDraw(false);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public EasygramAsrModels.Model getModel() {
        return model;
    }

    public void set(EasygramAsrModels.Model model, boolean divider, boolean animated, boolean selected) {
        boolean sameModel = this.model == model;
        this.model = model;
        this.divider = divider;
        int newState = EasygramAsrModelManager.getState(model);
        targetProgress = EasygramAsrModelManager.getProgress(model);
        animated &= sameModel && state != -1;
        if (!animated) {
            shownProgress = targetProgress;
            lineAlpha = isLoading(newState) ? 1f : 0f;
        }
        button.setState(newState, animated);
        state = newState;
        boolean lid = EasygramAsrModels.LANG_ID.equals(model.lang);
        radio.setVisibility(lid ? INVISIBLE : VISIBLE); // nothing to choose: it is used whenever it is downloaded
        radio.setChecked(!lid && selected, animated);

        if (lid) {
            title.setText(model.title);
        } else {
            String tier = LocaleController.getString(model.tier == EasygramAsrModels.TIER_LITE ? R.string.EasygramAsrTierLite
                    : model.tier == EasygramAsrModels.TIER_OPTIMAL ? R.string.EasygramAsrTierOptimal : R.string.EasygramAsrTierMax);
            title.setText(tier + " · " + model.title);
        }

        long bytes = EasygramAsrModelManager.downloadSize(model);
        String size = AndroidUtilities.formatFileSize(bytes);
        String status;
        boolean error = false;
        switch (state) {
            case EasygramAsrModelManager.STATE_UPDATE:
                status = LocaleController.getString(R.string.EasygramAsrUpdateAvailable) + " · " + size;
                break;
            case EasygramAsrModelManager.STATE_READY:
                status = LocaleController.formatString(R.string.EasygramAsrDownloaded, size);
                break;
            case EasygramAsrModelManager.STATE_DOWNLOADING:
                status = LocaleController.formatString(R.string.EasygramAsrDownloading, (int) (targetProgress * 100),
                        AndroidUtilities.formatFileSize((long) (bytes * targetProgress)), size);
                break;
            case EasygramAsrModelManager.STATE_QUEUED:
                status = LocaleController.formatString(R.string.EasygramAsrQueued, size);
                break;
            default: {
                int e = EasygramAsrModelManager.getLastError(model);
                error = e != EasygramAsrModelManager.ERROR_NONE;
                if (e == EasygramAsrModelManager.ERROR_NETWORK) {
                    status = LocaleController.getString(R.string.EasygramAsrErrorNetwork);
                } else if (e == EasygramAsrModelManager.ERROR_NO_SPACE) {
                    status = LocaleController.getString(R.string.EasygramAsrErrorNoSpace);
                } else if (e == EasygramAsrModelManager.ERROR_CHECKSUM) {
                    status = LocaleController.getString(R.string.EasygramAsrErrorChecksum);
                } else {
                    status = LocaleController.formatString(R.string.EasygramAsrNotDownloaded, size);
                }
            }
        }
        if (state == EasygramAsrModelManager.STATE_UPDATE) {
            int lastError = EasygramAsrModelManager.getLastError(model);
            if (lastError != EasygramAsrModelManager.ERROR_NONE) {
                error = true;
                int message = lastError == EasygramAsrModelManager.ERROR_NO_SPACE ? R.string.EasygramAsrErrorNoSpace
                        : lastError == EasygramAsrModelManager.ERROR_CHECKSUM ? R.string.EasygramAsrErrorChecksum
                        : R.string.EasygramAsrErrorNetwork;
                status += " · " + LocaleController.getString(message);
            }
        }
        StringBuilder sb = new StringBuilder(status);
        if (!error && !isLoading(state)) {
            if (model.punctuation) {
                sb.append(" · ").append(LocaleController.getString(R.string.EasygramAsrPunctuation));
            }
            if (model.tier != EasygramAsrModels.TIER_LITE && EasygramAsrSettings.isWeakDevice()) {
                sb.append(" · ").append(LocaleController.getString(R.string.EasygramAsrSlow));
            }
        }
        subtitle.setText(sb);
        subtitle.setTextColor(Theme.getColor(error ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText2));
        invalidate();
    }

    private static boolean isLoading(int state) {
        return state == EasygramAsrModelManager.STATE_DOWNLOADING || state == EasygramAsrModelManager.STATE_QUEUED;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (getMeasuredHeight() < dp(68)) {
            setMeasuredDimension(getMeasuredWidth(), dp(68));
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;
        // smooth progress (downloads report in steps) and fade the line in / out
        shownProgress += (targetProgress - shownProgress) * Math.min(1f, dt / 120f);
        float lineTarget = isLoading(state) ? 1f : 0f;
        lineAlpha += Math.signum(lineTarget - lineAlpha) * Math.min(Math.abs(lineTarget - lineAlpha), dt / 220f);
        boolean animating = Math.abs(targetProgress - shownProgress) > 0.001f || lineAlpha != lineTarget;

        if (lineAlpha > 0f) {
            float left = dp(LocaleController.isRTL ? 72 : 64), right = getWidth() - dp(LocaleController.isRTL ? 64 : 72);
            float y = getHeight() - dp(7);
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            float ease = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(lineAlpha);
            canvas.save();
            canvas.scale(1f, ease, 0, y); // the line unfolds vertically, like the M3 indicator entering
            int active = Theme.multAlpha(accent, ease);
            int track = Theme.multAlpha(accent, .22f * ease);
            float progress = state == EasygramAsrModelManager.STATE_QUEUED ? 0f : shownProgress;
            if (LocaleController.isRTL) {
                canvas.scale(-1f, 1f, (left + right) / 2f, y);
            }
            if (M3ExpressiveLoader.enabled()) {
                M3ExpressiveLoader.drawWavyLinear(canvas, linePath, left, right, y, progress, linePaint, active, track, dp(2.2f));
            } else {
                linePaint.setColor(track);
                canvas.drawLine(left, y, right, y, linePaint);
                linePaint.setColor(active);
                if (progress > 0) canvas.drawLine(left, y, left + (right - left) * progress, y, linePaint);
            }
            canvas.restore();
            animating = true; // the wave travels
        } else if (divider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : dp(64), getHeight() - 1, getWidth() - (LocaleController.isRTL ? dp(64) : 0), getHeight() - 1, Theme.dividerPaint);
        }
        button.setProgress(shownProgress);
        if (animating) {
            invalidate();
        } else {
            lastFrame = 0;
        }
    }

    /** The round M3E button on the right: download / loader / growing progress shape / check / delete. */
    private static class DownloadButton extends View {
        private static final long SUCCESS_MS = 1500, SUCCESS_FADE_MS = 320, MIX_MS = 340, PRESS_MS = 140;

        private int state = -1;
        private float progress;
        private long successStart = -1, bounceStart = -1, mixLast;
        private float mix; // 0 round button, 1 progress shape (eased when drawn)
        private float press; // 0..1 pressed scale-down
        private boolean pressed;

        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path(), glyph = new Path(), segment = new Path();
        private final PathMeasure measure = new PathMeasure();
        private final RectF tmp = new RectF();
        private final M3ExpressiveLoader.Morph morph = new M3ExpressiveLoader.Morph();
        private final Drawable trash;

        DownloadButton(Context context) {
            super(context);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
            icon.setStrokeWidth(dp(2));
            trash = ContextCompat.getDrawable(context, R.drawable.msg_delete).mutate();
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        }

        void setState(int newState, boolean animated) {
            int old = state;
            state = newState;
            if (!animated || old == -1) {
                successStart = -1;
                mix = isLoading(newState) ? 1f : 0f;
                morph.reset();
            } else if (old != newState) {
                if (isLoading(old) && newState == EasygramAsrModelManager.STATE_READY) {
                    successStart = SystemClock.uptimeMillis();
                    bounceStart = successStart;
                    try {
                        performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.KEYBOARD_TAP,
                                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                    } catch (Exception ignore) {
                    }
                } else {
                    bounceStart = SystemClock.uptimeMillis(); // a small bounce on every change: start, cancel, delete
                    if (old == EasygramAsrModelManager.STATE_NONE) {
                        morph.reset();
                    }
                }
            }
            invalidate();
        }

        void setProgress(float progress) {
            if (Math.abs(this.progress - progress) > 0.0005f) {
                this.progress = progress;
                invalidate();
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressed = true;
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pressed = false;
                if (action == MotionEvent.ACTION_UP) {
                    bounceStart = SystemClock.uptimeMillis();
                }
                invalidate();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            float dt = mixLast == 0 ? 16 : Math.min(48, now - mixLast);
            mixLast = now;
            boolean animating = false;

            press += (pressed ? 1 : -1) * dt / PRESS_MS;
            press = Math.max(0f, Math.min(1f, press));
            animating |= press > 0f && press < 1f;

            boolean loading = isLoading(state);
            float mixTarget = loading || successStart >= 0 && now - successStart < SUCCESS_MS ? 1f : 0f;
            mix += Math.signum(mixTarget - mix) * Math.min(Math.abs(mixTarget - mix), dt / MIX_MS);
            animating |= mix != mixTarget;
            float mixE = CubicBezierInterpolator.EASE_BOTH.getInterpolation(mix);

            float bounce = 1f;
            if (bounceStart >= 0) {
                float t = (now - bounceStart) / (successStart == bounceStart ? 650f : 420f);
                if (t >= 1f) {
                    bounceStart = -1;
                } else {
                    bounce = M3ExpressiveLoader.bounce(t, successStart == bounceStart ? 0.28f : 0.14f);
                    animating = true;
                }
            }
            float scale = bounce * (1f - 0.1f * CubicBezierInterpolator.EASE_OUT.getInterpolation(press));

            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = dp(19);
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            int onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
            canvas.save();
            canvas.scale(scale, scale, cx, cy);

            if (successStart >= 0) {
                float t = now - successStart;
                if (t >= SUCCESS_MS + SUCCESS_FADE_MS) {
                    successStart = -1;
                    drawTrash(canvas, cx, cy, 1f, 1f);
                } else {
                    float fade = Math.max(0f, (t - SUCCESS_MS) / SUCCESS_FADE_MS);
                    float fadeE = CubicBezierInterpolator.EASE_BOTH.getInterpolation(fade);
                    // the progress shape has grown into a full accent circle; a check draws itself in it
                    fill.setColor(Theme.multAlpha(accent, 1f - fadeE));
                    canvas.drawCircle(cx, cy, r * (1f - 0.35f * fadeE), fill);
                    float draw = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Math.min(1f, t / 380f));
                    icon.setColor(Theme.multAlpha(onAccent, 1f - fadeE));
                    drawCheck(canvas, cx, cy, dp(8) * (1f - 0.35f * fadeE), draw);
                    if (fade > 0) {
                        drawTrash(canvas, cx, cy, fadeE, 0.6f + 0.4f * M3ExpressiveLoader.springIn(fade));
                    }
                    animating = true;
                }
            } else if (state == EasygramAsrModelManager.STATE_UPDATE) {
                icon.setColor(accent);
                float rIcon = dp(8);
                tmp.set(cx - rIcon, cy - rIcon, cx + rIcon, cy + rIcon);
                canvas.drawArc(tmp, 0, 300, false, icon);
                canvas.drawLine(cx + rIcon / 2, cy - rIcon, cx + rIcon / 2, cy - dp(3), icon);
                canvas.drawLine(cx + rIcon / 2, cy - dp(3), cx - dp(1), cy - dp(4), icon);
            } else if (state == EasygramAsrModelManager.STATE_READY && mix <= 0f) {
                drawTrash(canvas, cx, cy, 1f, 1f);
            } else {
                boolean m3 = M3ExpressiveLoader.enabled();
                disc.setColor(Theme.multAlpha(accent, .16f));
                canvas.drawCircle(cx, cy, r, disc);
                float shown = state == EasygramAsrModelManager.STATE_QUEUED ? 0f : progress;
                if (state == EasygramAsrModelManager.STATE_QUEUED) {
                    // waiting in the queue: the shape-morphing loader
                    float s = mixE;
                    if (m3) {
                        morph.draw(canvas, cx, cy, r * 0.62f * s, Theme.multAlpha(accent, s));
                    } else {
                        drawSpinner(canvas, cx, cy, r * 0.6f, accent, s);
                    }
                    animating = true;
                } else if (mixE > 0f) {
                    fill.setColor(accent);
                    if (m3) {
                        M3ExpressiveLoader.buildGrowingShape(shape, cx, cy, r, shown, mixE);
                        canvas.save();
                        canvas.scale(mixE, mixE, cx, cy);
                        canvas.drawPath(shape, fill);
                        canvas.restore();
                    } else {
                        icon.setColor(accent);
                        tmp.set(cx - r + dp(2), cy - r + dp(2), cx + r - dp(2), cy + r - dp(2));
                        canvas.drawArc(tmp, -90, 360 * shown * mixE, false, icon);
                    }
                    // stop glyph while it downloads
                    fill.setColor(Theme.multAlpha(m3 ? onAccent : accent, mixE));
                    M3ExpressiveLoader.drawStop(canvas, tmp, cx, cy, dp(5) * mixE, fill);
                    animating = true;
                }
                if (mixE < 1f && !loading) {
                    // download arrow, sliding up into place as the shape leaves
                    float a = 1f - mixE;
                    icon.setColor(Theme.multAlpha(accent, a));
                    drawArrow(canvas, cx, cy + dp(6) * mixE, dp(9));
                }
            }
            canvas.restore();
            if (animating) {
                invalidate();
            } else {
                mixLast = 0;
            }
        }

        private void drawArrow(Canvas canvas, float cx, float cy, float s) {
            glyph.rewind();
            glyph.moveTo(cx, cy - s * 0.75f);
            glyph.lineTo(cx, cy + s * 0.25f);
            glyph.moveTo(cx - s * 0.45f, cy - s * 0.15f);
            glyph.lineTo(cx, cy + s * 0.3f);
            glyph.lineTo(cx + s * 0.45f, cy - s * 0.15f);
            glyph.moveTo(cx - s * 0.55f, cy + s * 0.75f);
            glyph.lineTo(cx + s * 0.55f, cy + s * 0.75f);
            canvas.drawPath(glyph, icon);
        }

        private void drawCheck(Canvas canvas, float cx, float cy, float s, float drawn) {
            glyph.rewind();
            glyph.moveTo(cx - s * 0.75f, cy + s * 0.02f);
            glyph.lineTo(cx - s * 0.2f, cy + s * 0.55f);
            glyph.lineTo(cx + s * 0.8f, cy - s * 0.5f);
            measure.setPath(glyph, false);
            segment.rewind();
            measure.getSegment(0, measure.getLength() * drawn, segment, true);
            float w = icon.getStrokeWidth();
            icon.setStrokeWidth(dp(2.4f));
            canvas.drawPath(segment, icon);
            icon.setStrokeWidth(w);
        }

        private void drawTrash(Canvas canvas, float cx, float cy, float alpha, float scale) {
            int w = trash.getIntrinsicWidth(), h = trash.getIntrinsicHeight();
            trash.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            trash.setAlpha((int) (255 * alpha));
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
            trash.setBounds((int) (cx - w / 2f), (int) (cy - h / 2f), (int) (cx + w / 2f), (int) (cy + h / 2f));
            trash.draw(canvas);
            canvas.restore();
        }

        private void drawSpinner(Canvas canvas, float cx, float cy, float r, int color, float alpha) {
            float angle = (SystemClock.uptimeMillis() % 1100) / 1100f * 360f;
            icon.setColor(Theme.multAlpha(color, alpha));
            tmp.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(tmp, angle, 270, false, icon);
        }
    }
}
