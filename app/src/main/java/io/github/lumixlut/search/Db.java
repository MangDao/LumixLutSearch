package io.github.lumixlut.search;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 库的「物理层」：只有打开、建表、逐版本迁移。
 *
 * 从这里劈出来的原因：`Store` 原来同时管
 *  ① 表结构与迁移　② SQL 拼装　③ LUT 领域读写　④ 下载表读写
 * 改一处要在 700 多行里找四个地方。现在表结构只在这个文件里，
 * `Store` 继承它，**所有调用点一个都不用改**（`new Store(ctx)` 与
 * `store.query(...)` 等签名不变）。
 *
 * 注意：迁移一律 ALTER/CREATE，**不要 DROP** —— 重建会把用户已同步、
 * 已补齐的整个目录清空，几百条数据白下。
 */
public class Db extends SQLiteOpenHelper {

    static final String NAME = "catalog.db";

    /**
     * v2：新增 thumb_before / thumb_after（滑动对比用的官方样张）。
     * v3：新增 dl 表（本工具下载过的 .cube 记录）。
     */
    static final int VERSION = 3;

    public Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
    }

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

    static void createDlTable(SQLiteDatabase db) {
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
}
