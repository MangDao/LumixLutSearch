package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;

/**
 * 颗粒 + 色彩噪点。两者都是程序化噪声，不需要邻域采样，一个 pass 搞定。
 *
 * 颗粒是亮度噪声，而且乘了个中间调掩膜——真实胶片和相机高感的颗粒
 * 都在中性灰附近最明显，纯黑纯白那头反而干净。不做这个掩膜会得到
 * 一层浮在画面上的"电子雪"。
 *
 * 色彩噪点走 RGB 三通道独立噪声，模拟高 ISO 的彩斑。
 */
public final class NoiseFilter extends GlFilter {

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uResolution;\n"
            + "uniform float uGrain;\n"
            + "uniform float uColorNoise;\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n"
            + "\n"
            + "float hash(vec2 p) {\n"
            + "    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 c = src.rgb;\n"
            + "    vec2 p = vTex * uResolution;\n"
            + "\n"
            + "    if (uGrain > 0.001) {\n"
            + "        float n = hash(p) - 0.5;\n"
            + "        float luma = dot(c, LUMA);\n"
            + "        float mask = 1.0 - abs(luma - 0.5) * 1.4;\n"
            + "        c += n * uGrain * 0.20 * clamp(mask, 0.0, 1.0);\n"
            + "    }\n"
            + "\n"
            + "    if (uColorNoise > 0.001) {\n"
            + "        vec3 n3 = vec3(hash(p + 17.0), hash(p + 43.0), hash(p + 91.0)) - 0.5;\n"
            + "        c += n3 * uColorNoise * 0.12;\n"
            + "    }\n"
            + "\n"
            + "    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);\n"
            + "}\n";

    private float grain;
    private float colorNoise;
    private int uniResolution;
    private int uniGrain;
    private int uniColorNoise;

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniResolution = GLES30.glGetUniformLocation(program(), "uResolution");
        uniGrain = GLES30.glGetUniformLocation(program(), "uGrain");
        uniColorNoise = GLES30.glGetUniformLocation(program(), "uColorNoise");
    }

    public void setValues(float[] v) {
        if (v == null || v.length < 2) {
            return;
        }
        grain = v[0];
        colorNoise = v[1];
    }

    public boolean isNeutral() {
        return grain < 0.0001f && colorNoise < 0.0001f;
    }

    @Override
    protected void onDraw() {
        GLES30.glUniform2f(uniResolution,
                Math.max(viewportWidth, 1), Math.max(viewportHeight, 1));
        GLES30.glUniform1f(uniGrain, grain);
        GLES30.glUniform1f(uniColorNoise, colorNoise);
    }
}
