package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;

/**
 * HSL：官方那排 8 个色板，每个色板下面三条滑杆（色彩 / 饱和度 / 辉度）。
 *
 * 做法就是常规的「按色相分带」：
 *   把像素转到 HSV，看它的色相离 8 个带中心各有多远，
 *   用一圈三角权重把三组调整按距离摊开，再转回 RGB。
 * 一次 pass 算完 8 个带，不拆。
 *
 * 色带中心和界面上那排水晶球一一对应（见 EditActivity.HSL_COLORS / HSL_CENTERS）。
 */
public final class HslFilter extends GlFilter {

    /** 和 EditState.HSL_COLORS 一致 */
    private static final int BANDS = 8;

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform float uHue[" + BANDS + "];\n"
            + "uniform float uSat[" + BANDS + "];\n"
            + "uniform float uLum[" + BANDS + "];\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "const float CENTERS[" + BANDS + "] = float[" + BANDS + "]("
            + "0.0, 30.0, 60.0, 120.0, 190.0, 240.0, 275.0, 315.0);\n"
            + "\n"
            + "vec3 rgb2hsv(vec3 c) {\n"
            + "    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n"
            + "    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n"
            + "    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n"
            + "    float d = q.x - min(q.w, q.y);\n"
            + "    float e = 1.0e-10;\n"
            + "    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n"
            + "}\n"
            + "\n"
            + "vec3 hsv2rgb(vec3 c) {\n"
            + "    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);\n"
            + "    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 c = src.rgb;\n"
            + "    vec3 hsv = rgb2hsv(c);\n"
            + "    float deg = hsv.x * 360.0;\n"
            + "\n"
            + "    float shift = 0.0;\n"
            + "    float satGain = 0.0;\n"
            + "    float lumGain = 0.0;\n"
            + "    float wsum = 0.0;\n"
            + "\n"
            + "    for (int i = 0; i < " + BANDS + "; i++) {\n"
            + "        float d = abs(deg - CENTERS[i]);\n"
            + "        d = min(d, 360.0 - d);\n"
            + "        float w = max(0.0, 1.0 - d / 45.0);\n"
            + "        if (w <= 0.0) {\n"
            + "            continue;\n"
            + "        }\n"
            + "        shift += uHue[i] * w * 30.0;\n"
            + "        satGain += uSat[i] * w;\n"
            + "        lumGain += uLum[i] * w;\n"
            + "        wsum += w;\n"
            + "    }\n"
            + "\n"
            // 没有落在任何色带上的像素（灰、极暗、色相在缝里）：原样返回，省一次转换
            + "    if (wsum <= 0.0001) {\n"
            + "        fragColor = src;\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            // 饱和度低的像素不该被色相调整动到，按 sat 本身再乘一次权重
            + "    float satWeight = smoothstep(0.02, 0.20, hsv.y);\n"
            + "    hsv.x = fract(hsv.x + (shift / 360.0) * satWeight);\n"
            + "    hsv.y = clamp(hsv.y * (1.0 + satGain * 0.8 * satWeight), 0.0, 1.0);\n"
            + "    hsv.z = clamp(hsv.z + lumGain * 0.18 * satWeight, 0.0, 1.0);\n"
            + "\n"
            + "    fragColor = vec4(hsv2rgb(hsv), src.a);\n"
            + "}\n";

    private final float[] hue = new float[BANDS];
    private final float[] sat = new float[BANDS];
    private final float[] lum = new float[BANDS];

    private int uniHue = -1;
    private int uniSat = -1;
    private int uniLum = -1;

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniHue = GLES30.glGetUniformLocation(program(), "uHue");
        uniSat = GLES30.glGetUniformLocation(program(), "uSat");
        uniLum = GLES30.glGetUniformLocation(program(), "uLum");
    }

    /** @param values EditState.packHsl()，8 组 (色彩, 饱和度, 辉度) 摊平 */
    public void setValues(float[] values) {
        if (values == null || values.length < BANDS * 3) {
            return;
        }
        for (int i = 0; i < BANDS; i++) {
            hue[i] = values[i * 3];
            sat[i] = values[i * 3 + 1];
            lum[i] = values[i * 3 + 2];
        }
    }

    public boolean isNeutral() {
        for (int i = 0; i < BANDS; i++) {
            if (Math.abs(hue[i]) > 0.0001f || Math.abs(sat[i]) > 0.0001f
                    || Math.abs(lum[i]) > 0.0001f) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDraw() {
        GLES30.glUniform1fv(uniHue, BANDS, hue, 0);
        GLES30.glUniform1fv(uniSat, BANDS, sat, 0);
        GLES30.glUniform1fv(uniLum, BANDS, lum, 0);
    }
}
