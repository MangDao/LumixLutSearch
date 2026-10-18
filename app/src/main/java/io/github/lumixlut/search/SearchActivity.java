package io.github.lumixlut.search;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 搜索页。
 *
 * 三个状态：
 *   空          → 搜索历史 + 热门搜索词
 *   有结果      → 分组展示（标题/创作者命中 · 简介命中）
 *   无结果      → 提示 + 可搜的热词
 *
 * 选中一条后把 code 回传给 MainActivity，由它打开详情 ——
 * 详情弹窗（含滑动对比）只在 MainActivity 里有一份，不重复实现。
 */
public class SearchActivity extends Activity {

    public static final String EXTRA_CODE = "picked_code";

    private static final int PER_GROUP = 15;

    private Store store;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input;
    private LinearLayout body;
    private ScrollView scroll;

    /** 当前输入（用于结果被点开后再回来时恢复）。 */
    private String query = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        store = new Store(this);
        setContentView(buildUi());
        showIdle();
        input.postDelayed(() -> {
            input.requestFocus();
            InputMethodManager im = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private View buildUi() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        // ---- 顶栏：返回 + 输入框 + 搜索 ----
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.CARD);
        // 返回键改成 48dp 图标按钮后自己带高度了，纵向内边距要压到 2dp，
        // 否则顶栏会比原来那个「←」字符高出一截。
        bar.setPadding(dp(4), dp(2), dp(12), dp(2));

        View back = Ui.iconButton(this, R.drawable.ic_back, 22f, Ui.TEXT,
                Ui.ICON_TOUCH, getString(R.string.cd_back), this::finish);
        bar.addView(back, Ui.lpWrap());

        LinearLayout box = Ui.row(this);
        box.setBackground(Ui.round(Ui.CHIP_BG, 20f, this, null));
        box.setPadding(dp(12), 0, dp(12), 0);

        input = new EditText(this);
        input.setHint(getString(R.string.search_hint));
        input.setHintTextColor(Ui.TEXT_WEAK);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(14f);
        input.setSingleLine(true);
        input.setBackground(null);           // 用外层 box 的圆角底
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(input, Ui.lpWeight(1f));

        // 清空叉被胶囊高度卡着，触控只能给到 40dp —— 比独立按钮小一号，
        // 但它在输入框里，拇指本来就在附近，是折中而不是疏忽。
        View clear = Ui.iconButton(this, R.drawable.ic_close, 16f, Ui.TEXT_WEAK, 40f,
                getString(R.string.cd_clear), () -> { input.setText(""); showIdle(); });
        box.addView(clear, Ui.lpWrap());

        LinearLayout.LayoutParams bp = Ui.lpWeight(1f);
        bar.addView(box, bp);

        TextView go = Ui.text(this, getString(R.string.search_action), 14f, Ui.ACCENT, true);
        go.setPadding(dp(12), dp(4), dp(2), dp(4));
        go.setOnClickListener(v -> doSearch(input.getText().toString()));
        LinearLayout.LayoutParams gp = Ui.lpWrap();
        gp.leftMargin = dp(8);
        bar.addView(go, gp);

        root.addView(bar, Ui.lpMatch());
        root.addView(Ui.divider(this), Ui.lpMatch());

        // 软键盘「搜索」键（IME_ACTION_SEARCH）也触发同一条路径
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch(input.getText().toString());
                return true;
            }
            return false;
        });

        scroll = new ScrollView(this);
        body = Ui.col(this);
        body.setPadding(dp(16), dp(14), dp(16), dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 边打边出建议。这段是 v2.0 漏掉的功能 —— 之前只有点「搜索」才出结果。
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (q.isEmpty()) { showIdle(); return; }
                showSuggest(q);
            }
        });

        return root;
    }

    // ============================================================ 实时建议

    /** 建议条数上限。太多会盖住键盘，也不好点。 */
    private static final int SUGGEST_MAX = 8;

    /**
     * 输入过程中给出建议。
     *
     * 建议行【不带缩略图】—— 每敲一个键都去 CDN 拉图会又慢又闪，
     * 而且打字快时会堆一堆无用请求。要图的话点进去看详情或结果页。
     */
    private void showSuggest(final String q) {
        // SQLite 查 199 行是微秒级，这里不用防抖；但查询仍放后台线程
        pool.execute(() -> {
            final List<Lut> hits = store.suggest(q, SUGGEST_MAX);
            ui.post(() -> {
                // 期间用户可能又改了输入，过期结果直接丢
                if (!q.equals(input.getText().toString().trim())) return;

                body.removeAllViews();

                if (hits.isEmpty()) {
                    TextView t = Ui.sub(this, getString(R.string.search_no_suggest_fmt, q));
                    t.setPadding(0, dp(6), 0, dp(10));
                    body.addView(t);
                    TextView go = Ui.ghostBtn(this, getString(R.string.search_still_search_fmt, q));
                    go.setOnClickListener(v -> doSearch(q));
                    body.addView(go, Ui.lpMatch());
                    return;
                }

                body.addView(sectionHead(getString(R.string.search_suggest), hits.size()));
                for (final Lut l : hits) body.addView(suggestRow(l, q));

                TextView all = Ui.ghostBtn(this, getString(R.string.search_all_results_fmt, q));
                all.setOnClickListener(v -> doSearch(q));
                LinearLayout.LayoutParams ap = Ui.lpMatch();
                ap.topMargin = dp(6);
                body.addView(all, ap);
            });
        });
    }

    /** 一条建议：标题（命中部分加粗观感）+ 创作者 + 下载量，无缩略图。 */
    private View suggestRow(final Lut l, String q) {
        LinearLayout r = Ui.row(this);
        r.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, this, Ui.LINE));
        r.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout.LayoutParams arp = Ui.lpWrap();
        arp.rightMargin = dp(10);
        r.addView(Ui.icon(this, R.drawable.ic_search, 13f, Ui.TEXT_WEAK), arp);

        LinearLayout col = Ui.col(this);
        TextView t = Ui.text(this, l.title.isEmpty() ? l.code : l.title, 14f, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t, Ui.lpMatch());

        StringBuilder sb = new StringBuilder();
        sb.append(l.creatorName.isEmpty() ? "—" : l.creatorName);
        if (!l.photoStyle.isEmpty()) sb.append("  ·  ").append(l.photoStyle);
        sb.append("  ·  ").append(l.genreLabel());
        TextView m = Ui.text(this, sb.toString(), 11f, Ui.TEXT_SUB, false);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp = Ui.lpMatch();
        mp.topMargin = dp(3);
        col.addView(m, mp);

        r.addView(col, Ui.lpWeight(1f));

        r.addView(Ui.stat(this, R.drawable.ic_download,
                Ui.compact(l.downloads), 11f, Ui.ACCENT, true), Ui.lpWrap());

        LinearLayout.LayoutParams lp = Ui.lpMatch();
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);

        // 点建议 = 直接选中这枚，回主界面打开详情
        r.setOnClickListener(v -> {
            Intent res = new Intent();
            res.putExtra(EXTRA_CODE, l.code);
            setResult(RESULT_OK, res);
            finish();
        });
        return r;
    }

    // ============================================================ 空态

    private void showIdle() {
        query = "";
        body.removeAllViews();

        List<String> hist = Prefs.history(this);
        if (!hist.isEmpty()) {
            LinearLayout head = Ui.row(this);
            head.addView(Ui.sectionTitle(this, getString(R.string.search_history)), Ui.lpWeight(1f));
            TextView clr = Ui.text(this, getString(R.string.search_history_clear), 12f, Ui.TEXT_SUB, false);
            clr.setPadding(dp(8), dp(6), 0, dp(6));
            clr.setOnClickListener(v -> {
                Prefs.clearHistory(this);
                showIdle();
            });
            head.addView(clr, Ui.lpWrap());
            body.addView(head);

            LinearLayout wrap = Ui.col(this);
            LinearLayout cur = null;
            for (int i = 0; i < hist.size(); i++) {
                if (i % 3 == 0) { cur = Ui.row(this); wrap.addView(cur, Ui.lpMatch()); }
                final String h = hist.get(i);
                TextView c = Ui.chip(this, h, false);
                c.setOnClickListener(v -> { input.setText(h); doSearch(h); });
                c.setOnLongClickListener(v -> {
                    Ui.longPress(v);              // 长按删除是"破坏性"动作，先震一下再删
                    Prefs.removeHistory(this, h);
                    showIdle();
                    return true;
                });
                cur.addView(c, chipLp());
            }
            body.addView(wrap);

            TextView tip = Ui.text(this, getString(R.string.search_history_tip), 10f, Ui.TEXT_WEAK, false);
            tip.setPadding(0, 0, 0, dp(4));
            body.addView(tip);
        }

        // 热门：本地下载量前 12 的名字，点一下直接搜
        List<Lut> hot = store.ranking(12, null);
        if (!hot.isEmpty()) {
            body.addView(Ui.sectionTitle(this, getString(R.string.search_hot)));
            LinearLayout wrap = Ui.col(this);
            LinearLayout cur = null;
            for (int i = 0; i < hot.size(); i++) {
                if (i % 2 == 0) { cur = Ui.row(this); wrap.addView(cur, Ui.lpMatch()); }
                final Lut l = hot.get(i);
                String label = l.title.isEmpty() ? l.code : l.title;
                if (label.length() > 14) label = label.substring(0, 14) + "…";
                TextView c = Ui.chip(this, label, false);
                c.setOnClickListener(v -> {
                    input.setText(l.title);
                    doSearch(l.title);
                });
                cur.addView(c, chipLp());
            }
            body.addView(wrap);
            TextView tip2 = Ui.text(this,
                    getString(R.string.search_hot_note),
                    10f, Ui.TEXT_WEAK, false);
            tip2.setPadding(0, 0, 0, 0);
            body.addView(tip2);
        }

        if (hist.isEmpty() && hot.isEmpty()) {
            body.addView(Ui.sub(this, getString(R.string.search_empty_lib)));
        }
    }

    // ============================================================ 搜索

    private void doSearch(String kw) {
        final String q = kw == null ? "" : kw.trim();
        if (q.isEmpty()) { showIdle(); return; }
        query = q;
        Prefs.pushHistory(this, q);

        // 收起键盘，让结果能看全
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();

        body.removeAllViews();
        body.addView(Ui.sub(this, getString(R.string.search_running)));

        pool.execute(() -> {
            Store.Filter f = new Store.Filter();
            f.keyword = q;
            f.limit = PER_GROUP;
            final Store.Grouped g = store.searchGrouped(f, PER_GROUP);
            ui.post(() -> render(q, g));
        });
    }

    private void render(String q, Store.Grouped g) {
        body.removeAllViews();

        if (g.total() == 0) {
            TextView t = Ui.body(this, getString(R.string.search_not_found_fmt, q));
            t.setPadding(0, dp(6), 0, dp(10));
            body.addView(t);
            body.addView(Ui.sub(this, getString(R.string.search_try_shorter)));
            return;
        }

        TextView sum = Ui.sub(this, getString(R.string.search_total_fmt, g.total()));
        sum.setPadding(0, 0, 0, dp(6));
        body.addView(sum);

        if (!g.byName.isEmpty()) {
            body.addView(sectionHead(getString(R.string.search_group_title_creator), g.byName.size()));
            for (Lut l : g.byName) body.addView(rowFor(l));
        }
        if (!g.byDesc.isEmpty()) {
            body.addView(sectionHead(getString(R.string.search_group_desc), g.byDesc.size()));
            for (Lut l : g.byDesc) body.addView(rowFor(l));
        }
    }

    private View sectionHead(String title, int n) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, dp(14), 0, dp(8));
        View bar = new View(this);
        bar.setBackground(Ui.round(Ui.ACCENT, 1.5f, this, null));
        r.addView(bar, new LinearLayout.LayoutParams(dp(3), dp(15)));
        TextView t = Ui.h2(this, title);
        LinearLayout.LayoutParams tp = Ui.lpWrap();
        tp.leftMargin = dp(8);
        r.addView(t, tp);
        TextView c = Ui.text(this, String.valueOf(n), 12f, Ui.TEXT_SUB, false);
        LinearLayout.LayoutParams cp = Ui.lpWrap();
        cp.leftMargin = dp(8);
        r.addView(c, cp);
        return r;
    }

    /** 一条结果：缩略图 + 标题 + 元信息 + 下载量。点开把 code 回传。 */
    private View rowFor(final Lut l) {
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, this, Ui.LINE));
        card.setPadding(dp(10), dp(10), dp(12), dp(10));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.round(Ui.CHIP_BG, 8f, this, null));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(72), dp(50));
        ip.rightMargin = dp(12);
        card.addView(iv, ip);

        String path = l.thumbAfter.isEmpty() ? l.thumbPath : l.thumbAfter;
        if (!path.isEmpty()) {
            Bitmap hit = ImageLoader.get().peek(path);
            if (hit != null) iv.setImageBitmap(hit);
            else ImageLoader.get().load(path, new ImageLoader.Callback() {
                @Override public void onLoaded(Bitmap b) { iv.setImageBitmap(b); }
                @Override public void onFailed() { }
            });
        }

        LinearLayout col = Ui.col(this);
        TextView t = Ui.text(this, l.title.isEmpty() ? l.code : l.title, 14f, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t, Ui.lpMatch());

        StringBuilder sb = new StringBuilder();
        sb.append(l.creatorName.isEmpty() ? "—" : l.creatorName);
        sb.append("  ·  ").append(l.genreLabel());
        if (!l.photoStyle.isEmpty()) sb.append("  ·  ").append(l.photoStyle);
        if (!l.countryCode.isEmpty()) sb.append("  ·  ").append(l.countryCode);
        TextView m = Ui.text(this, sb.toString(), 11f, Ui.TEXT_SUB, false);
        m.setSingleLine(true);
        m.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp = Ui.lpMatch();
        mp.topMargin = dp(3);
        col.addView(m, mp);

        LinearLayout.LayoutParams dlp = Ui.lpMatch();
        dlp.topMargin = dp(3);
        col.addView(Ui.stat(this, R.drawable.ic_download,
                Ui.num(l.downloads), 11f, Ui.ACCENT, true), dlp);

        card.addView(col, Ui.lpWeight(1f));

        LinearLayout.LayoutParams lp = Ui.lpMatch();
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        card.setOnClickListener(v -> {
            Intent r = new Intent();
            r.putExtra(EXTRA_CODE, l.code);
            setResult(RESULT_OK, r);
            finish();
        });
        return card;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams p = Ui.lpWrap();
        p.rightMargin = dp(8);
        p.bottomMargin = dp(8);
        return p;
    }
}
