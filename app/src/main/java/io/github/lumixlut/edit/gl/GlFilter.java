package io.github.lumixlut.edit.gl;

import android.opengl.GLES30;

import java.nio.FloatBuffer;

/**
 * 滤镜基类。整条管线就是一个 filter 接一个 filter，靠双 FBO 打乒乓。
 * 顶点固定是全屏四边形，不做投影矩阵 —— 预览和离屏导出都不需要。
 */
public abstract class GlFilter {

    /** 全屏四边形：y 方向纹理坐标翻转过，这样 Bitmap 上传后是正着的 */
    private static final float[] VERTICES = {
            // x,    y,     u,    v
            -1.0f, -1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 0.0f,
    };

    /**
     * 从中间 FBO 读的时候必须再用一次反向映射。
     * 原因：FBO 的 y=0 在底部，写进去的内容相对原图是上下颠倒的，
     * 下一个 pass 按正常映射再采样就会倒过来。滤镜链里按 pass 序号交替切换即可。
     */
    private static final float[] VERTICES_FLIPPED = {
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f,
    };

    private static final String VERTEX_SHADER =
            "#version 300 es\n"
            + "in vec2 aPos;\n"
            + "in vec2 aTex;\n"
            + "out vec2 vTex;\n"
            + "void main() {\n"
            + "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "    vTex = aTex;\n"
            + "}\n";

    private FloatBuffer vertexBuffer;
    private FloatBuffer vertexBufferFlipped;
    private boolean flipVertical;
    /** 当前 pass 的画布尺寸，需要邻域采样或噪声坐标的子类会用到 */
    protected int viewportWidth;
    protected int viewportHeight;
    private int program;
    private int attrPos;
    private int attrTex;
    private int uniTex;
    private boolean created;

    protected abstract String fragmentShader();

    /** program 建好后调一次，用来拿 uniform 位置 */
    protected void onCreate() {
    }

    /** 每次绘制前调用，子类在这里设 uniform；调用时 program 已绑定 */
    protected void onDraw() {
    }

    /** 本 filter 是否需要 3D LUT 纹理之类的额外资源，默认不管 */
    public void release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program);
            program = 0;
            created = false;
        }
    }

    protected final int program() {
        return program;
    }

    /** 从中间 FBO 读的时候置 true，见 VERTICES_FLIPPED 的说明 */
    public final void setFlipVertical(boolean flip) {
        this.flipVertical = flip;
    }

    public final void draw(int inputTexture, int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        if (!created) {
            program = GlUtil.createProgram(VERTEX_SHADER, fragmentShader());
            attrPos = GLES30.glGetAttribLocation(program, "aPos");
            attrTex = GLES30.glGetAttribLocation(program, "aTex");
            uniTex = GLES30.glGetUniformLocation(program, "uTex");
            vertexBuffer = GlUtil.floatBuffer(VERTICES);
            vertexBufferFlipped = GlUtil.floatBuffer(VERTICES_FLIPPED);
            created = true;
            onCreate();
        }

        GLES30.glUseProgram(program);

        FloatBuffer quad = flipVertical ? vertexBufferFlipped : vertexBuffer;
        quad.position(0);
        GLES30.glEnableVertexAttribArray(attrPos);
        GLES30.glVertexAttribPointer(attrPos, 2, GLES30.GL_FLOAT, false, 16, quad);

        quad.position(2);
        GLES30.glEnableVertexAttribArray(attrTex);
        GLES30.glVertexAttribPointer(attrTex, 2, GLES30.GL_FLOAT, false, 16, quad);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture);
        GLES30.glUniform1i(uniTex, 0);

        onDraw();

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glDisableVertexAttribArray(attrPos);
        GLES30.glDisableVertexAttribArray(attrTex);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
    }
}
