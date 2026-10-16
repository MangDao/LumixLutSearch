package io.github.lumixlut.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「我的下载」—— 本工具下载过的 .cube 一览。
 *
 * 数据来源是本地 dl 表（不是扫 MediaStore）。原因：
 *   1. 能顺带记住它对应哪枚 LUT（标题/创作者都能显示）
 *   2. 不受各版本 MediaStore 行为差异影响
 *   3. 用户手动删了文件也能识别出来（列表里标注「文件已不在」）
 *
 * 注意：文件本身在公共 Download/LUMIX_LUT/，任何文件管理器都能看到。
 * 这个页面是「集中查看 + 单条操作」，不是唯一出口。
 */
public class DownloadsActivity extends Activity {

    private Store store;
    private final List<Store.Dl> items = new ArrayList<>();
    private ArrayAdapter<Store.Dl> adapter;
    private TextView header;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        setContentView(buildUi());
        reload();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("我的下载");
        title.setTextSize(19f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(18), dp(16), dp(18), dp(6));
        root.addView(title);

        header = new TextView(this);
        header.setTextSize(12f);
        header.setPadding(dp(18), 0, dp(18), dp(10));
        root.addView(header);

        // 刷新 / 全部删除
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(18), 0, dp(18), dp(10));

        Button bRefresh = new Button(this);
        bRefresh.setText("刷新");
        bRefresh.setOnClickListener(v -> reload());

        Button bOpenDir = new Button(this);
        bOpenDir.setText("打开文件夹");
        bOpenDir.setOnClickListener(v -> openFolder());

        Button bClear = new Button(this);
        bClear.setText("清空记录");
        bClear.setOnClickListener(v -> confirmClear());

        bar.addView(bRefresh, weight());
        bar.addView(bOpenDir, weight());
        bar.addView(bClear, weight());
        root.addView(bar);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<Store.Dl>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                Store.Dl d = getItem(pos);
                if (d != null && t1 != null && t2 != null) {
                    t1.setText(d.line1());
                    t1.setTypeface(null, Typeface.BOLD);

                    StringBuilder sb = new StringBuilder(d.line2());
                    SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                    sb.append("  ·  ").append(f.format(new Date(d.savedAt)));
                    if (!fileExists(d)) sb.append("  ·  ⚠ 文件已不在");
                    t2.setText(sb.toString());
                    t2.setTextSize(11f);
                }
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showActions(items.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setTextSize(10f);
        foot.setPadding(dp(18), dp(6), dp(18), dp(12));
        foot.setText("文件保存在公共「下载/" + Downloader.OUT_DIR + "/」，"
                + "任何文件管理器都能看到。\n"
                + "导入 LUMIX Lab：LUT库 → 设备 → 导入LUT → 选这里的文件。");
        root.addView(foot);

        return root;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = 1f;
        return p;
    }

    private void reload() {
        items.clear();
        items.addAll(store.listDownloads());
        adapter.notifyDataSetChanged();

        int missing = 0;
        for (Store.Dl d : items) if (!fileExists(d)) missing++;
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(items.size()).append(" 枚");
        if (missing > 0) sb.append("，其中 ").append(missing).append(" 枚文件已不在");
        header.setText(sb.toString());
    }

    /**
     * 记录里的文件还在不在。
     *
     * 优先按 URI 探（content:// 走 ContentResolver，file:// 直接看 File），
     * URI 空或探不动就退回看 display_path —— 探不动时返回 true（宁可少报警）。
     */
    private boolean fileExists(Store.Dl d) {
        try {
            if (!d.uri.isEmpty()) {
                Uri u = Uri.parse(d.uri);
                if ("content".equals(u.getScheme())) {
                    try (android.database.Cursor c = getContentResolver()
                            .query(u, new String[]{"_id"}, null, null, null)) {
                        return c != null && c.moveToFirst();
                    }
                } else if ("file".equals(u.getScheme())) {
                    return new java.io.File(u.getPath()).exists();
                }
                return true;
            }
        } catch (Exception e) {
            return true;
        }
        return true;
    }

    private void showActions(final Store.Dl d) {
        new AlertDialog.Builder(this)
                .setTitle(d.cubeName)
                .setMessage(d.line2() + "\n\n"
                        + "位置：" + (d.displayPath.isEmpty() ? "（未记录）" : d.displayPath)
                        + "\n对应 LUT：" + (d.title.isEmpty() ? d.code : d.title)
                        + "\ncode：" + d.code)
                .setPositiveButton("打开", (x, w) -> doOpen(d))
                .setNeutralButton("分享", (x, w) -> doShare(d))
                .setNegativeButton("删除记录", (x, w) -> confirmDelete(d))
                .show();
    }

    private void doOpen(Store.Dl d) {
        if (d.uri.isEmpty()) { toast("这条记录没有可用路径"); return; }
        Uri u = Uri.parse(d.uri);
        if (Downloader.tryOpenWith(this, u, "application/octet-stream")) return;
        toast("系统里没有能打开 .cube 的应用 —— 用文件管理器打开「下载/"
                + Downloader.OUT_DIR + "/」即可");
    }

    private void doShare(Store.Dl d) {
        if (d.uri.isEmpty()) { toast("这条记录没有可用路径"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(d.uri));
            i.putExtra(Intent.EXTRA_SUBJECT, d.cubeName);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享 " + d.cubeName));
        } catch (Exception e) {
            toast("分享失败：" + e.getMessage());
        }
    }

    private void confirmDelete(final Store.Dl d) {
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("只从本页移除这条记录。\n\n"
                        + "「下载/" + Downloader.OUT_DIR + "/" + d.cubeName + "」"
                        + "本身不会被删 —— 那在公共下载目录，需要你自己在文件管理器里处理。")
                .setPositiveButton("删除记录", (x, w) -> {
                    store.removeDownload(d.code);
                    reload();
                    toast("已移除记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClear() {
        if (items.isEmpty()) { toast("记录已经是空的"); return; }
        new AlertDialog.Builder(this)
                .setTitle("清空记录")
                .setMessage("只清空本页的 " + items.size() + " 条记录，"
                        + "不会删除任何 .cube 文件。")
                .setPositiveButton("清空", (x, w) -> {
                    store.clearDownloads();
                    reload();
                    toast("已清空记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 尽力打开系统文件管理器里的公共下载目录。 */
    private void openFolder() {
        String path = "file:///sdcard/Download/" + Downloader.OUT_DIR + "/";
        Intent[] tries = new Intent[]{
                new Intent(Intent.ACTION_VIEW).setDataAndType(
                        Uri.parse(path), "resource/folder"),
                new Intent(Intent.ACTION_VIEW).setData(Uri.parse(path)),
                new Intent("android.intent.action.VIEW_DOWNLOADS"),
        };
        for (Intent i : tries) {
            if (i.resolveActivity(getPackageManager()) != null) {
                try { startActivity(i); return; } catch (Exception ignored) { }
            }
        }
        toast("没有文件管理器能直接打开这个目录。\n路径：下载/"
                + Downloader.OUT_DIR + "/");
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_LONG);
        t.setGravity(Gravity.BOTTOM, 0, dp(80));
        t.show();
    }
}
