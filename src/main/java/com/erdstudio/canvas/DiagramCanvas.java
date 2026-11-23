package com.erdstudio.canvas;

import com.erdstudio.editor.Editor;
import com.erdstudio.layout.AutoLayout;
import com.erdstudio.model.Area;
import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Note;
import com.erdstudio.model.Palette;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.ui.Theme;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** The infinite, zoomable diagram surface. */
public final class DiagramCanvas extends Region implements TableNode.Hooks, NoteNode.Hooks, AreaNode.Hooks {

    public static final double GRID = 20;
    private static final double MIN_ZOOM = 0.1, MAX_ZOOM = 3.0;

    private final Editor editor;

    private final Canvas grid = new Canvas();
    private final Translate pan = new Translate();
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final Group world = new Group();
    private final Group content = new Group();
    private final Group areasLayer = new Group();
    private final Group notesLayer = new Group();
    private final Group edgesLayer = new Group();
    private final Group tablesLayer = new Group();
    private final Group overlay = new Group();
    private final Rectangle marquee = new Rectangle();
    private final Rectangle clip = new Rectangle();

    private final Map<String, TableNode> tableNodes = new LinkedHashMap<>();
    private final Map<String, NoteNode> noteNodes = new LinkedHashMap<>();
    private final Map<String, AreaNode> areaNodes = new LinkedHashMap<>();
    private final Map<String, RelationEdge> edges = new LinkedHashMap<>();

    private final ReadOnlyDoubleWrapper zoom = new ReadOnlyDoubleWrapper(1);
    private final BooleanProperty snapToGrid = new SimpleBooleanProperty(true);
    private final BooleanProperty showGrid = new SimpleBooleanProperty(true);
    private final List<Runnable> viewListeners = new CopyOnWriteArrayList<>();
    private final ContextMenu menu = new ContextMenu();

    // interaction state
    private boolean spaceDown;
    private boolean panning;
    private double lastX, lastY, pressX, pressY;
    private boolean moved;
    private final Map<String, double[]> dragStart = new HashMap<>();
    private String dragPrimary;
    private String hoveredRelation;
    private Timeline viewAnimation;

    // connection drag
    private TableNode connectFrom;
    private Column connectColumn;
    private final SVGPath connectLine = new SVGPath();
    private final Circle connectDot = new Circle(4);
    private TableNode connectHover;

    public DiagramCanvas(Editor editor) {
        this.editor = editor;
        getStyleClass().add("diagram-canvas");
        setFocusTraversable(true);

        world.getTransforms().addAll(pan, scale);
        content.getChildren().addAll(areasLayer, notesLayer, edgesLayer, tablesLayer, overlay);
        world.getChildren().add(content);
        marquee.getStyleClass().add("marquee");
        marquee.setVisible(false);
        marquee.setMouseTransparent(true);
        connectLine.getStyleClass().add("connect-line");
        connectDot.getStyleClass().add("connect-dot");
        overlay.setMouseTransparent(true);
        getChildren().addAll(grid, world, marquee);
        setClip(clip);

        pan.xProperty().addListener(o -> viewChanged());
        pan.yProperty().addListener(o -> viewChanged());
        scale.xProperty().addListener(o -> { zoom.set(scale.getX()); viewChanged(); });
        showGrid.addListener(o -> drawGrid());
        Theme.darkProperty().addListener(o -> drawGrid());

        installMouse();
        installKeys();

        editor.addListener((change, source) -> {
            switch (change) {
                case REPLACED, CONTENT -> sync();
                case SELECTION -> applySelection();
            }
        });
        sync();
    }

    // ------------------------------------------------------------------ public API

    public ReadOnlyDoubleProperty zoomProperty() { return zoom.getReadOnlyProperty(); }

    public double zoom() { return scale.getX(); }

    public BooleanProperty snapToGridProperty() { return snapToGrid; }

    public BooleanProperty showGridProperty() { return showGrid; }

    public void addViewListener(Runnable r) { viewListeners.add(r); }

    public Point2D screenToWorld(double x, double y) {
        return new Point2D((x - pan.getX()) / zoom(), (y - pan.getY()) / zoom());
    }

    /** Visible area in world coordinates. */
    public Rectangle2D viewportWorld() {
        Point2D a = screenToWorld(0, 0);
        return new Rectangle2D(a.getX(), a.getY(), Math.max(1, getWidth()) / zoom(), Math.max(1, getHeight()) / zoom());
    }

    public Point2D viewportCenterWorld() {
        return screenToWorld(getWidth() / 2, getHeight() / 2);
    }

    /** Free spot near the viewport centre for a new element. */
    public Point2D newElementPosition(double w, double h) {
        Point2D c = viewportCenterWorld();
        double x = snap(c.getX() - w / 2), y = snap(c.getY() - h / 2);
        for (int i = 0; i < 40 && occupied(x, y); i++) { x += 40; y += 40; }
        return new Point2D(x, y);
    }

    private boolean occupied(double x, double y) {
        for (Table t : editor.diagram().tables) if (Math.abs(t.x - x) < 20 && Math.abs(t.y - y) < 20) return true;
        for (Note n : editor.diagram().notes) if (Math.abs(n.x - x) < 20 && Math.abs(n.y - y) < 20) return true;
        return false;
    }

    public AutoLayout.Size sizeOf(Table t) {
        return new AutoLayout.Size(TableNode.computeWidth(t), TableNode.height(t));
    }

    public Bounds contentBounds() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (TableNode n : tableNodes.values()) {
            Table t = n.table();
            minX = Math.min(minX, t.x); minY = Math.min(minY, t.y);
            maxX = Math.max(maxX, t.x + n.nodeWidth()); maxY = Math.max(maxY, t.y + n.nodeHeight());
            any = true;
        }
        for (NoteNode n : noteNodes.values()) {
            Note t = n.note();
            minX = Math.min(minX, t.x); minY = Math.min(minY, t.y);
            maxX = Math.max(maxX, t.x + t.width); maxY = Math.max(maxY, t.y + t.height);
            any = true;
        }
        for (AreaNode n : areaNodes.values()) {
            Area a = n.area();
            minX = Math.min(minX, a.x); minY = Math.min(minY, a.y);
            maxX = Math.max(maxX, a.x + a.width); maxY = Math.max(maxY, a.y + a.height);
            any = true;
        }
        if (!any) return null;
        return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }

    public void setView(double panX, double panY, double z) {
        stopAnimation();
        z = clamp(z);
        scale.setX(z);
        scale.setY(z);
        pan.setX(panX);
        pan.setY(panY);
    }

    public double panX() { return pan.getX(); }

    public double panY() { return pan.getY(); }

    public void zoomAt(double factor, double sx, double sy) {
        double old = zoom(), z = clamp(old * factor);
        if (z == old) return;
        stopAnimation();
        pan.setX(sx - (sx - pan.getX()) * z / old);
        pan.setY(sy - (sy - pan.getY()) * z / old);
        scale.setX(z);
        scale.setY(z);
    }

    public void zoomIn() { animateZoomAroundCenter(zoom() * 1.25); }

    public void zoomOut() { animateZoomAroundCenter(zoom() / 1.25); }

    public void resetZoom() { animateZoomAroundCenter(1); }

    private void animateZoomAroundCenter(double target) {
        target = clamp(target);
        Point2D c = viewportCenterWorld();
        animateTo(getWidth() / 2 - c.getX() * target, getHeight() / 2 - c.getY() * target, target);
    }

    public void fitToContent() {
        Bounds b = contentBounds();
        if (b == null || getWidth() < 10) { animateTo(60, 60, 1); return; }
        double pad = 60;
        double z = clamp(Math.min((getWidth() - 2 * pad) / b.getWidth(), (getHeight() - 2 * pad) / b.getHeight()));
        z = Math.min(z, 1.25);
        double px = (getWidth() - b.getWidth() * z) / 2 - b.getMinX() * z;
        double py = (getHeight() - b.getHeight() * z) / 2 - b.getMinY() * z;
        animateTo(px, py, z);
    }

    public void centerOnWorld(double wx, double wy, boolean animate) {
        double z = zoom();
        double px = getWidth() / 2 - wx * z, py = getHeight() / 2 - wy * z;
        if (animate) animateTo(px, py, z);
        else setView(px, py, z);
    }

    public void centerOn(String elementId) {
        TableNode n = tableNodes.get(elementId);
        if (n != null) {
            centerOnWorld(n.table().x + n.nodeWidth() / 2, n.table().y + n.nodeHeight() / 2, true);
            return;
        }
        NoteNode nn = noteNodes.get(elementId);
        if (nn != null) centerOnWorld(nn.note().x + nn.note().width / 2, nn.note().y + nn.note().height / 2, true);
        AreaNode an = areaNodes.get(elementId);
        if (an != null) centerOnWorld(an.area().x + an.area().width / 2, an.area().y + an.area().height / 2, true);
    }

    /** Area id -> ids of the tables lying completely inside it. */
    public Map<String, List<String>> areaMembers() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Area a : editor.diagram().areas) {
            List<String> ids = new ArrayList<>();
            for (String id : editor.contentsOf(a, this::sizeOf)) if (editor.diagram().table(id) != null) ids.add(id);
            out.put(a.id, ids);
        }
        return out;
    }

    public void startRename(String id) {
        TableNode n = tableNodes.get(id);
        if (n != null) n.startRenameTable();
        AreaNode a = areaNodes.get(id);
        if (a != null) a.startRename();
        NoteNode no = noteNodes.get(id);
        if (no != null) no.startEdit();
    }

    private void animateTo(double px, double py, double z) {
        stopAnimation();
        Interpolator ease = Interpolator.SPLINE(0.25, 0.1, 0.25, 1);
        viewAnimation = new Timeline(new KeyFrame(Duration.millis(260),
                new KeyValue(pan.xProperty(), px, ease),
                new KeyValue(pan.yProperty(), py, ease),
                new KeyValue(scale.xProperty(), z, ease),
                new KeyValue(scale.yProperty(), z, ease)));
        viewAnimation.play();
    }

    private void stopAnimation() {
        if (viewAnimation != null) viewAnimation.stop();
    }

    private static double clamp(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private double snap(double v) {
        return snapToGrid.get() ? Math.round(v / GRID) * GRID : Math.round(v);
    }

    /** Renders the whole diagram (not just the viewport) to an image. */
    public WritableImage exportImage(double imageScale, boolean transparent) {
        Bounds b = contentBounds();
        if (b == null) return null;
        Set<String> sel = new HashSet<>(editor.selection());
        editor.selection().clear();
        applySelection();
        try {
            double pad = 40;
            SnapshotParameters p = new SnapshotParameters();
            p.setFill(transparent ? Color.TRANSPARENT : Theme.canvasBackground());
            p.setTransform(new Scale(imageScale, imageScale));
            p.setViewport(new Rectangle2D((b.getMinX() - pad) * imageScale, (b.getMinY() - pad) * imageScale,
                    (b.getWidth() + 2 * pad) * imageScale, (b.getHeight() + 2 * pad) * imageScale));
            return content.snapshot(p, null);
        } finally {
            editor.selection().addAll(sel);
            applySelection();
        }
    }

    /** Bounds (world) of the given elements, or null. */
    public Bounds boundsOf(Set<String> ids) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        Diagram d = editor.diagram();
        for (String id : ids) {
            double x, y, w, h;
            TableNode tn = tableNodes.get(id);
            if (tn != null) { x = tn.table().x; y = tn.table().y; w = tn.nodeWidth(); h = tn.nodeHeight(); }
            else if (d.note(id) != null) { Note n = d.note(id); x = n.x; y = n.y; w = n.width; h = n.height; }
            else if (d.area(id) != null) { Area a = d.area(id); x = a.x; y = a.y; w = a.width; h = a.height; }
            else continue;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w); maxY = Math.max(maxY, y + h);
        }
        return minX == Double.MAX_VALUE ? null : new BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }

    /** Selection plus everything inside selected areas. */
    public Set<String> selectionWithContents() {
        Set<String> ids = new LinkedHashSet<>(editor.selection());
        for (Area a : editor.selectedAreas()) ids.addAll(editor.contentsOf(a, this::sizeOf));
        return ids;
    }

    /**
     * Renders a world-space rectangle of the diagram (transparent background, no selection highlight).
     * @param only when not null, everything else is hidden (relations only shown between visible tables)
     */
    public WritableImage snapshotRegion(Rectangle2D world, double imageScale, Set<String> only) {
        Set<String> sel = new HashSet<>(editor.selection());
        String hovered = hoveredRelation;
        hoveredRelation = null;
        editor.selection().clear();
        applySelection();
        List<javafx.scene.Node> hidden = new ArrayList<>();
        if (only != null) {
            for (TableNode n : tableNodes.values()) if (!only.contains(n.tableId())) hidden.add(n);
            for (NoteNode n : noteNodes.values()) if (!only.contains(n.note().id)) hidden.add(n);
            for (AreaNode n : areaNodes.values()) if (!only.contains(n.area().id)) hidden.add(n);
            for (RelationEdge e : edges.values()) {
                if (!only.contains(e.relation().fromTableId) || !only.contains(e.relation().toTableId)) hidden.add(e);
            }
        }
        List<javafx.scene.Node> wasVisible = new ArrayList<>();
        for (javafx.scene.Node n : hidden) if (n.isVisible()) { wasVisible.add(n); n.setVisible(false); }
        try {
            SnapshotParameters p = new SnapshotParameters();
            p.setFill(Color.TRANSPARENT);
            p.setTransform(new Scale(imageScale, imageScale));
            p.setViewport(new Rectangle2D(world.getMinX() * imageScale, world.getMinY() * imageScale,
                    world.getWidth() * imageScale, world.getHeight() * imageScale));
            return content.snapshot(p, null);
        } finally {
            for (javafx.scene.Node n : wasVisible) n.setVisible(true);
            hoveredRelation = hovered;
            editor.selection().addAll(sel);
            applySelection();
        }
    }

    public String exportSvg() {
        return SvgExporter.export(this, editor.diagram());
    }

    Iterable<TableNode> tableNodeList() { return tableNodes.values(); }

    Iterable<NoteNode> noteNodeList() { return noteNodes.values(); }

    Iterable<AreaNode> areaNodeList() { return areaNodes.values(); }

    Iterable<RelationEdge> edgeList() { return edges.values(); }

    TableNode tableNode(String id) { return tableNodes.get(id); }

    // ------------------------------------------------------------------ model -> view

    private void sync() {
        Diagram d = editor.diagram();
        Set<String> fk = new HashSet<>();
        for (Relation r : d.relations) fk.add(r.fromColumnId);

        Set<String> alive = new HashSet<>();
        List<javafx.scene.Node> order = new ArrayList<>();
        for (Table t : d.tables) {
            alive.add(t.id);
            TableNode n = tableNodes.computeIfAbsent(t.id, id -> createTableNode(t));
            n.bind(t, fk);
            order.add(n);
        }
        tableNodes.keySet().retainAll(alive);
        if (!tablesLayer.getChildren().equals(order)) {
            // keep the user's z-order for existing nodes, just add/remove
            tablesLayer.getChildren().retainAll(order);
            for (javafx.scene.Node n : order) if (!tablesLayer.getChildren().contains(n)) tablesLayer.getChildren().add(n);
        }

        alive.clear();
        List<javafx.scene.Node> areas = new ArrayList<>();
        for (Area a : d.areas) {
            alive.add(a.id);
            AreaNode n = areaNodes.computeIfAbsent(a.id, id -> createAreaNode(a));
            n.bind(a);
            areas.add(n);
        }
        areaNodes.keySet().retainAll(alive);
        areasLayer.getChildren().setAll(areas);

        alive.clear();
        List<javafx.scene.Node> notes = new ArrayList<>();
        for (Note note : d.notes) {
            alive.add(note.id);
            NoteNode n = noteNodes.computeIfAbsent(note.id, id -> createNoteNode(note));
            n.bind(note);
            notes.add(n);
        }
        noteNodes.keySet().retainAll(alive);
        notesLayer.getChildren().retainAll(notes);
        for (javafx.scene.Node n : notes) if (!notesLayer.getChildren().contains(n)) notesLayer.getChildren().add(n);

        alive.clear();
        List<javafx.scene.Node> edgeOrder = new ArrayList<>();
        for (Relation r : d.relations) {
            if (!tableNodes.containsKey(r.fromTableId) || !tableNodes.containsKey(r.toTableId)) continue;
            alive.add(r.id);
            RelationEdge e = edges.computeIfAbsent(r.id, id -> createEdge(r));
            e.bind(r);
            edgeOrder.add(e);
        }
        edges.keySet().retainAll(alive);
        edgesLayer.getChildren().setAll(edgeOrder);
        updateEdges();
        applySelection();
        fireView();
    }

    private void updateEdges() {
        List<Rectangle2D> obstacles = new ArrayList<>();
        for (TableNode n : tableNodes.values()) {
            obstacles.add(new Rectangle2D(n.table().x, n.table().y, n.nodeWidth(), n.nodeHeight()));
        }
        Map<String, List<RelationEdge>> pairs = new HashMap<>();
        for (RelationEdge e : edges.values()) {
            Relation r = e.relation();
            String a = r.fromTableId, b = r.toTableId;
            String key = a.compareTo(b) < 0 ? a + b : b + a;
            pairs.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        for (List<RelationEdge> group : pairs.values()) {
            for (int i = 0; i < group.size(); i++) {
                RelationEdge e = group.get(i);
                double lane = group.size() == 1 ? 0 : (i - (group.size() - 1) / 2.0) * 14;
                TableNode f = tableNodes.get(e.relation().fromTableId), t = tableNodes.get(e.relation().toTableId);
                if (f != null && t != null) e.update(f, t, lane, obstacles);
            }
        }
    }

    private void applySelection() {
        Set<String> sel = editor.selection();
        Set<String> selectedTables = new HashSet<>();
        for (String id : sel) if (tableNodes.containsKey(id)) selectedTables.add(id);
        Set<String> highlightColumns = new HashSet<>();
        boolean anyTable = !selectedTables.isEmpty();

        for (RelationEdge e : edges.values()) {
            Relation r = e.relation();
            boolean selected = sel.contains(r.id);
            boolean related = selectedTables.contains(r.fromTableId) || selectedTables.contains(r.toTableId);
            boolean hovered = r.id.equals(hoveredRelation);
            e.setSelected(selected);
            e.setHighlighted(related || hovered);
            e.setDimmed(anyTable && !related && !selected && !hovered);
            if (selected || hovered) {
                highlightColumns.add(r.fromColumnId);
                highlightColumns.add(r.toColumnId);
            }
            if (selected || related || hovered) e.toFront();
        }
        for (TableNode n : tableNodes.values()) {
            n.setSelected(sel.contains(n.tableId()));
            n.highlightColumns(highlightColumns);
        }
        for (NoteNode n : noteNodes.values()) n.setSelected(sel.contains(n.note().id));
        for (AreaNode n : areaNodes.values()) n.setSelected(sel.contains(n.area().id));
    }

    private void viewChanged() {
        drawGrid();
        fireView();
    }

    private void fireView() {
        for (Runnable r : viewListeners) r.run();
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        clip.setWidth(w);
        clip.setHeight(h);
        if (grid.getWidth() != w || grid.getHeight() != h) {
            grid.setWidth(w);
            grid.setHeight(h);
            drawGrid();
            fireView();
        }
    }

    private void drawGrid() {
        GraphicsContext g = grid.getGraphicsContext2D();
        double w = grid.getWidth(), h = grid.getHeight();
        g.setFill(Theme.canvasBackground());
        g.fillRect(0, 0, w, h);
        if (!showGrid.get()) return;
        double z = zoom();
        double step = GRID * z;
        while (step < 12) step *= 2;
        double r = Math.max(0.8, Math.min(1.6, 1.2 * z));
        double ox = ((pan.getX() % step) + step) % step, oy = ((pan.getY() % step) + step) % step;
        g.setFill(Theme.gridDot());
        for (double x = ox; x < w; x += step) {
            for (double y = oy; y < h; y += step) g.fillOval(x - r, y - r, r * 2, r * 2);
        }
    }

    // ------------------------------------------------------------------ node factories

    private TableNode createTableNode(Table t) {
        TableNode n = new TableNode(t, this);
        n.setOnMousePressed(e -> elementPressed(e, n.tableId()));
        n.setOnMouseDragged(this::elementDragged);
        n.setOnMouseReleased(this::elementReleased);
        n.setOnContextMenuRequested(e -> {
            if (consumeSuppressedMenu()) { e.consume(); return; }
            if (!editor.isSelected(n.tableId())) editor.select(n.tableId(), false);
            showTableMenu(n, null, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return n;
    }

    private NoteNode createNoteNode(Note note) {
        NoteNode n = new NoteNode(note, this);
        n.setOnMousePressed(e -> elementPressed(e, n.note().id));
        n.setOnMouseDragged(this::elementDragged);
        n.setOnMouseReleased(this::elementReleased);
        n.setOnContextMenuRequested(e -> {
            if (consumeSuppressedMenu()) { e.consume(); return; }
            if (!editor.isSelected(n.note().id)) editor.select(n.note().id, false);
            showNoteMenu(n, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return n;
    }

    private AreaNode createAreaNode(Area a) {
        AreaNode n = new AreaNode(a, this);
        n.handle().setOnMousePressed(e -> elementPressed(e, n.area().id));
        n.handle().setOnMouseDragged(this::elementDragged);
        n.handle().setOnMouseReleased(this::elementReleased);
        n.handle().setOnContextMenuRequested(e -> {
            if (consumeSuppressedMenu()) { e.consume(); return; }
            if (!editor.isSelected(n.area().id)) editor.select(n.area().id, false);
            showAreaMenu(n, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return n;
    }

    private RelationEdge createEdge(Relation r) {
        RelationEdge e = new RelationEdge(r);
        e.setOnMousePressed(ev -> {
            requestFocus();
            menu.hide();
            if (ev.getButton() != MouseButton.PRIMARY) { startPan(ev); ev.consume(); return; }
            if (ev.getButton() == MouseButton.PRIMARY) {
                if (ev.isShiftDown() || ev.isShortcutDown()) editor.toggleSelection(e.relation().id);
                else editor.select(e.relation().id, false);
            }
            ev.consume();
        });
        e.setOnMouseEntered(ev -> { hoveredRelation = e.relation().id; applySelection(); });
        e.setOnMouseExited(ev -> { hoveredRelation = null; applySelection(); });
        e.setOnContextMenuRequested(ev -> {
            if (consumeSuppressedMenu()) { ev.consume(); return; }
            editor.select(e.relation().id, false);
            showEdgeMenu(e.relation(), ev.getScreenX(), ev.getScreenY());
            ev.consume();
        });
        e.setCursor(Cursor.HAND);
        return e;
    }

    // ------------------------------------------------------------------ element dragging

    private enum Drag { NONE, ELEMENT, MARQUEE, CONNECT }

    private Drag drag = Drag.NONE;
    private Point2D pressWorld = Point2D.ZERO;
    private Point2D lastLocal = Point2D.ZERO;
    private Point2D marqueeStartWorld;
    private boolean marqueeAdditive;
    private boolean suppressContextMenu;
    private javafx.animation.AnimationTimer edgeScroller;

    private Point2D local(MouseEvent e) {
        return sceneToLocal(e.getSceneX(), e.getSceneY());
    }

    /** Context menus are skipped right after a right-button pan. */
    private boolean consumeSuppressedMenu() {
        if (!suppressContextMenu) return false;
        suppressContextMenu = false;
        return true;
    }

    private void elementPressed(MouseEvent e, String id) {
        requestFocus();
        menu.hide();
        if (spaceDown || e.getButton() == MouseButton.MIDDLE || e.getButton() == MouseButton.SECONDARY) {
            startPan(e);
            e.consume();
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) { e.consume(); return; }
        if (e.isShiftDown() || e.isShortcutDown()) {
            editor.toggleSelection(id);
        } else if (!editor.isSelected(id)) {
            editor.select(id, false);
        }
        TableNode tn = tableNodes.get(id);
        if (tn != null) tn.toFront();
        NoteNode nn = noteNodes.get(id);
        if (nn != null) nn.toFront();

        dragStart.clear();
        Diagram dg = editor.diagram();
        Set<String> moving = new LinkedHashSet<>(editor.selection());
        for (Area a : editor.selectedAreas()) moving.addAll(editor.contentsOf(a, this::sizeOf));
        for (String mid : moving) {
            Table t = dg.table(mid);
            if (t != null) { dragStart.put(t.id, new double[]{t.x, t.y}); continue; }
            Note n = dg.note(mid);
            if (n != null) { dragStart.put(n.id, new double[]{n.x, n.y}); continue; }
            Area a = dg.area(mid);
            if (a != null) dragStart.put(a.id, new double[]{a.x, a.y});
        }
        dragPrimary = editor.isSelected(id) ? id : null;
        lastLocal = local(e);
        pressWorld = screenToWorld(lastLocal.getX(), lastLocal.getY());
        moved = false;
        drag = dragPrimary == null ? Drag.NONE : Drag.ELEMENT;
        e.consume();
    }

    private void elementDragged(MouseEvent e) {
        if (panning) { panDrag(e); e.consume(); return; }
        if (drag != Drag.ELEMENT || !e.isPrimaryButtonDown()) return;
        lastLocal = local(e);
        Point2D w = screenToWorld(lastLocal.getX(), lastLocal.getY());
        if (!moved && w.distance(pressWorld) * zoom() < 3) return;
        if (!moved) { moved = true; startEdgeScroll(); }
        applyElementDrag();
        e.consume();
    }

    /** Moves the dragged elements so they follow the pointer (also called while auto-scrolling). */
    private void applyElementDrag() {
        double[] p = dragStart.get(dragPrimary);
        if (p == null) return;
        Point2D w = screenToWorld(lastLocal.getX(), lastLocal.getY());
        double dx = w.getX() - pressWorld.getX(), dy = w.getY() - pressWorld.getY();
        double sdx = snap(p[0] + dx) - p[0], sdy = snap(p[1] + dy) - p[1];
        Diagram d = editor.diagram();
        for (Map.Entry<String, double[]> en : dragStart.entrySet()) {
            Table t = d.table(en.getKey());
            if (t != null) {
                t.x = en.getValue()[0] + sdx;
                t.y = en.getValue()[1] + sdy;
                tableNodes.get(t.id).relocate(t.x, t.y);
                continue;
            }
            Note n = d.note(en.getKey());
            if (n != null) {
                n.x = en.getValue()[0] + sdx;
                n.y = en.getValue()[1] + sdy;
                noteNodes.get(n.id).relocate(n.x, n.y);
                continue;
            }
            Area a = d.area(en.getKey());
            if (a != null) {
                a.x = en.getValue()[0] + sdx;
                a.y = en.getValue()[1] + sdy;
                areaNodes.get(a.id).relocate(a.x, a.y);
            }
        }
        updateEdges();
        fireView();
    }

    private void elementReleased(MouseEvent e) {
        stopEdgeScroll();
        if (panning) { endPan(); e.consume(); return; }
        if (moved) editor.commit(this);
        moved = false;
        dragPrimary = null;
        drag = Drag.NONE;
        e.consume();
    }

    // ------------------------------------------------------------------ edge auto-scroll

    private void startEdgeScroll() {
        if (edgeScroller != null) return;
        edgeScroller = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                double vx = edgeSpeed(lastLocal.getX(), getWidth()), vy = edgeSpeed(lastLocal.getY(), getHeight());
                if (vx == 0 && vy == 0) return;
                stopAnimation();
                pan.setX(pan.getX() - vx);
                pan.setY(pan.getY() - vy);
                switch (drag) {
                    case ELEMENT -> applyElementDrag();
                    case MARQUEE -> updateMarquee();
                    case CONNECT -> connectDragAt(lastLocal);
                    default -> { }
                }
            }
        };
        edgeScroller.start();
    }

    private void stopEdgeScroll() {
        if (edgeScroller != null) edgeScroller.stop();
        edgeScroller = null;
    }

    /** Pixels per frame: 0 in the middle, faster the closer the pointer is to (or beyond) an edge. */
    private static double edgeSpeed(double pos, double size) {
        double margin = 48, max = 22;
        if (pos < margin) return -max * Math.min(1, Math.pow((margin - pos) / margin, 2) + (pos < 0 ? 0.3 : 0));
        if (pos > size - margin) return max * Math.min(1, Math.pow((pos - (size - margin)) / margin, 2) + (pos > size ? 0.3 : 0));
        return 0;
    }

    // ------------------------------------------------------------------ background mouse

    private void installMouse() {
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (!isBackground(e)) return;
            requestFocus();
            menu.hide();
            lastLocal = local(e);
            pressX = lastLocal.getX();
            pressY = lastLocal.getY();
            moved = false;
            if (e.getButton() == MouseButton.MIDDLE || e.getButton() == MouseButton.SECONDARY
                    || (e.getButton() == MouseButton.PRIMARY && spaceDown)) {
                startPan(e);
            } else if (e.getButton() == MouseButton.PRIMARY) {
                drag = Drag.MARQUEE;
                marqueeStartWorld = screenToWorld(pressX, pressY);
                marqueeAdditive = e.isShortcutDown() || e.isShiftDown();
                marquee.setWidth(0);
                marquee.setHeight(0);
            }
        });
        addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            Point2D l = local(e);
            if (Math.hypot(l.getX() - pressX, l.getY() - pressY) > 3) moved = true;
            if (panning) {
                panDrag(e);
            } else if (drag == Drag.MARQUEE) {
                lastLocal = l;
                if (moved && !marquee.isVisible()) { marquee.setVisible(true); startEdgeScroll(); }
                if (moved) updateMarquee();
            }
        });
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (!isBackground(e) && !panning && drag != Drag.MARQUEE) return;
            stopEdgeScroll();
            boolean wasPanning = panning;
            if (panning) endPan();
            if (drag == Drag.MARQUEE) {
                drag = Drag.NONE;
                if (moved) finishMarquee(marqueeAdditive);
                else if (!marqueeAdditive && e.getClickCount() == 1) editor.clearSelection();
                marquee.setVisible(false);
                marqueeStartWorld = null;
            } else if (wasPanning && !moved && e.getButton() == MouseButton.SECONDARY && isBackground(e)) {
                showCanvasMenu(e.getScreenX(), e.getScreenY(), screenToWorld(e.getX(), e.getY()));
            }
        });
        addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (isBackground(e) && e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !moved) {
                Point2D w = screenToWorld(e.getX(), e.getY());
                Table t = editor.addTable(snap(w.getX() - 100), snap(w.getY() - 20));
                javafx.application.Platform.runLater(() -> startRename(t.id));
            }
        });

        addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        addEventHandler(ZoomEvent.ZOOM, e -> {
            zoomAt(e.getZoomFactor(), e.getX(), e.getY());
            e.consume();
        });
    }

    // ------------------------------------------------------------------ wheel / touchpad

    private long lastScrollNanos;
    private boolean burstIsTouchpad;

    /**
     * Mouse wheels move in whole "notches" (a fixed delta, no horizontal part); touchpads send small,
     * fractional and often diagonal deltas, sometimes with inertia. A burst of scroll events is classified
     * once (and switched to touchpad as soon as anything touchpad-like shows up), so one gesture never mixes.
     */
    private boolean looksLikeTouchpad(ScrollEvent e) {
        if (e.getTouchCount() > 0 || e.isInertia()) return true;
        if (e.getDeltaX() != 0) return true;
        double mult = e.getMultiplierY() > 0 ? e.getMultiplierY() : 40;
        double notches = Math.abs(e.getDeltaY()) / mult;
        return notches < 0.99 || Math.abs(notches - Math.round(notches)) > 0.02;
    }

    private void onScroll(ScrollEvent e) {
        long now = System.nanoTime();
        boolean newBurst = now - lastScrollNanos > 220_000_000L;
        lastScrollNanos = now;
        boolean touchy = looksLikeTouchpad(e);
        if (newBurst) burstIsTouchpad = touchy;
        else if (touchy) burstIsTouchpad = true;

        com.erdstudio.editor.Settings.WheelMode mode = com.erdstudio.editor.Settings.wheelMode();
        double dx = e.getDeltaX(), dy = e.getDeltaY();
        double mult = e.getMultiplierY() > 0 ? e.getMultiplierY() : 40;
        if (e.isShortcutDown()) {
            // pinch on precision touchpads arrives as Ctrl+wheel: always zoom for those
            if (burstIsTouchpad || mode == com.erdstudio.editor.Settings.WheelMode.PAN) {
                zoomAt(Math.pow(1.006, dy), e.getX(), e.getY());
            } else {
                panBy(0, dy);
            }
        } else if (e.isShiftDown()) {
            panBy(dx != 0 ? dx : dy, 0);
        } else {
            boolean zoom = mode == com.erdstudio.editor.Settings.WheelMode.ZOOM
                    || (mode == com.erdstudio.editor.Settings.WheelMode.AUTO && !burstIsTouchpad);
            if (zoom) {
                double notches = Math.max(-4, Math.min(4, dy / mult));
                zoomAt(Math.pow(1.12, notches), e.getX(), e.getY());
            } else {
                panBy(dx, dy);
            }
        }
        e.consume();
    }

    private void panBy(double dx, double dy) {
        stopAnimation();
        pan.setX(pan.getX() + dx);
        pan.setY(pan.getY() + dy);
    }

    private boolean isBackground(MouseEvent e) {
        return e.getTarget() == this || e.getTarget() == grid;
    }

    private void startPan(MouseEvent e) {
        stopAnimation();
        panning = true;
        Point2D p = local(e);
        lastX = p.getX();
        lastY = p.getY();
        pressX = p.getX();
        pressY = p.getY();
        moved = false;
        suppressContextMenu = false;
        setCursor(Cursor.CLOSED_HAND);
    }

    private void panDrag(MouseEvent e) {
        Point2D p = local(e);
        if (Math.hypot(p.getX() - pressX, p.getY() - pressY) > 3) {
            moved = true;
            if (e.isSecondaryButtonDown()) suppressContextMenu = true;
        }
        pan.setX(pan.getX() + p.getX() - lastX);
        pan.setY(pan.getY() + p.getY() - lastY);
        lastX = p.getX();
        lastY = p.getY();
    }

    private void endPan() {
        panning = false;
        setCursor(spaceDown ? Cursor.OPEN_HAND : Cursor.DEFAULT);
    }

    private void updateMarquee() {
        if (marqueeStartWorld == null) return;
        double z = zoom();
        double sx = pan.getX() + marqueeStartWorld.getX() * z, sy = pan.getY() + marqueeStartWorld.getY() * z;
        double ex = Math.max(-2, Math.min(getWidth() + 2, lastLocal.getX()));
        double ey = Math.max(-2, Math.min(getHeight() + 2, lastLocal.getY()));
        marquee.setX(Math.min(sx, ex));
        marquee.setY(Math.min(sy, ey));
        marquee.setWidth(Math.abs(ex - sx));
        marquee.setHeight(Math.abs(ey - sy));
    }

    private void finishMarquee(boolean additive) {
        Point2D a = screenToWorld(marquee.getX(), marquee.getY());
        Point2D b = screenToWorld(marquee.getX() + marquee.getWidth(), marquee.getY() + marquee.getHeight());
        Rectangle2D box = new Rectangle2D(a.getX(), a.getY(), b.getX() - a.getX(), b.getY() - a.getY());
        Set<String> ids = new LinkedHashSet<>(additive ? editor.selection() : Set.of());
        for (TableNode n : tableNodes.values()) {
            Table t = n.table();
            if (box.intersects(t.x, t.y, n.nodeWidth(), n.nodeHeight())) ids.add(t.id);
        }
        for (NoteNode n : noteNodes.values()) {
            Note t = n.note();
            if (box.intersects(t.x, t.y, t.width, t.height)) ids.add(t.id);
        }
        for (AreaNode n : areaNodes.values()) {
            Area ar = n.area();
            if (box.contains(ar.x, ar.y, ar.width, ar.height)) ids.add(ar.id);
        }
        editor.setSelection(ids);
    }

    private void installKeys() {
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (isTextInputFocused()) return;
            KeyCode k = e.getCode();
            if (k == KeyCode.SPACE) {
                if (!spaceDown) { spaceDown = true; if (!panning) setCursor(Cursor.OPEN_HAND); }
                e.consume();
            } else if (k == KeyCode.DELETE || k == KeyCode.BACK_SPACE) {
                editor.deleteSelection();
                e.consume();
            } else if (k == KeyCode.ESCAPE) {
                editor.clearSelection();
                e.consume();
            } else if (k.isArrowKey() && !editor.selection().isEmpty()) {
                double step = e.isShiftDown() ? GRID : 1;
                double dx = k == KeyCode.LEFT ? -step : k == KeyCode.RIGHT ? step : 0;
                double dy = k == KeyCode.UP ? -step : k == KeyCode.DOWN ? step : 0;
                for (Table t : editor.selectedTables()) { t.x += dx; t.y += dy; }
                for (Note n : editor.selectedNotes()) { n.x += dx; n.y += dy; }
                for (Area a : editor.selectedAreas()) { a.x += dx; a.y += dy; }
                editor.commit(this);
                e.consume();
            } else if (k == KeyCode.ENTER || k == KeyCode.F2) {
                Object o = editor.singleSelection();
                if (o instanceof Table t) startRename(t.id);
                if (o instanceof Note n && noteNodes.containsKey(n.id)) noteNodes.get(n.id).startEdit();
                if (o instanceof Area a) startRename(a.id);
                e.consume();
            } else if (k == KeyCode.T && !e.isShortcutDown()) {
                Point2D p = newElementPosition(TableNode.MIN_WIDTH, 80);
                Table t = editor.addTable(p.getX(), p.getY());
                javafx.application.Platform.runLater(() -> startRename(t.id));
                e.consume();
            } else if (k == KeyCode.N && !e.isShortcutDown()) {
                Point2D p = newElementPosition(220, 130);
                editor.addNote(p.getX(), p.getY());
                e.consume();
            }
        });
        addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.SPACE) {
                spaceDown = false;
                if (!panning) setCursor(Cursor.DEFAULT);
            }
        });
        focusedProperty().addListener((o, a, b) -> { if (!b) { spaceDown = false; if (!panning) setCursor(Cursor.DEFAULT); } });
    }

    private boolean isTextInputFocused() {
        return getScene() != null && getScene().getFocusOwner() instanceof javafx.scene.control.TextInputControl;
    }

    // ------------------------------------------------------------------ TableNode hooks

    @Override
    public void connectStart(TableNode node, Column column, MouseEvent e) {
        requestFocus();
        connectFrom = node;
        connectColumn = column;
        drag = Drag.CONNECT;
        overlay.getChildren().setAll(connectLine, connectDot);
        connectDrag(e);
        startEdgeScroll();
    }

    @Override
    public void connectDrag(MouseEvent e) {
        if (connectFrom == null) return;
        lastLocal = local(e);
        connectDragAt(lastLocal);
    }

    private void connectDragAt(Point2D p) {
        if (connectFrom == null) return;
        Point2D w = screenToWorld(p.getX(), p.getY());
        Table t = connectFrom.table();
        int idx = t.columns.indexOf(connectColumn);
        double y = connectFrom.rowCenterY(idx);
        boolean right = w.getX() > t.x + connectFrom.nodeWidth() / 2;
        double x = right ? t.x + connectFrom.nodeWidth() : t.x;
        Routing.Anchor a = new Routing.Anchor(x, y, right ? 1 : -1);
        Routing.Anchor b = new Routing.Anchor(w.getX(), w.getY(), right ? -1 : 1);
        connectLine.setContent(Routing.path(Routing.route(a, b, 0), 10));
        connectDot.setCenterX(w.getX());
        connectDot.setCenterY(w.getY());

        TableNode target = nodeAt(w.getX(), w.getY());
        if (connectHover != null && connectHover != target) connectHover.highlightColumns(Set.of());
        connectHover = target;
        if (target != null) {
            Column c = targetColumn(target, w);
            target.highlightColumns(c == null ? Set.of() : Set.of(c.id));
        }
    }

    @Override
    public void connectEnd(MouseEvent e) {
        stopEdgeScroll();
        drag = Drag.NONE;
        if (connectFrom == null) return;
        overlay.getChildren().clear();
        Point2D p = sceneToLocal(e.getSceneX(), e.getSceneY());
        Point2D w = screenToWorld(p.getX(), p.getY());
        TableNode target = nodeAt(w.getX(), w.getY());
        if (connectHover != null) connectHover.highlightColumns(Set.of());
        connectHover = null;
        TableNode from = connectFrom;
        Column fromCol = connectColumn;
        connectFrom = null;
        connectColumn = null;
        if (target == null) { applySelection(); return; }
        Column c = targetColumn(target, w);
        if (c != null) editor.connect(from.table(), fromCol, target.table(), c);
        else applySelection();
    }

    /** Column under the pointer; dropping on the header picks the (single) primary key. */
    private Column targetColumn(TableNode target, Point2D w) {
        Column c = target.columnAt(w.getX(), w.getY());
        if (c == null) {
            List<Column> pk = target.table().primaryKey();
            if (pk.size() == 1) c = pk.get(0);
        }
        if (c == connectColumn) return null;
        return c;
    }

    private TableNode nodeAt(double wx, double wy) {
        List<javafx.scene.Node> kids = tablesLayer.getChildren();
        for (int i = kids.size() - 1; i >= 0; i--) {
            if (kids.get(i) instanceof TableNode n && n.containsWorld(wx, wy)) return n;
        }
        return null;
    }

    @Override
    public void renameTable(TableNode node, String newName) {
        Table t = node.table();
        Table clash = editor.diagram().tableByName(newName);
        t.name = clash != null && clash != t ? editor.diagram().uniqueTableName(newName) : newName;
        editor.commit(this);
        requestFocus();
    }

    @Override
    public void renameColumn(TableNode node, Column column, String newName) {
        column.name = newName;
        editor.commit(this);
        requestFocus();
    }

    @Override
    public void rowContextMenu(TableNode node, Column column, double sx, double sy) {
        if (consumeSuppressedMenu()) return;
        if (!editor.isSelected(node.tableId())) editor.select(node.tableId(), false);
        showTableMenu(node, column, sx, sy);
    }

    // ------------------------------------------------------------------ NoteNode hooks

    @Override
    public void noteText(NoteNode node, String text) {
        node.note().text = text;
        editor.commit(this);
        requestFocus();
    }

    @Override
    public void noteResize(NoteNode node, double w, double h, boolean finished) {
        node.note().width = w;
        node.note().height = h;
        node.bind(node.note());
        if (finished) editor.commit(this);
        else fireView();
    }

    // ------------------------------------------------------------------ AreaNode hooks

    @Override
    public void areaRename(AreaNode node, String name) {
        node.area().name = name;
        editor.commit(this);
        requestFocus();
    }

    @Override
    public void areaResize(AreaNode node, double w, double h, boolean finished) {
        node.area().width = w;
        node.area().height = h;
        node.bind(node.area());
        if (finished) editor.commit(this);
        else fireView();
    }

    /** Tidy up with a smooth animation from the old positions to the new ones, then fit the view. */
    public void tidyAnimated() {
        Map<String, double[]> before = positions();
        editor.tidy(this::sizeOf);
        Map<String, double[]> after = positions();
        fitToContent();
        animatePositions(before, after);
    }

    private Map<String, double[]> positions() {
        Map<String, double[]> m = new HashMap<>();
        Diagram d = editor.diagram();
        for (Table t : d.tables) m.put(t.id, new double[]{t.x, t.y, 0, 0});
        for (Note n : d.notes) m.put(n.id, new double[]{n.x, n.y, n.width, n.height});
        for (Area a : d.areas) m.put(a.id, new double[]{a.x, a.y, a.width, a.height});
        return m;
    }

    private javafx.animation.AnimationTimer layoutAnim;

    private void animatePositions(Map<String, double[]> from, Map<String, double[]> to) {
        if (layoutAnim != null) layoutAnim.stop();
        final long start = System.nanoTime();
        final double dur = 560e6;
        layoutAnim = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = Math.min(1, (now - start) / dur);
                double k = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; // ease in-out cubic
                Diagram d = editor.diagram();
                for (Map.Entry<String, double[]> e : to.entrySet()) {
                    double[] a = from.getOrDefault(e.getKey(), e.getValue()), b = e.getValue();
                    double x = a[0] + (b[0] - a[0]) * k, y = a[1] + (b[1] - a[1]) * k;
                    Table tb = d.table(e.getKey());
                    if (tb != null) { tb.x = x; tb.y = y; TableNode n = tableNodes.get(tb.id); if (n != null) n.relocate(x, y); continue; }
                    Note no = d.note(e.getKey());
                    if (no != null) { no.x = x; no.y = y; NoteNode n = noteNodes.get(no.id); if (n != null) n.relocate(x, y); continue; }
                    Area ar = d.area(e.getKey());
                    if (ar != null) {
                        ar.x = x; ar.y = y;
                        ar.width = a[2] + (b[2] - a[2]) * k;
                        ar.height = a[3] + (b[3] - a[3]) * k;
                        AreaNode n = areaNodes.get(ar.id);
                        if (n != null) n.bind(ar);
                    }
                }
                updateEdges();
                fireView();
                if (t >= 1) {
                    stop();
                    sync();
                }
            }
        };
        layoutAnim.start();
    }

    /** Ctrl+G: wrap the selection in a new area and start renaming it. */
    public void groupSelection() {
        Area a = editor.groupSelection(this::sizeOf);
        if (a != null) javafx.application.Platform.runLater(() -> startRename(a.id));
    }

    // ------------------------------------------------------------------ context menus

    private void showCanvasMenu(double sx, double sy, Point2D world) {
        MenuItem addTable = item("Add table here", () -> {
            Table t = editor.addTable(snap(world.getX()), snap(world.getY()));
            javafx.application.Platform.runLater(() -> startRename(t.id));
        });
        MenuItem addNote = item("Add note here", () -> editor.addNote(snap(world.getX()), snap(world.getY())));
        MenuItem addArea = item("Add area here", () -> {
            Area a = editor.addArea(snap(world.getX()), snap(world.getY()), 480, 320);
            javafx.application.Platform.runLater(() -> startRename(a.id));
        });
        MenuItem paste = item("Paste", editor::paste);
        paste.setDisable(!editor.hasClipboard());
        MenuItem layout = item("Tidy up (arrange everything)", this::tidyAnimated);
        MenuItem fit = item("Fit diagram to screen", this::fitToContent);
        MenuItem all = item("Select all", editor::selectAll);
        menu.getItems().setAll(addTable, addNote, addArea, paste, new SeparatorMenuItem(), layout, fit, all);
        menu.show(this, sx, sy);
    }

    private void showTableMenu(TableNode node, Column column, double sx, double sy) {
        Table t = node.table();
        List<MenuItem> items = new ArrayList<>();
        if (column != null) {
            items.add(item("Rename column", () -> node.startRenameColumn(column)));
            items.add(item(column.primaryKey ? "Remove from primary key" : "Make primary key", () -> {
                column.primaryKey = !column.primaryKey;
                if (column.primaryKey) column.notNull = true;
                editor.commit(this);
            }));
            items.add(item(column.notNull ? "Allow NULL" : "Set NOT NULL", () -> {
                column.notNull = !column.notNull;
                editor.commit(this);
            }));
            items.add(item(column.unique ? "Remove UNIQUE" : "Set UNIQUE", () -> {
                column.unique = !column.unique;
                editor.commit(this);
            }));
            int idx = t.columns.indexOf(column);
            MenuItem up = item("Move up", () -> { t.columns.remove(column); t.columns.add(idx - 1, column); editor.commit(this); });
            up.setDisable(idx <= 0);
            MenuItem down = item("Move down", () -> { t.columns.remove(column); t.columns.add(idx + 1, column); editor.commit(this); });
            down.setDisable(idx >= t.columns.size() - 1);
            items.add(up);
            items.add(down);
            items.add(item("Delete column", () -> editor.deleteColumn(t, column)));
            items.add(new SeparatorMenuItem());
        }
        items.add(item("Rename table", () -> node.startRenameTable()));
        items.add(item("Add column", () -> {
            Column c = editor.addColumn(t);
            javafx.application.Platform.runLater(() -> {
                TableNode n = tableNodes.get(t.id);
                if (n != null) n.startRenameColumn(c);
            });
        }));
        Menu color = new Menu("Color");
        for (String hex : Palette.TABLE_COLORS) {
            MenuItem mi = new MenuItem(hex);
            Circle dot = new Circle(6, Color.web(hex));
            mi.setGraphic(dot);
            mi.setOnAction(e -> {
                for (Table st : editor.selectedTables()) st.color = hex;
                editor.commit(this);
            });
            color.getItems().add(mi);
        }
        items.add(color);
        items.add(item("Duplicate", editor::duplicateSelection));
        items.add(item("Copy", editor::copySelection));
        items.add(item("Group into area", this::groupSelection));
        if (editor.selectedTables().size() > 1) {
            items.add(item("Arrange selected tables", () -> editor.autoLayout(this::sizeOf, true)));
        }
        items.add(new SeparatorMenuItem());
        MenuItem del = item(editor.selection().size() > 1 ? "Delete selected" : "Delete table", editor::deleteSelection);
        del.getStyleClass().add("danger-item");
        items.add(del);
        menu.getItems().setAll(items);
        menu.show(this, sx, sy);
    }

    private void showNoteMenu(NoteNode node, double sx, double sy) {
        Menu color = new Menu("Color");
        for (String hex : Palette.NOTE_COLORS) {
            MenuItem mi = new MenuItem(hex);
            mi.setGraphic(new Circle(6, Color.web(hex)));
            mi.setOnAction(e -> { node.note().color = hex; editor.commit(this); });
            color.getItems().add(mi);
        }
        MenuItem edit = item("Edit text", node::startEdit);
        MenuItem dup = item("Duplicate", editor::duplicateSelection);
        MenuItem del = item("Delete note", editor::deleteSelection);
        del.getStyleClass().add("danger-item");
        menu.getItems().setAll(edit, color, dup, new SeparatorMenuItem(), del);
        menu.show(this, sx, sy);
    }

    private void showAreaMenu(AreaNode node, double sx, double sy) {
        Area a = node.area();
        Menu color = new Menu("Color");
        for (String hex : Palette.AREA_COLORS) {
            MenuItem mi = new MenuItem(hex);
            mi.setGraphic(new Circle(6, Color.web(hex)));
            mi.setOnAction(e -> { a.color = hex; editor.commit(this); });
            color.getItems().add(mi);
        }
        MenuItem rename = item("Rename area", node::startRename);
        MenuItem selectInside = item("Select tables inside", () -> {
            Set<String> ids = new LinkedHashSet<>(editor.contentsOf(a, this::sizeOf));
            editor.setSelection(ids);
        });
        MenuItem fitArea = item("Fit area to its tables", () -> {
            Set<String> ids = editor.contentsOf(a, this::sizeOf);
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (String id : ids) {
                Table t = editor.diagram().table(id);
                if (t == null) continue;
                minX = Math.min(minX, t.x); minY = Math.min(minY, t.y);
                maxX = Math.max(maxX, t.x + sizeOf(t).width()); maxY = Math.max(maxY, t.y + sizeOf(t).height());
            }
            if (minX == Double.MAX_VALUE) return;
            a.x = minX - 30; a.y = minY - 56; a.width = maxX - minX + 60; a.height = maxY - minY + 86;
            editor.commit(this);
        });
        MenuItem del = item("Delete area (keep tables)", editor::deleteSelection);
        MenuItem delAll = item("Delete area and everything in it", () -> {
            Set<String> ids = new LinkedHashSet<>(editor.contentsOf(a, this::sizeOf));
            ids.add(a.id);
            editor.setSelection(ids);
            editor.deleteSelection();
        });
        del.getStyleClass().add("danger-item");
        delAll.getStyleClass().add("danger-item");
        menu.getItems().setAll(rename, color, selectInside, fitArea, new SeparatorMenuItem(), del, delAll);
        menu.show(this, sx, sy);
    }

    private void showEdgeMenu(Relation r, double sx, double sy) {
        ToggleGroup g = new ToggleGroup();
        Menu type = new Menu("Cardinality");
        for (RelationType rt : RelationType.values()) {
            RadioMenuItem mi = new RadioMenuItem(rt.label);
            mi.setToggleGroup(g);
            mi.setSelected(r.type == rt);
            mi.setOnAction(e -> { r.type = rt; editor.commit(this); });
            type.getItems().add(mi);
        }
        MenuItem swap = item("Swap direction", () -> {
            String t = r.fromTableId, c = r.fromColumnId;
            r.fromTableId = r.toTableId;
            r.fromColumnId = r.toColumnId;
            r.toTableId = t;
            r.toColumnId = c;
            editor.commit(this);
        });
        MenuItem del = item("Delete relation", editor::deleteSelection);
        del.getStyleClass().add("danger-item");
        menu.getItems().setAll(type, swap, new SeparatorMenuItem(), del);
        menu.show(this, sx, sy);
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }
}
