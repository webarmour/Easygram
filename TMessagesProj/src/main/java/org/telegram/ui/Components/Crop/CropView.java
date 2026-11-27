package org.telegram.ui.Components.Crop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.BubbleActivity;
import org.telegram.ui.Components.PaintingOverlay;
import org.telegram.ui.Components.VideoEditTextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

public class CropView extends FrameLayout implements CropAreaView.AreaViewListener, CropGestureDetector.CropGestureListener {
    private static final float EPSILON = 0.00001f;
    private static final int RESULT_SIDE = 1280;
    private static final float MAX_SCALE = 30.0f;

    public CropAreaView areaView;
    private ImageView imageView;
    private Matrix overlayMatrix;
    private PaintingOverlay paintingOverlay;
    private VideoEditTextureView videoEditTextureView;
    private CropTransform cropTransform;

    private RectF previousAreaRect;
    private RectF initialAreaRect;
    private float rotationStartScale;

    private boolean inBubbleMode;

    private CropRectangle tempRect;
    private Matrix tempMatrix;

    private Bitmap bitmap;
    private boolean freeform;
    public float bottomPadding;
    public float topPadding;

    private boolean animating;
    private CropGestureDetector detector;
    private ViewTreeObserver.OnPreDrawListener pendingPreDrawListener;
    private Runnable pendingInitAction;
    private ValueAnimator fitAnimator;
    private ValueAnimator maximizeAnimator;

    float[] values = new float[9];

    private boolean hasAspectRatioDialog;

    private boolean isVisible;

    protected int bitmapRotation;

    private boolean hasValidAreaSize() {
        float statusBarHeight = (Build.VERSION.SDK_INT >= 21 && !inBubbleMode ? AndroidUtilities.statusBarHeight : 0);
        float sidePadding = AndroidUtilities.dp(16);
        float measuredW = areaView.getMeasuredWidth() - 2 * sidePadding;
        float measuredH = areaView.getMeasuredHeight() - bottomPadding - topPadding - statusBarHeight - 2 * sidePadding;
        return measuredW > 0 && measuredH > 0;
    }

    private void cancelPendingPreDraw() {
        if (pendingPreDrawListener != null) {
            ViewTreeObserver observer = areaView.getViewTreeObserver();
            if (observer != null && observer.isAlive()) {
                observer.removeOnPreDrawListener(pendingPreDrawListener);
            }
            pendingPreDrawListener = null;
        }
        pendingInitAction = null;
    }

    private void cancelAnimators() {
        if (fitAnimator != null) {
            fitAnimator.cancel();
            fitAnimator = null;
        }
        if (maximizeAnimator != null) {
            maximizeAnimator.cancel();
            maximizeAnimator = null;
        }
        areaView.resetAnimator();
        animating = false;
    }

    public void setSubtitle(String subtitle) {
        areaView.setSubtitle(subtitle);
    }

    public class CropState {
        public float width;
        public float height;

        public float x;
        public float y;
        public float scale;
        public float minimumScale;
        public float baseRotation;
        public float orientation;
        public float rotation;
        public boolean mirrored;
        public Matrix matrix;

        private CropState(int w, int h, int bRotation) {
            width = w;
            height = h;
            x = 0.0f;
            y = 0.0f;
            scale = 1.0f;
            baseRotation = bRotation;
            rotation = 0.0f;
            matrix = new Matrix();
        }

        private void update(int w, int h, int rotation) {
            if (w <= 0 || h <= 0) {
                return;
            }
            if (width <= 0 || height <= 0 || minimumScale <= 0 || !Float.isFinite(minimumScale) || scale <= 0 || !Float.isFinite(scale)) {
                width = w;
                height = h;
                if (hasValidAreaSize()) {
                    CropView.this.reset();
                }
                return;
            }
            float ps = width / w;
            scale *= ps;
            width = w;
            height = h;
            updateMinimumScale();
            matrix.getValues(values);
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(values[2], values[5]);
            updateMatrix();
        }

        private boolean hasChanges() {
            return Math.abs(x) > EPSILON || Math.abs(y) > EPSILON || Math.abs(scale - minimumScale) > EPSILON || Math.abs(rotation) > EPSILON || Math.abs(orientation) > EPSILON;
        }

        private float getWidth() {
            return width;
        }

        private float getHeight() {
            return height;
        }

        private float getOrientedWidth() {
            return (orientation + baseRotation) % 180 != 0 ? height : width;
        }

        private float getOrientedHeight() {
            return (orientation + baseRotation) % 180 != 0 ? width : height;
        }

        private void translate(float x, float y) {
            this.x += x;
            this.y += y;
            matrix.postTranslate(x, y);
        }

        private float getX() {
            return x;
        }

        private float getY() {
            return y;
        }

        private void scale(float s, float pivotX, float pivotY) {
            scale *= s;
            matrix.postScale(s, s, pivotX, pivotY);
        }

        private float getScale() {
            return scale;
        }

        private float getMinimumScale() {
            return minimumScale;
        }

        private void rotate(float angle, float pivotX, float pivotY) {
            rotation += angle;
            matrix.postRotate(angle, pivotX, pivotY);
        }

        private float getRotation() {
            return rotation;
        }

        private boolean isMirrored() {
            return mirrored;
        }

        private float getOrientation() {
            return orientation + baseRotation;
        }

        private int getOrientationOnly() {
            return (int) orientation;
        }

        private float getBaseRotation() {
            return baseRotation;
        }

        private void mirror() {
            mirrored = !mirrored;
        }

        private void reset(float orient) {
            matrix.reset();

            x = 0.0f;
            y = 0.0f;
            rotation = 0.0f;
            orientation = orient;

            updateMinimumScale();
            scale = minimumScale;
            matrix.postScale(scale, scale);
        }

        private void updateMinimumScale() {
            float w = (orientation + baseRotation) % 180 != 0 ? height : width;
            float h = (orientation + baseRotation) % 180 != 0 ? width : height;
            float cropW = areaView.getCropWidth();
            float cropH = areaView.getCropHeight();
            if (w <= 0 || h <= 0 || cropW <= 0 || cropH <= 0 || !Float.isFinite(cropW) || !Float.isFinite(cropH)) {
                return;
            }
            if (freeform) {
                minimumScale = cropW / w;
            } else {
                float wScale = cropW / w;
                float hScale = cropH / h;
                minimumScale = Math.max(wScale, hScale);
            }
        }

        private void getConcatMatrix(Matrix toMatrix) {
            toMatrix.postConcat(matrix);
        }

        private Matrix getMatrix() {
            Matrix m = new Matrix();
            m.set(matrix);
            return m;
        }
    }

    public CropState state;

    public interface CropViewListener {
        void onChange(boolean reset);
        void onUpdate();
        void onAspectLock(boolean enabled);
        void onTapUp();
    }

    private CropViewListener listener;

    public float getStateOrientation() {
        return state == null ? 0 : state.orientation;
    }

    public float getStateFullOrientation() {
        return state == null ? 0 : state.baseRotation + state.orientation;
    }

    public boolean getStateMirror() {
        return state != null && state.mirrored;
    }

    public CropView(Context context) {
        super(context);

        inBubbleMode = context instanceof BubbleActivity;

        previousAreaRect = new RectF();
        initialAreaRect = new RectF();
        overlayMatrix = new Matrix();
        tempRect = new CropRectangle();
        tempMatrix = new Matrix();
        animating = false;

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        addView(imageView);

        detector = new CropGestureDetector(context);
        detector.setOnGestureListener(this);

        areaView = new CropAreaView(context);
        areaView.setListener(this);
        addView(areaView);
    }

    public boolean isReady() {
        return state != null && pendingPreDrawListener == null && !animating && !areaView.isAnimating()
                && state.width > 0 && state.height > 0
                && state.minimumScale > 0 && Float.isFinite(state.minimumScale)
                && state.scale > 0 && Float.isFinite(state.scale)
                && areaView.getCropWidth() > 0 && Float.isFinite(areaView.getCropWidth())
                && areaView.getCropHeight() > 0 && Float.isFinite(areaView.getCropHeight())
                && !detector.isScaling() && !detector.isDragging() && !areaView.isDragging();
    }

    public void setListener(CropViewListener l) {
        listener = l;
    }

    public void setBottomPadding(float value) {
        bottomPadding = value;
        areaView.setBottomPadding(value);
    }

    public void setTopPadding(float value) {
        topPadding = value;
        areaView.setTopPadding(value);
    }

    public void setAspectRatio(float ratio) {
        areaView.setActualRect(ratio);
    }

    public void stop() {
        cancelPendingPreDraw();
        cancelAnimators();
        detector.reset();
        areaView.resetTouchState();
        state = null;
    }

    private void scheduleInit(final int w, final int h, final MediaController.CropState restoreState) {
        cancelPendingPreDraw();
        final Runnable initAction = () -> {
            cancelPendingPreDraw();
            if (state == null || !hasValidAreaSize()) {
                return;
            }
            reset();
            if (restoreState != null) {
                if (restoreState.lockedAspectRatio > 0.0001f && Float.isFinite(restoreState.lockedAspectRatio)) {
                    areaView.setLockedAspectRatio(restoreState.lockedAspectRatio);
                    if (listener != null) {
                        listener.onAspectLock(true);
                    }
                }
                setFreeform(restoreState.freeform);

                float aspect = areaView.getAspectRatio();
                float stateWidth;
                float stateHeight;
                int rotatedW;
                int rotatedH;
                if (restoreState.transformRotation == 90 || restoreState.transformRotation == 270) {
                    aspect = 1.0f / aspect;
                    stateWidth = state.height;
                    stateHeight = state.width;
                    rotatedW = h;
                    rotatedH = w;
                } else {
                    stateWidth = state.width;
                    stateHeight = state.height;
                    rotatedW = w;
                    rotatedH = h;
                }

                int orientation = restoreState.transformRotation;
                if (freeform && areaView.getLockAspectRatio() > 0) {
                    areaView.setLockedAspectRatio(1.0f / areaView.getLockAspectRatio());
                    areaView.setActualRect(areaView.getLockAspectRatio());
                } else {
                    areaView.setBitmap(getCurrentWidth(), getCurrentHeight(), (orientation + state.getBaseRotation()) % 180 != 0, freeform);
                }
                state.reset(orientation);

                float safePw = Float.isFinite(restoreState.cropPw) && restoreState.cropPw > 0 ? restoreState.cropPw : 1.0f;
                float safePh = Float.isFinite(restoreState.cropPh) && restoreState.cropPh > 0 ? restoreState.cropPh : 1.0f;
                float targetAspect = aspect * safePw / safePh;
                if (Float.isFinite(targetAspect) && targetAspect > 0) {
                    areaView.setActualRect(targetAspect);
                }
                state.mirrored = restoreState.mirrored;
                if (Float.isFinite(restoreState.cropRotate)) {
                    state.rotate(restoreState.cropRotate, 0, 0);
                }
                if (state.minimumScale > 0 && Float.isFinite(state.minimumScale) && stateWidth > 0 && stateHeight > 0) {
                    if (Float.isFinite(restoreState.cropPx) && Float.isFinite(restoreState.cropPy)) {
                        state.translate(restoreState.cropPx * rotatedW * state.minimumScale, restoreState.cropPy * rotatedH * state.minimumScale);
                    }
                    float ts = Math.max(areaView.getCropWidth() / stateWidth, areaView.getCropHeight() / stateHeight) / state.minimumScale;
                    float targetScale = restoreState.cropScale * ts;
                    if (Float.isFinite(targetScale) && targetScale > 0) {
                        state.scale(targetScale, 0, 0);
                    }
                }
                updateMatrix();

                if (listener != null) {
                    listener.onChange(false);
                }
            }
        };
        pendingInitAction = initAction;
        final ViewTreeObserver.OnPreDrawListener preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (pendingPreDrawListener != this) {
                    ViewTreeObserver observer = areaView.getViewTreeObserver();
                    if (observer != null && observer.isAlive()) {
                        observer.removeOnPreDrawListener(this);
                    }
                    return true;
                }
                if (state == null) {
                    cancelPendingPreDraw();
                    return true;
                }
                if (!hasValidAreaSize()) {
                    return true;
                }
                initAction.run();
                return false;
            }
        };
        pendingPreDrawListener = preDrawListener;
        areaView.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    public void start(int rotation, boolean fform, boolean same, CropTransform transform, MediaController.CropState restoreState) {
        cancelAnimators();
        detector.reset();
        areaView.resetTouchState();
        freeform = fform;
        paintingOverlay = null;
        videoEditTextureView = null;
        cropTransform = transform;
        bitmapRotation = rotation;
        bitmap = null;
        areaView.setIsVideo(videoEditTextureView != null);
        int w = getCurrentWidth();
        int h = getCurrentHeight();
        if (state == null || !same) {
            state = new CropState(w, h, 0);
            scheduleInit(w, h, restoreState);
        } else {
            state.update(w, h, rotation);
        }
    }

    public void setBitmap(Bitmap b, int rotation, boolean fform, boolean same, PaintingOverlay overlay, CropTransform transform, VideoEditTextureView videoView, MediaController.CropState restoreState) {
        cancelAnimators();
        detector.reset();
        areaView.resetTouchState();
        freeform = fform;
        paintingOverlay = overlay;
        videoEditTextureView = videoView;
        cropTransform = transform;
        bitmapRotation = rotation;
        bitmap = b;
        areaView.setIsVideo(videoEditTextureView != null);
        if (b == null && videoView == null) {
            cancelPendingPreDraw();
            state = null;
            imageView.setImageDrawable(null);
        } else {
            int w = getCurrentWidth();
            int h = getCurrentHeight();
            if (state == null || !same) {
                state = new CropState(w, h, 0);
                scheduleInit(w, h, restoreState);
            } else {
                state.update(w, h, rotation);
            }
            imageView.setImageBitmap(videoView == null ? bitmap : null);
        }
    }

    public void willShow() {
        areaView.setFrameVisibility(true, false);
        areaView.setDimVisibility(true);
        areaView.invalidate();
    }

    public void setFreeform(boolean fform) {
        areaView.setFreeform(fform);
        freeform = fform;
    }

    public void onShow() {
        isVisible = true;
    }

    public void onHide() {
        cancelPendingPreDraw();
        cancelAnimators();
        detector.reset();
        areaView.resetTouchState();
        videoEditTextureView = null;
        paintingOverlay = null;
        isVisible = false;
    }

    public void show() {
        if (pendingInitAction != null && hasValidAreaSize()) {
            pendingInitAction.run();
        }
        if (state != null && state.minimumScale > 0 && Float.isFinite(state.minimumScale)) {
            updateCropTransform();
        }
        //imageView.setVisibility(VISIBLE);
        areaView.setDimVisibility(true);
        areaView.setFrameVisibility(true, true);
        areaView.invalidate();
    }

    public void hide() {
        cancelAnimators();
        detector.reset();
        areaView.resetTouchState();
        imageView.setVisibility(INVISIBLE);
        areaView.setDimVisibility(false);
        areaView.setFrameVisibility(false, false);
        areaView.invalidate();
    }

    public void reset() {
        reset(false);
    }

    public void reset(boolean force) {
        cancelAnimators();
        if (!hasValidAreaSize()) {
            return;
        }
        areaView.setBitmap(getCurrentWidth(), getCurrentHeight(), state != null && state.getBaseRotation() % 180 != 0, freeform);
        areaView.setLockedAspectRatio(freeform ? 0.0f : 1.0f);
        if (state != null) {
            state.reset(0);
            state.mirrored = false;
        }
        areaView.getCropRect(initialAreaRect);
        updateMatrix(force);

        resetRotationStartScale();

        if (listener != null) {
            listener.onChange(true);
            listener.onAspectLock(false);
        }
    }

    public void updateMatrix() {
        updateMatrix(false);
    }

    public void updateMatrix(boolean force) {
        if (state == null) {
            return;
        }
        overlayMatrix.reset();
        if (state.getBaseRotation() == 90 || state.getBaseRotation() == 270) {
            overlayMatrix.postTranslate(-state.getHeight() / 2, -state.getWidth() / 2);
        } else {
            overlayMatrix.postTranslate(-state.getWidth() / 2, -state.getHeight() / 2);
        }
        overlayMatrix.postRotate(state.getOrientationOnly());
        state.getConcatMatrix(overlayMatrix);
        overlayMatrix.postTranslate(areaView.getCropCenterX(), areaView.getCropCenterY());
        if (!freeform || isVisible || force) {
            updateCropTransform();
            listener.onUpdate();
        }
        invalidate();
    }

    private void fillAreaView(RectF targetRect, boolean allowZoomOut) {
        if (state == null || state.getScale() <= 0 || !Float.isFinite(state.getScale())) return;
        float cropW = areaView.getCropWidth();
        float cropH = areaView.getCropHeight();
        if (cropW <= 0 || cropH <= 0 || targetRect.width() <= 0 || targetRect.height() <= 0) {
            return;
        }

        final float[] currentScale = new float[]{1.0f};
        float scale = Math.max(targetRect.width() / cropW, targetRect.height() / cropH);
        if (!Float.isFinite(scale) || scale <= 0) {
            return;
        }

        float newScale = state.getScale() * scale;
        boolean ensureFit = false;
        if (newScale > MAX_SCALE) {
            scale = MAX_SCALE / state.getScale();
            ensureFit = true;
        }
        float statusBarHeight = (Build.VERSION.SDK_INT >= 21 && !inBubbleMode ? AndroidUtilities.statusBarHeight : 0);

        final float x = (targetRect.centerX() - imageView.getWidth() / 2) / cropW * state.getOrientedWidth();
        final float y = (targetRect.centerY() - (imageView.getHeight() - bottomPadding + statusBarHeight + topPadding) / 2) / cropH * state.getOrientedHeight();
        final float targetScale = scale;

        final boolean animEnsureFit = ensureFit;

        ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        final boolean[] cancelled = new boolean[]{false};
        animator.addUpdateListener(animation -> {
            if (state == null || cancelled[0]) return;
            float value = (Float) animation.getAnimatedValue();
            float deltaScale = (1.0f + ((targetScale - 1.0f) * value)) / currentScale[0];
            if (!Float.isFinite(deltaScale) || deltaScale <= 0) return;
            currentScale[0] *= deltaScale;
            state.scale(deltaScale, x, y);
            updateMatrix();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled[0] && animEnsureFit && state != null) {
                    fitContentInBounds(false, false, true);
                }
            }
        });
        areaView.fill(targetRect, animator, true);
        initialAreaRect.set(targetRect);
    }

    private float fitScale(RectF contentRect, float scale, float ratio) {
        float scaledW = contentRect.width() * ratio;
        float scaledH = contentRect.height() * ratio;

        float scaledX = (contentRect.width() - scaledW) / 2.0f;
        float scaledY = (contentRect.height() - scaledH) / 2.0f;

        contentRect.set(contentRect.left + scaledX, contentRect.top + scaledY, contentRect.left + scaledX + scaledW, contentRect.top + scaledY + scaledH);

        return scale * ratio;
    }

    private void fitTranslation(RectF contentRect, RectF boundsRect, PointF translation, float radians) {
        float frameLeft = boundsRect.left;
        float frameTop = boundsRect.top;
        float frameRight = boundsRect.right;
        float frameBottom = boundsRect.bottom;

        if (contentRect.left > frameLeft) {
            frameRight += contentRect.left - frameLeft;
            frameLeft = contentRect.left;
        }
        if (contentRect.top > frameTop) {
            frameBottom += contentRect.top - frameTop;
            frameTop = contentRect.top;
        }
        if (contentRect.right < frameRight) {
            frameLeft += contentRect.right - frameRight;
        }
        if (contentRect.bottom < frameBottom) {
            frameTop += contentRect.bottom - frameBottom;
        }

        float deltaX = boundsRect.centerX() - (frameLeft + boundsRect.width() / 2.0f);
        float deltaY = boundsRect.centerY() - (frameTop + boundsRect.height() / 2.0f);

        float xCompX = (float) (Math.sin(Math.PI / 2 - radians) * deltaX);
        float xCompY = (float) (Math.cos(Math.PI / 2 - radians) * deltaX);

        float yCompX = (float) (Math.cos(Math.PI / 2 + radians) * deltaY);
        float yCompY = (float) (Math.sin(Math.PI / 2 + radians) * deltaY);

        translation.set(translation.x + xCompX + yCompX, translation.y + xCompY + yCompY);
    }

    public RectF calculateBoundingBox(float w, float h, float rotation) {
        RectF result = new RectF(0, 0, w, h);
        Matrix m = new Matrix();
        m.postRotate(rotation, w / 2.0f, h / 2.0f);
        m.mapRect(result);
        return result;
    }

    public float scaleWidthToMaxSize(RectF sizeRect, RectF maxSizeRect) {
        if (sizeRect.width() <= 0 || sizeRect.height() <= 0 || maxSizeRect.width() <= 0 || maxSizeRect.height() <= 0) {
            return 0;
        }
        float w = maxSizeRect.width();
        float h = (float) Math.floor(w * sizeRect.height() / sizeRect.width());
        if (h > maxSizeRect.height()) {
            h = maxSizeRect.height();
            w = (float) Math.floor(h * sizeRect.width() / sizeRect.height());
        }
        return w;
    }

    private static class CropRectangle {
        float[] coords = new float[8];

        CropRectangle() {
        }

        void setRect(RectF rect) {
            coords[0] = rect.left;
            coords[1] = rect.top;
            coords[2] = rect.right;
            coords[3] = rect.top;
            coords[4] = rect.right;
            coords[5] = rect.bottom;
            coords[6] = rect.left;
            coords[7] = rect.bottom;
        }

        void applyMatrix(Matrix m) {
            m.mapPoints(coords);
        }

        void getRect(RectF rect) {
            rect.set(coords[0], coords[1], coords[2], coords[7]);
        }
    }

    private void fitContentInBounds(boolean allowScale, boolean maximize, boolean animated) {
        fitContentInBounds(allowScale, maximize, animated, false);
    }

    private void fitContentInBounds(final boolean allowScale, final boolean maximize, final boolean animated, final boolean fast) {
        if (state == null) {
            return;
        }
        float boundsW = areaView.getCropWidth();
        float boundsH = areaView.getCropHeight();
        float contentW = state.getOrientedWidth();
        float contentH = state.getOrientedHeight();
        float scale = state.getScale();
        if (boundsW <= 0 || boundsH <= 0 || contentW <= 0 || contentH <= 0 || scale <= 0 || !Float.isFinite(scale)) {
            return;
        }
        float rotation = state.getRotation();
        float radians = (float) Math.toRadians(rotation);

        RectF boundsRect = calculateBoundingBox(boundsW, boundsH, rotation);
        RectF contentRect = new RectF(0.0f, 0.0f, contentW, contentH);

        float initialX = (boundsW - contentW) / 2.0f;
        float initialY = (boundsH - contentH) / 2.0f;

        tempRect.setRect(contentRect);

        Matrix matrix = state.getMatrix();
        matrix.preTranslate(initialX / scale, initialY / scale);

        tempMatrix.reset();
        tempMatrix.setTranslate(contentRect.centerX(), contentRect.centerY());
        tempMatrix.setConcat(tempMatrix, matrix);
        tempMatrix.preTranslate(-contentRect.centerX(), -contentRect.centerY());
        tempRect.applyMatrix(tempMatrix);

        tempMatrix.reset();
        tempMatrix.preRotate(-rotation, contentW / 2.0f, contentH / 2.0f);
        tempRect.applyMatrix(tempMatrix);
        tempRect.getRect(contentRect);

        PointF targetTranslation = new PointF(state.getX(), state.getY());
        float targetScale = scale;

        if (!contentRect.contains(boundsRect)) {
            if (allowScale && (boundsRect.width() > contentRect.width() || boundsRect.height() > contentRect.height())) {
                float maxW = scaleWidthToMaxSize(boundsRect, contentRect);
                if (maxW > 0) {
                    float ratio = boundsRect.width() / maxW;
                    if (Float.isFinite(ratio) && ratio > 0) {
                        targetScale = fitScale(contentRect, scale, ratio);
                    }
                }
            }
            fitTranslation(contentRect, boundsRect, targetTranslation, radians);
        } else if (maximize && rotationStartScale > 0) {
            float maxW = scaleWidthToMaxSize(boundsRect, contentRect);
            if (maxW > 0) {
                float ratio = boundsRect.width() / maxW;
                if (Float.isFinite(ratio) && ratio > 0) {
                    float newScale = state.getScale() * ratio;
                    if (newScale < rotationStartScale) {
                        ratio = 1.0f;
                    }
                    targetScale = fitScale(contentRect, scale, ratio);
                }
            }

            fitTranslation(contentRect, boundsRect, targetTranslation, radians);
        }

        float dx = targetTranslation.x - state.getX();
        float dy = targetTranslation.y - state.getY();
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(targetScale) || targetScale <= 0) {
            return;
        }

        if (animated) {
            final float animScale = targetScale / scale;
            final float animDX = dx;
            final float animDY = dy;

            if (!Float.isFinite(animScale) || animScale <= 0 || (Math.abs(animScale - 1.0f) < EPSILON && Math.abs(animDX) < EPSILON && Math.abs(animDY) < EPSILON)) {
                return;
            }

            if (fitAnimator != null) {
                fitAnimator.cancel();
                fitAnimator = null;
            }

            animating = true;

            final float[] currentValues = new float[]{1.0f, 0.0f, 0.0f};
            final ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
            fitAnimator = animator;
            final boolean[] cancelled = new boolean[]{false};
            animator.addUpdateListener(animation -> {
                if (state == null || cancelled[0]) return;
                float value = (Float) animation.getAnimatedValue();

                float deltaX = animDX * value - currentValues[1];
                currentValues[1] += deltaX;
                float deltaY = animDY * value - currentValues[2];
                currentValues[2] += deltaY;
                state.translate(deltaX * currentValues[0], deltaY * currentValues[0]);

                float deltaScale = (1.0f + ((animScale - 1.0f) * value)) / currentValues[0];
                if (!Float.isFinite(deltaScale) || deltaScale <= 0) return;
                currentValues[0] *= deltaScale;
                state.scale(deltaScale, 0, 0);

                updateMatrix();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (fitAnimator == animator) {
                        fitAnimator = null;
                    }
                    animating = false;

                    if (!cancelled[0] && !fast && state != null) {
                        fitContentInBounds(allowScale, maximize, animated, true);
                    }
                }
            });
            animator.setInterpolator(areaView.getInterpolator());
            animator.setDuration(fast ? 100 : 200);
            animator.start();
        } else {
            state.translate(dx, dy);
            state.scale(targetScale / scale, 0, 0);
            updateMatrix();
        }
    }

    public int getCurrentWidth() {
        if (videoEditTextureView != null) {
            return videoEditTextureView.getVideoWidth();
        }
        if (bitmap == null) return 1;
        return bitmapRotation == 90 || bitmapRotation == 270 ? bitmap.getHeight() : bitmap.getWidth();
    }

    public int getCurrentHeight() {
        if (videoEditTextureView != null) {
            return videoEditTextureView.getVideoHeight();
        }
        if (bitmap == null) return 1;
        return bitmapRotation == 90 || bitmapRotation == 270 ? bitmap.getWidth() : bitmap.getHeight();
    }

    public boolean isMirrored() {
        if (state == null) {
            return false;
        }
        return state.mirrored;
    }

    public boolean mirror() {
        if (state == null) {
            return false;
        }
        state.mirror();
        updateMatrix();
        if (listener != null) {
            float orientation = (state.getOrientation() - state.getBaseRotation()) % 360;
            listener.onChange(!state.hasChanges() && orientation == 0 && areaView.getLockAspectRatio() == 0 && !state.mirrored);
        }
        return state.mirrored;
    }

    public void maximize(boolean animated) {
        if (state == null || state.minimumScale <= 0 || !Float.isFinite(state.minimumScale) || state.scale <= 0 || !Float.isFinite(state.scale) || !hasValidAreaSize()) {
            return;
        }
        final float toScale = state.minimumScale;
        cancelAnimators();
        float aspectRatio;
        if (state.getOrientation() % 180 != 0) {
            aspectRatio = getCurrentHeight() / (float) getCurrentWidth();
        } else {
            aspectRatio = getCurrentWidth() / (float) getCurrentHeight();
        }
        if (!freeform) {
            aspectRatio = 1.0f;
        }
        areaView.calculateRect(initialAreaRect, aspectRatio);
        areaView.setLockedAspectRatio(freeform ? 0.0f : 1.0f);
        resetRotationStartScale();

        if (animated) {
            final ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
            maximizeAnimator = animator;
            RectF fromActualRect = new RectF(), animatedRect = new RectF();
            areaView.getCropRect(fromActualRect);
            final float fromX = state.x;
            final float fromY = state.y;
            final float fromScale = state.scale;
            final float fromRot = state.rotation;
            animator.addUpdateListener(animation -> {
                if (state == null || state.scale <= 0) return;
                float t = (float) animation.getAnimatedValue();
                AndroidUtilities.lerp(fromActualRect, initialAreaRect, t, animatedRect);
                areaView.setActualRect(animatedRect);
                float dx = state.x - fromX * (1f - t),
                      dy = state.y - fromY * (1f - t),
                      dr = state.rotation - fromRot * (1f - t),
                      ds = AndroidUtilities.lerp(fromScale, toScale, t) / state.scale;
                if (!Float.isFinite(ds) || ds <= 0) return;
                state.translate(-dx, -dy);
                state.scale(ds, 0, 0);
                state.rotate(-dr, 0, 0);
                fitContentInBounds(true, false, false);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (maximizeAnimator == animator) {
                        maximizeAnimator = null;
                    }
                }
            });
            animator.setInterpolator(areaView.getInterpolator());
            animator.setDuration(250);
            animator.start();
        } else {
            areaView.setActualRect(initialAreaRect);
            state.translate(-state.x, -state.y);
            state.scale(state.minimumScale / state.scale, 0, 0);
            state.rotate(-state.rotation, 0, 0);
            updateMatrix();

            resetRotationStartScale();
        }
    }

    public boolean rotate(float angle) {
        if (state == null) {
            return false;
        }
        areaView.resetAnimator();

        resetRotationStartScale();

        float orientation = (state.getOrientation() - state.getBaseRotation() + angle) % 360;

        boolean fform = freeform;
        if (freeform && areaView.getLockAspectRatio() > 0) {
            areaView.setLockedAspectRatio(1.0f / areaView.getLockAspectRatio());
            areaView.setActualRect(areaView.getLockAspectRatio());
            fform = false;
        } else {
            areaView.setBitmap(getCurrentWidth(), getCurrentHeight(), (orientation + state.getBaseRotation()) % 180 != 0, freeform);
        }

        state.reset(orientation);
        updateMatrix();
        fitContentInBounds(true, false, false);

        if (listener != null) {
            listener.onChange(orientation == 0 && areaView.getLockAspectRatio() == 0 && !state.mirrored);
        }
        return state.getOrientationOnly() != 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (animating) {
            return true;
        }
        boolean result = false;
        if (areaView.onTouchEvent(event)) {
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                onScrollChangeBegan();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                onScrollChangeEnded();
                break;
            }
        }
        try {
            result = detector.onTouchEvent(event);
        } catch (Exception ignore) {

        }
        return result;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    public void onAreaChangeBegan() {
        areaView.getCropRect(previousAreaRect);
        resetRotationStartScale();

        if (listener != null) {
            listener.onChange(false);
        }
    }

    @Override
    public void onAreaChange() {
        areaView.setGridType(CropAreaView.GridType.MAJOR, false);

        float x = previousAreaRect.centerX() - areaView.getCropCenterX();
        float y = previousAreaRect.centerY() - areaView.getCropCenterY();
        if (state != null) {
            state.translate(x, y);
        }
        updateMatrix();

        areaView.getCropRect(previousAreaRect);

        fitContentInBounds(true, false, false);
    }

    @Override
    public void onAreaChangeEnded() {
        areaView.setGridType(CropAreaView.GridType.NONE, true);
        fillAreaView(areaView.getTargetRectToFill(), false);
    }

    public void onDrag(float dx, float dy) {
        if (animating || state == null) {
            return;
        }
        state.translate(dx, dy);
        updateMatrix();
    }

    public void onFling(float startX, float startY, float velocityX, float velocityY) {

    }

    @Override
    public void onTapUp() {
        if (listener != null) {
            listener.onTapUp();
        }
    }

    public void onScrollChangeBegan() {
        if (animating) {
            return;
        }

        areaView.setGridType(CropAreaView.GridType.MAJOR, true);
        resetRotationStartScale();

        if (listener != null) {
            listener.onChange(false);
        }
    }

    public void onScrollChangeEnded() {
        areaView.setGridType(CropAreaView.GridType.NONE, true);
        fitContentInBounds(true, false, true);
    }

    public void onScale(float scale, float x, float y) {
        if (animating || state == null || !Float.isFinite(scale) || scale <= 0) {
            return;
        }
        float cropW = areaView.getCropWidth();
        float cropH = areaView.getCropHeight();
        if (cropW <= 0 || cropH <= 0 || state.getScale() <= 0 || !Float.isFinite(state.getScale())) {
            return;
        }

        float newScale = state.getScale() * scale;
        if (newScale > MAX_SCALE) {
            scale = MAX_SCALE / state.getScale();
        }

        float statusBarHeight = (Build.VERSION.SDK_INT >= 21 && !inBubbleMode ? AndroidUtilities.statusBarHeight : 0);

        float pivotX = (x - imageView.getWidth() / 2) / cropW * state.getOrientedWidth();
        float pivotY = (y - (imageView.getHeight() - bottomPadding - statusBarHeight - topPadding) / 2) / cropH * state.getOrientedHeight();

        state.scale(scale, pivotX, pivotY);
        updateMatrix();
    }

    public void onRotationBegan() {
        areaView.setGridType(CropAreaView.GridType.MINOR, false);
        if (state != null && rotationStartScale < 0.00001f) {
            rotationStartScale = state.getScale();
        }
    }

    public void onRotationEnded() {
        areaView.setGridType(CropAreaView.GridType.NONE, true);
    }

    private void resetRotationStartScale() {
        rotationStartScale = 0.0f;
    }

    public void setRotation(float angle) {
        if (state == null || !Float.isFinite(angle)) {
            return;
        }
        float deltaAngle = angle - state.getRotation();
        state.rotate(deltaAngle, 0, 0);
        fitContentInBounds(true, true, false);
    }

    public static void editBitmap(Context context, String path, Bitmap b, Canvas canvas, Bitmap canvasBitmap, Bitmap.CompressFormat format, Matrix stateMatrix, int contentWidth, int contentHeight, float stateScale, float rotation, float orientationOnly, float scale, boolean mirror, ArrayList<VideoEditedInfo.MediaEntity> entities, boolean clear) {
        try {
            if (clear) {
                canvasBitmap.eraseColor(0);
            }
            if (b == null) {
                b = BitmapFactory.decodeFile(path);
            }
            float sc = Math.max(b.getWidth(), b.getHeight()) / (float) Math.max(contentWidth, contentHeight);
            Matrix matrix = new Matrix();
            matrix.postTranslate(-b.getWidth() / 2, -b.getHeight() / 2);
            if (mirror) {
                matrix.postScale(-1, 1);
            }
            matrix.postScale(1.0f / sc, 1.0f / sc);
            matrix.postRotate(orientationOnly);
            matrix.postConcat(stateMatrix);
            matrix.postScale(scale, scale);
            matrix.postTranslate(canvasBitmap.getWidth() / 2, canvasBitmap.getHeight() / 2);
            canvas.drawBitmap(b, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
            FileOutputStream stream = new FileOutputStream(new File(path));
            canvasBitmap.compress(format, 87, stream);
            stream.close();

            if (entities != null && !entities.isEmpty()) {
                float[] point = new float[4];
                for (int a = 0, N = entities.size(); a < N; a++) {
                    VideoEditedInfo.MediaEntity entity = entities.get(a);

                    point[0] = (entity.x + entity.width / 2) * b.getWidth();
                    point[1] = (entity.y + entity.height / 2) * b.getHeight();
                    point[2] = entity.textViewX * b.getWidth();
                    point[3] = entity.textViewY * b.getHeight();
                    matrix.mapPoints(point);

                    final int w = contentWidth, h = contentHeight;
                    int bw = b.getWidth(), bh = b.getHeight();
                    if (orientationOnly == 90 || orientationOnly == 270) {
                        bw = b.getHeight();
                        bh = b.getWidth();
                    }
                    if (entity.type == VideoEditedInfo.MediaEntity.TYPE_TEXT) {
                        entity.width = entity.width * w / canvasBitmap.getWidth() * scale * stateScale;
                        entity.height = entity.height * h / canvasBitmap.getHeight() * scale * stateScale;
                    } else {
                        entity.viewWidth = (int) (entity.viewWidth / (float) w * bw);
                        entity.viewHeight = (int) (entity.viewHeight / (float) h * bh);

                        entity.width = entity.width * w / bw * scale * stateScale;
                        entity.height = entity.height * h / bh * scale * stateScale;
                    }

                    entity.x = point[0] / canvasBitmap.getWidth() - entity.width / 2;
                    entity.y = point[1] / canvasBitmap.getHeight() - entity.height / 2;
                    entity.textViewX = point[2] / canvasBitmap.getWidth();
                    entity.textViewY = point[3] / canvasBitmap.getHeight();

                    entity.rotation -= (rotation + orientationOnly) * (Math.PI / 180);
                }
            }

            b.recycle();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    RectF cropRect = new RectF();
    RectF sizeRect = new RectF(0, 0, RESULT_SIDE, RESULT_SIDE);

    private void updateCropTransform() {
        if (cropTransform == null || state == null) {
            return;
        }
        if (state.width <= 0 || state.height <= 0 || state.minimumScale <= 0 || !Float.isFinite(state.minimumScale) || state.scale <= 0 || !Float.isFinite(state.scale)) {
            return;
        }
        areaView.getCropRect(cropRect);
        float cropW = areaView.getCropWidth();
        float cropH = areaView.getCropHeight();
        float aspect = areaView.getAspectRatio();
        if (cropW <= 0 || cropH <= 0 || aspect <= 0 || !Float.isFinite(cropW) || !Float.isFinite(cropH) || !Float.isFinite(aspect)) {
            return;
        }
        float w = scaleWidthToMaxSize(cropRect, sizeRect);
        int width = (int) Math.ceil(w);
        int height = (int) (Math.ceil(width / aspect));
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = width / cropW;

        state.matrix.getValues(values);
        float sc = state.minimumScale * scale;
        if (!Float.isFinite(sc) || sc <= 0) {
            return;
        }

        int transformRotation = state.getOrientationOnly();
        while (transformRotation < 0) {
            transformRotation += 360;
        }
        int sw;
        int sh;
        if (transformRotation == 90 || transformRotation == 270) {
            sw = (int) state.height;
            sh = (int) state.width;
        } else {
            sw = (int) state.width;
            sh = (int) state.height;
        }
        if (sw <= 0 || sh <= 0) {
            return;
        }
        double denomW = Math.ceil(sw * sc);
        double denomH = Math.ceil(sh * sc);
        if (denomW <= 0 || denomH <= 0) {
            return;
        }
        float cropPw = (float) (width / denomW);
        float cropPh = (float) (height / denomH);
        if (cropPw > 1 || cropPh > 1) {
            float max = Math.max(cropPw, cropPh);
            cropPw /= max;
            cropPh /= max;
        }

        float realMininumScale;
        RectF rect = areaView.getTargetRectToFill(sw / (float) sh);
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        if (freeform) {
            realMininumScale = rect.width() / sw;
        } else {
            float wScale = rect.width() / sw;
            float hScale = rect.height() / sh;
            realMininumScale = Math.max(wScale, hScale);
        }
        if (!Float.isFinite(realMininumScale) || realMininumScale <= 0) {
            return;
        }

        float cropScale = state.scale / realMininumScale;
        float trueCropScale = state.scale / state.minimumScale;
        float minScale = state.minimumScale / realMininumScale;
        float cropPx = values[2] / sw / state.scale;
        float cropPy = values[5] / sh / state.scale;
        float cropRotate = state.rotation;

        RectF targetRect = areaView.getTargetRectToFill();
        float tx = areaView.getCropCenterX() - targetRect.centerX();
        float ty = areaView.getCropCenterY() - targetRect.centerY();
        if (!Float.isFinite(cropPw) || cropPw <= 0 || !Float.isFinite(cropPh) || cropPh <= 0
                || !Float.isFinite(cropScale) || cropScale <= 0
                || !Float.isFinite(trueCropScale) || trueCropScale <= 0
                || !Float.isFinite(minScale) || minScale <= 0
                || !Float.isFinite(cropPx) || !Float.isFinite(cropPy)
                || !Float.isFinite(tx) || !Float.isFinite(ty)) {
            return;
        }
        cropTransform.setViewTransform(state.mirrored || state.hasChanges() || state.getBaseRotation() >= EPSILON, cropPx, cropPy, cropRotate, state.getOrientationOnly(), cropScale, trueCropScale, minScale, cropPw, cropPh, tx, ty, state.mirrored);
    }

    public static String getCopy(String path) {
        File f = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), SharedConfig.getLastLocalId() + "_temp.jpg");
        try {
            AndroidUtilities.copyFile(new File(path), f);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return f.getAbsolutePath();
    }

    public void makeCrop(MediaController.MediaEditState editState) {
        if (!isReady()) {
            return;
        }

        areaView.getCropRect(cropRect);

        float w = scaleWidthToMaxSize(cropRect, sizeRect);
        int width = (int) Math.ceil(w);
        int height = (int) (Math.ceil(width / areaView.getAspectRatio()));
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = width / areaView.getCropWidth();

        if (editState.paintPath != null) {
            Bitmap resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(resultBitmap);

            String path = getCopy(editState.paintPath);

            if (editState.croppedPaintPath != null) {
                new File(editState.croppedPaintPath).delete();
                editState.croppedPaintPath = null;
            }
            editState.croppedPaintPath = path;
            if (editState.mediaEntities != null && !editState.mediaEntities.isEmpty()) {
                editState.croppedMediaEntities = new ArrayList<>(editState.mediaEntities.size());
                for (int a = 0, N = editState.mediaEntities.size(); a < N; a++) {
                    editState.croppedMediaEntities.add(editState.mediaEntities.get(a).copy());
                }
            } else {
                editState.croppedMediaEntities = null;
            }

            editBitmap(getContext(), path, null, canvas, resultBitmap, Bitmap.CompressFormat.PNG, state.matrix, getCurrentWidth(), getCurrentHeight(), state.scale, state.rotation, state.getOrientationOnly(), scale, false, editState.croppedMediaEntities, false);
        }

        if (editState.cropState == null) {
            editState.cropState = new MediaController.CropState();
        }
        applyToCropState(editState.cropState);
        return;
    }

    public void applyToCropState(MediaController.CropState cropState) {
        if (state == null || cropState == null || state.width <= 0 || state.height <= 0
                || state.minimumScale <= 0 || !Float.isFinite(state.minimumScale)
                || state.scale <= 0 || !Float.isFinite(state.scale)) {
            return;
        }
        areaView.getCropRect(cropRect);
        float cropW = areaView.getCropWidth();
        float cropH = areaView.getCropHeight();
        float aspect = areaView.getAspectRatio();
        if (cropW <= 0 || cropH <= 0 || aspect <= 0 || !Float.isFinite(cropW) || !Float.isFinite(cropH) || !Float.isFinite(aspect)) {
            return;
        }

        float w = scaleWidthToMaxSize(cropRect, sizeRect);
        int width = (int) Math.ceil(w);
        int height = (int) (Math.ceil(width / aspect));
        if (width <= 0 || height <= 0) {
            return;
        }
        float scale = width / cropW;

        state.matrix.getValues(values);
        float sc = state.minimumScale * scale;
        if (!Float.isFinite(sc) || sc <= 0) {
            return;
        }

        int transformRotation = state.getOrientationOnly();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("set transformRotation = " + transformRotation);
        }
        while (transformRotation < 0) {
            transformRotation += 360;
        }
        int sw;
        int sh;
        if (transformRotation == 90 || transformRotation == 270) {
            sw = (int) state.height;
            sh = (int) state.width;
        } else {
            sw = (int) state.width;
            sh = (int) state.height;
        }
        if (sw <= 0 || sh <= 0) {
            return;
        }
        double denomW = Math.ceil(sw * sc);
        double denomH = Math.ceil(sh * sc);
        if (denomW <= 0 || denomH <= 0) {
            return;
        }
        float cropPw = (float) (width / denomW);
        float cropPh = (float) (height / denomH);
        if (cropPw > 1 || cropPh > 1) {
            float max = Math.max(cropPw, cropPh);
            cropPw /= max;
            cropPh /= max;
        }
        float cropScale = state.scale * Math.min(sw / cropW, sh / cropH);
        float cropPx = values[2] / sw / state.scale;
        float cropPy = values[5] / sh / state.scale;
        if (!Float.isFinite(cropPw) || cropPw <= 0 || !Float.isFinite(cropPh) || cropPh <= 0
                || !Float.isFinite(cropScale) || cropScale <= 0
                || !Float.isFinite(cropPx) || !Float.isFinite(cropPy)) {
            return;
        }

        cropState.transformRotation = transformRotation;
        cropState.cropPw = cropPw;
        cropState.cropPh = cropPh;
        cropState.cropScale = cropScale;
        cropState.cropPx = cropPx;
        cropState.cropPy = cropPy;
        cropState.cropRotate = state.rotation;
        cropState.stateScale = state.scale;
        cropState.mirrored = state.mirrored;

        cropState.scale = scale;
        cropState.matrix = state.matrix;
        cropState.width = width;
        cropState.height = height;
        cropState.freeform = freeform;
        cropState.lockedAspectRatio = areaView.getLockAspectRatio();

        cropState.initied = true;
    }

    private void setLockedAspectRatio(float aspectRatio) {
        areaView.setLockedAspectRatio(aspectRatio);
        RectF targetRect = new RectF();
        areaView.calculateRect(targetRect, aspectRatio);
        fillAreaView(targetRect, true);

        if (listener != null) {
            listener.onChange(false);
            listener.onAspectLock(true);
        }
    }

    public void showAspectRatioDialog() {
        if (state == null) {
            return;
        }
        /*if (areaView.getLockAspectRatio() > 0) {
            areaView.setLockedAspectRatio(0);

            if (listener != null) {
                listener.onAspectLock(false);
            }

            return;
        }*/

        if (hasAspectRatioDialog) {
            return;
        }

        hasAspectRatioDialog = true;

        String[] actions = new String[8];

        final Integer[][] ratios = new Integer[][]{
                new Integer[]{3, 2},
                new Integer[]{5, 3},
                new Integer[]{4, 3},
                new Integer[]{5, 4},
                new Integer[]{7, 5},
                new Integer[]{16, 9}
        };

        actions[0] = LocaleController.getString(R.string.CropOriginal);
        actions[1] = LocaleController.getString(R.string.CropSquare);

        int i = 2;
        for (Integer[] ratioPair : ratios) {
            if (areaView.getAspectRatio() > 1.0f) {
                actions[i] = String.format("%d:%d", ratioPair[0], ratioPair[1]);
            } else {
                actions[i] = String.format("%d:%d", ratioPair[1], ratioPair[0]);
            }
            i++;
        }

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setItems(actions, (dialog12, which) -> {
                    hasAspectRatioDialog = false;
                    switch (which) {
                        case 0: {
                            float w = state.getBaseRotation() % 180 != 0 ? state.getHeight() : state.getWidth();
                            float h = state.getBaseRotation() % 180 != 0 ? state.getWidth() : state.getHeight();
                            setLockedAspectRatio(w / h);
                        }
                        break;

                        case 1: {
                            setLockedAspectRatio(1.0f);
                        }
                        break;

                        default: {
                            Integer[] ratioPair = ratios[which - 2];

                            if (areaView.getAspectRatio() > 1.0f) {
                                setLockedAspectRatio(ratioPair[0] / (float) ratioPair[1]);
                            } else {
                                setLockedAspectRatio(ratioPair[1] / (float) ratioPair[0]);
                            }
                        }
                        break;
                    }
                })
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(dialog1 -> hasAspectRatioDialog = false);
        dialog.show();
    }

    public void updateLayout() {
        if (state == null || pendingPreDrawListener != null || animating || areaView.isAnimating() || areaView.isDragging()) {
            return;
        }
        if (!hasValidAreaSize()) {
            return;
        }
        if (state.minimumScale <= 0 || !Float.isFinite(state.minimumScale) || state.scale <= 0 || !Float.isFinite(state.scale)) {
            reset();
            return;
        }
        float w = areaView.getCropWidth();
        float h = areaView.getCropHeight();
        if (w <= 0 || h <= 0 || !Float.isFinite(w) || !Float.isFinite(h)) {
            return;
        }
        float aspectRatio = areaView.getAspectRatio();
        areaView.calculateRect(initialAreaRect, state.getWidth() / state.getHeight());
        areaView.setActualRect(aspectRatio);
        areaView.getCropRect(previousAreaRect);

        float newW = areaView.getCropWidth();
        if (newW <= 0 || !Float.isFinite(newW)) {
            return;
        }
        float ratio = newW / w;
        if (!Float.isFinite(ratio) || ratio <= 0) {
            return;
        }
        if (Math.abs(ratio - 1.0f) > EPSILON) {
            state.minimumScale *= ratio;
            state.scale(ratio, 0, 0);
        }
        updateMatrix();
    }

    public float getCropLeft() {
        return areaView.getCropLeft();
    }

    public float getCropTop() {
        return areaView.getCropTop();
    }

    public float getCropWidth() {
        return areaView.getCropWidth();
    }

    public float getCropHeight() {
        return areaView.getCropHeight();
    }

    public RectF getActualRect() {
        areaView.getCropRect(cropRect);
        return cropRect;
    }
}
