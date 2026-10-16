package io.github.lumixlut.search;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 样张图片加载器：后台下载 + 内存 LRU 缓存。
 *
 * 样张走的是 CDN（实测 200~600ms），比 LUT 接口快得多，所以用独立超时，
 * 不受 Api 那套 800ms 节流的约束 —— 图片和接口是两个不同的后端。
 */
public class ImageLoader {

    /** 图片直接挂在服务端根路径下，不含 lutapi/v2 前缀。 */
    public static final String IMG_BASE = "https://lumix-base.jpn.panasonic.com";

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 20000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public interface Callback {
        void onLoaded(Bitmap bmp);
        void onFailed();
    }

    private static volatile ImageLoader INSTANCE;

    /** 应用 Context，用于磁盘缓存判断与目录。 */
    private static volatile Context appCtx;

    /** 在 Activity.onCreate 里调一次。 */
    public static void init(Context c) {
        appCtx = c.getApplicationContext();
        File d = diskDir();
        if (d != null && !d.exists()) d.mkdirs();
    }

    private static File diskDir() {
        if (appCtx == null) return null;
        return new File(appCtx.getCacheDir(), "samples");
    }

    /** 磁盘缓存是否启用（用户开关 + 目录可用）。 */
    private static boolean diskEnabled() {
        if (appCtx == null) return false;
        return Prefs.cacheSamples(appCtx) && diskDir() != null;
    }

    /** 路径 → 稳定的缓存文件名（避免路径里的 / 变成目录层级）。 */
    private static File diskFile(String url) {
        File d = diskDir();
        if (d == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] h = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(d, sb.toString() + ".img");
        } catch (Exception e) {
            return null;
        }
    }

    /** 已缓存的样张数量（给设置页显示进度用）。 */
    public static int diskCount() {
        File d = diskDir();
        if (d == null || !d.exists()) return 0;
        String[] ls = d.list();
        return ls == null ? 0 : ls.length;
    }

    public static long diskBytes() {
        File d = diskDir();
        if (d == null || !d.exists()) return 0L;
        long n = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    public static void clearDisk() {
        File d = diskDir();
        if (d == null) return;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** 用户关掉开关时把已占的空间释放掉。 */
    public static void clearDiskAsync() {
        ImageLoader l = INSTANCE;
        if (l != null) l.cache.evictAll();
        new Thread(new Runnable() {
            @Override public void run() { clearDisk(); }
        }).start();
    }

    public static ImageLoader get() {
        if (INSTANCE == null) {
            synchronized (ImageLoader.class) {
                if (INSTANCE == null) INSTANCE = new ImageLoader();
            }
        }
        return INSTANCE;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache;
    private final Set<String> inFlight =
            Collections.synchronizedSet(new HashSet<String>());

    private ImageLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        // 图片用 1/6 堆，够放十来张 1080x720
        cache = new LruCache<String, Bitmap>(maxKb / 6) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };
    }

    /** 把服务端返回的相对路径拼成完整 URL。 */
    public static String url(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        return IMG_BASE + (path.startsWith("/") ? path : "/" + path);
    }

    /** 命中缓存就直接回调（仍在主线程），否则排队下载。 */
    public void load(final String path, final Callback cb) {
        final String u = url(path);
        if (u.isEmpty()) {
            cb.onFailed();
            return;
        }

        Bitmap hit = cache.get(u);
        if (hit != null && !hit.isRecycled()) {
            cb.onLoaded(hit);
            return;
        }

        // 同一张图并发请求只发一次
        if (!inFlight.add(u)) return;

        pool.execute(new Runnable() {
            @Override
            public void run() {
                // 取字节：磁盘 → 网络。开启离线缓存后，第二遍就不再走网络。
                byte[] data = null;
                File df = diskEnabled() ? diskFile(u) : null;
                if (df != null && df.exists() && df.length() > 0) {
                    data = readFile(df);
                }
                if (data == null) {
                    data = fetchBytes(u);
                    if (data != null && df != null) writeFile(df, data);
                }

                final Bitmap bmp = data == null ? null : decode(data);
                inFlight.remove(u);
                if (bmp != null) cache.put(u, bmp);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bmp != null) cb.onLoaded(bmp);
                        else cb.onFailed();
                    }
                });
            }
        });
    }

    private static byte[] readFile(File f) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeFile(File f, byte[] data) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(data);
            out.flush();
        } catch (Throwable ignored) {
            // 写盘失败不影响本次显示，只是下次还得联网
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    /** 解字节为位图。RGB_565 省一半内存；解不出再退回默认配置。 */
    private static Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) b = BitmapFactory.decodeByteArray(data, 0, data.length);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 已在缓存里的图（不同步，仅用于快速判断）。 */
    public Bitmap peek(String path) {
        return cache.get(url(path));
    }

    /** 下载到字节（不解码 —— 解码交给 decode()，同一份字节还能顺手落盘）。 */
    private byte[] fetchBytes(String u) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setRequestProperty("Accept", "image/*");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) return null;

            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (c != null) c.disconnect();
        }
    }
}
