package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;

/**
 * 清晰度 + 降噪 + 紫边去除，合在一个 pass 里做——三者都要 3×3 邻域，
 * 分开就是白跑好几遍九次采样。
 *
 * 降噪用简化双边：空间权重固定成 1，只按色差加权，色差越大越不掺和，
 * 这样边缘不会被糊掉。真正的双边要乘空间高斯，但 3×3 上差别看不出来。
 *
 * 紫边去除（官方叫「紫边去除」）：紫边几乎只长在高反差边缘上，
 * 特征是 G 明显低于 R 和 B、且 R≈B。所以只在「邻域亮度差够大」的像素上，
 * 把那点洋红往灰里拉，平地上不会误伤正常的紫色物体。
 *
 * 顺序是先降噪再锐化：反过来的话锐化会把噪点一起放大。
 */
public final class DetailFilter extends GlFilter {

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uTexel;\n"
            + "uniform float uSharpness;\n"
            + "uniform float uNoiseReduction;\n"
            + "uniform float uRpf;\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 c = src.rgb;\n"
            + "\n"
            + "    vec3 blurred = vec3(0.0);\n"
            + "    vec3 denoised = vec3(0.0);\n"
            + "    float weightSum = 0.0;\n"
            + "    float sigma = 0.025 + 0.10 * (1.0 - uNoiseReduction);\n"
            + "    float invSigma2 = 1.0 / (sigma * sigma);\n"
            + "    float lumaMin = 1.0;\n"
            + "    float lumaMax = 0.0;\n"
            + "    float lumaCenter = dot(c, LUMA);\n"
            + "\n"
            + "    for (int y = -1; y <= 1; y++) {\n"
            + "        for (int x = -1; x <= 1; x++) {\n"
            + "            vec3 s = texture(uTex, vTex + vec2(float(x), float(y)) * uTexel).rgb;\n"
            + "            blurred += s;\n"
            + "            vec3 d = s - c;\n"
            + "            float w = exp(-dot(d, d) * invSigma2);\n"
            + "            denoised += s * w;\n"
            + "            weightSum += w;\n"
            + "            float sl = dot(s, LUMA);\n"
            + "            lumaMin = min(lumaMin, sl);\n"
            + "            lumaMax = max(lumaMax, sl);\n"
            + "        }\n"
            + "    }\n"
            + "    blurred /= 9.0;\n"
            + "    denoised /= max(weightSum, 1e-4);\n"
            + "\n"
            + "    c = mix(c, denoised, uNoiseReduction);\n"
            + "    c = c + (c - blurred) * (uSharpness * 1.6);\n"
            + "\n"
            + "    if (uRpf > 0.0001) {\n"
            + "        float edge = lumaMax - lumaMin;\n"
            + "        float edgeMask = smoothstep(0.10, 0.45, edge);\n"
            // 洋红倾向：G 低于 R/B 的平均，且 R 与 B 接近
            + "        float rb = (c.r + c.b) * 0.5;\n"
            + "        float magenta = clamp((rb - c.g) * 6.0, 0.0, 1.0);\n"
            + "        float balance = 1.0 - clamp(abs(c.r - c.b) * 4.0, 0.0, 1.0);\n"
            + "        float kill = magenta * balance * edgeMask * uRpf;\n"
            + "        float gray = dot(c, LUMA);\n"
            + "        c = mix(c, vec3(gray), kill * 0.85);\n"
            + "        fragColor = vec4(clamp(c, 0.0, 1.0), src.a);\n"
            + "        return;\n"
            + "    }\n"
            + "\n"
            + "    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);\n"
            + "}\n";

    private float sharpness;
    private float noiseReduction;
    private float rpf;
    private int uniTexel;
    private int uniSharpness;
    private int uniNoiseReduction;
    private int uniRpf;

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniTexel = GLES30.glGetUniformLocation(program(), "uTexel");
        uniSharpness = GLES30.glGetUniformLocation(program(), "uSharpness");
        uniNoiseReduction = GLES30.glGetUniformLocation(program(), "uNoiseReduction");
        uniRpf = GLES30.glGetUniformLocation(program(), "uRpf");
    }

    /** @param v EditState.packDetail() = {清晰度, 降噪, 紫边去除} */
    public void setValues(float[] v) {
        if (v == null || v.length < 2) {
            return;
        }
        sharpness = v[0];
        noiseReduction = v[1];
        rpf = v.length >= 3 ? v[2] : 0f;
    }

    public boolean isNeutral() {
        return sharpness < 0.0001f && noiseReduction < 0.0001f && rpf < 0.0001f;
    }

    @Override
    protected void onDraw() {
        float w = Math.max(viewportWidth, 1);
        float h = Math.max(viewportHeight, 1);
        GLES30.glUniform2f(uniTexel, 1f / w, 1f / h);
        GLES30.glUniform1f(uniSharpness, sharpness);
        GLES30.glUniform1f(uniNoiseReduction, noiseReduction);
        GLES30.glUniform1f(uniRpf, rpf);
    }
}
