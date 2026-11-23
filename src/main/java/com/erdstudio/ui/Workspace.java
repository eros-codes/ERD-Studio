package com.erdstudio.ui;

import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;

import com.erdstudio.canvas.DiagramCanvas;
import com.erdstudio.canvas.Minimap;
import com.erdstudio.editor.Editor;
import com.erdstudio.editor.Settings;
import com.erdstudio.model.Ids;
import com.erdstudio.model.Table;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** One open diagram (one tab): editor + canvas + side panels + floating canvas controls. */
public final class Workspace {

    /** Actions the workspace's own buttons need from the window. */
    public interface Host {
        void importDialog();
        void loadSample();
        BooleanProperty minimapVisible();
        PanelState panels();
    }

    public final String id = Ids.next();
    public final Editor editor = new Editor();
    public final DiagramCanvas canvas = new DiagramCanvas(editor);
    public final Sidebar sidebar;
    public final PropertiesPanel props;
    private final BorderPane root = new BorderPane();
    private final StackPane canvasArea = new StackPane();
    private final HBox toast = new HBox();
    private final Label toastText = new Label();
    private final Button toastAction = new Button();
    private SequentialTransition toastAnim;
    private final VBox emptyState;
    private final Host host;

    public Workspace(Host host) {
        this.host = host;
        canvas.snapToGridProperty().set(Settings.snapToGrid());
        canvas.snapToGridProperty().addListener((o, a, b) -> Settings.setSnapToGrid(b));
        sidebar = new Sidebar(editor, canvas, this::addTableAtCenter);
        props = new PropertiesPanel(editor, canvas);
        emptyState = buildEmptyState();
        PanelState ps = host.panels();
        sidebar.setOnClose(() -> ps.leftOpen.set(false));

        // left: rail (when closed) | panel | divider
        Node leftRail = rail("Explorer", Icons.TABLE, () -> ps.leftOpen.set(true), true);
        Region leftDivider = divider(ps.leftWidth, PanelState.LEFT_MIN, PanelState.LEFT_MAX, 1, ps);
        bindPanel(sidebar, ps.leftWidth, ps.leftOpen);
        bindVisible(leftDivider, ps.leftOpen, true);
        bindVisible(leftRail, ps.leftOpen, false);

        // right: divider | panel (header + content) | rail
        Label pt = new Label("PROPERTIES");
        pt.getStyleClass().add("panel-title");
        Region psp = new Region();
        HBox.setHgrow(psp, javafx.scene.layout.Priority.ALWAYS);
        Button closeRight = panelCloseButton(() -> ps.rightOpen.set(false));
        HBox propsHeader = new HBox(8, pt, psp, closeRight);
        propsHeader.setAlignment(Pos.CENTER_LEFT);
        propsHeader.getStyleClass().add("panel-header");
        VBox right = new VBox(propsHeader, props);
        right.getStyleClass().add("props-wrap");
        VBox.setVgrow(props, javafx.scene.layout.Priority.ALWAYS);
        props.setMinWidth(0);
        props.setPrefWidth(Region.USE_COMPUTED_SIZE);
        Node rightRail = rail("Properties", Icons.SETTINGS, () -> ps.rightOpen.set(true), false);
        Region rightDivider = divider(ps.rightWidth, PanelState.RIGHT_MIN, PanelState.RIGHT_MAX, -1, ps);
        bindPanel(right, ps.rightWidth, ps.rightOpen);
        bindVisible(rightDivider, ps.rightOpen, true);
        bindVisible(rightRail, ps.rightOpen, false);

        Node center = buildCanvasArea();
        HBox.setHgrow(center, javafx.scene.layout.Priority.ALWAYS);
        HBox row = new HBox(leftRail, sidebar, leftDivider, center, rightDivider, right, rightRail);
        root.setCenter(row);
        editor.addListener((c, s) -> emptyState.setVisible(editor.isEmpty()));
        emptyState.setVisible(editor.isEmpty());
    }

    public Node root() { return root; }

    private static void bindPanel(Region panel, javafx.beans.property.DoubleProperty width, BooleanProperty open) {
        panel.minWidthProperty().bind(width);
        panel.prefWidthProperty().bind(width);
        panel.maxWidthProperty().bind(width);
        bindVisible(panel, open, true);
    }

    private static void bindVisible(Node n, BooleanProperty open, boolean whenOpen) {
        if (whenOpen) n.visibleProperty().bind(open);
        else n.visibleProperty().bind(open.not());
        n.managedProperty().bind(n.visibleProperty());
    }

    /** A thin draggable line that resizes a side panel. dir = +1 for the left panel, -1 for the right one. */
    private static Region divider(javafx.beans.property.DoubleProperty width, double min, double max, int dir, PanelState ps) {
        Region d = new Region();
        d.getStyleClass().add("panel-divider");
        d.setMinWidth(6);
        d.setPrefWidth(6);
        d.setMaxWidth(6);
        d.setCursor(javafx.scene.Cursor.H_RESIZE);
        double[] start = new double[2];
        d.setOnMousePressed(e -> { start[0] = e.getScreenX(); start[1] = width.get(); e.consume(); });
        d.setOnMouseDragged(e -> {
            width.set(PanelState.clamp(start[1] + dir * (e.getScreenX() - start[0]), min, max));
            e.consume();
        });
        d.setOnMouseReleased(e -> { ps.saveWidths(); e.consume(); });
        d.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) { width.set(dir > 0 ? 264 : 362); ps.saveWidths(); }
        });
        return d;
    }

    /** Collapsed panel: a slim vertical strip with icon and rotated title; click to expand. */
    private static Node rail(String title, String icon, Runnable open, boolean left) {
        Label l = new Label(title);
        l.getStyleClass().add("rail-label");
        l.setRotate(left ? -90 : 90);
        javafx.scene.Group rotated = new javafx.scene.Group(l);
        VBox box = new VBox(12, Icons.icon(icon, 16), rotated);
        box.setAlignment(Pos.TOP_CENTER);
        box.getStyleClass().addAll("panel-rail", left ? "rail-left" : "rail-right");
        box.setMinWidth(30);
        box.setPrefWidth(30);
        box.setMaxWidth(30);
        box.setOnMouseClicked(e -> open.run());
        box.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(box, new Tooltip("Show " + title + (left ? " (Ctrl+B)" : " (Ctrl+Shift+B)")));
        return box;
    }

    static Button panelCloseButton(Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.icon(Icons.X, 14));
        b.getStyleClass().addAll("icon-button", "small", "panel-close");
        b.setTooltip(new Tooltip("Hide panel"));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    public String title() {
        return editor.diagram().name;
    }

    // ------------------------------------------------------------------ canvas area

    private Node buildCanvasArea() {
        Button addTable = toolButton(Icons.TABLE, "Add table (T)", this::addTableAtCenter);
        Button addNote = toolButton(Icons.NOTE, "Add note (N)", this::addNoteAtCenter);
        Button addArea = toolButton(Icons.AREA, "Add area / group selection (Ctrl+G)", this::addAreaOrGroup);
        Button layout = new Button("Tidy up");
        layout.setGraphic(Icons.icon(Icons.SPARKLES, 16, "accent-icon"));
        layout.getStyleClass().addAll("flat", "tidy-button");
        layout.setTooltip(new Tooltip("Tidy up: neatly re-arrange all tables, keeping areas together (Ctrl+L)"));
        layout.setFocusTraversable(false);
        layout.setOnAction(e -> { autoLayout(); canvas.requestFocus(); });
        MenuButton layoutStyle = new MenuButton();
        layoutStyle.getStyleClass().addAll("flat", "tidy-style");
        layoutStyle.setTooltip(new Tooltip("Tidy up style"));
        layoutStyle.setFocusTraversable(false);
        layoutStyle.setOnShowing(ev -> {
            layoutStyle.getItems().clear();
            ToggleGroup tg = new ToggleGroup();
            for (com.erdstudio.layout.AutoLayout.Style st : com.erdstudio.layout.AutoLayout.Style.values()) {
                RadioMenuItem ri = new RadioMenuItem(st.label);
                ri.setToggleGroup(tg);
                ri.setSelected(st == com.erdstudio.editor.Settings.tidyStyle());
                ri.setOnAction(x -> { com.erdstudio.editor.Settings.setTidyStyle(st); autoLayout(); canvas.requestFocus(); });
                layoutStyle.getItems().add(ri);
            }
        });
        HBox layoutGroup = new HBox(0, layout, layoutStyle);
        layoutGroup.setAlignment(Pos.CENTER);
        Button fit = toolButton(Icons.FIT, "Fit to screen (Shift+1)", canvas::fitToContent);
        Button undo = toolButton(Icons.UNDO, "Undo (Ctrl+Z)", editor::undo);
        Button redo = toolButton(Icons.REDO, "Redo (Ctrl+Y)", editor::redo);
        undo.disableProperty().bind(editor.canUndoProperty().not());
        redo.disableProperty().bind(editor.canRedoProperty().not());
        ToggleButton snap = new ToggleButton();
        snap.setGraphic(Icons.icon(Icons.GRID, 17));
        snap.getStyleClass().add("icon-button");
        snap.setTooltip(new Tooltip("Snap to grid"));
        snap.setFocusTraversable(false);
        snap.selectedProperty().bindBidirectional(canvas.snapToGridProperty());
        ToggleButton mapToggle = new ToggleButton();
        mapToggle.setGraphic(Icons.icon(Icons.MAP, 17));
        mapToggle.getStyleClass().add("icon-button");
        mapToggle.setTooltip(new Tooltip("Minimap"));
        mapToggle.setFocusTraversable(false);
        mapToggle.selectedProperty().bindBidirectional(host.minimapVisible());

        HBox tools = new HBox(addTable, addNote, addArea, vsep(), layoutGroup, fit, vsep(), undo, redo, vsep(), snap, mapToggle);
        tools.getStyleClass().add("floating");
        tools.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(tools, Pos.TOP_CENTER);
        StackPane.setMargin(tools, new Insets(14, 0, 0, 0));

        Button zoomOut = toolButton(Icons.ZOOM_OUT, "Zoom out (Ctrl+-)", canvas::zoomOut);
        Button zoomIn = toolButton(Icons.ZOOM_IN, "Zoom in (Ctrl+=)", canvas::zoomIn);
        Button zoomLabel = new Button();
        zoomLabel.getStyleClass().add("zoom-label");
        zoomLabel.setTooltip(new Tooltip("Reset to 100% (Ctrl+0)"));
        zoomLabel.setFocusTraversable(false);
        zoomLabel.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(canvas.zoomProperty().get() * 100) + "%", canvas.zoomProperty()));
        zoomLabel.setOnAction(e -> canvas.resetZoom());
        HBox zoom = new HBox(zoomOut, zoomLabel, zoomIn);
        zoom.getStyleClass().add("floating");
        zoom.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(zoom, Pos.BOTTOM_LEFT);
        StackPane.setMargin(zoom, new Insets(0, 0, 14, 14));

        Minimap minimap = new Minimap(canvas);
        StackPane.setAlignment(minimap, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(minimap, new Insets(0, 14, 14, 0));
        minimap.visibleProperty().bind(host.minimapVisible());

        toast.getStyleClass().add("toast");
        toast.getChildren().addAll(toastText, toastAction);
        toast.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 24, 0));

        canvasArea.getChildren().addAll(canvas, emptyState, tools, zoom, minimap, toast);
        return canvasArea;
    }

    private VBox buildEmptyState() {
        StackPane icon = new StackPane(Icons.icon(Icons.DATABASE, 30));
        icon.getStyleClass().add("empty-icon");
        Label t = new Label("Design your database");
        t.getStyleClass().add("empty-title");
        Label s = new Label("Double-click anywhere to add a table, import SQL / Prisma / DBML,\n"
                + "or connect to a PostgreSQL or MySQL database.  Ctrl+K opens the command palette.");
        s.getStyleClass().add("empty-sub");
        Button add = new Button("Add table");
        add.getStyleClass().add("primary");
        add.setGraphic(Icons.icon(Icons.PLUS, 15));
        add.setOnAction(e -> addTableAtCenter());
        Button imp = new Button("Import…");
        imp.setGraphic(Icons.icon(Icons.UPLOAD, 15));
        imp.setOnAction(e -> host.importDialog());
        Button sample = new Button("Load sample");
        sample.setGraphic(Icons.icon(Icons.TABLE, 15));
        sample.setOnAction(e -> host.loadSample());
        HBox buttons = new HBox(10, add, imp, sample);
        buttons.setAlignment(Pos.CENTER);
        VBox box = new VBox(12, icon, t, s, buttons);
        box.getStyleClass().add("empty-state");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setPickOnBounds(false);
        VBox.setMargin(buttons, new Insets(8, 0, 0, 0));
        return box;
    }

    private Node vsep() {
        return new Separator(Orientation.VERTICAL);
    }

    private Button toolButton(String icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.icon(icon, 17));
        b.getStyleClass().add("icon-button");
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> { action.run(); canvas.requestFocus(); });
        return b;
    }

    // ------------------------------------------------------------------ actions

    public void addTableAtCenter() {
        Point2D p = canvas.newElementPosition(220, 120);
        Table t = editor.addTable(p.getX(), p.getY());
        Platform.runLater(() -> canvas.startRename(t.id));
    }

    public void addNoteAtCenter() {
        Point2D p = canvas.newElementPosition(220, 130);
        editor.addNote(p.getX(), p.getY());
    }

    /** Groups the selection into an area, or adds an empty area when nothing is selected. */
    public void addAreaOrGroup() {
        if (!editor.selectedTables().isEmpty() || !editor.selectedNotes().isEmpty()) {
            canvas.groupSelection();
            return;
        }
        Point2D p = canvas.newElementPosition(480, 320);
        var a = editor.addArea(p.getX(), p.getY(), 480, 320);
        Platform.runLater(() -> canvas.startRename(a.id));
    }

    /** Tidy up (animated). */
    public void autoLayout() {
        canvas.tidyAnimated();
    }

    public void showToast(String text, String actionText, Runnable action) {
        toastText.setText(text);
        toastAction.setVisible(actionText != null);
        toastAction.setManaged(actionText != null);
        toastAction.setText(actionText == null ? "" : actionText);
        toastAction.setOnAction(e -> { if (action != null) action.run(); });
        toast.setMouseTransparent(actionText == null);
        if (toastAnim != null) toastAnim.stop();
        FadeTransition in = new FadeTransition(Duration.millis(160), toast);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.millis(actionText == null ? 2200 : 6000));
        FadeTransition out = new FadeTransition(Duration.millis(300), toast);
        out.setToValue(0);
        toastAnim = new SequentialTransition(in, hold, out);
        toastAnim.setOnFinished(e -> toast.setMouseTransparent(true));
        toastAnim.play();
    }
}
