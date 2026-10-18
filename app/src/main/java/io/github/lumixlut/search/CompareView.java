package io.github.lumixlut.search;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 原图 / 效果图 滑动对比。
 *
 * 服务端为每枚 LUT 同时提供两张官方样张：
 *   thumbnail.path_original  → 原片
 *   thumbnail.path           → 套用该 LUT 之后
 * 所以这里不需要做任何 LUT 渲染，只负责把两张图叠起来、按拖动位置裁剪。
 *
 * 画法：
 *   1. 先整幅画 after（效果图）
 *   2. 再在左半边画 before（原图），用 clipRect 裁到分隔线
 *   3. 最后画分隔线、把手、两侧文字
 */
public class CompareView extends View {

    /** 服务端样张固定 1080x720。 */
    private static final float ASPECT = 720f / 1080f;

    private Bitmap before;   // 原图
    private Bitmap after;    // 效果图

    /** 分隔线位置，0..1。 */
    private float split = 0.5f;
    /** 按下时是否抓在把手上（决定是拖动还是跳转）。 */
    private boolean dragging = false;
    /** 原图还在加载：只有效果图，把手以"待命"样式出现。 */
    private boolean pendingBefore = false;
    /** 官方压根没给原图：不画把手（画了也没得对比）。 */
    private boolean noBefore = false;

    private final Paint pImg = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pKnobRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTextBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 把手里的方向箭头（纯路径，不依赖字体）。 */
    private final Paint pArrow = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 图片按比例居中后的实际绘制区域。 */
    private final RectF imgRect = new RectF();
    private final Rect clipRect = new Rect();
    private final Path clipPath = new Path();
    /** 把手里箭头的路径，反复重置复用（onDraw 里不 new 对象）。 */
    private final Path arrowPath = new Path();

    private final float dp;

    public CompareView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;

        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(dp * 2f);
        pLine.setColor(Color.WHITE);

        pGlow.setStyle(Paint.Style.STROKE);
        pGlow.setStrokeWidth(dp * 5f);
        pGlow.setColor(0x66000000);

        pKnob.setStyle(Paint.Style.FILL);
        pKnob.setColor(Color.WHITE);

        pKnobRing.setStyle(Paint.Style.STROKE);
        pKnobRing.setStrokeWidth(dp * 1.5f);
        pKnobRing.setColor(0x33000000);

        pText.setTextSize(dp * 12f);
        pText.setColor(Color.WHITE);
        pText.setFakeBoldText(true);
        pText.setShadowLayer(dp * 2f, 0f, dp, 0xCC000000);

        pTextBg.setColor(0x99000000);

        pArrow.setStyle(Paint.Style.FILL);
        pArrow.setColor(0xFF333333);

        setFocusable(true);
    }

    /** 传入样张。允许只传一张（另一张为 null 时退化为单图显示）。 */
    public void setBitmaps(Bitmap beforeBmp, Bitmap afterBmp) {
        this.before = beforeBmp;
        this.after = afterBmp;
        invalidate();
    }

    /** 原图正在路上：把手先出现，但画成待命态（半透明 + 「原图加载中」）。 */
    public void setPendingBefore(boolean pending) {
        this.pendingBefore = pending;
        if (pending) this.noBefore = false;
        invalidate();
    }

    /** 官方没有原图：收起把手，避免"拖了没反应"。 */
    public void setNoBefore(boolean none) {
        this.noBefore = none;
        if (none) this.pendingBefore = false;
        invalidate();
    }

    public void setSplit(float f) {
        split = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(w * ASPECT);
        setMeasuredDimension(w, h);
    }

    /** 计算图片在控件内的等比居中矩形。 */
    private void layoutImage() {
        Bitmap ref = (after != null) ? after : before;
        if (ref == null) {
            imgRect.set(0, 0, getWidth(), getHeight());
            return;
        }
        float vw = getWidth(), vh = getHeight();
        float iw = ref.getWidth(), ih = ref.getHeight();
        if (iw <= 0 || ih <= 0) {
            imgRect.set(0, 0, vw, vh);
            return;
        }
        float scale = Math.min(vw / iw, vh / ih);
        float dw = iw * scale, dh = ih * scale;
        float left = (vw - dw) / 2f, top = (vh - dh) / 2f;
        imgRect.set(left, top, left + dw, top + dh);
    }

    @Override
    protected void onDraw(Canvas cv) {
        layoutImage();

        // 0) 两张都还没到：画骨架屏（灰块 + 扫光），比一片白更像"正在来"
        if (before == null && after == null) {
            Shimmer.draw(cv, imgRect, 8f, phase());
            postInvalidateOnAnimation();
            return;
        }

        // 1) 整幅先画效果图
        if (after != null) {
            cv.drawBitmap(after, null, imgRect, pImg);
        } else if (before != null) {
            cv.drawBitmap(before, null, imgRect, pImg);
        }

        // 2) 左半边盖原图（原图没到就只画效果图，但分隔线/把手照样出现 —— 不让人干等）
        if (before != null && after != null) {
            float x = imgRect.left + imgRect.width() * split;
            clipRect.set(Math.round(imgRect.left), Math.round(imgRect.top),
                    Math.round(x), Math.round(imgRect.bottom));
            clipPath.reset();
            clipPath.addRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom,
                    Path.Direction.CW);
            cv.save();
            cv.clipPath(clipPath);
            cv.drawBitmap(before, null, imgRect, pImg);
            cv.restore();
        }
        // 3) 分隔线 + 把手：图一出现就画（不等第二张）；原图没到时画成半透明待命态
        final boolean canCompare = (before != null && after != null);
        final boolean showHandle = canCompare || (after != null && pendingBefore && !noBefore);
        if (showHandle) {
            final int aGlow = pGlow.getAlpha(), aLine = pLine.getAlpha(),
                    aKnob = pKnob.getAlpha(), aRing = pKnobRing.getAlpha();
            if (!canCompare) {
                pGlow.setAlpha(90); pLine.setAlpha(120);
                pKnob.setAlpha(150); pKnobRing.setAlpha(150);
            }
            float x = imgRect.left + imgRect.width() * split;

            // 3) 分隔线 + 把手
            float cx = x;
            float cy = imgRect.centerY();
            float top = imgRect.top, bot = imgRect.bottom;

            cv.drawLine(cx, top, cx, bot, pGlow);
            cv.drawLine(cx, top, cx, bot, pLine);

            float r = dp * 11f;
            cv.drawCircle(cx, cy, r, pKnob);
            cv.drawCircle(cx, cy, r, pKnobRing);

            // 把手里的 ‹ ›：用 Path 画三角，不再 drawText("◀"/"▶")
            // —— 那两个字符随字体变宽变高，同一台机器换字体后把手里的箭头会一胖一瘦。
            drawChevron(cv, cx - r * 0.40f, cy, r * 0.17f, r * 0.23f, -1);
            drawChevron(cv, cx + r * 0.40f, cy, r * 0.17f, r * 0.23f, 1);

            if (!canCompare) {
                // 待命态提示：说明为什么拖了看不出变化
                String t = getContext().getString(R.string.detail_before_pending);
                Paint.Align oa = pText.getTextAlign();
                float ats = pText.getTextSize();
                int ac = pText.getColor();
                pText.setTextAlign(Paint.Align.CENTER);
                pText.setTextSize(dp * 10f);
                pText.setColor(0xCCFFFFFF);
                cv.drawText(t, cx, imgRect.bottom - dp * 10f, pText);
                pText.setTextSize(ats);
                pText.setTextAlign(oa);
                pText.setColor(ac);
            }

            pGlow.setAlpha(aGlow); pLine.setAlpha(aLine);
            pKnob.setAlpha(aKnob); pKnobRing.setAlpha(aRing);
        }

        // 4) 两侧标签
        drawTag(cv, getContext().getString(R.string.detail_badge_before),
                imgRect.left + dp * 8f, imgRect.top + dp * 8f, true);
        drawTag(cv, getContext().getString(R.string.detail_badge_after),
                imgRect.right - dp * 8f, imgRect.top + dp * 8f, false);
    }

    /** 骨架屏扫光进度 0..1。 */
    private float phase() {
        return (android.os.SystemClock.uptimeMillis() % 1100L) / 1100f;
    }

    /**
     * 把手里的小箭头：dir = -1 朝左，+1 朝右。
     *
     * 不用 drawText("◀"/"▶")：那两个码位在部分字体里是"半宽/全宽"，换字体会一胖一瘦，
     * 甚至被替换成豆腐块。这里用三个点画实心三角，尺寸只跟 dp 有关。
     */
    private void drawChevron(Canvas cv, float x, float y,
                             float halfW, float halfH, int dir) {
        arrowPath.reset();
        arrowPath.moveTo(x - dir * halfW, y - halfH);
        arrowPath.lineTo(x + dir * halfW, y);
        arrowPath.lineTo(x - dir * halfW, y + halfH);
        arrowPath.close();
        cv.drawPath(arrowPath, pArrow);
    }

    /** 画一个带底衬的小标签。leftAnchor=true 时 x 是左边缘，否则是右边缘。 */
    private void drawTag(Canvas cv, String s, float x, float y, boolean leftAnchor) {
        float pad = dp * 5f;
        float tw = pText.measureText(s);
        float th = pText.descent() - pText.ascent();
        float l = leftAnchor ? x : x - tw - pad * 2f;
        float t = y;
        RectF bg = new RectF(l, t, l + tw + pad * 2f, t + th + pad);

        Paint.Align old = pText.getTextAlign();
        pText.setTextAlign(Paint.Align.LEFT);
        cv.drawRoundRect(bg, dp * 4f, dp * 4f, pTextBg);

        // 文字加阴影后带底衬会糊，这里直接画纯色
        int oldShadowColor = pText.getShadowLayerColor();
        float oldRadius = pText.getShadowLayerRadius();
        pText.clearShadowLayer();
        cv.drawText(s, bg.left + pad, bg.top + pad - pText.ascent(), pText);
        pText.setShadowLayer(oldRadius, 0f, dp, oldShadowColor);
        pText.setTextAlign(old);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (before == null && after == null) return false;
        float x = e.getX();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                // 抓把手附近才拖动，其余位置直接跳过去（更好用）
                float hx = imgRect.left + imgRect.width() * split;
                dragging = Math.abs(x - hx) < dp * 40f;
                if (dragging) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // 抓住把手的那一下：CONTEXT_CLICK 比 tick 略沉，像"捏住"
                    performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                }
                setSplit((x - imgRect.left) / Math.max(1f, imgRect.width()));
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    setSplit((x - imgRect.left) / Math.max(1f, imgRect.width()));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                performClick();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
