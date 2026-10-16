package io.github.lumixlut.search;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 卡片式列表：左边样张缩略图，右边标题 + 元信息 + 下载量。
 *
 * 【为什么用 BaseAdapter 而不是 ArrayAdapter】
 * 之前继承 ArrayAdapter 并拿到它生成的视图后 removeAllViews() 重建，
 * 结果把 android.R.id.text1 / text2 从视图树里删掉了 ——
 * ListView 复用这个 view 时 ArrayAdapter.createViewFromResource 找不到 id，
 * 直接抛 RuntimeException（"Failed to find view with ID android:id/text1"）。
 *
 * 自己实现 BaseAdapter 就能完全掌控视图结构，复用逻辑也简单：
 * convertView 里挂一个 Holder，有就复用，没有就建。
 */
public class LutCardAdapter extends BaseAdapter {

    private final Context ctx;
    private final boolean showRank;
    private final List<Lut> data;

    public LutCardAdapter(Context c, List<Lut> list, boolean showRank) {
        this.ctx = c;
        this.data = list;
        this.showRank = showRank;
    }

    private int dp(float v) { return Ui.dp(ctx, v); }

    @Override public int getCount() { return data.size(); }
    @Override public Object getItem(int i) { return data.get(i); }
    @Override public long getItemId(int i) { return i; }

    /** 复用容器：整行卡片是一个 LinearLayout，holder 挂在它的 tag 上。 */
    private static class Holder {
        LinearLayout root;
        TextView rank, title, meta, dl;
        ImageView thumb;
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView instanceof LinearLayout) {
            LinearLayout root = (LinearLayout) convertView;
            Object tag = root.getTag();
            // tag 不是 Holder 说明这是别人的 view（理论上不会），重建结构
            h = (tag instanceof Holder) ? (Holder) tag : create(root);
        } else {
            LinearLayout root = Ui.col(ctx);
            root.setBackgroundColor(Ui.CARD);
            h = create(root);
        }
        bind(h, data.get(pos), pos);
        return h.root;
    }

    /** 在一张空容器里搭出卡片结构。只跑一次，之后一直复用。 */
    private Holder create(LinearLayout root) {
        Holder h = new Holder();
        h.root = root;
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = Ui.row(ctx);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));

        if (showRank) {
            h.rank = Ui.rank(ctx, 1);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    dp(26), ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.rightMargin = dp(8);
            row.addView(h.rank, rp);
        }

        h.thumb = new ImageView(ctx);
        h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        h.thumb.setBackground(Ui.round(Ui.CHIP_BG, 8f, ctx, null));
        int th = dp(52);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(76), th);
        tp.rightMargin = dp(12);
        row.addView(h.thumb, tp);

        LinearLayout col = Ui.col(ctx);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setMinimumHeight(th);

        h.title = Ui.text(ctx, "", 14.5f, Ui.TEXT, true);
        h.title.setSingleLine(true);
        h.title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.title, Ui.lpMatch());

        h.meta = Ui.text(ctx, "", 11.5f, Ui.TEXT_SUB, false);
        h.meta.setSingleLine(true);
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp = Ui.lpMatch();
        mp.topMargin = dp(3);
        col.addView(h.meta, mp);

        h.dl = Ui.text(ctx, "", 11.5f, Ui.ACCENT, true);
        LinearLayout.LayoutParams dp2 = Ui.lpMatch();
        dp2.topMargin = dp(3);
        col.addView(h.dl, dp2);

        row.addView(col, Ui.lpWeight(1f));
        root.addView(row, Ui.lpMatch());
        root.addView(Ui.divider(ctx), Ui.lpMatch());

        root.setTag(h);
        return h;
    }

    private void bind(final Holder h, final Lut l, int pos) {
        if (l == null) return;
        if (h.rank != null) h.rank.setText(String.valueOf(pos + 1));
        h.title.setText(l.title.isEmpty() ? l.code : l.title);

        StringBuilder sb = new StringBuilder();
        sb.append(l.creatorName.isEmpty() ? "—" : l.creatorName);
        sb.append("  ·  ").append(l.genreLabel());
        if (!l.photoStyle.isEmpty()) sb.append("  ·  ").append(l.photoStyle);
        if (!l.countryCode.isEmpty()) sb.append("  ·  ").append(l.countryCode);
        h.meta.setText(sb.toString());
        h.dl.setText("↓ " + Ui.num(l.downloads));

        h.thumb.setImageDrawable(null);
        final String path = l.thumbAfter.isEmpty() ? l.thumbPath : l.thumbAfter;
        if (path.isEmpty()) { h.thumb.setTag(null); return; }

        // tag 存当前路径：回调回来时校验这张 view 是否还在显示同一张图
        h.thumb.setTag(path);
        Bitmap hit = ImageLoader.get().peek(path);
        if (hit != null) { h.thumb.setImageBitmap(hit); return; }

        ImageLoader.get().load(path, new ImageLoader.Callback() {
            @Override public void onLoaded(Bitmap b) {
                if (b != null && path.equals(h.thumb.getTag())) h.thumb.setImageBitmap(b);
            }
            @Override public void onFailed() { }
        });
    }

    /** 换一批数据并刷新列表。 */
    public void setItems(List<Lut> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }
}
