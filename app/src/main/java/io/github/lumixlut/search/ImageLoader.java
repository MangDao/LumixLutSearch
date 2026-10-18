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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    /** 图片域名（预热 DNS 用）。 */
    private static final String HOST = "lumix-base.jpn.panasonic.com";

    private static final int CONNECT_TIMEOUT = 8000;   // 实测建连可能卡 60s+，超时快点好换路
    private static final int READ_TIMEOUT = 15000;
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
    private final ExecutorService pool = Executors.newFixedThreadPool(4);   // 并行多试几条路，比串行重试快
    /** 详情弹层专用线程池：不跟网格抢那 3 个线程，点开就开始下。 */
    private final ExecutorService detailPool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache;
    /**
     * 同一 URL 的等待者。
     *
     * 以前用 inFlight 集合去重，后来的请求直接 return —— 那个回调永远不会被调用。
     * 网格正在下同一张图时点开详情，就会出现"图一直转圈、永远不出来"。
     * 现在改成登记回调，谁先发起谁负责下载，下完统一分发。
     */
    private final Map<String, List<Callback>> waiters = new HashMap<>();

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

    /**
     * 预热：进程一起来就把图片域名的 DNS/TLS 打通。
     *
     * 实测这网络解析 lumix-base.jpn.panasonic.com 要 ~20 秒，而且是**每个进程一次**；
     * 用户点开详情时才发现要解析，就会干等半分钟。提前在后台解析，命中系统缓存后
     * 后续请求就是 0.5 秒量级。
     */
    public void warmUp() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    java.net.InetAddress[] a = java.net.InetAddress.getAllByName(HOST);
                } catch (Throwable ignored) {
                }
            }
        }, "img-warmup");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
    /** 网格用：命中缓存就直接回调（仍在主线程），否则进 3 线程池排队。 */
    public void load(final String path, final Callback cb) {
        enqueue(path, cb, false);
    }

    /**
     * 详情弹层用：进独立的 2 线程池。
     *
     * 关键差别不是线程多，而是**不排在网格队尾** —— 网格一次能排进几十张缩略图，
     * 详情请求跟着排就要等前面全下完，看起来就是"点开半天不出图"。
     */
    public void loadDetail(final String path, final Callback cb) {
        enqueue(path, cb, true);
    }

    private void enqueue(final String path, final Callback cb, final boolean detail) {
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

        boolean first;
        synchronized (waiters) {
            List<Callback> list = waiters.get(u);
            first = (list == null);
            if (first) {
                list = new ArrayList<>();
                waiters.put(u, list);
            }
            list.add(cb);
        }
        if (!first) return;      // 已经有人在下了，等他的结果

        final Runnable job = new Runnable() {
            @Override public void run() { fetchInto(u); }
        };
        try {
            (detail ? detailPool : pool).execute(job);
        } catch (Throwable t) {
            finish(u, null);
        }
    }

    /** 真正的下载：磁盘 → 网络（失败重试一次）→ 解码 → 落盘 → 分发。 */
    private void fetchInto(String u) {
        byte[] data = null;
        File df = diskEnabled() ? diskFile(u) : null;
        if (df != null && df.exists() && df.length() > 0) {
            data = readFile(df);
            if (data == null) df.delete();      // 半截文件，删了重下
        }
        if (data == null) {
            data = fetchBytes(u);
            // 建连本身可能被路由到坏路径（实测 0.4s ~ 66s 反复横跳），
            // 失败就退避重来，最多 3 次；每次都是全新的连接，命中好路径就 1 秒内完成
            for (int attempt = 1; data == null && attempt <= 2; attempt++) {
                try { Thread.sleep(300L * attempt); } catch (InterruptedException ignored) { }
                data = fetchBytes(u);
            }

            if (data != null && df != null) writeFile(df, data);
        }

        Bitmap bmp = data == null ? null : decode(data);
        if (bmp != null) cache.put(u, bmp);
        finish(u, bmp);
    }

    /** 把结果分发给所有等待者（主线程）。 */
    private void finish(final String u, final Bitmap bmp) {
        final List<Callback> cbs;
        synchronized (waiters) {
            cbs = waiters.remove(u);
        }
        if (cbs == null || cbs.isEmpty()) return;
        ui.post(new Runnable() {
            @Override public void run() {
                for (Callback c : cbs) {
                    if (bmp != null) c.onLoaded(bmp);
                    else c.onFailed();
                }
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
