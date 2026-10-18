package io.github.lumixlut.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「松下25周年专属及官方lut」专栏：四个子分类 + 预览 + 下载。
 *
 * 数据来自 `assets/songying/index.json`（离线打包脚本生成），LUT 文件本身**不进 APK**：
 * 点下载时才现抓专题页拿有效直链（链接带时间窗签名，旧的会 403），带 Referer 取回，
 * 落盘时用清单里的 ASCII 文件名。
 */
public class SongyingActivity extends Activity {

    private static final String[] CATS = {"25周年专属", "人像", "户外", "室内"};

    private WrapLayout tabs;
    private LinearLayout grid;
    private String cat = CATS[0];
    private List<JSONObject> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        loadIndex();
        setContentView(build());
    }

    private int dp(float v) { return Ui.dp(this, v); }

    // ---------------------------------------------------------- 数据

    private void loadIndex() {
        try (InputStream is = getAssets().open("songying/index.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) all.add(arr.getJSONObject(i));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------- 界面

    private View build() {
        LinearLayout root = Ui.col(this);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));

        TextView sub = Ui.text(this, getString(R.string.songying_sub), 10.5f, Ui.TEXT_WEAK, false);
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        // 四个分类 chip 在 320dp 宽度下放不进一行：横向 LinearLayout 会把最后一个挤成 42px 细条，
        // 必须用会自动换行的 WrapLayout（HANDOFF §7.5 第 1 条同类问题）
        tabs = new WrapLayout(this).gaps(8, 8, this);
        root.addView(tabs);
        renderTabs();

        ScrollView sv = new ScrollView(this);
        grid = Ui.col(this);
        grid.setPadding(0, dp(10), 0, dp(10));
        sv.addView(grid);
        sv.setFillViewport(true);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        renderGrid();
        return root;
    }

    private void renderTabs() {
        tabs.removeAllViews();
        for (final String c : CATS) {
            int count = 0;
            for (JSONObject o : all) if (c.equals(o.optString("cat"))) count++;
            TextView t = Ui.chip(this, c + " " + count, c.equals(cat));
            t.setOnClickListener(v -> {
                Ui.tick(v);
                cat = c;
                renderTabs();
                renderGrid();
            });
            tabs.addView(t, new WrapLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void renderGrid() {
        grid.removeAllViews();
        LinearLayout row = null;
        int col = 0;
        for (final JSONObject o : all) {
            if (!cat.equals(o.optString("cat"))) continue;
            if (row == null || col == 2) {
                row = Ui.row(this);
                LinearLayout.LayoutParams rlp = Ui.lpMatch();
                rlp.bottomMargin = dp(12);
                grid.addView(row, rlp);
                col = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = col == 0 ? dp(6) : 0;
            lp.leftMargin = col == 1 ? dp(6) : 0;
            row.addView(card(o), lp);
            col++;
            if (col == 2) row = null;   // 满两个就换行
        }
        // 单数行：末尾补一个等宽空位，避免最后一张卡拉满整行
        if (row != null && row.getChildCount() == 1) {
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        if (grid.getChildCount() == 0) {
            TextView e = Ui.body(this, getString(R.string.songying_empty)
                    + "  [all=" + all.size() + " cat=" + cat + "]");
            e.setPadding(0, dp(24), 0, 0);
            grid.addView(e);
        }
    }

    private View card(final JSONObject o) {
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(Ui.CARD, 14f, this, Ui.LINE));
        card.setPadding(dp(8), dp(8), dp(8), dp(10));

        FrameLayout frame = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Ui.round(Ui.CHIP_BG, 10f, this, null));
        img.setClipToOutline(true);
        img.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        img.setImageBitmap(asset(prefix(o) + "_card.jpg"));
        int h = dp(96);
        frame.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        if (!o.optString("variant").isEmpty()) {
            TextView tag = Anniv25Ui.badge(this, o.optString("variant"), 8.5f);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(5); tlp.topMargin = dp(5);
            frame.addView(tag, tlp);
        }
        if (isVltOnly(o)) {
            TextView tag = Anniv25Ui.badge(this, getString(R.string.songying_vlt), 8.5f);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(5);
            tlp.topMargin = dp(5);
            frame.addView(tag, tlp);
        }
        card.addView(frame);

        String shown = o.optString("title");
        if (!o.optString("variant").isEmpty()) shown += " · " + o.optString("variant");
        TextView t = Ui.text(this, shown, 12f, Ui.TEXT, true);
        t.setPadding(0, dp(6), 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(t);

        TextView a = Ui.text(this, o.optString("authorDisplay"), 10f, Ui.TEXT_SUB, false);
        a.setSingleLine(true);
        card.addView(a);

        TextView en = Ui.text(this, o.optString("nameEn"), 9f, Ui.TEXT_WEAK, false);
        en.setSingleLine(true);
        en.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(en);

        Ui.pressable(card, () -> {
            Ui.tap(card);
            showDetail(o);
        });
        return card;
    }

    // ---------------------------------------------------------- 详情

    private void showDetail(final JSONObject o) {
        final String title = o.optString("title");
        final String author = o.optString("author");
        final boolean anniv = CATS[0].equals(o.optString("cat"));

        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final FrameLayout root = new FrameLayout(this);
        final View scrim = new View(this);
        scrim.setBackgroundColor(getColor(R.color.scrim));
        scrim.setOnClickListener(v -> dlg.dismiss());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout sheet = Ui.col(this);
        sheet.setBackground(Ui.roundTop(Ui.CARD, 24f, this));
        View handle = new View(this);
        handle.setBackground(Ui.round(Ui.LINE, 2f, this, null));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(10);
        sheet.addView(handle, hlp);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = Ui.col(this);
        col.setPadding(dp(20), dp(6), dp(20), dp(8));
        sv.addView(col);
        sheet.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.h1(this, title), Ui.lpWeight(1f));
        head.addView(Ui.iconButton(this, R.drawable.ic_close, 18f, Ui.TEXT_SUB,
                Ui.ICON_TOUCH, getString(R.string.cd_close), dlg::dismiss), Ui.lpWrap());
        col.addView(head);

        LinearLayout meta = Ui.row(this);
        meta.setPadding(0, dp(6), 0, dp(8));
        meta.addView(Ui.sub(this, o.optString("authorDisplay") + "  ·  " + o.optString("nameEn")), Ui.lpWeight(1f));
        if (anniv) meta.addView(Anniv25Ui.badge(this, getString(R.string.anniv_badge), 9f), Ui.lpWrap());
        // WARM/COOL 金标跟作者同一行：和 25 周年金标一样是"这条的身份"，不参与换行
        String variant = o.optString("variant");
        if (!variant.isEmpty()) {
            LinearLayout.LayoutParams vlp = Ui.lpWrap();
            vlp.leftMargin = dp(8);
            meta.addView(Anniv25Ui.badge(this, variant, 9.5f), vlp);
        }
        col.addView(meta);

        CompareView cv = new CompareView(this);
        cv.setBitmaps(asset(prefix(o) + "_before.jpg"), asset(prefix(o) + "_after.jpg"));
        col.addView(cv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        TextView hint = Ui.text(this, getString(R.string.anniv_hint), 10f, Ui.TEXT_WEAK, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(6));
        col.addView(hint);

        // 简介：官网每个 <li> 里的 <p class="dot">，抓进 index.json 的 desc 字段。
        // 样式与社区详情里的 overview 一致（13sp 正文、行距 +3dp）。
        String desc = o.optString("desc");
        if (!desc.isEmpty()) {
            TextView ov = Ui.body(this, desc);
            ov.setLineSpacing(dp(3), 1f);
            ov.setPadding(0, dp(10), 0, dp(2));
            col.addView(ov);
        }

        // 标签：格调
        // 取预览图左上角印的基线（「基于 V-log / 基于 STD / 基于标准…」），
        // 由离线 OCR 写进 index.json 的 base 字段（HANDOFF §5.4 待办 1）；读不出就是空，不猜。
        String base = o.optString("base");
        if (!base.isEmpty()) {
            WrapLayout chips = new WrapLayout(this).gaps(8, 8, this);
            chips.addView(Ui.chip(this, getString(R.string.anniv_base_fmt, base), false),
                    new WrapLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            chips.setPadding(0, dp(6), 0, 0);
            col.addView(chips);
        }
        // 官方把 warm/cool 两版挂在同一个预览图上（同一个 assetId），对比图就是同一张
        if (sharesCompare(o)) {
            TextView same = Ui.text(this, getString(R.string.songying_shared_compare),
                    10f, Ui.TEXT_SUB, false);
            same.setPadding(0, dp(8), 0, dp(2));
            col.addView(same);
        }

        JSONArray files = o.optJSONArray("files");
        // 一包多版时只列**本版**的文件名（warm/cold 各有自己的卡，列全部会让人以为点了会下两个）
        String myVariant = o.optString("variant");
        StringBuilder names = new StringBuilder();
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                String fv = f.optString("variant");
                if (!myVariant.isEmpty() && !fv.isEmpty() && !myVariant.equals(fv)) continue;
                if (names.length() > 0) names.append("  ·  ");
                names.append(f.optString("name"));
            }
        }
        TextView f = Ui.text(this, names.toString(), 10f, Ui.TEXT_SUB, false);
        f.setPadding(0, dp(2), 0, dp(6));
        col.addView(f);

        // 按钮上写清到底下什么：专栏里有 7 条包里只有 .vlt、3 条是 .cube + .vlt
        LinearLayout dl = Ui.primaryIconBtn(this, R.drawable.ic_download,
                getString(R.string.songying_dl_fmt, dlExts(o)));
        LinearLayout.LayoutParams blp = Ui.lpMatch();
        blp.setMargins(dp(20), dp(6), dp(20), dp(16));
        sheet.addView(dl, blp);
        Ui.pressable(dl, () -> {
            Ui.tap(dl);
            dlg.dismiss();
            download(o);
        });

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(sheet, slp);
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.86f);
        sheet.setTranslationY(maxH);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(false);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dlg.show();
        scrim.setAlpha(0f);
        // 内容超 86% 屏高时把滚动区收成 weight 区，下载按钮始终可见（同 ui HANDOFF §7.5 第 2 条）
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver vto = root.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnPreDrawListener(this);
                if (sheet.getHeight() > maxH) {
                    ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                    lp.height = maxH;
                    sheet.setLayoutParams(lp);
                    LinearLayout.LayoutParams svlp = (LinearLayout.LayoutParams) sv.getLayoutParams();
                    svlp.height = 0;
                    svlp.weight = 1f;
                    sv.setLayoutParams(svlp);
                }
                scrim.animate().alpha(1f).setDuration(260).start();
                sheet.animate().translationY(0f).setDuration(260).start();
                return true;
            }
        });
    }

    /** 这条是否和别的条目共用同一张对比图（官方 warm/cool 两版同一个 assetId）。 */
    private boolean sharesCompare(JSONObject o) {
        String asset = o.optString("assetId");
        if (asset.isEmpty()) return false;
        for (JSONObject other : all) {
            if (other == o) continue;
            if (asset.equals(other.optString("assetId"))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------- 下载

    private void download(final JSONObject o) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.anniv_dl_busy));
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        final List<String> outNames = new ArrayList<>();
        // 包内哈希名 → 落盘名：清单里带 zip 字段的条目才有（千禧回声那种 warm/cold 双版包）
        final java.util.Map<String, String> zipNames = new java.util.HashMap<>();
        JSONArray files = o.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                outNames.add(f.optString("name"));
                if (f.has("zip")) zipNames.put(f.optString("zip"), f.optString("name"));
            }
        }

        new Thread(() -> {
            try {
                // 四个子分类在俱乐部是四个栏目页，下载链接必须去**本条所属**那页现抓（见 Songying）
                String page = Songying.pageUrl(o.optString("cat"));
                String url = Songying.resolveZip(page, o.optString("preview"));
                List<Downloader.Result> rs = Downloader.fetchZipAll(this, url, page,
                        outNames, o.optString("nameEn"), zipNames,
                        pct -> runOnUiThread(() -> pd.setProgress(pct)));
                // 用户点的是哪一版，哪一版就排第一：官方包内 cold 常排在 warm 前面，
                // 不排一下，「我的下载」里 songying_<id>#1 会落在没点的那一版上。
                // 只对 WARM/COOL 成对条目动手 —— 像千禧回声那种没有 variant 的包保持原顺序。
                final String variant = o.optString("variant");
                if (!variant.isEmpty()) {
                    String want = (o.optString("nameEn") + "_" + variant).toLowerCase();
                    for (int k = 0; k < rs.size(); k++) {
                        String n = rs.get(k).cubeName.toLowerCase();
                        if (n.startsWith(want) || n.equals(want + ".cube")) {
                            if (k > 0) {
                                Downloader.Result first = rs.get(0);
                                rs.set(0, rs.get(k));
                                rs.set(k, first);
                            }
                            break;
                        }
                    }
                }
                Store store = new Store(this);
                StringBuilder saved = new StringBuilder();
                int i = 0;
                for (Downloader.Result r : rs) {
                    String code = "songying_" + o.optString("id") + "#" + (++i);
                    store.recordDownload(code, o.optString("title"), o.optString("author"),
                            r.cubeName, r.bytes, r.uri == null ? "" : r.uri.toString(), r.displayPath);
                    if (saved.length() > 0) saved.append('\n');
                    saved.append("· ").append(r.cubeName);
                }
                // 一包多版时给**目录**就够了：只报最后一个文件路径会让人以为只落了一个
                String path = rs.isEmpty() ? "" : rs.get(0).displayPath;
                int cut = path.lastIndexOf('/');
                if (cut > 0) path = path.substring(0, cut + 1);
                final String msg = getString(R.string.songying_dl_done_fmt, rs.size(), saved.toString(), path);
                runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.anniv_dl_done_title))
                            .setMessage(msg)
                            .setPositiveButton(getString(R.string.common_close), null)
                            .show();
                });
            } catch (Exception e) {
                final String m = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.anniv_dl_fail))
                            .setMessage(m)
                            .setPositiveButton(getString(R.string.common_ok), null)
                            .show();
                });
            }
        }).start();
    }

    // ---------------------------------------------------------- 工具

    private static String prefix(JSONObject o) {
        String id = o.optString("assetId");
        return "songying/" + (id.isEmpty() ? o.optString("id") : id);
    }

    /** 这条到底会落什么扩展名：包里可能只有 .cube、只有 .vlt，或两者都有。 */
    private static String dlExts(JSONObject o) {
        boolean cube = false, vlt = false;
        String myVariant = o.optString("variant");
        JSONArray files = o.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                String fv = f.optString("variant");
                if (!myVariant.isEmpty() && !fv.isEmpty() && !myVariant.equals(fv)) continue;
                String n = f.optString("name").toLowerCase();
                if (n.endsWith(".cube")) cube = true;
                else if (n.endsWith(".vlt")) vlt = true;
            }
        }
        if (cube && vlt) return ".cube + .vlt";
        if (vlt) return ".vlt";
        return ".cube";
    }

    private static boolean isVltOnly(JSONObject o) {
        JSONArray files = o.optJSONArray("files");
        if (files == null) return false;
        for (int i = 0; i < files.length(); i++) {
            String n = files.optJSONObject(i).optString("name").toLowerCase();
            if (n.endsWith(".cube")) return false;
        }
        return true;
    }

    private Bitmap asset(String path) {
        try (InputStream is = getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        }
    }
}
