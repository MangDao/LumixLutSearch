package io.github.lumixlut.edit.gl.filter;

import android.opengl.GLES30;

import io.github.lumixlut.edit.gl.GlFilter;
import io.github.lumixlut.edit.gl.LutTexture;

/**
 * 3D LUT 滤镜，外加一个"素材进 LUT 之前先转 V-Log"的开关。
 *
 * 为什么要这个开关：官方所有 LUT 的输入域都是 V-Log（mergeLut/STD_to_VLOG.cube 首点
 * 输出 0.125，正是 V-Log 黑位）。相机直出的 JPEG 已经把 Photo Style 烘进像素了，
 * 直接喂给 LUT 会闷、对比塌，所以要先按公开的 V-Log 曲线把 sRGB 重新编码一次。
 *
 * 参数顺序跟官方 backendProcess.spv 那组 uniform 对得上：
 *   LUTTBL / lut3DSize / LUT3D_fact / bValidPhotoStyle。
 */
public final class Lut3DFilter extends GlFilter {

    public static final int SOURCE_PASSTHROUGH = 0;
    public static final int SOURCE_SRGB_TO_VLOG = 1;
    /** 素材本身已经是 V-Log（V-Log 拍的 JPEG/视频），要先把 log 解开再套普通 LUT */
    public static final int SOURCE_VLOG_TO_SRGB = 2;

    private static final String FRAGMENT =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "precision highp sampler3D;\n"
            + "in vec2 vTex;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform sampler3D uLut;\n"
            + "uniform sampler3D uLut2;\n"
            + "uniform float uHasLut2;\n"
            + "uniform float uIntensity1;\n"
            + "uniform float uIntensity2;\n"
            + "uniform vec3 uDomainMin;\n"
            + "uniform vec3 uDomainRange;\n"
            + "uniform int uSourceMode;\n"
            + "out vec4 fragColor;\n"
            + "\n"
            + "float srgbToLinear(float c) {\n"
            + "    if (c <= 0.04045) return c / 12.92;\n"
            + "    return pow((c + 0.055) / 1.055, 2.4);\n"
            + "}\n"
            + "\n"
            + "// 松下公开的 V-Log 曲线：V = 0.241514*log10(R+0.00873)+0.598206\n"
            + "// 这里只保留 log 段：原先照着资料写的 lin*5.367655+0.0600908 那条线性段\n"
            + "// 和 log 段在 R=0.01 处对不上（0.114 vs 0.181），会让暗部整体偏暗 0.06，\n"
            + "// 所以统一走 log 公式，低于黑位的部分裁到 0.125（V-Log 的 0 IRE）。\n"
            + "float vlogEncode(float lin) {\n"
            + "    float v = 0.241514 * (log(max(lin, 0.0) + 0.00873) / log(10.0)) + 0.598206;\n"
            + "    return max(v, 0.125);\n"
            + "}\n"
            + "\n"
            + "vec3 toVLog(vec3 c) {\n"
            + "    return vec3(vlogEncode(srgbToLinear(c.r)),\n"
            + "                vlogEncode(srgbToLinear(c.g)),\n"
            + "                vlogEncode(srgbToLinear(c.b)));\n"
            + "}\n"
            + "\n"
            + "// 官方 STD_to_VLOG_2nd.cube 的首个采样点输出 0.125，也就是 V-Log 的 0 IRE（黑位）；\n"
            + "// 而裸曲线在 R=0 时只给 0.0601，那是低于黑位的负 IRE 区间。\n"
            + "// 所以这里做裁剪而不是拉伸：拉伸会把 18% 灰从 0.4233 抬到 0.4445，中间调整体偏亮；\n"
            + "// 裁剪能让三个锚点同时对：黑 0.125、18% 灰 0.4233、白 0.5991。\n"
            + "float alignBlack(float v) {\n"
            + "    return max(v, 0.125);\n"
            + "}\n"
            + "\n"
            + "vec3 toVLogAligned(vec3 c) {\n"
            + "    vec3 v = toVLog(c);\n"
            + "    return vec3(alignBlack(v.r), alignBlack(v.g), alignBlack(v.b));\n"
            + "}\n"
            + "\n"
            + "// 反向：V-Log 码值 -> 线性光 -> sRGB 显示值。\n"
            + "// 用在「素材本身就是 V-Log」的情况（V-Log 拍的 JPEG、V-Log 视频），\n"
            + "// 不先解开 log 就直接套普通 LUT，画面只会比原图更灰。\n"
            + "float vlogDecode(float v) {\n"
            + "    float lin = pow(10.0, (v - 0.598206) / 0.241514) - 0.00873;\n"
            + "    return max(lin, 0.0);\n"
            + "}\n"
            + "\n"
            + "float linearToSrgb(float lin) {\n"
            + "    if (lin <= 0.0031308) return lin * 12.92;\n"
            + "    return 1.055 * pow(lin, 1.0 / 2.4) - 0.055;\n"
            + "}\n"
            + "\n"
            + "vec3 vlogToSrgb(vec3 c) {\n"
            + "    return vec3(min(linearToSrgb(vlogDecode(c.r)), 1.0),\n"
            + "                min(linearToSrgb(vlogDecode(c.g)), 1.0),\n"
            + "                min(linearToSrgb(vlogDecode(c.b)), 1.0));\n"
            + "}\n"
            + "\n"
            // 3D LUT 取样。两件事必须同时对：
            //
            // 1) 寻址：value 要映射到「纹素中心」上，即 t = value*(N-1)，
            //    而不是直接拿 value 当归一化坐标。直接当坐标用，GL 会算成
            //    index = value*N-0.5，等于把整张表往里挤了一点点 ——
            //    连恒等 LUT 都会变成一条 S 曲线（暗部 -3、亮部 +3 /255）。
            //    实测：恒等表下 16→13、223→226，和 value*33-0.5 的模型逐点吻合。
            //
            // 2) 插值：用四边体（tetrahedral）而不是硬件三线性。
            //    三线性在饱和区会掉饱和度、拐角处偏色；四边体是按 6 个四面体分块线性，
            //    和 LUT 作者/官方工具的算法一致，同一条 LUT 出来才同色。
            //    所以这里 texelFetch 拿 8 个角自己算，颜色完全可控。
            + "vec3 lut3d(sampler3D lut, vec3 n) {\n"
            + "    vec3 size = vec3(textureSize(lut, 0));\n"
            + "    vec3 t = clamp(n, 0.0, 1.0) * (size - 1.0);\n"
            + "    vec3 i0 = floor(t);\n"
            + "    i0 = min(i0, size - 2.0);\n"
            + "    vec3 f = t - i0;\n"
            + "    ivec3 b = ivec3(i0);\n"
            + "    vec3 c000 = texelFetch(lut, b + ivec3(0, 0, 0), 0).rgb;\n"
            + "    vec3 c100 = texelFetch(lut, b + ivec3(1, 0, 0), 0).rgb;\n"
            + "    vec3 c010 = texelFetch(lut, b + ivec3(0, 1, 0), 0).rgb;\n"
            + "    vec3 c001 = texelFetch(lut, b + ivec3(0, 0, 1), 0).rgb;\n"
            + "    vec3 c110 = texelFetch(lut, b + ivec3(1, 1, 0), 0).rgb;\n"
            + "    vec3 c101 = texelFetch(lut, b + ivec3(1, 0, 1), 0).rgb;\n"
            + "    vec3 c011 = texelFetch(lut, b + ivec3(0, 1, 1), 0).rgb;\n"
            + "    vec3 c111 = texelFetch(lut, b + ivec3(1, 1, 1), 0).rgb;\n"
            + "    float dx = f.x;\n"
            + "    float dy = f.y;\n"
            + "    float dz = f.z;\n"
            + "    if (dx > dy) {\n"
            + "        if (dy > dz) {\n"
            + "            return (1.0 - dx) * c000 + (dx - dy) * c100 + (dy - dz) * c110 + dz * c111;\n"
            + "        } else if (dx > dz) {\n"
            + "            return (1.0 - dx) * c000 + (dx - dz) * c100 + (dz - dy) * c101 + dy * c111;\n"
            + "        } else {\n"
            + "            return (1.0 - dz) * c000 + (dz - dx) * c001 + (dx - dy) * c101 + dy * c111;\n"
            + "        }\n"
            + "    } else {\n"
            + "        if (dz > dy) {\n"
            + "            return (1.0 - dz) * c000 + (dz - dy) * c001 + (dy - dx) * c011 + dx * c111;\n"
            + "        } else if (dz > dx) {\n"
            + "            return (1.0 - dy) * c000 + (dy - dz) * c010 + (dz - dx) * c011 + dx * c111;\n"
            + "        } else {\n"
            + "            return (1.0 - dy) * c000 + (dy - dx) * c010 + (dx - dz) * c110 + dz * c111;\n"
            + "        }\n"
            + "    }\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "    vec4 src = texture(uTex, vTex);\n"
            + "    vec3 base = src.rgb;\n"
            + "    if (uSourceMode == 1) {\n"
            + "        base = toVLogAligned(src.rgb);\n"
            + "    } else if (uSourceMode == 2) {\n"
            + "        base = vlogToSrgb(src.rgb);\n"
            + "    }\n"
            + "    // 第一条：用自己的浓度和「未套 LUT 的画面」混合\n"
            + "    vec3 coord = (base - uDomainMin) / max(uDomainRange, vec3(1e-6));\n"
            + "    vec3 g1 = lut3d(uLut, coord);\n"
            + "    vec3 stage1 = mix(base, g1, clamp(uIntensity1, 0.0, 1.0));\n"
            + "    vec3 result = stage1;\n"
            + "    // 第二条：吃第一条的输出，浓度也是自己的，两边互不影响\n"
            + "    if (uHasLut2 > 0.5) {\n"
            + "        vec3 coord2 = (stage1 - uDomainMin) / max(uDomainRange, vec3(1e-6));\n"
            + "        vec3 g2 = lut3d(uLut2, coord2);\n"
            + "        result = mix(stage1, g2, clamp(uIntensity2, 0.0, 1.0));\n"
            + "    }\n"
            + "    fragColor = vec4(result, src.a);\n"
            + "}\n";

    private final LutTexture lutTexture;
    private final LutTexture lutTexture2;
    private boolean hasLut2;
    private float intensity1 = 1f;
    private float intensity2 = 1f;
    private int sourceMode = SOURCE_PASSTHROUGH;
    private int uniLut;
    private int uniIntensity1;
    private int uniIntensity2;
    private int uniDomainMin;
    private int uniDomainRange;
    private int uniSourceMode;
    private int uniLut2;
    private int uniHasLut2;

    public Lut3DFilter(LutTexture lutTexture, LutTexture lutTexture2) {
        this.lutTexture = lutTexture;
        this.lutTexture2 = lutTexture2;
    }

    /** 第二条 LUT 是否参与。false 时 shader 里那个分支整段跳过 */
    public void setHasLut2(boolean has) {
        this.hasLut2 = has;
    }

    @Override
    protected String fragmentShader() {
        return FRAGMENT;
    }

    @Override
    protected void onCreate() {
        uniLut = GLES30.glGetUniformLocation(program(), "uLut");
        uniIntensity1 = GLES30.glGetUniformLocation(program(), "uIntensity1");
        uniIntensity2 = GLES30.glGetUniformLocation(program(), "uIntensity2");
        uniDomainMin = GLES30.glGetUniformLocation(program(), "uDomainMin");
        uniDomainRange = GLES30.glGetUniformLocation(program(), "uDomainRange");
        uniSourceMode = GLES30.glGetUniformLocation(program(), "uSourceMode");
        uniLut2 = GLES30.glGetUniformLocation(program(), "uLut2");
        uniHasLut2 = GLES30.glGetUniformLocation(program(), "uHasLut2");
    }

    /** 第一条 LUT 的浓度 */
    public void setIntensity(float value) {
        this.intensity1 = value;
    }

    /** 第二条 LUT 的浓度，和第一条完全独立 */
    public void setIntensity2(float value) {
        this.intensity2 = value;
    }

    /** SOURCE_PASSTHROUGH / SOURCE_SRGB_TO_VLOG */
    public void setSourceMode(int mode) {
        this.sourceMode = mode;
    }

    @Override
    protected void onDraw() {
        GLES30.glUniform1f(uniIntensity1, intensity1);
        GLES30.glUniform1f(uniIntensity2, intensity2);
        GLES30.glUniform3f(uniDomainMin, 0f, 0f, 0f);
        GLES30.glUniform3f(uniDomainRange, 1f, 1f, 1f);
        GLES30.glUniform1i(uniSourceMode, sourceMode);
        GLES30.glUniform1i(uniLut, 1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture.id());

        GLES30.glUniform1f(uniHasLut2, hasLut2 ? 1f : 0f);
        GLES30.glUniform1i(uniLut2, 2);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture2.id());
    }
}
