package io.github.lumixlut.edit;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 素材画像：判断这张图处在哪个色彩空间，决定套 LUT 前后要不要做转换。
 *
 * 判定依据来自真实样张实测（Panasonic DC-S5）：
 *   - MakerNote 0x0089 = PhotoStyle，17 就是 V-Log
 *   - V-Log 素材必须先把 log 解开，才能套社区 LUT（那些 LUT 的输入域是已显影画面）
 *   - 其余格调（含自定义）一律当已烘焙处理，直通
 *   - LUT 文件里的 #LUMIXPHOTOSTYLE 只表示"这条 LUT 为哪种拍摄素材调过"
 */
public final class SourceProbe {

    public enum Kind {
        /** JPEG / HEIF：相机已经把 Photo Style 烘进像素了 */
        BAKED,
        /** 真·V-Log（MakerNote 0x0089 = 17），进 LUT 前要解 log */
        VLOG,
        /** RAW 原文件，当前解码器吃不了 */
        RAW,
        /** 认不出来，按 BAKED 处理 */
        UNKNOWN
    }

    public final Kind kind;
    public final String make;
    public final String model;
    public final String format;
    /** STD / NAT / V-LOG / 自定义风格 / null */
    public final String photoStyle;
    /** 给界面看的一句话 */
    public final String label;

    private SourceProbe(Kind kind, String make, String model, String format,
                        String photoStyle, String label) {
        this.kind = kind;
        this.make = make;
        this.model = model;
        this.format = format;
        this.photoStyle = photoStyle;
        this.label = label;
    }

    public static SourceProbe probe(Context context, Uri uri) {
        String name = uri.getLastPathSegment();
        String ext = "";
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                ext = name.substring(dot + 1).toLowerCase(Locale.US);
            }
        }

        if (ext.equals("rw2") || ext.equals("dng") || ext.equals("raw")) {
            return new SourceProbe(Kind.RAW, null, null, ext.toUpperCase(Locale.US),
                    null, "RAW 原文件 · 暂不支持解码");
        }

        PanasonicExif.Info exif = null;
        try (InputStream in = SourceLoader.open(context, uri)) {
            exif = PanasonicExif.read(in);
        } catch (IOException ignored) {
            // 没有 EXIF 很正常（截图、导出图、asset 里的测试图）
        }

        String make = exif == null ? null : exif.make;
        String model = exif == null ? null : exif.model;
        String style = null;
        if (exif != null) {
            style = exif.photoStyleName;
            if (style == null && exif.photoStyleRaw != null) {
                style = "自定义风格(" + exif.photoStyleRaw + ")";
            }
        }

        String format = ext.isEmpty() ? "JPEG" : ext.toUpperCase(Locale.US);
        boolean panasonic = isPanasonic(make, model);
        String who = panasonic
                ? ("Panasonic " + safe(model))
                : (make != null ? make : "无 EXIF");

        boolean vlog = exif != null && exif.isVLog();
        Kind kind = vlog ? Kind.VLOG : Kind.BAKED;

        StringBuilder label = new StringBuilder(who).append(" · ").append(format);
        if (style != null) {
            label.append(" · ").append(style);
        } else {
            label.append(" · 已烘焙");
        }
        return new SourceProbe(kind, make, model, format, style, label.toString());
    }

    private static boolean isPanasonic(String make, String model) {
        return containsIgnoreCase(make, "panasonic")
                || containsIgnoreCase(model, "lumix")
                || containsIgnoreCase(make, "lumix")
                || containsIgnoreCase(model, "dc-");
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.US).contains(needle);
    }

    private static String safe(String value) {
        return value == null ? "相机" : value.trim();
    }
}
