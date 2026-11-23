package com.erdstudio.mcp;

import com.erdstudio.editor.Editor;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;

import java.util.List;
import java.util.Set;

/**
 * Where MCP tools read and write diagrams: the running app (live, changes appear on the canvas
 * and can be undone) or the project files directly when ERD Studio is closed.
 */
public interface Backend {

    record ProjectRef(String name, String location, boolean open, int tables, String dialect) {}

    final class ToolError extends Exception {
        public ToolError(String message) { super(message); }
    }

    @FunctionalInterface
    interface EditFn<T> {
        /** {@code touched} collects ids of tables/relations the tool changed (they get selected in the app). */
        T apply(Editor editor, Editor.Sizer size, Set<String> touched) throws ToolError;
    }

    boolean live();

    List<ProjectRef> projects() throws ToolError;

    /** Resolves a project by name, file name or path; null/blank = the active (or only) project. */
    Diagram read(String project) throws ToolError;

    <T> T edit(String project, EditFn<T> fn) throws ToolError;

    /** Creates a library project and returns its name. */
    String create(String name, Dialect dialect) throws ToolError;
}
