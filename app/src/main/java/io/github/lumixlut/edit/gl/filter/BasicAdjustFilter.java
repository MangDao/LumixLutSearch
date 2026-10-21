package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;

/**
 * 「亮度」+「色彩」两组调整，合成一个 pass 做完。
 *
 * 官方把它们拆成两组滑杆，但底层是同一段色彩管线，拆成两个 pass 只是多一次全屏采样。
 * 这里一次采样把 10 个参数全算完。
 *
 * 顺序按色彩管线的常规做法：
 *   黑白电平 → 曝光 → 白平衡 → 对比 → 阴影/突出显示 → 饱和度 → 自然饱和度。
 * 顺序错了会出「调对比把白平衡带跑」这类怪事。
 */
public final class BasicAdjustFilter extends GlFilter {

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform float uExposure;\n"
            + "uniform float uContrast;\n"
            + "uniform float uHighlight;\n"
            + "uniform float uShadow;\n"
            + "uniform float uWhite;\n"
            + "uniform float uBlack;\n"
            + "uniform float uTemperature;\n"
            + "uniform float uTint;\n"
            + "uniform float uVibrance;\n"
            + "uniform float uSaturation;\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 c = src.rgb;\n"
            + "\n"
            // 黑电平：把黑场往上抬，暗部被压掉一点 -> 反差变大
            + "    float black = uBlack * 0.22;\n"
            + "    c = (c - black) / max(0.05, 1.0 - black);\n"
            // 白电平：把白场往下拉，亮部更容易打满
            + "    float white = uWhite * 0.22;\n"
            + "    c = c / max(0.05, 1.0 - white);\n"
            + "\n"
            + "    c *= pow(2.0, uExposure * 2.0);\n"
            + "\n"
            + "    c.r += uTemperature * 0.10;\n"
            + "    c.b -= uTemperature * 0.10;\n"
            + "    c.g -= uTint * 0.06;\n"
            + "    c.r += uTint * 0.03;\n"
            + "    c.b += uTint * 0.03;\n"
            + "\n"
            + "    c = (c - 0.5) * (1.0 + uContrast * 0.9) + 0.5;\n"
            + "\n"
            + "    c = clamp(c, 0.0, 1.0);\n"
            + "    float luma = dot(c, LUMA);\n"
            + "\n"
            + "    float shadowMask = 1.0 - smoothstep(0.0, 0.55, luma);\n"
            + "    c += uShadow * shadowMask * 0.30;\n"
            + "\n"
            + "    float highMask = smoothstep(0.45, 1.0, luma);\n"
            + "    c -= uHighlight * highMask * 0.30;\n"
            + "\n"
            + "    luma = dot(c, LUMA);\n"
            + "    c = mix(vec3(luma), c, 1.0 + uSaturation);\n"
            + "\n"
            + "    float mx = max(c.r, max(c.g, c.b));\n"
            + "    float mn = min(c.r, min(c.g, c.b));\n"
            + "    float chroma = clamp(mx - mn, 0.0, 1.0);\n"
            + "    float vibranceGain = uVibrance * (1.0 - chroma);\n"
            + "    c = mix(vec3(luma), c, 1.0 + vibranceGain);\n"
            + "\n"
            + "    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);\n"
            + "}\n";

    private static final String[] NAMES = {
            "uExposure", "uContrast", "uHighlight", "uShadow", "uWhite", "uBlack",
            "uTemperature", "uTint", "uVibrance", "uSaturation"
    };

    private final float[] values = new float[NAMES.length];
    private final int[] uniforms = new int[NAMES.length];

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        for (int i = 0; i < NAMES.length; i++) {
            uniforms[i] = GLES30.glGetUniformLocation(program(), NAMES[i]);
        }
    }

    /** @param light EditState.packLight()  @param color EditState.packColor() */
    public void setValues(float[] light, float[] color) {
        if (light != null && light.length >= 6) {
            System.arraycopy(light, 0, values, 0, 6);
        }
        if (color != null && color.length >= 4) {
            System.arraycopy(color, 0, values, 6, 4);
        }
    }

    public boolean isNeutral() {
        for (float v : values) {
            if (Math.abs(v) > 0.0001f) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDraw() {
        for (int i = 0; i < values.length; i++) {
            GLES30.glUniform1f(uniforms[i], values[i]);
        }
    }
}
