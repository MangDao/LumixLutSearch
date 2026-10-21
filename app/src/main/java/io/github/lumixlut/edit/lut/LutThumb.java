package io.github.lumixlut.edit.lut;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.InputStream;

/**
 * 给 LUT 生成一张小样图。
 *
 * 做法是拿一张固定的参考照片，把 LUT 套上去当缩略图——这是用户一眼能看懂
 * 「这条 LUT 长什么样」的方式（官方的列表也是这么干的）。
 *
 * 之前那版是上半灰阶、下半色相环，信息量其实更大，但看着像调试用的色卡，
 * 摆在列表里不好看。参考照片能同时给出天空、绿植、白墙、饱和色，
 * 足够看出风格差异，而且好看。
 *
 * 参考图打包成 216×216：72dp 的缩略图在 3x 屏上正好 216px，1:1 不糊。
 */
public final class LutThumb {

    private static final String TAG = "LutThumb";
    private static final String ASSET = "lut_preview.jpg";

    /** 按尺寸缓存参考图的像素，换尺寸才重新解 */
    private static int[] referencePixels;
    private static int referenceSize;

    private LutThumb() {
    }

    private static int[] loadReference(Context ctx, int size) {
        if (referencePixels != null && referenceSize == size) {
            return referencePixels;
        }
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null) {
                return null;
            }
            if (bitmap.getWidth() != size || bitmap.getHeight() != size) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, size, size, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                }
                bitmap = scaled;
            }
            int[] pixels = new int[size * size];
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
            bitmap.recycle();
            referencePixels = pixels;
            referenceSize = size;
            return pixels;
        } catch (Exception e) {
            Log.w(TAG, "读不到参考图：" + e.getMessage());
            return null;
        }
    }

    /** 拿不到参考图时退回色相环，至少还有东西显示 */
    public static Bitmap make(Context ctx, LutData lut, int size) {
        return make(ctx, lut, size, null);
    }

    /**
     * @param reference 参考像素（size×size 的 ARGB），传 null 就用内置那张样片。
     *                  列表里希望缩略图跟着当前照片走，就把照片缩好传进来。
     */
    public static Bitmap make(Context ctx, LutData lut, int size, int[] reference) {
        if (lut == null || lut.size < 2 || lut.rgb == null) {
            return null;
        }
        int[] pixels = reference != null && reference.length == size * size
                ? reference
                : loadReference(ctx, size);
        return pixels == null ? makeColorWheel(lut, size) : makeFromReference(lut, size, pixels);
    }

    /** 把任意照片裁成居中正方形并缩到 size，给缩略图当参考 */
    public static int[] squareReference(Bitmap source, int size) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        try {
            int w = source.getWidth();
            int h = source.getHeight();
            float scale = size / (float) Math.min(w, h);
            int sw = Math.max(size, Math.round(w * scale));
            int sh = Math.max(size, Math.round(h * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(source, sw, sh, true);
            Bitmap square = Bitmap.createBitmap(scaled, (sw - size) / 2, (sh - size) / 2,
                    size, size);
            int[] pixels = new int[size * size];
            square.getPixels(pixels, 0, size, 0, 0, size, size);
            if (square != scaled && square != source) {
                square.recycle();
            }
            if (scaled != source) {
                scaled.recycle();
            }
            return pixels;
        } catch (Throwable t) {
            Log.w(TAG, "生成参考图失败：" + t.getMessage());
            return null;
        }
    }

    private static Bitmap makeFromReference(LutData lut, int size, int[] ref) {
        int[] out = new int[size * size];
        for (int i = 0; i < out.length; i++) {
            int px = ref[i];
            float r = ((px >> 16) & 0xFF) / 255f;
            float g = ((px >> 8) & 0xFF) / 255f;
            float b = (px & 0xFF) / 255f;
            float[] graded = sample(lut, r, g, b);
            out[i] = 0xFF000000
                    | ((int) (clamp01(graded[0]) * 255) << 16)
                    | ((int) (clamp01(graded[1]) * 255) << 8)
                    | (int) (clamp01(graded[2]) * 255);
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap makeColorWheel(LutData lut, int size) {
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            float v = y / (float) (size - 1);
            for (int x = 0; x < size; x++) {
                float u = x / (float) (size - 1);
                float r;
                float g;
                float b;
                if (v < 0.46f) {
                    r = g = b = u;
                } else {
                    float hue = u * 6f;
                    float level = 1f - (v - 0.46f) / 0.54f * 0.85f;
                    float[] rgb = hueToRgb(hue);
                    r = rgb[0] * level;
                    g = rgb[1] * level;
                    b = rgb[2] * level;
                }
                float[] graded = sample(lut, r, g, b);
                pixels[y * size + x] = 0xFF000000
                        | ((int) (clamp01(graded[0]) * 255) << 16)
                        | ((int) (clamp01(graded[1]) * 255) << 8)
                        | (int) (clamp01(graded[2]) * 255);
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    /** 和 GPU 里的 TEXTURE_3D + LINEAR 等价的三线性插值 */
    private static float[] sample(LutData lut, float r, float g, float b) {
        int n = lut.size;
        float[] min = lut.domainMin;
        float[] range = lut.domainRange;
        float fx = clamp01((r - min[0]) / Math.max(range[0], 1e-6f)) * (n - 1);
        float fy = clamp01((g - min[1]) / Math.max(range[1], 1e-6f)) * (n - 1);
        float fz = clamp01((b - min[2]) / Math.max(range[2], 1e-6f)) * (n - 1);

        int x0 = (int) fx;
        int y0 = (int) fy;
        int z0 = (int) fz;
        int x1 = Math.min(x0 + 1, n - 1);
        int y1 = Math.min(y0 + 1, n - 1);
        int z1 = Math.min(z0 + 1, n - 1);
        float tx = fx - x0;
        float ty = fy - y0;
        float tz = fz - z0;

        float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            float c000 = at(lut, x0, y0, z0, c);
            float c001 = at(lut, x0, y0, z1, c);
            float c010 = at(lut, x0, y1, z0, c);
            float c011 = at(lut, x0, y1, z1, c);
            float c100 = at(lut, x1, y0, z0, c);
            float c101 = at(lut, x1, y0, z1, c);
            float c110 = at(lut, x1, y1, z0, c);
            float c111 = at(lut, x1, y1, z1, c);

            float c00 = c000 + (c001 - c000) * tz;
            float c01 = c010 + (c011 - c010) * tz;
            float c10 = c100 + (c101 - c100) * tz;
            float c11 = c110 + (c111 - c110) * tz;

            float c0 = c00 + (c01 - c00) * ty;
            float c1 = c10 + (c11 - c10) * ty;
            out[c] = c0 + (c1 - c0) * tx;
        }
        return out;
    }

    private static float at(LutData lut, int x, int y, int z, int c) {
        int index = ((z * lut.size + y) * lut.size + x) * 3 + c;
        return index >= 0 && index < lut.rgb.length ? lut.rgb[index] : 0f;
    }

    private static float[] hueToRgb(float h) {
        float x = 1f - Math.abs((h % 2f) - 1f);
        if (h < 1f) {
            return new float[]{1f, x, 0f};
        } else if (h < 2f) {
            return new float[]{x, 1f, 0f};
        } else if (h < 3f) {
            return new float[]{0f, 1f, x};
        } else if (h < 4f) {
            return new float[]{0f, x, 1f};
        } else if (h < 5f) {
            return new float[]{x, 0f, 1f};
        }
        return new float[]{1f, 0f, x};
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
