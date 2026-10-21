package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;

/**
 * 色调分离：给阴影 / 中间调 / 突出显示三个亮度区段分别染一个色。
 *
 * 官方的三个区段各有一对滑杆（色相 + 饱和度），色相那一栏在界面上是条彩虹条，
 * 所以 0..1 直接映射到色相环。这里按亮度做三顶帽子盖上去：
 * 三个 mask 在中间调互相重叠，过渡才不会有硬边。
 */
public final class SplitToneFilter extends GlFilter {

    private static final int RANGES = 3;

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform float uHue[" + RANGES + "];\n"
            + "uniform float uSat[" + RANGES + "];\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n"
            + "\n"
            + "vec3 hueToRgb(float h) {\n"
            + "    vec3 p = abs(fract(vec3(h) + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);\n"
            + "    return clamp(p - 1.0, 0.0, 1.0);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 c = src.rgb;\n"
            + "    float luma = dot(clamp(c, 0.0, 1.0), LUMA);\n"
            + "\n"
            + "    float mShadow = 1.0 - smoothstep(0.05, 0.55, luma);\n"
            + "    float mMid = smoothstep(0.15, 0.50, luma) * (1.0 - smoothstep(0.50, 0.85, luma));\n"
            + "    float mHigh = smoothstep(0.45, 0.95, luma);\n"
            + "\n"
            + "    for (int i = 0; i < " + RANGES + "; i++) {\n"
            + "        float amount = uSat[i];\n"
            + "        if (amount <= 0.0001) {\n"
            + "            continue;\n"
            + "        }\n"
            + "        float mask = (i == 0) ? mShadow : ((i == 1) ? mMid : mHigh);\n"
            + "        if (mask <= 0.0001) {\n"
            + "            continue;\n"
            + "        }\n"
            + "        vec3 tint = hueToRgb(uHue[i]);\n"
            // 把纯色按亮度方向推：亮部往纯色靠一点，暗部只染色相不抬亮度
            + "        vec3 target = mix(vec3(luma), tint, 0.45);\n"
            + "        c = mix(c, target, amount * mask * 0.75);\n"
            + "    }\n"
            + "\n"
            + "    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);\n"
            + "}\n";

    private final float[] hue = new float[]{0.03f, 0.12f, 0.55f};
    private final float[] sat = new float[RANGES];

    private int uniHue = -1;
    private int uniSat = -1;

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniHue = GLES30.glGetUniformLocation(program(), "uHue");
        uniSat = GLES30.glGetUniformLocation(program(), "uSat");
    }

    /** @param values EditState.packSplit()，3 组 (色相 0..1, 饱和度 0..1) */
    public void setValues(float[] values) {
        if (values == null || values.length < RANGES * 2) {
            return;
        }
        for (int i = 0; i < RANGES; i++) {
            hue[i] = values[i * 2];
            sat[i] = values[i * 2 + 1];
        }
    }

    public boolean isNeutral() {
        for (int i = 0; i < RANGES; i++) {
            if (sat[i] > 0.0001f) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDraw() {
        GLES30.glUniform1fv(uniHue, RANGES, hue, 0);
        GLES30.glUniform1fv(uniSat, RANGES, sat, 0);
    }
}
