package io.github.lumixlut.edit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * LUMIX Lab 那种「从正中往两边填」的滑杆。
 *
 * 和系统 SeekBar 的区别就三点，但少了任何一点都不是那个味道：
 *   1. 填充段永远从**正中**出发，往手指那一侧长 —— 不是从头开始填
 *      （所以归零时看着是空的，而不是半满）；
 *   2. 轨道很细（3dp），拇指是个纯色圆点，没有系统那种阴影和 ripple；
 *   3. 拇指走到哪，轨道就停在哪，不会画到轨道两端之外。
 *
 * 官方那个 CustomCenterSeekBar 我按反编译看过计算方式：
 * 轨道内缩一个拇指半径，填充矩形 = [min(thumbX, centerX), max(thumbX, centerX)]，
 * 高度就是轨道粗细。这里按同样几何画。
 */
public class CenterSeekBar extends View {

    public interface OnChangeListener {
        /** 按下，整段拖动只算一步撤销，所以起点要单独报一次 */
        void onStart();

        void onChanged(int progress, boolean fromUser);

        void onStop();
    }

    private static final int CENTER = 100;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF trackRect = new RectF();

    private float trackThickness;
    private float thumbRadius;
    private int max = CENTER * 2;
    private int progress = CENTER;

    private float thumbX;
    private boolean dragging;
    private boolean silent;

    private OnChangeListener listener;

    public CenterSeekBar(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        trackThickness = 3f * density;
        thumbRadius = 7f * density;
        trackPaint.setColor(0xFF4D4D4D);
        fillPaint.setColor(0xFFFFFFFF);
        thumbPaint.setColor(0xFFFFFFFF);
        setClickable(true);
    }

    public void setTrackColor(int color) {
        trackPaint.setColor(color);
        invalidate();
    }

    public void setFillColor(int color) {
        fillPaint.setColor(color);
        thumbPaint.setColor(color);
        invalidate();
    }

    public void setThumbRadiusDp(float dp) {
        thumbRadius = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    public void setTrackThicknessDp(float dp) {
        trackThickness = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = Math.max(1, max);
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    public void setOnChangeListener(OnChangeListener listener) {
        this.listener = listener;
    }

    /** 外部回填状态时用：不触发回调，避免「恢复快照」被当成用户操作又压一次撤销 */
    public void setProgressSilently(int value) {
        int clamped = Math.max(0, Math.min(max, value));
        if (clamped == progress) {
            return;
        }
        progress = clamped;
        updateThumbX();
        invalidate();
    }

    public void setProgress(int value) {
        int clamped = Math.max(0, Math.min(max, value));
        if (clamped == progress) {
            return;
        }
        progress = clamped;
        updateThumbX();
        invalidate();
        if (!silent && listener != null) {
            listener.onChanged(progress, true);
        }
    }

    private void updateThumbX() {
        float left = getPaddingLeft() + thumbRadius;
        float right = getWidth() - getPaddingRight() - thumbRadius;
        if (right <= left) {
            thumbX = left;
            return;
        }
        thumbX = left + (right - left) * (progress / (float) max);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cy = h / 2f;
        float innerLeft = getPaddingLeft() + thumbRadius;
        float innerRight = w - getPaddingRight() - thumbRadius;
        trackRect.set(innerLeft, cy - trackThickness / 2f, innerRight, cy + trackThickness / 2f);
        updateThumbX();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int h = getHeight();
        float cy = h / 2f;

        canvas.drawRoundRect(trackRect, trackThickness / 2f, trackThickness / 2f, trackPaint);

        float centerX = trackRect.centerX();
        float left = Math.min(centerX, thumbX);
        float right = Math.max(centerX, thumbX);
        if (right - left > 0.5f) {
            canvas.drawRect(left, cy - trackThickness / 2f, right, cy + trackThickness / 2f, fillPaint);
        }

        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        float left = getPaddingLeft() + thumbRadius;
        float right = getWidth() - getPaddingRight() - thumbRadius;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (listener != null) {
                    listener.onStart();
                }
                applyTouch(event.getX(), left, right);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    applyTouch(event.getX(), left, right);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (listener != null) {
                        listener.onStop();
                    }
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void applyTouch(float x, float left, float right) {
        if (right <= left) {
            return;
        }
        float ratio = (x - left) / (right - left);
        ratio = Math.max(0f, Math.min(1f, ratio));
        int value = Math.round(ratio * max);
        if (value == progress) {
            return;
        }
        progress = value;
        updateThumbX();
        invalidate();
        if (listener != null) {
            listener.onChanged(progress, true);
        }
    }
}
