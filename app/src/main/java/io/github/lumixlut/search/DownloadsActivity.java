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
    private android.widget.BaseAdapter adapter;
    private TextView header;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        store = new Store(this);
        setContentView(buildUi());
        reload();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        // 页名由 ActionBar 显示，这里不再重复画标题
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, 0);

        header = new TextView(this);
        header.setTextSize(12f);
        header.setPadding(dp(18), 0, dp(18), dp(10));
        root.addView(header);

        // 刷新 / 打开文件夹 / 清空：换成与主界面一致的按钮样式（原来是系统灰按钮，跟全局不搭）
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(16), 0, dp(16), dp(12));

        TextView bRefresh = Ui.ghostBtn(this, getString(R.string.dlpage_refresh));
        TextView bOpenDir = Ui.ghostBtn(this, getString(R.string.dlpage_open_dir));
        TextView bClear = Ui.ghostBtn(this, getString(R.string.dlpage_clear));
        bClear.setTextColor(getColor(R.color.danger));

        TextView[] btns = {bRefresh, bOpenDir, bClear};
        for (int i = 0; i < btns.length; i++) {
            final TextView b = btns[i];
            b.setBackground(Ui.rippleCard(this, Ui.R_BTN, Ui.LINE));
            LinearLayout.LayoutParams p = Ui.lpWeight(1f);
            if (i > 0) p.leftMargin = dp(8);
            bar.addView(b, p);
        }
        bRefresh.setOnClickListener(v -> reload());
        bOpenDir.setOnClickListener(v -> openFolder());
        bClear.setOnClickListener(v -> confirmClear());
        root.addView(bar);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(dp(12), 0, dp(12), 0);
        list.setClipToPadding(false);
        adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                LinearLayout row = (cv instanceof LinearLayout) ? (LinearLayout) cv : buildRow();
                bindRow(row, items.get(pos));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showActions(items.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setTextSize(10f);
        foot.setPadding(dp(18), dp(6), dp(18), dp(12));
        foot.setText(getString(R.string.dlpage_foot_fmt, Downloader.OUT_DIR));
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
        StringBuilder sb = new StringBuilder(getString(R.string.dlpage_count_fmt, items.size()));
        if (missing > 0) sb.append(getString(R.string.dlpage_missing_fmt, missing));
        header.setText(sb.toString());
    }

    /**
     * 记录里的文件还在不在。
     *
     * 优先按 URI 探（content:// 走 ContentResolver，file:// 直接看 File）；
     * 探不动（未知 scheme / 抛异常）返回 true —— 宁可少报警，不要把好的记录标成丢失。
     *
     * **URI 为空时必须退回看 display_path**。`recordDownload` 在 uri 拿不到时会存空串
     * （MediaStore 插入失败的机型就会出现），这种记录如果直接 return true，
     * 文件删掉后它永远显示正常、页头「其中 N 枚文件已不在」也会少报
     * —— 2026-10-18 用假记录验非空路径时发现的。
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
                return true;                       // 未知 scheme：判不了，按"在"处理
            }
        } catch (Exception e) {
            return true;
        }
        // uri 为空：退回看落盘路径；路径也没有就判不了，按"在"处理
        return d.displayPath.isEmpty() || new java.io.File(d.displayPath).exists();
    }

    private void showActions(final Store.Dl d) {
        new AlertDialog.Builder(this)
                .setTitle(d.cubeName)
                .setMessage(d.line2() + "\n\n"
                        + getString(R.string.dlpage_actions_path_fmt,
                                d.displayPath.isEmpty()
                                        ? getString(R.string.dlpage_path_unknown) : d.displayPath)
                        + getString(R.string.dlpage_actions_lut_fmt,
                                d.title.isEmpty() ? d.code : d.title))
                .setPositiveButton(getString(R.string.dlpage_open), (x, w) -> doOpen(d))
                .setNeutralButton(getString(R.string.dlpage_share), (x, w) -> doShare(d))
                .setNegativeButton(getString(R.string.dlpage_delete_ellipsis),
                        (x, w) -> confirmDelete(d))
                .show();
    }

    /** 一行下载记录：白卡 + 水波纹，右侧一个「删除」。 */
    private LinearLayout buildRow() {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.rippleCard(this, 14f, Ui.LINE));
        row.setElevation(dp(1f));
        row.setPadding(dp(14), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        LinearLayout mid = Ui.col(this);
        TextView t1 = new TextView(this);
        t1.setId(android.R.id.text1);
        t1.setTextSize(14f);
        t1.setTypeface(Ui.medium());
        t1.setTextColor(Ui.TEXT);
        mid.addView(t1);

        TextView t2 = new TextView(this);
        t2.setId(android.R.id.text2);
        t2.setTextSize(11f);
        t2.setTextColor(Ui.TEXT_SUB);
        LinearLayout.LayoutParams t2p = Ui.lpMatch();
        t2p.topMargin = dp(3);
        mid.addView(t2, t2p);
        row.addView(mid, Ui.lpWeight(1f));

        TextView del = new TextView(this);
        del.setId(android.R.id.button1);
        del.setText(getString(R.string.common_delete));
        del.setTextSize(12.5f);
        del.setTextColor(getColor(R.color.danger));
        del.setTypeface(Ui.medium());
        del.setGravity(Gravity.CENTER);
        del.setPadding(dp(14), dp(12), dp(14), dp(12));   // ≥48dp 触控
        row.addView(del, Ui.lpWrap());
        return row;
    }

    private void bindRow(final LinearLayout row, final Store.Dl d) {
        TextView t1 = row.findViewById(android.R.id.text1);
        TextView t2 = row.findViewById(android.R.id.text2);
        TextView del = row.findViewById(android.R.id.button1);
        t1.setText(d.line1());
        StringBuilder sb = new StringBuilder(d.line2());
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        sb.append("  ·  ").append(f.format(new Date(d.savedAt)));
        if (!fileExists(d)) sb.append(getString(R.string.dlpage_row_missing));
        t2.setText(sb.toString());
        if (del != null) del.setOnClickListener(v -> confirmDelete(d));
    }

    /**
     * 删掉记录对应的 .cube 文件。
     *
     * 顺序：先按 MediaStore/content URI 删（我们自己是 owner，通常能删掉）；
     * 失败再按 display_path 直接 File.delete()（≤28 有权限，29+ 可能被 scoped storage 拒绝）。
     * 返回是否真的删掉了文件；删不掉不影响删记录。
     */
    private boolean deleteFile(Store.Dl d) {
        boolean ok = false;
        if (!d.uri.isEmpty()) {
            Uri u = Uri.parse(d.uri);
            try {
                if ("content".equals(u.getScheme())) {
                    ok = getContentResolver().delete(u, null, null) > 0;
                }
            } catch (SecurityException ignored) {
                // 不是本应用创建的文件，系统会要求额外的用户确认；这里退回按路径删
            } catch (Exception ignored) { }
            if (!ok && "file".equals(u.getScheme()) && u.getPath() != null) {
                ok = new java.io.File(u.getPath()).delete();
            }
        }
        if (!ok && !d.displayPath.isEmpty()) {
            try { ok = new java.io.File(d.displayPath).delete(); } catch (Exception ignored) { }
        }
        return ok;
    }

    private void doOpen(Store.Dl d) {
        if (d.uri.isEmpty()) { toast(getString(R.string.dlpage_no_path)); return; }
        Uri u = Uri.parse(d.uri);
        if (Downloader.tryOpenWith(this, u, "application/octet-stream")) return;
        toast(getString(R.string.dlpage_no_opener_fmt, Downloader.OUT_DIR));
    }

    private void doShare(Store.Dl d) {
        if (d.uri.isEmpty()) { toast(getString(R.string.dlpage_no_path)); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(d.uri));
            i.putExtra(Intent.EXTRA_SUBJECT, d.cubeName);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,
                    getString(R.string.dlpage_share_chooser_fmt, d.cubeName)));
        } catch (Exception e) {
            toast(getString(R.string.dlpage_share_failed_fmt, String.valueOf(e.getMessage())));
        }
    }

    private void confirmDelete(final Store.Dl d) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlpage_delete_title))
                .setMessage(getString(R.string.dlpage_delete_message_fmt, d.cubeName))
                .setPositiveButton(getString(R.string.dlpage_delete_both), (x, w) -> {
                    boolean ok = deleteFile(d);
                    store.removeDownload(d.code);
                    reload();
                    toast(getString(ok
                            ? R.string.dlpage_deleted_both
                            : R.string.dlpage_delete_file_failed));
                })
                .setNeutralButton(getString(R.string.dlpage_delete_record_only), (x, w) -> {
                    store.removeDownload(d.code);
                    reload();
                    toast(getString(R.string.dlpage_deleted_record_only));
                })
                .setNegativeButton(getString(R.string.common_cancel), null)
                .show();
    }

    private void confirmClear() {
        if (items.isEmpty()) { toast(getString(R.string.dlpage_already_empty)); return; }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlpage_clear_title))
                .setMessage(getString(R.string.dlpage_clear_message_fmt, items.size()))
                .setPositiveButton(getString(R.string.dlpage_clear_both), (x, w) -> {
                    int n = 0;
                    for (Store.Dl d : items) if (deleteFile(d)) n++;
                    store.clearDownloads();
                    reload();
                    toast(getString(R.string.dlpage_cleared_with_files_fmt, n));
                })
                .setNeutralButton(getString(R.string.dlpage_clear_records), (x, w) -> {
                    store.clearDownloads();
                    reload();
                    toast(getString(R.string.dlpage_cleared_keep_files));
                })
                .setNegativeButton(getString(R.string.common_cancel), null)
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
        toast(getString(R.string.dlpage_no_file_manager_fmt, Downloader.OUT_DIR));
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_LONG);
        t.setGravity(Gravity.BOTTOM, 0, dp(80));
        t.show();
    }
}
