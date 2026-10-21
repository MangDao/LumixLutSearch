package io.github.lumixlut.search;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 下载 → 解包 → 落盘 → 交棒给 LUMIX Lab。
 *
 * 关于「发 Intent 给 LUMIX Lab」的现实（已核实，不要指望能做到更多）：
 *   官方包的 AndroidManifest 里【没有】任何接收 .cube 文件的 intent-filter ——
 *   既无 ACTION_SEND，也无针对 .cube 的 ACTION_VIEW。它的导入流程是自己
 *   用 ACTION_GET_CONTENT 拉起系统文件选择器（LutFragment case 7）。
 *   因此第三方应用【无法】直接把文件塞进它的 LUT 库，非 root 下这是 Android 沙箱的硬边界。
 *
 * 本类的做法：
 *   1. 把 .cube 存到【公共 Downloads/LUMIX_LUT/】，这个位置系统选择器能看到
 *   2. 先尝试 ACTION_VIEW（万一有文件管理器能处理）
 *   3. 否则直接拉起 LUMIX Lab，用户在 App 内走「LUT库 → 设备 → 导入LUT」
 */
public final class Downloader {

    public static final String OUT_DIR = "LUMIX_LUT";
    public static final String LUMIX_PKG = "com.panasonic.jp.lumixlab";

    private Downloader() {}

    public static class Result {
        public String cubeName = "";
        public Uri uri;              // 落盘后的可读 URI
        public String displayPath = "";
        public int bytes = 0;
        public final List<String> entries = new ArrayList<>();
    }

    /** 下载并解出 .cube，写入公共下载目录。 */
    public static Result fetchAndSave(Context ctx, String code, String grid,
                                      Api.Progress p) throws IOException {
        byte[] zip = Api.download(code, grid, p);
        if (zip == null || zip.length == 0) throw new IOException("下载内容为空");

        Result r = new Result();

        // --- 在内存里解包，找出第一个 .cube ---
        byte[] cube = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                r.entries.add(e.getName());
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".cube")
                        && !isMacJunk(e.getName())) {
                    cube = readAll(zis);
                    r.cubeName = new File(e.getName()).getName();
                    break;
                }
            }
        }
        if (cube == null) {
            throw new IOException("官方包内未找到 .cube（条目：" + r.entries + "）");
        }

        // --- 落盘到公共 Downloads（与 fetchZipAll 共用一份实现）---
        r.bytes = cube.length;          // 记录**文件本身**的大小；zip 长度会被列表误读成文件大小
        saveCube(ctx, r, cube);
        return r;
    }

    /**
     * 下载任意 URL 的 zip 并把里面**所有** .cube 落盘（松影俱乐部 25 周年那套用这条）。
     *
     * 与 {@link #fetchAndSave} 的区别：
     *   1. 官方包走 `Api.download(code, grid)`；这里走裸 URL（对方的 OSS 直链）；
     *   2. 官方包只解第一个 .cube；德松那包里有 warm/cold **两个**，这里全存；
     *   3. 松影 OSS 开了防盗链，**必须带 Referer**，否则 403（实测裸请求 304 字节的拒绝页）。
     *
     * Referer 是用户 2026-10-18 明确选择要加的（备选方案是「跳浏览器下载」），
     * 目的只是让公开免费发布的文件能正常取到，不做任何批量抓取。
     */
    /** 进度回调（百分比 0~100）。 */
    public interface Percent {
        void onPercent(int pct);
    }

    public static List<Result> fetchZipAll(Context ctx, String url, String referer,
                                           Api.Progress p) throws IOException {
        return fetchZipAll(ctx, url, referer, null, null, null);
    }

    /**
     * @param outNames 落盘用的文件名（按 zip 内 .cube 顺序）；null 则用包里的原名。
     * @param pct      下载进度（0~100），可 null。
     */
    public static List<Result> fetchZipAll(Context ctx, String url, String referer,
                                           List<String> outNames, Percent pct) throws IOException {
        return fetchZipAll(ctx, url, referer, outNames, null, pct);
    }

    /**
     * 落盘命名规则（松影那套 warm/cold 混装包就靠这条不丢语义）：
     *   ① 声明名（`.cube` + `.vlt`）的个数与包内 LUT 文件数一致 → 按声明顺序用声明名；
     *   ② 对不上（典型：冷暖色调那种 warm+cold 装一个 zip，而条目只声明 1 个名）
     *      → `<namePrefix>_<包内 token>.<ext>`；`cold` 归一到 `cool`，
     *        于是 WARM 卡和 COOL 卡推出来的两个名字完全一致，和清单里的 `_warm` / `_cool` 对得上。
     *
     * `.vlt` 也要存：专栏里有 **7 条**包里只有 `.vlt`（VLT 格式，没有 .cube），
     * 另 3 条是 `.cube + .vlt`。以前只认 `.cube`，这 7 条点下载必抛「压缩包内未找到 .cube」。
     *
     * @param namePrefix 兜底前缀，通常是条目的 nameEn；null 时退回包内原名
     */
    public static List<Result> fetchZipAll(Context ctx, String url, String referer,
                                           List<String> outNames, String namePrefix,
                                           Percent pct) throws IOException {
        return fetchZipAll(ctx, url, referer, outNames, namePrefix, null, pct);
    }

    /**
     * @param zipNames 包内文件名 → 落盘名 的显式映射。有些官方包里的 cube 是**哈希名**
     *                 （`73ec…_8.cube` 这种），靠名字推不出冷暖，只能由清单写明；
     *                 命中映射就按映射落盘，优先级高于"声明名/前缀"两套兜底规则。
     */
    public static List<Result> fetchZipAll(Context ctx, String url, String referer,
                                           List<String> outNames, String namePrefix,
                                           java.util.Map<String, String> zipNames,
                                           Percent pct) throws IOException {
        byte[] zip = getBytes(url, referer, null, pct);
        if (zip == null || zip.length == 0) throw new IOException("下载内容为空");

        // 先把 LUT 文件全读出来再命名落盘：命名要按"包内文件总数"决定，边解边写会缺这个信息
        List<String> entryNames = new ArrayList<>();
        List<String> lutZipNames = new ArrayList<>();
        List<byte[]> luts = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                entryNames.add(e.getName());
                if (!isLutFile(e.getName())) continue;
                // macOS 打包会塞 __MACOSX/._xxx.cube（212 字节的资源分叉），这不是 LUT：
                // 实测一个 zip 里 4 个 .cube，其中 2 个是这种空壳，落盘就会多出两个假文件
                if (isMacJunk(e.getName())) continue;
                luts.add(readAll(zis));
                lutZipNames.add(new File(e.getName()).getName());
            }
        }
        if (luts.isEmpty()) {
            throw new IOException("压缩包内未找到 .cube / .vlt（条目：" + entryNames + "）");
        }

        List<String> declared = new ArrayList<>();
        if (outNames != null) {
            for (String n : outNames) {
                if (isLutFile(n)) declared.add(n);
            }
        }
        boolean useDeclared = declared.size() == luts.size();

        List<Result> out = new ArrayList<>();
        for (int k = 0; k < luts.size(); k++) {
            Result r = new Result();
            r.bytes = luts.get(k).length;   // 每个 LUT 文件自己的大小（原来是整个 zip 的长度）
            String zipBase = lutZipNames.get(k);
            String ext = extensionOf(zipBase);
            String mapped = zipNames == null ? null : zipNames.get(zipBase);
            if (mapped != null && !mapped.isEmpty()) {
                r.cubeName = mapped;
            } else if (useDeclared) {
                r.cubeName = declared.get(k);
            } else if (namePrefix != null && !namePrefix.isEmpty()) {
                r.cubeName = namePrefix + "_" + totFor(zipBase) + ext;
            } else {
                r.cubeName = zipBase;
            }
            r.entries.addAll(entryNames);
            saveCube(ctx, r, luts.get(k));
            out.add(r);
        }
        return out;
    }

    private static boolean isLutFile(String name) {
        String n = name == null ? "" : name.toLowerCase();
        return n.endsWith(".cube") || n.endsWith(".vlt");
    }

    private static String extensionOf(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".vlt") ? ".vlt" : ".cube";
    }

    /** 包内原名 → 可安全落盘的 ASCII 基名（小写、非 [a-z0-9_-] 一律折成 _）。 */
    private static String safeBase(String zipBase) {
        String base = zipBase;
        int dot = base.toLowerCase().lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = Character.toLowerCase(base.charAt(i));
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String s = sb.toString();
        return s.isEmpty() ? "lut" : s;
    }

    /**
     * 包内名 → 命名用的 token。官方包里冷暖两版叫 `cold` / `warm`，
     * 而专栏卡面写的是 `COOL` / `WARM`；不归一就会出现「卡面 COOL、文件 _cold」的对不上。
     */
    private static String totFor(String zipBase) {
        String s = safeBase(zipBase);
        if (s.equals("cold") || s.equals("cool")) return "cool";
        return s;
    }

    /** macOS 打包残留：`__MACOSX/…` 里的 `._xxx.cube` 只是资源分叉，不是 LUT 数据。 */
    private static boolean isMacJunk(String entryName) {
        String n = entryName.replace('\\', '/');
        if (n.startsWith("__MACOSX/") || n.contains("/__MACOSX/")) return true;
        int slash = n.lastIndexOf('/');
        return n.substring(slash + 1).startsWith("._");
    }

    /** 裸 GET 返回文本（抓松影专题页用）。 */
    static String httpGet(String url, String referer) throws IOException {
        byte[] b = getBytes(url, referer, null);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 裸 GET（可带 Referer），只在下载松影那套时用。 */
    private static byte[] getBytes(String url, String referer, Api.Progress p) throws IOException {
        return getBytes(url, referer, p, null);
    }

    private static byte[] getBytes(String url, String referer, Api.Progress p,
                                   Percent pct) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "LUMIXLab/3.1.0");
            if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + "（直链可能失效或被拒）");
            int total = c.getContentLength();
            if (pct == null || total <= 0) return readAll(c.getInputStream());
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, done = 0, last = -1;
            try (InputStream is = c.getInputStream()) {
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    done += n;
                    int percent = (int) (done * 100L / total);
                    if (percent != last) { last = percent; pct.onPercent(percent); }
                }
            }
            return bos.toByteArray();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 把解出来的 .cube 写进公共下载目录（与官方那套同一套落盘逻辑）。 */
    private static void saveCube(Context ctx, Result r, byte[] cube) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, r.cubeName);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + OUT_DIR);
            Uri item = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (item == null) throw new IOException("MediaStore 插入失败");
            try (OutputStream os = cr.openOutputStream(item)) {
                if (os == null) throw new IOException("无法写入 " + item);
                os.write(cube);
            }
            r.uri = item;
            r.displayPath = "Download/" + OUT_DIR + "/" + r.cubeName;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), OUT_DIR);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录 " + dir);
            File f = new File(dir, r.cubeName);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(cube);
            }
            r.uri = Uri.fromFile(f);
            r.displayPath = f.getAbsolutePath();
        }

        // 公共目录那份只是「给用户和别的 app 看的」。编辑器不能直接读它：
        // Android 11 起读 Download 集合要 READ_MEDIA_DOWNLOADS（13+）
        // 或 MANAGE_EXTERNAL_STORAGE，本应用两个都没有，于是会出现
        //   SecurityException: has no access to content://media/external/downloads/…
        // ——下载完当场还能用（同一进程持有临时授权），app 重启后再点就挂。
        // 所以在自己的私有目录留一份，编辑器只认这一份。
        File lutsDir = new File(ctx.getFilesDir(), "luts");
        if (lutsDir.exists() || lutsDir.mkdirs()) {
            try (FileOutputStream fo = new FileOutputStream(new File(lutsDir, r.cubeName))) {
                fo.write(cube);
            } catch (IOException ignored) {
                // 私有副本写失败不影响公共目录那份，只是编辑器可能读不到
            }
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    public static boolean isLumixInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(LUMIX_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 尝试把文件交给别的应用。
     * 返回 true 表示系统找到了处理者（不保证是 LUMIX Lab —— 它没注册）。
     */
    public static boolean tryOpenWith(Context ctx, Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(ctx.getPackageManager()) == null) return false;
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 拉起 LUMIX Lab 主界面。找不到则返回 false。 */
    public static boolean launchLumix(Context ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(LUMIX_PKG);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
