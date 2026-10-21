package io.github.lumixlut.edit;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.lumixlut.edit.lut.CubeParser;
import io.github.lumixlut.edit.lut.LutData;
import io.github.lumixlut.edit.lut.LutThumb;
import io.github.lumixlut.search.Store;

/**
 * 把「下载过的 LUT」接进编辑器。
 *
 * 数据直接从现有下载表读（{@link Store#listDownloads()}），不另建索引——
 * 用户下载过什么，这里就能选什么，不会出现两份对不上的清单。
 *
 * 解析和缩略图都有缓存：一个 33³ 的 LUT 解析出来约 130 KB，
 * 反复进出编辑页时不必每次从文件重啃。
 */
public final class LutLibrary {

    public static final class Entry {
        public String code;
        public String name;
        public String title;
        public String creator;
        public Uri uri;
        /** 提前烘好的缩略图，可能为 null */
        public Bitmap thumb;

        public String label() {
            return name == null || name.isEmpty() ? code : name;
        }

        public String shortLabel() {
            String n = label();
            if (n.toLowerCase(java.util.Locale.US).endsWith(".cube")) {
                n = n.substring(0, n.length() - 5);
            }
            return n;
        }
    }

    private static final Map<String, Bitmap> THUMBS = new HashMap<>();
    private static final Map<String, LutData> DATA = new HashMap<>();

    private LutLibrary() {
    }

    /** 后台线程调用。读不到库或库是空的都返回空列表，不抛。 */
    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        Store store = null;
        try {
            store = new Store(ctx);
            List<Store.Dl> downloads = store.listDownloads();
            if (downloads == null) {
                return out;
            }
            for (Store.Dl d : downloads) {
                Uri uri = resolve(ctx, d);
                if (uri == null) {
                    continue;
                }
                Entry e = new Entry();
                e.code = d.code == null ? "" : d.code;
                e.name = d.cubeName == null ? "" : d.cubeName;
                e.title = d.title == null ? "" : d.title;
                e.creator = d.creatorName == null ? "" : d.creatorName;
                e.uri = uri;
                out.add(e);
            }
        } catch (Throwable t) {
            // 库还没建好、表结构不对等等，都当作"没有 LUT"处理
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** uri 优先（MediaStore 授权过），拿不到再按落盘路径拼 */
    private static Uri resolve(Context ctx, Store.Dl d) {
        if (d.uri != null && !d.uri.isEmpty()) {
            try {
                Uri u = Uri.parse(d.uri);
                if (u != null && u.getScheme() != null) {
                    return u;
                }
            } catch (Exception ignored) {
            }
        }
        String path = d.displayPath;
        if (path == null || path.isEmpty()) {
            return null;
        }
        File file;
        if (path.startsWith("/")) {
            file = new File(path);
        } else {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "LUMIX_LUT");
            file = new File(dir, new File(path).getName());
        }
        return file.exists() ? Uri.fromFile(file) : null;
    }

    public static LutData load(Context ctx, Entry entry) throws IOException {
        LutData cached = DATA.get(entry.code);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = SourceLoader.open(ctx, entry.uri)) {
            LutData data = CubeParser.parse(in, entry.name);
            DATA.put(entry.code, data);
            return data;
        }
    }

    /** 换照片后要把缩略图全丢掉，否则列表还挂着上一张图的预览 */
    public static void invalidateThumbs() {
        THUMBS.clear();
    }

    /** 拿不到就返回 null，界面显示一个占位色块 */
    public static Bitmap thumb(Context ctx, Entry entry) {
        return thumb(ctx, entry, null);
    }

    public static Bitmap thumb(Context ctx, Entry entry, int[] reference) {
        Bitmap cached = THUMBS.get(entry.code);
        if (cached != null) {
            return cached;
        }
        try {
            LutData data = load(ctx, entry);
            // 216：72dp 的缩略图在 3x 屏上正好，1:1 不糊
            Bitmap bitmap = LutThumb.make(ctx, data, 216, reference);
            if (bitmap != null) {
                THUMBS.put(entry.code, bitmap);
            }
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }
}
