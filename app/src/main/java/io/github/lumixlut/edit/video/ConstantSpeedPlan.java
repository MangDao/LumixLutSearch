package io.github.lumixlut.edit.video;

import java.util.Locale;

/**
 * 匀速变速：整段一个倍率。对应官方那几个固定档。
 *
 * 音频策略按实测的可行范围来定：
 *   1x        → 原音直通
 *   0.5x~2x   → atempo 拉伸，音调不变（ffmpeg 原生支持这个区间）
 *   超出区间  → 静音。5x 的 atempo 要串联好几级，质量已经很差，
 *               而且用户本来就是想看快放，声音没意义
 */
public final class ConstantSpeedPlan implements SpeedPlan {

    /** 界面上的档位顺序 */
    public static final float[] PRESETS = {5f, 2f, 1f, 0.5f, 0.2f};

    private final float speed;

    public ConstantSpeedPlan(float speed) {
        this.speed = speed;
    }

    @Override
    public float speedAt(float progress) {
        return speed;
    }

    @Override
    public float averageSpeed() {
        return speed;
    }

    @Override
    public String label() {
        return labelOf(speed);
    }

    /** 音频还能走 atempo 的区间 */
    public boolean keepsAudio() {
        return speed >= 0.5f && speed <= 2f;
    }

    public boolean needsAtempo() {
        return keepsAudio() && Math.abs(speed - 1f) > 0.001f;
    }

    public static String labelOf(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.01f) {
            return String.valueOf(Math.round(speed)) + "x";
        }
        return String.format(Locale.US, "%.1fx", speed);
    }
}
