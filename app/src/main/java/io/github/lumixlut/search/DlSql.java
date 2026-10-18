package io.github.lumixlut.search;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载记录表 `dl` 的全部读写。
 *
 * 表结构在 {@link Db}（`createDlTable`），LUT 的查询在 {@link LutSql}；
 * 这里只管"本工具下载过哪些 .cube"。
 *
 * 为什么单独一个表而不是每次扫 MediaStore：扫盘在 Android 11+ 受 scoped storage
 * 限制、也不记得这枚 .cube 对应哪条 LUT（缩略图/格调都没法显示）。
 *
 * 方法都是静态、显式接 `SQLiteDatabase`；`Store` 里保留同名薄封装，
 * 所以 `DownloadsActivity` 的调用点一个字都不用改。
 */
public final class DlSql {

    private DlSql() { }

    /**
     * 记录一次成功下载。
     *
     * 同一 code 重复下载时覆盖（文件名可能因改名而不同，以最后一次为准）。
     */
    static void recordDownload(SQLiteDatabase db, Lut l, String cubeName, int bytes,
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
        db.insertWithOnConflict("dl", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 全部下载记录，最近的在最前。 */
    static List<Store.Dl> listDownloads(SQLiteDatabase db) {
        List<Store.Dl> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT code,cube_name,bytes,uri,display_path,saved_at,title,creator_name"
                        + " FROM dl ORDER BY saved_at DESC", null)) {
            while (c.moveToNext()) {
                Store.Dl d = new Store.Dl();
                d.code = LutSql.nz(c.getString(0));
                d.cubeName = LutSql.nz(c.getString(1));
                d.bytes = c.getInt(2);
                d.uri = LutSql.nz(c.getString(3));
                d.displayPath = LutSql.nz(c.getString(4));
                d.savedAt = c.getLong(5);
                d.title = LutSql.nz(c.getString(6));
                d.creatorName = LutSql.nz(c.getString(7));
                out.add(d);
            }
        }
        return out;
    }

    static void removeDownload(SQLiteDatabase db, String code) {
        db.delete("dl", "code=?", new String[]{code});
    }

    static void clearDownloads(SQLiteDatabase db) {
        db.delete("dl", null, null);
    }

    static int dlCount(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM dl", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
