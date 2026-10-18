package io.github.lumixlut.search;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 松影俱乐部专题页（`panasonicshuma.cn/RealtimeLUTLibrary/list.aspx?lcid=…`）的最小访问层。
 *
 * ## 为什么不能只认 lcid=113
 *
 * 「松下25周年专属及官方lut」专栏的四个子分类是**四个独立栏目页**：
 *
 * | 子分类 | lcid | 条目数 |
 * |---|---|---|
 * | 25周年专属 | 113 | 3 |
 * | 人像 | 32 | 23 |
 * | 户外 | 33 | 23 |
 * | 室内 | 34 | 8 |
 *
 * 第一版所有下载都去抓 113，于是人像/户外/室内一律报「专题页里没找到这条」——
 * 页面本身没改版，是**找错了页**。这里按分类查表。
 *
 * ## 直链的时效
 *
 * `https://oss.panasonicshuma.cn/<202610180420>/<hash>/…zip` 里的第一段是**时间窗签名**，
 * 过期就 403（见 HANDOFF §5.4 坑 2）。所以每次下载都现抓当前页面，链接绝不写死。
 * OSS 还开了防盗链：请求必须带 Referer（就是该分类页本身）。
 */
public final class Songying {

    public static final String SITE = "https://www.panasonicshuma.cn";

    /** 子分类，顺序即界面上的 chip 顺序。 */
    public static final String[] CATS = {"25周年专属", "人像", "户外", "室内"};

    /** 与 {@link #CATS} 一一对应的栏目 id。 */
    private static final String[] LCID = {"113", "32", "33", "34"};

    private static final Pattern ZIP_LINK =
            Pattern.compile("href=\"(https://oss\\.panasonicshuma\\.cn/[^\"]+\\.zip)\"");

    private Songying() { }

    /** 子分类对应的栏目页 URL；未知分类退回 113（25 周年）。 */
    public static String pageUrl(String cat) {
        for (int i = 0; i < CATS.length; i++) {
            if (CATS[i].equals(cat)) {
                return SITE + "/RealtimeLUTLibrary/list.aspx?lcid=" + LCID[i];
            }
        }
        return SITE + "/RealtimeLUTLibrary/list.aspx?lcid=" + LCID[0];
    }

    /**
     * 抓分类页，按预览图文件名定位条目块，取块内第一个 zip 直链。
     *
     * 页面结构（2026-10-18 实测）：
     * <pre>
     *   &lt;li data-small="/uploadfiles/2024/07/20240709162027993.jpg"&gt;
     *     &lt;a href="https://oss.panasonicshuma.cn/&lt;签名&gt;/….zip" download …&gt;&lt;/a&gt;
     *     &lt;figure class="Ispic" style="background-image: url('/uploadfiles/…')"&gt;&lt;/figure&gt;
     *     &lt;div class="txt"&gt;&lt;h2&gt;by 薛璐瑶 冷暖色调&lt;/h2&gt;…&lt;/div&gt;
     *   &lt;/li&gt;
     * </pre>
     *
     * 按预览图名定位比按标题稳：标题里带 `by`、作者名，文案一改就散。
     *
     * @param page        分类页 URL（同时用作 Referer）
     * @param previewPath 清单里的预览图路径（只取文件名比对，目录层级用不上）
     * @throws IOException 抓不到页面、或页面里找不到这一条
     */
    public static String resolveZip(String page, String previewPath) throws IOException {
        String html = Downloader.httpGet(page, page);
        String name = baseName(previewPath);
        if (name.isEmpty()) throw new IOException("这条没有预览图文件名，无法定位下载链接");
        int at = html.indexOf(name);
        if (at < 0) throw new IOException("专题页里没找到这条（页面可能改版了）");
        int start = html.lastIndexOf("<li", at);
        int end = html.indexOf("</li>", at);
        if (start < 0 || end < 0) throw new IOException("专题页结构变了（找不到条目块）");
        Matcher m = ZIP_LINK.matcher(html.substring(start, end));
        if (!m.find()) throw new IOException("条目块里没有 zip 链接");
        return m.group(1);
    }

    static String baseName(String path) {
        if (path == null) return "";
        int s = path.lastIndexOf('/');
        return s < 0 ? path.trim() : path.substring(s + 1).trim();
    }
}
