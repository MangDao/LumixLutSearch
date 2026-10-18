package io.github.lumixlut.search;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * 用户设置。
 *
 * 目前两项：
 *   autoUpdate   自动更新频率（见 FREQ_*）
 *   cacheSamples 是否把官方样张缓存到本地（换离线也能做滑动对比，代价约 50 MB）
 */
public final class Prefs {

    private static final String FILE = "settings";

    // ---- 自动更新频率 ----
    public static final String FREQ_OPEN = "open";     // 每次打开
    public static final String FREQ_1D = "1d";
    public static final String FREQ_3D = "3d";
    public static final String FREQ_5D = "5d";
    public static final String FREQ_1W = "1w";
    public static final String FREQ_MANUAL = "manual"; // 手动

    /**
     * 界面上按这个顺序显示（文案在 `R.array.settings_freq_labels`）。
     *
     * 键留在代码里、文案进资源：键是逻辑（swhichAutoSync 要按它判断间隔），
     * 文案是会改的东西，两者混在一张表里最容易改错行。
     */
    public static final String[] FREQ_KEYS = {
            FREQ_OPEN, FREQ_1D, FREQ_3D, FREQ_5D, FREQ_1W, FREQ_MANUAL
    };

    private static final String K_FREQ = "auto_update";
    private static final String K_LAST = "last_sync_ms";
    private static final String K_CACHE = "cache_samples";
    private static final String K_CACHE_AT = "cache_at_ms";

    private static final long DAY = 24L * 60 * 60 * 1000;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------- 自动更新

    /**
     * 上次抓详情用的接口语言。语言一变（比如系统语言从英文切成中文），
     * 已缓存的简介还是旧语言的，必须整体重抓 —— 见 MainActivity 的 doPreload。
     */
    public static String detailLang(Context c) {
        return sp(c).getString("detail_lang", "");
    }

    public static void setDetailLang(Context c, String lang) {
        sp(c).edit().putString("detail_lang", lang).apply();
    }

    public static String freq(Context c) {
        return sp(c).getString(K_FREQ, FREQ_1D);
    }

    public static void setFreq(Context c, String key) {
        sp(c).edit().putString(K_FREQ, key).apply();
    }

    public static long lastSync(Context c) {
        return sp(c).getLong(K_LAST, 0L);
    }

    public static void markSynced(Context c) {
        sp(c).edit().putLong(K_LAST, System.currentTimeMillis()).apply();
    }

    /**
     * 现在该不该自动同步。
     *
     * 返回 false 的情形：
     *   · 设为「手动」
     *   · 间隔还没到
     *   · 本次启动已经同步过（sessionSynced，避免同一会话里反复拉）
     */
    public static boolean shouldAutoSync(Context c, boolean sessionSynced) {
        String f = freq(c);
        if (FREQ_MANUAL.equals(f)) return false;
        if (sessionSynced) return false;
        long last = lastSync(c);
        if (last == 0L) return true;                 // 从没同步过
        if (FREQ_OPEN.equals(f)) return true;        // 每次打开
        long gap = System.currentTimeMillis() - last;
        return gap >= intervalMs(f);
    }

    /** 距离下次自动同步还有多久；已到期或手动返回 0。 */
    public static long intervalMs(String key) {
        if (FREQ_1D.equals(key)) return DAY;
        if (FREQ_3D.equals(key)) return 3 * DAY;
        if (FREQ_5D.equals(key)) return 5 * DAY;
        if (FREQ_1W.equals(key)) return 7 * DAY;
        return 0L;                                   // open / manual
    }

    public static String lastSyncText(Context c) {
        long t = lastSync(c);
        if (t == 0L) return c.getString(R.string.common_never_synced);
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
        return f.format(new java.util.Date(t));
    }

    // ---------------------------------------------------------- 搜索历史

    private static final String K_HISTORY = "search_history";
    private static final int HISTORY_MAX = 12;
    /** 用换行分隔存一个字符串 —— 条目少，不值得为此再开一张表。 */
    private static final String SEP = "\n";

    public static List<String> history(Context c) {
        String s = sp(c).getString(K_HISTORY, "");
        List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(SEP)) {
            if (!p.trim().isEmpty()) out.add(p);
        }
        return out;
    }

    /** 记一条搜索。重复的提到最前，超出上限丢最旧的。 */
    public static void pushHistory(Context c, String kw) {
        if (kw == null) return;
        String k = kw.trim();
        if (k.isEmpty()) return;
        List<String> h = history(c);
        h.remove(k);
        h.add(0, k);
        while (h.size() > HISTORY_MAX) h.remove(h.size() - 1);
        sp(c).edit().putString(K_HISTORY, String.join(SEP, h)).apply();
    }

    public static void removeHistory(Context c, String kw) {
        List<String> h = history(c);
        h.remove(kw);
        sp(c).edit().putString(K_HISTORY, String.join(SEP, h)).apply();
    }

    public static void clearHistory(Context c) {
        sp(c).edit().remove(K_HISTORY).apply();
    }

    // ---------------------------------------------------------- 离线样张

    public static boolean cacheSamples(Context c) {
        return sp(c).getBoolean(K_CACHE, true);   // 默认开：第二次打开详情直接走本地，不再等网络
    }

    public static void setCacheSamples(Context c, boolean on) {
        sp(c).edit().putBoolean(K_CACHE, on).apply();
    }

    public static void markCacheRun(Context c) {
        sp(c).edit().putLong(K_CACHE_AT, System.currentTimeMillis()).apply();
    }

    // ---------------------------------------------------------- 触感

    private static final String K_HAPTIC = "haptic_on";
    private static final String K_HAPTIC_LEVEL = "haptic_level";

    public static boolean hapticOn(Context c) {
        return sp(c).getBoolean(K_HAPTIC, true);   // 默认开：触感是这轮的主要体验点
    }

    public static void setHapticOn(Context c, boolean on) {
        sp(c).edit().putBoolean(K_HAPTIC, on).apply();
    }

    public static int hapticLevel(Context c) {
        int v = sp(c).getInt(K_HAPTIC_LEVEL, 1);
        return v < 0 ? 0 : (v > 2 ? 2 : v);
    }

    public static void setHapticLevel(Context c, int level) {
        int v = level < 0 ? 0 : (level > 2 ? 2 : level);
        sp(c).edit().putInt(K_HAPTIC_LEVEL, v).apply();
    }

    // ---------------------------------------------------------- 外观（深浅色）

    /** 外观档位。系统只在 Android 12+ 提供"只改本应用"的能力（见 applyAppearance）。 */
    public static final int LOOK_FOLLOW = 0;
    public static final int LOOK_LIGHT = 1;
    public static final int LOOK_DARK = 2;

    private static final String K_LOOK = "appearance";

    public static int appearance(Context c) {
        int v = sp(c).getInt(K_LOOK, LOOK_FOLLOW);
        return v < 0 ? LOOK_FOLLOW : (v > LOOK_DARK ? LOOK_DARK : v);
    }

    public static void setAppearance(Context c, int v) {
        int k = v < 0 ? LOOK_FOLLOW : (v > LOOK_DARK ? LOOK_DARK : v);
        sp(c).edit().putInt(K_LOOK, k).apply();
    }

    /** 本进程上一次真正下发过的档位；-1 = 还没下发过。 */
    private static int lookApplied = -1;

    /**
     * 把外观偏好下发给系统（只在 Android 12+ 有效）。
     *
     * 为什么不用 `getApplicationNightMode()` 反查：那是 API 34 才有的方法，
     * 本项目 compileSdk 34 的 android.jar 里就没有（javap 验过），所以偏好由我们自己存，
     * 系统只负责"执行"。
     *
     * 每进程只下发一次（`lookApplied` 守卫）：`setApplicationNightMode` 会触发 Activity 重建，
     * 不守卫的话每次 init 都可能再触发一次。
     */
    public static void applyAppearance(Context c) {
        if (c == null || android.os.Build.VERSION.SDK_INT < 31) return;
        final int want = appearance(c);
        if (lookApplied == want) return;
        lookApplied = want;
        try {
            android.app.UiModeManager um =
                    (android.app.UiModeManager) c.getApplicationContext()
                            .getSystemService(Context.UI_MODE_SERVICE);
            if (um == null) return;
            um.setApplicationNightMode(want == LOOK_LIGHT
                    ? android.app.UiModeManager.MODE_NIGHT_NO
                    : (want == LOOK_DARK
                            ? android.app.UiModeManager.MODE_NIGHT_YES
                            : android.app.UiModeManager.MODE_NIGHT_AUTO));
        } catch (Throwable ignored) {
            // 个别 ROM 没实现：忽略，界面继续按系统走
        }
    }
}
