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
 */
public final class Syncer {

    /** 端点数：实测目录全量 199 条，每页 100 两页就够。留点余量到 5 页。 */
    private static final int PAGE = 100;
    private static final int MAX_PAGES = 5;

    private Syncer() {}

    public interface Step {
        void onPage(int got, int total);
    }

    /**
     * 拉全量目录并写入本地库。
     *
     * @return 本次拉到的条目数
     */
    public static int syncCatalog(Store store, Api.Progress p, Step step)
            throws IOException {
        int got = 0;
        for (int i = 0; i < MAX_PAGES; i++) {
            int off = i * PAGE;
            List<Lut> page = Api.filter("en", PAGE, off, null, null, null, p);
            if (page.isEmpty()) break;
            store.upsertList(page);
            got += page.size();
            if (step != null) step.onPage(got, got + (page.size()));
            if (page.size() < PAGE) break;      // 不足一页 = 到底了
        }
        return got;
    }
}
