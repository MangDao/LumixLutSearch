package io.github.lumixlut.edit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 从相册 Uri 里取图：按长边采样、按 EXIF 摆正。 */
public final class SourceLoader {

    private SourceLoader() {
    }

    public static Bitmap load(Context context, Uri uri, int maxDimension) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(context, uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("这张图解不出尺寸，可能格式不支持：" + uri);
        }

        int sample = 1;
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longSide / sample > maxDimension * 2) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = open(context, uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) {
            throw new IOException("解码失败：" + uri);
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = open(context, uri)) {
            ExifInterface exif = new ExifInterface(in);
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException ignored) {
            // 有些容器没有 EXIF，按正常方向处理
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1f, -1f);
                break;
            default:
                break;
        }
        if (matrix.isIdentity()) {
            return decoded;
        }
        Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0,
                decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (rotated != decoded) {
            decoded.recycle();
        }
        return rotated;
    }

    /**
     * 三种来源都走这一条：
     *   content://  相册 / 文件选择器给的（正式路径，不需要任何存储权限）
     *   asset:xxx   打包在 APK 里的素材（debug 构建用它做自测，见 app/src/debug/assets）
     *   file 路径    从 LUT 详情页跳进来时用；注意 Android 15 上读 /sdcard 会 EACCES，
     *               所以正式流程不要依赖它，能走 content:// 就走 content://
     */
    public static InputStream open(Context context, Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("asset".equals(scheme)) {
            // "asset:teal.cube" 是 opaque URI，getPath() 是 null，得走 schemeSpecificPart
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = uri.getSchemeSpecificPart();
            }
            if (path == null || path.isEmpty()) {
                throw new IOException("asset 路径为空：" + uri);
            }
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return context.getAssets().open(path);
        }
        if (scheme == null || "file".equals(scheme)) {
            return new FileInputStream(new File(uri.getPath()));
        }
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("打不开：" + uri);
        }
        return in;
    }
}
