package io.github.lumixlut.edit.gl;

import android.opengl.GLES30;

import java.util.ArrayList;
import java.util.List;

/**
 * 滤镜链：输入纹理 → filter1 → filter2 → … → 输出。
 * 内部用两张 FBO 纹理打乒乓，最后一步可以选择画到屏幕还是留在离屏纹理里。
 */
public final class FilterChain {

    private final List<GlFilter> filters = new ArrayList<>();

    private int[] fbo = new int[2];
    private int[] fboTexture = new int[2];
    private int fboWidth = -1;
    private int fboHeight = -1;

    public void addFilter(GlFilter filter) {
        filters.add(filter);
    }

    public void clearFilters() {
        releaseFilters();
        filters.clear();
    }

    /**
     * 直接把输入纹理画到当前 EGLSurface（预览用）。
     * viewport 由调用者设好 —— 预览要按 letterbox 居中，这里不该插手。
     */
    public void renderToScreen(int inputTexture, int width, int height) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        drawAll(inputTexture, width, height);
    }

    /**
     * 把输入纹理走完滤镜链，结果落在离屏纹理上并返回其 id（导出用）。
     * 调用前请确保离屏表面已经 makeCurrent，并把 viewport 设成目标尺寸。
     */
    public int renderToTexture(int inputTexture, int width, int height) {
        if (filters.isEmpty()) {
            return inputTexture;
        }
        ensureFramebuffer(width, height);
        // 导出时必须自己设视口：预览那一步设的是 1080x720，
        // 不重置的话只会画出左下角一小块，其余全是黑的
        GLES30.glViewport(0, 0, width, height);
        int current = inputTexture;
        int target = 0;
        for (int i = 0; i < filters.size(); i++) {
            boolean last = (i == filters.size() - 1);
            filters.get(i).setFlipVertical(last && needsFlip(filters.size()));
            if (last) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]);
                filters.get(i).draw(current, width, height);
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[target]);
                filters.get(i).draw(current, width, height);
                current = fboTexture[target];
                target = 1 - target;
            }
        }
        // 这里刻意不解绑：导出时紧接着就要从这个 FBO 读像素
        lastFbo = fbo[0];
        return fboTexture[0];
    }

    private int lastFbo;

    /** 上一次 renderToTexture 落到哪个 FBO，导出读像素时用 */
    public int lastFboId() {
        return lastFbo;
    }

    private void drawAll(int inputTexture, int width, int height) {
        if (filters.isEmpty()) {
            return;
        }
        if (filters.size() == 1) {
            filters.get(0).draw(inputTexture, width, height);
            return;
        }
        // 记下调用者设的屏幕视口（letterbox 之后的位置），
        // 中间那些 pass 画到 FBO 时必须从 (0,0) 开始，
        // 否则竖图会带上横向偏移，左边一大片画不到——就是预览全黑那个 bug
        int[] viewport = new int[4];
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);

        ensureFramebuffer(width, height);
        int current = inputTexture;
        int target = 0;
        for (int i = 0; i < filters.size(); i++) {
            boolean last = (i == filters.size() - 1);
            GlFilter filter = filters.get(i);
            filter.setFlipVertical(last && needsFlip(filters.size()));
            if (last) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                filter.draw(current, width, height);
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[target]);
                GLES30.glViewport(0, 0, width, height);
                filter.draw(current, width, height);
                current = fboTexture[target];
                target = 1 - target;
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void ensureFramebuffer(int width, int height) {
        if (fboWidth == width && fboHeight == height && fbo[0] != 0) {
            return;
        }
        releaseFramebuffer();
        GLES30.glGenFramebuffers(2, fbo, 0);
        GLES30.glGenTextures(2, fboTexture, 0);
        for (int i = 0; i < 2; i++) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture[i]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i]);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, fboTexture[i], 0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("FBO 不完整，status=0x" + Integer.toHexString(status));
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        fboWidth = width;
        fboHeight = height;
    }

    private static void bindFramebuffer(int target) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target);
    }

    /**
     * 最后一个 pass 要不要翻转纹理坐标。
     *
     * 推导：每渲染到一个 FBO，内容相对输入就上下翻一次（FBO 的 y=0 在物理底部）。
     * 中间 pass 全用正常映射，方向就在 ±1 之间来回；真正决定最终显示的是
     * 写屏幕那一下。数学上只有 n 为偶数（中间翻了奇数次）时才需要在最后翻回来。
     *
     * n=1 不翻、n=2 翻、n=3 不翻、n=4 翻…… 即 (n-1) 为奇数时翻。
     * 之前写成"每隔一个 pass 翻一次"，两个 pass 时凑巧对，三个 pass 立刻翻车。
     */
    private static boolean needsFlip(int filterCount) {
        return (filterCount - 1) % 2 == 1;
    }

    private void releaseFramebuffer() {
        if (fbo[0] != 0) {
            GLES30.glDeleteFramebuffers(2, fbo, 0);
            GLES30.glDeleteTextures(2, fboTexture, 0);
            fbo[0] = 0;
            fbo[1] = 0;
            fboTexture[0] = 0;
            fboTexture[1] = 0;
            fboWidth = -1;
            fboHeight = -1;
        }
    }

    private void releaseFilters() {
        for (GlFilter filter : filters) {
            filter.release();
        }
    }

    public void release() {
        releaseFilters();
        releaseFramebuffer();
    }
}
