package io.github.lumixlut.edit;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 撤销 / 重做栈。
 *
 * 语义是"记上一个状态"：每次用户确认一次改动（滑杆松手、点选按钮、画完曲线），
 * 就把**改动前**的快照压进来。撤销时弹出它，同时把当前状态塞进重做栈。
 *
 * 有上限，超了丢最老的——编辑过程里来回试色很常见，不能无限涨。
 */
public final class UndoStack {

    private static final int LIMIT = 50;

    private final Deque<EditSnapshot> undo = new ArrayDeque<>();
    private final Deque<EditSnapshot> redo = new ArrayDeque<>();

    /** 记一个"改动前"的状态，并清空重做链（分叉了就不能再往前重做） */
    public void push(EditSnapshot before) {
        if (before == null) {
            return;
        }
        undo.push(before);
        while (undo.size() > LIMIT) {
            undo.removeLast();
        }
        redo.clear();
    }

    /**
     * @param current 当前状态，会被塞进重做栈
     * @return 要恢复到的状态；没得撤就返回 null
     */
    public EditSnapshot undo(EditSnapshot current) {
        if (undo.isEmpty()) {
            return null;
        }
        if (current != null) {
            redo.push(current);
            while (redo.size() > LIMIT) {
                redo.removeLast();
            }
        }
        return undo.pop();
    }

    public EditSnapshot redo(EditSnapshot current) {
        if (redo.isEmpty()) {
            return null;
        }
        if (current != null) {
            undo.push(current);
            while (undo.size() > LIMIT) {
                undo.removeLast();
            }
        }
        return redo.pop();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }

    public int undoDepth() {
        return undo.size();
    }

    public int redoDepth() {
        return redo.size();
    }

    // ---------- 草稿持久化 ----------

    /** 栈顶在前。存草稿时按这个顺序写出去 */
    public java.util.List<EditSnapshot> undoList() {
        return new java.util.ArrayList<>(undo);
    }

    public java.util.List<EditSnapshot> redoList() {
        return new java.util.ArrayList<>(redo);
    }

    /**
     * 从草稿恢复。push 是往头部加，所以要从最老的一端开始压，
     * 压完顺序才和存进去时一致。
     */
    public void restore(java.util.List<EditSnapshot> undoSnapshots,
                        java.util.List<EditSnapshot> redoSnapshots) {
        undo.clear();
        redo.clear();
        if (undoSnapshots != null) {
            for (int i = undoSnapshots.size() - 1; i >= 0; i--) {
                if (undoSnapshots.get(i) != null) {
                    undo.push(undoSnapshots.get(i));
                }
            }
        }
        if (redoSnapshots != null) {
            for (int i = redoSnapshots.size() - 1; i >= 0; i--) {
                if (redoSnapshots.get(i) != null) {
                    redo.push(redoSnapshots.get(i));
                }
            }
        }
    }
}
