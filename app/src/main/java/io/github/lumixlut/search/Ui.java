package io.github.lumixlut.search;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    //
    // 唯一真源是 res/values/colors.xml，由 init(Context) 在启动时读进来。
    // 下面的十六进制只是 init 之前的兜底值（例如单元测试或忘了调 init），
    // 改配色请改 colors.xml，不要改这里。
    public static int BG = 0xFFF2F3F5;      // 页面底色
    public static int CARD = 0xFFFFFFFF;    // 卡片
    public static int TEXT = 0xFF111111;    // 主文字
    public static int TEXT_SUB = 0xFF8A8F99; // 次文字
    public static int TEXT_WEAK = 0xFFB0B5BD;
    public static int ACCENT = 0xFF00A6A6;  // 强调（青绿）
    public static int ACCENT_SOFT = 0x1A00A6A6;
    public static int LINE = 0xFFE8EAED;    // 分割线
    public static int CHIP_BG = 0xFFF0F1F3; // 未选中的 chip 底
    public static int RANK_TOP = 0xFFFF6B35; // 榜单前三
    public static int RANK_N = 0xFF9AA0A6;
    public static int SKELETON = 0xFFECEFF3;   // 骨架屏底块
    public static int SKELETON_SHINE = 0xCCFFFFFF; // 骨架屏扫光
    public static int HANDLE = 0xFFD8DBE0;     // 弹层抓手
    public static int SEG_ON = 0xFFFFFFFF;     // 分段控件选中块底
    /** 25 周年专栏标题的金色（浅色深金 / 深色浅金）。 */
    public static int GOLD_TITLE = 0xFF8A6A1F;
    /** 专栏标题下面那条极淡的金色细线。 */
    public static int GOLD_RULE = 0x33C79A3F;
    /** ACCENT 底色上的前景色（浅色白 / 深色深青 #062B2B，对比度 2.02:1 → 7.50:1）。 */
    public static int ON_ACCENT = 0xFFFFFFFF;

    private static boolean colorsLoaded;
    /** 上次载入配色时的 uiMode（night 位）。切深浅色要重载，否则静态色值还是旧的。 */
    private static int loadedNightMode = -1;

    // ------------------------------------------------------------ 尺寸（dp）
    public static final float R_CARD = 12f;   // 卡片圆角
    /**
     * 药丸圆角。**必须 ≤ 药丸高度的一半**，否则角弧会被绘制层钳制、看起来上下被削平
     * （坑 #11；2026-10-18 详情页的「视频 / VLOG / FR」被用户指出"显示不全"就是这条：
     * 16dp = 42px 而药丸高 76px、半高只有 38px）。现在 14dp ＜ 半高，是标准 stadium。
     */
    public static final float R_CHIP = 14f;
    public static final float R_BTN = 8f;     // 按钮圆角

    private Ui() {}

    /**
     * 从 colors.xml 载入配色。每个界面在 setContentView 之前调一次即可（幂等）。
     *
     * 只在这里读一次资源，是为了让 Ui.BG / Ui.ACCENT 这类名字在任何界面里
     * 都能当普通 int 用，调用点一个都不用改。
     */
    public static void init(Context c) {
        loadHapticSettings(c);          // 触感设置每次 init 都重读（改完设置页能立刻生效）
        Api.setLang();                 // 接口语言跟系统走（zh → zh-CN，服务端只认这个写法）
        // 外观偏好（浅色/深色/跟随系统）也在这里下发：每个界面进来都会调 init，
        // 等于全 app 唯一钩子；内部有"每进程只下发一次"的守卫，不会反复触发重建。
        Prefs.applyAppearance(c);

        // 深浅色切换时 Activity 会重建、但进程里的静态色值不会自己变 ——
        // 所以按 uiMode 的 night 位判断，变了就重读一遍。
        int night = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (colorsLoaded && night == loadedNightMode) return;
        loadedNightMode = night;
        colorsLoaded = true;
        BG = c.getColor(R.color.bg);
        CARD = c.getColor(R.color.card);
        TEXT = c.getColor(R.color.text);
        TEXT_SUB = c.getColor(R.color.text_sub);
        TEXT_WEAK = c.getColor(R.color.text_weak);
        ACCENT = c.getColor(R.color.accent);
        ACCENT_SOFT = c.getColor(R.color.accent_soft);
        LINE = c.getColor(R.color.line);
        CHIP_BG = c.getColor(R.color.chip_bg);
        RANK_TOP = c.getColor(R.color.rank_top);
        RANK_N = c.getColor(R.color.rank_n);
        SKELETON = c.getColor(R.color.skeleton);
        SKELETON_SHINE = c.getColor(R.color.skeleton_shine);
        HANDLE = c.getColor(R.color.handle);
        SEG_ON = c.getColor(R.color.seg_on);
        GOLD_TITLE = c.getColor(R.color.gold_title);
        GOLD_RULE = c.getColor(R.color.gold_rule);
        ON_ACCENT = c.getColor(R.color.on_accent);
    }

    // ------------------------------------------------------------ 基础

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 圆角实心背景。 */
    public static GradientDrawable round(int fill, float radiusDp, Context c, Integer stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != null) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    /** 白卡片容器：圆角 + 极淡描边 + 1dp 高度，比纯描边更有层次。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, R_CARD, c, LINE));
        l.setElevation(dp(c, 1f));
        return l;
    }

    /** 只有上面两个圆角的背景（底部弹层用）。 */
    public static GradientDrawable roundTop(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        float r = dp(c, radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        return d;
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

    private static Typeface MEDIUM;

    /** 中等字重（比默认 Bold 更"贵"，中文下也不会糊成一团）。 */
    public static Typeface medium() {
        if (MEDIUM == null) MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return MEDIUM;
    }

    public static TextView h1(Context c, String s) {
        TextView t = text(c, s, 18f, TEXT, false);
        t.setTypeface(medium());
        t.setLetterSpacing(-0.005f);
        return t;
    }

    public static TextView h2(Context c, String s) {
        TextView t = text(c, s, 14.5f, TEXT, false);
        t.setTypeface(medium());
        return t;
    }

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

    /**
     * 「专栏」标题：左侧青绿竖条（与普通小节**同一套 3×15dp 尺寸**，一眼看得出是同级），
     * 标题本身走金色 + 加大 1sp + 加宽字距 —— 这是"专栏"的字体特色，
     * 依赖的 gold_title 在浅色下是深金（#8A6A1F，对底 4.71:1），深色下是浅金（13.63:1）。
     *
     * 返回的 LinearLayout 里：竖条、标题（weight=1，过长会省略）、右侧副元素（由调用方 add）。
     */
    public static LinearLayout columnTitle(Context c, String s) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 14), 0, dp(c, 8));

        View bar = new View(c);
        bar.setBackground(round(ACCENT, 1.5f, c, null));
        r.addView(bar, new LinearLayout.LayoutParams(dp(c, 3), dp(c, 15)));

        TextView t = text(c, s, 16f, GOLD_TITLE, false);
        t.setTypeface(medium());
        t.setLetterSpacing(0.06f);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = lpWeight(1f);
        tp.leftMargin = dp(c, 8);
        r.addView(t, tp);
        return r;
    }

    /** 专栏标题下面那条极淡的金色细线（1dp，通栏），把"专栏"和下面的内容轻轻隔开。 */
    public static View columnRule(Context c) {
        View v = new View(c);
        v.setBackgroundColor(GOLD_RULE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    /**
     * 列表加载中的骨架：n 行"缩略图方块 + 两条文字条"，全部是 {@link Shimmer}（自带扫光动画）。
     *
     * 只用于**异步查询还没回来**的那一小段时间（排行/筛选都是 `pool.execute` + `ui.post`），
     * 查询一回来就整块换回真列表，所以最终态和基线一致。
     */
    public static LinearLayout skeletonList(Context c, int n) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 8), 0, 0);
        for (int i = 0; i < n; i++) {
            LinearLayout row = row(c);
            row.setPadding(0, dp(c, 8), 0, dp(c, 8));

            Shimmer thumb = new Shimmer(c).radius(R_CARD);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(c, 64), dp(c, 64)));

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = lpWeight(1f);
            cp.leftMargin = dp(c, 12);
            row.addView(col, cp);

            Shimmer l1 = new Shimmer(c).radius(4f);
            col.addView(l1, new LinearLayout.LayoutParams(dp(c, 132), dp(c, 13)));

            Shimmer l2 = new Shimmer(c).radius(4f);
            LinearLayout.LayoutParams l2p = new LinearLayout.LayoutParams(dp(c, 88), dp(c, 10));
            l2p.topMargin = dp(c, 8);
            col.addView(l2, l2p);

            box.addView(row, lpMatch());
        }
        box.setVisibility(View.GONE);
        return box;
    }

    // ------------------------------------------------------------ 图标
    //
    // 全部图标都是 res/drawable/ic_*.xml 的矢量图，这里只负责【尺寸 + 颜色 + 触控】：
    //   · 颜色一律传 token（TEXT_SUB / ACCENT / Color.WHITE），不传字面色值
    //   · 有独立可点区域的用 iconButton()，别裸放一个 ImageView 当按钮
    //
    // 【为什么不再拿字符当图标】
    // 之前 🔍 ✕ ← › ↓ ▲ ▼ ▾ 都是文本。用户手机装了第三方衬线字体，
    // 这些字符会跟着换字体、变宽变窄，严重的直接被替换成豆腐块 ——
    // 一个字符塌了，整行基线都跟着歪。矢量图跟字体无关，且能跟着 token 改色。

    /** 独立可点图标的最小触控边长（dp）。低于这个值手指点不中。 */
    public static final float ICON_TOUCH = 48f;

    /** 把"想要的尺寸"套进 measureSpec：EXACTLY 听父的，AT_MOST 取小的，其余用想要的。 */
    private static int fitToSpec(int desired, int spec) {
        int mode = View.MeasureSpec.getMode(spec);
        int size = View.MeasureSpec.getSize(spec);
        if (mode == View.MeasureSpec.EXACTLY) return size;
        if (mode == View.MeasureSpec.AT_MOST) return Math.min(desired, size);
        return desired;
    }

    /**
     * 一个矢量图标。
     *
     * 自己量自己的尺寸（和 divider 里那个 Line 同一个套路）：
     * 这样调用方可以照常 addView(icon, lpWrap()) 再补 margin，
     * 不用为了加 3dp 间距去 cast LayoutParams。
     */
    public static ImageView icon(Context c, int res, final float sizeDp, int color) {
        final int px = dp(c, sizeDp);
        ImageView iv = new ImageView(c) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                setMeasuredDimension(fitToSpec(px, wSpec), fitToSpec(px, hSpec));
            }
        };
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 用 tint 而不是给每份 drawable 写一套颜色：暗色模式只要换传进来的 token
        iv.setImageTintList(ColorStateList.valueOf(color));
        return iv;
    }

    /**
     * 可点的图标按钮：图标小、触控区域大。
     *
     * 水波纹遮罩是圆的 → 反馈只在图标周围一圈，不会漫成一个方块。
     * touchDp 小于 ICON_TOUCH 只在"被容器高度卡住"的场合用（比如输入框里的清空叉）。
     */
    public static FrameLayout iconButton(Context c, int res, float sizeDp, int color,
                                         float touchDp, String desc, final Runnable action) {
        final int touch = dp(c, touchDp);
        FrameLayout box = new FrameLayout(c) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                super.onMeasure(
                        View.MeasureSpec.makeMeasureSpec(
                                fitToSpec(touch, wSpec), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(
                                fitToSpec(touch, hSpec), View.MeasureSpec.EXACTLY));
            }
        };
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        box.setBackground(new RippleDrawable(
                ColorStateList.valueOf(c.getColor(R.color.card_press)), null, mask));
        box.addView(icon(c, res, sizeDp, color), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        box.setContentDescription(desc);
        box.setFocusable(true);
        if (action != null) {
            box.setClickable(true);
            box.setOnClickListener(v -> action.run());
        }
        return box;
    }

    /** 图标 + 数字的紧凑组合（下载量用）。返回的容器里 index 0 是图标、1 是文字。 */
    public static LinearLayout stat(Context c, int iconRes, String s,
                                    float sizeSp, int color, boolean bold) {
        LinearLayout r = row(c);
        // 图标比字小半号：同字号时图标看着"重"，会把数字压下去
        r.addView(icon(c, iconRes, Math.max(10f, sizeSp - 0.5f), color));
        TextView t = text(c, s, sizeSp, color, bold);
        LinearLayout.LayoutParams tp = lpWrap();
        tp.leftMargin = dp(c, 3);
        r.addView(t, tp);
        return r;
    }

    /** 取出 stat() 里那个 TextView（要单独改字重/颜色时用）。 */
    public static TextView statLabel(View stat) {
        if (!(stat instanceof LinearLayout)) return null;
        View v = ((LinearLayout) stat).getChildAt(1);
        return v instanceof TextView ? (TextView) v : null;
    }

    // ------------------------------------------------------------ 控件

    /** 药丸标签。selected 时青绿底白字，否则浅灰底深字。 */
    public static TextView chip(Context c, String s, boolean selected) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        // 高度至少 2×圆角，否则角弧会被钳制（见 R_CHIP 注释）
        t.setMinimumHeight(dp(c, Math.round(R_CHIP * 2)));
        applyChip(t, c, selected);
        TouchExpand.apply(t);          // 视觉 28~30dp，触摸区补到 48dp
        return t;
    }

    public static void applyChip(TextView t, Context c, boolean selected) {
        if (selected) {
            t.setBackground(round(ACCENT, R_CHIP, c, null));
            t.setTextColor(ON_ACCENT);
            t.setTypeface(null, Typeface.BOLD);
        } else {
            t.setBackground(round(CHIP_BG, R_CHIP, c, null));
            t.setTextColor(TEXT);
            t.setTypeface(null, Typeface.NORMAL);
        }
    }

    // ------------------------------------------------------------ 分段控件
    //
    // 「发现」页搜索框下面那条（全部 / 视频 / 照片）：浅灰轨道 + 白色选中块。
    // 「排行」页的题材筛选是同一个交互，必须共用这一份代码，否则两处会各自漂。

    /** 轨道：浅灰底 + 4dp 内边距；item 由调用方用 weight 塞进来。 */
    public static LinearLayout segmentRail(Context c) {
        LinearLayout rail = row(c);
        rail.setBackground(round(CHIP_BG, 12f, c, null));
        rail.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        return rail;
    }

    /**
     * 轨道里的一项。
     *
     * 【高度只靠上下 9dp 内边距，不写死】写死高度在大字体下会把字切掉；
     * 圆角固定 10dp，远小于控件半高，也就不会出现"半径超过半高、被绘制引擎钳制"
     * 那一类几何问题（排行页原来那排独立的 16dp 药丸就栽在这儿：盒高 30dp、
     * 只画出 26dp、顶边还缺一条）。
     */
    public static TextView segmentItem(Context c, String label, boolean selected) {
        TextView t = text(c, label, 13f, TEXT_SUB, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 9), 0, dp(c, 9));
        paintSegment(t, c, selected);
        TouchExpand.apply(t);
        return t;
    }

    /**
     * 重画一项的选中态：选中 = 亮块 + 主色加粗，未选中 = 透明 + 次色。
     *
     * 选中块用 SEG_ON 而不是 CARD：浅色下两者都是白；暗色下 CARD 比轨道还暗，
     * 用它会变成"凹槽"，而选中应当凸起。
     */
    public static void paintSegment(TextView t, Context c, boolean selected) {
        t.setBackground(selected ? round(SEG_ON, 10f, c, null) : null);
        t.setTextColor(selected ? ACCENT : TEXT_SUB);
        t.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** 主按钮（青绿实心）。 */
    public static TextView primaryBtn(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(14f);
        t.setTextColor(ON_ACCENT);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        t.setBackground(round(ACCENT, R_BTN, c, null));
        return t;
    }

    /**
     * 主按钮的图标版：青绿底 + 前景色图标/文字（浅色白、深色深青，见 ON_ACCENT）。
     *
     * 内边距与 primaryBtn 完全一致，所以两个按钮并排时高度一样；
     * 水波纹用 33FFFFFF（半透明白），深色底上才看得见 —— card_press 那种近黑色压在青绿上是瞎的。
     */
    public static LinearLayout primaryIconBtn(Context c, int iconRes, String s) {
        LinearLayout b = row(c);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(ACCENT, 0x33FFFFFF, R_BTN, c, null));
        b.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        b.addView(icon(c, iconRes, 16f, ON_ACCENT));
        TextView t = text(c, s, 14f, ON_ACCENT, true);
        LinearLayout.LayoutParams tp = lpWrap();
        tp.leftMargin = dp(c, 7);
        b.addView(t, tp);
        return b;
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

    // ------------------------------------------------------------ 动效

    /**
     * 任意纯色底 + 水波纹。
     *
     * content 是常态底（描边可选），mask 决定波纹被裁成什么形状 ——
     * 两者形状必须一致，否则波纹会从圆角外面漏出来。
     */
    public static Drawable ripple(int fill, int rippleColor, float radiusDp,
                                 Context c, Integer stroke) {
        GradientDrawable content = round(fill, radiusDp, c, stroke);
        GradientDrawable mask = round(0xFFFFFFFF, radiusDp, c, null);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    /**
     * 可点击容器的统一背景：白底卡片 + 水波纹。
     *
     * 之前所有卡片/按钮都是"死"的 —— 点下去没有任何反馈，
     * 这是"廉价感"最直接的来源。RippleDrawable 在 API 21+ 原生支持。
     */
    public static Drawable rippleCard(Context c, float radiusDp, Integer stroke) {
        return ripple(CARD, c.getColor(R.color.card_press), radiusDp, c, stroke);
    }

    /** 加按压反馈：水波纹 + 0.97 缩放。放 UP/CANCEL 时回弹。 */
    public static void pressable(final View v, final Runnable action) {
        v.setClickable(true);
        final float s = 0.97f;
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(s).scaleY(s).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    break;
                default:
                    break;
            }
            return false;   // 不抢事件，click 照常派发
        });
        v.setOnClickListener(x -> action.run());
    }

    /** 淡入 + 轻微上移。用于内容出现、列表项、弹层内部块。 */
    public static void fadeInUp(final View v, long delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 10));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    // ------------------------------------------------------------ 触感
    //
    // 【通道：Vibrator + VibrationEffect.createOneShot(时长, 振幅)】
    // 为什么不是 View.performHapticFeedback —— 它只有系统预置效果，**给不了振幅**，
    // 做不出"低/中/高"。代价两个，都认：
    //   · 需要普通权限 android.permission.VIBRATE（安装即授予，不弹框）
    //   · 系统里关掉"触感反馈"时我们**不会**自动静默 → 所以设置页有一条本应用自己的总闸
    // 兜底：拿不到 Vibrator 就退回 performHapticFeedback，强度不可控但还有一下。
    //
    // 三档语义，别到处乱用 —— 全屏都在抖比没有触感更廉价：
    //   tick()      切换类：底部 Tab、分段控件、展开/收起
    //   tap()       确认类：主按钮、把手抓取
    //   longPress() 长按类：长按删除、长按行
    //
    // 调用点一律放在**用户动作的回调里**，不要放进 buildXxx / showTab 这类程序化路径，
    // 否则冷启动、同步完成自动切页时也会震一下。


    /** 触感强度：0 低 / 1 中 / 2 高。 */
    private static boolean hapticOn = true;
    private static int hapticLevel = 1;

    private static final int KIND_TICK = 0;
    private static final int KIND_TAP = 1;
    private static final int KIND_LONG = 2;

    /**
     * 每种动作的 {时长 ms, 低振幅, 中振幅, 高振幅}。
     *
     * 时长压得很短：8~22ms 是"一下"，再长就成"嗡一声"了；
     * 振幅 255 是上限，低档取 27%~43% 才拉得开差距（不然三档听着一样）。
     */
    private static final int[][] SHAPE = {
            {  8,  70, 130, 200 },   // tick：最轻
            { 14,  90, 165, 255 },   // tap：实一点
            { 22, 110, 190, 255 },   // longPress：最长最沉
    };

    private static android.os.Vibrator VIBRATOR;

    /**
     * 从 Prefs 读开关与强度。`Ui.init()` 每次都会调；设置页改完再显式调一次。
     *
     * 为什么每次 init 都读：MainActivity 常驻，用户改完设置返回时它不会重建，
     * 只有靠 init（每个界面进来都会调）+ 设置页主动调，新强度才能立刻生效。
     */
    public static void loadHapticSettings(Context c) {
        if (c == null) return;
        hapticOn = Prefs.hapticOn(c);
        hapticLevel = Prefs.hapticLevel(c);
    }

    /** 切换类触感（Tab / 分段控件 / 展开收起）。 */
    public static void tick(View v) {
        haptic(v, KIND_TICK);
    }

    /** 确认类触感（主按钮、把手抓取）。 */
    public static void tap(View v) {
        haptic(v, KIND_TAP);
    }

    /** 长按类触感（长按删除、长按行）。 */
    public static void longPress(View v) {
        haptic(v, KIND_LONG);
    }

    /**
     * 真正下发振动。
     *
     * 【为什么换成 Vibrator】
     * 强度要可调，而 performHapticFeedback 只有"系统预置效果"、给不了振幅；
     * `VibrationEffect.createOneShot(时长, 振幅)` 才能做低/中/高。
     * 代价是要 VIBRATE 权限（普通权限，安装即授予），并且**系统里关掉触感时我们不会自动静默** ——
     * 所以设置页有一条本应用自己的总闸（默认开），由用户显式控制。
     *
     * 【兜底】拿不到 Vibrator（模拟器 / 异常 ROM）时退回 performHapticFeedback，
     * 强度不可控，但至少还有一下。
     */
    private static void haptic(View v, int kind) {
        if (v == null || !hapticOn) return;
        int[] shape = SHAPE[kind];
        android.os.Vibrator vib = vibrator(v.getContext());
        if (vib != null && vib.hasVibrator()) {
            try {
                vib.vibrate(android.os.VibrationEffect.createOneShot(
                        shape[0], Math.max(1, shape[1 + hapticLevel])));
                return;
            } catch (Throwable ignored) {
                // 省电模式 / 某些 ROM 会拒绝一次性振动 —— 落到下面走系统通道
            }
        }
        v.performHapticFeedback(kind == KIND_TICK
                ? android.view.HapticFeedbackConstants.CLOCK_TICK
                : android.view.HapticFeedbackConstants.CONTEXT_CLICK);
    }

    private static android.os.Vibrator vibrator(Context c) {
        if (VIBRATOR == null && c != null) {
            VIBRATOR = (android.os.Vibrator) c.getApplicationContext()
                    .getSystemService(Context.VIBRATOR_SERVICE);
        }
        return VIBRATOR;
    }
}
