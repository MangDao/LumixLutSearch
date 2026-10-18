package io.github.lumixlut.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LUMIX Lab 社区 LUT 浏览器。
 *
 * 结构：
 *   底部 4 个 Tab —— 发现 / 排行 / 筛选 / 我的
 *   顶部搜索框 → SearchActivity（历史 / 热词 / 分组结果）
 *   详情弹窗带官方样张滑动对比
 *   下载走官方接口，落盘到公共「下载/LUMIX_LUT/」
 *
 * 筛选维度【全部是服务端真实字段】，无文本推断：
 *   题材 genre_code（movie/still）· 格调 photo_style · 国家 countries
 * 下载量是真实字段；创作者榜是由下载量求和得到的【派生】数据，界面里已标注。
 */
public class MainActivity extends Activity {

    private static final String PREF = "firstrun";
    private static final String KEY_PRELOADED = "preloaded";

    private static final int TAB_DISCOVER = 0;
    private static final int TAB_RANK = 1;
    private static final int TAB_FILTER = 2;
    private static final int TAB_MINE = 3;

    /** 搜索页回传选中项的请求码。 */
    private static final int REQ_SEARCH = 1001;

    private Store store;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout host;
    private LinearLayout tabBar;
    private final List<View> tabViews = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    /** 每个 tab 的选中指示条（2dp 高），跟 tabLabels 一一对应。 */
    private final List<View> tabIndicators = new ArrayList<>();
    private int tab = TAB_DISCOVER;

    /** 发现页当前题材过滤："" = 全部，"movie" / "still"。就地切换，不再跳到「筛选」tab。 */
    private String discoverGenre = "";
    /** 发现页热门网格容器；切换题材时原地重绘这块，不动滚动位置。 */
    private LinearLayout hotGrid;
    private final List<TextView> segChips = new ArrayList<>();
    private final List<String> segGenres = new ArrayList<>();

    private ProgressBar bar;
    private TextView barText;

    /** 当前筛选条件（多选）。 */
    private final Store.Filter filter = new Store.Filter();
    private final List<Lut> shown = new ArrayList<>();

    /** 首页「按格调浏览」是否展开。收起时只显示前 6 个。 */
    private boolean stylesExpanded = true;
    /** 发现页重建计数：旧的一次性填充任务见到新 token 就放弃，避免往已废弃的视图里加东西。 */
    private int discoverToken;

    public MainActivity() {}

    // ============================================================ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        ImageLoader.get().warmUp();   // 提前把图片域名的 DNS/TLS 打通              // 配色来自 colors.xml，必须在建视图之前
        store = new Store(this);
        ImageLoader.init(this);
        setContentView(buildRoot());

        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        if (!sp.getBoolean(KEY_PRELOADED, false) && store.count() == 0) {
            showFirstRun();
        } else {
            showTab(TAB_DISCOVER);
            maybeAutoSync();
        }
    }

    /** 用浏览器打开外部链接（25 周年专题的「更多」/ 素材来源）。 */
    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            android.widget.Toast t = android.widget.Toast.makeText(this,
                    getString(R.string.anniv_dl_fail), android.widget.Toast.LENGTH_SHORT);
            t.show();
        }
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private View buildRoot() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 进度条（预载/补齐时用）
        LinearLayout barBox = Ui.col(this);
        barBox.setPadding(dp(16), dp(2), dp(16), dp(2));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        barBox.addView(bar, Ui.lpMatch());
        barText = Ui.sub(this, "");
        barText.setVisibility(View.GONE);
        barBox.addView(barText);
        root.addView(barBox);

        root.addView(buildTabBar());
        return root;
    }

    private View buildTabBar() {
        tabBar = Ui.row(this);
        tabBar.setBackgroundColor(Ui.CARD);
        tabBar.setPadding(0, dp(6), 0, dp(6));

        View top = Ui.divider(this);
        LinearLayout wrap = Ui.col(this);
        wrap.addView(top);
        wrap.addView(tabBar);

        String[] names = {
                getString(R.string.tab_discover), getString(R.string.tab_rank),
                getString(R.string.tab_filter), getString(R.string.tab_mine),
        };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            // 触控目标 ≥ 48dp；原来那排「●」圆点看着像未读标记，其实什么都不表示，已经去掉
            cell.setMinimumHeight(dp(52));
            cell.setPadding(0, dp(8), 0, dp(6));

            TextView lb = Ui.text(this, names[i], 12f, Ui.TEXT_SUB, false);
            lb.setGravity(Gravity.CENTER);
            cell.addView(lb);

            View ind = new View(this);
            ind.setBackgroundColor(Ui.ACCENT);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(18), dp(2));
            ip.topMargin = dp(6);
            cell.addView(ind, ip);
            ind.setAlpha(0f);                       // 选中时才显形，替代原来的圆点
            tabIndicators.add(ind);

            tabLabels.add(lb);
            // 触感放在点击回调里：showTab 也被冷启动/同步完成调用，那些路径不该震
            cell.setOnClickListener(v -> { Ui.tick(v); showTab(idx); });
            tabBar.addView(cell, Ui.lpWeight(1f));
        }
        return wrap;
    }

    /**
     * 让某个 Tab 缓存失效，下次切过去时重建。
     *
     * 【为什么不能直接 tabViews.set(idx, null)】
     * tabViews 是按需增长的：启动只建了「发现」，size 就是 1。
     * 这时 set(2, null) 直接抛 IndexOutOfBoundsException ——
     * 点首页的「视频 / 照片 / 全部」正好走到这条路径，必崩。
     * 先把列表补到够长，再置空。
     */
    private void dropTab(int idx) {
        while (tabViews.size() <= idx) tabViews.add(null);
        tabViews.set(idx, null);
    }

    /** 切 Tab。每个 Tab 的内容按需构建，切回来不重建（省得丢滚动位置）。 */
    private void showTab(int idx) {
        tab = idx;
        while (tabViews.size() <= idx) tabViews.add(null);

        if (tabViews.get(idx) == null) {
            View v;
            switch (idx) {
                case TAB_RANK: v = buildRank(); break;
                case TAB_FILTER: v = buildFilterTab(); break;
                case TAB_MINE: v = buildMine(); break;
                default: v = buildDiscover(); break;
            }
            tabViews.set(idx, v);
        }

        host.removeAllViews();
        host.addView(tabViews.get(idx), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 切 Tab 不再硬切：内容淡入 + 8dp 上移
        Ui.fadeInUp(tabViews.get(idx), 0);

        for (int i = 0; i < tabLabels.size(); i++) {
            boolean on = (i == idx);
            tabLabels.get(i).setTextColor(on ? Ui.ACCENT : Ui.TEXT_SUB);
            tabLabels.get(i).setTypeface(on ? Ui.medium() : Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            if (i < tabIndicators.size()) {
                View ind = tabIndicators.get(i);
                ind.animate().alpha(on ? 1f : 0f).setDuration(160).start();
            }
        }
        // 排行/筛选页需要现算数据
        if (idx == TAB_RANK) loadRank();
        if (idx == TAB_FILTER) runFilterQuery();
    }

    /** 切到指定 Tab 并带上筛选条件（首页点分类时用）。 */
    private void gotoFilterWith(Store.Filter f) {
        filter.keyword = f.keyword;
        filter.genres.clear();   filter.genres.addAll(f.genres);
        filter.styles.clear();   filter.styles.addAll(f.styles);
        filter.countries.clear();filter.countries.addAll(f.countries);
        filter.creator = f.creator;
        filter.sort = f.sort;
        dropTab(TAB_FILTER);   // 强制重建，让 chip 反映新条件
        showTab(TAB_FILTER);
    }

    // ============================================================ 发现

    private View buildDiscover() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(col);

        // 搜索入口
        LinearLayout search = Ui.row(this);
        search.setBackground(Ui.round(Ui.CARD, 22f, this, Ui.LINE));
        // 上下的 15dp 让整行落到 48dp，正好是可点区域的下限（原来 11dp 只有 ~40dp）
        search.setPadding(dp(14), dp(15), dp(14), dp(15));
        search.addView(Ui.icon(this, R.drawable.ic_search, 16f, Ui.TEXT_SUB), Ui.lpWrap());
        TextView hint = Ui.text(this, getString(R.string.search_hint), 13.5f, Ui.TEXT_SUB, false);
        LinearLayout.LayoutParams hp = Ui.lpWeight(1f);
        hp.leftMargin = dp(9);
        search.addView(hint, hp);
        // 图标是纯装饰，无障碍文案挂到整行上，TalkBack 才不会把它念成一堆图形
        search.setContentDescription(getString(R.string.cd_search));
        search.setFocusable(true);
        search.setOnClickListener(v ->
                startActivityForResult(new Intent(this, SearchActivity.class), REQ_SEARCH));
        col.addView(search, Ui.lpMatch());

        // 题材分段控件：就地过滤下面的网格。
        // 原来这三个 chip 点了会跳到「筛选」tab（还带预设条件）——
        // 用户以为只是换个分类，结果被换了页面、丢了滚动位置，这是交互最别扭的一处。
        LinearLayout seg = Ui.segmentRail(this);
        LinearLayout.LayoutParams segLp = Ui.lpMatch();
        segLp.topMargin = dp(12);
        col.addView(seg, segLp);

        segChips.clear();
        segGenres.clear();
        addSeg(seg, "", getString(R.string.discover_seg_all_fmt, store.count()));
        addSeg(seg, "movie", getString(R.string.discover_seg_movie_fmt,
                store.countGenre("movie")));
        addSeg(seg, "still", getString(R.string.discover_seg_still_fmt,
                store.countGenre("still")));
        paintSeg();

        // 首屏只出上面这些 —— 搜索框和快捷 chips 已经能交互。
        // 下面这些内容（热门 8 张卡 + 13 个格调格 + 10 行创作者）放到第一帧之后再建：
        // 启动时少建 30 多个 View，白屏时间更短，数据一条不少。
        final int token = ++discoverToken;
        sv.post(() -> {
            if (token != discoverToken || isFinishing()) return;
            fillDiscoverRest(col);
        });

        return sv;
    }

    /** 往分段控件里加一项。genre 为空串表示「全部」。 */
    private void addSeg(LinearLayout seg, final String genre, String label) {
        final TextView t = Ui.segmentItem(this, label, genre.equals(discoverGenre));
        t.setOnClickListener(v -> {
            Ui.tick(v);                                // 先给反馈：点已选中项也要有回应
            if (genre.equals(discoverGenre)) return;   // 已选中就不再重绘
            discoverGenre = genre;
            paintSeg();
            renderHot();                               // 原地换网格，不切 tab、不动滚动
        });
        segChips.add(t);
        segGenres.add(genre);
        seg.addView(t, Ui.lpWeight(1f));
    }

    /** 重画分段控件选中态（样式定义在 Ui.paintSegment，和排行页共用一份）。 */
    private void paintSeg() {
        for (int i = 0; i < segChips.size(); i++) {
            Ui.paintSegment(segChips.get(i), this, segGenres.get(i).equals(discoverGenre));
        }
    }

    /**
     * 重绘热门网格（按 discoverGenre 过滤）。
     *
     * 只清空 hotGrid 里的内容，不动外层 ScrollView —— 所以切换题材时滚动位置保持不变。
     * 「全部」显示下载量前 8；某一题材时放宽到 40，让用户真的能翻到东西。
     */
    private void renderHot() {
        if (hotGrid == null) return;
        hotGrid.removeAllViews();

        String g = discoverGenre.isEmpty() ? null : discoverGenre;
        List<Lut> hot = store.ranking(g == null ? 8 : 40, g);
        if (hot.isEmpty()) {
            TextView empty = Ui.sub(this, g == null
                    ? getString(R.string.discover_empty_lib)
                    : getString(R.string.discover_empty_genre));
            empty.setPadding(0, dp(8), 0, dp(8));
            hotGrid.addView(empty);
            return;
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int gap = dp(10);
        int cellW = (screenW - dp(32) - gap) / 2;
        int thumbH = cellW * 2 / 3;

        LinearLayout rowView = null;
        for (int i = 0; i < hot.size(); i++) {
            if (i % 2 == 0) {
                rowView = Ui.row(this);
                rowView.setGravity(Gravity.TOP);
                hotGrid.addView(rowView, Ui.lpMatch());
            }
            LinearLayout cell = hotCell(hot.get(i), cellW, thumbH);
            LinearLayout.LayoutParams cp = Ui.lpWeight(1f);
            cp.setMargins(i % 2 == 0 ? 0 : gap / 2, 0, i % 2 == 0 ? gap / 2 : 0, dp(10));
            rowView.addView(cell, cp);
        }
        if (hot.size() % 2 == 1 && rowView != null) {
            View pad = new View(this);
            LinearLayout.LayoutParams lp = Ui.lpWeight(1f);
            lp.leftMargin = gap / 2;
            rowView.addView(pad, lp);
        }
    }

    /** 发现页「首屏以下」的全部内容：热门推荐 / 格调 / 创作者 / 维护入口 / 页脚。 */
    private void fillDiscoverRest(LinearLayout col) {
        // ---- 松下 25 周年专题（松影俱乐部 lcid=113；不在 LUMIX Lab 目录里，见 Anniv25）----
        col.addView(Anniv25Ui.strip(this,
                () -> startActivity(new android.content.Intent(this, SongyingActivity.class))));

        // ---- 热门推荐：容器只建一次，切题材时只重绘里面这块 ----
        col.addView(Ui.sectionTitle(this, getString(R.string.discover_hot)));
        hotGrid = Ui.col(this);
        col.addView(hotGrid);
        renderHot();

        // ---- 格调分类宫格（可收起）----
        Map<String, int[]> styles = store.styleStats();
        final boolean hasStyles = !styles.isEmpty();

        LinearLayout styleHead = Ui.row(this);
        styleHead.addView(Ui.sectionTitle(this, getString(R.string.discover_styles)),
                Ui.lpWeight(1f));

        if (!hasStyles) {
            col.addView(styleHead);
            col.addView(Ui.sub(this, getString(R.string.discover_styles_empty)));
        } else {
            final int kCollapsedRows = 2;   // 收起时只留两行

            // 先建全部，收起/展开只切可见性 ——
            // 之前是重建整个 Tab，结果滚动位置被重置回顶部，
            // 用户点「收起」后反而看不到刚点的那个按钮了。
            final LinearLayout sgrid = Ui.col(this);
            final List<View> allRows = new ArrayList<>();
            final List<View> lastRowHolder = new ArrayList<>();
            LinearLayout cur = null;
            int n = 0;
            int total = 0;
            for (final Map.Entry<String, int[]> e : styles.entrySet()) {
                if ("?".equals(e.getKey())) continue;
                total++;
                if (n % 3 == 0) {
                    cur = Ui.row(this);
                    allRows.add(cur);
                    sgrid.addView(cur, Ui.lpMatch());
                }
                LinearLayout cell = Ui.col(this);
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, this, Ui.LINE));
                cell.setPadding(dp(4), dp(12), dp(4), dp(12));

                TextView name = Ui.text(this, e.getKey(), 13.5f, Ui.TEXT, true);
                name.setGravity(Gravity.CENTER);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(name, Ui.lpMatch());
                TextView cnt = Ui.text(this, e.getValue()[0] + " 枚", 11f, Ui.TEXT_SUB, false);
                cnt.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cp = Ui.lpMatch();
                cp.topMargin = dp(3);
                cell.addView(cnt, cp);

                cell.setOnClickListener(v -> {
                    Store.Filter f = new Store.Filter();
                    f.styles.add(e.getKey());
                    gotoFilterWith(f);
                });

                LinearLayout.LayoutParams lp = Ui.lpWeight(1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                cur.addView(cell, lp);
                n++;
            }
            // 补齐最后一行（保持等宽）
            if (cur != null) {
                int rem = 3 - (n % 3);
                if (rem < 3) {
                    for (int i = 0; i < rem; i++) {
                        View pad = new View(this);
                        LinearLayout.LayoutParams lp = Ui.lpWeight(1f);
                        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                        cur.addView(pad, lp);
                    }
                }
            }

            final TextView styleHint = Ui.text(this,
                    getString(R.string.discover_styles_hint_fmt, total), 11f, Ui.TEXT_WEAK, false);
            styleHint.setGravity(Gravity.CENTER);
            styleHint.setPadding(0, dp(8), 0, 0);

            // 收起/展开：只换文字，箭头用一个矢量图标 setRotation(180) ——
            // 以前是「收起 ▲ / 展开 ▼」，▲▼ 是字符，换字体会大小不一。
            final TextView toggleLabel = Ui.text(this,
                    getString(stylesExpanded ? R.string.common_collapse : R.string.common_expand),
                    12.5f, Ui.ACCENT, true);
            final ImageView toggleChev = Ui.icon(this, R.drawable.ic_chevron_down, 13f, Ui.ACCENT);
            final LinearLayout toggle = Ui.row(this);
            toggle.setPadding(dp(8), dp(15), 0, dp(15));
            toggle.addView(toggleLabel, Ui.lpWrap());
            LinearLayout.LayoutParams chevLp = Ui.lpWrap();
            chevLp.leftMargin = dp(3);
            toggle.addView(toggleChev, chevLp);
            toggleChev.setRotation(stylesExpanded ? 180f : 0f);
            toggle.setContentDescription(getString(stylesExpanded
                    ? R.string.common_collapse : R.string.common_expand));
            styleHead.addView(toggle, Ui.lpWrap());
            col.addView(styleHead);
            col.addView(sgrid);
            col.addView(styleHint, Ui.lpMatch());

            // 初始与点击都用同一个函数，保证两边一致
            final Runnable apply = () -> {
                for (int i = 0; i < allRows.size(); i++) {
                    allRows.get(i).setVisibility(
                            stylesExpanded || i < kCollapsedRows ? View.VISIBLE : View.GONE);
                }
                styleHint.setVisibility(stylesExpanded ? View.GONE : View.VISIBLE);
                toggleLabel.setText(getString(stylesExpanded
                        ? R.string.common_collapse : R.string.common_expand));
                toggle.setContentDescription(getString(stylesExpanded
                        ? R.string.common_collapse : R.string.common_expand));
                toggleChev.animate().rotation(stylesExpanded ? 180f : 0f)
                        .setDuration(180).start();
            };
            apply.run();
            toggle.setOnClickListener(v -> {
                Ui.tick(v);
                stylesExpanded = !stylesExpanded;
                apply.run();      // 原地切换，不重建、不跳滚动位置
            });
        }

        // ---- 创作者榜已搬到「排行」页的「作者榜」----
        // 首页原来也放了一份（同一个 store.topCreators），但它被压在「按格调浏览」之后，
        // 又被一个 HAVING 类型 bug 打成空列表；搬过去之后还能就地按作者筛 LUT。

        // ---- 维护入口 ----
        col.addView(Ui.sectionTitle(this, getString(R.string.discover_lib_section)));
        LinearLayout ops = Ui.row(this);
        TextView bPre = Ui.ghostBtn(this, getString(R.string.discover_preload));
        bPre.setOnClickListener(v -> confirmPreload());
        TextView bEnr = Ui.ghostBtn(this, getString(R.string.discover_enrich));
        bEnr.setOnClickListener(v -> confirmEnrich());
        TextView bSty = Ui.ghostBtn(this, getString(R.string.discover_style_dist));
        bSty.setOnClickListener(v -> showStyles());
        ops.addView(bPre, Ui.lpWeight(1f));
        LinearLayout.LayoutParams o1 = Ui.lpWeight(1f); o1.leftMargin = dp(8);
        ops.addView(bEnr, o1);
        LinearLayout.LayoutParams o2 = Ui.lpWeight(1f); o2.leftMargin = dp(8);
        ops.addView(bSty, o2);
        col.addView(ops);

        TextView foot = Ui.text(this, "", 10f, Ui.TEXT_WEAK, false);
        foot.setText(getString(R.string.discover_foot_fmt, appVersion()));
        foot.setPadding(0, dp(16), 0, 0);
        col.addView(foot);
    }

    /**
     * 首页热门推荐的一格（两列竖排用）。
     *
     * 尺寸由调用方按屏幕宽算好传进来：宽度用 weight 自适应，
     * 高度按样张原始比例 3:2 由 cellW 推出，所以换屏也不会变形。
     */
    private LinearLayout hotCell(final Lut l, int cellW, int thumbH) {
        LinearLayout card = Ui.col(this);
        // 白卡 + 水波纹（原来卡片点下去毫无反馈，是廉价感的主要来源之一）
        card.setBackground(Ui.rippleCard(this, Ui.R_CARD, Ui.LINE));
        card.setElevation(dp(1.5f));
        card.setPadding(dp(8), dp(8), dp(8), dp(10));

        // 缩略图外面套一层：底下是骨架屏（灰块 + 扫光），图片到了再淡入盖上
        FrameLayout thumbBox = new FrameLayout(this);
        final Shimmer sk = new Shimmer(this).radius(10f);
        thumbBox.addView(sk, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setAlpha(0f);
        thumbBox.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(thumbBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, thumbH));

        final String path = l.thumbAfter.isEmpty() ? l.thumbPath : l.thumbAfter;
        if (!path.isEmpty()) {
            Bitmap hit = ImageLoader.get().peek(path);
            if (hit != null) {
                iv.setImageBitmap(hit);
                iv.setAlpha(1f);
                sk.setVisibility(View.GONE);
            } else {
                iv.setTag(path);
                ImageLoader.get().load(path, new ImageLoader.Callback() {
                    @Override public void onLoaded(Bitmap b) {
                        if (b != null && path.equals(iv.getTag())) {
                            iv.setImageBitmap(b);
                            iv.animate().alpha(1f).setDuration(220).start();
                            sk.animate().alpha(0f).setDuration(160)
                                    .withEndAction(() -> sk.setVisibility(View.GONE)).start();
                        }
                    }
                    @Override public void onFailed() {
                        // 图没了也别一直扫光，换成安静的空块
                        sk.animate().alpha(0f).setDuration(160)
                                .withEndAction(() -> sk.setVisibility(View.GONE)).start();
                    }
                });
            }
        } else {
            sk.setVisibility(View.GONE);
        }

        TextView t = Ui.h2(this, l.title.isEmpty() ? l.code : l.title);
        t.setTextSize(12.5f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = Ui.lpMatch();
        tp.topMargin = dp(7);
        card.addView(t, tp);

        String meta = l.photoStyle.isEmpty() ? l.genreLabel()
                : l.photoStyle + " · " + l.genreLabel();
        TextView m = Ui.text(this, meta, 10.5f, Ui.TEXT_SUB, false);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(m, Ui.lpMatch());

        LinearLayout d = Ui.stat(this, R.drawable.ic_download,
                Ui.compact(l.downloads), 10.5f, Ui.ACCENT, true);
        TextView dlText = Ui.statLabel(d);
        if (dlText != null) dlText.setTypeface(Ui.medium());
        card.addView(d, Ui.lpMatch());

        Ui.pressable(card, () -> showDetail(l));
        return card;
    }

    /** 格调总数（"?" 是没补齐的占位，不算）。 */
    private int countStyles(Map<String, int[]> m) {
        int n = 0;
        for (String k : m.keySet()) if (!"?".equals(k)) n++;
        return n;
    }

    // ============================================================ 排行（榜单）

    /** 榜单类型：LUT 榜 / 作者榜。两个榜共用一条分段控件切换。 */
    private static final int BOARD_LUT = 0;
    private static final int BOARD_CREATOR = 1;

    /** 骨架屏延迟：本地查询一般在 10ms 内，低于这个阈值不显示，免得闪一下。 */
    private static final long SKELETON_DELAY_MS = 120L;

    private ListView rankList;
    private LinearLayout rankSkeleton;
    private LinearLayout browseSkeleton;
    private LutCardAdapter rankAdapter;
    private CreatorAdapter creatorAdapter;
    private TextView rankEmpty;
    private final List<Lut> rankData = new ArrayList<>();
    private final List<Store.Creator> creatorData = new ArrayList<>();

    private int rankBoard = BOARD_LUT;
    private String rankGenre = "";
    /** 从作者榜点某位作者后，LUT 榜只看这个人；空串 = 不限。 */
    private String rankCreator = "";
    /** 作者胶囊上显示的名字（清除时一起清掉）。 */
    private String rankCreatorLabel = "";
    /** 排行页自己的筛选：只吃格调 / 国家（题材归分段控件，排序固定按下载量）。 */

    private LinearLayout rankGenreRail;
    private LinearLayout rankCondRow;
    private TextView rankSummary;
    private TextView rankBoardSegLut, rankBoardSegAuthor;
    private final List<TextView> rankGenreSegs = new ArrayList<>();
    private final List<String> rankGenreKeys = new ArrayList<>();

    private View buildRank() {
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(16), dp(12), dp(16), 0);

        // 标题行：榜单 + 筛选入口（与「筛选」tab 的入口同款，48dp 触控）
        LinearLayout head = Ui.row(this);
        head.addView(Ui.h1(this, getString(R.string.rank_title)), Ui.lpWeight(1f));
        LinearLayout btnFilter = Ui.row(this);
        btnFilter.setPadding(dp(10), dp(15), 0, dp(15));
        btnFilter.addView(Ui.icon(this, R.drawable.ic_filter, 15f, Ui.ACCENT));
        TextView btnLabel = Ui.text(this, getString(R.string.filter_title), 13f, Ui.ACCENT, true);
        LinearLayout.LayoutParams blp = Ui.lpWrap();
        blp.leftMargin = dp(5);
        btnFilter.addView(btnLabel, blp);
        btnFilter.setContentDescription(getString(R.string.cd_filter));
        btnFilter.setOnClickListener(v -> openRankFilter());
        head.addView(btnFilter, Ui.lpWrap());
        col.addView(head);

        // 榜类型：LUT 榜 / 作者榜
        LinearLayout boardRail = Ui.segmentRail(this);
        rankBoardSegLut = Ui.segmentItem(this, getString(R.string.rank_board_lut),
                rankBoard == BOARD_LUT);
        rankBoardSegAuthor = Ui.segmentItem(this, getString(R.string.rank_board_creator),
                rankBoard == BOARD_CREATOR);
        rankBoardSegLut.setOnClickListener(v -> { Ui.tick(v); setRankBoard(BOARD_LUT); });
        rankBoardSegAuthor.setOnClickListener(v -> { Ui.tick(v); setRankBoard(BOARD_CREATOR); });
        boardRail.addView(rankBoardSegLut, Ui.lpWeight(1f));
        boardRail.addView(rankBoardSegAuthor, Ui.lpWeight(1f));
        LinearLayout.LayoutParams brl = Ui.lpMatch();
        brl.topMargin = dp(10);
        col.addView(boardRail, brl);

        // 题材分段：只有 LUT 榜用（作者榜是"人"的榜，按题材切会把榜首拆碎，所以隐藏）
        rankGenreRail = Ui.segmentRail(this);
        rankGenreSegs.clear();
        rankGenreKeys.clear();
        addRankGenre("", getString(R.string.common_all));
        addRankGenre("movie", getString(R.string.filter_genre_movie));
        addRankGenre("still", getString(R.string.filter_genre_still));
        paintRankGenres();
        LinearLayout.LayoutParams grl = Ui.lpMatch();
        grl.topMargin = dp(8);
        col.addView(rankGenreRail, grl);
        rankGenreRail.setVisibility(rankBoard == BOARD_LUT ? View.VISIBLE : View.GONE);

        // 生效中的条件：作者胶囊（可点掉）+ 一行摘要
        rankCondRow = Ui.row(this);
        rankCondRow.setPadding(0, dp(8), 0, 0);
        col.addView(rankCondRow, Ui.lpMatch());
        rankSummary = Ui.sub(this, "");
        rankSummary.setPadding(0, dp(6), 0, dp(8));
        col.addView(rankSummary, Ui.lpMatch());

        rankAdapter = new LutCardAdapter(this, rankData, true);
        creatorAdapter = new CreatorAdapter(this, creatorData);
        rankList = new ListView(this);
        rankList.setDivider(null);
        rankList.setAdapter(rankBoard == BOARD_LUT ? rankAdapter : creatorAdapter);
        rankList.setOnItemClickListener((p, v, pos, id) -> {
            if (rankBoard == BOARD_CREATOR) {
                if (pos < creatorData.size()) pickCreator(creatorData.get(pos));
            } else if (pos < rankData.size()) {
                showDetail(rankData.get(pos));
            }
        });
        col.addView(rankList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rankSkeleton = Ui.skeletonList(this, 7);
        col.addView(rankSkeleton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 空状态：一条都没有时给提示，而不是留一片空白
        rankEmpty = Ui.text(this, "", 13f, Ui.TEXT_SUB, false);
        rankEmpty.setGravity(Gravity.CENTER);
        rankEmpty.setPadding(0, dp(40), 0, 0);
        rankEmpty.setVisibility(View.GONE);
        col.addView(rankEmpty, Ui.lpMatch());

        // 先把条件行/摘要刷出来（不然要等 loadRank 回来才有字）
        refreshRankCondRow();
        rankSummary.setText(getString(rankBoard == BOARD_LUT
                ? R.string.rank_sort_lut : R.string.rank_sort_creator));
        return col;
    }

    /** 加一项题材。key 为空串表示「全部」。 */
    private void addRankGenre(final String key, String label) {
        final TextView t = Ui.segmentItem(this, label, key.equals(rankGenre));
        t.setOnClickListener(v -> {
            Ui.tick(v);
            if (key.equals(rankGenre)) return;   // 已选中不重查
            rankGenre = key;
            paintRankGenres();
            loadRank();
        });
        rankGenreSegs.add(t);
        rankGenreKeys.add(key);
        rankGenreRail.addView(t, Ui.lpWeight(1f));
    }

    private void paintRankGenres() {
        for (int i = 0; i < rankGenreSegs.size(); i++) {
            Ui.paintSegment(rankGenreSegs.get(i), this, rankGenreKeys.get(i).equals(rankGenre));
        }
    }

    /** 切榜类型：换适配器 + 显隐题材分段 + 重查。 */
    private void setRankBoard(int board) {
        if (rankBoard == board && rankList != null) return;
        rankBoard = board;
        Ui.paintSegment(rankBoardSegLut, this, board == BOARD_LUT);
        Ui.paintSegment(rankBoardSegAuthor, this, board == BOARD_CREATOR);
        if (rankGenreRail != null) {
            rankGenreRail.setVisibility(board == BOARD_LUT ? View.VISIBLE : View.GONE);
        }
        if (rankList != null) {
            rankList.setAdapter(board == BOARD_LUT ? rankAdapter : creatorAdapter);
        }
        loadRank();
    }

    /** 点作者：就地切到 LUT 榜，只看这个人（不跳走，排行页状态全留着）。 */
    private void pickCreator(Store.Creator k) {
        if (k == null || k.code.isEmpty()) return;
        rankCreator = k.code;
        rankCreatorLabel = k.name.isEmpty() ? k.code : k.name;
        setRankBoard(BOARD_LUT);
    }

    /** 重画"生效中的条件"那一行：作者胶囊可点掉。 */
    private void refreshRankCondRow() {
        if (rankCondRow == null) return;
        rankCondRow.removeAllViews();
        // 作者胶囊只描述「LUT 榜只看谁」；停在作者榜时它不生效，就别摆出来误导
        if (rankCreator.isEmpty() || rankBoard != BOARD_LUT) return;
        final TextView chip = Ui.text(this,
                getString(R.string.rank_only_fmt,
                        rankCreatorLabel.isEmpty() ? rankCreator : rankCreatorLabel),
                11.5f, Ui.ACCENT, true);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackground(Ui.round(Ui.ACCENT_SOFT, 16f, this, null));
        chip.setOnClickListener(v -> {
            rankCreator = "";
            rankCreatorLabel = "";
            refreshRankCondRow();
            loadRank();
        });
        rankCondRow.addView(chip, Ui.lpWrap());
    }

    /** 摘要：命中多少 + 当前筛选条件，让用户知道这是"筛过的榜"。 */
    private void refreshRankSummary(boolean empty) {
        if (rankSummary == null) return;
        StringBuilder sb = new StringBuilder();
        if (rankBoard == BOARD_LUT) {
            sb.append(empty ? getString(R.string.rank_hit_none)
                    : getString(R.string.rank_hit_fmt, rankData.size()))
                    .append(getString(R.string.rank_note_lut));
        } else {
            sb.append(empty ? getString(R.string.rank_hit_none)
                    : getString(R.string.rank_creators_fmt, creatorData.size()))
                    .append(getString(R.string.rank_note_creator));
        }
        if (!filter.styles.isEmpty()) {
            sb.append(getString(R.string.rank_style_fmt, join(filter.styles)));
        }
        if (!filter.countries.isEmpty()) {
            sb.append(getString(R.string.rank_country_fmt, join(filter.countries)));
        }
        rankSummary.setText(sb.toString());
    }

    private String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    /** 排行页的筛选入口：复用筛选弹层，但隐藏「排序」（榜固定按下载量）。 */
    private void openRankFilter() {
        Store.Filter cur = new Store.Filter();
        cur.styles.addAll(filter.styles);
        cur.countries.addAll(filter.countries);
        if (!rankGenre.isEmpty()) cur.genres.add(rankGenre);
        FilterSheet.show(this, store, cur, false, false, f -> {
            filter.styles.clear();
            filter.styles.addAll(f.styles);
            filter.countries.clear();
            filter.countries.addAll(f.countries);
            // 弹层里也能改题材：单选就同步到分段控件，多选/清空一律当「全部」
            rankGenre = (f.genres.size() == 1) ? f.genres.get(0) : "";
            paintRankGenres();
            loadRank();
        });
    }

    /**
     * 按当前状态查一次榜。
     *
     * 两个榜吃同一份筛选（题材 / 格调 / 国家）；作者榜按 creator_code 聚合，
     * 从作者榜点进某人时，LUT 榜再叠一个 creator 条件。
     */
    private void loadRank() {
        if (rankList == null) return;
        final int board = rankBoard;
        final Store.Filter f = new Store.Filter();
        if (!rankGenre.isEmpty()) f.genres.add(rankGenre);
        f.styles.addAll(filter.styles);
        f.countries.addAll(filter.countries);
        if (board == BOARD_LUT && !rankCreator.isEmpty()) f.creator = rankCreator;

        // 本地查询实测 9ms —— 直接显示骨架会闪一下，所以只在慢到值得等的时候才露出来
        final Runnable showSkeleton = () -> {
            if (rankSkeleton != null) {
                // 列表和骨架都吃 weight=1，不藏起来就各占一半（实测骨架被挤到下半屏）
                rankList.setVisibility(View.GONE);
                rankSkeleton.setVisibility(View.VISIBLE);
            }
        };
        ui.postDelayed(showSkeleton, SKELETON_DELAY_MS);
        pool.execute(() -> {
            if (board == BOARD_CREATOR) {
                final List<Store.Creator> r = store.topCreators(50, 1, f);
                ui.post(() -> {
                    ui.removeCallbacks(showSkeleton);
                    if (rankSkeleton != null) rankSkeleton.setVisibility(View.GONE);
                    creatorData.clear();
                    creatorData.addAll(r);
                    creatorAdapter.notifyDataSetChanged();
                    rankEmpty.setText(getString(R.string.rank_empty_creator));
                    applyRankState(r.isEmpty());
                });
            } else {
                final List<Lut> r = store.rankingBy(200, f);
                ui.post(() -> {
                    ui.removeCallbacks(showSkeleton);
                    if (rankSkeleton != null) rankSkeleton.setVisibility(View.GONE);
                    rankData.clear();
                    rankData.addAll(r);
                    rankAdapter.notifyDataSetChanged();
                    rankEmpty.setText(getString(rankCreator.isEmpty()
                            ? R.string.rank_empty_lut : R.string.rank_empty_lut_by_creator));
                    applyRankState(r.isEmpty());
                });
            }
        });
    }

    private void applyRankState(boolean empty) {
        if (rankEmpty != null) rankEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (rankList != null) rankList.setVisibility(empty ? View.GONE : View.VISIBLE);
        refreshRankCondRow();
        refreshRankSummary(empty);
    }

    // ============================================================ 筛选 Tab

    private TextView filterSummary;
    /** 摘要下面那行小字：条件与「排行」共用（只在真的有条件时显示）。 */
    private TextView filterSharedHint;
    private LinearLayout filterEmpty;
    private ListView browseList;
    private LutCardAdapter browseAdapter;

    private View buildFilterTab() {
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(16), dp(12), dp(16), 0);

        LinearLayout head = Ui.row(this);
        TextView h = Ui.h1(this, getString(R.string.filter_title));
        head.addView(h, Ui.lpWeight(1f));
        // 「筛选条件 ▾」→ 漏斗图标 + 文字：漏斗比一个三角更能说明"点开是筛选面板"
        // 上下 15dp 是给触控留的（13sp 文字 + 30 = 48dp），视觉上看不出来
        LinearLayout btn = Ui.row(this);
        btn.setPadding(dp(10), dp(15), 0, dp(15));
        btn.addView(Ui.icon(this, R.drawable.ic_filter, 15f, Ui.ACCENT));
        TextView btnLabel = Ui.text(this, getString(R.string.filter_tab_cond), 13f, Ui.ACCENT, true);
        LinearLayout.LayoutParams blp = Ui.lpWrap();
        blp.leftMargin = dp(5);
        btn.addView(btnLabel, blp);
        btn.setContentDescription(getString(R.string.cd_filter));
        btn.setOnClickListener(v -> FilterSheet.show(this, store, filter, f -> {
            filter.keyword = f.keyword;
            filter.genres.clear();    filter.genres.addAll(f.genres);
            filter.styles.clear();    filter.styles.addAll(f.styles);
            filter.countries.clear(); filter.countries.addAll(f.countries);
            filter.sort = f.sort;
            runFilterQuery();
        }));
        head.addView(btn, Ui.lpWrap());
        col.addView(head);

        filterSummary = Ui.sub(this, "");
        filterSummary.setPadding(0, dp(8), 0, 4);
        col.addView(filterSummary);

        // 一句话说清"为什么这里已经有条件了"：它和「排行」页共用同一个 Filter
        filterSharedHint = Ui.text(this, getString(R.string.filter_sum_shared_hint),
                10f, Ui.TEXT_WEAK, false);
        filterSharedHint.setPadding(0, 0, 0, dp(8));
        filterSharedHint.setVisibility(View.GONE);
        col.addView(filterSharedHint);

        // 空状态：0 结果时必须给出明确提示，不能只是一片空白
        filterEmpty = Ui.col(this);
        filterEmpty.setVisibility(View.GONE);
        filterEmpty.setPadding(0, dp(30), 0, 0);
        TextView et = Ui.body(this, getString(R.string.rank_empty_lut));
        et.setGravity(Gravity.CENTER);
        filterEmpty.addView(et, Ui.lpMatch());
        TextView es = Ui.text(this, getString(R.string.filter_tab_empty_hint),
                11.5f, Ui.TEXT_SUB, false);
        es.setGravity(Gravity.CENTER);
        es.setPadding(0, dp(8), 0, dp(16));
        filterEmpty.addView(es, Ui.lpMatch());
        TextView er = Ui.ghostBtn(this, getString(R.string.filter_tab_clear));
        er.setOnClickListener(v -> {
            filter.genres.clear();
            filter.styles.clear();
            filter.countries.clear();
            filter.creator = "";
            runFilterQuery();
        });
        filterEmpty.addView(er, Ui.lpMatch());
        col.addView(filterEmpty);

        browseAdapter = new LutCardAdapter(this, shown, false);
        browseList = new ListView(this);
        browseList.setDivider(null);
        browseList.setAdapter(browseAdapter);
        browseList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < shown.size()) showDetail(shown.get(pos));
        });
        col.addView(browseList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        browseSkeleton = Ui.skeletonList(this, 7);
        col.addView(browseSkeleton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private void runFilterQuery() {
        if (browseAdapter == null) return;
        final Store.Filter f = filter;
        final Runnable showSkeleton = () -> {
            if (browseSkeleton != null) {
                browseList.setVisibility(View.GONE);
                browseSkeleton.setVisibility(View.VISIBLE);
            }
        };
        ui.postDelayed(showSkeleton, SKELETON_DELAY_MS);
        pool.execute(() -> {
            final List<Lut> r = store.query(f);
            ui.post(() -> {
                ui.removeCallbacks(showSkeleton);
                if (browseSkeleton != null) browseSkeleton.setVisibility(View.GONE);
                shown.clear();
                shown.addAll(r);
                browseAdapter.notifyDataSetChanged();

                boolean empty = r.isEmpty();
                if (filterEmpty != null) {
                    filterEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                }
                if (browseList != null) {
                    browseList.setVisibility(empty ? View.GONE : View.VISIBLE);
                }

                StringBuilder sb = new StringBuilder(getString(R.string.filter_sum_hit_fmt, r.size()));
                List<String> parts = new ArrayList<>();
                if (!f.genres.isEmpty()) {
                    parts.add(getString(R.string.filter_sum_genre_fmt, f.genres.size()));
                }
                if (!f.styles.isEmpty()) {
                    parts.add(getString(R.string.filter_sum_style_fmt, f.styles.size()));
                }
                if (!f.countries.isEmpty()) {
                    parts.add(getString(R.string.filter_sum_country_fmt, f.countries.size()));
                }
                if (!f.creator.isEmpty()) parts.add(getString(R.string.filter_sum_creator));
                if (!parts.isEmpty()) {
                    sb.append(getString(R.string.filter_sum_parts_fmt, String.join(" · ", parts)));
                }
                if (!f.keyword.isEmpty()) {
                    sb.append(getString(R.string.filter_sum_keyword_fmt, f.keyword));
                }
                filterSummary.setText(sb.toString());
                if (filterSharedHint != null) {
                    filterSharedHint.setVisibility(filter.activeCount() > 0
                            ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    // ============================================================ 我的

    private View buildMine() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(col);

        col.addView(Ui.h1(this, getString(R.string.mine_title)));

        col.addView(Ui.sectionTitle(this, getString(R.string.mine_section_content)));
        LinearLayout box = Ui.card(this);
        box.setPadding(dp(14), 0, dp(14), 0);
        addRow(box, getString(R.string.mine_downloads),
                getString(R.string.mine_downloads_sub_fmt, store.dlCount()),
                v -> startActivity(new Intent(this, DownloadsActivity.class)));
        box.addView(Ui.divider(this));
        addRow(box, getString(R.string.mine_settings),
                getString(R.string.mine_settings_sub),
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        col.addView(box);

        col.addView(Ui.sectionTitle(this, getString(R.string.mine_lib_section)));
        LinearLayout box2 = Ui.card(this);
        box2.setPadding(dp(14), dp(14), dp(14), dp(14));
        box2.addView(Ui.body(this, getString(R.string.mine_lib_state_fmt,
                store.count(), store.enrichedCount())));
        TextView sync = Ui.sub(this, getString(R.string.mine_sync_fmt,
                Prefs.lastSyncText(this)));
        sync.setPadding(0, dp(4), 0, 0);
        box2.addView(sync);
        TextView now = Ui.ghostBtn(this, getString(R.string.settings_sync_now));
        now.setOnClickListener(v -> manualSync());
        LinearLayout.LayoutParams np = Ui.lpMatch();
        np.topMargin = dp(12);
        box2.addView(now, np);
        col.addView(box2);

        col.addView(Ui.sectionTitle(this, getString(R.string.mine_about)));
        LinearLayout box3 = Ui.card(this);
        box3.setPadding(dp(14), dp(14), dp(14), dp(14));
        box3.addView(Ui.body(this, getString(R.string.app_name) + " " + appVersion()));
        TextView ab = Ui.text(this, getString(R.string.mine_about_body),
                11.5f, Ui.TEXT_SUB, false);
        ab.setPadding(0, dp(8), 0, 0);
        ab.setLineSpacing(dp(2), 1f);
        box3.addView(ab);
        col.addView(box3);

        return sv;
    }

    private void addRow(LinearLayout parent, String title, String sub, View.OnClickListener click) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, dp(14), 0, dp(14));
        LinearLayout mid = Ui.col(this);
        mid.addView(Ui.text(this, title, 14f, Ui.TEXT, true));
        TextView s = Ui.sub(this, sub);
        s.setPadding(0, dp(3), 0, 0);
        mid.addView(s);
        r.addView(mid, Ui.lpWeight(1f));
        r.addView(Ui.icon(this, R.drawable.ic_chevron_right, 18f, Ui.TEXT_WEAK), Ui.lpWrap());
        r.setOnClickListener(click);
        parent.addView(r, Ui.lpMatch());
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams p = Ui.lpWrap();
        p.rightMargin = dp(8);
        p.bottomMargin = dp(8);
        return p;
    }

    // ============================================================ 首次引导

    private void showFirstRun() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.firstrun_title))
                .setMessage(getString(R.string.firstrun_message))
                .setPositiveButton(getString(R.string.firstrun_full), (d, w) -> doPreload(true))
                .setNegativeButton(getString(R.string.firstrun_quick), (d, w) -> doPreload(false))
                .setCancelable(false)
                .show();
    }

    // ============================================================ 同步 / 补齐

    private void maybeAutoSync() {
        if (!Prefs.shouldAutoSync(this, false)) return;
        pool.execute(() -> {
            try {
                Api.Progress p = (attempt, waitMs, cause) -> { };
                int n = Syncer.syncCatalog(store, p, null);
                Prefs.markSynced(this);
                ui.post(() -> {
                    if (n > 0) toast(getString(R.string.sync_auto_done_fmt, n));
                    // 首页显示的数字要跟着更新
                    dropTab(TAB_DISCOVER);
                    if (tab == TAB_DISCOVER) showTab(TAB_DISCOVER);
                });
            } catch (Exception ignored) {
                // 自动更新静默失败，不打断用户
            }
        });
    }

    private void manualSync() {
        final ProgressDialog pd = busy(getString(R.string.sync_catalog_busy),
                getString(R.string.sync_fetching));
        pool.execute(() -> {
            try {
                Api.Progress p = mkProgress(pd);
                final int n = Syncer.syncCatalog(store, p, null);
                Prefs.markSynced(this);
                // 语言换过（例：英文 → 中文）时，顺手把详情也补一遍，否则简介还是旧语言。
                // 只在"语言变了"时做，平时点一次仍只是刷目录。
                final boolean langChanged = !Api.lang().equals(Prefs.detailLang(this));
                if (langChanged) {
                    java.util.List<String> todo = store.allCodes();
                    for (int i = 0; i < todo.size(); i++) {
                        final int idx = i;
                        final int total = todo.size();
                        if (i % 5 == 0) {
                            ui.post(() -> pd.setMessage(getString(R.string.preload_enrich_step_fmt, idx + 1, total)));
                        }
                        try {
                            Lut d = Api.detail(todo.get(i), Api.lang(), p);
                            if (d != null && !d.code.isEmpty()) store.upsertDetail(d);
                        } catch (Exception ignored) { }
                    }
                    Prefs.setDetailLang(this, Api.lang());
                }
                ui.post(() -> {
                    pd.dismiss();
                    toast(getString(R.string.sync_done_fmt, n));
                    dropTab(TAB_DISCOVER);
                    dropTab(TAB_MINE);
                    showTab(tab);
                });
            } catch (Exception e) {
                ui.post(() -> { pd.dismiss();
                    toast(getString(R.string.sync_failed_fmt, String.valueOf(e.getMessage()))); });
            }
        });
    }

    private void confirmPreload() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.preload_title))
                .setMessage(getString(R.string.preload_message_fmt,
                        store.enrichedCount(), store.count()))
                .setPositiveButton(getString(R.string.preload_start), (d, w) -> doPreload(true))
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    private void doPreload(final boolean full) {
        final ProgressDialog pd = busy(getString(full
                ? R.string.preload_running : R.string.sync_catalog_running),
                getString(R.string.busy_preparing));
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_PRELOADED, true).apply();

        pool.execute(() -> {
            try {
                Api.Progress p = mkProgress(pd);
                ui.post(() -> pd.setMessage(getString(R.string.preload_fetch_catalog)));
                final int got = Syncer.syncCatalog(store, p, null);
                Prefs.markSynced(this);

                int done = 0;
                final boolean langChanged = !Api.lang().equals(Prefs.detailLang(this));
                if (full || langChanged) {
                    List<String> todo = store.pendingEnrich(Integer.MAX_VALUE);
                    // 语言一变（例：英文 → 中文），已缓存的简介还是旧语言；pendingEnrich 看不出这点，
                    // 所以直接把全部 code 排进待办，抓完再把语言记下来。
                    if (langChanged) {
                        todo = store.allCodes();
                    }
                    final int total = todo.size();
                    for (int i = 0; i < total; i++) {
                        final int idx = i;
                        if (i % 5 == 0) {
                            ui.post(() -> {
                                pd.setMessage(getString(R.string.preload_enrich_step_fmt, idx + 1, total));
                                setBar(idx, total);
                            });
                        }
                        try {
                            Lut d = Api.detail(todo.get(i), Api.lang(), p);
                            if (d != null && !d.code.isEmpty()) store.upsertDetail(d);
                            done++;
                        } catch (Exception ignored) { }
                    }
                }

                final int fDone = done;
                Prefs.setDetailLang(this, Api.lang());
                ui.post(() -> {
                    pd.dismiss();
                    hideBar();
                    dropTab(TAB_DISCOVER);
                    dropTab(TAB_RANK);
                    dropTab(TAB_FILTER);
                    dropTab(TAB_MINE);
                    showTab(tab);
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.done_title))
                            .setMessage(getString(R.string.done_message_fmt,
                                    got, fDone, store.count()))
                            .setPositiveButton(getString(R.string.common_ok), null).show();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pd.dismiss();
                    hideBar();
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.error_title))
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton(getString(R.string.common_ok), null).show();
                });
            }
        });
    }

    private void confirmEnrich() {
        int pending = store.pendingEnrichCount();
        if (pending == 0) { toast(getString(R.string.enrich_all_done)); return; }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.enrich_title))
                .setMessage(getString(R.string.enrich_message_fmt,
                        pending, pending * 8 / 10 / 60 + 1))
                .setPositiveButton(getString(R.string.enrich_first_60), (d, w) -> doEnrich(60))
                .setNeutralButton(getString(R.string.enrich_all),
                        (d, w) -> doEnrich(Integer.MAX_VALUE))
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    private void doEnrich(int max) {
        final ProgressDialog pd = busy(getString(R.string.enrich_running),
                getString(R.string.busy_preparing));
        pool.execute(() -> {
            List<String> todo = store.pendingEnrich(max);
            int n = todo.size(), ok = 0;
            try {
                Api.Progress p = mkProgress(pd);
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    if (i % 5 == 0) {
                        ui.post(() -> {
                            pd.setMessage(getString(R.string.enrich_step_fmt, idx + 1, n));
                            setBar(idx, n);
                        });
                    }
                    try {
                        Lut d = Api.detail(todo.get(i), Api.lang(), p);
                        if (d != null && !d.code.isEmpty()) { store.upsertDetail(d); ok++; }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            final int fOk = ok;
            ui.post(() -> {
                pd.dismiss();
                hideBar();
                toast(getString(R.string.enrich_done_fmt, fOk));
                dropTab(TAB_DISCOVER);
                dropTab(TAB_FILTER);
                dropTab(TAB_MINE);
                showTab(tab);
            });
        });
    }

    private void showStyles() {
        Map<String, int[]> m = store.styleStats();
        if (m.isEmpty()) { toast(getString(R.string.styles_empty)); return; }
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.styles_dist_head_fmt, store.enrichedCount()));
        for (Map.Entry<String, int[]> e : m.entrySet()) {
            sb.append(String.format(getString(R.string.styles_dist_row_fmt),
                    e.getKey(), e.getValue()[0], Ui.num(e.getValue()[1])));
        }
        sb.append(getString(R.string.styles_dist_note));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.styles_dist_title)).setMessage(sb.toString())
                .setPositiveButton(getString(R.string.common_ok), null).show();
    }

    // ============================================================ 详情 / 下载

    /**
     * 详情弹层。
     *
     * 形态：全屏透明 Dialog，两层 —— 自绘遮罩（点它关闭）/ 底部面板。
     *
     * 动效：遮罩 300ms 淡入，面板**同时**从屏幕下方滑入。对比图本身就是面板的一部分，
     * 所以"图片张开"和"面板滑入"天然是同一条曲线、同一个起点，不会再出现两段动画各走各的。
     * 面板滑上来时对比图先用卡片已经加载过的那张顶上（不再有空白等待），
     * 等官方原图就位后由 loadSamplePair 换成可拖动的左右对比。
     */
    private void showDetail(Lut l) {
        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final FrameLayout root = new FrameLayout(this);

        // 1) 自绘遮罩：比系统 dim 更可控（跟面板一起动）
        final View scrim = new View(this);
        scrim.setBackgroundColor(getColor(R.color.scrim));
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> dlg.dismiss());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2) 底部面板：内容和图片都在里面，一起滑
        final LinearLayout sheet = Ui.col(this);
        sheet.setBackground(Ui.roundTop(Ui.CARD, 24f, this));

        View handle = new View(this);
        handle.setBackground(Ui.round(Ui.LINE, 2f, this, null));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(10);
        hlp.bottomMargin = dp(4);
        sheet.addView(handle, hlp);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(20), 0, dp(20), dp(8));
        sv.addView(col);
        final LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheet.addView(sv, svLp);

        LinearLayout head = Ui.row(this);
        TextView title = Ui.h1(this, l.line1());
        head.addView(title, Ui.lpWeight(1f));
        // 关闭：图标 18dp，但触控区域 48dp；原来那个「✕」字符只有 ~30×24dp，拇指很难点中
        View close = Ui.iconButton(this, R.drawable.ic_close, 18f, Ui.TEXT_SUB,
                Ui.ICON_TOUCH, getString(R.string.cd_close), dlg::dismiss);
        head.addView(close, Ui.lpWrap());
        col.addView(head, Ui.lpMatch());

        String who = l.creatorName.isEmpty() ? l.creatorCode : l.creatorName;
        LinearLayout by = Ui.row(this);
        by.addView(Ui.sub(this, who), Ui.lpWrap());
        by.addView(Ui.text(this, "　·　", 11.5f, Ui.TEXT_WEAK, false), Ui.lpWrap());
        by.addView(Ui.stat(this, R.drawable.ic_download,
                Ui.compact(l.downloads), 11.5f, Ui.TEXT_SUB, false), Ui.lpWrap());
        by.setPadding(0, dp(2), 0, dp(12));
        col.addView(by, Ui.lpMatch());

        final TextView hint = Ui.sub(this, "");
        hint.setPadding(0, dp(6), 0, dp(12));
        boolean hasPair = !l.thumbBefore.isEmpty() && !l.thumbAfter.isEmpty();
        String afterPath = l.thumbAfter.isEmpty() ? l.thumbPath : l.thumbAfter;
        boolean hasAny = hasPair || !afterPath.isEmpty();

        if (hasAny) {
            final CompareView cmp = new CompareView(this);
            // 先用卡片已缓存的那张顶上 —— 面板滑上来时图就在，不是空白
            Bitmap cached = afterPath.isEmpty() ? null : ImageLoader.get().peek(afterPath);
            if (cached != null) cmp.setBitmaps(null, cached);
            // 把手跟着第一张图一起出现：原图在加载就画待命态，官方没有原图就收起
            if (l.thumbBefore.isEmpty()) cmp.setNoBefore(true);
            else cmp.setPendingBefore(true);
            col.addView(cmp, Ui.lpMatch());
            col.addView(hint, Ui.lpMatch());
            if (hasPair) {
                loadSamplePair(cmp, hint, l);
            } else {
                if (cmp != null) cmp.setNoBefore(true);
                hint.setText(getString(R.string.detail_no_before_hint));
            }
        } else {
            hint.setText(l.enriched
                    ? getString(R.string.detail_no_sample)
                    : getString(R.string.detail_no_sample_hint));
            col.addView(hint, Ui.lpMatch());
        }

        // 标签行用 WrapLayout：放不下换行，而不是溢出到弹层外面被裁
        WrapLayout meta = new WrapLayout(this).gaps(8, 8, this);
        meta.setPadding(0, dp(4), 0, dp(4));
        meta.addView(metaChip(l.genreLabel()), metaChipLp());
        if (!l.photoStyle.isEmpty()) meta.addView(metaChip(l.photoStyle), metaChipLp());
        if (!l.countryCode.isEmpty()) meta.addView(metaChip(l.countryCode), metaChipLp());
        col.addView(meta);

        if (!l.overview.isEmpty()) {
            TextView ov = Ui.body(this, l.overview);
            ov.setLineSpacing(dp(3), 1f);
            ov.setPadding(0, dp(8), 0, 0);
            col.addView(ov);
        }

        LinearLayout dl = Ui.primaryIconBtn(this, R.drawable.ic_download, getString(R.string.detail_download_btn));
        LinearLayout.LayoutParams blp = Ui.lpMatch();
        blp.setMargins(dp(20), dp(6), dp(20), dp(18));
        sheet.addView(dl, blp);
        // 主按钮补上按压反馈（原来只有水波纹，没有缩放；和卡片的手感统一）
        Ui.pressable(dl, () -> { Ui.tap(dl); dlg.dismiss(); doDownload(l); });

        // 面板高度：短内容贴内容，超过屏高 86% 才钉上限并内部滚动
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        final int maxH = (int) (screenH * 0.86);
        sheet.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        final int desired = sheet.getMeasuredHeight();
        final int sheetH;
        final int sheetLpHeight;
        if (desired >= maxH) {
            // 内容超了：ScrollView 收成 weight=1，底部按钮固定贴底
            svLp.height = 0;
            svLp.weight = 1f;
            // 【必须重测】第一轮量的时候 ScrollView 还是"按内容撑开"，底部按钮拿到的
            // 高度 spec 已经被挤成 AT_MOST(0)，量出来是压扁的；而 LinearLayout 只重测
            // 带 weight 的子节点，按钮不会自己回来。第二次按 EXACTLY(maxH) 量才对。
            sheet.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.EXACTLY));
            sheetH = maxH;
            sheetLpHeight = maxH;
        } else {
            sheetH = desired;
            // 【高度用 WRAP，不钉死】内容在弹出后还会长：样张加载完提示行会从
            // "样张加载中… 0/2" 换成两句换行的说明，大字体下一换行就高出 50px。
            // 一旦把面板高度钉死，多出来的高度只能从最后一个子节点（底部按钮）身上扣，
            // 「下载 .cube」会被压成一条细线。交给 WRAP，面板自己向上长，按钮不受影响。
            sheetLpHeight = ViewGroup.LayoutParams.WRAP_CONTENT;
        }

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sheetLpHeight);
        slp.gravity = Gravity.BOTTOM;
        root.addView(sheet, slp);

        // 起跑前先落到屏幕外，避免第一帧闪一下终态
        sheet.setTranslationY(sheetH);

        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(false);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            WindowManager.LayoutParams wlp = w.getAttributes();
            wlp.gravity = Gravity.BOTTOM;
            w.setAttributes(wlp);
        }
        dlg.show();

        // 等第一帧真正布局完成后统一开跑：遮罩与面板同一时长、同一曲线
        root.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        android.view.ViewTreeObserver v = root.getViewTreeObserver();
                        if (v.isAlive()) v.removeOnPreDrawListener(this);
                        android.view.animation.Interpolator dec =
                                new android.view.animation.DecelerateInterpolator();
                        scrim.animate().alpha(1f).setDuration(300).setInterpolator(dec).start();
                        sheet.animate().translationY(0f).setDuration(300).setInterpolator(dec).start();
                        return true;
                    }
                });
    }
    /** 详情标签行的子项布局：间距由 WrapLayout 统一给，这里不加 margin。 */
    private ViewGroup.LayoutParams metaChipLp() {
        return new WrapLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 详情里的小属性标签。 */
    private TextView metaChip(String s) {
        TextView t = Ui.chip(this, s, false);
        t.setTextSize(11.5f);
        return t;
    }

    private String detailText(Lut l) {
        StringBuilder m = new StringBuilder();
        m.append(getString(R.string.detail_label_creator)).append(l.creatorName).append("（").append(l.creatorCode).append("）\n");
        m.append(getString(R.string.detail_label_genre)).append(l.genreLabel()).append("（").append(l.genreCode).append("）\n");
        m.append(getString(R.string.detail_label_style))
                .append(l.photoStyle.isEmpty() ? getString(R.string.detail_style_missing) : l.photoStyle)
                .append("\n");
        m.append(getString(R.string.detail_label_country)).append(l.countryCode.isEmpty() ? "—" : l.countryCode).append("\n");
        m.append(getString(R.string.detail_label_downloads)).append(Ui.num(l.downloads)).append("\n");
        m.append("code：").append(l.code).append("\n");
        if (!l.overview.isEmpty()) {
            m.append(getString(R.string.detail_overview_prefix)).append(l.overview.length() > 400
                    ? l.overview.substring(0, 400) + "…" : l.overview);
        }
        m.append(getString(R.string.detail_foot));
        return m.toString();
    }

    /**
     * 加载详情里的两张样张（原图 + 效果图）。
     *
     * 与以前三点不同：
     *  1. 走 ImageLoader.loadDetail —— 独立线程池，不排在网格几十张缩略图后面；
     *  2. 先到先显示：效果图先到就先画（把手一起出现），原图到了再补左半边；
     *  3. 失败可点重试，而不是只留一句"检查网络后重开此页"。
     */
    private void loadSamplePair(final CompareView cmp, final TextView hint, final Lut l) {
        hint.setClickable(false);
        hint.setOnClickListener(null);

        final Bitmap[] beforeB = new Bitmap[1];
        final Bitmap[] afterB = new Bitmap[1];
        final boolean[] beforeDone = new boolean[1];
        final boolean[] afterDone = new boolean[1];
        final boolean[] flushed = new boolean[1];

        Bitmap cb = ImageLoader.get().peek(l.thumbBefore);
        Bitmap ca = ImageLoader.get().peek(l.thumbAfter);
        if (cb != null) { beforeB[0] = cb; beforeDone[0] = true; }
        if (ca != null) { afterB[0] = ca; afterDone[0] = true; }
        if (ca != null) cmp.setBitmaps(cb, ca);   // 有就先画，别等

        hint.setText(getString(R.string.detail_samples_loading_fmt,
                (beforeDone[0] ? 1 : 0) + (afterDone[0] ? 1 : 0)));

        Runnable flush = new Runnable() {
            @Override public void run() {
                if (flushed[0]) return;

                // 先到先用：一张到位就先画一张，把手跟着出现，不让人干等
                if (beforeDone[0]) cmp.setPendingBefore(false);
                if (afterB[0] != null) cmp.setBitmaps(beforeB[0], afterB[0]);
                else if (beforeB[0] != null) cmp.setBitmaps(beforeB[0], null);

                if (!beforeDone[0] || !afterDone[0]) {
                    hint.setText(getString(R.string.detail_samples_loading_fmt,
                            (beforeDone[0] ? 1 : 0) + (afterDone[0] ? 1 : 0)));
                    return;
                }

                flushed[0] = true;
                if (beforeB[0] != null && afterB[0] != null) {
                    hint.setText(getString(R.string.detail_samples_ready));
                } else if (afterB[0] != null) {
                    hint.setText(getString(R.string.detail_samples_after_only));
                } else {
                    hint.setText(getString(R.string.detail_samples_failed));
                    hint.setTextColor(Ui.ACCENT);
                    hint.setClickable(true);
                    hint.setOnClickListener(v -> {
                        hint.setTextColor(Ui.TEXT_SUB);
                        loadSamplePair(cmp, hint, l);
                    });
                }
            }
        };

        ImageLoader.get().loadDetail(l.thumbBefore, new ImageLoader.Callback() {
            @Override public void onLoaded(Bitmap b) { beforeB[0] = b; beforeDone[0] = true; flush.run(); }
            @Override public void onFailed() { beforeDone[0] = true; flush.run(); }
        });
        ImageLoader.get().loadDetail(l.thumbAfter, new ImageLoader.Callback() {
            @Override public void onLoaded(Bitmap b) { afterB[0] = b; afterDone[0] = true; flush.run(); }
            @Override public void onFailed() { afterDone[0] = true; flush.run(); }
        });
    }

    private void doDownload(Lut l) {
        ProgressDialog pd = busy(getString(R.string.dl_busy), l.line1());
        pool.execute(() -> {
            try {
                Api.Progress p = mkProgress(pd);
                Downloader.Result r = Downloader.fetchAndSave(this, l.code, "grid33", p);
                store.recordDownload(l, r.cubeName, r.bytes,
                        r.uri == null ? "" : r.uri.toString(), r.displayPath);
                ui.post(() -> { pd.dismiss(); afterDownload(l, r); });
            } catch (Exception e) {
                ui.post(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.dl_failed))
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton(getString(R.string.common_ok), null).show();
                });
            }
        });
    }

    private void afterDownload(Lut l, Downloader.Result r) {
        boolean hasLumix = Downloader.isLumixInstalled(this);
        String msg = getString(R.string.dl_done_message_fmt,
                r.cubeName, r.displayPath, Ui.num(r.bytes), l.code, r.cubeName);

        AlertDialog.Builder ab = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dl_done_title))
                .setMessage(msg)
                .setPositiveButton(getString(hasLumix
                        ? R.string.dl_open_lumix : R.string.common_ok), null)
                .setNeutralButton(getString(R.string.dl_open_other), null)
                .setNegativeButton(getString(R.string.common_close), null);
        AlertDialog d = ab.create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (hasLumix && !Downloader.launchLumix(this)) toast(getString(R.string.dl_lumix_missing));
                d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (!Downloader.tryOpenWith(this, r.uri, "application/octet-stream")) {
                    toast(getString(R.string.dl_no_app));
                }
                d.dismiss();
            });
        });
        d.show();
    }

    // ============================================================ 小工具

    private ProgressDialog busy(String title, String msg) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(title);
        pd.setMessage(msg);
        pd.setCancelable(false);
        pd.show();
        return pd;
    }

    private Api.Progress mkProgress(final ProgressDialog pd) {
        return (attempt, waitMs, cause) -> ui.post(() ->
                pd.setMessage(getString(R.string.sync_throttled_fmt,
                        attempt, waitMs / 1000)));
    }

    private void setBar(int done, int total) {
        bar.setVisibility(View.VISIBLE);
        bar.setMax(Math.max(1, total));
        bar.setProgress(done);
        barText.setVisibility(View.VISIBLE);
        barText.setText(getString(R.string.sync_progress_fmt, done, total));
    }

    private void hideBar() {
        bar.setVisibility(View.GONE);
        barText.setVisibility(View.GONE);
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_LONG);
        t.setGravity(Gravity.BOTTOM, 0, dp(90));
        t.show();
    }

    private String appVersion() {
        try {
            return "v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "v?";
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SEARCH && res == RESULT_OK && data != null) {
            final String code = data.getStringExtra(SearchActivity.EXTRA_CODE);
            if (code == null || code.isEmpty()) return;
            // 搜索页只回传 code，详情数据在这里按 code 取 —— 详情弹窗只有这一份实现
            pool.execute(() -> {
                Store.Filter f = new Store.Filter();
                f.limit = 500;
                final Lut found = store.byCode(code);
                ui.post(() -> {
                    if (found != null) showDetail(found);
                    else toast(getString(R.string.dl_not_in_lib));
                });
            });
        }
    }

    /**
     * 返回键：排行 / 筛选 / 我的 先回「发现」，再按一次才退出到桌面。
     *
     * 为什么不担心弹层：详情弹层和筛选弹层都是 Dialog，打开时返回键先被它们消费
     * （关弹层），根本走不到这里 —— 验收里有专门一条手工用例盯这个。
     *
     * 切回发现页**不重置**其它 Tab 的状态：`showTab` 复用缓存的 View，
     * 所以再点回排行，上次的筛选条件和滚动位置都还在。"返回"是回首页看看，
     * 不是"撤销我刚才做的事"。
     */
    @Override
    public void onBackPressed() {
        if (tab != TAB_DISCOVER) {
            showTab(TAB_DISCOVER);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「我的下载」返回时数字可能变了
        if (tab == TAB_MINE && tabViews.get(TAB_MINE) != null) {
            dropTab(TAB_MINE);
            showTab(TAB_MINE);
        }
    }
}
