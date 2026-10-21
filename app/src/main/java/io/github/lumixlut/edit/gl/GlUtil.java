package io.github.lumixlut.edit.gl;

import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** 着色器编译、纹理创建、错误检查。全是 GLES 3.0 原生接口，没有第三方。 */
public final class GlUtil {

    private static final String TAG = "GlUtil";

    private GlUtil() {
    }

    public static int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        if (shader == 0) {
            throw new IllegalStateException("glCreateShader 返回 0");
        }
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("着色器编译失败：" + log);
        }
        return shader;
    }

    public static int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vs);
        GLES30.glAttachShader(program, fs);
        GLES30.glLinkProgram(program);
        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        // 链接完就可以删，附着在 program 上的引用会保留
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        if (status[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteProgram(program);
            throw new IllegalStateException("Program 链接失败：" + log);
        }
        return program;
    }

    public static int createTexture2D(int width, int height) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        return tex[0];
    }

    public static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    public static void checkError(String where) {
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            Log.w(TAG, where + " 报 GL 错误 0x" + Integer.toHexString(error));
        }
    }
}
