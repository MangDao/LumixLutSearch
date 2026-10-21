package io.github.lumixlut.edit.curve;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条色调曲线：一串归一化控制点，烘焙成 256 个采样值。
 *
 * 插值用 Catmull-Rom 切线配合三次 Hermite，最后把结果夹进 0..1。
 * 分段线性太生硬，纯 Catmull-Rom 又会在控制点密集处过冲，
 * 夹一下是最省事又不出错的折中。
 */
public final class ToneCurve {

    public static final int SAMPLES = 256;

    private final List<float[]> points = new ArrayList<>();

    public ToneCurve() {
        reset();
    }

    public void reset() {
        points.clear();
        points.add(new float[]{0f, 0f});
        points.add(new float[]{1f, 1f});
    }

    public int pointCount() {
        return points.size();
    }

    public float pointX(int index) {
        return points.get(index)[0];
    }

    public float pointY(int index) {
        return points.get(index)[1];
    }

    public boolean isIdentity() {
        if (points.size() != 2) {
            return false;
        }
        return Math.abs(points.get(0)[1]) < 1e-4f
                && Math.abs(points.get(1)[1] - 1f) < 1e-4f;
    }

    /** 找到 x 落在哪一段，返回段起点下标；没有控制点覆盖时返回 -1 */
    public int segmentFor(float x) {
        for (int i = 0; i < points.size() - 1; i++) {
            if (x >= points.get(i)[0] && x <= points.get(i + 1)[0]) {
                return i;
            }
        }
        return -1;
    }

    /** 在 x 处加一个控制点（若已有则移动它），返回它的下标 */
    public int addOrMove(float x, float y) {
        y = clamp01(y);
        for (int i = 0; i < points.size(); i++) {
            if (Math.abs(points.get(i)[0] - x) < 0.02f) {
                points.get(i)[1] = y;
                return i;
            }
        }
        // 插到按 x 排序的位置，端点不参与
        int insertAt = points.size() - 1;
        for (int i = 1; i < points.size() - 1; i++) {
            if (x < points.get(i)[0]) {
                insertAt = i;
                break;
            }
        }
        if (points.size() >= 16) {
            return -1;
        }
        points.add(insertAt, new float[]{x, y});
        normalizeX();
        return insertAt;
    }

    /** 拖动第 index 个点；端点只允许改 y，中间点 x 被前后点夹住 */
    public void move(int index, float x, float y) {
        if (index < 0 || index >= points.size()) {
            return;
        }
        float[] p = points.get(index);
        p[1] = clamp01(y);
        if (index != 0 && index != points.size() - 1) {
            float lo = points.get(index - 1)[0] + 0.01f;
            float hi = points.get(index + 1)[0] - 0.01f;
            p[0] = Math.max(lo, Math.min(hi, x));
        }
    }

    public boolean remove(int index) {
        if (index <= 0 || index >= points.size() - 1) {
            return false;
        }
        points.remove(index);
        return true;
    }

    private void normalizeX() {
        points.get(0)[0] = 0f;
        points.get(points.size() - 1)[0] = 1f;
    }

    /** 烘焙成 SAMPLES 个值 */
    public void bake(float[] out) {
        int n = points.size();
        if (n < 2) {
            for (int i = 0; i < SAMPLES; i++) {
                out[i] = i / (float) (SAMPLES - 1);
            }
            return;
        }
        for (int s = 0; s < SAMPLES; s++) {
            float x = s / (float) (SAMPLES - 1);
            out[s] = clamp01(sampleAt(x));
        }
    }

    private float sampleAt(float x) {
        int n = points.size();
        if (x <= points.get(0)[0]) {
            return points.get(0)[1];
        }
        if (x >= points.get(n - 1)[0]) {
            return points.get(n - 1)[1];
        }
        int i = 0;
        while (i < n - 2 && x > points.get(i + 1)[0]) {
            i++;
        }
        float[] p0 = points.get(i);
        float[] p1 = points.get(i + 1);
        float dx = p1[0] - p0[0];
        if (dx <= 1e-6f) {
            return p1[1];
        }
        float t = (x - p0[0]) / dx;

        float[] prev = i > 0 ? points.get(i - 1) : p0;
        float[] next = i + 2 < n ? points.get(i + 2) : p1;

        float m0 = (p1[1] - prev[1]) / Math.max(p1[0] - prev[0], 1e-6f);
        float m1 = (next[1] - p0[1]) / Math.max(next[0] - p0[0], 1e-6f);

        return hermite(p0[1], p1[1], m0 * dx, m1 * dx, t);
    }

    private static float hermite(float p0, float p1, float m0, float m1, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * p0
                + (t3 - 2 * t2 + t) * m0
                + (-2 * t3 + 3 * t2) * p1
                + (t3 - t2) * m1;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 写进扁平数组：先点数，然后 x,y 交替。返回写完后的偏移 */
    public int writeTo(float[] out, int offset) {
        out[offset] = points.size();
        int cursor = offset + 1;
        for (float[] p : points) {
            out[cursor++] = p[0];
            out[cursor++] = p[1];
        }
        return cursor;
    }

    /** 从扁平数组恢复，返回读完后的偏移 */
    public int readFrom(float[] in, int offset) {
        int count = (int) in[offset];
        if (count < 2 || count > 32) {
            reset();
            return offset + 1;
        }
        points.clear();
        int cursor = offset + 1;
        for (int i = 0; i < count; i++) {
            points.add(new float[]{in[cursor], in[cursor + 1]});
            cursor += 2;
        }
        return cursor;
    }
}
