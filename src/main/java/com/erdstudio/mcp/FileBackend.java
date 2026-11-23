package com.erdstudio.mcp;

import com.erdstudio.editor.Editor;
import com.erdstudio.io.JsonStore;
import com.erdstudio.io.ProjectLibrary;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Works directly on the library's .erd files; used while the ERD Studio window is closed. */
public final class FileBackend implements Backend {

    @Override
    public boolean live() { return false; }

    @Override
    public List<ProjectRef> projects() {
        List<ProjectRef> out = new ArrayList<>();
        for (ProjectLibrary.Project p : ProjectLibrary.get().list()) {
            if (p.broken()) continue;
            out.add(new ProjectRef(p.name(), p.file().toString(), false, p.tables(), p.diagram().dialect.label));
        }
        return out;
    }

    public static Path resolve(String project, List<ProjectLibrary.Project> all) throws ToolError {
        List<ProjectLibrary.Project> ok = new ArrayList<>();
        for (ProjectLibrary.Project p : all) if (!p.broken()) ok.add(p);
        if (project == null || project.isBlank()) {
            if (ok.size() == 1) return ok.get(0).file();
            if (ok.isEmpty()) throw new ToolError("The library is empty. Use create_project first.");
            throw new ToolError("Several projects exist; pass 'project'. Projects: " + names(ok));
        }
        String q = project.trim();
        try {
            Path p = Path.of(q);
            if (p.isAbsolute() && Files.isRegularFile(p)) return p;
        } catch (Exception ignored) {
            // not a path
        }
        for (ProjectLibrary.Project p : ok) if (p.name().equalsIgnoreCase(q)) return p.file();
        for (ProjectLibrary.Project p : ok) if (ProjectLibrary.stem(p.file()).equalsIgnoreCase(q)) return p.file();
        List<ProjectLibrary.Project> partial = new ArrayList<>();
        for (ProjectLibrary.Project p : ok) if (p.name().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) partial.add(p);
        if (partial.size() == 1) return partial.get(0).file();
        throw new ToolError("No project named '" + q + "'. Projects: " + (ok.isEmpty() ? "none" : names(ok)));
    }

    private static String names(List<ProjectLibrary.Project> ps) {
        List<String> n = new ArrayList<>();
        for (ProjectLibrary.Project p : ps) n.add(p.name());
        return String.join(", ", n);
    }

    @Override
    public Diagram read(String project) throws ToolError {
        Path p = resolve(project, ProjectLibrary.get().list());
        try {
            return JsonStore.load(p);
        } catch (Exception e) {
            throw new ToolError("Could not read " + p.getFileName() + ": " + e.getMessage());
        }
    }

    @Override
    public <T> T edit(String project, EditFn<T> fn) throws ToolError {
        ProjectLibrary lib = ProjectLibrary.get();
        Path p = resolve(project, lib.list());
        Editor ed = new Editor();
        try {
            ed.replace(JsonStore.load(p), p);
        } catch (Exception e) {
            throw new ToolError("Could not read " + p.getFileName() + ": " + e.getMessage());
        }
        T result = fn.apply(ed, Sizes::of, new HashSet<>());
        try {
            JsonStore.save(ed.diagram(), p);
            if (lib.contains(p)) lib.syncFileName(p, ed.diagram().name);
        } catch (Exception e) {
            throw new ToolError("Could not save " + p.getFileName() + ": " + e.getMessage());
        }
        return result;
    }

    @Override
    public String create(String name, Dialect dialect) throws ToolError {
        Diagram d = new Diagram();
        d.name = name.trim();
        d.dialect = dialect;
        try {
            ProjectLibrary.get().create(d);
        } catch (Exception e) {
            throw new ToolError("Could not create the project: " + e.getMessage());
        }
        return d.name;
    }
}
