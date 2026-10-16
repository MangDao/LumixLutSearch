package io.github.lumixlut.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.graphics.Typeface;
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

    private Store store;
    private TextView freqState;
    private TextView cacheState;
    private CheckBox cbCache;
    private Button btnCacheAll;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        setContentView(buildUi());
        refreshState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(19f);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        // ========================================== 自动更新
        root.addView(section("自动更新本地 LUT 库"));

        TextView freqDesc = new TextView(this);
        freqDesc.setTextSize(12f);
        freqDesc.setPadding(0, 0, 0, dp(6));
        freqDesc.setText("到点后在启动时后台拉一次目录（新上架的 LUT + 最新下载量），"
                + "不阻塞界面。\n"
                + "注意：只拉列表，不会把全部格调/样张重新补一遍 —— 那要几分钟。"
                + "新增的条目会在你点「补格调」时补齐。");
        root.addView(freqDesc);

        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, Prefs.FREQ_LABELS);
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
        root.addView(sp);

        freqState = new TextView(this);
        freqState.setTextSize(11f);
        freqState.setPadding(0, dp(6), 0, dp(8));
        root.addView(freqState);

        Button bNow = new Button(this);
        bNow.setText("立即更新一次");
        bNow.setOnClickListener(v -> syncNow());
        root.addView(bNow);

        // ========================================== 离线样张
        root.addView(section("离线样张缓存"));

        TextView cacheDesc = new TextView(this);
        cacheDesc.setTextSize(12f);
        cacheDesc.setPadding(0, 0, 0, dp(6));
        cacheDesc.setText("官方接口为每枚 LUT 提供两张样张（原图 + 套用效果），"
                + "滑动对比就是这两张。\n"
                + "默认不缓存：看图需要联网。开启后会把样张存到本地，"
                + "断网也能拖对比。\n"
                + "代价：约 " + estimateMb() + " MB 空间（存在应用缓存目录，"
                + "系统清理缓存时可能被回收）。");
        root.addView(cacheDesc);

        cbCache = new CheckBox(this);
        cbCache.setText("把官方样张缓存到本地");
        cbCache.setChecked(Prefs.cacheSamples(this));
        cbCache.setOnCheckedChangeListener((v, on) -> {
            Prefs.setCacheSamples(this, on);
            if (!on) {
                // 关掉就把已占空间释放，别留着占地方
                ImageLoader.clearDiskAsync();
            }
            refreshState();
        });
        root.addView(cbCache);

        cacheState = new TextView(this);
        cacheState.setTextSize(11f);
        cacheState.setPadding(0, dp(6), 0, dp(8));
        root.addView(cacheState);

        LinearLayout cacheBtns = new LinearLayout(this);
        cacheBtns.setOrientation(LinearLayout.HORIZONTAL);

        btnCacheAll = new Button(this);
        btnCacheAll.setText("缓存全部样张");
        btnCacheAll.setOnClickListener(v -> cacheAll());

        Button bClear = new Button(this);
        bClear.setText("清理缓存");
        bClear.setOnClickListener(v -> {
            ImageLoader.clearDiskAsync();
            ui.postDelayed(this::refreshState, 400);
            toast("已清理");
        });

        cacheBtns.addView(btnCacheAll, weight());
        cacheBtns.addView(bClear, weight());
        root.addView(cacheBtns);

        // ========================================== 说明
        TextView foot = new TextView(this);
        foot.setTextSize(10f);
        foot.setPadding(0, dp(18), 0, 0);
        foot.setText("服务端目录实测共 199 条，本地库就是全量。\n"
                + "搜索与筛选一直是纯本地 SQL（服务端无全文检索、不支持排序）。");
        root.addView(foot);

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
        freqState.setText("上次同步：" + Prefs.lastSyncText(this)
                + "　·　本地 " + store.count() + " 条");
        int n = ImageLoader.diskCount();
        long by = ImageLoader.diskBytes();
        cacheState.setText("已缓存 " + n + " 张，占 " + (by / 1024 / 1024) + " MB"
                + (Prefs.cacheSamples(this) ? "" : "　（未开启，不写盘）"));
        btnCacheAll.setEnabled(Prefs.cacheSamples(this));
    }

    /** 估算：199 条 × 2 张 × 约 120 KB。 */
    private int estimateMb() {
        int n = Math.max(store.count(), 199);
        return n * 2 * 120 / 1024;
    }

    // ---------------------------------------------------------- 立即更新

    private void syncNow() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在更新目录…");
        pd.setCancelable(false);
        pd.show();
        pool.execute(() -> {
            try {
                // Progress 只有一个方法 onRetry；重试时更新一下提示
                Api.Progress p = (attempt, waitMs, cause) -> ui.post(() ->
                        pd.setMessage("被限流，第 " + attempt + " 次重试，等 "
                                + (waitMs / 1000) + " 秒…"));
                final int n = Syncer.syncCatalog(store, p, null);
                Prefs.markSynced(this);
                ui.post(() -> {
                    pd.dismiss();
                    refreshState();
                    toast("已同步 " + n + " 条（本地 " + store.count() + " 条）");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pd.dismiss();
                    toast("更新失败：" + e.getMessage());
                });
            }
        });
    }

    // ---------------------------------------------------------- 缓存全部样张

    private void cacheAll() {
        if (!Prefs.cacheSamples(this)) {
            toast("请先勾选上面的开关");
            return;
        }
        final List<Lut> all = store.query(new Store.Filter());
        if (all.isEmpty()) { toast("本地库是空的，先「预载全部」"); return; }

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("缓存样张…");
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
                toast(df == 0 ? "样张已全部缓存，可离线查看对比"
                             : "完成，其中 " + df + " 张没取到（网络或服务端问题）");
            });
        });
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_LONG);
        t.setGravity(Gravity.BOTTOM, 0, dp(80));
        t.show();
    }
}
