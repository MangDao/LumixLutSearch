package io.github.lumixlut.edit.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * 一套最小可用的 EGL 封装，只做我们真正需要的两件事：
 * 1) 给 SurfaceView 开一个窗口表面做预览；
 * 2) 给离屏导出开一个 PBuffer 表面（第 3 步接导出时用上）。
 *
 * 注意：EGL 上下文只能被创建它的线程使用，所以这个类一律在渲染线程里 new。
 */
public final class EglCore {

    /** EGL_OPENGL_ES3_BIT_KHR，EGL14 里叫这个名，字节值 0x40 */
    private static final int EGL_OPENGL_ES3_BIT = 0x0040;
    /** EGL_RECORDABLE_ANDROID：给 MediaCodec 当输入Surface时必需，第 2 步视频要用 */
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int surfaceWidth;
    private int surfaceHeight;

    public EglCore() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("eglGetDisplay 失败");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize 失败，这台机器没有可用的 EGL");
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            // 退一步：不要 alpha、不要 pbuffer，老驱动有时候只认这一套
            int[] fallback = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };
            if (!EGL14.eglChooseConfig(display, fallback, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                throw new IllegalStateException("找不到支持 GLES3 的 EGLConfig");
            }
        }
        config = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
        };
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("eglCreateContext 失败，拿不到 GLES3 上下文");
        }
    }

    public void createWindowSurface(Surface window) {
        releaseSurface();
        int[] attribs = { EGL14.EGL_NONE };
        surface = EGL14.eglCreateWindowSurface(display, config, window, attribs, 0);
        if (surface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface 失败");
        }
    }

    /** 离屏表面，导出时用。第 1 步暂未调用，先把能力留出来。 */
    public void createOffscreenSurface(int width, int height) {
        releaseSurface();
        int[] attribs = { EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE };
        surface = EGL14.eglCreatePbufferSurface(display, config, attribs, 0);
        if (surface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreatePbufferSurface 失败");
        }
        surfaceWidth = width;
        surfaceHeight = height;
    }

    public boolean makeCurrent() {
        return EGL14.eglMakeCurrent(display, surface, surface, context);
    }

    public void makeNothingCurrent() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
    }

    public int querySurfaceWidth() {
        int[] value = new int[1];
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, value, 0);
        return value[0];
    }

    public int querySurfaceHeight() {
        int[] value = new int[1];
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, value, 0);
        return value[0];
    }

    public boolean swapBuffers() {
        return EGL14.eglSwapBuffers(display, surface);
    }

    private void releaseSurface() {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface);
            surface = EGL14.EGL_NO_SURFACE;
        }
    }

    /**
     * 只销毁窗口表面、保留上下文。
     * SurfaceView 被回收时必须走这条路，否则所有纹理跟着上下文一起没。
     */
    public void destroySurfaceOnly() {
        makeNothingCurrent();
        releaseSurface();
        surfaceWidth = 0;
        surfaceHeight = 0;
    }

    public void release() {
        releaseSurface();
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, context);
            context = EGL14.EGL_NO_CONTEXT;
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }
}
