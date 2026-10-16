package io.github.lumixlut.search;

import java.util.HashMap;
import java.util.Map;

/** 格调代号 → 中文含义。 */
public final class Util {

    /**
     * 基础映射取自官方 App 源码 C1415w.java 的 to-VLOG 转换表（权威），
     * 其余按 assets/photoStyleLUT/ 下 21 个 Photo Style 名称补全。
     */
    private static final Map<String, String> STYLE_NAME = new HashMap<>();
    /** 格调中直接对应「拍摄主体 / 显著色调」的项 —— 真实字段，非推断。 */
    private static final Map<String, String> SUBJECT = new HashMap<>();

    static {
        STYLE_NAME.put("VLOG", "V-Log 灰片");
        STYLE_NAME.put("STD", "标准");
        STYLE_NAME.put("NAT", "自然");
        STYLE_NAME.put("LAND", "风景");
        STYLE_NAME.put("PORT", "人像");
        STYLE_NAME.put("FLAT", "平面");
        STYLE_NAME.put("VIVD", "鲜艳");
        STYLE_NAME.put("VIVID", "鲜艳");
        STYLE_NAME.put("709L", "709 Like");
        STYLE_NAME.put("CNEV2", "电影模式V2");
        STYLE_NAME.put("CNED2", "电影模式D2");
        STYLE_NAME.put("CNEA2", "电影模式A2");
        STYLE_NAME.put("LCLAS", "L.经典");
        STYLE_NAME.put("LCLASN", "L.经典新");
        STYLE_NAME.put("LCLASG", "L.经典金");
        STYLE_NAME.put("MONO", "单色");
        STYLE_NAME.put("LMONO", "L.单色");
        STYLE_NAME.put("LMONOD", "L.单色D");
        STYLE_NAME.put("LMONOS", "L.单色S");
        STYLE_NAME.put("LEICAMONO", "徕卡单色");
        STYLE_NAME.put("HLG", "HLG 标准");
        STYLE_NAME.put("HLGS", "HLG 标准");
        STYLE_NAME.put("HLGM", "HLG 单色");

        SUBJECT.put("LAND", "风景");
        SUBJECT.put("PORT", "人像");
        SUBJECT.put("MONO", "黑白");
        SUBJECT.put("LMONO", "黑白");
        SUBJECT.put("LMONOD", "黑白");
        SUBJECT.put("LMONOS", "黑白");
        SUBJECT.put("LEICAMONO", "黑白");
        SUBJECT.put("HLGM", "黑白");
        SUBJECT.put("VIVD", "鲜艳");
        SUBJECT.put("VIVID", "鲜艳");
    }

    private Util() {}

    public static String styleName(String code) {
        if (code == null) return "";
        String v = STYLE_NAME.get(code.toUpperCase());
        return v == null ? "" : v;
    }

    /** 用于格调分布展示的附注，如 " ← 即「风景」"。 */
    public static String styleNote(String code) {
        if (code == null) return "";
        String s = SUBJECT.get(code.toUpperCase());
        return s == null ? "" : "  ← 即「" + s + "」";
    }

    public static String styleLabel(String code) {
        if (code == null || code.isEmpty()) return "未补齐";
        String n = styleName(code);
        return n.isEmpty() ? code : code + "（" + n + "）";
    }
}
