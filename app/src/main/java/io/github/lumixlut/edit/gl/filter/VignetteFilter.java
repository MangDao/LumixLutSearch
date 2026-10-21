package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;

/**
 * 暗角：只压四角，中心不动。
 *
 * 用「到中心的归一化半径」做 smoothstep，从 55% 半径开始衰减到边上。
 * 径向距离按短边归一化 —— 用长边的话竖图上下会被吃得比左右狠，
 * 同一个强度在横竖构图上看起来完全不一样。
 */
public final class VignetteFilter extends GlFilter {

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform float uVignette;\n"
            + "uniform float uAspect;\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 c = src.rgb;\n"
            + "    if (uVignette <= 0.0001) {\n"
            + "        fragColor = src;\n"
            + "        return;\n"
            + "    }\n"
            + "    vec2 p = vTex - vec2(0.5);\n"
            + "    p.x *= max(uAspect, 1.0);\n"
            + "    p.y *= max(1.0 / max(uAspect, 0.0001), 1.0);\n"
            + "    float r = length(p) / 0.7071;\n"
            + "    float mask = smoothstep(0.55, 1.15, r);\n"
            + "    c *= (1.0 - mask * uVignette * 0.85);\n"
            + "    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);\n"
            + "}\n";

    private float strength;
    private int uniVignette = -1;
    private int uniAspect = -1;

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniVignette = GLES30.glGetUniformLocation(program(), "uVignette");
        uniAspect = GLES30.glGetUniformLocation(program(), "uAspect");
    }

    /** @param values EditState.packEffect()，第 3 位是暗角强度 0..1 */
    public void setValues(float[] values) {
        if (values == null || values.length < 3) {
            return;
        }
        strength = Math.max(0f, Math.min(1f, values[2]));
    }

    public boolean isNeutral() {
        return strength <= 0.0001f;
    }

    @Override
    protected void onDraw() {
        float w = Math.max(viewportWidth, 1);
        float h = Math.max(viewportHeight, 1);
        GLES30.glUniform1f(uniVignette, strength);
        GLES30.glUniform1f(uniAspect, w / h);
    }
}
