package io.github.lumixlut.search;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 作者榜一行：名次 · 作者名 · N 枚 · 总下载量。
 *
 * 【为什么也自己实现 BaseAdapter】
 * 与 LutCardAdapter 同一个理由：ArrayAdapter 复用视图时会去找 android:id/text1、text2，
 * 我们把视图结构整个换掉之后它找不到 id，直接抛 RuntimeException。自己写最省事。
 *
 * 数据是派生值（每枚 LUT 的下载量求和），界面里由调用方标注，不在这里重复解释。
 */
public class CreatorAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Store.Creator> data;

    public CreatorAdapter(Context c, List<Store.Creator> list) {
        this.ctx = c;
        this.data = list;
    }

    private int dp(float v) { return Ui.dp(ctx, v); }

    @Override public int getCount() { return data.size(); }
    @Override public Object getItem(int i) { return data.get(i); }
    @Override public long getItemId(int i) { return i; }

    /** 复用容器：整行是一个 LinearLayout，holder 挂在它的 tag 上。 */
    private static class Holder {
        LinearLayout root;
        TextView rank, name, count, downloads;
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView instanceof LinearLayout) {
            LinearLayout root = (LinearLayout) convertView;
            Object tag = root.getTag();
            h = (tag instanceof Holder) ? (Holder) tag : create(root);
        } else {
            LinearLayout root = Ui.col(ctx);
            root.setBackgroundColor(Ui.CARD);
            h = create(root);
        }
        bind(h, data.get(pos), pos);
        return h.root;
    }

    /** 搭结构，只跑一次。 */
    private Holder create(LinearLayout root) {
        Holder h = new Holder();
        h.root = root;
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = Ui.row(ctx);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        h.rank = Ui.rank(ctx, 1);
        row.addView(h.rank, new LinearLayout.LayoutParams(
                dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout mid = Ui.col(ctx);
        mid.setGravity(Gravity.CENTER_VERTICAL);

        h.name = Ui.text(ctx, "", 14.5f, Ui.TEXT, false);
        h.name.setTypeface(Ui.medium());
        h.name.setSingleLine(true);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(h.name, Ui.lpMatch());

        h.count = Ui.text(ctx, "", 11.5f, Ui.TEXT_SUB, false);
        h.count.setSingleLine(true);
        h.count.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams cp = Ui.lpMatch();
        cp.topMargin = dp(3);
        mid.addView(h.count, cp);

        LinearLayout.LayoutParams mp = Ui.lpWeight(1f);
        mp.leftMargin = dp(10);
        row.addView(mid, mp);

        // 总下载量：图标 + 数字。图标在 create() 里建一次，bind() 只改数字。
        LinearLayout stat = Ui.stat(ctx, R.drawable.ic_download, "", 12f, Ui.ACCENT, true);
        h.downloads = Ui.statLabel(stat);
        row.addView(stat, Ui.lpWrap());

        root.addView(row, Ui.lpMatch());
        root.addView(Ui.divider(ctx), Ui.lpMatch());

        root.setTag(h);
        return h;
    }

    private void bind(Holder h, Store.Creator k, int pos) {
        if (k == null) return;
        h.rank.setText(String.valueOf(pos + 1));
        // 复用视图：名次颜色每行都要重设，否则前三名的橙色会跟着滚动跑到别人身上
        h.rank.setTextColor(pos < 3 ? Ui.RANK_TOP : Ui.RANK_N);
        h.name.setText(k.name.isEmpty() ? k.code : k.name);
        h.count.setText(k.count + " 枚 · " + k.code);
        if (h.downloads != null) h.downloads.setText(Ui.compact(k.downloads));
    }
}
