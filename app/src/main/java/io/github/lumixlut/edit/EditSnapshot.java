package io.github.lumixlut.edit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一次编辑的完整快照，撤销栈的基本单位。
 *
 * 用快照而不是命令，是因为参数都是数值、体积小（曲线那条 4 KB 是最大头），
 * 50 步也就 200 KB 上下，比维护一套可逆命令简单得多，也不会因为某条命令
 * 写反了导致状态漂移。
 */
public final class EditSnapshot {

    /** 亮度 6 项 */
    public final float[] light;
    /** 色彩 4 项 */
    public final float[] color;
    /** 细节 3 项 */
    public final float[] detail;
    /** 效果 3 项 */
    public final float[] effect;
    public final float[] crop;
    /** HSL 8×3 */
    public final float[] hsl;
    /** 色调分离 3×2 */
    public final float[] split;
    /**
     * 曲线存的是控制点，不是烘焙后的查找表。
     * 存查找表撤销后渲染是对的，但曲线编辑器画不出形状（控制点已经丢了），
     * 用户会觉得"曲线自己变了"。
     */
    public final float[] curvePoints;
    public final int frameType;
    public final int cropIndex;
    public final float lutIntensity;
    public final int sourceMode;

    EditSnapshot(float[] light, float[] color, float[] detail, float[] effect,
                 float[] crop, float[] hsl, float[] split, float[] curvePoints,
                 int frameType, int cropIndex, float lutIntensity, int sourceMode) {
        this.light = light;
        this.color = color;
        this.detail = detail;
        this.effect = effect;
        this.crop = crop;
        this.hsl = hsl;
        this.split = split;
        this.curvePoints = curvePoints;
        this.frameType = frameType;
        this.cropIndex = cropIndex;
        this.lutIntensity = lutIntensity;
        this.sourceMode = sourceMode;
    }

    /** 粗算占用，用来在日志里看栈有多重 */
    public int sizeBytes() {
        return light.length * 4 + color.length * 4 + detail.length * 4 + effect.length * 4
                + crop.length * 4 + hsl.length * 4 + split.length * 4 + curvePoints.length * 4;
    }

    // ---------- 草稿持久化 ----------

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("a", toArray(light));
        o.put("b", toArray(color));
        o.put("d", toArray(detail));
        o.put("e", toArray(effect));
        o.put("c", toArray(crop));
        o.put("h", toArray(hsl));
        o.put("s", toArray(split));
        o.put("k", toArray(curvePoints));
        o.put("f", frameType);
        o.put("ci", cropIndex);
        o.put("li", lutIntensity);
        o.put("sm", sourceMode);
        return o;
    }

    public static EditSnapshot fromJson(JSONObject o) {
        return new EditSnapshot(
                fromArray(o.optJSONArray("a")),
                fromArray(o.optJSONArray("b")),
                fromArray(o.optJSONArray("d")),
                fromArray(o.optJSONArray("e")),
                fromArray(o.optJSONArray("c")),
                fromArray(o.optJSONArray("h")),
                fromArray(o.optJSONArray("s")),
                fromArray(o.optJSONArray("k")),
                o.optInt("f", 0),
                o.optInt("ci", 0),
                (float) o.optDouble("li", 1.0),
                o.optInt("sm", 0));
    }

    private static JSONArray toArray(float[] values) throws JSONException {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (float v : values) {
                array.put(v);
            }
        }
        return array;
    }

    private static float[] fromArray(JSONArray array) {
        if (array == null) {
            return new float[0];
        }
        float[] out = new float[array.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) array.optDouble(i, 0);
        }
        return out;
    }
}
