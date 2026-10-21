package io.github.lumixlut.edit.video;

/**
 * 曲线变速：整段的倍率随时间变化。
 *
 * 内部存成等间隔采样点，取任意位置线性插值。用采样点而不是连续函数，
 * 是因为 ffmpeg 那边本来就要切段拼接，段数固定，采样点和段一一对应最省事。
 */
public final class CurveSpeedPlan implements SpeedPlan {

    /** 切多少段近似曲线。12 段在 5 秒视频上是每段 0.4 秒，够平滑了 */
    public static final int SEGMENTS = 12;

    private final float[] speeds;

    public CurveSpeedPlan(float[] speeds) {
        this.speeds = speeds == null || speeds.length == 0
                ? new float[]{1f} : speeds.clone();
    }

    /** 慢入快出：2x 起，5x 收 */
    public static CurveSpeedPlan easeIn() {
        float[] s = new float[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            float t = i / (float) (SEGMENTS - 1);
            s[i] = 2f + 3f * t * t;
        }
        return new CurveSpeedPlan(s);
    }

    /** 快入慢出：5x 起，2x 收 */
    public static CurveSpeedPlan easeOut() {
        float[] s = new float[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            float t = i / (float) (SEGMENTS - 1);
            s[i] = 5f - 3f * t * t;
        }
        return new CurveSpeedPlan(s);
    }

    /** 中间最快：2x → 5x → 2x，官方那种「整段 5x-1x-5x」的味道 */
    public static CurveSpeedPlan peak() {
        float[] s = new float[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            float t = i / (float) (SEGMENTS - 1);
            // 抛物线：两端 2x，中间 5x
            float k = (t - 0.5f) * 2f;
            s[i] = 5f - 3f * k * k;
        }
        return new CurveSpeedPlan(s);
    }

    @Override
    public float speedAt(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        float x = p * (speeds.length - 1);
        int i = (int) x;
        if (i >= speeds.length - 1) {
            return speeds[speeds.length - 1];
        }
        float t = x - i;
        return speeds[i] + (speeds[i + 1] - speeds[i]) * t;
    }

    /** 段倍率直接取段中点采样，避免边界跳变 */
    public float segmentSpeed(int segment) {
        return speedAt((segment + 0.5f) / SEGMENTS);
    }

    @Override
    public float averageSpeed() {
        float sum = 0f;
        for (int i = 0; i < SEGMENTS; i++) {
            sum += segmentSpeed(i);
        }
        return sum / SEGMENTS;
    }

    @Override
    public String label() {
        return "曲线 " + ConstantSpeedPlan.labelOf(speedAt(0f))
                + "→" + ConstantSpeedPlan.labelOf(speedAt(1f));
    }
}
