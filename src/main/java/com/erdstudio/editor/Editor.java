package com.erdstudio.editor;

import com.erdstudio.io.JsonStore;
import com.erdstudio.io.ImportResult;
import com.erdstudio.layout.AutoLayout;
import com.erdstudio.model.Area;
import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.Ids;
import com.erdstudio.model.Note;
import com.erdstudio.model.Palette;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.model.TableIndex;
import com.erdstudio.model.Version;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The single source of truth for one open diagram: owns the model, selection, undo history and file state.
 * Views never talk to each other; they mutate the model through here and listen for changes.
 */
public final class Editor {

    public enum Change { CONTENT, REPLACED, SELECTION }

    public enum Align { LEFT, CENTER, RIGHT, TOP, MIDDLE, BOTTOM }

    @FunctionalInterface
    public interface Listener {
        void onChange(Change change, Object source);
    }

    /** Width/height of anything on the canvas (tables need the view to measure them). */
    public interface Sizer extends Function<Table, AutoLayout.Size> {}

    private Diagram diagram = new Diagram();
    private final History history = new History();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedHashSet<String> selection = new LinkedHashSet<>();
    private String savedSnapshot;
    private int savedVersionCount;
    private boolean versionsChanged;
    private static String clipboard; // shared between tabs
    private static int pasteCount;

    private final ObjectProperty<Path> file = new SimpleObjectProperty<>();
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private final BooleanProperty canUndo = new SimpleBooleanProperty();
    private final BooleanProperty canRedo = new SimpleBooleanProperty();

    public Editor() {
        diagram.dialect = Settings.defaultDialect();
        resetHistory();
    }

    // ------------------------------------------------------------------ state

    public Diagram diagram() { return diagram; }

    public ObjectProperty<Path> fileProperty() { return file; }

    public Path file() { return file.get(); }

    public ReadOnlyBooleanProperty dirtyProperty() { return dirty; }

    public boolean isDirty() { return dirty.get(); }

    public ReadOnlyBooleanProperty canUndoProperty() { return canUndo; }

    public ReadOnlyBooleanProperty canRedoProperty() { return canRedo; }

    public void addListener(Listener l) { listeners.add(l); }

    public boolean isEmpty() {
        return diagram.tables.isEmpty() && diagram.notes.isEmpty() && diagram.areas.isEmpty();
    }

    private void fire(Change c, Object source) {
        for (Listener l : listeners) l.onChange(c, source);
    }

    /** Live change (e.g. while typing) — views refresh, but no undo step yet. */
    public void changed(Object source) {
        fire(Change.CONTENT, source);
    }

    /** A finished user action: refresh views and record an undo step. */
    public void commit(Object source) {
        if (history.record(snapshot())) updateFlags();
        fire(Change.CONTENT, source);
    }

    public void commit() { commit(null); }

    /** Fire a full refresh without touching history (e.g. after a theme or settings change). */
    public void refreshViews() {
        fire(Change.REPLACED, null);
    }

    public void replace(Diagram d, Path path) {
        diagram = d;
        if (diagram.areas == null) diagram.areas = new ArrayList<>();
        if (diagram.versions == null) diagram.versions = new ArrayList<>();
        selection.clear();
        file.set(path);
        resetHistory();
        fire(Change.REPLACED, null);
        fire(Change.SELECTION, null);
    }

    /** For crash recovery: load content but keep it marked as unsaved. */
    public void replaceUnsaved(Diagram d, Path path) {
        replace(d, path);
        savedSnapshot = "";
        updateFlags();
    }

    public void markSaved(Path path) {
        file.set(path);
        savedSnapshot = snapshot();
        history.record(savedSnapshot);
        savedVersionCount = diagram.versions.size();
        versionsChanged = false;
        updateFlags();
    }

    private void resetHistory() {
        String s = snapshot();
        history.reset(s);
        savedSnapshot = s;
        savedVersionCount = diagram.versions.size();
        versionsChanged = false;
        updateFlags();
    }

    /** Serialized model without the viewport and version history (those don't create undo steps). */
    private String snapshot() {
        double vx = diagram.viewX, vy = diagram.viewY, z = diagram.zoom;
        List<Version> versions = diagram.versions;
        diagram.viewX = 0;
        diagram.viewY = 0;
        diagram.zoom = 1;
        diagram.versions = new ArrayList<>();
        try {
            return JsonStore.toJson(diagram);
        } finally {
            diagram.viewX = vx;
            diagram.viewY = vy;
            diagram.zoom = z;
            diagram.versions = versions;
        }
    }

    private void updateFlags() {
        canUndo.set(history.canUndo());
        canRedo.set(history.canRedo());
        dirty.set(!history.current().equals(savedSnapshot) || versionsChanged
                || diagram.versions.size() != savedVersionCount);
    }

    public void undo() { restore(history.undo()); }

    public void redo() { restore(history.redo()); }

    private void restore(String json) {
        if (json == null) return;
        Diagram d = JsonStore.fromJson(json, Diagram.class);
        d.viewX = diagram.viewX;
        d.viewY = diagram.viewY;
        d.zoom = diagram.zoom;
        d.versions = diagram.versions;
        diagram = d;
        selection.removeIf(id -> resolve(id) == null);
        updateFlags();
        fire(Change.REPLACED, null);
        fire(Change.SELECTION, null);
    }

    // ------------------------------------------------------------------ selection

    public Set<String> selection() { return selection; }

    public boolean isSelected(String id) { return selection.contains(id); }

    public void select(String id, boolean additive) {
        if (!additive) selection.clear();
        if (id != null) selection.add(id);
        fire(Change.SELECTION, null);
    }

    public void toggleSelection(String id) {
        if (!selection.remove(id)) selection.add(id);
        fire(Change.SELECTION, null);
    }

    public void setSelection(Set<String> ids) {
        selection.clear();
        selection.addAll(ids);
        fire(Change.SELECTION, null);
    }

    public void clearSelection() {
        if (selection.isEmpty()) return;
        selection.clear();
        fire(Change.SELECTION, null);
    }

    public void selectAll() {
        selection.clear();
        for (Table t : diagram.tables) selection.add(t.id);
        for (Note n : diagram.notes) selection.add(n.id);
        for (Area a : diagram.areas) selection.add(a.id);
        fire(Change.SELECTION, null);
    }

    /** Table, Note, Area or Relation for an id (or null). */
    public Object resolve(String id) {
        Object o = diagram.table(id);
        if (o == null) o = diagram.note(id);
        if (o == null) o = diagram.area(id);
        if (o == null) o = diagram.relation(id);
        return o;
    }

    public Object singleSelection() {
        return selection.size() == 1 ? resolve(selection.iterator().next()) : null;
    }

    public List<Table> selectedTables() {
        List<Table> out = new ArrayList<>();
        for (String id : selection) { Table t = diagram.table(id); if (t != null) out.add(t); }
        return out;
    }

    public List<Note> selectedNotes() {
        List<Note> out = new ArrayList<>();
        for (String id : selection) { Note n = diagram.note(id); if (n != null) out.add(n); }
        return out;
    }

    public List<Area> selectedAreas() {
        List<Area> out = new ArrayList<>();
        for (String id : selection) { Area a = diagram.area(id); if (a != null) out.add(a); }
        return out;
    }

    // ------------------------------------------------------------------ tables & columns

    public Table addTable(double x, double y) {
        Table t = new Table(diagram.uniqueTableName("table_" + (diagram.tables.size() + 1)));
        t.color = Palette.tableColor(diagram.tables.size());
        t.x = Math.round(x);
        t.y = Math.round(y);
        Column id = new Column(Settings.idColumnName(), diagram.dialect.defaultIntType());
        id.primaryKey = true;
        id.notNull = true;
        id.autoIncrement = true;
        t.columns.add(id);
        if (Settings.timestampColumns()) {
            for (String n : List.of("created_at", "updated_at")) {
                Column c = new Column(n, timestampType(diagram.dialect));
                c.notNull = true;
                c.defaultValue = diagram.dialect == Dialect.SQLSERVER ? "GETDATE()" : "CURRENT_TIMESTAMP";
                t.columns.add(c);
            }
        }
        diagram.tables.add(t);
        selection.clear();
        selection.add(t.id);
        commit();
        fire(Change.SELECTION, null);
        return t;
    }

    private static String timestampType(Dialect d) {
        return switch (d) {
            case POSTGRESQL -> "TIMESTAMPTZ";
            case MYSQL, SQLITE -> "DATETIME";
            case SQLSERVER -> "DATETIME2";
        };
    }

    public Column addColumn(Table t) {
        int n = t.columns.size() + 1;
        String name = "column_" + n;
        while (t.columnByName(name) != null) name = "column_" + (++n);
        Column c = new Column(name, diagram.dialect.defaultTextType());
        t.columns.add(c);
        commit();
        return c;
    }

    /**
     * Adds a column from a one-line spec like {@code email varchar(255) nn uq} or
     * {@code price numeric(10,2) default 0}. Flags: pk, nn / notnull, uq / unique, ai, null.
     * {@code ref users} / {@code -> users} also creates a foreign key to that table's primary key.
     */
    public Column quickAddColumn(Table t, String spec) {
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) return null;
        String refTable = null;
        java.util.regex.Matcher rm = java.util.regex.Pattern.compile("(?i)(?:\\s+(?:ref|references)\\s+|\\s*->\\s*|\\s*>\\s*)([A-Za-z_][\\w.]*)\\s*$").matcher(s);
        if (rm.find()) {
            refTable = rm.group(1);
            s = s.substring(0, rm.start()).trim();
        }
        String def = null;
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("(?i)\\s+default\\s+(.+)$").matcher(s);
        if (dm.find()) {
            def = dm.group(1).trim();
            s = s.substring(0, dm.start()).trim();
        }
        List<String> parts = new ArrayList<>(List.of(s.split("\\s+")));
        String name = parts.remove(0);
        if (name.endsWith(":")) name = name.substring(0, name.length() - 1);
        Column c = new Column(name, "");
        StringBuilder type = new StringBuilder();
        for (String p : parts) {
            switch (p.toLowerCase(Locale.ROOT)) {
                case "pk" -> { c.primaryKey = true; c.notNull = true; }
                case "nn", "notnull", "!" -> c.notNull = true;
                case "null", "?" -> c.notNull = false;
                case "uq", "unique" -> c.unique = true;
                case "ai", "autoincrement", "identity" -> c.autoIncrement = true;
                default -> { if (type.length() > 0) type.append(' '); type.append(p); }
            }
        }
        String ty = type.toString().trim();
        if (ty.endsWith("?")) { ty = ty.substring(0, ty.length() - 1); c.notNull = false; }
        if (ty.endsWith("!")) { ty = ty.substring(0, ty.length() - 1); c.notNull = true; }
        Table parent = refTable == null ? null : diagram.tableByName(refTable);
        Column parentPk = parent == null || parent.primaryKey().isEmpty() ? null : parent.primaryKey().get(0);
        if (ty.isEmpty()) ty = parentPk != null ? fkType(parentPk) : guessType(name);
        c.type = ty.toUpperCase(Locale.ROOT).equals(ty) || ty.contains("'") ? ty : ty.toUpperCase(Locale.ROOT);
        if (def != null) c.defaultValue = def;
        String unique = c.name;
        int i = 2;
        while (t.columnByName(unique) != null) unique = c.name + "_" + i++;
        c.name = unique;
        t.columns.add(c);
        if (parentPk != null) {
            Relation r = new Relation(t, c, parent, parentPk);
            r.type = c.unique ? RelationType.ONE_TO_ONE : RelationType.MANY_TO_ONE;
            diagram.relations.add(r);
        }
        commit();
        return c;
    }

    /** Sensible default type from a column name (created_at -> timestamp, is_x -> boolean, …). */
    public String guessType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        Dialect d = diagram.dialect;
        if (n.equals("id") || n.endsWith("_id")) return d.defaultIntType();
        if (n.endsWith("_at") || n.endsWith("_on") || n.equals("timestamp")) return timestampType(d);
        if (n.endsWith("date") || n.equals("birthday")) return "DATE";
        if (n.startsWith("is_") || n.startsWith("has_") || n.startsWith("can_")) return d == Dialect.SQLSERVER ? "BIT" : "BOOLEAN";
        if (n.contains("price") || n.contains("amount") || n.contains("total") || n.contains("cost")) return "NUMERIC(12, 2)";
        if (n.contains("count") || n.contains("qty") || n.contains("quantity") || n.equals("age") || n.contains("order")) return d.defaultIntType();
        if (n.contains("description") || n.contains("body") || n.contains("content") || n.equals("text") || n.contains("bio")) return d == Dialect.SQLSERVER ? "NVARCHAR(MAX)" : "TEXT";
        if (n.equals("uuid") || n.equals("guid")) return d == Dialect.POSTGRESQL ? "UUID" : d == Dialect.SQLSERVER ? "UNIQUEIDENTIFIER" : "CHAR(36)";
        return d.defaultTextType();
    }

    /** Type for a column referencing pk (serial-ish identity types become their plain integer type). */
    public static String fkType(Column pk) {
        String t = pk.type == null ? "INTEGER" : pk.type.trim();
        String u = t.toUpperCase(Locale.ROOT);
        if (u.equals("SERIAL") || u.equals("SERIAL4")) return "INTEGER";
        if (u.equals("BIGSERIAL") || u.equals("SERIAL8")) return "BIGINT";
        if (u.equals("SMALLSERIAL")) return "SMALLINT";
        return t;
    }

    /** Adds {@code <parent>_id} to child (typed like parent's PK) and relates it. */
    public Relation addForeignKey(Table child, Table parent) {
        List<Column> pk = parent.primaryKey();
        if (pk.isEmpty()) return null;
        Column target = pk.get(0);
        String base = Settings.fkPattern().replace("{table}", singular(parent.name));
        String name = base;
        int i = 2;
        while (child.columnByName(name) != null) name = base + "_" + i++;
        Column c = new Column(name, fkType(target));
        c.notNull = true;
        child.columns.add(c);
        Relation r = new Relation(child, c, parent, target);
        diagram.relations.add(r);
        commit();
        return r;
    }

    static String singular(String name) {
        String n = name;
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith("ies") && n.length() > 4) return n.substring(0, n.length() - 3) + "y";
        if (l.endsWith("sses") || l.endsWith("xes") || l.endsWith("ches") || l.endsWith("shes")) return n.substring(0, n.length() - 2);
        if (l.endsWith("s") && !l.endsWith("ss") && n.length() > 3) return n.substring(0, n.length() - 1);
        return n;
    }

    public void deleteColumn(Table t, Column c) {
        t.columns.remove(c);
        diagram.pruneRelations();
        commit();
    }

    // ------------------------------------------------------------------ notes & areas

    public Note addNote(double x, double y) {
        Note n = new Note();
        n.x = Math.round(x);
        n.y = Math.round(y);
        n.color = Palette.NOTE_COLORS[diagram.notes.size() % Palette.NOTE_COLORS.length];
        diagram.notes.add(n);
        selection.clear();
        selection.add(n.id);
        commit();
        fire(Change.SELECTION, null);
        return n;
    }

    public Area addArea(double x, double y, double w, double h) {
        Area a = new Area();
        a.name = "Area " + (diagram.areas.size() + 1);
        a.color = Palette.AREA_COLORS[diagram.areas.size() % Palette.AREA_COLORS.length];
        a.x = Math.round(x);
        a.y = Math.round(y);
        a.width = Math.round(w);
        a.height = Math.round(h);
        diagram.areas.add(a);
        selection.clear();
        selection.add(a.id);
        commit();
        fire(Change.SELECTION, null);
        return a;
    }

    /** Wraps the selected tables and notes in a new area (Ctrl+G). */
    public Area groupSelection(Sizer size) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        int n = 0;
        for (Table t : selectedTables()) {
            AutoLayout.Size s = size.apply(t);
            minX = Math.min(minX, t.x); minY = Math.min(minY, t.y);
            maxX = Math.max(maxX, t.x + s.width()); maxY = Math.max(maxY, t.y + s.height());
            n++;
        }
        for (Note no : selectedNotes()) {
            minX = Math.min(minX, no.x); minY = Math.min(minY, no.y);
            maxX = Math.max(maxX, no.x + no.width); maxY = Math.max(maxY, no.y + no.height);
            n++;
        }
        if (n == 0) return null;
        double pad = 30;
        return addArea(minX - pad, minY - pad - 26, maxX - minX + 2 * pad, maxY - minY + 2 * pad + 26);
    }

    /** Tables and notes that lie completely inside an area. */
    public Set<String> contentsOf(Area a, Sizer size) {
        Set<String> ids = new LinkedHashSet<>();
        for (Table t : diagram.tables) {
            AutoLayout.Size s = size.apply(t);
            if (a.contains(t.x, t.y, s.width(), s.height())) ids.add(t.id);
        }
        for (Note n : diagram.notes) if (a.contains(n.x, n.y, n.width, n.height)) ids.add(n.id);
        for (Area other : diagram.areas) {
            if (other != a && a.contains(other.x, other.y, other.width, other.height)) ids.add(other.id);
        }
        return ids;
    }

    public void deleteSelection() {
        if (selection.isEmpty()) return;
        Set<String> ids = new HashSet<>(selection);
        diagram.tables.removeIf(t -> ids.contains(t.id));
        diagram.notes.removeIf(n -> ids.contains(n.id));
        diagram.areas.removeIf(a -> ids.contains(a.id));
        diagram.relations.removeIf(r -> ids.contains(r.id));
        diagram.pruneRelations();
        selection.clear();
        commit();
        fire(Change.SELECTION, null);
    }

    // ------------------------------------------------------------------ relations

    /**
     * Creates a FK between two columns. If the user dragged from a PK to a non-PK column,
     * the direction is flipped so the FK always lives on the referencing side.
     */
    public Relation connect(Table fromT, Column fromC, Table toT, Column toC) {
        if (fromC == toC) return null;
        if (fromC.primaryKey && !toC.primaryKey && !fromT.id.equals(toT.id)) {
            Table tt = fromT; fromT = toT; toT = tt;
            Column tc = fromC; fromC = toC; toC = tc;
        }
        for (Relation r : diagram.relations) {
            if (r.fromColumnId.equals(fromC.id) && r.toColumnId.equals(toC.id)) return null;
        }
        Relation r = new Relation(fromT, fromC, toT, toC);
        r.type = fromC.unique || (fromC.primaryKey && fromT.primaryKey().size() == 1)
                ? RelationType.ONE_TO_ONE : RelationType.MANY_TO_ONE;
        diagram.relations.add(r);
        selection.clear();
        selection.add(r.id);
        commit();
        fire(Change.SELECTION, null);
        return r;
    }

    // ------------------------------------------------------------------ arrange

    public void autoLayout(Sizer size, boolean selectionOnly) {
        Set<String> subset = null;
        if (selectionOnly) {
            subset = new HashSet<>();
            for (Table t : selectedTables()) subset.add(t.id);
            if (subset.size() < 2) subset = null;
        }
        AutoLayout.apply(diagram, subset, size);
        commit();
    }

    /** Aligns the selected tables, notes and areas to the edge/centre of the selection. */
    public void align(Align how, Sizer size) {
        List<double[]> boxes = new ArrayList<>(); // x, y, w, h
        List<Object> items = new ArrayList<>();
        collectBoxes(size, boxes, items);
        if (items.size() < 2) return;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] b : boxes) {
            minX = Math.min(minX, b[0]); minY = Math.min(minY, b[1]);
            maxX = Math.max(maxX, b[0] + b[2]); maxY = Math.max(maxY, b[1] + b[3]);
        }
        for (int i = 0; i < items.size(); i++) {
            double[] b = boxes.get(i);
            double x = b[0], y = b[1];
            switch (how) {
                case LEFT -> x = minX;
                case RIGHT -> x = maxX - b[2];
                case CENTER -> x = (minX + maxX) / 2 - b[2] / 2;
                case TOP -> y = minY;
                case BOTTOM -> y = maxY - b[3];
                case MIDDLE -> y = (minY + maxY) / 2 - b[3] / 2;
            }
            setPos(items.get(i), Math.round(x), Math.round(y));
        }
        commit();
    }

    /** Spreads the selection with equal gaps (horizontal = left to right). */
    public void distribute(boolean horizontal, Sizer size) {
        List<double[]> boxes = new ArrayList<>();
        List<Object> items = new ArrayList<>();
        collectBoxes(size, boxes, items);
        if (items.size() < 3) return;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> horizontal ? boxes.get(i)[0] : boxes.get(i)[1]));
        double start = horizontal ? boxes.get(order.get(0))[0] : boxes.get(order.get(0))[1];
        double[] lastBox = boxes.get(order.get(order.size() - 1));
        double end = horizontal ? lastBox[0] + lastBox[2] : lastBox[1] + lastBox[3];
        double total = 0;
        for (double[] b : boxes) total += horizontal ? b[2] : b[3];
        double gap = (end - start - total) / (items.size() - 1);
        double cur = start;
        for (int i : order) {
            double[] b = boxes.get(i);
            if (horizontal) setPos(items.get(i), Math.round(cur), b[1]);
            else setPos(items.get(i), b[0], Math.round(cur));
            cur += (horizontal ? b[2] : b[3]) + gap;
        }
        commit();
    }

    private void collectBoxes(Sizer size, List<double[]> boxes, List<Object> items) {
        for (Table t : selectedTables()) {
            AutoLayout.Size s = size.apply(t);
            boxes.add(new double[]{t.x, t.y, s.width(), s.height()});
            items.add(t);
        }
        for (Note n : selectedNotes()) { boxes.add(new double[]{n.x, n.y, n.width, n.height}); items.add(n); }
        for (Area a : selectedAreas()) { boxes.add(new double[]{a.x, a.y, a.width, a.height}); items.add(a); }
    }

    private static void setPos(Object o, double x, double y) {
        if (o instanceof Table t) { t.x = x; t.y = y; }
        else if (o instanceof Note n) { n.x = x; n.y = y; }
        else if (o instanceof Area a) { a.x = x; a.y = y; }
    }

    // ------------------------------------------------------------------ import

    /** Merges or replaces with imported content. Returns ids of new tables. */
    public Set<String> applyImport(ImportResult result, boolean replaceAll, Sizer size) {
        Diagram src = result.diagram();
        if (replaceAll) {
            diagram.tables.clear();
            diagram.relations.clear();
            diagram.notes.clear();
            diagram.areas.clear();
        }
        Map<String, String> renamed = new HashMap<>();
        for (Table t : src.tables) {
            String unique = diagram.uniqueTableName(t.name);
            if (!unique.equals(t.name)) renamed.put(t.id, unique);
        }
        for (Table t : src.tables) if (renamed.containsKey(t.id)) t.name = renamed.get(t.id);

        boolean hasPositions = false;
        for (Table t : src.tables) if (t.x != 0 || t.y != 0) { hasPositions = true; break; }
        if (!hasPositions) AutoLayout.apply(src, null, size, Settings.tidyStyle());
        if (!result.areaMembers().isEmpty() && !hasPositions) fitAreasToTables(src, result.areaMembers(), size, Settings.tidyStyle());
        if (!replaceAll && !isEmpty()) {
            double maxY = -Double.MAX_VALUE, minX = Double.MAX_VALUE;
            for (Table t : diagram.tables) {
                maxY = Math.max(maxY, t.y + size.apply(t).height());
                minX = Math.min(minX, t.x);
            }
            for (Area a : diagram.areas) { maxY = Math.max(maxY, a.y + a.height); minX = Math.min(minX, a.x); }
            double srcMinX = Double.MAX_VALUE, srcMinY = Double.MAX_VALUE;
            for (Table t : src.tables) { srcMinX = Math.min(srcMinX, t.x); srcMinY = Math.min(srcMinY, t.y); }
            for (Area a : src.areas) { srcMinX = Math.min(srcMinX, a.x); srcMinY = Math.min(srcMinY, a.y); }
            double dx = minX - srcMinX, dy = maxY + 140 - srcMinY;
            for (Table t : src.tables) { t.x += dx; t.y += dy; }
            for (Area a : src.areas) { a.x += dx; a.y += dy; }
            for (Note n : src.notes) { n.x += dx; n.y += dy; }
        }
        if (!result.keepsColors()) {
            int colorBase = replaceAll ? 0 : diagram.tables.size();
            for (int i = 0; i < src.tables.size(); i++) src.tables.get(i).color = Palette.tableColor(colorBase + i);
        }
        diagram.tables.addAll(src.tables);
        diagram.relations.addAll(src.relations);
        diagram.areas.addAll(src.areas);
        diagram.notes.addAll(src.notes);
        if (replaceAll) {
            diagram.dialect = src.dialect;
            if (src.name != null && !src.name.isBlank() && !src.name.equals("Untitled diagram")) diagram.name = src.name;
        }

        Set<String> ids = new LinkedHashSet<>();
        for (Table t : src.tables) ids.add(t.id);
        selection.clear();
        selection.addAll(ids);
        commit();
        fire(Change.REPLACED, null);
        fire(Change.SELECTION, null);
        return ids;
    }

    /**
     * "Tidy up": a clean layout that keeps groups together. Tables inside an area are arranged inside it,
     * areas are placed side by side in rows, ungrouped tables go below, loose notes to the right.
     */
    public void tidy(Sizer size) {
        tidy(size, Settings.tidyStyle());
    }

    public void tidy(Sizer size, AutoLayout.Style style) {
        if (diagram.areas.isEmpty()) {
            AutoLayout.apply(diagram, null, size, style);
            placeLooseNotes(diagram, Set.of(), size);
            commit();
            return;
        }
        Map<String, Set<String>> members = new java.util.LinkedHashMap<>();
        Map<String, Set<String>> notesIn = new HashMap<>();
        Set<String> taken = new HashSet<>();
        // smaller areas first so nested/overlapping areas claim their own tables
        List<Area> bySize = new ArrayList<>(diagram.areas);
        bySize.sort(Comparator.comparingDouble(a -> a.width * a.height));
        for (Area a : bySize) {
            Set<String> ids = new LinkedHashSet<>();
            Set<String> ns = new LinkedHashSet<>();
            for (String id : contentsOf(a, size)) {
                if (taken.contains(id)) continue;
                if (diagram.table(id) != null) ids.add(id);
                else if (diagram.note(id) != null) ns.add(id);
                else continue;
                taken.add(id);
            }
            members.put(a.id, ids);
            notesIn.put(a.id, ns);
        }
        Map<String, double[]> areaOld = new HashMap<>();
        for (Area a : diagram.areas) areaOld.put(a.id, new double[]{a.x, a.y});
        fitAreasToTables(diagram, members, size, style);
        for (Area a : diagram.areas) { // notes travel with their area
            double[] o = areaOld.get(a.id);
            for (String nid : notesIn.getOrDefault(a.id, Set.of())) {
                Note n = diagram.note(nid);
                n.x += a.x - o[0];
                n.y += a.y - o[1];
            }
        }
        placeLooseNotes(diagram, taken, size);
        commit();
    }

    private static void placeLooseNotes(Diagram d, Set<String> inAreas, Sizer size) {
        double maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (Table t : d.tables) { maxX = Math.max(maxX, t.x + size.apply(t).width()); minY = Math.min(minY, t.y); }
        for (Area a : d.areas) { maxX = Math.max(maxX, a.x + a.width); minY = Math.min(minY, a.y); }
        if (maxX == -Double.MAX_VALUE) return;
        double y = minY;
        for (Note n : d.notes) {
            if (inAreas.contains(n.id)) continue;
            n.x = Math.round(maxX + 100);
            n.y = Math.round(y);
            y += n.height + 30;
        }
    }

    /**
     * Lays each group out as its own block, draws its area around it and places the blocks in rows;
     * tables that belong to no group are arranged below. Used by tidy() and by imports with TableGroups.
     */
    private static void fitAreasToTables(Diagram d, Map<String, Set<String>> members, Sizer size, AutoLayout.Style style) {
        final double gap = 90, pad = 30, titleH = 30;
        /** One rectangle to pack: an area with its tables, or (area == null) the ungrouped tables. */
        final class Block {
            final Area area; final Set<String> ids; double w, h;
            Block(Area area, Set<String> ids) { this.area = area; this.ids = ids; }
        }
        Set<String> grouped = new HashSet<>();
        List<Block> blocks = new ArrayList<>();
        for (Area a : d.areas) {
            Set<String> ids = members.getOrDefault(a.id, Set.of());
            grouped.addAll(ids);
            Block b = new Block(a, ids);
            if (ids.isEmpty()) {
                b.w = a.width;
                b.h = a.height;
            } else {
                double[] box = layoutBlock(d, ids, size, pad, pad + titleH, style);
                b.w = Math.max(220, box[0] + 2 * pad);
                b.h = Math.max(140, box[1] + 2 * pad + titleH);
            }
            blocks.add(b);
        }
        Set<String> rest = new HashSet<>();
        for (Table t : d.tables) if (!grouped.contains(t.id)) rest.add(t.id);
        if (!rest.isEmpty()) {
            Block b = new Block(null, rest);
            double[] box = layoutBlock(d, rest, size, 0, 0, style);
            b.w = box[0];
            b.h = box[1];
            blocks.add(b);
        }

        // choose the row width whose overall shape is closest to a widescreen (about 1.7 : 1) picture
        double rowLimit = Double.MAX_VALUE, bestScore = Double.MAX_VALUE, acc = 0;
        for (int k = 0; k < blocks.size(); k++) {
            acc += blocks.get(k).w + (k > 0 ? gap : 0);
            double limit = acc + 1, cx = 0, cy = 0, rh = 0, maxW = 0;
            for (Block bl : blocks) {
                if (cx > 0 && cx + bl.w > limit) { cy += rh + gap; cx = 0; rh = 0; }
                cx += bl.w + gap;
                rh = Math.max(rh, bl.h);
                maxW = Math.max(maxW, cx - gap);
            }
            double score = Math.abs(Math.log((maxW / Math.max(1, cy + rh)) / 1.7));
            if (score < bestScore - 1e-9) { bestScore = score; rowLimit = limit; }
        }
        double x = 80, y = 80, rowH = 0;
        for (Block bl : blocks) {
            if (x > 80 && x - 80 + bl.w > rowLimit) { x = 80; y += rowH + gap; rowH = 0; }
            for (Table t : d.tables) if (bl.ids.contains(t.id)) { t.x = Math.round(t.x + x); t.y = Math.round(t.y + y); }
            if (bl.area != null) {
                bl.area.x = Math.round(x);
                bl.area.y = Math.round(y);
                bl.area.width = Math.round(bl.w);
                bl.area.height = Math.round(bl.h);
            }
            x += bl.w + gap;
            rowH = Math.max(rowH, bl.h);
        }
    }

    /** Lays the given tables out and moves them so their bounding box starts at (ox, oy). Returns {w, h}. */
    private static double[] layoutBlock(Diagram d, Set<String> ids, Sizer size, double ox, double oy, AutoLayout.Style style) {
        for (Table t : d.tables) if (ids.contains(t.id)) { t.x = 0; t.y = 0; }
        AutoLayout.apply(d, ids, size, style);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Table t : d.tables) {
            if (!ids.contains(t.id)) continue;
            AutoLayout.Size s = size.apply(t);
            minX = Math.min(minX, t.x); minY = Math.min(minY, t.y);
            maxX = Math.max(maxX, t.x + s.width()); maxY = Math.max(maxY, t.y + s.height());
        }
        for (Table t : d.tables) if (ids.contains(t.id)) { t.x += ox - minX; t.y += oy - minY; }
        return new double[]{maxX - minX, maxY - minY};
    }

    // ------------------------------------------------------------------ versions

    public Version saveVersion(String name, String note) {
        Version v = new Version();
        v.name = name == null || name.isBlank() ? "Version " + (diagram.versions.size() + 1) : name.trim();
        v.note = note == null ? "" : note;
        v.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        v.snapshot = snapshot();
        diagram.versions.add(v);
        versionsChanged = true;
        updateFlags();
        fire(Change.CONTENT, this);
        return v;
    }

    public void deleteVersion(Version v) {
        diagram.versions.remove(v);
        versionsChanged = true;
        updateFlags();
        fire(Change.CONTENT, this);
    }

    public void renameVersion(Version v, String name) {
        v.name = name;
        versionsChanged = true;
        updateFlags();
    }

    public static Diagram versionDiagram(Version v) {
        return JsonStore.fromJson(v.snapshot, Diagram.class);
    }

    /** Replaces the schema with a saved version (undoable; the version list is kept). */
    public void restoreVersion(Version v) {
        Diagram d = versionDiagram(v);
        d.versions = diagram.versions;
        d.viewX = diagram.viewX;
        d.viewY = diagram.viewY;
        d.zoom = diagram.zoom;
        diagram = d;
        selection.clear();
        commit();
        fire(Change.REPLACED, null);
        fire(Change.SELECTION, null);
    }

    /** Copy of the current schema without history, for diffing. */
    public Diagram currentSchema() {
        return JsonStore.fromJson(snapshot(), Diagram.class);
    }

    // ------------------------------------------------------------------ clipboard

    public boolean copySelection() {
        List<Table> tables = selectedTables();
        List<Note> notes = selectedNotes();
        List<Area> areas = selectedAreas();
        if (tables.isEmpty() && notes.isEmpty() && areas.isEmpty()) return false;
        Diagram clip = new Diagram();
        clip.tables.addAll(tables);
        clip.notes.addAll(notes);
        clip.areas.addAll(areas);
        Set<String> ids = new HashSet<>();
        for (Table t : tables) ids.add(t.id);
        for (Relation r : diagram.relations) {
            if (ids.contains(r.fromTableId) && ids.contains(r.toTableId)) clip.relations.add(r);
        }
        clipboard = JsonStore.toJson(clip);
        pasteCount = 0;
        return true;
    }

    public boolean hasClipboard() { return clipboard != null; }

    public void paste() {
        if (clipboard == null) return;
        pasteCount++;
        Diagram clip = JsonStore.fromJson(clipboard, Diagram.class);
        double offset = 40.0 * pasteCount;
        Map<String, String> idMap = new HashMap<>();
        Set<String> newSel = new LinkedHashSet<>();
        for (Table t : clip.tables) {
            String old = t.id;
            t.id = Ids.next();
            idMap.put(old, t.id);
            t.name = diagram.uniqueTableName(t.name);
            t.x += offset;
            t.y += offset;
            Map<String, String> colMap = new HashMap<>();
            for (Column c : t.columns) {
                String oc = c.id;
                c.id = Ids.next();
                colMap.put(oc, c.id);
                idMap.put(oc, c.id);
            }
            for (TableIndex idx : t.indexes) {
                idx.id = Ids.next();
                idx.columnIds.replaceAll(cid -> colMap.getOrDefault(cid, cid));
            }
            diagram.tables.add(t);
            newSel.add(t.id);
        }
        for (Relation r : clip.relations) {
            r.id = Ids.next();
            r.fromTableId = idMap.get(r.fromTableId);
            r.toTableId = idMap.get(r.toTableId);
            r.fromColumnId = idMap.get(r.fromColumnId);
            r.toColumnId = idMap.get(r.toColumnId);
            r.name = "";
            diagram.relations.add(r);
        }
        for (Note n : clip.notes) {
            n.id = Ids.next();
            n.x += offset;
            n.y += offset;
            diagram.notes.add(n);
            newSel.add(n.id);
        }
        for (Area a : clip.areas) {
            a.id = Ids.next();
            a.x += offset;
            a.y += offset;
            diagram.areas.add(a);
            newSel.add(a.id);
        }
        diagram.pruneRelations();
        selection.clear();
        selection.addAll(newSel);
        commit();
        fire(Change.SELECTION, null);
    }

    public void duplicateSelection() {
        String saved = clipboard;
        int savedCount = pasteCount;
        if (copySelection()) paste();
        if (saved != null) {
            clipboard = saved;
            pasteCount = savedCount;
        }
    }
}
