package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;

/**
 * 裁剪 + 相框，放在滤镜链最末端。
 *
 * 裁剪不用改源纹理，只改采样坐标：把源图的一个子矩形铺满整个画布。
 * 这样拖动比例时不用重传纹理，立刻出结果。
 *
 * 相框是纯几何：画面往里缩一圈，剩下那圈填颜色。三款都是同一个 shader，
 * 差别只在 inset 和颜色。底部留白那款把画面往上推，下面空出一条白带，
 * 正好留给签名或标题。
 */
public final class FrameFilter extends GlFilter {

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uCropOffset;\n"
            + "uniform vec2 uCropScale;\n"
            + "uniform int uFrameType;\n"
            + "uniform float uFrameInset;\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "void main() {\n"
            + "    vec2 p = vTex;\n"
            + "\n"
            + "    vec2 lo = vec2(uFrameInset);\n"
            + "    vec2 hi = vec2(1.0 - uFrameInset);\n"
            + "    if (uFrameType == 3) {\n"
            + "        // 底部留白：只压上边，下边空出一条带子\n"
            + "        hi.y = 1.0 - uFrameInset * 2.2;\n"
            + "    }\n"
            + "\n"
            + "    if (uFrameType != 0 && (p.x < lo.x || p.x > hi.x || p.y < lo.y || p.y > hi.y)) {\n"
            + "        vec3 edge = (uFrameType == 2) ? vec3(0.03) : vec3(0.97);\n"
            + "        fragColor = vec4(edge, 1.0);\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            + "    vec2 local = (uFrameType == 0) ? p : (p - lo) / max(hi - lo, vec2(1e-4));\n"
            + "    vec2 uv = uCropOffset + clamp(local, 0.0, 1.0) * uCropScale;\n"
            + "    fragColor = texture(uTex, uv);\n"
            + "}\n";

    private float offsetX;
    private float offsetY = 0f;
    private float scaleX = 1f;
    private float scaleY = 1f;
    private int frameType;
    private float frameInset;

    private int uniCropOffset;
    private int uniCropScale;
    private int uniFrameType;
    private int uniFrameInset;

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniCropOffset = GLES30.glGetUniformLocation(program(), "uCropOffset");
        uniCropScale = GLES30.glGetUniformLocation(program(), "uCropScale");
        uniFrameType = GLES30.glGetUniformLocation(program(), "uFrameType");
        uniFrameInset = GLES30.glGetUniformLocation(program(), "uFrameInset");
    }

    /** {offsetX, offsetY, scaleX, scaleY, frameInset} */
    public void setCrop(float[] v) {
        if (v == null || v.length < 5) {
            return;
        }
        offsetX = v[0];
        offsetY = v[1];
        scaleX = v[2];
        scaleY = v[3];
        frameInset = v[4];
    }

    public void setFrameType(int type) {
        this.frameType = type;
    }

    public boolean isNeutral() {
        return frameType == 0
                && Math.abs(scaleX - 1f) < 1e-4f
                && Math.abs(scaleY - 1f) < 1e-4f;
    }

    @Override
    protected void onDraw() {
        GLES30.glUniform2f(uniCropOffset, offsetX, offsetY);
        GLES30.glUniform2f(uniCropScale, scaleX, scaleY);
        GLES30.glUniform1i(uniFrameType, frameType);
        GLES30.glUniform1f(uniFrameInset, frameInset);
    }
}
