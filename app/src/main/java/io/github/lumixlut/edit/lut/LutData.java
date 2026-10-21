package io.github.lumixlut.edit.lut;

/** 一份解析好的 3D LUT。rgb 按 R 变化最快的顺序排列，正好对上 GL_TEXTURE_3D 的 x→R、y→G、z→B。 */
public final class LutData {

    public final int size;
    public final float[] rgb;
    public final float[] domainMin;
    public final float[] domainRange;
    public final String title;
    /**
     * LUT 期望的输入素材格调，来自文件里的 {@code #LUMIXPHOTOSTYLE STD} 注释。
     * 实测结论：有这行的都是"风格化 LUT"，输入就该是相机直出的那个格调，
     * 不是 V-Log。所以默认直通，只有用户明确知道素材是 log 时才转。
     */
    public final String targetStyle;

    public LutData(int size, float[] rgb, float[] domainMin, float[] domainRange,
                   String title, String targetStyle) {
        this.size = size;
        this.rgb = rgb;
        this.domainMin = domainMin;
        this.domainRange = domainRange;
        this.title = title;
        this.targetStyle = targetStyle;
    }

    /** 原样不动的 LUT，用来在没有选 LUT 时占位，顺便验证管线是否通 */
    public static LutData identity(int size) {
        float[] data = new float[size * size * size * 3];
        int i = 0;
        float step = 1f / (size - 1);
        for (int b = 0; b < size; b++) {
            for (int g = 0; g < size; g++) {
                for (int r = 0; r < size; r++) {
                    data[i++] = r * step;
                    data[i++] = g * step;
                    data[i++] = b * step;
                }
            }
        }
        return new LutData(size, data,
                new float[]{0f, 0f, 0f},
                new float[]{1f, 1f, 1f},
                "Identity", null);
    }
}
