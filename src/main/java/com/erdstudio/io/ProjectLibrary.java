package com.erdstudio.io;

import com.erdstudio.editor.Settings;
import com.erdstudio.model.Diagram;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The project library: every project is one {@code .erd} file (plain JSON) in a single folder,
 * by default {@code Documents\ERD Studio\Projects}. Pins and "last opened" times live in a small
 * {@code .library.json} next to them; deleted projects go to {@code .trash} first.
 * Nothing here is required for a file to open — a copied .erd file is a complete project.
 */
public final class ProjectLibrary {

    public static final String EXT = ".erd";
    private static final long TRASH_DAYS = 30;
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** A project as shown on the home screen. {@code diagram} is null when the file is unreadable. */
    public record Project(Path file, String name, Diagram diagram, long modified, long lastOpened, boolean pinned) {
        public boolean broken() { return diagram == null; }

        public int tables() { return diagram == null ? 0 : diagram.tables.size(); }

        public int relations() { return diagram == null ? 0 : diagram.relations.size(); }

        public long lastActivity() { return Math.max(modified, lastOpened); }
    }

    public record TrashItem(Path file, String name, Diagram diagram, long deletedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        public boolean pinned;
        public long lastOpened;
        public long deletedAt;
        public String originalName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        public int formatVersion = 1;
        public Map<String, Entry> projects = new LinkedHashMap<>();
        public Map<String, Entry> trash = new LinkedHashMap<>();
    }

    private record Cached(long mtime, long size, Diagram diagram) {}

    private static ProjectLibrary instance;

    private final Path dir;
    private final Map<Path, Cached> cache = new HashMap<>();
    private Meta meta;

    private ProjectLibrary(Path dir) {
        this.dir = dir;
    }

    public static synchronized ProjectLibrary get() {
        Path want = configuredDir();
        if (instance == null || !instance.dir.equals(want)) {
            instance = new ProjectLibrary(want);
            instance.purgeOldTrash();
        }
        return instance;
    }

    /** Default: Documents\ERD Studio\Projects (honours a redirected Documents folder on Windows). */
    public static Path defaultDir() {
        Path docs = null;
        try {
            java.io.File f = javax.swing.filechooser.FileSystemView.getFileSystemView().getDefaultDirectory();
            if (f != null && f.isDirectory()) docs = f.toPath();
        } catch (Throwable ignored) {
            // headless or unusual platform
        }
        Path home = Path.of(System.getProperty("user.home"));
        if (docs == null || docs.equals(home)) {
            Path d = home.resolve("Documents");
            docs = Files.isDirectory(d) ? d : home;
        }
        return docs.resolve("ERD Studio").resolve("Projects");
    }

    public static Path configuredDir() {
        String s = Settings.libraryDir();
        if (!s.isBlank()) {
            try {
                return Path.of(s).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                // invalid path in settings
            }
        }
        return defaultDir().toAbsolutePath().normalize();
    }

    public Path dir() { return dir; }

    private Path trashDir() { return dir.resolve(".trash"); }

    private Path metaFile() { return dir.resolve(".library.json"); }

    public void ensureDir() throws IOException {
        Files.createDirectories(dir);
    }

    /** True when the file lives directly in the library folder (so it is autosaved and listed). */
    public boolean contains(Path p) {
        if (p == null) return false;
        Path parent = p.toAbsolutePath().normalize().getParent();
        return parent != null && parent.equals(dir) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXT);
    }

    // ------------------------------------------------------------------ meta

    private Meta meta() {
        if (meta == null) {
            meta = new Meta();
            try {
                if (Files.exists(metaFile())) meta = JsonStore.fromJson(Files.readString(metaFile()), Meta.class);
                if (meta.projects == null) meta.projects = new LinkedHashMap<>();
                if (meta.trash == null) meta.trash = new LinkedHashMap<>();
            } catch (Exception ignored) {
                meta = new Meta(); // corrupt meta only loses pins
            }
        }
        return meta;
    }

    private void saveMeta() {
        try {
            ensureDir();
            Path tmp = metaFile().resolveSibling(".library.json.tmp");
            Files.writeString(tmp, JsonStore.toJson(meta()));
            try {
                Files.move(tmp, metaFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, metaFile(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // pins are a convenience
        }
    }

    private Entry entry(Path p) {
        return meta().projects.computeIfAbsent(p.getFileName().toString(), k -> new Entry());
    }

    public void setPinned(Path p, boolean pinned) {
        entry(p).pinned = pinned;
        saveMeta();
    }

    public void touchOpened(Path p) {
        if (!contains(p)) return;
        entry(p).lastOpened = System.currentTimeMillis();
        saveMeta();
    }

    // ------------------------------------------------------------------ listing

    public List<Project> list() {
        List<Project> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXT) && Files.isRegularFile(f)).toList();
        } catch (IOException e) {
            return out;
        }
        cache.keySet().retainAll(Set.copyOf(files));
        for (Path f : files) {
            Diagram d = load(f);
            long mod = 0;
            try {
                mod = Files.getLastModifiedTime(f).toMillis();
            } catch (IOException ignored) {
                // keep 0
            }
            Entry e = meta().projects.get(f.getFileName().toString());
            String name = d != null && d.name != null && !d.name.isBlank() ? d.name : stem(f);
            out.add(new Project(f, name, d, mod, e == null ? 0 : e.lastOpened, e != null && e.pinned));
        }
        return out;
    }

    /** Loads a project, cached by modification time and size. Null when unreadable. */
    private Diagram load(Path f) {
        try {
            long mt = Files.getLastModifiedTime(f).toMillis(), size = Files.size(f);
            Cached c = cache.get(f);
            if (c != null && c.mtime == mt && c.size == size) return c.diagram;
            Diagram d;
            try {
                d = JsonStore.load(f);
            } catch (Exception ex) {
                d = null;
            }
            cache.put(f, new Cached(mt, size, d));
            return d;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ create / save / rename

    public Path create(Diagram d) throws IOException {
        ensureDir();
        Path p = uniquePath(d.name, null);
        JsonStore.save(d, p);
        entry(p).lastOpened = System.currentTimeMillis();
        saveMeta();
        return p;
    }

    public void save(Diagram d, Path p) throws IOException {
        ensureDir();
        JsonStore.save(d, p);
    }

    /**
     * Keeps the file name in step with the project name. Returns the (possibly new) path;
     * if the move fails the old path is kept, which is harmless.
     */
    public Path syncFileName(Path p, String name) {
        if (!contains(p)) return p;
        String want = safeName(name);
        if (stem(p).equals(want)) return p;
        String current = stem(p);
        if (current.matches(java.util.regex.Pattern.quote(want) + " \\(\\d+\\)")) return p; // already the deduped form
        Path target = uniquePath(name, p);
        if (target.equals(p)) return p;
        try {
            Files.move(p, target);
        } catch (IOException e) {
            return p;
        }
        Entry e = meta().projects.remove(p.getFileName().toString());
        if (e != null) meta().projects.put(target.getFileName().toString(), e);
        saveMeta();
        cache.remove(p);
        return target;
    }

    /** Renames a project that is not open in any tab. */
    public Path rename(Path p, String newName) throws IOException {
        Diagram d = JsonStore.load(p);
        d.name = newName;
        JsonStore.save(d, p);
        return syncFileName(p, newName);
    }

    public Path duplicate(Path p) throws IOException {
        Diagram d = JsonStore.load(p);
        d.name = copyName(d.name);
        Path target = uniquePath(d.name, null);
        JsonStore.save(d, target);
        return target;
    }

    private String copyName(String name) {
        Set<String> names = new java.util.HashSet<>();
        for (Project pr : list()) names.add(pr.name().toLowerCase(Locale.ROOT));
        String base = (name == null || name.isBlank() ? "Untitled diagram" : name) + " copy";
        String n = base;
        for (int i = 2; names.contains(n.toLowerCase(Locale.ROOT)); i++) n = base + " " + i;
        return n;
    }

    /** Copies an external .erd / .erd.json file into the library and returns the new path. */
    public Path importFile(Path external) throws IOException {
        Diagram d = JsonStore.load(external); // validates
        if (d.name == null || d.name.isBlank()) d.name = stem(external);
        ensureDir();
        Path target = uniquePath(d.name, null);
        JsonStore.save(d, target);
        entry(target).lastOpened = System.currentTimeMillis();
        saveMeta();
        return target;
    }

    public Path uniquePath(String name, Path except) {
        String base = safeName(name);
        Path p = dir.resolve(base + EXT);
        for (int i = 2; Files.exists(p) && !p.equals(except); i++) {
            p = dir.resolve(base + " (" + i + ")" + EXT);
        }
        return p;
    }

    // ------------------------------------------------------------------ trash

    public void trash(Path p) throws IOException {
        Files.createDirectories(trashDir());
        long now = System.currentTimeMillis();
        Path target = trashDir().resolve(now + "-" + p.getFileName());
        Files.move(p, target);
        Entry e = new Entry();
        e.deletedAt = now;
        e.originalName = p.getFileName().toString();
        meta().projects.remove(p.getFileName().toString());
        meta().trash.put(target.getFileName().toString(), e);
        saveMeta();
        cache.remove(p);
    }

    public List<TrashItem> trashItems() {
        List<TrashItem> out = new ArrayList<>();
        if (!Files.isDirectory(trashDir())) return out;
        try (Stream<Path> s = Files.list(trashDir())) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                Entry e = meta().trash.get(f.getFileName().toString());
                Diagram d = load(f);
                long at = e != null ? e.deletedAt : Files.getLastModifiedTime(f).toMillis();
                String name = d != null && d.name != null ? d.name : f.getFileName().toString();
                out.add(new TrashItem(f, name, d, at));
            }
        } catch (IOException ignored) {
            // empty list
        }
        out.sort((a, b) -> Long.compare(b.deletedAt(), a.deletedAt()));
        return out;
    }

    public Path restore(Path trashed) throws IOException {
        Entry e = meta().trash.remove(trashed.getFileName().toString());
        String original = e != null && e.originalName != null ? e.originalName
                : trashed.getFileName().toString().replaceFirst("^\\d+-", "");
        String stem = original.toLowerCase(Locale.ROOT).endsWith(EXT) ? original.substring(0, original.length() - EXT.length()) : original;
        ensureDir();
        Path target = dir.resolve(stem + EXT);
        if (Files.exists(target)) target = uniquePath(stem, null);
        Files.move(trashed, target);
        saveMeta();
        cache.remove(trashed);
        return target;
    }

    public void deletePermanently(Path trashed) throws IOException {
        Files.deleteIfExists(trashed);
        meta().trash.remove(trashed.getFileName().toString());
        saveMeta();
        cache.remove(trashed);
    }

    public void emptyTrash() throws IOException {
        for (TrashItem t : trashItems()) Files.deleteIfExists(t.file());
        meta().trash.clear();
        saveMeta();
    }

    private void purgeOldTrash() {
        long limit = System.currentTimeMillis() - TRASH_DAYS * 24L * 3600 * 1000;
        boolean changed = false;
        for (TrashItem t : trashItems()) {
            if (t.deletedAt() < limit) {
                try {
                    Files.deleteIfExists(t.file());
                    meta().trash.remove(t.file().getFileName().toString());
                    changed = true;
                } catch (IOException ignored) {
                    // try next time
                }
            }
        }
        if (changed) saveMeta();
    }

    // ------------------------------------------------------------------ names

    public static String stem(Path p) {
        String n = p.getFileName().toString();
        String low = n.toLowerCase(Locale.ROOT);
        if (low.endsWith(".erd.json")) return n.substring(0, n.length() - 9);
        if (low.endsWith(EXT)) return n.substring(0, n.length() - EXT.length());
        if (low.endsWith(".json")) return n.substring(0, n.length() - 5);
        return n;
    }

    /** A file-system-safe version of a project name (Windows rules, which are the strictest). */
    public static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("[. ]+$", "").replaceAll("^[. ]+", "");
        if (s.length() > 80) s = s.substring(0, 80).trim();
        if (s.isEmpty()) s = "Untitled diagram";
        if (RESERVED.contains(s.toUpperCase(Locale.ROOT))) s = "_" + s;
        return s;
    }
}
