package com.erdstudio.ui;

import com.erdstudio.io.JsonStore;
import com.erdstudio.io.ProjectLibrary;
import com.erdstudio.mcp.Backend;
import com.erdstudio.mcp.McpTools;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs MCP tool calls inside the open window: edits go through the tab's editor (one undo step,
 * live on the canvas, autosaved like any other change) and the touched tables get selected.
 */
final class McpBridge implements Backend {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MainWindow w;

    McpBridge(MainWindow w) {
        this.w = w;
    }

    /** Called on the single-instance listener thread with one JSON request. */
    String handle(String json) {
        ObjectNode answer = JSON.createObjectNode();
        try {
            JsonNode req = JSON.readTree(json);
            String tool = req.path("tool").asText("");
            JsonNode args = req.get("args");
            CompletableFuture<String> f = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    f.complete(McpTools.call(tool, args, this));
                } catch (ToolError e) {
                    f.completeExceptionally(e);
                } catch (Throwable e) {
                    f.completeExceptionally(new ToolError("Error: " + e));
                }
            });
            answer.put("result", f.get(90, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException e) {
            answer.put("error", e.getCause() == null ? String.valueOf(e) : e.getCause().getMessage());
        } catch (java.util.concurrent.TimeoutException e) {
            answer.put("error", "ERD Studio is busy (a dialog may be open). Try again.");
        } catch (Exception e) {
            answer.put("error", "Bad request: " + e.getMessage());
        }
        try {
            return JSON.writeValueAsString(answer);
        } catch (Exception e) {
            return "{\"error\":\"internal\"}";
        }
    }

    @Override
    public boolean live() { return true; }

    @Override
    public List<ProjectRef> projects() {
        List<ProjectRef> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (ProjectLibrary.Project p : ProjectLibrary.get().list()) {
            if (p.broken()) continue;
            Workspace ws = w.workspaceFor(p.file());
            Diagram d = ws != null ? ws.editor.diagram() : p.diagram();
            out.add(new ProjectRef(d.name, p.file().toString(), ws != null, d.tables.size(), d.dialect.label));
            seen.add(p.file().toAbsolutePath().normalize());
        }
        for (Workspace ws : w.workspaces.values()) {
            Path f = ws.editor.file();
            if (f != null && seen.contains(f.toAbsolutePath().normalize())) continue;
            Diagram d = ws.editor.diagram();
            out.add(new ProjectRef(d.name, f == null ? "(not saved yet)" : f.toString(), true, d.tables.size(), d.dialect.label));
        }
        return out;
    }

    /** Finds an open tab for the project, or null. */
    private Workspace openTab(String project) {
        if (project == null || project.isBlank()) return w.cur();
        String q = project.trim().toLowerCase(Locale.ROOT);
        for (Workspace ws : w.workspaces.values()) if (ws.editor.diagram().name.toLowerCase(Locale.ROOT).equals(q)) return ws;
        return null;
    }

    private Path libraryFile(String project) throws ToolError {
        List<ProjectLibrary.Project> all = ProjectLibrary.get().list();
        if (project == null || project.isBlank()) {
            if (w.workspaces.size() > 1 || all.size() != 1)
                throw new ToolError("No project is selected in ERD Studio; pass 'project'. Use list_projects to see them.");
        }
        return com.erdstudio.mcp.FileBackend.resolve(project, all);
    }

    @Override
    public Diagram read(String project) throws ToolError {
        Workspace ws = openTab(project);
        if (ws != null) return ws.editor.diagram();
        Path p = libraryFile(project);
        Workspace open = w.workspaceFor(p);
        if (open != null) return open.editor.diagram();
        try {
            return JsonStore.load(p);
        } catch (Exception e) {
            throw new ToolError("Could not read " + p.getFileName() + ": " + e.getMessage());
        }
    }

    @Override
    public <T> T edit(String project, EditFn<T> fn) throws ToolError {
        Workspace ws = openTab(project);
        if (ws == null) {
            Path p = libraryFile(project);
            ws = w.workspaceFor(p);
            if (ws == null) {
                w.openPath(p);
                ws = w.workspaceFor(p);
            }
            if (ws == null) throw new ToolError("Could not open the project in ERD Studio.");
        }
        javafx.scene.control.Tab tab = w.tabOf(ws);
        if (tab != null) w.tabs.getSelectionModel().select(tab);
        Set<String> touched = new LinkedHashSet<>();
        T result = fn.apply(ws.editor, ws.canvas::sizeOf, touched);
        Set<String> exist = new LinkedHashSet<>();
        for (String id : touched) if (ws.editor.resolve(id) != null) exist.add(id);
        if (!exist.isEmpty()) ws.editor.setSelection(exist);
        Workspace target = ws;
        ws.showToast("Changed by your AI assistant", "Undo", () -> target.editor.undo());
        return result;
    }

    @Override
    public String create(String name, Dialect dialect) throws ToolError {
        Diagram d = new Diagram();
        d.name = name.trim();
        d.dialect = dialect;
        Workspace ws = w.createAndOpen(d);
        if (ws == null) throw new ToolError("Could not create the project (is the projects folder writable?).");
        return d.name;
    }
}
