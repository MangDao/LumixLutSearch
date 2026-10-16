package io.github.lumixlut.search;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设计令牌 + 一组复用的构造器。
 *
 * 全 app 的颜色、圆角、间距都从这里走，避免每个界面各写一套数值。
 * 配色参考 LUMIX Lab：中性灰底 + 白卡片 + 青绿强调。
 */
public final class Ui {

    // ------------------------------------------------------------ 颜色
    public static final int BG = 0xFFF2F3F5;      // 页面底色
    public static final int CARD = 0xFFFFFFFF;    // 卡片
    public static final int TEXT = 0xFF111111;    // 主文字
    public static final int TEXT_SUB = 0xFF8A8F99; // 次文字
    public static final int TEXT_WEAK = 0xFFB0B5BD;
    public static final int ACCENT = 0xFF00A6A6;  // 强调（青绿）
    public static final int ACCENT_SOFT = 0x1A00A6A6;
    public static final int LINE = 0xFFE8EAED;    // 分割线
    public static final int CHIP_BG = 0xFFF0F1F3; // 未选中的 chip 底
    public static final int RANK_TOP = 0xFFFF6B35; // 榜单前三
    public static final int RANK_N = 0xFF9AA0A6;

    // ------------------------------------------------------------ 尺寸（dp）
    public static final float R_CARD = 12f;   // 卡片圆角
    public static final float R_CHIP = 16f;   // chip 圆角
    public static final float R_BTN = 8f;     // 按钮圆角

    private Ui() {}

    // ------------------------------------------------------------ 基础

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int px(Context c) {
        return dp(c, 16);
    }

    /** 圆角实心背景。 */
    public static GradientDrawable round(int fill, float radiusDp, Context c, Integer stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != null) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    /** 白卡片容器（圆角 + 轻描边，不用阴影控件）。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, R_CARD, c, LINE));
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = weight;
        return p;
    }

    public static LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 给布局参数加外边距并返回。 */
    public static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p,
                                                    Context c, float l, float t,
                                                    float r, float b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    // ------------------------------------------------------------ 文字

    public static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    public static TextView h1(Context c, String s) { return text(c, s, 18f, TEXT, true); }
    public static TextView h2(Context c, String s) { return text(c, s, 15f, TEXT, true); }
    public static TextView body(Context c, String s) { return text(c, s, 13f, TEXT, false); }
    public static TextView sub(Context c, String s) { return text(c, s, 11.5f, TEXT_SUB, false); }

    /** 段落小标题：左侧一根青绿竖条 + 标题。 */
    public static LinearLayout sectionTitle(Context c, String s) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 18), 0, dp(c, 10));

        View bar = new View(c);
        bar.setBackground(round(ACCENT, 1.5f, c, null));
        r.addView(bar, new LinearLayout.LayoutParams(dp(c, 3), dp(c, 15)));

        TextView t = h2(c, s);
        LinearLayout.LayoutParams tp = lpWrap();
        tp.leftMargin = dp(c, 8);
        r.addView(t, tp);
        return r;
    }

    // ------------------------------------------------------------ 控件

    /** 药丸标签。selected 时青绿底白字，否则浅灰底深字。 */
    public static TextView chip(Context c, String s, boolean selected) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        applyChip(t, c, selected);
        return t;
    }

    public static void applyChip(TextView t, Context c, boolean selected) {
        if (selected) {
            t.setBackground(round(ACCENT, R_CHIP, c, null));
            t.setTextColor(Color.WHITE);
            t.setTypeface(null, Typeface.BOLD);
        } else {
            t.setBackground(round(CHIP_BG, R_CHIP, c, null));
            t.setTextColor(TEXT);
            t.setTypeface(null, Typeface.NORMAL);
        }
    }

    /** 主按钮（青绿实心）。 */
    public static TextView primaryBtn(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(14f);
        t.setTextColor(Color.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        t.setBackground(round(ACCENT, R_BTN, c, null));
        return t;
    }

    /** 次要按钮（描边）。 */
    public static TextView ghostBtn(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(14f);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        t.setBackground(round(CARD, R_BTN, c, LINE));
        return t;
    }

    /**
     * 一条 1px 分割线。
     *
     * 【为什么不用裸 View】
     * View.getDefaultSize() 在 AT_MOST / EXACTLY 下直接返回 specSize，
     * 也就是说裸 View 用 WRAP_CONTENT 高度时【会撑满全部可用空间】。
     * 一旦调用方写 addView(divider, lpMatch())，divider 就会变成几百像素高，
     * 把带 weight 的兄弟节点挤成 0 —— 页面看起来整块空白。
     *
     * 这里用子类固定自己的测量高度，调用方传什么 LayoutParams 都改不坏。
     */
    public static View divider(Context c) {
        return new Line(c, c);
    }

    private static class Line extends View {
        private final int thickness;
        Line(Context c, Context ctx) {
            super(c);
            setBackgroundColor(LINE);
            thickness = Math.max(1, dp(ctx, 0.6f));
        }
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // 宽度听调用方的，高度坚持自己那一条
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), thickness);
        }
    }

    /** 竖分割线。 */
    public static View vDivider(Context c, int heightDp) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 0.6f), dp(c, heightDp)));
        return v;
    }

    /** 名次数字，前三名高亮。 */
    public static TextView rank(Context c, int n) {
        TextView t = new TextView(c);
        t.setText(String.valueOf(n));
        t.setTextSize(15f);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(n <= 3 ? RANK_TOP : RANK_N);
        return t;
    }

    /** 让 View 可点（带轻微按下反馈）。 */
    public static void clickable(View v, float radiusDp, Context c, Runnable action) {
        v.setClickable(true);
        v.setOnClickListener(x -> action.run());
    }

    /** 数字加千分位。 */
    public static String num(int n) {
        return String.format(java.util.Locale.getDefault(), "%,d", n);
    }

    /** 下载量的紧凑写法：12.3万 / 1.2万 / 9,876。 */
    public static String compact(int n) {
        if (n >= 100000) return String.format(java.util.Locale.getDefault(), "%.1f万", n / 10000.0);
        if (n >= 10000) return String.format(java.util.Locale.getDefault(), "%.1f万", n / 10000.0);
        return num(n);
    }
}
