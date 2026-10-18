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
public class Store extends Db {

    /**
     * 表结构与迁移都在 {@link Db}（父类）里。这里只放领域读写：
     * LUT 的查询/排行/分面统计，以及 dl 表的增删查。
     *
     * 为什么用「继承」而不是「持有 + 门面转发」：调用点（`MainActivity` 里 40 处
     * `store.ranking(...)` / `store.byCode(...)`）**一个都不用改**，diff 里全是搬移，
     * review 时只需要看 SQL 有没有搬家搬丢条件。
     */
    public Store(Context ctx) { super(ctx); }

    // ======================================================== LUT 读：转发给 LutSql
    // 本类只留"对外契约"（同名、同签名），SQL 全在 LutSql 里。

    public List<Lut> query(Filter f) { return LutSql.query(getReadableDatabase(), f); }

    public int countPrefix(String kw) {
        return LutSql.countPrefix(getReadableDatabase(), kw);
    }

    public List<Lut> suggest(String query, int limit) {
        return LutSql.suggest(getReadableDatabase(), query, limit);
    }

    public int count() { return LutSql.count(getReadableDatabase()); }

    public int enrichedCount() { return LutSql.enrichedCount(getReadableDatabase()); }

    /** 全部 code（按下载量倒序）。语言切换后要整体重抓详情时用。 */
    public List<String> allCodes() {
        return LutSql.allCodes(getReadableDatabase());
    }

    public List<String> pendingEnrich(int max) {
        return LutSql.pendingEnrich(getReadableDatabase(), max);
    }

    public int pendingEnrichCount() {
        return LutSql.pendingEnrichCount(getReadableDatabase());
    }

    public Map<String, int[]> styleStats() {
        return LutSql.styleStats(getReadableDatabase());
    }

    public List<String[]> countryCounts() {
        return LutSql.countryCounts(getReadableDatabase());
    }

    public List<Lut> ranking(int limit, String genre) {
        return LutSql.ranking(getReadableDatabase(), limit, genre);
    }

    public List<Lut> rankingBy(int limit, Filter f) {
        return LutSql.rankingBy(getReadableDatabase(), limit, f);
    }

    public Lut byCode(String code) { return LutSql.byCode(getReadableDatabase(), code); }

    public List<Creator> topCreators(int limit, int minCount, Filter f) {
        return LutSql.topCreators(getReadableDatabase(), limit, minCount, f);
    }

    public int countGenre(String genre) {
        return LutSql.countGenre(getReadableDatabase(), genre);
    }

    public Map<String, Integer> facetCounts(Filter f, String dim) {
        return LutSql.facetCounts(getReadableDatabase(), f, dim);
    }

    public Grouped searchGrouped(Filter f, int limitPerGroup) {
        return LutSql.searchGrouped(getReadableDatabase(), f, limitPerGroup);
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


    /** 库里是否已有可搜索的数据。 */
    public boolean hasData() { return count() > 0; }

    /** 是否已完成全量格调补齐。 */
    public boolean isFullyEnriched() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM lut WHERE enriched=0", null)) {
            return c.moveToFirst() && c.getInt(0) == 0 && count() > 0;
        }
    }

    // ============================================================ 首页 / 榜单

    /** 创作者榜一行：code / 名字 / 条数 / 总下载量。 */
    public static class Creator {
        public String code = "";
        public String name = "";
        public int count = 0;
        public int downloads = 0;
    }

    // ------------------------------------------------------------ 分面计数

    public static final String FACET_GENRE = "genre";
    public static final String FACET_STYLE = "style";
    public static final String FACET_COUNTRY = "country";

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

    // ======================================================== 下载表：转发给 DlSql

    /**
     * 记录一次下载（给不在目录里的条目用 —— 比如松影 25 周年那套，它没有 Lut 对象）。
     */
    public void recordDownload(String code, String title, String creatorName,
                               String cubeName, int bytes, String uri, String displayPath) {
        Lut l = new Lut();
        l.code = code;
        l.title = title;
        l.creatorName = creatorName;
        recordDownload(l, cubeName, bytes, uri, displayPath);
    }

    public void recordDownload(Lut l, String cubeName, int bytes,
                               String uri, String displayPath) {
        DlSql.recordDownload(getWritableDatabase(), l, cubeName, bytes, uri, displayPath);
    }

    public List<Dl> listDownloads() {
        return DlSql.listDownloads(getReadableDatabase());
    }

    public void removeDownload(String code) {
        DlSql.removeDownload(getWritableDatabase(), code);
    }

    public void clearDownloads() {
        DlSql.clearDownloads(getWritableDatabase());
    }

    public int dlCount() {
        return DlSql.dlCount(getReadableDatabase());
    }
}
