package io.github.lumixlut.edit.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import io.github.lumixlut.edit.curve.ToneCurve;

/**
 * 手势画曲线的方框。
 *
 * 交互：按在控制点附近就拖它，按在空白处就新增一个点并跟着走。
 * 端点只能上下拉，中间点的 x 被前后邻居夹住，保证曲线永远是单值函数。
 */
public class ToneCurveView extends View {

    public interface Listener {
        void onCurveChanged(ToneCurve curve);

        /** 手指按下，一次拖动算一步撤销 */
        void onEditStart();
    }

    private static final int GRID = 4;
    private static final float HIT_RADIUS_DP = 26f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diagonalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private ToneCurve curve;
    private Listener listener;
    private int draggingIndex = -1;
    private int activeColor = Color.WHITE;

    private final float[] baked = new float[ToneCurve.SAMPLES];

    public ToneCurveView(Context context) {
        super(context);
        init();
    }

    public ToneCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // 配色跟 app 其他页面同源，不再写死深色
        setBackgroundColor(getContext().getColor(io.github.lumixlut.search.R.color.card));
        gridPaint.setColor(getContext().getColor(io.github.lumixlut.search.R.color.line));
        gridPaint.setStrokeWidth(dp(1));
        diagonalPaint.setColor(getContext().getColor(io.github.lumixlut.search.R.color.text_weak));
        diagonalPaint.setStrokeWidth(dp(1));
        curvePaint.setStrokeWidth(dp(2f));
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setColor(getContext().getColor(io.github.lumixlut.search.R.color.text));
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setColor(getContext().getColor(io.github.lumixlut.search.R.color.text));
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setColor(getContext().getColor(io.github.lumixlut.search.R.color.line));
        framePaint.setStrokeWidth(dp(1));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setCurve(ToneCurve curve, int color) {
        this.curve = curve;
        this.activeColor = color;
        curvePaint.setColor(color);
        pointPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || curve == null) {
            return;
        }

        for (int i = 1; i < GRID; i++) {
            float x = w * i / (float) GRID;
            float y = h * i / (float) GRID;
            canvas.drawLine(x, 0, x, h, gridPaint);
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        canvas.drawLine(0, h, w, 0, diagonalPaint);
        canvas.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, framePaint);

        curve.bake(baked);
        path.reset();
        for (int i = 0; i < ToneCurve.SAMPLES; i++) {
            float x = w * i / (float) (ToneCurve.SAMPLES - 1);
            float y = h * (1f - baked[i]);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, curvePaint);

        for (int i = 0; i < curve.pointCount(); i++) {
            float x = w * curve.pointX(i);
            float y = h * (1f - curve.pointY(i));
            canvas.drawCircle(x, y, dp(i == draggingIndex ? 6f : 4.5f), pointPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (curve == null) {
            return false;
        }
        float nx = clamp01(event.getX() / getWidth());
        float ny = 1f - clamp01(event.getY() / getHeight());

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (listener != null) {
                    listener.onEditStart();
                }
                draggingIndex = findNearest(nx, ny);
                if (draggingIndex < 0) {
                    draggingIndex = curve.addOrMove(nx, ny);
                } else {
                    curve.move(draggingIndex, nx, ny);
                }
                notifyChanged();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (draggingIndex >= 0) {
                    curve.move(draggingIndex, nx, ny);
                    notifyChanged();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                draggingIndex = -1;
                invalidate();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    private int findNearest(float nx, float ny) {
        float best = dp(HIT_RADIUS_DP);
        int found = -1;
        for (int i = 0; i < curve.pointCount(); i++) {
            float dx = (curve.pointX(i) - nx) * getWidth();
            float dy = (curve.pointY(i) - ny) * getHeight();
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < best) {
                best = dist;
                found = i;
            }
        }
        return found;
    }

    private void notifyChanged() {
        invalidate();
        if (listener != null) {
            listener.onCurveChanged(curve);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
