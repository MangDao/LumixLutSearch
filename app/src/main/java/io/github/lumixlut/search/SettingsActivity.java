package io.github.lumixlut.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置。
 *
 * 两项：
 *   自动更新   —— 按用户选的频率在启动时后台拉目录（只拉列表，不做全量补齐）
 *   离线样张   —— 把官方样张缓存到本地，换完全离线也能做滑动对比（约 50 MB）
 */
public class SettingsActivity extends Activity {

    /** 当前在哪一级："" = 条目列表；其它 = 二级页 key。 */
    private String page = "";

    private static final String PAGE_SYNC = "sync";
    private static final String PAGE_CACHE = "cache";
    private static final String PAGE_HAPTIC = "haptic";
    private static final String PAGE_LOOK = "look";
    private static final String PAGE_ABOUT = "about";

    private ScrollView scroller;
    private LinearLayout listBox;
    private LinearLayout boxSync;
    private LinearLayout boxCache;
    private LinearLayout boxHaptic;
    private LinearLayout boxLook;
    private LinearLayout boxAbout;

    private Store store;
    private TextView freqState;
    private TextView cacheState;
    private CheckBox cbCache;
    private Button btnCacheAll;

    /** 外观（深浅色）状态行；低版本没有这一栏，保持 null。 */
    private TextView lookState;

    /** 触感设置：开关 + 强度分段控件 + 状态行。 */
    private TextView hapticState;
    private LinearLayout hapticRail;
    private final java.util.List<TextView> hapticSegs = new java.util.ArrayList<>();

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        store = new Store(this);
        View root = buildUi();
        scroller = (ScrollView) root;
        setContentView(root);
        refreshState();
        setPage(page);          // 冷启动一律是一级列表
    }

    /**
     * 返回键：在二级页先回一级列表，在一级列表才真的退出。
     *
     * 为什么不用 onBackPressedDispatcher：这是原生 Activity（没有 androidx），
     * 直接重写 onBackPressed() 最省事；Android 13+ 的预测性返回动画没启用，不影响。
     */
    @Override
    public void onBackPressed() {
        if (!page.isEmpty()) {
            setPage("");
            return;
        }
        super.onBackPressed();
    }

    /** 左上角返回箭头走同一条逻辑（默认它会直接 finish，跳过了列表这一级）。 */
    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home && !page.isEmpty()) {
            setPage("");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    /**
     * 切换层级。
     *
     * @param key "" = 一级列表；否则显示对应那一箱，其它箱 GONE。
     */
    private void setPage(String key) {
        page = key == null ? "" : key;
        boolean list = page.isEmpty();

        listBox.setVisibility(list ? View.VISIBLE : View.GONE);
        boxSync.setVisibility(PAGE_SYNC.equals(page) ? View.VISIBLE : View.GONE);
        boxCache.setVisibility(PAGE_CACHE.equals(page) ? View.VISIBLE : View.GONE);
        boxHaptic.setVisibility(PAGE_HAPTIC.equals(page) ? View.VISIBLE : View.GONE);
        boxLook.setVisibility(PAGE_LOOK.equals(page) ? View.VISIBLE : View.GONE);
        boxAbout.setVisibility(PAGE_ABOUT.equals(page) ? View.VISIBLE : View.GONE);

        if (list) rebuildList();          // 回到一级时刷新每行的"当前值"
        if (scroller != null) scroller.scrollTo(0, 0);

        if (getActionBar() != null) {
            getActionBar().setTitle(list ? getString(R.string.set_title) : titleOf(page));
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private String titleOf(String key) {
        switch (key) {
            case PAGE_SYNC:   return getString(R.string.settings_entry_sync);
            case PAGE_CACHE:  return getString(R.string.settings_cache_section);
            case PAGE_HAPTIC: return getString(R.string.settings_haptic_section);
            case PAGE_LOOK:   return getString(R.string.settings_look_section);
            case PAGE_ABOUT:  return getString(R.string.mine_about);
            default:          return getString(R.string.set_title);
        }
    }

    /** 一级列表：一行一个条目，右边显示当前值 + ›。 */
    private void rebuildList() {
        listBox.removeAllViews();
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(14), 0, dp(14), 0);
        listBox.addView(card);

        addEntry(card, getString(R.string.settings_entry_sync), syncSummary(),
                () -> setPage(PAGE_SYNC));
        card.addView(Ui.divider(this));
        addEntry(card, getString(R.string.settings_cache_section), cacheSummary(),
                () -> setPage(PAGE_CACHE));
        card.addView(Ui.divider(this));
        addEntry(card, getString(R.string.settings_haptic_section), hapticSummary(),
                () -> setPage(PAGE_HAPTIC));
        card.addView(Ui.divider(this));
        addEntry(card, getString(R.string.settings_look_section), lookSummary(),
                () -> setPage(PAGE_LOOK));
        card.addView(Ui.divider(this));
        addEntry(card, getString(R.string.mine_about), "",
                () -> setPage(PAGE_ABOUT));

        TextView foot = Ui.text(this,
                getString(R.string.settings_footer_fmt,
                        getString(R.string.app_name), appVersion()),
                10f, Ui.TEXT_WEAK, false);
        foot.setPadding(0, dp(18), 0, 0);
        listBox.addView(foot);
    }

    /** 一行：左标题 + 右摘要 + ›，整行可点（≥48dp）。 */
    private void addEntry(LinearLayout card, String title, String summary, Runnable onClick) {
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(dp(52));
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout col = Ui.col(this);
        TextView t = Ui.text(this, title, 14f, Ui.TEXT, false);
        col.addView(t);
        if (summary != null && !summary.isEmpty()) {
            TextView s = Ui.text(this, summary, 11.5f, Ui.TEXT_SUB, false);
            s.setPadding(0, dp(3), 0, 0);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        row.addView(col, Ui.lpWeight(1f));

        row.addView(Ui.icon(this, R.drawable.ic_chevron_right, 12f, Ui.TEXT_WEAK),
                Ui.lpWrap());
        row.setOnClickListener(v -> {
            Ui.tick(v);
            onClick.run();
        });
        card.addView(row);
    }

    // ---------------------------------------------------------- 一级列表的摘要值

    private String syncSummary() {
        String label = freqLabel();
        return getString(R.string.settings_row_sync_fmt, label, Prefs.lastSyncText(this));
    }

    private String cacheSummary() {
        if (!Prefs.cacheSamples(this)) return getString(R.string.settings_row_cache_off);
        return getString(R.string.settings_row_cache_fmt,
                ImageLoader.diskCount(), ImageLoader.diskBytes() / 1024 / 1024);
    }

    private String hapticSummary() {
        if (!Prefs.hapticOn(this)) return getString(R.string.settings_row_haptic_off);
        String[] labels = getResources().getStringArray(R.array.settings_haptic_levels);
        return getString(R.string.settings_row_haptic_on_fmt, labels[Prefs.hapticLevel(this)]);
    }

    private String lookSummary() {
        String[] names = getResources().getStringArray(R.array.settings_look_labels);
        int look = Prefs.appearance(this);
        if (look == Prefs.LOOK_FOLLOW) {
            boolean sysNight = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            return getString(R.string.settings_row_look_follow_fmt, names[sysNight ? 2 : 1]);
        }
        return names[look];
    }

    /** 当前频率对应的文案（键在 Prefs.FREQ_KEYS，文案在 string-array，按顺序对齐）。 */
    private String freqLabel() {
        String[] keys = Prefs.FREQ_KEYS;
        String[] labels = getResources().getStringArray(R.array.settings_freq_labels);
        String cur = Prefs.freq(this);
        for (int i = 0; i < keys.length && i < labels.length; i++) {
            if (keys[i].equals(cur)) return labels[i];
        }
        return labels[0];
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 标题交给 ActionBar（manifest 里的 label），这里不再重复画一个「设置」
        root.setPadding(dp(18), dp(8), dp(18), dp(24));

        // 一级：条目列表（卡片行，右边一个 ›）。摘要值在每次回到一级时重建。
        listBox = Ui.col(this);
        root.addView(listBox);
        sv.addView(root);

        boxSync = Ui.col(this);
        root.addView(boxSync);

        // ========================================== 自动更新
        boxSync.addView(section(getString(R.string.settings_sync_section)));

        TextView freqDesc = new TextView(this);
        freqDesc.setTextSize(12f);
        freqDesc.setPadding(0, 0, 0, dp(6));
        freqDesc.setText(R.string.settings_sync_desc);
        boxSync.addView(freqDesc);

        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.settings_freq_labels));
        sp.setAdapter(ad);
        String cur = Prefs.freq(this);
        for (int i = 0; i < Prefs.FREQ_KEYS.length; i++) {
            if (Prefs.FREQ_KEYS[i].equals(cur)) { sp.setSelection(i); break; }
        }
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Prefs.setFreq(SettingsActivity.this, Prefs.FREQ_KEYS[pos]);
                refreshState();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        boxSync.addView(sp);

        freqState = new TextView(this);
        freqState.setTextSize(11f);
        freqState.setPadding(0, dp(6), 0, dp(8));
        boxSync.addView(freqState);

        Button bNow = new Button(this);
        bNow.setText(R.string.settings_sync_now);
        bNow.setOnClickListener(v -> syncNow());
        boxSync.addView(bNow);

        boxCache = Ui.col(this);
        root.addView(boxCache);

        // ========================================== 离线样张
        boxCache.addView(section(getString(R.string.settings_cache_section)));

        TextView cacheDesc = new TextView(this);
        cacheDesc.setTextSize(12f);
        cacheDesc.setPadding(0, 0, 0, dp(6));
        cacheDesc.setText(getString(R.string.settings_cache_desc_fmt, estimateMb()));
        boxCache.addView(cacheDesc);

        cbCache = new CheckBox(this);
        cbCache.setText(R.string.settings_cache_switch);
        // 平台 CheckBox 默认只有 32dp 高，补到 52dp 才够手指点（触控下限 48dp）
        cbCache.setMinimumHeight(dp(52));
        cbCache.setPadding(0, dp(6), 0, dp(6));
        cbCache.setChecked(Prefs.cacheSamples(this));
        cbCache.setOnCheckedChangeListener((v, on) -> {
            Prefs.setCacheSamples(this, on);
            if (!on) {
                // 关掉就把已占空间释放，别留着占地方
                ImageLoader.clearDiskAsync();
            }
            refreshState();
        });
        boxCache.addView(cbCache);

        cacheState = new TextView(this);
        cacheState.setTextSize(11f);
        cacheState.setPadding(0, dp(6), 0, dp(8));
        boxCache.addView(cacheState);

        LinearLayout cacheBtns = new LinearLayout(this);
        cacheBtns.setOrientation(LinearLayout.HORIZONTAL);

        btnCacheAll = new Button(this);
        btnCacheAll.setText(R.string.settings_cache_all);
        btnCacheAll.setOnClickListener(v -> cacheAll());

        Button bClear = new Button(this);
        bClear.setText(R.string.settings_cache_clear);
        bClear.setOnClickListener(v -> {
            ImageLoader.clearDiskAsync();
            ui.postDelayed(this::refreshState, 400);
            toast(getString(R.string.settings_cache_cleared));
        });

        cacheBtns.addView(btnCacheAll, weight());
        cacheBtns.addView(bClear, weight());
        boxCache.addView(cacheBtns);

        boxHaptic = Ui.col(this);
        root.addView(boxHaptic);

        // ========================================== 触感
        boxHaptic.addView(section(getString(R.string.settings_haptic_section)));

        TextView hapticDesc = new TextView(this);
        hapticDesc.setTextSize(12f);
        hapticDesc.setPadding(0, 0, 0, dp(6));
        hapticDesc.setText(R.string.settings_haptic_desc);
        boxHaptic.addView(hapticDesc);

        CheckBox cbHaptic = new CheckBox(this);
        cbHaptic.setText(R.string.settings_haptic_switch);
        cbHaptic.setMinimumHeight(dp(52));
        cbHaptic.setPadding(0, dp(6), 0, dp(6));   // 同上：32dp -> 52dp
        cbHaptic.setChecked(Prefs.hapticOn(this));
        cbHaptic.setOnCheckedChangeListener((v, on) -> {
            Prefs.setHapticOn(this, on);
            Ui.loadHapticSettings(this);
            if (on) Ui.tick(v);              // 开的时候先让手感出现
            refreshState();
        });
        boxHaptic.addView(cbHaptic);

        // 强度：低 / 中 / 高 —— 用全 app 同一套分段控件
        hapticRail = Ui.segmentRail(this);
        hapticSegs.clear();
        final String[] hapticLabels = getResources().getStringArray(R.array.settings_haptic_levels);
        for (int i = 0; i < hapticLabels.length; i++) {
            final int level = i;
            final TextView seg = Ui.segmentItem(this, hapticLabels[i],
                    Prefs.hapticLevel(this) == level);
            // 分段控件在发现/榜单页是 35.8dp 高；设置页是"要按准"的地方，
            // 这里单独抬到 48dp。样式 token 不动，免得改了三处观感。
            seg.setMinimumHeight(dp(48));
            seg.setOnClickListener(v -> {
                Prefs.setHapticLevel(this, level);
                for (int k = 0; k < hapticSegs.size(); k++) {
                    Ui.paintSegment(hapticSegs.get(k), this, k == level);
                }
                Ui.loadHapticSettings(this);
                Ui.tick(v);                  // 立刻让用户感到新强度
                refreshState();
            });
            hapticSegs.add(seg);
            hapticRail.addView(seg, Ui.lpWeight(1f));
        }
        LinearLayout.LayoutParams railLp = Ui.lpMatch();
        railLp.topMargin = dp(8);
        boxHaptic.addView(hapticRail, railLp);

        hapticState = new TextView(this);
        hapticState.setTextSize(11f);
        hapticState.setPadding(0, dp(6), 0, dp(8));
        boxHaptic.addView(hapticState);

        boxLook = Ui.col(this);
        root.addView(boxLook);

        // ========================================== 外观（浅色 / 深色）
        boxLook.addView(section(getString(R.string.settings_look_section)));

        // per-app 夜间模式是 API 31（Android 12）才有的平台能力：
        // 低版本没有等价的"只改本应用"的办法，硬做只能改 Activity 配置，会和平台控件/主题打架。
        if (Build.VERSION.SDK_INT >= 31) {
            final UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);

            TextView lookDesc = new TextView(this);
            lookDesc.setTextSize(12f);
            lookDesc.setPadding(0, 0, 0, dp(6));
            lookDesc.setText(R.string.settings_look_desc);
            boxLook.addView(lookDesc);

            final String[] labels = getResources().getStringArray(R.array.settings_look_labels);
            final int[] modes = {
                    UiModeManager.MODE_NIGHT_AUTO,   // 跟随系统
                    UiModeManager.MODE_NIGHT_NO,     // 强制浅色
                    UiModeManager.MODE_NIGHT_YES,    // 强制深色
            };
            // 当前档位从我们自己的偏好读（不反查系统：getApplicationNightMode 是 API 34+）
            final int curIdx = Prefs.appearance(this);

            LinearLayout lookRail = Ui.segmentRail(this);
            final java.util.List<TextView> lookSegs = new java.util.ArrayList<>();
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                final TextView seg = Ui.segmentItem(this, labels[i], i == curIdx);
                seg.setMinimumHeight(dp(48));    // 与触感强度条一致：设置页控件给足触控高度
                seg.setOnClickListener(v -> {
                    Ui.tick(v);
                    Prefs.setAppearance(this, idx);
                    Prefs.applyAppearance(this);   // 系统会按新档位重建 Activity
                    for (int k = 0; k < lookSegs.size(); k++) {
                        Ui.paintSegment(lookSegs.get(k), this, k == idx);
                    }
                    refreshState();
                });
                lookSegs.add(seg);
                lookRail.addView(seg, Ui.lpWeight(1f));
            }
            LinearLayout.LayoutParams lookLp = Ui.lpMatch();
            lookLp.topMargin = dp(8);
            boxLook.addView(lookRail, lookLp);

            lookState = new TextView(this);
            lookState.setTextSize(11f);
            lookState.setPadding(0, dp(6), 0, dp(8));
            boxLook.addView(lookState);
        } else {
            TextView lookDesc = new TextView(this);
            lookDesc.setTextSize(12f);
            lookDesc.setPadding(0, 0, 0, dp(6));
            lookDesc.setText(getString(R.string.settings_look_unsupported_fmt,
                    Build.VERSION.RELEASE));
            boxLook.addView(lookDesc);
        }

        boxAbout = Ui.col(this);
        root.addView(boxAbout);

        // ========================================== 说明
        TextView aboutVer = Ui.body(this,
                getString(R.string.app_name) + " " + appVersion());
        aboutVer.setPadding(0, dp(10), 0, 0);
        boxAbout.addView(aboutVer);

        // 免责与来源：和「我的 → 关于」用同一段文字，不做两份
        TextView aboutLegal = Ui.text(this, getString(R.string.mine_about_body),
                11.5f, Ui.TEXT_SUB, false);
        aboutLegal.setPadding(0, dp(10), 0, 0);
        aboutLegal.setLineSpacing(dp(2), 1f);
        boxAbout.addView(aboutLegal);

        TextView foot = new TextView(this);
        foot.setTextSize(10f);
        foot.setPadding(0, dp(18), 0, 0);
        foot.setText(R.string.settings_foot);
        boxAbout.addView(foot);

        return sv;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = 1f;
        return p;
    }

    private View section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15f);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(22), 0, dp(8));
        return t;
    }

    private void refreshState() {
        freqState.setText(getString(R.string.settings_sync_state_fmt,
                Prefs.lastSyncText(this), store.count()));
        int n = ImageLoader.diskCount();
        long by = ImageLoader.diskBytes();
        cacheState.setText(getString(R.string.settings_cache_state_fmt, n,
                by / 1024 / 1024,
                Prefs.cacheSamples(this) ? "" : getString(R.string.settings_cache_off_suffix)));
        btnCacheAll.setEnabled(Prefs.cacheSamples(this));

        if (lookState != null) {
            int look = Prefs.appearance(this);
            String[] names = getResources().getStringArray(R.array.settings_look_labels);
            if (look == Prefs.LOOK_FOLLOW) {
                boolean sysNight = (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                lookState.setText(getString(R.string.settings_look_state_follow_fmt,
                        names[sysNight ? 2 : 1]));   // 2=深色 1=浅色
            } else {
                lookState.setText(getString(R.string.settings_look_state_fixed_fmt, names[look]));
            }
        }

        if (hapticState != null) {
            boolean on = Prefs.hapticOn(this);
            hapticState.setText(on
                    ? getString(R.string.settings_haptic_state_on_fmt,
                            getResources().getStringArray(R.array.settings_haptic_levels)[
                                    Prefs.hapticLevel(this)])
                    : getString(R.string.settings_haptic_state_off));
            // 关掉时把强度条压暗，让人一眼看出"现在调了也不生效"
            if (hapticRail != null) hapticRail.setAlpha(on ? 1f : 0.4f);
        }
    }

    /** 估算：199 条 × 2 张 × 约 120 KB。 */
    private int estimateMb() {
        int n = Math.max(store.count(), 199);
        return n * 2 * 120 / 1024;
    }

    // ---------------------------------------------------------- 立即更新

    private void syncNow() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.settings_syncing));
        pd.setCancelable(false);
        pd.show();
        pool.execute(() -> {
            try {
                // Progress 只有一个方法 onRetry；重试时更新一下提示
                Api.Progress p = (attempt, waitMs, cause) -> ui.post(() ->
                        pd.setMessage(getString(R.string.settings_sync_throttled_fmt,
                                attempt, waitMs / 1000)));
                final int n = Syncer.syncCatalog(store, p, null);
                Prefs.markSynced(this);
                ui.post(() -> {
                    pd.dismiss();
                    refreshState();
                    toast(getString(R.string.settings_sync_done_fmt, n, store.count()));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pd.dismiss();
                    toast(getString(R.string.settings_sync_failed_fmt, String.valueOf(e.getMessage())));
                });
            }
        });
    }

    // ---------------------------------------------------------- 缓存全部样张

    private void cacheAll() {
        if (!Prefs.cacheSamples(this)) {
            toast(getString(R.string.settings_cache_need_switch));
            return;
        }
        final List<Lut> all = store.query(new Store.Filter());
        if (all.isEmpty()) { toast(getString(R.string.settings_cache_empty_lib)); return; }

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.settings_cache_running));
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(all.size() * 2);
        pd.setCancelable(true);
        // Dialog 没有 isCanceled()，自己记一个取消标志
        final boolean[] cancelled = {false};
        pd.setOnCancelListener(d -> cancelled[0] = true);
        pd.show();

        pool.execute(() -> {
            int done = 0, failed = 0;
            for (Lut l : all) {
                if (cancelled[0]) break;
                for (String path : new String[]{l.thumbBefore, l.thumbAfter}) {
                    if (path == null || path.isEmpty()) { done++; continue; }
                    if (ImageLoader.get().peek(path) == null) {
                        // 走同一条加载路径，顺带把结果落盘
                        final Object lock = new Object();
                        final boolean[] ok = {false};
                        ImageLoader.get().load(path, new ImageLoader.Callback() {
                            @Override public void onLoaded(android.graphics.Bitmap b) {
                                ok[0] = true;
                                synchronized (lock) { lock.notifyAll(); }
                            }
                            @Override public void onFailed() {
                                synchronized (lock) { lock.notifyAll(); }
                            }
                        });
                        synchronized (lock) {
                            try { lock.wait(25000); } catch (InterruptedException ignored) {}
                        }
                        if (!ok[0]) failed++;
                    }
                    done++;
                    final int d = done;
                    ui.post(() -> pd.setProgress(d));
                }
            }
            final int df = failed;
            Prefs.markCacheRun(this);
            ui.post(() -> {
                pd.dismiss();
                refreshState();
                toast(df == 0 ? getString(R.string.settings_cache_done_all)
                             : getString(R.string.settings_cache_done_partial_fmt, df));
            });
        });
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_LONG);
        t.setGravity(Gravity.BOTTOM, 0, dp(80));
        t.show();
    }
}
