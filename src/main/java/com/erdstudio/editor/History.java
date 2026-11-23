package com.erdstudio.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/** Snapshot-based undo/redo. Each entry is the full diagram serialized as JSON. */
final class History {
    private static final int LIMIT = 200;

    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private String current;

    void reset(String snapshot) {
        undo.clear();
        redo.clear();
        current = snapshot;
    }

    /** @return true if the snapshot differs from the current state (i.e. something was recorded) */
    boolean record(String snapshot) {
        if (snapshot.equals(current)) return false;
        if (current != null) undo.push(current);
        while (undo.size() > LIMIT) undo.removeLast();
        redo.clear();
        current = snapshot;
        return true;
    }

    boolean canUndo() { return !undo.isEmpty(); }

    boolean canRedo() { return !redo.isEmpty(); }

    String undo() {
        if (undo.isEmpty()) return null;
        redo.push(current);
        current = undo.pop();
        return current;
    }

    String redo() {
        if (redo.isEmpty()) return null;
        undo.push(current);
        current = redo.pop();
        return current;
    }

    String current() { return current; }
}
