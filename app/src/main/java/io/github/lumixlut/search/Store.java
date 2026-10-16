package io.github.lumixlut.search;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地目录库。
 *
 * 因为服务端【无全文检索】且【不支持排序】，检索与排序一律在本地做。
 * 库里的字段全部对应服务端真实响应字段，无推断列。
 */
public class Store extends SQLiteOpenHelper {

    private static final String NAME = "catalog.db";
    /**
     * v2：新增 thumb_before / thumb_after（滑动对比用的官方样张）。
     * v3：新增 dl 表（本工具下载过的 .cube 记录）。
     */
    private static final int VERSION = 3;

    public Store(Context ctx) { super(ctx, NAME, null, VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE lut ("
                + "code TEXT PRIMARY KEY,"
                + "title TEXT,"
                + "creator_code TEXT,"
                + "creator_name TEXT,"
                + "genre_code TEXT,"
                + "photo_style TEXT,"
                + "country_code TEXT,"
                + "downloads INTEGER DEFAULT 0,"
                + "overview TEXT,"
                + "thumb_path TEXT,"
                + "thumb_before TEXT,"
                + "thumb_after TEXT,"
                + "enriched INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX ix_dl ON lut(downloads DESC)");
        db.execSQL("CREATE INDEX ix_style ON lut(photo_style)");
        createDlTable(db);
    }

    private static void createDlTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dl ("
                + "code TEXT PRIMARY KEY,"
                + "cube_name TEXT,"
                + "bytes INTEGER DEFAULT 0,"
                + "uri TEXT,"
                + "display_path TEXT,"
                + "saved_at INTEGER DEFAULT 0,"
                + "title TEXT,"
                + "creator_name TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_dl_time ON dl(saved_at DESC)");
    }

    /**
     * 逐版本迁移，全部用 ALTER/CREATE，不 DROP。
     * 重建会把用户已同步/已补齐的整个目录清空，几百条数据白下。
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            try { db.execSQL("ALTER TABLE lut ADD COLUMN thumb_before TEXT"); }
            catch (Exception ignored) { }
            try { db.execSQL("ALTER TABLE lut ADD COLUMN thumb_after TEXT"); }
            catch (Exception ignored) { }
            try { db.execSQL("UPDATE lut SET thumb_after = thumb_path WHERE thumb_after IS NULL"); }
            catch (Exception ignored) { }
        }
        if (o < 3) {
            try { createDlTable(db); } catch (Exception ignored) { }
        }
    }

    /** 批量写入/更新列表数据。不会覆盖已有的 photo_style / overview。 */
    public void upsertList(List<Lut> list) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Lut l : list) {
                ContentValues v = new ContentValues();
                v.put("code", l.code);
                v.put("title", l.title);
                v.put("creator_code", l.creatorCode);
                v.put("creator_name", l.creatorName);
                if (!l.genreCode.isEmpty()) v.put("genre_code", l.genreCode);
                if (!l.countryCode.isEmpty()) v.put("country_code", l.countryCode);
                v.put("downloads", l.downloads);
                v.put("thumb_path", l.thumbPath);
                // 只插入新 code；已存在的只更新下载量等易变字段
                db.execSQL("INSERT INTO lut (code,title,creator_code,creator_name,"
                                + "genre_code,country_code,downloads,thumb_path,"
                                + "thumb_before,thumb_after,enriched)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,0)"
                                + " ON CONFLICT(code) DO UPDATE SET"
                                + " title=excluded.title,"
                                + " creator_code=excluded.creator_code,"
                                + " creator_name=excluded.creator_name,"
                                + " genre_code=COALESCE(excluded.genre_code, lut.genre_code),"
                                + " country_code=COALESCE(excluded.country_code, lut.country_code),"
                                + " downloads=MAX(excluded.downloads, lut.downloads),"
                                + " thumb_path=excluded.thumb_path,"
                                + " thumb_after=excluded.thumb_after,"
                                + " thumb_before=COALESCE(NULLIF(excluded.thumb_before,''), lut.thumb_before)",
                        new Object[]{l.code, l.title, l.creatorCode, l.creatorName,
                                l.genreCode.isEmpty() ? null : l.genreCode,
                                l.countryCode.isEmpty() ? null : l.countryCode,
                                l.downloads, l.thumbPath,
                                l.thumbBefore.isEmpty() ? null : l.thumbBefore,
                                l.thumbAfter.isEmpty() ? null : l.thumbAfter});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 写入详情（补齐格调与简介）。 */
    public void upsertDetail(Lut l) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("photo_style", l.photoStyle);
        v.put("overview", l.overview);
        v.put("enriched", 1);
        if (!l.title.isEmpty()) v.put("title", l.title);
        if (l.downloads > 0) v.put("downloads", l.downloads);
        // 详情接口是唯一会返回 path_original 的地方，所以这里必须写
        if (!l.thumbBefore.isEmpty()) v.put("thumb_before", l.thumbBefore);
        if (!l.thumbAfter.isEmpty()) v.put("thumb_after", l.thumbAfter);
        db.update("lut", v, "code=?", new String[]{l.code});
    }

    // ------------------------------------------------------------ 查询

    /** 排序方式。服务端不支持排序，一律本地做。 */
    public static final int SORT_DOWNLOADS = 0;   // 下载量降序
    public static final int SORT_TITLE = 1;       // 名称

    /**
     * 检索条件。
     *
     * 题材/格调/国家都是【多选】—— 同一维度内 OR，维度之间 AND。
     * 例：题材选(视频) + 格调选(LAND,PORT) → 视频 且 格调是 LAND 或 PORT。
     */
    public static class Filter {
        /**
         * 字符串字段一律默认空串，不是 null。
         *
         * 之前留成 null 导致过一次必崩：调用方按「有就 trim / isEmpty」写，
         * 一进页面就 NPE。空串让所有判空写法天然安全。
         */
        public String keyword = "";
        public final List<String> genres = new ArrayList<>();
        public final List<String> styles = new ArrayList<>();
        public final List<String> countries = new ArrayList<>();
        public String creator = "";
        public boolean enrichedOnly;
        public int sort = SORT_DOWNLOADS;
        public int limit = 500;

        public boolean isEmpty() {
            return genres.isEmpty() && styles.isEmpty() && countries.isEmpty()
                    && creator.isEmpty() && keyword.trim().isEmpty();
        }

        public int activeCount() {
            return genres.size() + styles.size() + countries.size();
        }
    }

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
    private static void where(Filter f, StringBuilder w, List<String> p) {
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
    private static String orderBy(Filter f, boolean hasKeyword) {
        String tie = (f.sort == SORT_TITLE)
                ? "title COLLATE NOCASE ASC"
                : "downloads DESC";
        return hasKeyword ? " ORDER BY rank ASC, " + tie : " ORDER BY " + tie;
    }

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
    public List<Lut> query(Filter f) {
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
        try (Cursor c = getReadableDatabase()
                .rawQuery(sql, args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    /** 关键词为前缀命中的条数（用于状态栏提示）。 */
    public int countPrefix(String kw) {
        if (kw == null || kw.trim().isEmpty()) return 0;
        String pre = kw.trim() + "%";
        try (Cursor c = getReadableDatabase().rawQuery(
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
    public List<Lut> suggest(String query, int limit) {
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
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{
                prefix, prefix, contains, contains, String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    /** 库里是否已有可搜索的数据。 */
    public boolean hasData() { return count() > 0; }

    /** 是否已完成全量格调补齐。 */
    public boolean isFullyEnriched() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM lut WHERE enriched=0", null)) {
            return c.moveToFirst() && c.getInt(0) == 0 && count() > 0;
        }
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

    private static String nz(String s) { return s == null ? "" : s; }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM lut", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int enrichedCount() {
        try (Cursor c = getReadableDatabase()
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
    public List<String> pendingEnrich(int max) {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT code FROM lut"
                        + " WHERE enriched=0 OR length(COALESCE(thumb_before,''))=0"
                        + " ORDER BY downloads DESC LIMIT ?",
                new String[]{String.valueOf(max)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** 待补条数 —— 与 pendingEnrich 的口径必须一致，否则弹窗数字对不上。 */
    public int pendingEnrichCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM lut"
                        + " WHERE enriched=0 OR length(COALESCE(thumb_before,''))=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** 格调分布：[格调, 条数, 总下载量]。 */
    public Map<String, int[]> styleStats() {
        Map<String, int[]> m = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(UPPER(photo_style),'?'), COUNT(*), COALESCE(SUM(downloads),0)"
                        + " FROM lut WHERE enriched=1 GROUP BY 1 ORDER BY 2 DESC", null)) {
            while (c.moveToNext()) m.put(c.getString(0), new int[]{c.getInt(1), c.getInt(2)});
        }
        return m;
    }

    // ============================================================ 首页 / 榜单

    /** 创作者榜一行：code / 名字 / 条数 / 总下载量。 */
    public static class Creator {
        public String code = "";
        public String name = "";
        public int count = 0;
        public int downloads = 0;
    }

    /**
     * 下载榜。genre 为空则不分题材。
     *
     * 用 MAX(downloads) 而不是 SUM —— 同一条 LUT 不该因多题材被重复计数。
     */
    public List<Lut> ranking(int limit, String genre) {
        String w = " WHERE 1=1";
        List<String> args = new ArrayList<>();
        if (genre != null && !genre.isEmpty()) {
            w += " AND genre_code=?";
            args.add(genre);
        }
        args.add(String.valueOf(limit));
        List<Lut> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLS + " FROM lut" + w + " ORDER BY downloads DESC LIMIT ?",
                args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    /** 按 code 取一条。搜索页回传 code 后用这个还原成完整记录。 */
    public Lut byCode(String code) {
        if (code == null || code.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().rawQuery(
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
    public List<Creator> topCreators(int limit, int minCount) {
        List<Creator> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT creator_code, MAX(creator_name), COUNT(*), SUM(downloads)"
                        + " FROM lut WHERE length(COALESCE(creator_code,''))>0"
                        + " GROUP BY creator_code HAVING COUNT(*)>=?"
                        + " ORDER BY SUM(downloads) DESC LIMIT ?",
                new String[]{String.valueOf(minCount), String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Creator k = new Creator();
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
    public int countGenre(String genre) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM lut WHERE genre_code=?", new String[]{genre})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ------------------------------------------------------------ 分面计数

    public static final String FACET_GENRE = "genre";
    public static final String FACET_STYLE = "style";
    public static final String FACET_COUNTRY = "country";

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
    public Map<String, Integer> facetCounts(Filter f, String dim) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<String> p = new ArrayList<>();
        if (!FACET_GENRE.equals(dim))   in(w, p, "genre_code", f.genres, false);
        if (!FACET_STYLE.equals(dim))   in(w, p, "UPPER(photo_style)", f.styles, true);
        if (!FACET_COUNTRY.equals(dim)) in(w, p, "country_code", f.countries, true);
        if (f.enrichedOnly) w.append(" AND enriched=1");

        String col;
        if (FACET_GENRE.equals(dim))        col = "genre_code";
        else if (FACET_STYLE.equals(dim))   col = "UPPER(photo_style)";
        else                                col = "country_code";

        Map<String, Integer> m = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
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
     * 分组搜索的结果。
     *
     * 分成两组而不是一组，是因为「标题命中」和「只在简介里出现」的
     * 相关性差很多 —— 混在一起会让真正想找的那条被淹没。
     */
    public static class Grouped {
        public final List<Lut> byName = new ArrayList<>();   // 标题/创作者/code 命中
        public final List<Lut> byDesc = new ArrayList<>();   // 仅简介命中
        public int total() { return byName.size() + byDesc.size(); }
    }

    /**
     * 分组搜索。筛选条件照常生效。
     *
     * byName 用 rank 排序（前缀 > 包含，同档按下载量）；
     * byDesc 内部按下载量排序。
     */
    public Grouped searchGrouped(Filter f, int limitPerGroup) {
        Grouped g = new Grouped();
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
        try (Cursor c = getReadableDatabase().rawQuery(sqlA, a.toArray(new String[0]))) {
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
        try (Cursor c = getReadableDatabase().rawQuery(sqlB, b2.toArray(new String[0]))) {
            while (c.moveToNext()) g.byDesc.add(read(c));
        }

        return g;
    }

    /** 各国条数（按条数降序），用于首页的按国家浏览。 */
    public List<String[]> countryCounts() {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT country_code, COUNT(*) FROM lut"
                        + " WHERE length(COALESCE(country_code,''))>0"
                        + " GROUP BY 1 ORDER BY 2 DESC", null)) {
            while (c.moveToNext()) out.add(new String[]{nz(c.getString(0)), String.valueOf(c.getInt(1))});
        }
        return out;
    }

    // ============================================================ 下载记录

    /** 本工具下载过的一枚 .cube。 */
    public static class Dl {
        public String code = "";
        public String cubeName = "";
        public int bytes = 0;
        public String uri = "";
        public String displayPath = "";
        public long savedAt = 0L;
        public String title = "";
        public String creatorName = "";

        /** 供列表显示的一行。 */
        public String line1() { return cubeName.isEmpty() ? code : cubeName; }

        public String line2() {
            StringBuilder sb = new StringBuilder();
            if (!title.isEmpty()) sb.append(title);
            if (!creatorName.isEmpty()) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(creatorName);
            }
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(String.format("%,d", bytes)).append(" B");
            return sb.toString();
        }
    }

    /**
     * 记录一次成功下载。
     *
     * 同一 code 重复下载时覆盖（文件名可能因改名而不同，以最后一次为准）。
     * 这个表是「我的下载」的唯一来源 —— 比每次去扫 MediaStore 可靠，
     * 也能顺带记住它对应哪枚 LUT（缩略图/格调都能显示）。
     */
    public void recordDownload(Lut l, String cubeName, int bytes,
                               String uri, String displayPath) {
        ContentValues v = new ContentValues();
        v.put("code", l.code);
        v.put("cube_name", cubeName);
        v.put("bytes", bytes);
        v.put("uri", uri == null ? "" : uri);
        v.put("display_path", displayPath == null ? "" : displayPath);
        v.put("saved_at", System.currentTimeMillis());
        v.put("title", l.title);
        v.put("creator_name", l.creatorName);
        getWritableDatabase().insertWithOnConflict(
                "dl", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 全部下载记录，最近的在最前。 */
    public List<Dl> listDownloads() {
        List<Dl> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT code,cube_name,bytes,uri,display_path,saved_at,title,creator_name"
                        + " FROM dl ORDER BY saved_at DESC", null)) {
            while (c.moveToNext()) {
                Dl d = new Dl();
                d.code = nz(c.getString(0));
                d.cubeName = nz(c.getString(1));
                d.bytes = c.getInt(2);
                d.uri = nz(c.getString(3));
                d.displayPath = nz(c.getString(4));
                d.savedAt = c.getLong(5);
                d.title = nz(c.getString(6));
                d.creatorName = nz(c.getString(7));
                out.add(d);
            }
        }
        return out;
    }

    public void removeDownload(String code) {
        getWritableDatabase().delete("dl", "code=?", new String[]{code});
    }

    public void clearDownloads() {
        getWritableDatabase().delete("dl", null, null);
    }

    public int dlCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM dl", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
