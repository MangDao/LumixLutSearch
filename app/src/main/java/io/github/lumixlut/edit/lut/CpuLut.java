package io.github.lumixlut.edit.lut;

import android.graphics.Bitmap;

/**
 * 在 CPU 上把 LUT 套到一张图上。
 *
 * 编辑页那条路走 GPU（GL_TEXTURE_3D），但详情页只是要一张静态对比图，
 * 为它起一个 EGL 线程太重了，直接逐像素算反而简单。
 * 三线性插值和着色器里那段是同一个算法。
 */
public final class CpuLut {

    private CpuLut() {
    }

    /**
     * @param intensity 0~1，和编辑页那个「浓度」一个意思
     * @return 新位图；lut 不合法时返回原图
     */
    public static Bitmap apply(Bitmap source, LutData lut, float intensity) {
        if (source == null || lut == null || lut.size < 2 || lut.rgb == null) {
            return source;
        }
        float mix = Math.max(0f, Math.min(1f, intensity));
        int w = source.getWidth();
        int h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            float r = ((px >> 16) & 0xFF) / 255f;
            float g = ((px >> 8) & 0xFF) / 255f;
            float b = (px & 0xFF) / 255f;
            float[] graded = sample(lut, r, g, b);
            int nr = (int) (clamp01(r + (graded[0] - r) * mix) * 255);
            int ng = (int) (clamp01(g + (graded[1] - g) * mix) * 255);
            int nb = (int) (clamp01(b + (graded[2] - b) * mix) * 255);
            pixels[i] = (px & 0xFF000000) | (nr << 16) | (ng << 8) | nb;
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    static float[] sample(LutData lut, float r, float g, float b) {
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

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
