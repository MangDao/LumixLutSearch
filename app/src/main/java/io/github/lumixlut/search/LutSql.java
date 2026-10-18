package io.github.lumixlut.search;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LUT 表的**只读 SQL**：查询、建议、排行、分面计数、分组搜索。
 *
 * 全部是静态方法，第一个参数是 `SQLiteDatabase` —— 这样它不持有连接、
 * 不参与事务边界，谁调用谁负责拿库。`Store` 里保留同名薄封装转发到这里，
 * 所以 `MainActivity` 等调用点一个字都不用改。
 *
 * 写操作（upsertList / upsertDetail）仍留在 `Store`：它们要和事务、
 * 字段裁剪规则（不覆盖已补的 photo_style / overview）绑在一起看。
 *
 * 约定：所有拼进 SQL 的**数值**（LIMIT / HAVING 的比较值）必须内联成数字字面量，
 * 不能走 ? 绑定 —— SQLite 里 INTEGER < TEXT 恒成立，绑定成字符串会让
 * `HAVING COUNT(*)>=?` 永远为假（这个坑踩过：作者榜整个空掉）。
 */
public final class LutSql {

    private LutSql() { }

    private static final String COLS =
            "code,title,creator_code,creator_name,genre_code,photo_style,"
          + "country_code,downloads,overview,thumb_path,enriched,"
          + "thumb_before,thumb_after";

    /**
     * 拼 IN (?,?,...)。
     * 之所以用 IN 而不是 OR 串，是为了让参数走绑定，不做字符串拼接。
     */
    private static void in(StringBuilder w, List<String> p, String col, List<String> vals,
                           boolean upper) {
        if (vals == null || vals.isEmpty()) return;
        w.append(" AND ").append(col).append(" IN (");
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) w.append(",");
            w.append("?");
            p.add(upper ? vals.get(i).toUpperCase() : vals.get(i));
        }
        w.append(")");
    }
    /** 非关键词的筛选条件（关键词在 query() 里单独处理，因为要参与排序）。 */
    private static void where(Store.Filter f, StringBuilder w, List<String> p) {
        in(w, p, "genre_code", f.genres, false);
        in(w, p, "UPPER(photo_style)", f.styles, true);
        in(w, p, "country_code", f.countries, true);
        if (f.creator != null && !f.creator.isEmpty()) {
            w.append(" AND creator_code=?"); p.add(f.creator);
        }
        if (f.enrichedOnly) w.append(" AND enriched=1");
    }
    /**
     * 排序。
     *
     * 有关键词时【匹配质量优先】（rank），用户选的排序只作为同档内的次级 ——
     * 搜到的相关性不该被"按名称排"打乱。
     */
    private static String orderBy(Store.Filter f, boolean hasKeyword) {
        String tie = (f.sort == Store.SORT_TITLE)
                ? "title COLLATE NOCASE ASC"
                : "downloads DESC";
        return hasKeyword ? " ORDER BY rank ASC, " + tie : " ORDER BY " + tie;
    }
    private static Lut read(Cursor c) {
        Lut l = new Lut();
        l.code = nz(c.getString(0));
        l.title = nz(c.getString(1));
        l.creatorCode = nz(c.getString(2));
        l.creatorName = nz(c.getString(3));
        l.genreCode = nz(c.getString(4));
        l.photoStyle = nz(c.getString(5));
        l.countryCode = nz(c.getString(6));
        l.downloads = c.getInt(7);
        l.overview = nz(c.getString(8));
        l.thumbPath = nz(c.getString(9));
        l.enriched = c.getInt(10) == 1;
        l.thumbBefore = nz(c.getString(11));
        l.thumbAfter = nz(c.getString(12));
        // 老数据可能只填了 thumb_path，兜一下
        if (l.thumbAfter.isEmpty()) l.thumbAfter = l.thumbPath;
        return l;
    }
    static String nz(String s) { return s == null ? "" : s; }
    /**
     * 按条件查询。
     *
     * 有关键词时按【匹配质量】排序，而不是一律 %kw%：
     *   0  标题以关键词开头        ← "M" 先出 M 开头的
     *   1  创作者以关键词开头
     *   2  标题包含关键词
     *   3  创作者包含关键词
     *   4  code 包含
     *   5  仅简介命中              ← 最不相关，排最后
     * 同档内按下载量降序。
     *
     * 这样搜 "M" 时 M 开头的 LUT 一定排在最前，简介里的散字母不会挤掉它们。
     */
    public static List<Lut> query(SQLiteDatabase db, Store.Filter f) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<String> fp = new ArrayList<>();
        where(f, w, fp);

        String kw = f.keyword == null ? "" : f.keyword.trim();
        String sql;
        List<String> args = new ArrayList<>();

        if (kw.isEmpty()) {
            sql = "SELECT " + COLS + " FROM lut" + w + orderBy(f, false) + " LIMIT ?";
            args.addAll(fp);
        } else {
            String pre = kw + "%";
            String con = "%" + kw + "%";
            // SELECT 里的 ? 排在 WHERE 之前，参数顺序必须对应
            args.add(pre);      // rank 0: title 前缀
            args.add(pre);      // rank 1: creator 前缀
            args.add(con);      // rank 2: title 包含
            args.add(con);      // rank 3: creator 包含
            args.add(con);      // rank 4: code 包含
            args.addAll(fp);    // 其它筛选
            args.add(con);      // WHERE: title
            args.add(con);      // WHERE: creator
            args.add(con);      // WHERE: code
            args.add(con);      // WHERE: overview
            sql = "SELECT " + COLS + ", CASE"
                + " WHEN title LIKE ? THEN 0"
                + " WHEN creator_name LIKE ? THEN 1"
                + " WHEN title LIKE ? THEN 2"
                + " WHEN creator_name LIKE ? THEN 3"
                + " WHEN code LIKE ? THEN 4"
                + " ELSE 5 END AS rank"
                + " FROM lut" + w
                + " AND (title LIKE ? OR creator_name LIKE ?"
                + "      OR code LIKE ? OR overview LIKE ?)"
                + orderBy(f, true) + " LIMIT ?";
        }
        args.add(String.valueOf(f.limit));

        List<Lut> out = new ArrayList<>();
        try (Cursor c = db
                .rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }
    /** 关键词为前缀命中的条数（用于状态栏提示）。 */
    public static int countPrefix(SQLiteDatabase db, String kw) {
        if (kw == null || kw.trim().isEmpty()) return 0;
        String pre = kw.trim() + "%";
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM lut WHERE title LIKE ? OR creator_name LIKE ?",
                new String[]{pre, pre})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    /**
     * 搜索建议：输入 "M" → 返回以 M 开头的下载量前 N 条。
     *
     * 排序优先级：
     *   0  标题以关键词开头      （最贴合"打一个 M 推荐 M 开头"）
     *   1  创作者名以关键词开头
     *   2  标题包含关键词
     *   同一档内按下载量降序。
     *
     * 【列清单必须用 COLS】
     * 这里以前是手写的 11 列，v1.2 给 read() 加了 thumb_before/thumb_after 变成 13 列，
     * 忘了同步 —— 于是 c.getString(11) 直接越界，搜索建议一打就崩。
     * 统一走 COLS 就不会再出现这种"两处清单不一致"的问题。
     */
    public static List<Lut> suggest(SQLiteDatabase db, String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return new ArrayList<>();
        String prefix = q + "%";
        String contains = "%" + q + "%";

        String sql = "SELECT " + COLS + ", CASE "
                + "   WHEN title LIKE ? THEN 0 "
                + "   WHEN creator_name LIKE ? THEN 1 "
                + "   ELSE 2 END AS rank"
                + " FROM lut"
                + " WHERE title LIKE ? OR creator_name LIKE ?"
                + " ORDER BY rank ASC, downloads DESC LIMIT ?";

        List<Lut> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{
                prefix, prefix, contains, contains, String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }
    public static int count(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM lut", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    public static int enrichedCount(SQLiteDatabase db) {
        try (Cursor c = db
                .rawQuery("SELECT COUNT(*) FROM lut WHERE enriched=1", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    /**
     * 待补齐的 code 列表（按下载量降序，优先补热门的）。
     *
     * 两类都算「待补」：
     *   1. enriched=0            —— 从没抓过详情，格调/简介/样张都没有
     *   2. enriched=1 但没有样张 —— v1.1 及更早补过的行，当时没保存
     *      thumbnail.path_original，所以缺原图。不重新认领的话，
     *      老用户升级后永远滑不出对比图。
     *
     * 详情接口是唯一会返回 path_original 的地方，重抓一次就能补上。
     */
    /** 全部 code，按下载量倒序。 */
    public static List<String> allCodes(SQLiteDatabase db) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT code FROM lut ORDER BY downloads DESC", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public static List<String> pendingEnrich(SQLiteDatabase db, int max) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT code FROM lut"
                        + " WHERE enriched=0 OR length(COALESCE(thumb_before,''))=0"
                        + " ORDER BY downloads DESC LIMIT ?",
                new String[]{String.valueOf(max)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }
    /** 待补条数 —— 与 pendingEnrich 的口径必须一致，否则弹窗数字对不上。 */
    public static int pendingEnrichCount(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM lut"
                        + " WHERE enriched=0 OR length(COALESCE(thumb_before,''))=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    /** 格调分布：[格调, 条数, 总下载量]。 */
    public static Map<String, int[]> styleStats(SQLiteDatabase db) {
        Map<String, int[]> m = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery(
                "SELECT COALESCE(UPPER(photo_style),'?'), COUNT(*), COALESCE(SUM(downloads),0)"
                        + " FROM lut WHERE enriched=1 GROUP BY 1 ORDER BY 2 DESC", null)) {
            while (c.moveToNext()) m.put(c.getString(0), new int[]{c.getInt(1), c.getInt(2)});
        }
        return m;
    }
    /** 各国条数（按条数降序），用于首页的按国家浏览。 */
    public static List<String[]> countryCounts(SQLiteDatabase db) {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT country_code, COUNT(*) FROM lut"
                        + " WHERE length(COALESCE(country_code,''))>0"
                        + " GROUP BY 1 ORDER BY 2 DESC", null)) {
            while (c.moveToNext()) out.add(new String[]{nz(c.getString(0)), String.valueOf(c.getInt(1))});
        }
        return out;
    }
    /**
     * 下载榜。genre 为空则不分题材。
     *
     * 用 MAX(downloads) 而不是 SUM —— 同一条 LUT 不该因多题材被重复计数。
     */
    public static List<Lut> ranking(SQLiteDatabase db, int limit, String genre) {
        Store.Filter f = new Store.Filter();
        if (genre != null && !genre.isEmpty()) f.genres.add(genre);
        return rankingBy(db, limit, f);
    }
    /**
     * 下载榜（带筛选）。排行页用这个：题材来自顶部分段控件，格调/国家来自筛选弹层。
     *
     * 排序固定按下载量 —— 这是「榜」，不跟随筛选里的排序项。
     */
    public static List<Lut> rankingBy(SQLiteDatabase db, int limit, Store.Filter f) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (f != null) where(f, w, args);
        List<Lut> out = new ArrayList<>();
        // LIMIT 用字面量：limit 是代码里的 int，不存在注入
        try (Cursor c = db.rawQuery(
                "SELECT " + COLS + " FROM lut" + w + " ORDER BY downloads DESC LIMIT "
                        + Math.max(1, limit),
                args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }
    /** 按 code 取一条。搜索页回传 code 后用这个还原成完整记录。 */
    public static Lut byCode(SQLiteDatabase db, String code) {
        if (code == null || code.isEmpty()) return null;
        try (Cursor c = db.rawQuery(
                "SELECT " + COLS + " FROM lut WHERE code=? LIMIT 1",
                new String[]{code})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }
    /**
     * 创作者榜（按总下载量）。
     *
     * 注意：这是【派生数据】——由每枚 LUT 的 download_number 相加得到，
     * 不是服务端提供的独立字段。榜单排名可复核，但它不代表官方认证。
     */
    /**
     * 创作者榜（可带筛选条件；作者榜跟着排行页的格调/国家一起过滤）。
     *
     * 【数字一律写字面量，不走 ? 绑定】
     * rawQuery 只收 String[]，`HAVING COUNT(*)>=?` 会绑成**文本** '1'；
     * SQLite 的类型序是 NULL &lt; INTEGER &lt; TEXT，整数永远小于文本，
     * 于是 `1 >= '1'` 恒为 false —— 条件永远不成立，榜单直接空掉（这条坑真踩过）。
     * limit / minCount 都是代码里的 int，直接拼进 SQL 没有注入面。
     */
    public static List<Store.Creator> topCreators(SQLiteDatabase db, int limit, int minCount,
            Store.Filter f) {
        List<Store.Creator> out = new ArrayList<>();
        StringBuilder w = new StringBuilder(" WHERE length(COALESCE(creator_code,''))>0");
        List<String> args = new ArrayList<>();
        if (f != null) where(f, w, args);
        String sql = "SELECT creator_code, MAX(creator_name), COUNT(*), SUM(downloads)"
                + " FROM lut" + w
                + " GROUP BY creator_code HAVING COUNT(*)>=" + Math.max(0, minCount)
                + " ORDER BY SUM(downloads) DESC LIMIT " + Math.max(1, limit);
        try (Cursor c = db.rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                Store.Creator k = new Store.Creator();
                k.code = nz(c.getString(0));
                k.name = nz(c.getString(1));
                k.count = c.getInt(2);
                k.downloads = c.getInt(3);
                out.add(k);
            }
        }
        return out;
    }
    /** 某个题材的条数（movie / still）。筛选弹层用来显示选项徽标。 */
    public static int countGenre(SQLiteDatabase db, String genre) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM lut WHERE genre_code=?", new String[]{genre})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    /**
     * 分面计数：在【除该维度外】的已选条件下，这个维度每个值还剩多少条。
     *
     * 【为什么必须这样算】
     * 之前弹层显示的是全局条数 —— 选「照片」后 VLOG 仍写着 94，
     * 而这些格调是互斥的（VLOG 只存在于视频、其余 12 个只存在于照片），
     * 于是「照片 + VLOG」实际命中 0 枚，列表一片空白，
     * 用户根本分不清是"没结果"还是"页面坏了"。
     *
     * 正确的口径是标准的分面搜索：
     * 统计某维度时，要应用其它维度的已选条件，但不应用本维度自己的。
     * 这样「选照片后 VLOG 显示 0」，用户一眼就知道这个组合是空的。
     */
    public static Map<String, Integer> facetCounts(SQLiteDatabase db, Store.Filter f, String dim) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<String> p = new ArrayList<>();
        if (!Store.FACET_GENRE.equals(dim))   in(w, p, "genre_code", f.genres, false);
        if (!Store.FACET_STYLE.equals(dim))   in(w, p, "UPPER(photo_style)", f.styles, true);
        if (!Store.FACET_COUNTRY.equals(dim)) in(w, p, "country_code", f.countries, true);
        if (f.enrichedOnly) w.append(" AND enriched=1");

        String col;
        if (Store.FACET_GENRE.equals(dim))        col = "genre_code";
        else if (Store.FACET_STYLE.equals(dim))   col = "UPPER(photo_style)";
        else                                col = "country_code";

        Map<String, Integer> m = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery(
                "SELECT " + col + ", COUNT(*) FROM lut" + w + " GROUP BY 1", 
                p.toArray(new String[0]))) {
            while (c.moveToNext()) {
                String k = nz(c.getString(0));
                if (!k.isEmpty()) m.put(k, c.getInt(1));
            }
        }
        return m;
    }
    /**
     * 分组搜索。筛选条件照常生效。
     *
     * byName 用 rank 排序（前缀 > 包含，同档按下载量）；
     * byDesc 内部按下载量排序。
     */
    public static Store.Grouped searchGrouped(SQLiteDatabase db, Store.Filter f, int limitPerGroup) {
        Store.Grouped g = new Store.Grouped();
        String kw = f.keyword == null ? "" : f.keyword.trim();
        if (kw.isEmpty()) return g;

        String pre = kw + "%";
        String con = "%" + kw + "%";

        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<String> fp = new ArrayList<>();
        where(f, w, fp);

        // ---- A: 标题 / 创作者 / code 命中 ----
        List<String> a = new ArrayList<>();
        a.add(pre);              // rank 0
        a.add(pre);              // rank 1
        a.add(con);              // rank 2
        a.add(con);              // rank 3
        a.add(con);              // rank 4
        a.addAll(fp);
        a.add(con);              // WHERE title
        a.add(con);              // WHERE creator
        a.add(con);              // WHERE code
        a.add(String.valueOf(limitPerGroup));

        String sqlA = "SELECT " + COLS + ", CASE"
                + " WHEN title LIKE ? THEN 0"
                + " WHEN creator_name LIKE ? THEN 1"
                + " WHEN title LIKE ? THEN 2"
                + " WHEN creator_name LIKE ? THEN 3"
                + " WHEN code LIKE ? THEN 4"
                + " ELSE 5 END AS rank"
                + " FROM lut" + w
                + " AND (title LIKE ? OR creator_name LIKE ? OR code LIKE ?)"
                + " ORDER BY rank ASC, downloads DESC LIMIT ?";
        try (Cursor c = db.rawQuery(sqlA, a.toArray(new String[0]))) {
            while (c.moveToNext()) g.byName.add(read(c));
        }

        // ---- B: 仅简介命中（排除掉 A 里已经有的）----
        List<String> b2 = new ArrayList<>();
        b2.addAll(fp);
        b2.add(con);   // overview LIKE
        b2.add(con);   // NOT title
        b2.add(con);   // NOT creator
        b2.add(con);   // NOT code
        b2.add(String.valueOf(limitPerGroup));

        String sqlB = "SELECT " + COLS + " FROM lut" + w
                + " AND overview LIKE ?"
                + " AND NOT (title LIKE ? OR creator_name LIKE ? OR code LIKE ?)"
                + " ORDER BY downloads DESC LIMIT ?";
        try (Cursor c = db.rawQuery(sqlB, b2.toArray(new String[0]))) {
            while (c.moveToNext()) g.byDesc.add(read(c));
        }

        return g;
    }
}
