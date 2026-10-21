package io.github.lumixlut.edit.video;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

/**
 * 视频转码的唯一入口。
 *
 * 用 ffmpeg-kit-min：LGPL v3，且不带任何外部编解码器，
 * 视频编解码全部落到系统 MediaCodec（h264_mediacodec / hevc_mediacodec）。
 * 这样既避开了 libx264 的 GPL 传染，也不用担心包体再翻一倍。
 */
public final class VideoEngine {

    private static final String TAG = "VideoEngine";

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    private VideoEngine() {
    }

    /** 调试用：把编码器列表里和 h264/mediacodec 有关的行捞出来 */
    public static void probeEncoders(final Callback callback) {
        FFmpegKit.executeAsync("-hide_banner -encoders", (FFmpegSession session) -> {
            String output = session.getOutput();
            boolean success = ReturnCode.isSuccess(session.getReturnCode());
            StringBuilder hits = new StringBuilder();
            if (output != null) {
                for (String line : output.split("\n")) {
                    String lower = line.toLowerCase();
                    if (lower.contains("h264") || lower.contains("hevc")
                            || lower.contains("mediacodec") || lower.contains("mpeg4")) {
                        hits.append(line.trim()).append(" | ");
                    }
                }
            }
            String message = (success ? "OK " : "FAIL ") + hits;
            Log.d(TAG, "编码器探测：" + message);
            if (callback != null) {
                callback.onResult(success, message);
            }
        });
    }

    /**
     * 临时验证：把 test30.mp4 按 2 倍速转一遍，走 mediacodec 硬编。
     * 产物写在外部私有目录，方便 adb pull 出来核对。
     */
    public static void testTranscode(android.content.Context ctx, final Callback callback) {
        java.io.File dir = ctx.getExternalFilesDir(null);
        java.io.File in = new java.io.File(dir, "test30.mp4");
        if (!in.exists()) {
            callback.onResult(false, "没有测试视频");
            return;
        }
        final java.io.File out = new java.io.File(dir, "test_out.mp4");
        if (out.exists() && !out.delete()) {
            Log.w(TAG, "旧的测试输出删不掉");
        }
        long start = System.currentTimeMillis();
        java.io.File lut = new java.io.File(dir, "teal.cube");
        String filter = "setpts=PTS/2";
        if (lut.exists()) {
            filter = "lut3d=" + lut.getAbsolutePath() + ",setpts=PTS/2";
        }
        String cmd = "-y -i " + quote(in.getAbsolutePath())
                + " -vf \"" + filter + "\" -an"
                + " -c:v h264_mediacodec -b:v 6M"
                + " " + quote(out.getAbsolutePath());
        FFmpegKit.executeAsync(cmd, (FFmpegSession session) -> {
            long cost = System.currentTimeMillis() - start;
            boolean ok = ReturnCode.isSuccess(session.getReturnCode());
            String message = "rc=" + session.getReturnCode()
                    + " 耗时=" + cost + "ms"
                    + " 产物=" + out.length() + "B";
            if (!ok) {
                String log = session.getOutput();
                message += " 日志=" + (log == null ? "(null)"
                        : log.substring(Math.max(0, log.length() - 800)));
            }
            Log.d(TAG, "转码测试：" + (ok ? "成功 " : "失败 ") + message);
            callback.onResult(ok, message);
        });
    }

    private static String quote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * 正式转码：套 LUT + 变速，一次编码出片。
     *
     * @param lutCube LUT 文件路径，null 表示不套
     * @param plan    变速方案
     */
    public static void transcode(java.io.File input, java.io.File lutCube,
                                 SpeedPlan plan, java.io.File output,
                                 final Callback callback) {
        transcode(input, lutCube, plan, 0f, 0, 0, output, callback);
    }

    /**
     * @param cropAspect 目标宽高比，0 表示不裁；裁剪是居中的
     * @param srcWidth   源尺寸，裁剪比例算具体像素要用
     */
    public static void transcode(java.io.File input, java.io.File lutCube,
                                 SpeedPlan plan, float cropAspect,
                                 int srcWidth, int srcHeight,
                                 java.io.File output, final Callback callback) {
        if (input == null || !input.exists()) {
            callback.onResult(false, "找不到输入视频");
            return;
        }
        float speed = plan.averageSpeed();
        if (Math.abs(speed - 1f) < 0.001f) {
            speed = 1f;
        }

        StringBuilder filters = new StringBuilder();
        if (cropAspect > 0.001f && srcWidth > 0 && srcHeight > 0) {
            String crop = cropExpression(cropAspect, srcWidth, srcHeight);
            if (crop != null) {
                filters.append(crop);
            }
        }
        if (lutCube != null && lutCube.exists()) {
            if (filters.length() > 0) {
                filters.append(",");
            }
            filters.append("lut3d=").append(lutCube.getAbsolutePath());
        }
        if (speed != 1f) {
            if (filters.length() > 0) {
                filters.append(",");
            }
            filters.append("setpts=PTS/").append(fmt(speed));
        }

        StringBuilder cmd = new StringBuilder("-y -hide_banner -i ");
        cmd.append(quote(input.getAbsolutePath()));
        if (filters.length() > 0) {
            cmd.append(" -vf \"").append(filters).append("\"");
        }

        // 音频：只有 0.5x~2x 才留声音，其余整条静音
        boolean keepAudio = speed >= 0.5f && speed <= 2f;
        if (keepAudio) {
            if (Math.abs(speed - 1f) > 0.001f) {
                cmd.append(" -af atempo=").append(fmt(speed));
            }
            cmd.append(" -c:a aac -b:a 160k");
        } else {
            cmd.append(" -an");
        }

        cmd.append(" -c:v h264_mediacodec -b:v 12M -pix_fmt yuv420p");
        cmd.append(" -movflags +faststart");
        cmd.append(" ").append(quote(output.getAbsolutePath()));

        Log.d(TAG, "开始转码：" + cmd);
        long start = System.currentTimeMillis();
        FFmpegKit.executeAsync(cmd.toString(), (FFmpegSession session) -> {
            long cost = System.currentTimeMillis() - start;
            boolean ok = ReturnCode.isSuccess(session.getReturnCode());
            String message = "耗时 " + (cost / 1000f) + "s，产物 "
                    + (output.exists() ? output.length() / 1024 : 0) + " KB";
            if (!ok) {
                String log = session.getOutput();
                message += "；日志 " + (log == null ? "(null)"
                        : log.substring(Math.max(0, log.length() - 600)));
            }
            Log.d(TAG, "转码完成：" + (ok ? "成功 " : "失败 ") + message);
            callback.onResult(ok, message);
        });
    }

    private static String fmt(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(java.util.Locale.US, "%.3f", value);
    }

    /**
     * 曲线变速：按段切分，每段用各自的 setpts，最后 concat。
     *
     * 为什么不写一条 setpts 表达式：倍率随时间变化时表达式会变成嵌套 if，
     * 又难读又容易踩 ffmpeg 表达式解析的坑。切段拼接虽然命令长，但每段
     * 都是最简单的形式，出问题能一段一段对。
     *
     * 曲线模式的倍率会跨出 atempo 能处理的区间（而且逐段变），
     * 所以整条静音——和「5x/0.2x 静音」是同一条规矩。
     */
    public static void transcodeCurve(java.io.File input, java.io.File lutCube,
                                      CurveSpeedPlan plan, long durationMs,
                                      java.io.File output, final Callback callback) {
        transcodeCurve(input, lutCube, plan, durationMs, 0f, 0, 0, output, callback);
    }

    public static void transcodeCurve(java.io.File input, java.io.File lutCube,
                                      CurveSpeedPlan plan, long durationMs,
                                      float cropAspect, int srcWidth, int srcHeight,
                                      java.io.File output, final Callback callback) {
        if (input == null || !input.exists()) {
            callback.onResult(false, "找不到输入视频");
            return;
        }
        if (durationMs <= 0) {
            callback.onResult(false, "拿不到视频时长");
            return;
        }
        int n = CurveSpeedPlan.SEGMENTS;
        double totalSec = durationMs / 1000.0;

        StringBuilder filter = new StringBuilder();
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double t0 = totalSec * i / n;
            double t1 = totalSec * (i + 1) / n;
            float speed = plan.segmentSpeed(i);
            filter.append("[0:v]trim=").append(fmt((float) t0)).append(":")
                    .append(fmt((float) t1))
                    .append(",setpts=(PTS-STARTPTS)/").append(fmt(speed))
                    .append("[v").append(i).append("];");
            labels.append("[v").append(i).append("]");
        }
        filter.append(labels).append("concat=n=").append(n).append(":v=1:a=0[vc]");

        String last = "vc";
        if (cropAspect > 0.001f && srcWidth > 0 && srcHeight > 0) {
            String crop = cropExpression(cropAspect, srcWidth, srcHeight);
            if (crop != null) {
                filter.append(";[vc]").append(crop).append("[vcr]");
                last = "vcr";
            }
        }
        if (lutCube != null && lutCube.exists()) {
            filter.append(";[").append(last).append("]lut3d=")
                    .append(lutCube.getAbsolutePath()).append("[vout]");
        } else {
            filter.append(";[").append(last).append("]null[vout]");
        }

        StringBuilder cmd = new StringBuilder("-y -hide_banner -i ");
        cmd.append(quote(input.getAbsolutePath()));
        cmd.append(" -filter_complex \"").append(filter).append("\"");
        cmd.append(" -map \"[vout]\" -an");
        cmd.append(" -c:v h264_mediacodec -b:v 12M -pix_fmt yuv420p");
        cmd.append(" -movflags +faststart");
        cmd.append(" ").append(quote(output.getAbsolutePath()));

        Log.d(TAG, "开始曲线转码：" + cmd);
        long start = System.currentTimeMillis();
        FFmpegKit.executeAsync(cmd.toString(), (FFmpegSession session) -> {
            long cost = System.currentTimeMillis() - start;
            boolean ok = ReturnCode.isSuccess(session.getReturnCode());
            String message = "耗时 " + (cost / 1000f) + "s，产物 "
                    + (output.exists() ? output.length() / 1024 : 0) + " KB";
            if (!ok) {
                String log = session.getOutput();
                message += "；日志 " + (log == null ? "(null)"
                        : log.substring(Math.max(0, log.length() - 600)));
            }
            Log.d(TAG, "曲线转码完成：" + (ok ? "成功 " : "失败 ") + message);
            callback.onResult(ok, message);
        });
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }

    /**
     * 居中裁剪到指定宽高比。
     * 宽高都取偶数——h264 编码器对奇数尺寸不友好，会直接报错。
     */
    static String cropExpression(float aspect, int srcW, int srcH) {
        int outW;
        int outH;
        if (srcW / (float) srcH > aspect) {
            outH = srcH;
            outW = Math.round(outH * aspect);
        } else {
            outW = srcW;
            outH = Math.round(outW / aspect);
        }
        outW &= ~1;
        outH &= ~1;
        if (outW <= 1 || outH <= 1) {
            return null;
        }
        int x = (srcW - outW) / 2;
        int y = (srcH - outH) / 2;
        return "crop=" + outW + ":" + outH + ":" + x + ":" + y;
    }
}
