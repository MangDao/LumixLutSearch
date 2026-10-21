package io.github.lumixlut.edit;

/**
 * 一次编辑的全部参数。目前是可变对象，主线程改、渲染线程读，
 * 每次改动都会重新打包成一个 float[] 传过去，所以读的时候不会读到半截状态。
 *
 * 分组照 LUMIX Lab 官方那三层走（名字都和官方中文一致）：
 *
 *   亮度：曝光 / 对比度 / 突出显示 / 阴影 / 白电平 / 黑电平
 *   色彩：白平衡（色温）/ 色调 / 自然饱和度 / 饱和度
 *   工具→细节：清晰度 / 降噪 / 紫边去除
 *   工具→效果：颗粒效果 / 彩色噪点 / 暗角强度
 *   工具→HSL：8 个色相 × 色彩/饱和度/辉度
 *   工具→色调分离：阴影/中间调/突出显示 × 色相/饱和度
 *
 * 数值约定：双向项都归一化到 -1..+1，单向项（清晰度、降噪这种）0..1。
 * 界面上乘 100 显示成官方那种 -100..+100 的整数，因为官方就是整数。
 */
public final class EditState {

    /** HSL 的 8 个色相，官方那排色板 */
    public static final int HSL_COLORS = 8;
    public static final int HSL_STRIDE = 3;
    /** 色调分离的三个区段：阴影 / 中间调 / 突出显示 */
    public static final int SPLIT_RANGES = 3;
    public static final int SPLIT_STRIDE = 2;

    public float lutIntensity = 1f;
    public int sourceMode;

    // ---- 亮度 ----
    public float exposure;
    public float contrast;
    public float highlight;
    public float shadow;
    public float whiteLevel;
    public float blackLevel;

    // ---- 色彩 ----
    public float temperature;
    public float tint;
    public float vibrance;
    public float saturation;

    // ---- 细节（0..1 单向）----
    public float sharpness;
    public float noiseReduction;
    /** 紫边去除 */
    public float rpf;

    // ---- 效果（0..1 单向）----
    public float grain;
    public float colorNoise;
    /** 暗角强度 */
    public float vignette;

    /** 8 色 × (色彩, 饱和度, 辉度)，都 -1..+1 */
    public final float[] hsl = new float[HSL_COLORS * HSL_STRIDE];
    /** 3 区段 × (色相, 饱和度)，色相 0..1、饱和度 0..1 */
    public final float[] splitTone = new float[SPLIT_RANGES * SPLIT_STRIDE];

    /** 裁剪比例，0 表示不裁；其余是 宽/高（例如 1.0 = 1:1） */
    public float cropAspect;
    /** 裁剪区域，归一化到原图：offset 是左下角，scale 是宽高占比 */
    public float cropOffsetX;
    public float cropOffsetY;
    public float cropScaleX = 1f;
    public float cropScaleY = 1f;

    /** 0 无边框 / 1 细白边 / 2 粗黑边 / 3 底部留白 */
    public int frameType;
    /** 边框占画布短边的比例 */
    public float frameInset;

    public float[] packFrame() {
        return new float[]{cropOffsetX, cropOffsetY, cropScaleX, cropScaleY, frameInset};
    }

    public float[] packLight() {
        return new float[]{exposure, contrast, highlight, shadow, whiteLevel, blackLevel};
    }

    public float[] packColor() {
        return new float[]{temperature, tint, vibrance, saturation};
    }

    public float[] packDetail() {
        return new float[]{sharpness, noiseReduction, rpf};
    }

    public float[] packEffect() {
        return new float[]{grain, colorNoise, vignette};
    }

    public float[] packHsl() {
        return hsl.clone();
    }

    public float[] packSplit() {
        return splitTone.clone();
    }

    /** 除了 LUT 浓度以外，有没有动过调整项 */
    public boolean hasAdjustments() {
        for (float v : packLight()) {
            if (Math.abs(v) > 0.0001f) {
                return true;
            }
        }
        for (float v : packColor()) {
            if (Math.abs(v) > 0.0001f) {
                return true;
            }
        }
        for (float v : packDetail()) {
            if (v > 0.0001f) {
                return true;
            }
        }
        for (float v : packEffect()) {
            if (v > 0.0001f) {
                return true;
            }
        }
        for (float v : hsl) {
            if (Math.abs(v) > 0.0001f) {
                return true;
            }
        }
        for (float v : splitTone) {
            if (Math.abs(v) > 0.0001f) {
                return true;
            }
        }
        return false;
    }

    public void resetLight() {
        exposure = 0f;
        contrast = 0f;
        highlight = 0f;
        shadow = 0f;
        whiteLevel = 0f;
        blackLevel = 0f;
    }

    public void resetColor() {
        temperature = 0f;
        tint = 0f;
        vibrance = 0f;
        saturation = 0f;
    }

    public void resetDetail() {
        sharpness = 0f;
        noiseReduction = 0f;
        rpf = 0f;
    }

    public void resetEffect() {
        grain = 0f;
        colorNoise = 0f;
        vignette = 0f;
    }

    public void resetHsl() {
        java.util.Arrays.fill(hsl, 0f);
    }

    public void resetSplit() {
        java.util.Arrays.fill(splitTone, 0f);
    }

    public void resetAdjustments() {
        resetLight();
        resetColor();
        resetDetail();
        resetEffect();
        resetHsl();
        resetSplit();
    }

    private static void copy(float[] src, float[] dst) {
        if (src == null) {
            return;
        }
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
    }

    /** 用快照里的数组把数值恢复回来（按长度判断版本，旧草稿少几个数组也能读） */
    public void restore(float[] light, float[] color, float[] detail, float[] effect,
                        float[] crop, float[] hslValues, float[] splitValues) {
        if (light != null && light.length >= 9) {
            // 旧草稿：一个数组塞了 曝光/亮度/对比度/高光/阴影 + 色温/色调/饱和/自然饱和。
            // 「亮度」在新版里没有对应项（官方光比组是曝光/对比/突出显示/阴影/白电平/黑电平），
            // 直接丢掉，别把它错当地塞进对比度。
            exposure = light[0];
            contrast = light[2];
            highlight = light[3];
            shadow = light[4];
            whiteLevel = 0f;
            blackLevel = 0f;
            if (color == null || color.length < 4) {
                temperature = light[5];
                tint = light[6];
                saturation = light[7];
                vibrance = light[8];
            }
        } else if (light != null && light.length >= 6) {
            exposure = light[0];
            contrast = light[1];
            highlight = light[2];
            shadow = light[3];
            whiteLevel = light[4];
            blackLevel = light[5];
        }
        if (color != null && color.length >= 4) {
            temperature = color[0];
            tint = color[1];
            vibrance = color[2];
            saturation = color[3];
        }
        if (detail != null && detail.length >= 2) {
            sharpness = detail[0];
            noiseReduction = detail[1];
            rpf = detail.length >= 3 ? detail[2] : 0f;
        }
        if (effect != null && effect.length >= 2) {
            grain = effect[0];
            colorNoise = effect[1];
            vignette = effect.length >= 3 ? effect[2] : 0f;
        }
        if (crop != null && crop.length >= 5) {
            cropOffsetX = crop[0];
            cropOffsetY = crop[1];
            cropScaleX = crop[2];
            cropScaleY = crop[3];
            frameInset = crop[4];
        }
        copy(hslValues, hsl);
        copy(splitValues, splitTone);
    }
}
