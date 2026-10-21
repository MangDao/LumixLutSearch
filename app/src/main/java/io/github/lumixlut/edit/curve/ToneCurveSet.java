package io.github.lumixlut.edit.curve;

/**
 * 四条曲线：主曲线 + RGB 三条。烘焙成一张 256×4 的 RGBA 纹理，
 * 每行一条曲线（行序：主 / R / G / B），每像素四个通道填同一个值，
 * 这样 shader 里查哪一维都拿到同样的结果。
 */
public final class ToneCurveSet {

    public final ToneCurve master = new ToneCurve();
    public final ToneCurve red = new ToneCurve();
    public final ToneCurve green = new ToneCurve();
    public final ToneCurve blue = new ToneCurve();

    /** 行序，shader 里用 (row + 0.5) / 4 定位 */
    public static final int ROW_MASTER = 0;
    public static final int ROW_RED = 1;
    public static final int ROW_GREEN = 2;
    public static final int ROW_BLUE = 3;

    public void reset() {
        master.reset();
        red.reset();
        green.reset();
        blue.reset();
    }

    public boolean isIdentity() {
        return master.isIdentity() && red.isIdentity()
                && green.isIdentity() && blue.isIdentity();
    }

    public ToneCurve byRow(int row) {
        switch (row) {
            case ROW_RED:
                return red;
            case ROW_GREEN:
                return green;
            case ROW_BLUE:
                return blue;
            default:
                return master;
        }
    }

    /** 256 × 4 × RGBA8 = 4096 字节 */
    public byte[] toTexture() {
        byte[] out = new byte[ToneCurve.SAMPLES * 4 * 4];
        float[] baked = new float[ToneCurve.SAMPLES];
        ToneCurve[] rows = {master, red, green, blue};
        int offset = 0;
        for (ToneCurve curve : rows) {
            curve.bake(baked);
            for (int i = 0; i < ToneCurve.SAMPLES; i++) {
                int v = Math.round(baked[i] * 255f);
                v = v < 0 ? 0 : (v > 255 ? 255 : v);
                out[offset++] = (byte) v;
                out[offset++] = (byte) v;
                out[offset++] = (byte) v;
                out[offset++] = (byte) 255;
            }
        }
        return out;
    }

    /** 每条最多 32 个控制点，留足余量后定长 */
    private static final int SNAPSHOT_STRIDE = 1 + 32 * 2;

    /** 控制点打包，供撤销快照使用 */
    public float[] toSnapshot() {
        float[] buffer = new float[SNAPSHOT_STRIDE * 4];
        int offset = 0;
        offset = master.writeTo(buffer, offset);
        offset = red.writeTo(buffer, offset);
        offset = green.writeTo(buffer, offset);
        blue.writeTo(buffer, offset);
        return buffer;
    }

    public void applySnapshot(float[] buffer) {
        if (buffer == null || buffer.length < SNAPSHOT_STRIDE * 4) {
            return;
        }
        int offset = 0;
        offset = master.readFrom(buffer, offset);
        offset = red.readFrom(buffer, offset);
        offset = green.readFrom(buffer, offset);
        blue.readFrom(buffer, offset);
    }
}
