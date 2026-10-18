package io.github.lumixlut.search;

import java.io.IOException;
import java.util.List;

/**
 * 目录同步。
 *
 * 只拉列表（filter），不碰详情 —— 详情那套（格调 + 样张）是逐条 0.8 秒，
 * 199 条要三分钟，不能塞进启动流程。新增条目交给「补格调」按需补。
 *
 * 服务端无全文检索、不支持排序，所以这里的产物只是本地库的数据源；
 * 检索与排序全部由 Store 在本地 SQL 里做。
 *
 * 【为什么按题材分两趟拉 —— 2026-10 实测】
 * 不带 genre_code 的请求，返回条目里【没有 genre 字段】：
 *   code / download_number / title / creator / thumbnail
 * 带 genre_code 的请求，条目里就多一个 genre（{"code":"movie"}）：
 *   code / download_number / title / creator / genre / thumbnail
 * 早先只发一次全局请求，于是 genre_code 一律写成 null，后果是：
 *   首页「视频 0 / 照片 0」、热门卡副标题「VLOG · —」、筛选页题材维度全空。
 * 现在按 movie / still 各翻一遍（题材在服务端是真过滤：movie=94、still=105），
 * 并集仍是全量 199 条，且每条都能拿到题材归属。
 * 老库不用手动清：upsert 里 genre_code 走 COALESCE(excluded, lut)，
 * 新值非空就会把之前的 null 覆盖掉。
 */
public final class Syncer {

    /** 端点数：实测目录全量 199 条，每页 100 两页就够。留点余量到 5 页。 */
    private static final int PAGE = 100;
    private static final int MAX_PAGES = 5;

    /** 题材字典：community/genres 实测就这两个。 */
    private static final String[] GENRES = {"movie", "still"};

    private Syncer() {}

    public interface Step {
        void onPage(int got, int total);
    }

    /**
     * 拉全量目录并写入本地库。
     *
     * 按题材分别翻页，避免丢掉 genre 归属；万一两趟都是 0 条（服务端行为变了），
     * 退回一次全局请求，保证「同步」不会变成"拉回 0 条"。
     *
     * @return 本次拉到的条目数
     */
    public static int syncCatalog(Store store, Api.Progress p, Step step)
            throws IOException {
        int got = 0;
        for (String g : GENRES) {
            got += syncGenre(store, p, step, g, got);
        }
        if (got == 0) {
            got = syncGenre(store, p, step, null, 0);
        }
        return got;
    }

    /**
     * 拉一个题材（genre 为 null 表示不限题材）的全部页。
     *
     * @param already 前面几个题材已经拉到的条数，仅用于进度回调
     * @return 这个题材拉到的条数
     */
    private static int syncGenre(Store store, Api.Progress p, Step step,
                                 String genre, int already) throws IOException {
        int got = 0;
        for (int i = 0; i < MAX_PAGES; i++) {
            int off = i * PAGE;
            List<Lut> page = Api.filter(Api.lang(), PAGE, off, genre, null, null, p);
            if (page.isEmpty()) break;
            store.upsertList(page);
            got += page.size();
            if (step != null) step.onPage(already + got, already + got + page.size());
            if (page.size() < PAGE) break;      // 不足一页 = 这个题材到底了
        }
        return got;
    }
}
