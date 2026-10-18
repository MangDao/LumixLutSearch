package io.github.lumixlut.search;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 松下 25 周年专题的界面：发现页横滑条 + 详情弹层。
 *
 * 数据在 {@link Anniv25}。这里只负责画，以及把「下载」接上 {@link Downloader#fetchZipAll}。
 *
 * 视觉上刻意**不**照抄官方徽章：金标是我们自己画的圆角小标签（写「松下 25 周年专属 LUT」），
 * 页面底部也标明素材来自松影俱乐部 —— 因为这是非官方工具。
 */
public final class Anniv25Ui {

    /** 金标配色：要在照片上读得清，所以固定色值，不跟浅/深色主题走。 */
    private static final int GOLD_BG = 0xFFF3D9A0;
    private static final int GOLD_LINE = 0xFFC79A3F;
    private static final int GOLD_TEXT = 0xFF4A3308;

    private Anniv25Ui() { }

    private static int dp(Context c, float v) { return Ui.dp(c, v); }

    // ======================================================== 金标

    /** 金色小标签。text 传「25th」这类短词，或完整的「松下 25 周年专属 LUT」。 */
    public static TextView badge(Context c, String text, float sizeSp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(GOLD_TEXT);
        t.setGravity(Gravity.CENTER);
        int px = dp(c, 7), py = dp(c, 3);
        t.setPadding(px, py, px, py);
        GradientDrawable d = Ui.round(GOLD_BG, 5f, c, GOLD_LINE);
        t.setBackground(d);
        return t;
    }

    // ======================================================== 发现页横滑条

    /**
     * 发现页顶部的「松下 25 周年」横滑条。
     *
     * @param onOpenSource 点底部来源那行时调用（跳浏览器看原页面）
     */
    public static View strip(final Activity host, final Runnable onOpenSource) {
        LinearLayout box = Ui.col(host);

        // 专栏标题：青绿竖条（与普通小节的 sectionTitle 同尺寸）+ 金色字体特色
        LinearLayout head = Ui.columnTitle(host, host.getString(R.string.anniv_section));
        TextView src = Ui.text(host, host.getString(R.string.anniv_more), 11f, Ui.ACCENT, false);
        src.setPadding(dp(host, 10), dp(host, 4), 0, dp(host, 4));
        src.setOnClickListener(v -> {
            Ui.tick(v);
            if (onOpenSource != null) onOpenSource.run();
        });
        head.addView(src, Ui.lpWrap());
        box.addView(head);
        box.addView(Ui.columnRule(host));

        HorizontalScrollView hs = new HorizontalScrollView(host);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout row = Ui.row(host);
        row.setPadding(0, 0, dp(host, 8), 0);
        hs.addView(row);
        box.addView(hs, Ui.lpMatch());

        for (final Anniv25.Item it : Anniv25.ITEMS) {
            row.addView(card(host, it));
        }

        TextView note = Ui.text(host, host.getString(R.string.anniv_source),
                10f, Ui.TEXT_WEAK, false);
        note.setPadding(0, dp(host, 8), 0, 0);
        note.setOnClickListener(v -> {
            if (onOpenSource != null) onOpenSource.run();
        });
        box.addView(note);
        return box;
    }

    /** 横滑条里的一张卡：1:1 图 + 左上金标 + 标题 + 作者。 */
    private static View card(final Activity host, final Anniv25.Item it) {
        LinearLayout card = Ui.col(host);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(host, 132), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(host, 10);
        card.setLayoutParams(lp);

        FrameLayout frame = new FrameLayout(host);
        ImageView img = new ImageView(host);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Ui.round(Ui.CARD, 12f, host, Ui.LINE));
        img.setClipToOutline(true);
        img.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        img.setImageBitmap(asset(host, it.cardAsset()));
        frame.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(host, 132)));
        TextView b = badge(host, host.getString(R.string.anniv_badge_short), 9f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(host, 6);
        blp.topMargin = dp(host, 6);
        frame.addView(b, blp);
        card.addView(frame);

        TextView t = Ui.text(host, it.title, 12.5f, Ui.TEXT, true);
        t.setPadding(0, dp(host, 6), 0, 0);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(t);

        TextView c = Ui.text(host, it.creator, 10.5f, Ui.TEXT_SUB, false);
        c.setSingleLine(true);
        card.addView(c);

        Ui.pressable(card, () -> {
            Ui.tap(card);
            showDetail(host, it);
        });
        return card;
    }

    // ======================================================== 详情弹层

    public static void showDetail(final Activity host, final Anniv25.Item it) {
        final Dialog dlg = new Dialog(host);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final FrameLayout root = new FrameLayout(host);
        final View scrim = new View(host);
        scrim.setBackgroundColor(host.getColor(R.color.scrim));
        scrim.setOnClickListener(v -> dlg.dismiss());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout sheet = Ui.col(host);
        sheet.setBackground(Ui.roundTop(Ui.CARD, 24f, host));

        View handle = new View(host);
        handle.setBackground(Ui.round(Ui.LINE, 2f, host, null));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(host, 36), dp(host, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(host, 10);
        hlp.bottomMargin = dp(host, 4);
        sheet.addView(handle, hlp);

        ScrollView sv = new ScrollView(host);
        LinearLayout col = Ui.col(host);
        col.setPadding(dp(host, 20), 0, dp(host, 20), dp(host, 8));
        sv.addView(col);
        sheet.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题 + 关闭
        LinearLayout head = Ui.row(host);
        TextView title = Ui.h1(host, it.title);
        head.addView(title, Ui.lpWeight(1f));
        View close = Ui.iconButton(host, R.drawable.ic_close, 18f, Ui.TEXT_SUB,
                Ui.ICON_TOUCH, host.getString(R.string.cd_close), dlg::dismiss);
        head.addView(close, Ui.lpWrap());
        col.addView(head);

        // 作者 · 金标
        LinearLayout meta = Ui.row(host);
        meta.setPadding(0, dp(host, 6), 0, dp(host, 8));
        meta.addView(Ui.sub(host, it.creator), Ui.lpWrap());
        TextView gold = badge(host, host.getString(R.string.anniv_badge), 9.5f);
        LinearLayout.LayoutParams glp = Ui.lpWrap();
        glp.leftMargin = dp(host, 8);
        meta.addView(gold, glp);
        col.addView(meta);

        // 滑动对比（素材是裁好的 before/after）
        CompareView cv = new CompareView(host);
        cv.setBitmaps(asset(host, it.beforeAsset()), asset(host, it.afterAsset()));
        LinearLayout.LayoutParams cvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(host, 210));
        col.addView(cv, cvlp);

        TextView hint = Ui.text(host, host.getString(R.string.anniv_hint),
                10.5f, Ui.TEXT_WEAK, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(host, 6), 0, dp(host, 8));
        col.addView(hint);

        // 标签：格调（来自原图角标）+ 专题
        WrapLayout chips = new WrapLayout(host).gaps(8, 8, host);
        TextView chip1 = Ui.chip(host, host.getString(R.string.anniv_base_fmt, it.baseStyle), false);
        chips.addView(chip1, new WrapLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView chip2 = Ui.chip(host, host.getString(R.string.anniv_badge), true);
        chips.addView(chip2, new WrapLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(chips);

        TextView desc = Ui.body(host, it.desc);
        desc.setLineSpacing(dp(host, 3), 1f);
        desc.setPadding(0, dp(host, 10), 0, 0);
        col.addView(desc);

        // 下载（钉在面板底部，和普通 LUT 的详情一致）
        LinearLayout dl = Ui.primaryIconBtn(host, R.drawable.ic_download,
                host.getString(R.string.anniv_dl_btn));
        LinearLayout.LayoutParams blp = Ui.lpMatch();
        blp.setMargins(dp(host, 20), dp(host, 6), dp(host, 20), dp(host, 18));
        sheet.addView(dl, blp);
        Ui.pressable(dl, () -> {
            Ui.tap(dl);
            dlg.dismiss();
            download(host, it);
        });

        // 面板最多 86% 屏高：超出时把 ScrollView 变成 weight 区（按钮始终可见）
        final int maxH = (int) (host.getResources().getDisplayMetrics().heightPixels * 0.86f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM;
        root.addView(sheet, slp);
        sheet.setTranslationY(maxH);

        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(false);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            android.view.WindowManager.LayoutParams wlp = w.getAttributes();
            wlp.gravity = Gravity.BOTTOM;
            w.setAttributes(wlp);
        }
        dlg.show();

        scrim.setAlpha(0f);
        root.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        android.view.ViewTreeObserver v = root.getViewTreeObserver();
                        if (v.isAlive()) v.removeOnPreDrawListener(this);
                        if (sheet.getHeight() > maxH) {
                            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                            lp.height = maxH;
                            sheet.setLayoutParams(lp);
                        }
                        android.view.animation.Interpolator dec =
                                new android.view.animation.DecelerateInterpolator();
                        scrim.animate().alpha(1f).setDuration(300).setInterpolator(dec).start();
                        sheet.animate().translationY(0f).setDuration(300).setInterpolator(dec).start();
                        return true;
                    }
                });
    }

    // ======================================================== 下载

    private static void download(final Activity host, final Anniv25.Item it) {
        final ProgressDialog pd = new ProgressDialog(host);
        pd.setMessage(host.getString(R.string.anniv_dl_busy));
        pd.setCancelable(false);
        pd.show();

        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        pool.execute(() -> {
            try {
                // 直链带时间窗签名，必须现抓现用（旧的会 403）
                String url = Anniv25.resolveZip(it.previewName);
                List<Downloader.Result> rs = Downloader.fetchZipAll(host, url, Anniv25.REFERER, null);
                Store store = new Store(host);
                StringBuilder names = new StringBuilder();
                String path = "";
                // code 必须每个文件唯一：dl 表按 code 覆盖写入。德松那种一个包两个 cube
                // 如果共用 code，后一个会把前一个的记录顶掉（实测「我的下载」只剩 1 条）。
                int seq = 0;
                for (Downloader.Result r : rs) {
                    seq++;
                    store.recordDownload("anniv25_" + it.key + "#" + seq, it.title, it.creator,
                            r.cubeName, r.bytes,
                            r.uri == null ? "" : r.uri.toString(), r.displayPath);
                    if (names.length() > 0) names.append('\n');
                    names.append("· ").append(r.cubeName);
                    path = r.displayPath;
                }
                final String msg = host.getString(R.string.anniv_dl_done_fmt,
                        rs.size(), names.toString(), path);
                ui.post(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(host)
                            .setTitle(host.getString(R.string.anniv_dl_done_title))
                            .setMessage(msg)
                            .setPositiveButton(host.getString(R.string.common_close), null)
                            .show();
                });
            } catch (Exception e) {
                final String m = String.valueOf(e.getMessage());
                ui.post(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(host)
                            .setTitle(host.getString(R.string.anniv_dl_fail))
                            .setMessage(m)
                            .setPositiveButton(host.getString(R.string.common_ok), null)
                            .show();
                });
            } finally {
                pool.shutdown();
            }
        });
    }

    // ======================================================== 素材

    static Bitmap asset(Context c, String path) {
        try (java.io.InputStream is = c.getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        }
    }

}
