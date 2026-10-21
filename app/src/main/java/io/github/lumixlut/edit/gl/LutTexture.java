package io.github.lumixlut.edit.gl;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import io.github.lumixlut.edit.lut.LutData;

/**
 * 把 33³ 的 LUT 塞进 GL_TEXTURE_3D。GLES 3.0 原生支持三维纹理，
 * 硬件会替我们做三线性插值，不用自己在 shader 里算 8 个角。
 */
public final class LutTexture {

    private int textureId;
    private int size;

    /** 在 GL 线程里调用 */
    public void upload(LutData data) {
        if (data == null) {
            return;
        }
        release();
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        textureId = tex[0];
        size = data.size;

        FloatBuffer buffer = ByteBuffer.allocateDirect(data.rgb.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(data.rgb);
        buffer.position(0);

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
                size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buffer);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0);
        GlUtil.checkError("上传 3D LUT(" + size + "³)");
    }

    public int id() {
        return textureId;
    }

    public int size() {
        return size;
    }

    public boolean isValid() {
        return textureId != 0;
    }

    public void release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
            size = 0;
        }
    }
}
