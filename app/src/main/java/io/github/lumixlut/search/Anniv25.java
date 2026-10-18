package io.github.lumixlut.search;

import java.util.ArrayList;
import java.util.List;

/**
 * 松下 25 周年专属 LUT（松影俱乐部专题，`list.aspx?lcid=113`）。
 *
 * ## 为什么是硬编码的静态数据
 *
 * 这套 LUT **不在 LUMIX Lab 官方目录里**（服务端 199 条与本地库逐条对齐，`LeBox / 千禧回声 / Y2K`
 * 零命中）。它是松下中国俱乐部单独发的专题：页面只有「标题 + 作者 + 中文描述 + OSS 直链」，
 * 没有 code、没有官方样张、没有格调/国家/下载量字段。
 *
 * 所以这里不假装它是目录条目：4 个页面条目里有 2 条是德松同一个包（warm / cold 两版预览），
 * 合并成 **3 张卡**；每条只保存页面上的真实信息。
 *
 * ## 素材
 *
 * 页面上的预览图是「上下对比图」（上=原图、中=横幅、下=LUT 效果），我们离线处理过：
 * 裁掉左上角「基于 STD」角标和中间横幅，拆成 before / after，另存 1:1 卡片图。
 * 金标由 {@link Anniv25Ui#badge} 现画（不冒用官方徽章）。
 */
public final class Anniv25 {

    public static final class Item {
        /** 资源前缀，同时用作下载记录的 code。 */
        public final String key;
        public final String title;
        public final String creator;
        /** 原图角标上的基线（「基于 STD」→ STD）；页面没写就空。 */
        public final String baseStyle;
        public final String desc;
        /**
         * 页面里这条目的预览图文件名 —— **用来在运行时定位当前有效的下载直链**。
         *
         * 为什么不存 zip 直链：OSS 路径里的 `202610180252` 是**时间窗签名**，
         * 旧的（11 分钟前那次页面加载生成的）实测直接 403。所以每次下载都重新抓页面解析。
         */
        public final String previewName;
        /** 包里应该有几个 .cube（德松那包是 warm/cold 两版）。 */
        public final int cubeCount;

        Item(String key, String title, String creator, String baseStyle,
             String desc, String previewName, int cubeCount) {
            this.key = key;
            this.title = title;
            this.creator = creator;
            this.baseStyle = baseStyle;
            this.desc = desc;
            this.previewName = previewName;
            this.cubeCount = cubeCount;
        }

        public String cardAsset()   { return "anniv25/" + key + "_card.jpg"; }
        public String beforeAsset() { return "anniv25/" + key + "_before.jpg"; }
        public String afterAsset()  { return "anniv25/" + key + "_after.jpg"; }
    }

    private Anniv25() { }

    /** 下载时随包带的 Referer —— 松影 OSS 开了防盗链，不带就 403。 */
    public static final String REFERER = "https://www.panasonicshuma.cn/RealtimeLUTLibrary/list.aspx?lcid=113";

    /** 专题原页面（「更多」按钮跳这里）。 */
    public static final String SOURCE_URL = REFERER;

    public static final List<Item> ITEMS = new ArrayList<>();

    static {
        ITEMS.add(new Item(
                "a1",
                "AgfaPhoto LeBox",
                "兔小呆",
                "STD",
                "爱克发一次性胶片模拟。整体色调偏冷，带轻微青蓝倾向，高光与阴影处尤其明显，"
                        + "画面干净冷静。蓝绿还原更突出，红橙相对柔和；自然光下肤色偏冷白，开闪光灯时略偏暖，"
                        + "带胶片特有的柔焦质感。对比度中等偏高，适合街头与城市题材，颗粒建议机内打开。",
                "微信图片_20260325164720.png",
                1));

        ITEMS.add(new Item(
                "a2",
                "千禧回声",
                "德松",
                "STD",
                "以 LUMIX CCD 相机的成像风格为灵感，提炼其色彩特征并赋予画面千禧年复古质感。"
                        + "压缩高光与阴影，对比度中等偏低，模拟早期数码的高光溢出感，光线充足时表现最好。"
                        + "官方包内含 warm / cold 两版，按喜好与场景选择（两个都会保存下来）。",
                "微信图片_20260325164723.png",
                2));

        ITEMS.add(new Item(
                "a3",
                "Y2K",
                "plady",
                "STD",
                "参考早期 CCD 数码相机的成像风格，整体偏青，高光里加了一点暖色，"
                        + "清透又有阳光温度。高光略溢出、暗部适度压缩，典型 CCD 层次。"
                        + "适合阳光环境与黄昏人像，搭配机内彩色颗粒直出更佳。",
                "组 1.png",
                1));
    }

    /**
     * 抓专题页，找出这个条目**当前**的 zip 直链。
     *
     * 页面结构（2026-10-18）：
     *   <li data-small="/uploadfiles/2026/03/<预览图>">
     *       <a href="https://oss.panasonicshuma.cn/<签名>/....zip"> ...
     *
     * 所以按预览图文件名定位那个 `<li>`，再从块里取第一个 zip 链接 —— 比按标题匹配稳
     * （标题里带 `by`、作者名，页面改文案就散）。
     *
     * @throws java.io.IOException 抓不到页面，或页面里找不到这条（结构变了）
     */
    public static String resolveZip(String previewName) throws java.io.IOException {
        String html = Downloader.httpGet(SOURCE_URL, null);
        int at = html.indexOf(previewName);
        if (at < 0) throw new java.io.IOException("专题页里没找到这条（页面可能改版了）");
        int start = html.lastIndexOf("<li", at);
        int end = html.indexOf("</li>", at);
        if (start < 0 || end < 0) throw new java.io.IOException("专题页结构变了（找不到条目块）");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("href=\"(https://oss\\.panasonicshuma\\.cn/[^\"]+\\.zip)\"")
                .matcher(html.substring(start, end));
        if (!m.find()) throw new java.io.IOException("条目块里没有 zip 链接");
        return m.group(1);
    }

    public static Item byKey(String key) {
        for (Item it : ITEMS) if (it.key.equals(key)) return it;
        return null;
    }
}
