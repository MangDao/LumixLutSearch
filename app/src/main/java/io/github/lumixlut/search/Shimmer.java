package io.github.lumixlut.search;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * 骨架屏占位块：灰底 + 一条高光从左扫到右。
 *
 * 为什么不用第三方库：全工程零依赖（连 androidx 都没有），一个 60 行的自绘 View
 * 比引一个 shimmer 库干净得多。
 *
 * 两处用法：
 *  1. {@link #Shimmer} 作为独立 View 垫在缩略图下面（图片加载完成后隐藏）；
 *  2. {@link #draw(Canvas, RectF, float, float)} 静态方法，给 CompareView 这种
 *     自绘控件直接画一块占位（详情里两张样张都没到的时候）。
 */
public class Shimmer extends View {

    /** 一格扫光走完的时间。太快显廉价，太慢像卡住，1100ms 接近 Material 的手感。 */
    private static final long PERIOD_MS = 1100;

    private static final Paint BASE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint HIGH = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix MTX = new Matrix();
    /** 上次构建 shader 用的扫光色（切深浅色后要重建）。 */
    private static int shineCached = 0;

    private final RectF rect = new RectF();
    private float radiusDp = 12f;
    private long startAt = 0;

    public Shimmer(Context c) {
        super(c);
        // 比卡片底差一档，才看得出高光在扫（深浅两套由 token 给，见 colors.xml）
        Ui.init(c);
        BASE.setColor(Ui.SKELETON);
    }

    public Shimmer radius(float dp) {
        this.radiusDp = dp;
        return this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float phase = phase();
        rect.set(0, 0, getWidth(), getHeight());
        draw(cv, rect, radiusDp, phase);
        postInvalidateOnAnimation();   // 只有可见时才跑，View.GONE 时不会回调
    }

    private float phase() {
        if (startAt == 0) startAt = SystemClock.uptimeMillis();
        long t = (SystemClock.uptimeMillis() - startAt) % PERIOD_MS;
        return t / (float) PERIOD_MS;
    }

    /**
     * 画一块骨架占位。
     *
     * @param phase 0..1 的扫光进度
     */
    public static void draw(Canvas cv, RectF r, float radiusDp, float phase) {
        float dp = cv.getDensity();
        float rad = radiusDp * dp;

        cv.drawRoundRect(r, rad, rad, BASE);

        float w = Math.max(1f, r.width());
        float band = Math.max(w * 0.34f, 110f * dp);
        // 起点从 -band 扫到 w + band，保证两端完全离开
        float cx = -band + phase * (w + band * 2f);

        // 扫光颜色来自 token：浅色是一道白（CC），暗色是很浅的一层白（1A），
        // 否则暗色主题下会亮得刺眼。颜色变了就重建 shader。
        int shine = Ui.SKELETON_SHINE;
        if (HIGH.getShader() == null || shine != shineCached) {
            shineCached = shine;
            HIGH.setShader(new LinearGradient(-band, 0, band, 0,
                    new int[]{shine & 0x00FFFFFF, shine, shine & 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        MTX.reset();
        MTX.setTranslate(cx, 0);
        HIGH.getShader().setLocalMatrix(MTX);

        cv.save();
        cv.clipRect(r);
        cv.drawRoundRect(r, rad, rad, HIGH);
        cv.restore();
    }
}
