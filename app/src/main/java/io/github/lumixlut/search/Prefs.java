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

    /** 界面上按这个顺序显示。 */
    public static final String[] FREQ_KEYS = {
            FREQ_OPEN, FREQ_1D, FREQ_3D, FREQ_5D, FREQ_1W, FREQ_MANUAL
    };
    public static final String[] FREQ_LABELS = {
            "每次打开", "1 天", "3 天", "5 天", "1 周", "手动"
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

    public static String freq(Context c) {
        return sp(c).getString(K_FREQ, FREQ_1D);
    }

    public static void setFreq(Context c, String key) {
        sp(c).edit().putString(K_FREQ, key).apply();
    }

    public static String freqLabel(Context c) {
        String k = freq(c);
        for (int i = 0; i < FREQ_KEYS.length; i++) {
            if (FREQ_KEYS[i].equals(k)) return FREQ_LABELS[i];
        }
        return FREQ_LABELS[1];
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
        if (t == 0L) return "从未同步";
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
        return sp(c).getBoolean(K_CACHE, false);
    }

    public static void setCacheSamples(Context c, boolean on) {
        sp(c).edit().putBoolean(K_CACHE, on).apply();
    }

    public static void markCacheRun(Context c) {
        sp(c).edit().putLong(K_CACHE_AT, System.currentTimeMillis()).apply();
    }
}
