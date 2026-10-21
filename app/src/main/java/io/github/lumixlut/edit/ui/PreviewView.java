package io.github.lumixlut.edit.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import io.github.lumixlut.edit.gl.GlRenderThread;
import io.github.lumixlut.edit.lut.LutData;

/**
 * 预览视图。SurfaceView + 自己的 EGL 线程，不用 GLSurfaceView ——
 * 因为导出时要在同一个上下文里做离屏渲染，线程模型得自己说了算。
 */
public class PreviewView extends SurfaceView implements SurfaceHolder.Callback {

    private GlRenderThread renderThread;

    public PreviewView(Context context) {
        super(context);
        init();
    }

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        getHolder().addCallback(this);
        // 预览区不该出现任何滚动条（HyperOS 上会画出一条白竖线）
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setScrollContainer(false);
        setWillNotDraw(true);
        renderThread = new GlRenderThread();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderThread.onSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        renderThread.onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        renderThread.onSurfaceDestroyed();
    }

    public void setImage(Bitmap bitmap) {
        if (renderThread != null) {
            renderThread.setImage(bitmap);
        }
    }

    public void setLut(LutData data) {
        if (renderThread != null) {
            renderThread.setLut(data);
        }
    }

    public void setLut2(LutData data) {
        if (renderThread != null) {
            renderThread.setLut2(data);
        }
    }

    public void setIntensity(float value) {
        if (renderThread != null) {
            renderThread.setIntensity(value);
        }
    }

    public void setIntensity2(float value) {
        if (renderThread != null) {
            renderThread.setIntensity2(value);
        }
    }

    public void setSourceMode(int mode) {
        if (renderThread != null) {
            renderThread.setSourceMode(mode);
        }
    }

    public void setAdjust(float[] light, float[] color) {
        if (renderThread != null) {
            renderThread.setAdjust(light, color);
        }
    }

    public void setHsl(float[] values) {
        if (renderThread != null) {
            renderThread.setHsl(values);
        }
    }

    public void setSplit(float[] values) {
        if (renderThread != null) {
            renderThread.setSplit(values);
        }
    }

    public void setCurve(byte[] data, boolean enabled) {
        if (renderThread != null) {
            renderThread.setCurve(data, enabled);
        }
    }

    public void setDetail(float[] values) {
        if (renderThread != null) {
            renderThread.setDetail(values);
        }
    }

    public void setNoise(float[] values) {
        if (renderThread != null) {
            renderThread.setNoise(values);
        }
    }

    public void setFrame(float[] crop, int frameType, float aspect) {
        if (renderThread != null) {
            renderThread.setFrame(crop, frameType, aspect);
        }
    }

    /** letterbox 底色，跟界面主题一致 */
    public void setBackdrop(int color) {
        if (renderThread != null) {
            renderThread.setBackdrop(color);
        }
    }

    public void export(Bitmap source, GlRenderThread.ExportCallback callback) {
        if (renderThread != null) {
            renderThread.export(source, callback);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 被 detach 过一次之后渲染线程已经关掉了，这里必须补回来，
        // 否则复用的 Activity 实例一调 setLut 就 NPE
        if (renderThread == null) {
            renderThread = new GlRenderThread();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (renderThread != null) {
            renderThread.shutdown();
            renderThread = null;
        }
    }
}
