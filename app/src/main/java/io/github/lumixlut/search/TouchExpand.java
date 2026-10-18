package io.github.lumixlut.search;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 把小控件的**触摸区**撑到 48dp，但**不改视觉尺寸**。
 *
 * ## 为什么挂在根视图而不是 immediate parent
 *
 * `TouchDelegate` 只有在**宿主自己**收到事件时才会被调用；挂在 chip 的父容器上，
 * 点在容器外面（比如 chip 上方 15px）时事件根本到不了容器 —— 实测点了没反应。
 * 所以这里统一挂到 `getRootView()`：坐标按窗口换算，扩展区落在屏幕上的任意位置都能命中。
 *
 * ## 为什么一个宿主只挂一个 delegate
 *
 * 一个 ViewGroup 只有一个 `touchDelegate` 槽位，而一排 chip 有 3~6 个，
 * 所以 delegate 内部维护"控件 → 扩展矩形"的多张映射。
 *
 * ## 语义
 *
 * 只处理点击（按下高亮 → 抬起 `performClick()`），不转发整条事件流：
 * 这些控件都是"点一下"而不是"拖动"。代价：**从扩展区按下**的这一次手势不会变成滚动，
 * 所以扩展量只取到 48dp（上下各 9dp 左右），不会把整行都吃掉。
 */
final class TouchExpand {

    /** 目标触摸边长（dp）—— Material / WCAG 的常用下限。 */
    static final float TARGET_DP = 48f;

    private static final WeakHashMap<ViewGroup, Multi> ROOTS = new WeakHashMap<>();

    private TouchExpand() { }

    /** 登记"这个控件的触摸区至少 48dp"。重复调用无副作用。 */
    static void apply(final View v) {
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                // 沿祖先链逐级登记：DOWN 只会送到"接住它的那一级容器"，
                // 挂一级不够（挂 content 收不到、挂祖父又够不到更高处）。
                // 每级各挂一个 delegate，谁的子控件都没吃下这个 DOWN，谁就用自己的 delegate 命中。
                View cur = view;
                for (int depth = 0; depth < 6; depth++) {
                    if (!(cur.getParent() instanceof ViewGroup)) break;
                    ViewGroup p = (ViewGroup) cur.getParent();
                    if (p.getClass().getSimpleName().contains("DecorView")) break;
                    Multi multi = ROOTS.get(p);
                    if (multi == null) {
                        multi = new Multi(p);
                        ROOTS.put(p, multi);
                        p.setTouchDelegate(multi);
                    }
                    multi.add(view);
                    cur = p;
                }
            }

            @Override public void onViewDetachedFromWindow(View view) { }
        });
    }

    private static final class Multi extends TouchDelegate {

        private final ViewGroup root;
        private final List<View> targets = new ArrayList<>();
        private View active;

        Multi(ViewGroup root) {
            super(new Rect(), root);
            this.root = root;
        }

        void add(View v) {
            if (!targets.contains(v)) targets.add(v);
        }

        /** 控件在窗口里的矩形，垂直方向中心对齐扩到 48dp；横向不动（相邻控件会互相抢）。 */
        private Rect rectOf(View v) {
            int want = Ui.dp(v.getContext(), TARGET_DP);
            int extra = Math.max(0, (want - v.getHeight()) / 2);
            int[] a = new int[2];
            int[] b = new int[2];
            v.getLocationInWindow(a);
            root.getLocationInWindow(b);
            int left = a[0] - b[0];
            int top = a[1] - b[1];
            return new Rect(left, top - extra, left + v.getWidth(), top + v.getHeight() + extra);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();

            if (action == MotionEvent.ACTION_DOWN) {
                active = null;
                for (View v : targets) {
                    if (v == null || !v.isShown() || v.getWindowToken() == null) continue;
                    if (rectOf(v).contains((int) x, (int) y)) { active = v; break; }
                }
                if (active == null) return false;
                active.setPressed(true);
                return true;
            }

            if (active == null) return false;

            if (action == MotionEvent.ACTION_UP) {
                View v = active;
                active = null;
                v.setPressed(false);
                if (rectOf(v).contains((int) x, (int) y)) v.performClick();
                return true;
            }

            if (action == MotionEvent.ACTION_CANCEL) {
                active.setPressed(false);
                active = null;
                return true;
            }

            return true;   // MOVE：这次手势已按"点按"接管，不再交给别处当滚动
        }
    }
}
