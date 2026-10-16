package io.github.lumixlut.search;

import org.json.JSONArray;
import org.json.JSONObject;

/** 一枚社区 LUT 的记录。字段全部来自服务端真实响应，不做任何推断。 */
public class Lut {

    public String code = "";
    public String title = "";
    public String creatorCode = "";
    public String creatorName = "";
    /** movie = 视频LUT，still = 照片LUT —— 服务端真字段，就是这个区分 */
    public String genreCode = "";
    /** 格调（Photo Style），如 VLOG / LAND / PORT / STD。仅详情接口返回。 */
    public String photoStyle = "";
    public int downloads = 0;
    public String overview = "";
    public String thumbPath = "";
    /** 官方样张 · 原图（thumbnail.path_original）。用于滑动对比的左半边。 */
    public String thumbBefore = "";
    /** 官方样张 · 套用该 LUT 之后（thumbnail.path）。 */
    public String thumbAfter = "";
    public String countryCode = "";
    /** 是否已抓过详情（photoStyle / overview 是否有效） */
    public boolean enriched = false;

    /** 列表接口（filter / top）响应解析。 */
    public static Lut fromList(JSONObject it, String genreHint, String[] countryHint) {
        Lut l = new Lut();
        l.code = it.optString("code", "");
        l.title = it.optString("title", "");
        JSONObject cr = it.optJSONObject("creator");
        if (cr != null) {
            l.creatorCode = cr.optString("code", "");
            l.creatorName = cr.optString("name", "");
        }
        JSONObject g = it.optJSONObject("genre");
        if (g != null) l.genreCode = g.optString("code", "");
        if (l.genreCode.isEmpty() && genreHint != null) l.genreCode = genreHint;
        l.downloads = it.optInt("download_number", 0);
        JSONObject th = it.optJSONObject("thumbnail");
        if (th != null) {
            l.thumbPath = th.optString("path", "");
            l.thumbAfter = l.thumbPath;
            l.thumbBefore = th.optString("path_original", "");
        }
        l.countryCode = deriveCountry(l.creatorCode, countryHint);
        return l;
    }

    /** 详情接口（community/lut）响应解析。 */
    public static Lut fromDetail(JSONObject o) {
        Lut l = new Lut();
        l.code = o.optString("code", "");
        l.title = o.optString("title", "");
        l.photoStyle = o.optString("photo_style", "");
        l.overview = o.optString("overview", "");
        l.downloads = o.optInt("download_number", 0);
        JSONObject cr = o.optJSONObject("creator");
        if (cr != null) {
            l.creatorCode = cr.optString("code", "");
            l.creatorName = cr.optString("name", "");
        }
        JSONObject th = o.optJSONObject("thumbnail");
        if (th != null) {
            l.thumbPath = th.optString("path", "");
            l.thumbAfter = l.thumbPath;
            l.thumbBefore = th.optString("path_original", "");
        }
        l.countryCode = deriveCountry(l.creatorCode, null);
        l.enriched = true;
        return l;
    }

    /**
     * 创作者 code 尾部常带国家线索，如 crt_0045_ireland / crt_0007_ja。
     * 这是从既有字段的命名规律提取，非服务端独立字段。
     */
    static String deriveCountry(String creatorCode, String[] countryHint) {
        if (countryHint != null && countryHint.length > 0
                && countryHint[0] != null && !countryHint[0].isEmpty()) {
            return countryHint[0].toUpperCase();
        }
        if (creatorCode == null || creatorCode.isEmpty()) return "";
        String[] parts = creatorCode.split("_");
        if (parts.length >= 2) {
            String tail = parts[parts.length - 1];
            if (tail.length() == 2 || tail.length() == 3) return tail.toUpperCase();
        }
        return "";
    }

    /** 供列表展示的一行摘要。 */
    public String line1() {
        return title.isEmpty() ? code : title;
    }

    public String line2() {
        StringBuilder sb = new StringBuilder();
        sb.append(creatorName.isEmpty() ? "—" : creatorName);
        sb.append("  ·  ").append(genreLabel());
        if (!photoStyle.isEmpty()) sb.append("  ·  ").append(photoStyle);
        if (!countryCode.isEmpty()) sb.append("  ·  ").append(countryCode);
        return sb.toString();
    }

    /** movie → 视频，still → 照片。对应 App 的 community_lut_title_*_lut。 */
    public String genreLabel() {
        if ("movie".equals(genreCode)) return "视频";
        if ("still".equals(genreCode)) return "照片";
        return genreCode.isEmpty() ? "—" : genreCode;
    }

    static String optStringOrNull(JSONArray a, int i) {
        return a == null ? null : a.optString(i, null);
    }
}
