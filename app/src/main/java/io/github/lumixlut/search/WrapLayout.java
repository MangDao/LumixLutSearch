package io.github.lumixlut.search;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 会自动换行的容器。
 *
 * 项目不引 androidx，用不了 FlexboxLayout / FlowLayout。
 * Android 自带的 LinearLayout 只有「横」或「竖」两个方向：
 * 横向放不下会直接溢出屏幕 —— 筛选项一多，后面的就永远点不到了。
 *
 * 这个类把子 View 一行行排，放不下就换行，并支持水平/垂直间距与整体居中。
 */
public class WrapLayout extends ViewGroup {

    private int gapX, gapY;
    private boolean center = false;

    public WrapLayout(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        gapX = Math.round(8 * d);
        gapY = Math.round(8 * d);
    }

    public WrapLayout gaps(int xDp, int yDp, Context c) {
        gapX = Ui.dp(c, xDp);
        gapY = Ui.dp(c, yDp);
        return this;
    }

    public WrapLayout centered() {
        center = true;
        return this;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int usable = Math.max(0, width - getPaddingLeft() - getPaddingRight());

        int x = 0, y = 0, rowH = 0, maxX = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;

            // 先按「不限宽」量一次，拿到它自己想占的宽度
            measureChild(ch, MeasureSpec.makeMeasureSpec(usable, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = ch.getMeasuredWidth();
            int chh = ch.getMeasuredHeight();

            // 本行放不下 → 换行（除非本行还是空的，那就硬放）
            if (x > 0 && x + gapX + cw > usable) {
                maxX = Math.max(maxX, x);
                y += rowH + gapY;
                x = 0;
                rowH = 0;
            }
            if (x > 0) x += gapX;
            x += cw;
            rowH = Math.max(rowH, chh);
        }
        maxX = Math.max(maxX, x);

        int totalH = y + rowH + getPaddingTop() + getPaddingBottom();
        int totalW = maxX + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(resolveSize(Math.max(totalW, getSuggestedMinimumWidth()), widthSpec),
                resolveSize(Math.max(totalH, getSuggestedMinimumHeight()), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int usable = Math.max(0, width - getPaddingLeft() - getPaddingRight());

        // 先算行，再布置 —— 居中要按行宽算，所以得走两遍
        int n = getChildCount();
        int[] rowOf = new int[n];
        int[] wOf = new int[n];
        java.util.List<int[]> rows = new java.util.ArrayList<>();   // {start, end, rowWidth, rowHeight}

        int x = 0, rowH = 0, start = 0;
        for (int i = 0; i < n; i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) { rowOf[i] = -1; continue; }
            int cw = ch.getMeasuredWidth();
            int chh = ch.getMeasuredHeight();
            if (x > 0 && x + gapX + cw > usable) {
                rows.add(new int[]{start, i, x, rowH});
                start = i; x = 0; rowH = 0;
            }
            if (x > 0) x += gapX;
            rowOf[i] = rows.size();
            wOf[i] = cw;
            x += cw;
            rowH = Math.max(rowH, chh);
        }
        rows.add(new int[]{start, n, x, rowH});

        int cy = getPaddingTop();
        int idx = 0;
        for (int[] row : rows) {
            int rowW = row[2], rh = row[3];
            boolean empty = true;
            for (int i = row[0]; i < row[1]; i++) {
                if (rowOf[i] >= 0) { empty = false; break; }
            }
            if (empty) { idx++; continue; }

            int cx = getPaddingLeft();
            if (center) cx += Math.max(0, (usable - rowW) / 2);

            for (int i = row[0]; i < row[1]; i++) {
                if (rowOf[i] < 0) continue;
                View ch = getChildAt(i);
                ch.layout(cx, cy, cx + wOf[i], cy + ch.getMeasuredHeight());
                cx += wOf[i] + gapX;
            }
            cy += rh + gapY;
            idx++;
        }
    }
}
