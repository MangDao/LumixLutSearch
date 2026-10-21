package io.github.lumixlut.edit.gl;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import io.github.lumixlut.edit.gl.filter.BasicAdjustFilter;
import io.github.lumixlut.edit.gl.filter.DetailFilter;
import io.github.lumixlut.edit.gl.filter.FrameFilter;
import io.github.lumixlut.edit.gl.filter.HslFilter;
import io.github.lumixlut.edit.gl.filter.Lut3DFilter;
import io.github.lumixlut.edit.gl.filter.NoiseFilter;
import io.github.lumixlut.edit.gl.filter.SplitToneFilter;
import io.github.lumixlut.edit.gl.filter.ToneCurveFilter;
import io.github.lumixlut.edit.gl.filter.VignetteFilter;
import io.github.lumixlut.edit.lut.LutData;

/**
 * 渲染线程。所有 GL 资源（纹理、program、FBO）只在这个线程里创建和释放，
 * 主线程一律通过 post 改参数，不碰 GL。
 */
public final class GlRenderThread extends HandlerThread {

    private static final String TAG = "GlRenderThread";

    private final Object lock = new Object();

    private Handler handler;
    private EglCore egl;
    private FilterChain chain;
    private LutTexture lutTexture;
    private LutTexture lutTexture2;
    private Lut3DFilter lutFilter;
    private BasicAdjustFilter adjustFilter;
    private HslFilter hslFilter;
    private SplitToneFilter splitFilter;
    private ToneCurveFilter curveFilter;
    private DetailFilter detailFilter;
    private NoiseFilter noiseFilter;
    private VignetteFilter vignetteFilter;
    private FrameFilter frameFilter;
    /** 滤镜还没建好时先记住调整值 */
    private float[] pendingLight;
    private float[] pendingColor;
    private float[] pendingHsl;
    private float[] pendingSplit;
    private byte[] pendingCurve;
    private boolean pendingCurveEnabled;
    private float[] pendingDetail;
    private float[] pendingNoise;
    private float[] pendingFrame;
    private int pendingFrameType;
    private float cropAspect;
    /** letterbox 底色，跟着界面主题走 */
    private float clearR;
    private float clearG;
    private float clearB;

    private Surface surface;
    private int surfaceWidth;
    private int surfaceHeight;

    private int imageTexture;
    private int imageWidth;
    private int imageHeight;

    /** 滤镜还没建好时先把浓度记下来，建好立刻套上 */
    private float pendingIntensity = 1f;
    private float pendingIntensity2 = 1f;
    /** 素材是否要先转 V-Log，见 Lut3DFilter.SOURCE_* */
    private int pendingSourceMode = Lut3DFilter.SOURCE_PASSTHROUGH;

    private boolean renderScheduled;
    private boolean firstFrameLogged;
    private volatile boolean released;

    public GlRenderThread() {
        super("lumix-edit-gl", 0);
        start();
        synchronized (lock) {
            while (handler == null) {
                try {
                    lock.wait(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    protected void onLooperPrepared() {
        handler = new Handler(getLooper());
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void post(Runnable task) {
        if (released) {
            return;
        }
        Handler h = handler;
        if (h != null) {
            h.post(task);
        }
    }

    // ---------- 表面生命周期 ----------

    public void onSurfaceCreated(Surface s) {
        post(() -> {
            surface = s;
            try {
                ensureEgl();
                egl.createWindowSurface(s);
                egl.makeCurrent();
                createGlResourcesIfNeeded();
                requestRenderInternal();
            } catch (RuntimeException e) {
                Log.e(TAG, "创建窗口表面失败", e);
            }
        });
    }

    public void onSurfaceChanged(int width, int height) {
        post(() -> {
            if (width == surfaceWidth && height == surfaceHeight) {
                requestRenderInternal();
                return;
            }
            surfaceWidth = width;
            surfaceHeight = height;
            if (egl != null && surface != null) {
                // 尺寸变了必须重建 EGLSurface。
                // 只改 surfaceWidth/Height 是不够的：默认 framebuffer 的尺寸
                // 跟着 EGLSurface 走，不重建的话 GL 还在按旧画布画，
                // swapBuffers 时整套内容被系统拉伸再裁掉 ——
                // 表现就是"拖动面板后图片被放大并裁掉一截"
                try {
                    egl.createWindowSurface(surface);
                    egl.makeCurrent();
                } catch (Throwable t) {
                    Log.w(TAG, "重建 EGLSurface 失败：" + t.getMessage());
                }
            }
            requestRenderInternal();
        });
    }

    public void onSurfaceDestroyed() {
        post(() -> {
            surface = null;
            if (egl != null) {
                egl.destroySurfaceOnly();
            }
        });
    }

    // ---------- 参数 ----------

    public void setImage(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        post(() -> {
            try {
                if (!ensureCurrent()) {
                    Log.w(TAG, "没有可用的 current surface，源图先留着");
                    return;
                }
                createGlResourcesIfNeeded();
                if (imageTexture != 0) {
                    GLES30.glDeleteTextures(1, new int[]{imageTexture}, 0);
                    imageTexture = 0;
                }
                int[] tex = new int[1];
                GLES30.glGenTextures(1, tex, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
                imageTexture = tex[0];
                imageWidth = bitmap.getWidth();
                imageHeight = bitmap.getHeight();
                GlUtil.checkError("上传源图 " + imageWidth + "x" + imageHeight);
                requestRenderInternal();
            } catch (RuntimeException e) {
                Log.e(TAG, "上传源图失败", e);
            }
        });
    }

    public void setLut(LutData data) {
        post(() -> {
            try {
                if (!ensureCurrent()) {
                    Log.w(TAG, "没有可用的 current surface，LUT 先不上传");
                    return;
                }
                createGlResourcesIfNeeded();
                lutTexture.upload(data == null ? LutData.identity(33) : data);
                requestRenderInternal();
            } catch (RuntimeException e) {
                Log.e(TAG, "上传 LUT 失败", e);
            }
        });
    }

    /** 第二条 LUT。传 null 表示关掉，shader 里那段分支就不跑 */
    public void setLut2(LutData data) {
        post(() -> {
            if (lutTexture2 == null || lutFilter == null) {
                return;
            }
            if (data == null || data.rgb == null) {
                lutTexture2.upload(LutData.identity(33));
                lutFilter.setHasLut2(false);
            } else {
                lutTexture2.upload(data);
                lutFilter.setHasLut2(true);
            }
            GlUtil.checkError("上传第二条 LUT");
            firstFrameLogged = false;
            requestRenderInternal();
        });
    }

    public void setIntensity(float value) {
        post(() -> {
            pendingIntensity = value;
            if (lutFilter != null) {
                lutFilter.setIntensity(value);
                requestRenderInternal();
            }
        });
    }

    /** 第二条 LUT 的浓度 */
    public void setIntensity2(float value) {
        post(() -> {
            pendingIntensity2 = value;
            if (lutFilter != null) {
                lutFilter.setIntensity2(value);
            }
            requestRenderInternal();
        });
    }

    public void setSourceMode(int mode) {
        post(() -> {
            pendingSourceMode = mode;
            if (lutFilter != null) {
                lutFilter.setSourceMode(mode);
                requestRenderInternal();
            }
        });
    }

    /** letterbox 底色，传 ARGB */
    public void setBackdrop(int color) {
        clearR = ((color >> 16) & 0xFF) / 255f;
        clearG = ((color >> 8) & 0xFF) / 255f;
        clearB = (color & 0xFF) / 255f;
        post(this::requestRenderInternal);
    }

    /**
     * 「亮度」+「色彩」两组，同一个 pass。
     *
     * @param light EditState.packLight() 6 项
     * @param color EditState.packColor() 4 项
     */
    public void setAdjust(float[] light, float[] color) {
        post(() -> {
            pendingLight = light;
            pendingColor = color;
            if (adjustFilter != null) {
                adjustFilter.setValues(light, color);
                requestRenderInternal();
            }
        });
    }

    /** HSL，8 组 (色彩, 饱和度, 辉度) 摊平 */
    public void setHsl(float[] values) {
        post(() -> {
            pendingHsl = values;
            if (hslFilter != null) {
                hslFilter.setValues(values);
                requestRenderInternal();
            }
        });
    }

    /** 色调分离，3 组 (色相, 饱和度) */
    public void setSplit(float[] values) {
        post(() -> {
            pendingSplit = values;
            if (splitFilter != null) {
                splitFilter.setValues(values);
                requestRenderInternal();
            }
        });
    }

    /** data 来自 ToneCurveSet.toTexture()，256*4*4 字节 */
    public void setCurve(byte[] data, boolean enabled) {
        post(() -> {
            pendingCurve = data;
            pendingCurveEnabled = enabled;
            if (curveFilter != null) {
                curveFilter.upload(data);
                curveFilter.setEnabled(enabled);
                requestRenderInternal();
            }
        });
    }

    /** {锐度, 降噪} */
    public void setDetail(float[] values) {
        post(() -> {
            pendingDetail = values;
            if (detailFilter != null) {
                detailFilter.setValues(values);
                requestRenderInternal();
            }
        });
    }

    /** {颗粒, 色彩噪点, 暗角强度} */
    public void setNoise(float[] values) {
        post(() -> {
            pendingNoise = values;
            if (noiseFilter != null) {
                noiseFilter.setValues(values);
            }
            if (vignetteFilter != null) {
                vignetteFilter.setValues(values);
            }
            if (noiseFilter != null || vignetteFilter != null) {
                requestRenderInternal();
            }
        });
    }

    /**
     * @param crop       {offsetX, offsetY, scaleX, scaleY, frameInset}
     * @param frameType  0 无 / 1 细白 / 2 粗黑 / 3 底部留白
     * @param aspect     裁剪后宽高比，0 表示保持原始；影响预览的 letterbox 比例
     */
    public void setFrame(float[] crop, int frameType, float aspect) {
        post(() -> {
            pendingFrame = crop;
            pendingFrameType = frameType;
            cropAspect = aspect;
            if (frameFilter != null) {
                frameFilter.setCrop(crop);
                frameFilter.setFrameType(frameType);
                requestRenderInternal();
            }
        });
    }

    public void requestRender() {
        post(this::requestRenderInternal);
    }

    // ---------- 导出 ----------

    public interface ExportCallback {
        void onDone(Bitmap bitmap);

        void onFailed(String message);
    }

    /**
     * 按原图尺寸完整重放一遍滤镜链，读回 Bitmap。
     *
     * 不是截屏：屏幕上是缩略预览，这里按全分辨率重新跑，
     * 所以导出的细节和预览看到的不完全一样（更清楚）。
     *
     * 分块读像素是为了不一次性申请整张图的直接缓冲——
     * 5328×4000 一张就是 85 MB，很容易在路上被系统掐掉。
     */
    public void export(Bitmap source, ExportCallback callback) {
        post(() -> {
            int sourceTexture = 0;
            try {
                if (!ensureCurrent()) {
                    callback.onFailed("拿不到 GL 上下文");
                    return;
                }
                int maxSize = maxTextureSize();
                Bitmap working = source;
                if (working.getWidth() > maxSize || working.getHeight() > maxSize) {
                    float scale = Math.min(maxSize / (float) working.getWidth(),
                            maxSize / (float) working.getHeight());
                    int w = Math.max(1, Math.round(working.getWidth() * scale));
                    int h = Math.max(1, Math.round(working.getHeight() * scale));
                    working = Bitmap.createScaledBitmap(working, w, h, true);
                    Log.w(TAG, "原图超过 GL 纹理上限 " + maxSize + "，导出降到 " + w + "x" + h);
                }
                int width = working.getWidth();
                int height = working.getHeight();

                sourceTexture = uploadTexture(working);
                if (chain == null) {
                    createGlResourcesIfNeeded();
                }
                // 裁剪过的话导出画布也要跟着变，否则 1:1 的内容会被拉成 3:2
                int outWidth = width;
                int outHeight = height;
                if (cropAspect > 0.0001f) {
                    if (width / (float) height > cropAspect) {
                        outWidth = Math.round(height * cropAspect);
                    } else {
                        outHeight = Math.round(width / cropAspect);
                    }
                }
                chain.renderToTexture(sourceTexture, outWidth, outHeight);
                Bitmap out = readPixels(chain.lastFboId(), outWidth, outHeight);

                callback.onDone(out);
            } catch (Throwable t) {
                Log.e(TAG, "导出失败", t);
                callback.onFailed(String.valueOf(t.getMessage()));
            } finally {
                if (sourceTexture != 0) {
                    GLES30.glDeleteTextures(1, new int[]{sourceTexture}, 0);
                }
                // 把上下文还给预览窗口，否则后面刷新会画到离屏表面上去
                if (egl != null) {
                    egl.createWindowSurface(surface);
                    egl.makeCurrent();
                }
            }
        });
    }

    private int maxTextureSize() {
        int[] value = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, value, 0);
        return value[0] > 0 ? value[0] : 2048;
    }

    private int uploadTexture(Bitmap bitmap) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        return tex[0];
    }

    /** 从 FBO 分块读回，顺手做 Y 翻转和 RGBA→ARGB 重排 */
    private Bitmap readPixels(int fboId, int width, int height) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] row = new int[width];
        int chunkRows = Math.max(1, Math.min(height, 65536 / Math.max(width, 1)));
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * chunkRows * 4)
                .order(ByteOrder.nativeOrder());

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId);
        for (int y = 0; y < height; y += chunkRows) {
            int rows = Math.min(chunkRows, height - y);
            buffer.clear();
            GLES30.glReadPixels(0, y, width, rows, GLES30.GL_RGBA,
                    GLES30.GL_UNSIGNED_BYTE, buffer);
            buffer.position(0);
            IntBuffer ints = buffer.asIntBuffer();
            for (int r = 0; r < rows; r++) {
                ints.position(r * width);
                ints.get(row, 0, width);
                // GL 的 y=0 在底部，Bitmap 的 y=0 在顶部，所以要翻
                int destY = height - 1 - (y + r);
                for (int x = 0; x < width; x++) {
                    int rgba = row[x];
                    // 小端机把 R,G,B,A 四字节读成 int 得到 (A<<24)|(B<<16)|(G<<8)|R，
                    // Bitmap 的 ARGB_8888 要的是 (A<<24)|(R<<16)|(G<<8)|B，R 和 B 得换位
                    row[x] = (((rgba >>> 24) & 0xFF) << 24)
                            | ((rgba & 0xFF) << 16)
                            | (((rgba >>> 8) & 0xFF) << 8)
                            | ((rgba >>> 16) & 0xFF);
                }
                out.setPixels(row, 0, width, 0, destY, width, 1);
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return out;
    }

    // ---------- 内部 ----------

    private void ensureEgl() {
        if (egl == null) {
            egl = new EglCore();
        }
    }

    /**
     * 保证当前线程有可用的 current surface。
     * 窗口表面还没来、或者刚被销毁时，先用一个 4×4 的 PBuffer 顶着，
     * 这样纹理和 program 都能先建好，等窗口回来直接画。
     */
    private boolean ensureCurrent() {
        ensureEgl();
        if (egl.makeCurrent()) {
            return true;
        }
        if (egl.getSurfaceWidth() == 0 && egl.getSurfaceHeight() == 0) {
            egl.createOffscreenSurface(4, 4);
        }
        return egl.makeCurrent();
    }

    private void createGlResourcesIfNeeded() {
        if (chain != null) {
            return;
        }
        lutTexture = new LutTexture();
        lutTexture.upload(LutData.identity(33));
        lutTexture2 = new LutTexture();
        lutTexture2.upload(LutData.identity(33));
        lutFilter = new Lut3DFilter(lutTexture, lutTexture2);
        lutFilter.setIntensity(pendingIntensity);
        lutFilter.setIntensity2(pendingIntensity2);
        lutFilter.setSourceMode(pendingSourceMode);
        adjustFilter = new BasicAdjustFilter();
        if (pendingLight != null || pendingColor != null) {
            adjustFilter.setValues(pendingLight, pendingColor);
        }
        hslFilter = new HslFilter();
        if (pendingHsl != null) {
            hslFilter.setValues(pendingHsl);
        }
        splitFilter = new SplitToneFilter();
        if (pendingSplit != null) {
            splitFilter.setValues(pendingSplit);
        }
        curveFilter = new ToneCurveFilter();
        if (pendingCurve != null) {
            curveFilter.upload(pendingCurve);
            curveFilter.setEnabled(pendingCurveEnabled);
        }
        detailFilter = new DetailFilter();
        if (pendingDetail != null) {
            detailFilter.setValues(pendingDetail);
        }
        noiseFilter = new NoiseFilter();
        if (pendingNoise != null) {
            noiseFilter.setValues(pendingNoise);
        }
        vignetteFilter = new VignetteFilter();
        if (pendingNoise != null) {
            vignetteFilter.setValues(pendingNoise);
        }
        frameFilter = new FrameFilter();
        if (pendingFrame != null) {
            frameFilter.setCrop(pendingFrame);
            frameFilter.setFrameType(pendingFrameType);
        }
        chain = new FilterChain();
        chain.addFilter(lutFilter);
        chain.addFilter(adjustFilter);
        chain.addFilter(hslFilter);
        chain.addFilter(splitFilter);
        chain.addFilter(curveFilter);
        chain.addFilter(detailFilter);
        chain.addFilter(noiseFilter);
        chain.addFilter(vignetteFilter);
        chain.addFilter(frameFilter);
    }

    private void requestRenderInternal() {
        if (renderScheduled) {
            return;
        }
        renderScheduled = true;
        post(this::doRender);
    }

    private void doRender() {
        renderScheduled = false;
        if (egl == null || surface == null
                || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return;
        }
        if (!egl.makeCurrent()) {
            Log.w(TAG, "makeCurrent 失败，跳过这一帧");
            return;
        }

        // 先把整块画布刷成主题底色：没选图的时候预览区不该是一块黑，
        // 选了图之后这就是 letterbox 的颜色
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glClearColor(clearR, clearG, clearB, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        if (imageTexture == 0) {
            // 还没有素材，刷完底色就收工
            egl.swapBuffers();
            return;
        }

        // 图片按比例居中，多余部分留黑边（letterbox）
        // 裁剪过的话画布比例要跟着变，否则预览框会歪
        int effW = imageWidth;
        int effH = imageHeight;
        if (cropAspect > 0.0001f) {
            if (imageWidth / (float) imageHeight > cropAspect) {
                effW = Math.round(imageHeight * cropAspect);
            } else {
                effH = Math.round(imageWidth / cropAspect);
            }
        }
        int[] box = fit(effW, effH, surfaceWidth, surfaceHeight);
        GLES30.glViewport(box[0], box[1], box[2], box[3]);
        chain.renderToScreen(imageTexture, box[2], box[3]);
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight);

        if (!firstFrameLogged) {
            firstFrameLogged = true;
            Log.d(TAG, "首帧绘制 表面=" + surfaceWidth + "x" + surfaceHeight
                    + " 图片=" + imageWidth + "x" + imageHeight
                    + " 画布=" + box[2] + "x" + box[3] + "@" + box[0] + "," + box[1]
                    + " LUT=" + (lutTexture != null && lutTexture.isValid()
                    ? lutTexture.size() + "³" : "无"));
        }

        egl.swapBuffers();
    }

    /** 返回 {x, y, w, h}，等比塞进容器并居中 */
    static int[] fit(int srcW, int srcH, int boxW, int boxH) {
        if (srcW <= 0 || srcH <= 0 || boxW <= 0 || boxH <= 0) {
            return new int[]{0, 0, Math.max(boxW, 1), Math.max(boxH, 1)};
        }
        float srcRatio = (float) srcW / srcH;
        float boxRatio = (float) boxW / boxH;
        int w;
        int h;
        if (srcRatio >= boxRatio) {
            w = boxW;
            h = Math.round(boxW / srcRatio);
        } else {
            h = boxH;
            w = Math.round(boxH * srcRatio);
        }
        if (w < 1) {
            w = 1;
        }
        if (h < 1) {
            h = 1;
        }
        return new int[]{(boxW - w) / 2, (boxH - h) / 2, w, h};
    }

    public void shutdown() {
        released = true;
        post(() -> {
            if (chain != null) {
                chain.release();
                chain = null;
            }
            if (lutTexture != null) {
                lutTexture.release();
                lutTexture = null;
            }
            if (lutTexture2 != null) {
                lutTexture2.release();
                lutTexture2 = null;
            }
            if (imageTexture != 0) {
                GLES30.glDeleteTextures(1, new int[]{imageTexture}, 0);
                imageTexture = 0;
            }
            if (egl != null) {
                egl.release();
                egl = null;
            }
        });
        quitSafely();
    }
}
