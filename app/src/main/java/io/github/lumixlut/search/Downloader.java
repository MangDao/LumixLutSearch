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
        r.bytes = zip.length;

        // --- 在内存里解包，找出第一个 .cube ---
        byte[] cube = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                r.entries.add(e.getName());
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".cube")) {
                    cube = readAll(zis);
                    r.cubeName = new File(e.getName()).getName();
                    break;
                }
            }
        }
        if (cube == null) {
            throw new IOException("官方包内未找到 .cube（条目：" + r.entries + "）");
        }

        // --- 落盘到公共 Downloads ---
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
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建目录 " + dir);
            }
            File f = new File(dir, r.cubeName);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(cube);
            }
            // FileProvider 未配置，26~28 用 file:// 给系统文件管理器
            r.uri = Uri.fromFile(f);
            r.displayPath = f.getAbsolutePath();
        }
        return r;
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
