package io.github.lumixlut.search;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LUMIX Lab 社区 LUT API 客户端。
 *
 * 接口事实（均为实测所得，改动前请先复核）：
 *   base   https://lumix-base.jpn.panasonic.com/lutapi/v2/
 *   必需头 lutapp-api-key: 111      —— 硬编码于官方 App 的 p084h6/e.java
 *   鉴权   无。无 Bearer、无登录
 *   方法   全部 POST
 *
 * 坑（已在代码中处理）：
 *   1. 服务端会限流：约 20 次连发后连接超时，约 45 秒恢复
 *      -> 全局 THROTTLE_MS 节流 + 指数退避重试
 *   2. 服务端无全文检索：filter 的 key 字段被忽略（实测两值响应字节一致）
 *      -> 检索必须做在本地
 *   3. 服务端不支持自定义排序：filter 的 order.key 被忽略（实测 7 个取值结果相同）
 *      -> 排序在本地做
 *   4. top 端点：值为空的字段必须【整个省略】，传空字符串会 HTTP 400；
 *      date_range 只接受 weekly / monthly
 *   5. 只有 filter 返回 download_number；top 不返回
 */
public final class Api {

    public static final String BASE = "https://lumix-base.jpn.panasonic.com/lutapi/v2/";

    /** 请求最小间隔。服务端限流阈值实测约 20 连发，留足余量。 */
    private static final long THROTTLE_MS = 800;
    private static final int CONNECT_TIMEOUT = 15000;
    /**
     * 实测该接口偶尔会慢到 40 秒以上（链路绕行 CloudFront 时）。
     * 原来 40 秒正好卡在边缘，会被误判成失败，放宽到 90 秒。
     * 注意：样张图片走 CDN，快得多，用的是 ImageLoader 自己的超时。
     */
    private static final int READ_TIMEOUT = 90000;
    private static final int RETRIES = 4;

    private static long sLastRequest = 0L;

    private Api() {}

    // ------------------------------------------------------------ 基础请求
    private static synchronized void throttle() {
        long gap = System.currentTimeMillis() - sLastRequest;
        if (gap < THROTTLE_MS) {
            try { Thread.sleep(THROTTLE_MS - gap); } catch (InterruptedException ignored) {}
        }
        sLastRequest = System.currentTimeMillis();
    }

    private static HttpURLConnection open(String path, String accept) throws IOException {
        URL url = new URL(BASE + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setRequestProperty("lutapp-api-key", "111");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "LUMIXLab/3.1.0");
        return c;
    }

    private static byte[] writeAndRead(HttpURLConnection c, byte[] body)
            throws IOException {
        try (OutputStream os = c.getOutputStream()) {
            if (body != null) os.write(body);
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            // 4xx 属于请求本身的问题，不重试
            String msg = "";
            try (InputStream es = c.getErrorStream()) {
                if (es != null) msg = new String(readAll(es), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
            throw new ApiException(code, msg, code >= 400 && code < 500);
        }
        try (InputStream is = c.getInputStream()) {
            return readAll(is);
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** HTTP 错误。fatal=true 表示 4xx，不应重试。 */
    public static class ApiException extends IOException {
        public final int code;
        public final String body;
        public final boolean fatal;
        ApiException(int code, String body, boolean fatal) {
            super("HTTP " + code + (body.isEmpty() ? "" : " " + body));
            this.code = code; this.body = body; this.fatal = fatal;
        }
    }

    /** 带节流 + 指数退避重试的 POST，返回原始字节。 */
    private static byte[] post(String path, JSONObject payload, String accept,
                               Progress progress) throws IOException {
        byte[] body = payload == null
                ? new byte[0]
                : payload.toString().getBytes(StandardCharsets.UTF_8);
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            throttle();
            HttpURLConnection c = null;
            try {
                c = open(path, accept);
                return writeAndRead(c, body);
            } catch (ApiException e) {
                if (e.fatal) throw e;              // 4xx 直接抛
                last = e;
            } catch (IOException e) {
                last = e;                          // 超时 / 连接重置 —— 多半是限流
            } finally {
                if (c != null) c.disconnect();
            }
            long wait = 3000L << attempt;          // 3s, 6s, 12s, 24s
            if (progress != null) {
                progress.onRetry(attempt + 1, wait, last);
            }
            try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
        }
        throw last != null ? last : new IOException("unknown");
    }

    private static JSONObject postJson(String path, JSONObject payload,
                                       Progress progress) throws IOException {
        try {
            return new JSONObject(new String(
                    post(path, payload, "application/json", progress),
                    StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("响应不是合法 JSON: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ 接口
    public interface Progress {
        void onRetry(int attempt, long waitMs, IOException cause);
    }

    /** 题材字典 + 各 region 的 top 缩略图。 */
    public static List<String> genres(Progress p) throws IOException {
        List<String> out = new ArrayList<>();
        JSONObject o = postJson("community/genres", null, p);
        JSONArray arr = o.optJSONArray("genres");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String c = arr.optJSONObject(i).optString("code", "");
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    /** 国家/地区字典。 */
    public static List<String> countries(Progress p) throws IOException {
        List<String> out = new ArrayList<>();
        JSONObject o = postJson("community/creators/countries", new JSONObject(), p);
        JSONArray arr = o.optJSONArray("countries");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            String c = arr.optJSONObject(i).optString("code", "");
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    /** filter 请求体。字段为空则整个省略（服务端拒绝空串）。 */
    public static JSONObject filterBody(String lang, int limit, int offset,
                                        String genreCode, String creatorCode,
                                        String[] countryCodes) throws IOException {
        try {
            JSONObject b = new JSONObject();
            b.put("language", lang);
            b.put("limit", String.valueOf(limit));
            b.put("offset", String.valueOf(offset));
            JSONObject order = new JSONObject();
            order.put("key", "created");     // 实测被服务端忽略，仅作占位
            order.put("order", "desc");
            b.put("order", order);
            if (genreCode != null && !genreCode.isEmpty()) b.put("genre_code", genreCode);
            if (creatorCode != null && !creatorCode.isEmpty()) b.put("creator_code", creatorCode);
            if (countryCodes != null && countryCodes.length > 0) {
                b.put("countries", new JSONArray(countryCodes));
            }
            return b;
        } catch (JSONException e) {
            throw new IOException("构造请求体失败: " + e.getMessage(), e);
        }
    }

    /** 目录列表。返回已解析的条目。 */
    public static List<Lut> filter(String lang, int limit, int offset,
                                   String genreCode, String creatorCode,
                                   String[] countryCodes, Progress p) throws IOException {
        List<Lut> out = new ArrayList<>();
        JSONObject o = postJson("community/luts/filter",
                filterBody(lang, limit, offset, genreCode, creatorCode, countryCodes), p);
        JSONArray arr = o.optJSONArray("filter");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it != null) out.add(Lut.fromList(it, genreCode, countryCodes));
        }
        return out;
    }

    /** 单条详情：补齐 photo_style（格调）与 overview。 */
    public static Lut detail(String code, String lang, Progress p) throws IOException {
        JSONObject b = new JSONObject();
        try { b.put("code", code); b.put("language", lang); }
        catch (JSONException e) { throw new IOException(e); }
        JSONObject o = postJson("community/lut", b, p);
        return Lut.fromDetail(o);
    }

    /**
     * 官方榜单。注意：值为空必须【省略字段】，传空串会 400。
     * date_range 只接受 weekly / monthly。
     */
    public static List<Lut> top(String lang, int limit, String genreCode,
                               String region, String dateRange, Progress p)
            throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("limit", limit);           // 注意：这里是 int，不是字符串
            b.put("language", lang);
            if (genreCode != null && !genreCode.isEmpty()) b.put("genre_code", genreCode);
            if (region != null && !region.isEmpty()) b.put("region", region);
            if ("weekly".equals(dateRange) || "monthly".equals(dateRange)) {
                b.put("date_range", dateRange);
            }
        } catch (JSONException e) { throw new IOException(e); }
        JSONObject o = postJson("community/luts/top", b, p);
        List<Lut> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("top");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it != null) out.add(Lut.fromList(it, genreCode, null));
        }
        return out;
    }

    /**
     * 官方下载：返回 zip 字节。
     * key = "grid33" 或 "grid17"。
     *
     * ⚠ LUMIX Lab 导入硬校验 getIs33Or17Lut() == 33（LutFragment.P0），
     *   17grid 会被拒绝并删除文件。所以默认只用 grid33。
     */
    public static byte[] download(String code, String key, Progress p)
            throws IOException {
        JSONObject b = new JSONObject();
        try { b.put("code", code); b.put("key", key); }
        catch (JSONException e) { throw new IOException(e); }
        return post("community/luts/download", b, "application/zip", p);
    }
}
