package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import io.github.lumixlut.edit.curve.ToneCurve;
import io.github.lumixlut.edit.gl.GlFilter;

/**
 * 色调曲线：一张 256×4 的查找表纹理，shader 里做一维查表。
 *
 * 不在 shader 里算样条，是因为每条曲线要 256 个点、还要四条，
 * 逐像素跑样条太浪费；烘焙成纹理后就是一次带插值的贴图采样，几乎不花钱。
 *
 * 行序：主曲线 / R / G / B，定位用 (row + 0.5) / 4。
 * 处理顺序按 Lightroom 那套：先主曲线调形，再单通道调色。
 */
public final class ToneCurveFilter extends GlFilter {

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform sampler2D uCurve;\n"
            + "uniform int uHasCurve;\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "float curveAt(float v, float row) {\n"
            + "    return texture(uCurve, vec2(clamp(v, 0.0, 1.0), row)).r;\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 c = src.rgb;\n"
            + "    if (uHasCurve == 1) {\n"
            + "        c = vec3(curveAt(c.r, 0.125), curveAt(c.g, 0.125), curveAt(c.b, 0.125));\n"
            + "        c.r = curveAt(c.r, 0.375);\n"
            + "        c.g = curveAt(c.g, 0.625);\n"
            + "        c.b = curveAt(c.b, 0.875);\n"
            + "    }\n"
            + "    fragColor = vec4(c, src.a);\n"
            + "}\n";

    private int textureId;
    private boolean hasCurve;
    private int uniCurve;
    private int uniHasCurve;

    /** 传进来的必须是 ToneCurveSet.toTexture() 的 256*4*4 字节 */
    public void upload(byte[] data) {
        if (data == null || data.length != ToneCurve.SAMPLES * 4 * 4) {
            return;
        }
        if (textureId == 0) {
            int[] tex = new int[1];
            GLES30.glGenTextures(1, tex, 0);
            textureId = tex[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                    ToneCurve.SAMPLES, 4, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length)
                .order(ByteOrder.nativeOrder());
        buffer.put(data);
        buffer.position(0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0,
                ToneCurve.SAMPLES, 4, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
    }

    public void setEnabled(boolean enabled) {
        this.hasCurve = enabled;
    }

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniCurve = GLES30.glGetUniformLocation(program(), "uCurve");
        uniHasCurve = GLES30.glGetUniformLocation(program(), "uHasCurve");
    }

    @Override
    protected void onDraw() {
        GLES30.glUniform1i(uniHasCurve, hasCurve ? 1 : 0);
        GLES30.glUniform1i(uniCurve, 2);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId);
    }

    @Override
    public void release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
        super.release();
    }
}
