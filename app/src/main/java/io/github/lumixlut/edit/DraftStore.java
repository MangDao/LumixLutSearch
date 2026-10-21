package io.github.lumixlut.edit;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 草稿：把「这次编辑到哪了」落到磁盘，下次进来能原样接着改。
 *
 * 存三样东西：
 *   source.jpg  当前照片的副本（用户原来是 content:// 拿的，重开时可能已经失效）
 *   lut.cube    LUT 副本
 *   state.json  当前参数 + 整条撤销/重做链
 *
 * 撤销链也一起存，是为了「下次进来还能退回去」——
 * 只恢复最终状态的话，用户一按撤销就没反应，会觉得草稿是坏的。
 */
public final class DraftStore {

    private static final String TAG = "DraftStore";
    private static final String DIR = "draft";
    private static final String FILE_STATE = "state.json";
    private static final String FILE_SOURCE = "source.jpg";
    private static final String FILE_LUT = "lut.cube";
    private static final int VERSION = 1;

    public static final class Draft {
        public File sourceFile;
        public File lutFile;
        public EditSnapshot current;
        public List<EditSnapshot> undo = new ArrayList<>();
        public List<EditSnapshot> redo = new ArrayList<>();
        public float cropAspect;
        public int overrideMode = -1;
    }

    private DraftStore() {
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "建不了草稿目录");
        }
        return d;
    }

    public static boolean has(Context ctx) {
        return new File(dir(ctx), FILE_STATE).exists();
    }

    public static void clear(Context ctx) {
        File d = dir(ctx);
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    Log.w(TAG, "删不掉 " + f.getName());
                }
            }
        }
    }

    /**
     * 后台线程调用。source 允许为 null（比如只调了参数还没选图）。
     */
    public static void save(Context ctx, Bitmap source, File lutSource,
                            EditSnapshot current, UndoStack stack,
                            float cropAspect, int overrideMode) throws IOException {
        File d = dir(ctx);

        if (source != null && !source.isRecycled()) {
            File out = new File(d, FILE_SOURCE);
            try (OutputStream os = new FileOutputStream(out)) {
                source.compress(Bitmap.CompressFormat.JPEG, 92, os);
            }
        }

        if (lutSource != null && lutSource.exists()) {
            File out = new File(d, FILE_LUT);
            Files.copy(lutSource.toPath(), out.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        JSONObject root = new JSONObject();
        try {
            root.put("v", VERSION);
            root.put("cropAspect", cropAspect);
            root.put("overrideMode", overrideMode);
            if (current != null) {
                root.put("current", current.toJson());
            }
            root.put("undo", toJsonArray(stack == null ? null : stack.undoList()));
            root.put("redo", toJsonArray(stack == null ? null : stack.redoList()));
        } catch (Exception e) {
            throw new IOException("序列化失败：" + e.getMessage(), e);
        }

        File stateFile = new File(d, FILE_STATE);
        try (OutputStream os = new FileOutputStream(stateFile)) {
            os.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.d(TAG, "草稿已保存：撤销 " + (stack == null ? 0 : stack.undoDepth())
                + " 步，重做 " + (stack == null ? 0 : stack.redoDepth()) + " 步");
    }

    /** 后台线程调用。没有草稿或读坏了都返回 null */
    public static Draft load(Context ctx) {
        File d = dir(ctx);
        File stateFile = new File(d, FILE_STATE);
        if (!stateFile.exists()) {
            return null;
        }
        try {
            String text = new String(Files.readAllBytes(stateFile.toPath()),
                    StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            Draft draft = new Draft();
            draft.cropAspect = (float) root.optDouble("cropAspect", 0);
            draft.overrideMode = root.optInt("overrideMode", -1);
            JSONObject cur = root.optJSONObject("current");
            draft.current = cur == null ? null : EditSnapshot.fromJson(cur);
            draft.undo = fromJsonArray(root.optJSONArray("undo"));
            draft.redo = fromJsonArray(root.optJSONArray("redo"));

            File source = new File(d, FILE_SOURCE);
            draft.sourceFile = source.exists() ? source : null;
            File lut = new File(d, FILE_LUT);
            draft.lutFile = lut.exists() ? lut : null;
            return draft;
        } catch (Exception e) {
            Log.w(TAG, "草稿读取失败：" + e.getMessage());
            return null;
        }
    }

    private static JSONArray toJsonArray(List<EditSnapshot> snapshots) throws Exception {
        JSONArray array = new JSONArray();
        if (snapshots != null) {
            for (EditSnapshot s : snapshots) {
                array.put(s == null ? JSONObject.NULL : s.toJson());
            }
        }
        return array;
    }

    private static List<EditSnapshot> fromJsonArray(JSONArray array) {
        List<EditSnapshot> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            out.add(o == null ? null : EditSnapshot.fromJson(o));
        }
        return out;
    }
}
