package io.github.lumixlut.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private int tab = TAB_DISCOVER;

    private ProgressBar bar;
    private TextView barText;

    /** 当前筛选条件（多选）。 */
    private final Store.Filter filter = new Store.Filter();
    private final List<Lut> shown = new ArrayList<>();

    /** 首页「按格调浏览」是否展开。收起时只显示前 6 个。 */
    private boolean stylesExpanded = true;

    public MainActivity() {}

    // ============================================================ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

        String[] names = {"发现", "排行", "筛选", "我的"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(6), 0, dp(4));

            TextView dot = Ui.text(this, "●", 13f, Ui.TEXT_WEAK, false);
            dot.setGravity(Gravity.CENTER);
            cell.addView(dot);

            TextView lb = Ui.text(this, names[i], 11.5f, Ui.TEXT_SUB, false);
            lb.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = Ui.lpMatch();
            lp.topMargin = dp(2);
            cell.addView(lb, lp);

            tabLabels.add(lb);
            cell.setOnClickListener(v -> showTab(idx));
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

        for (int i = 0; i < tabLabels.size(); i++) {
            boolean on = (i == idx);
            tabLabels.get(i).setTextColor(on ? Ui.ACCENT : Ui.TEXT_SUB);
            tabLabels.get(i).setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
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
        search.setPadding(dp(14), dp(11), dp(14), dp(11));
        TextView icon = Ui.text(this, "🔍", 14f, Ui.TEXT_SUB, false);
        search.addView(icon, Ui.lpWrap());
        TextView hint = Ui.text(this, "搜标题 / 创作者 / 简介", 13.5f, Ui.TEXT_SUB, false);
        LinearLayout.LayoutParams hp = Ui.lpWeight(1f);
        hp.leftMargin = dp(8);
        search.addView(hint, hp);
        search.setOnClickListener(v ->
                startActivityForResult(new Intent(this, SearchActivity.class), REQ_SEARCH));
        col.addView(search, Ui.lpMatch());

        // 快捷 chips（实打实有数据的两个题材）
        LinearLayout quick = Ui.row(this);
        quick.setPadding(0, dp(12), 0, 0);
        int nm = store.countGenre("movie"), ns = store.countGenre("still");
        TextView c1 = Ui.chip(this, "视频 " + nm, false);
        c1.setOnClickListener(v -> {
            Store.Filter f = new Store.Filter();
            f.genres.add("movie");
            gotoFilterWith(f);
        });
        TextView c2 = Ui.chip(this, "照片 " + ns, false);
        c2.setOnClickListener(v -> {
            Store.Filter f = new Store.Filter();
            f.genres.add("still");
            gotoFilterWith(f);
        });
        TextView c3 = Ui.chip(this, "全部 " + store.count(), false);
        c3.setOnClickListener(v -> gotoFilterWith(new Store.Filter()));
        quick.addView(c1, chipLp());
        quick.addView(c2, chipLp());
        quick.addView(c3, chipLp());
        col.addView(quick);

        // ---- 热门推荐（下载量前 8）· 两列竖排 ----
        col.addView(Ui.sectionTitle(this, "热门推荐"));
        List<Lut> hot = store.ranking(8, null);
        if (hot.isEmpty()) {
            col.addView(Ui.sub(this, "本地库是空的 —— 先同步目录。"));
        } else {
            // 按屏幕宽算格子宽，缩略图按样张原始比例 3:2（1080x720）定高
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int gap = dp(10);
            int cellW = (screenW - dp(32) - gap) / 2;
            int thumbH = cellW * 2 / 3;

            LinearLayout grid = Ui.col(this);
            LinearLayout rowView = null;
            for (int i = 0; i < hot.size(); i++) {
                if (i % 2 == 0) {
                    rowView = Ui.row(this);
                    rowView.setGravity(Gravity.TOP);
                    grid.addView(rowView, Ui.lpMatch());
                }
                LinearLayout cell = hotCell(hot.get(i), cellW, thumbH);
                LinearLayout.LayoutParams cp = Ui.lpWeight(1f);
                cp.setMargins(i % 2 == 0 ? 0 : gap / 2, 0, i % 2 == 0 ? gap / 2 : 0, dp(10));
                rowView.addView(cell, cp);
            }
            // 奇数个时补一个空位，保证左边那张不会被拉宽
            if (hot.size() % 2 == 1 && rowView != null) {
                View pad = new View(this);
                LinearLayout.LayoutParams lp = Ui.lpWeight(1f);
                lp.leftMargin = gap / 2;
                rowView.addView(pad, lp);
            }
            col.addView(grid);
        }

        // ---- 格调分类宫格（可收起）----
        Map<String, int[]> styles = store.styleStats();
        final boolean hasStyles = !styles.isEmpty();

        LinearLayout styleHead = Ui.row(this);
        styleHead.addView(Ui.sectionTitle(this, "按格调浏览"), Ui.lpWeight(1f));

        if (!hasStyles) {
            col.addView(styleHead);
            col.addView(Ui.sub(this, "还没有格调数据 —— 点下面「补齐格调」后这里会出现分类。"));
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
                    "共 " + total + " 个格调，点「展开」看全部", 11f, Ui.TEXT_WEAK, false);
            styleHint.setGravity(Gravity.CENTER);
            styleHint.setPadding(0, dp(8), 0, 0);

            final TextView toggle = Ui.text(this,
                    stylesExpanded ? "收起 ▲" : "展开 ▼", 12.5f, Ui.ACCENT, true);
            toggle.setPadding(dp(8), dp(18), 0, dp(10));
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
                toggle.setText(stylesExpanded ? "收起 ▲" : "展开 ▼");
            };
            apply.run();
            toggle.setOnClickListener(v -> {
                stylesExpanded = !stylesExpanded;
                apply.run();      // 原地切换，不重建、不跳滚动位置
            });
        }

        // ---- 创作者榜（派生数据，界面里标注）----
        col.addView(Ui.sectionTitle(this, "创作者（按总下载量）"));
        TextView note = Ui.sub(this, "由每枚 LUT 的下载量求和得到，非服务端独立字段。");
        note.setPadding(0, 0, 0, dp(8));
        col.addView(note);

        List<Store.Creator> ks = store.topCreators(10, 1);
        if (ks.isEmpty()) {
            col.addView(Ui.sub(this, "暂无数据。"));
        } else {
            LinearLayout box = Ui.card(this);
            box.setPadding(dp(14), dp(4), dp(14), dp(4));
            for (int i = 0; i < ks.size(); i++) {
                final Store.Creator k = ks.get(i);
                LinearLayout r = Ui.row(this);
                r.setPadding(0, dp(10), 0, dp(10));

                TextView rk = Ui.rank(this, i + 1);
                r.addView(rk, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout mid = Ui.col(this);
                mid.addView(Ui.text(this, k.name.isEmpty() ? k.code : k.name, 13.5f, Ui.TEXT, true));
                mid.addView(Ui.text(this, k.count + " 枚", 11f, Ui.TEXT_SUB, false));
                LinearLayout.LayoutParams mp = Ui.lpWeight(1f);
                mp.leftMargin = dp(10);
                r.addView(mid, mp);

                TextView dl = Ui.text(this, "↓ " + Ui.compact(k.downloads), 12f, Ui.ACCENT, true);
                r.addView(dl, Ui.lpWrap());

                r.setOnClickListener(v -> {
                    Store.Filter f = new Store.Filter();
                    f.creator = k.code;
                    gotoFilterWith(f);
                });
                box.addView(r, Ui.lpMatch());
                if (i < ks.size() - 1) box.addView(Ui.divider(this), Ui.lpMatch());
            }
            col.addView(box);
        }

        // ---- 维护入口 ----
        col.addView(Ui.sectionTitle(this, "本地库"));
        LinearLayout ops = Ui.row(this);
        TextView bPre = Ui.ghostBtn(this, "预载全部");
        bPre.setOnClickListener(v -> confirmPreload());
        TextView bEnr = Ui.ghostBtn(this, "补齐格调");
        bEnr.setOnClickListener(v -> confirmEnrich());
        TextView bSty = Ui.ghostBtn(this, "格调分布");
        bSty.setOnClickListener(v -> showStyles());
        ops.addView(bPre, Ui.lpWeight(1f));
        LinearLayout.LayoutParams o1 = Ui.lpWeight(1f); o1.leftMargin = dp(8);
        ops.addView(bEnr, o1);
        LinearLayout.LayoutParams o2 = Ui.lpWeight(1f); o2.leftMargin = dp(8);
        ops.addView(bSty, o2);
        col.addView(ops);

        TextView foot = Ui.text(this, "", 10f, Ui.TEXT_WEAK, false);
        foot.setText("LUT 搜索器 " + appVersion()
                + "　数据来源：LUMIX Lab 官方接口。本工具非官方、与 Panasonic 无关联。\n"
                + "服务端目录实测共 199 条，本地库即全量。搜索与筛选全部在本地 SQL 完成。");
        foot.setPadding(0, dp(16), 0, 0);
        col.addView(foot);

        return sv;
    }

    /**
     * 首页热门推荐的一格（两列竖排用）。
     *
     * 尺寸由调用方按屏幕宽算好传进来：宽度用 weight 自适应，
     * 高度按样张原始比例 3:2 由 cellW 推出，所以换屏也不会变形。
     */
    private LinearLayout hotCell(final Lut l, int cellW, int thumbH) {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, this, Ui.LINE));
        card.setPadding(dp(8), dp(8), dp(8), dp(10));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.CHIP_BG, 8f, this, null));
        card.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, thumbH));

        final String path = l.thumbAfter.isEmpty() ? l.thumbPath : l.thumbAfter;
        if (!path.isEmpty()) {
            Bitmap hit = ImageLoader.get().peek(path);
            if (hit != null) {
                iv.setImageBitmap(hit);
            } else {
                iv.setTag(path);
                ImageLoader.get().load(path, new ImageLoader.Callback() {
                    @Override public void onLoaded(Bitmap b) {
                        if (b != null && path.equals(iv.getTag())) iv.setImageBitmap(b);
                    }
                    @Override public void onFailed() { }
                });
            }
        }

        TextView t = Ui.text(this, l.title.isEmpty() ? l.code : l.title, 12.5f, Ui.TEXT, true);
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

        TextView d = Ui.text(this, "↓ " + Ui.compact(l.downloads), 10.5f, Ui.ACCENT, true);
        card.addView(d, Ui.lpMatch());

        card.setOnClickListener(v -> showDetail(l));
        return card;
    }

    /** 格调总数（"?" 是没补齐的占位，不算）。 */
    private int countStyles(Map<String, int[]> m) {
        int n = 0;
        for (String k : m.keySet()) if (!"?".equals(k)) n++;
        return n;
    }

    // ============================================================ 排行

    private ListView rankList;
    private LutCardAdapter rankAdapter;
    private TextView rankEmpty;
    private final List<Lut> rankData = new ArrayList<>();
    private String rankGenre = "";

    private View buildRank() {
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(16), dp(12), dp(16), 0);

        TextView h = Ui.h1(this, "下载榜");
        col.addView(h);

        LinearLayout toggles = Ui.row(this);
        toggles.setPadding(0, dp(10), 0, dp(8));
        final String[][] opts = {{"", "全部"}, {"movie", "视频"}, {"still", "照片"}};
        final List<TextView> chips = new ArrayList<>();
        for (final String[] o : opts) {
            final TextView c = Ui.chip(this, o[1], o[0].equals(rankGenre));
            c.setOnClickListener(v -> {
                rankGenre = o[0];
                for (int i = 0; i < chips.size(); i++) {
                    Ui.applyChip(chips.get(i), this, opts[i][0].equals(rankGenre));
                }
                loadRank();
            });
            chips.add(c);
            toggles.addView(c, chipLp());
        }
        col.addView(toggles);

        rankAdapter = new LutCardAdapter(this, rankData, true);
        rankList = new ListView(this);
        rankList.setDivider(null);
        rankList.setAdapter(rankAdapter);
        rankList.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < rankData.size()) showDetail(rankData.get(pos));
        });
        col.addView(rankList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 空状态：这个题材一条都没有时给提示，而不是留一片空白
        rankEmpty = Ui.text(this, "这个题材下还没有数据", 13f, Ui.TEXT_SUB, false);
        rankEmpty.setGravity(Gravity.CENTER);
        rankEmpty.setPadding(0, dp(40), 0, 0);
        rankEmpty.setVisibility(View.GONE);
        col.addView(rankEmpty, Ui.lpMatch());
        return col;
    }

    private void loadRank() {
        if (rankAdapter == null) return;
        final String g = rankGenre;
        pool.execute(() -> {
            final List<Lut> r = store.ranking(100, g);
            ui.post(() -> {
                rankData.clear();
                rankData.addAll(r);
                rankAdapter.notifyDataSetChanged();
                boolean empty = r.isEmpty();
                if (rankEmpty != null) rankEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                if (rankList != null) rankList.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ============================================================ 筛选 Tab

    private TextView filterSummary;
    private LinearLayout filterEmpty;
    private ListView browseList;
    private LutCardAdapter browseAdapter;

    private View buildFilterTab() {
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(16), dp(12), dp(16), 0);

        LinearLayout head = Ui.row(this);
        TextView h = Ui.h1(this, "筛选");
        head.addView(h, Ui.lpWeight(1f));
        TextView btn = Ui.text(this, "筛选条件 ▾", 13f, Ui.ACCENT, true);
        btn.setPadding(dp(10), dp(6), 0, dp(6));
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
        filterSummary.setPadding(0, dp(8), 0, dp(8));
        col.addView(filterSummary);

        // 空状态：0 结果时必须给出明确提示，不能只是一片空白
        filterEmpty = Ui.col(this);
        filterEmpty.setVisibility(View.GONE);
        filterEmpty.setPadding(0, dp(30), 0, 0);
        TextView et = Ui.body(this, "没有符合条件的 LUT");
        et.setGravity(Gravity.CENTER);
        filterEmpty.addView(et, Ui.lpMatch());
        TextView es = Ui.text(this,
                "格调与题材是互斥的（VLOG 只属于视频，其余格调都属于照片），\n"
              + "所以「照片 + VLOG」这类组合本来就是空的。\n"
              + "减少一个条件试试。", 11.5f, Ui.TEXT_SUB, false);
        es.setGravity(Gravity.CENTER);
        es.setPadding(0, dp(8), 0, dp(16));
        filterEmpty.addView(es, Ui.lpMatch());
        TextView er = Ui.ghostBtn(this, "清空条件，看全部");
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
        return col;
    }

    private void runFilterQuery() {
        if (browseAdapter == null) return;
        final Store.Filter f = filter;
        pool.execute(() -> {
            final List<Lut> r = store.query(f);
            ui.post(() -> {
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

                StringBuilder sb = new StringBuilder("命中 ").append(r.size()).append(" 枚");
                List<String> parts = new ArrayList<>();
                if (!f.genres.isEmpty()) parts.add("题材 " + f.genres.size());
                if (!f.styles.isEmpty()) parts.add("格调 " + f.styles.size());
                if (!f.countries.isEmpty()) parts.add("国家 " + f.countries.size());
                if (!f.creator.isEmpty()) parts.add("创作者");
                if (!parts.isEmpty()) sb.append("　（").append(String.join(" · ", parts)).append("）");
                if (!f.keyword.isEmpty()) sb.append("　关键词「").append(f.keyword).append("」");
                filterSummary.setText(sb.toString());
            });
        });
    }

    // ============================================================ 我的

    private View buildMine() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(col);

        col.addView(Ui.h1(this, "我的"));

        col.addView(Ui.sectionTitle(this, "内容"));
        LinearLayout box = Ui.card(this);
        box.setPadding(dp(14), 0, dp(14), 0);
        addRow(box, "我的下载", "本工具下载过的 " + store.dlCount() + " 枚 .cube",
                v -> startActivity(new Intent(this, DownloadsActivity.class)));
        box.addView(Ui.divider(this));
        addRow(box, "设置", "自动更新频率 · 离线样张缓存",
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        col.addView(box);

        col.addView(Ui.sectionTitle(this, "本地库"));
        LinearLayout box2 = Ui.card(this);
        box2.setPadding(dp(14), dp(14), dp(14), dp(14));
        box2.addView(Ui.body(this, "已收录 " + store.count() + " 枚，已补格调 "
                + store.enrichedCount() + " 枚"));
        TextView sync = Ui.sub(this, "上次同步：" + Prefs.lastSyncText(this));
        sync.setPadding(0, dp(4), 0, 0);
        box2.addView(sync);
        TextView now = Ui.ghostBtn(this, "立即更新一次");
        now.setOnClickListener(v -> manualSync());
        LinearLayout.LayoutParams np = Ui.lpMatch();
        np.topMargin = dp(12);
        box2.addView(now, np);
        col.addView(box2);

        col.addView(Ui.sectionTitle(this, "关于"));
        LinearLayout box3 = Ui.card(this);
        box3.setPadding(dp(14), dp(14), dp(14), dp(14));
        box3.addView(Ui.body(this, "LUT 搜索器 " + appVersion()));
        TextView ab = Ui.text(this,
                "LUMIX Lab 社区 LUT 的搜索 / 筛选 / 下载工具。\n"
              + "非官方，与 Panasonic 无任何关联。\n\n"
              + "数据来自 LUMIX Lab 官方接口；筛选维度均为服务端真实字段，"
              + "无文本推断。服务端不支持检索与排序，因此全部在本地 SQL 完成。\n\n"
              + "本工具不打包、不分发任何 LUT 文件 —— 下载始终直连官方接口。",
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
        r.addView(Ui.text(this, "›", 18f, Ui.TEXT_WEAK, false), Ui.lpWrap());
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
                .setTitle("第一次使用")
                .setMessage("选择怎么把社区 LUT 目录拉到本地：\n\n"
                        + "【完整预载】推荐\n"
                        + "  拉目录 + 逐条补齐格调与官方样张。\n"
                        + "  约 3 分钟。补齐后才能按格调筛选、才能看滑动对比。\n\n"
                        + "【快速开始】\n"
                        + "  只拉名称 / 创作者 / 题材 / 国家 / 下载量，约 30 秒。\n"
                        + "  格调与样张之后随时可以在「发现」页补齐。")
                .setPositiveButton("完整预载", (d, w) -> doPreload(true))
                .setNegativeButton("快速开始", (d, w) -> doPreload(false))
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
                    if (n > 0) toast("已自动更新目录（" + n + " 条）");
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
        final ProgressDialog pd = busy("更新目录…", "正在拉取");
        pool.execute(() -> {
            try {
                Api.Progress p = mkProgress(pd);
                final int n = Syncer.syncCatalog(store, p, null);
                Prefs.markSynced(this);
                ui.post(() -> {
                    pd.dismiss();
                    toast("已同步 " + n + " 条");
                    dropTab(TAB_DISCOVER);
                    dropTab(TAB_MINE);
                    showTab(tab);
                });
            } catch (Exception e) {
                ui.post(() -> { pd.dismiss(); toast("更新失败：" + e.getMessage()); });
            }
        });
    }

    private void confirmPreload() {
        new AlertDialog.Builder(this)
                .setTitle("预载全部")
                .setMessage("拉取目录 + 逐条补齐格调与官方样张。\n"
                        + "已补 " + store.enrichedCount() + " / " + store.count() + " 枚。\n"
                        + "已完成的部分会跳过。")
                .setPositiveButton("开始", (d, w) -> doPreload(true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doPreload(final boolean full) {
        final ProgressDialog pd = busy(full ? "预载中…" : "同步目录…", "准备中");
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        sp.edit().putBoolean(KEY_PRELOADED, true).apply();

        pool.execute(() -> {
            try {
                Api.Progress p = mkProgress(pd);
                ui.post(() -> pd.setMessage("拉取目录…"));
                final int got = Syncer.syncCatalog(store, p, null);
                Prefs.markSynced(this);

                int done = 0;
                if (full) {
                    List<String> todo = store.pendingEnrich(Integer.MAX_VALUE);
                    final int total = todo.size();
                    for (int i = 0; i < total; i++) {
                        final int idx = i;
                        if (i % 5 == 0) {
                            ui.post(() -> {
                                pd.setMessage("补齐第 " + (idx + 1) + "/" + total + " 枚");
                                setBar(idx, total);
                            });
                        }
                        try {
                            Lut d = Api.detail(todo.get(i), "en", p);
                            if (d != null && !d.code.isEmpty()) store.upsertDetail(d);
                            done++;
                        } catch (Exception ignored) { }
                    }
                }

                final int fDone = done;
                ui.post(() -> {
                    pd.dismiss();
                    hideBar();
                    dropTab(TAB_DISCOVER);
                    dropTab(TAB_RANK);
                    dropTab(TAB_FILTER);
                    dropTab(TAB_MINE);
                    showTab(tab);
                    new AlertDialog.Builder(this)
                            .setTitle("完成")
                            .setMessage("目录 " + got + " 条\n补齐 " + fDone + " 枚\n"
                                    + "本地共 " + store.count() + " 枚")
                            .setPositiveButton("好", null).show();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pd.dismiss();
                    hideBar();
                    new AlertDialog.Builder(this)
                            .setTitle("出错了").setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("好", null).show();
                });
            }
        });
    }

    private void confirmEnrich() {
        int pending = store.pendingEnrichCount();
        if (pending == 0) { toast("格调与样张都已补齐"); return; }
        new AlertDialog.Builder(this)
                .setTitle("补齐格调 / 样张")
                .setMessage("待补 " + pending + " 枚，约 " + (pending * 8 / 10 / 60 + 1)
                        + " 分钟（服务端限流，快不了）。\n"
                        + "补齐后才有官方原图可做滑动对比。\n"
                        + "也可以先补下载量最高的 60 枚。")
                .setPositiveButton("补 60 枚", (d, w) -> doEnrich(60))
                .setNeutralButton("全部", (d, w) -> doEnrich(Integer.MAX_VALUE))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doEnrich(int max) {
        final ProgressDialog pd = busy("补齐中…", "准备中");
        pool.execute(() -> {
            List<String> todo = store.pendingEnrich(max);
            int n = todo.size(), ok = 0;
            try {
                Api.Progress p = mkProgress(pd);
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    if (i % 5 == 0) {
                        ui.post(() -> {
                            pd.setMessage("第 " + (idx + 1) + "/" + n + " 枚");
                            setBar(idx, n);
                        });
                    }
                    try {
                        Lut d = Api.detail(todo.get(i), "en", p);
                        if (d != null && !d.code.isEmpty()) { store.upsertDetail(d); ok++; }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            final int fOk = ok;
            ui.post(() -> {
                pd.dismiss();
                hideBar();
                toast("补齐 " + fOk + " 枚");
                dropTab(TAB_DISCOVER);
                dropTab(TAB_FILTER);
                dropTab(TAB_MINE);
                showTab(tab);
            });
        });
    }

    private void showStyles() {
        Map<String, int[]> m = store.styleStats();
        if (m.isEmpty()) { toast("还没有格调数据，先「补齐格调」"); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("本地已补 ").append(store.enrichedCount()).append(" 枚\n\n");
        for (Map.Entry<String, int[]> e : m.entrySet()) {
            sb.append(String.format("%-12s %3d 枚   %s 次\n",
                    e.getKey(), e.getValue()[0], Ui.num(e.getValue()[1])));
        }
        sb.append("\n格调代号取自官方源码，均为真实字段。");
        new AlertDialog.Builder(this)
                .setTitle("格调分布").setMessage(sb.toString())
                .setPositiveButton("好", null).show();
    }

    // ============================================================ 详情 / 下载

    private void showDetail(Lut l) {
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(18), dp(6), dp(18), 0);

        final TextView hint = Ui.sub(this, "");
        hint.setPadding(0, dp(6), 0, dp(10));

        boolean hasPair = !l.thumbBefore.isEmpty() && !l.thumbAfter.isEmpty();
        boolean hasAny = hasPair || !l.thumbAfter.isEmpty();

        if (hasAny) {
            final CompareView cmp = new CompareView(this);
            col.addView(cmp, Ui.lpMatch());
            col.addView(hint, Ui.lpMatch());
            if (hasPair) loadSamplePair(cmp, hint, l);
            else { cmp.setBitmaps(null, null); hint.setText("（这枚 LUT 没有官方样张）"); }
        } else {
            hint.setText(l.enriched
                    ? "（这枚 LUT 没有官方样张）"
                    : "样张需要先补齐详情：回「发现」页点「补齐格调」。");
            col.addView(hint, Ui.lpMatch());
        }

        TextView info = Ui.text(this, detailText(l), 13f, Ui.TEXT, false);
        info.setLineSpacing(dp(2), 1f);
        col.addView(info, Ui.lpMatch());

        ScrollView sv = new ScrollView(this);
        sv.addView(col);

        new AlertDialog.Builder(this)
                .setTitle(l.line1())
                .setView(sv)
                .setPositiveButton("下载", (d, w) -> doDownload(l))
                .setNeutralButton("关闭", null)
                .show();
    }

    private String detailText(Lut l) {
        StringBuilder m = new StringBuilder();
        m.append("创作者：").append(l.creatorName).append("（").append(l.creatorCode).append("）\n");
        m.append("题材：").append(l.genreLabel()).append("（").append(l.genreCode).append("）\n");
        m.append("格调：").append(l.photoStyle.isEmpty() ? "未补齐" : l.photoStyle).append("\n");
        m.append("国家：").append(l.countryCode.isEmpty() ? "—" : l.countryCode).append("\n");
        m.append("下载量：").append(Ui.num(l.downloads)).append("\n");
        m.append("code：").append(l.code).append("\n");
        if (!l.overview.isEmpty()) {
            m.append("\n简介：").append(l.overview.length() > 400
                    ? l.overview.substring(0, 400) + "…" : l.overview);
        }
        m.append("\n\n点「下载」走官方接口取 33-grid .cube（17-grid 会被 LUMIX Lab 拒绝）。");
        m.append("\n样张为官方提供的对照图，非本工具生成。");
        return m.toString();
    }

    private void loadSamplePair(final CompareView cmp, final TextView hint, final Lut l) {
        final Bitmap[] beforeB = new Bitmap[1];
        final Bitmap[] afterB = new Bitmap[1];
        final boolean[] beforeDone = new boolean[1];
        final boolean[] afterDone = new boolean[1];
        final boolean[] flushed = new boolean[1];

        Bitmap cb = ImageLoader.get().peek(l.thumbBefore);
        Bitmap ca = ImageLoader.get().peek(l.thumbAfter);
        if (cb != null) { beforeB[0] = cb; beforeDone[0] = true; }
        if (ca != null) { afterB[0] = ca; afterDone[0] = true; }

        hint.setText("样张加载中… 已就绪 "
                + ((beforeDone[0] ? 1 : 0) + (afterDone[0] ? 1 : 0)) + "/2");

        Runnable flush = () -> {
            if (flushed[0]) return;
            if (!beforeDone[0] || !afterDone[0]) {
                hint.setText("样张加载中… 已就绪 "
                        + ((beforeDone[0] ? 1 : 0) + (afterDone[0] ? 1 : 0)) + "/2");
                return;
            }
            flushed[0] = true;
            if (beforeB[0] != null && afterB[0] != null) {
                cmp.setBitmaps(beforeB[0], afterB[0]);
                hint.setText("← 拖动把手对比「原图 / LUT 效果」→　样张由官方提供");
            } else if (afterB[0] != null) {
                cmp.setBitmaps(null, afterB[0]);
                hint.setText("（官方未提供该 LUT 的原图，只显示效果图）");
            } else {
                hint.setText("样张加载失败 —— 检查网络后重开此页");
            }
        };

        ImageLoader.get().load(l.thumbBefore, new ImageLoader.Callback() {
            @Override public void onLoaded(Bitmap b) { beforeB[0] = b; beforeDone[0] = true; flush.run(); }
            @Override public void onFailed() { beforeDone[0] = true; flush.run(); }
        });
        ImageLoader.get().load(l.thumbAfter, new ImageLoader.Callback() {
            @Override public void onLoaded(Bitmap b) { afterB[0] = b; afterDone[0] = true; flush.run(); }
            @Override public void onFailed() { afterDone[0] = true; flush.run(); }
        });
    }

    private void doDownload(Lut l) {
        ProgressDialog pd = busy("下载中…", l.line1());
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
                            .setTitle("下载失败").setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("好", null).show();
                });
            }
        });
    }

    private void afterDownload(Lut l, Downloader.Result r) {
        boolean hasLumix = Downloader.isLumixInstalled(this);
        String msg = "已解出：" + r.cubeName + "\n"
                + "保存到：" + r.displayPath + "\n"
                + "官方包 " + Ui.num(r.bytes) + " 字节\n\n"
                + "社区 code：" + l.code + "\n"
                + "（导入的 LUT 不带这个 code，所以在 LUMIX Lab 里没有「Check Details」）\n\n"
                + "接下来在 LUMIX Lab 里：\n"
                + "  LUT库 → 设备 → 导入LUT → 在「下载」里选 " + r.cubeName;

        AlertDialog.Builder ab = new AlertDialog.Builder(this)
                .setTitle("下载完成")
                .setMessage(msg)
                .setPositiveButton(hasLumix ? "打开 LUMIX Lab" : "好", null)
                .setNeutralButton("用其他应用打开", null)
                .setNegativeButton("关闭", null);
        AlertDialog d = ab.create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (hasLumix && !Downloader.launchLumix(this)) toast("无法启动 LUMIX Lab");
                d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (!Downloader.tryOpenWith(this, r.uri, "application/octet-stream")) {
                    toast("系统没有能打开 .cube 的应用 —— 按上面的路径在 LUMIX Lab 里导入");
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
                pd.setMessage("请求被限流，第 " + attempt + " 次重试，等 "
                        + (waitMs / 1000) + " 秒…"));
    }

    private void setBar(int done, int total) {
        bar.setVisibility(View.VISIBLE);
        bar.setMax(Math.max(1, total));
        bar.setProgress(done);
        barText.setVisibility(View.VISIBLE);
        barText.setText("进度 " + done + " / " + total);
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
                    else toast("这枚 LUT 不在本地库里");
                });
            });
        }
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
