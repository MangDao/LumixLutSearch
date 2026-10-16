package io.github.lumixlut.search;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 底部弹层筛选。
 *
 * 特点：
 *   · 同一维度内【多选】，维度之间 AND
 *   · 选项上带条数（本地 SQL 数出来的真实数量，不是估的）
 *   · 候选只显示【本地库里真的有】的值，不列空项
 *   · 未点「确定」前不改动原条件（在副本上操作）
 *
 * 实现上用 Dialog + Gravity.BOTTOM 自己搭，因为项目不引 androidx，
 * 用不了 BottomSheetDialogFragment。
 */
public final class FilterSheet {

    public interface OnApply {
        void apply(Store.Filter f);
    }

    private FilterSheet() {}

    public static void show(Activity act, Store store, Store.Filter current, OnApply cb) {
        final Context c = act;

        // 在副本上改，取消就什么都不影响
        final Store.Filter draft = new Store.Filter();
        draft.keyword = current.keyword;
        draft.genres.addAll(current.genres);
        draft.styles.addAll(current.styles);
        draft.countries.addAll(current.countries);
        draft.creator = current.creator;
        draft.enrichedOnly = current.enrichedOnly;
        draft.sort = current.sort;
        draft.limit = current.limit;

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = Ui.col(c);
        sheet.setBackground(Ui.round(Ui.CARD, 16f, c, null));
        sheet.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 12));

        // --- 顶部把手 ---
        View handle = new View(c);
        handle.setBackground(Ui.round(0xFFD8DBE0, 2f, c, null));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                Ui.dp(c, 36), Ui.dp(c, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, hp);

        // --- 标题行 ---
        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 18), Ui.dp(c, 8));
        TextView title = Ui.h1(c, "筛选");
        head.addView(title, Ui.lpWeight(1f));
        TextView reset = Ui.text(c, "重置", 13f, Ui.TEXT_SUB, false);
        reset.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
        head.addView(reset, Ui.lpWrap());
        sheet.addView(head);

        // --- 可滚动主体 ---
        final LinearLayout bodyCol = Ui.col(c);
        bodyCol.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), Ui.dp(c, 8));

        final Map<String, int[]> styles = store.styleStats();
        final List<String[]> ctry = store.countryCounts();
        final int totalAll = store.count();
        final int nMovie = store.countGenre("movie");
        final int nStill = store.countGenre("still");

        /**
         * 底部按钮文案的刷新器。
         *
         * 这里必须用数组做「延后绑定」：按钮在下面的 footer 里才创建，
         * 而各选项组的点击回调在上面就注册了。
         */
        final Runnable[] refresh = new Runnable[1];

        /**
         * 所有选项组的「重算自己」闭包。
         *
         * 【为什么需要这个列表】
         * 第一版只重算了被点的那一组 —— 选「照片」时「格调」组不动，
         * VLOG 仍显示 94 且可点，点下去就是 0 结果、一片空白。
         * 分面计数必须【全部一起重算】：任一条件变化都会影响其它维度的计数。
         */
        final List<Runnable> recount = new ArrayList<>();

        final Runnable onToggle = () -> {
            for (Runnable r : recount) r.run();
            if (refresh[0] != null) refresh[0].run();
        };

        // 题材
        addGroup(c, bodyCol, "题材", new String[][]{
                {"movie", "视频"},
                {"still", "照片"},
        }, draft.genres, store, draft, Store.FACET_GENRE, recount, onToggle);

        // 格调：只用本地真的有的
        List<String[]> styleOpts = new ArrayList<>();
        for (Map.Entry<String, int[]> e : styles.entrySet()) {
            if ("?".equals(e.getKey())) continue;
            styleOpts.add(new String[]{e.getKey(), e.getKey()});
        }
        if (!styleOpts.isEmpty()) {
            addGroup(c, bodyCol, "格调", styleOpts.toArray(new String[0][]),
                    draft.styles, store, draft, Store.FACET_STYLE, recount, onToggle);
        } else {
            hintNeedEnrich(c, bodyCol, "格调");
        }

        // 国家
        if (!ctry.isEmpty()) {
            List<String[]> cOpts = new ArrayList<>();
            for (String[] row : ctry) cOpts.add(new String[]{row[0], row[0]});
            addGroup(c, bodyCol, "国家", cOpts.toArray(new String[0][]),
                    draft.countries, store, draft, Store.FACET_COUNTRY, recount, onToggle);
        }

        // 排序（单选）
        final int[] sortSel = {draft.sort};
        addSingleGroup(c, bodyCol, "排序",
                new String[]{"下载量", "名称"},
                sortSel[0] == Store.SORT_TITLE ? 1 : 0,
                idx -> { sortSel[0] = (idx == 1 ? Store.SORT_TITLE : Store.SORT_DOWNLOADS); });

        ScrollView sv = new ScrollView(c);
        sv.addView(bodyCol);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        sp.weight = 1f;
        sheet.addView(sv, sp);

        // --- 底部按钮 ---
        sheet.addView(Ui.divider(c));
        LinearLayout foot = Ui.row(c);
        foot.setPadding(Ui.dp(c, 18), Ui.dp(c, 12), Ui.dp(c, 18), 0);

        final TextView btnReset = Ui.ghostBtn(c, "重置");
        final TextView btnOk = Ui.primaryBtn(c, "确定");

        btnReset.setOnClickListener(v -> {
            draft.genres.clear();
            draft.styles.clear();
            draft.countries.clear();
            draft.creator = null;
            sortSel[0] = Store.SORT_DOWNLOADS;
            dlg.dismiss();
            cb.apply(draft);
        });
        btnOk.setOnClickListener(v -> {
            draft.sort = sortSel[0];
            dlg.dismiss();
            cb.apply(draft);
        });
        reset.setOnClickListener(v -> btnReset.performClick());

        foot.addView(btnReset, Ui.lpWeight(1f));
        LinearLayout.LayoutParams okp = Ui.lpWeight(1.4f);
        okp.leftMargin = Ui.dp(c, 10);
        foot.addView(btnOk, okp);
        sheet.addView(foot);

        // 确定按钮上显示当前选了几项
        refresh[0] = () -> {
            int n = draft.genres.size() + draft.styles.size() + draft.countries.size();
            btnOk.setText(n == 0 ? "全部 " + totalAll + " 枚" : "应用（" + n + " 项）");
        };

        dlg.setContentView(sheet);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = 0.45f;
            w.setAttributes(lp);
        }
        // 先跑一次，让初始文案正确（比如进来时已带条件）
        refresh[0].run();
        dlg.show();
    }

    /** 库里还没补齐格调时的提示。 */
    private static void hintNeedEnrich(Context c, LinearLayout parent, String what) {
        TextView t = Ui.sub(c, "本地还没有" + what + "数据 —— 先回主界面点「补齐格调」。");
        t.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        parent.addView(t);
    }

    /**
     * 加一个多选组（带分面计数）。
     *
     * opts 每项 = {值, 显示名}；条数由 facetCounts 实时算。
     * 这样「选照片后 VLOG 显示 0 并置灰」，用户不会再撞上空白结果页。
     */
    private static void addGroup(Context c, LinearLayout parent, String title,
                                 String[][] opts, final List<String> sel,
                                 final Store store, final Store.Filter draft,
                                 final String facetDim, final List<Runnable> recount,
                                 final Runnable onToggle) {
        parent.addView(Ui.sectionTitle(c, title));

        WrapLayout wrap = wrapRow(c);
        final List<TextView> chips = new ArrayList<>();

        // 这个组的「重算自己」闭包，注册进共享列表，任何条件变化都会被调用
        final Runnable self = () -> refreshGroup(c, chips, opts, sel, store, draft, facetDim);
        recount.add(self);

        for (final String[] o : opts) {
            final String val = o[0];
            final TextView chip = Ui.chip(c, o[1], sel.contains(val));
            chip.setOnClickListener(v -> {
                if (sel.contains(val)) sel.remove(val);
                else sel.add(val);
                if (onToggle != null) onToggle.run();
            });
            chips.add(chip);
            wrap.addView(chip, chipLp(c));
        }
        parent.addView(wrap);

        self.run();
    }

    /**
     * 重算这一组的徽标与可用状态。
     *
     * 计数为 0 且当前未选中的 → 置灰不可点，不让用户走进死路。
     * 已选中的即使当前是 0 也保持可点，否则他没法取消。
     */
    private static void refreshGroup(Context c, List<TextView> chips,
                                     String[][] opts, List<String> sel,
                                     Store store, Store.Filter draft, String dim) {
        Map<String, Integer> counts = store.facetCounts(draft, dim);
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            String val = opts[i][0];
            String base = opts[i][1];
            Integer boxed = counts.get(val);
            int n = boxed == null ? 0 : boxed;
            boolean selected = sel.contains(val);

            chip.setText(base + " " + n);
            chip.setEnabled(selected || n > 0);
            chip.setAlpha(selected ? 1f : (n > 0 ? 1f : 0.35f));
            Ui.applyChip(chip, c, selected);
        }
    }

    /** 加一个单选组。 */
    private static void addSingleGroup(Context c, LinearLayout parent, String title,
                                       String[] labels, int initial,
                                       final java.util.function.IntConsumer onChange) {
        parent.addView(Ui.sectionTitle(c, title));
        WrapLayout wrap = wrapRow(c);
        final int[] cur = {initial};
        final List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final TextView chip = Ui.chip(c, labels[i], i == initial);
            chip.setOnClickListener(v -> {
                cur[0] = idx;
                for (int k = 0; k < chips.size(); k++) {
                    Ui.applyChip(chips.get(k), c, k == idx);
                }
                if (onChange != null) onChange.accept(idx);
            });
            chips.add(chip);
            wrap.addView(chip, chipLp(c));
        }
        parent.addView(wrap);
    }

    /**
     * chip 容器：用 WrapLayout，一行放不下会自动换到下一行。
     *
     * 之前用的是横向 LinearLayout —— 放不下就直接溢出屏幕，
     * 13 个格调只能看到 4 个，剩下的永远点不到。这是个真 bug。
     */
    private static WrapLayout wrapRow(Context c) {
        return new WrapLayout(c).gaps(8, 8, c);
    }

    private static ViewGroup.LayoutParams chipLp(Context c) {
        // 间距由 WrapLayout 统一负责，这里不再各加 margin
        return new WrapLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
