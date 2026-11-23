package com.erdstudio.ui;

import com.erdstudio.editor.Editor;
import com.erdstudio.editor.Settings;
import com.erdstudio.io.ImportResult;
import com.erdstudio.io.JsonStore;
import com.erdstudio.io.ProjectLibrary;
import com.erdstudio.io.SqlImporter;
import com.erdstudio.model.Area;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.Table;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Application window: tabs of workspaces, menus, command palette, file actions, autosave and recovery. */
public final class MainWindow implements Workspace.Host, HomeView.Actions {
    private static final int MAX_RECENT = 10;

    private final Stage stage;
    final TabPane tabs = new TabPane();
    final Map<Tab, Workspace> workspaces = new LinkedHashMap<>();
    private final BooleanProperty minimap = new SimpleBooleanProperty(Boolean.parseBoolean(Settings.get("minimap", "true")));
    private final PanelState panels = new PanelState();

    private final Menu recentMenu = new Menu("Open recent");
    private final TextField nameField = new TextField();
    private final ComboBox<Dialect> dialectBox = new ComboBox<>();
    private final Circle dirtyDot = new Circle(3.5);
    private final Label statusLeft = new Label();
    private final Label statusRight = new Label();
    private final List<CommandPalette.Command> commands = new ArrayList<>();
    private final CommandPalette palette = new CommandPalette(this::paletteCommands);
    private final Tab homeTab = new Tab("Home");
    private final HomeView home;
    private final BooleanProperty homeSelected = new SimpleBooleanProperty(true);
    private final Map<Workspace, javafx.animation.PauseTransition> autosavers = new java.util.HashMap<>();
    private final Updater updater;
    private MenuItem undoItem, redoItem;
    private Timeline autosave;
    private boolean syncingChrome;

    public MainWindow(Stage stage) {
        this.stage = stage;
        minimap.addListener((o, a, b) -> Settings.put("minimap", String.valueOf(b)));

        home = new HomeView(stage, this);
        updater = new Updater(stage, this::exitThen);
        homeTab.setContent(home);
        homeTab.setClosable(false);
        homeTab.setGraphic(Icons.icon(Icons.HOME, 14));
        homeTab.getStyleClass().add("home-tab");
        homeTab.setTooltip(new Tooltip("Your projects (Ctrl+Shift+H)"));
        tabs.getTabs().add(homeTab);

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        tabs.getStyleClass().add("doc-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> onTabChanged());
        StackPane center = new StackPane(tabs, palette);
        palette.setOnClose(() -> { if (cur() != null) cur().canvas.requestFocus(); });
        root.setCenter(center);
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1480, 920);
        Theme.apply(scene);
        installShortcuts(scene);
        installDragAndDrop(scene);
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(520);
        scene.widthProperty().addListener((o, a, b) -> panels.onWindowWidth(b.doubleValue()));
        stage.setOnCloseRequest(e -> {
            e.consume();
            exit();
        });
        rebuildRecent();
        restoreWindow();
    }

    public void show(List<String> args) {
        stage.show();
        Platform.runLater(() -> {
            recoverCrashed();
            for (String a : args) {
                Path p = Path.of(a);
                if (Files.exists(p)) openPath(p);
            }
            if (args.isEmpty() && Settings.reopenLastSession()) {
                for (String s : Settings.get("session", "").split("\n")) {
                    if (!s.isBlank() && Files.exists(Path.of(s))) openPath(Path.of(s));
                }
                String sel = Settings.get("sessionSelected", "");
                for (Map.Entry<Tab, Workspace> e : workspaces.entrySet()) {
                    Path f = e.getValue().editor.file();
                    if (f != null && f.toString().equals(sel)) tabs.getSelectionModel().select(e.getKey());
                }
                if (sel.isEmpty()) tabs.getSelectionModel().select(homeTab);
            }
            if (workspaces.isEmpty()) showHome();
            else onTabChanged();
            startAutosave();
            if (cur() != null) cur().canvas.requestFocus();
            updater.checkOnStartup();
        });
    }

    // ================================================================== tabs

    Workspace cur() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        return t == null ? null : workspaces.get(t);
    }

    private Workspace newTab() {
        Workspace ws = new Workspace(this);
        Tab tab = new Tab();
        tab.setContent(ws.root());
        tab.setOnCloseRequest(e -> {
            e.consume();
            closeTab(tab);
        });
        workspaces.put(tab, ws);
        ws.editor.addListener((c, s) -> {
            updateTab(tab, ws);
            if (ws == cur()) refreshChrome();
            scheduleAutosave(ws);
        });
        ws.editor.dirtyProperty().addListener((o, a, b) -> {
            updateTab(tab, ws);
            if (!b) deleteRecovery(ws);
            if (ws == cur()) refreshChrome();
        });
        ws.editor.fileProperty().addListener(o -> { updateTab(tab, ws); if (ws == cur()) refreshChrome(); });
        ws.canvas.zoomProperty().addListener((o, a, b) -> { if (ws == cur()) refreshChrome(); });
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        updateTab(tab, ws);
        Platform.runLater(() -> ws.canvas.setView(60, 60, 1));
        return ws;
    }

    private void updateTab(Tab tab, Workspace ws) {
        tab.setText((ws.editor.isDirty() ? "• " : "") + ws.title());
        Path f = ws.editor.file();
        tab.setTooltip(new Tooltip(f == null ? "Not saved yet" : f.toString()));
    }

    /** A blank, untouched tab can be reused when opening a file. */
    private boolean pristine(Workspace ws) {
        return ws != null && ws.editor.file() == null && !ws.editor.isDirty() && ws.editor.isEmpty();
    }

    private Workspace targetForOpen() {
        Workspace c = cur();
        return pristine(c) ? c : newTab();
    }

    private void closeTab(Tab tab) {
        Workspace ws = workspaces.get(tab);
        if (ws == null) return;
        if (!confirmDiscard(ws)) return;
        persistView(ws);
        deleteRecovery(ws);
        workspaces.remove(tab);
        javafx.animation.PauseTransition pt = autosavers.remove(ws);
        if (pt != null) pt.stop();
        tabs.getTabs().remove(tab);
        if (workspaces.isEmpty()) showHome();
    }

    /** Fit after the tab's first layout pass (a brand-new tab has no size yet). */
    private static void fitSoon(Workspace w) {
        javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(Duration.millis(90));
        t.setOnFinished(e -> w.canvas.fitToContent());
        t.play();
    }

    private void showHome() {
        tabs.getSelectionModel().select(homeTab);
        home.refresh();
    }

    Tab tabOf(Workspace w) {
        for (Map.Entry<Tab, Workspace> e : workspaces.entrySet()) if (e.getValue() == w) return e.getKey();
        return null;
    }

    Workspace workspaceFor(Path p) {
        if (p == null) return null;
        Path abs = p.toAbsolutePath().normalize();
        for (Workspace w : workspaces.values()) {
            Path f = w.editor.file();
            if (f != null && f.toAbsolutePath().normalize().equals(abs)) return w;
        }
        return null;
    }

    // ================================================================== autosave (library projects)

    private boolean autosaves(Workspace w) {
        return Settings.autosaveProjects() && ProjectLibrary.get().contains(w.editor.file());
    }

    private void scheduleAutosave(Workspace w) {
        if (!autosaves(w) || !w.editor.isDirty()) return;
        javafx.animation.PauseTransition pt = autosavers.computeIfAbsent(w, k -> {
            javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(Duration.millis(1200));
            t.setOnFinished(e -> {
                if (workspaces.containsValue(k) && k.editor.isDirty() && autosaves(k)) writeTo(k, k.editor.file(), true);
            });
            return t;
        });
        pt.playFromStart();
    }

    private void onTabChanged() {
        Workspace ws = cur();
        homeSelected.set(ws == null);
        if (ws == null) {
            home.refresh();
            stage.setTitle("ERD Studio");
            statusLeft.setText("Projects are stored in " + ProjectLibrary.get().dir());
            statusRight.setText(Settings.autosaveProjects() ? "Autosave on" : "Autosave off");
            syncingChrome = true;
            nameField.setText("");
            syncingChrome = false;
            return;
        }
        if (undoItem != null) {
            undoItem.disableProperty().unbind();
            redoItem.disableProperty().unbind();
            undoItem.disableProperty().bind(ws.editor.canUndoProperty().not());
            redoItem.disableProperty().bind(ws.editor.canRedoProperty().not());
        }
        dirtyDot.visibleProperty().unbind();
        dirtyDot.visibleProperty().bind(ws.editor.dirtyProperty());
        refreshChrome();
        Platform.runLater(ws.canvas::requestFocus);
    }

    @Override
    public BooleanProperty minimapVisible() { return minimap; }

    @Override
    public PanelState panels() { return panels; }

    // ================================================================== layout

    private Node buildTopBar() {
        StackPane logo = new StackPane(Icons.icon(Icons.DATABASE, 16));
        logo.getStyleClass().add("logo");
        Label title = new Label("ERD Studio");
        title.getStyleClass().add("app-title");

        MenuBar menuBar = new MenuBar(fileMenu(), editMenu(), viewMenu(), diagramMenu(), helpMenu());
        menuBar.setUseSystemMenuBar(false);

        nameField.getStyleClass().addAll("borderless", "diagram-name");
        nameField.setOnAction(e -> { if (cur() != null) cur().canvas.requestFocus(); });
        nameField.textProperty().addListener((o, a, b) -> {
            Workspace ws = cur();
            if (syncingChrome || ws == null || b == null || b.equals(ws.editor.diagram().name)) return;
            ws.editor.diagram().name = b;
            ws.editor.changed(nameField);
        });
        nameField.focusedProperty().addListener((o, a, b) -> {
            Workspace ws = cur();
            if (!b && ws != null) {
                if (nameField.getText().isBlank()) nameField.setText("Untitled diagram");
                ws.editor.commit(nameField);
            }
        });
        dirtyDot.getStyleClass().add("dirty-dot");
        Tooltip.install(dirtyDot, new Tooltip("Unsaved changes"));
        HBox center = new HBox(6, nameField, dirtyDot);
        center.setAlignment(Pos.CENTER);

        dialectBox.getItems().setAll(Dialect.values());
        dialectBox.setTooltip(new Tooltip("Database dialect (column types, export)"));
        dialectBox.valueProperty().addListener((o, a, b) -> {
            Workspace ws = cur();
            if (syncingChrome || ws == null || b == null || b == ws.editor.diagram().dialect) return;
            ws.editor.diagram().dialect = b;
            ws.editor.commit(dialectBox);
        });

        MenuButton importBtn = new MenuButton("Import");
        importBtn.setGraphic(Icons.icon(Icons.UPLOAD, 15));
        importBtn.getItems().addAll(
                plain("SQL, Prisma or DBML…", "Shortcut+I", () -> importDialog(null)),
                plain("From database (PostgreSQL / MySQL)…", "Shortcut+Shift+I", this::importDatabase));

        MenuButton exportBtn = new MenuButton("Export");
        exportBtn.setGraphic(Icons.icon(Icons.DOWNLOAD, 15));
        exportBtn.getItems().addAll(
                plain("SQL script…", "Shortcut+E", this::exportSql),
                plain("Prisma schema…", null, () -> exportCode(ExportCodeDialog.Format.PRISMA)),
                plain("DBML…", null, () -> exportCode(ExportCodeDialog.Format.DBML)),
                plain("Mermaid ER diagram…", null, () -> exportCode(ExportCodeDialog.Format.MERMAID)),
                plain("Markdown documentation…", null, () -> exportCode(ExportCodeDialog.Format.MARKDOWN)),
                new SeparatorMenuItem(),
                plain("Screenshot (high-quality PNG)…", "Shortcut+Shift+E", this::screenshot),
                plain("Quick PNG (2×)…", null, this::exportPng),
                plain("SVG image…", null, this::exportSvg));

        Button shot = iconButton(Icons.CAMERA, "Screenshot (Ctrl+Shift+E)", this::screenshot);
        Button versions = iconButton(Icons.HISTORY, "Versions & migrations (Ctrl+Shift+M)", this::versions);
        Button paletteBtn = iconButton(Icons.COMMAND, "Command palette (Ctrl+K)", () -> palette.open(""));
        ToggleButton themeBtn = new ToggleButton();
        themeBtn.getStyleClass().add("icon-button");
        themeBtn.setTooltip(new Tooltip("Toggle light / dark theme (Ctrl+Shift+L)"));
        Runnable themeIcon = () -> themeBtn.setGraphic(Icons.icon(Theme.isDark() ? Icons.SUN : Icons.MOON, 17));
        themeIcon.run();
        Theme.darkProperty().addListener(o -> themeIcon.run());
        themeBtn.setOnAction(e -> { Theme.toggle(); themeBtn.setSelected(false); });

        // "⋯" overflow: holds the actions that no longer fit, so nothing ever gets clipped
        MenuButton more = new MenuButton();
        more.setGraphic(Icons.icon(Icons.MORE, 17));
        more.getStyleClass().addAll("icon-button", "overflow-button");
        more.setTooltip(new Tooltip("More"));
        more.setFocusTraversable(false);
        more.setOnShowing(e -> {
            more.getItems().clear();
            if (!homeSelected.get() && !dialectBox.isVisible()) {
                Menu dm = new Menu("Dialect: " + dialectBox.getValue());
                for (Dialect d : Dialect.values()) {
                    javafx.scene.control.RadioMenuItem ri = new javafx.scene.control.RadioMenuItem(d.toString());
                    ri.setSelected(d == dialectBox.getValue());
                    ri.setOnAction(x -> dialectBox.setValue(d));
                    dm.getItems().add(ri);
                }
                more.getItems().add(dm);
            }
            if (!homeSelected.get() && !importBtn.isVisible()) { Menu im = new Menu("Import"); im.setGraphic(Icons.icon(Icons.UPLOAD, 14)); copyItems(importBtn, im); more.getItems().add(im); }
            if (!homeSelected.get() && !exportBtn.isVisible()) { Menu em = new Menu("Export"); em.setGraphic(Icons.icon(Icons.DOWNLOAD, 14)); copyItems(exportBtn, em); more.getItems().add(em); }
            if (!homeSelected.get() && !shot.isVisible()) more.getItems().add(withIcon(plain("Screenshot…", null, this::screenshot), Icons.CAMERA));
            if (!homeSelected.get() && !versions.isVisible()) more.getItems().add(withIcon(plain("Versions & migrations…", null, this::versions), Icons.HISTORY));
            if (!paletteBtn.isVisible()) more.getItems().add(withIcon(plain("Command palette", null, () -> palette.open("")), Icons.COMMAND));
            if (!themeBtn.isVisible()) more.getItems().add(withIcon(plain(Theme.isDark() ? "Light theme" : "Dark theme", null, Theme::toggle), Theme.isDark() ? Icons.SUN : Icons.MOON));
            if (!more.getItems().isEmpty()) more.getItems().add(new SeparatorMenuItem());
            more.getItems().add(withIcon(plain("Settings…", null, this::settings), Icons.SETTINGS));
        });

        nameField.setMinWidth(110);
        nameField.setPrefColumnCount(16);
        Region l = new Region(), r = new Region();
        HBox.setHgrow(l, Priority.ALWAYS);
        HBox.setHgrow(r, Priority.ALWAYS);
        // hide order when space runs out (first = hidden first)
        Node[] collapsible = { themeBtn, paletteBtn, versions, shot, exportBtn, importBtn, dialectBox, title };
        for (Node n : new Node[]{ logo, title, menuBar, dialectBox, importBtn, exportBtn, shot, versions, paletteBtn, themeBtn, more })
            ((Region) n).setMinWidth(Region.USE_PREF_SIZE);
        center.setMinWidth(130);
        center.visibleProperty().bind(homeSelected.not());
        docOnly = java.util.Set.of(dialectBox, importBtn, exportBtn, shot, versions);
        homeSelected.addListener(o -> bar().requestLayout());
        HBox bar = new HBox(logo, title, new Separator(Orientation.VERTICAL), menuBar, l, center, r,
                dialectBox, importBtn, exportBtn, shot, versions, paletteBtn, themeBtn, more) {
            @Override
            protected void layoutChildren() {
                fitTopBar(this, collapsible, center);
                super.layoutChildren();
            }
        };
        bar.getStyleClass().add("top-bar");
        topBar = bar;
        return bar;
    }

    private boolean fitting;
    private java.util.Set<Node> docOnly = java.util.Set.of();
    private HBox topBar;

    private HBox bar() { return topBar; }

    /** Shows as many collapsible controls as fit; the rest move into the "⋯" menu. */
    private void fitTopBar(HBox bar, Node[] collapsible, Node center) {
        if (fitting) return;
        fitting = true;
        try {
            double avail = bar.getWidth() - bar.snappedLeftInset() - bar.snappedRightInset();
            if (avail <= 0) return;
            for (Node n : collapsible) {
                boolean show = !(homeSelected.get() && docOnly.contains(n));
                n.setVisible(show);
                n.setManaged(show);
            }
            for (Node n : collapsible) {
                if (!n.isManaged()) continue;
                if (requiredWidth(bar, center) <= avail) break;
                n.setVisible(false);
                n.setManaged(false);
            }
        } finally {
            fitting = false;
        }
    }

    private static double requiredWidth(HBox bar, Node center) {
        double w = bar.snappedLeftInset() + bar.snappedRightInset();
        int managed = 0;
        for (Node n : bar.getChildren()) {
            if (!n.isManaged()) continue;
            managed++;
            w += n == center ? 170 : n.prefWidth(-1);
        }
        return w + Math.max(0, managed - 1) * bar.getSpacing() + 16;
    }

    private void copyItems(MenuButton from, Menu to) {
        for (MenuItem mi : from.getItems()) {
            if (mi instanceof SeparatorMenuItem) { to.getItems().add(new SeparatorMenuItem()); continue; }
            MenuItem c = new MenuItem(mi.getText());
            c.setOnAction(mi.getOnAction());
            to.getItems().add(c);
        }
    }

    private static MenuItem withIcon(MenuItem mi, String icon) {
        mi.setGraphic(Icons.icon(icon, 14));
        return mi;
    }

    private Node buildStatusBar() {
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bar = new HBox(statusLeft, sp, statusRight);
        bar.getStyleClass().add("status-bar");
        bar.visibleProperty().bind(homeSelected.not());
        bar.managedProperty().bind(bar.visibleProperty());
        return bar;
    }

    private Button iconButton(String icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.icon(icon, 17));
        b.getStyleClass().add("icon-button");
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Menu item that is also listed in the command palette. */
    private MenuItem item(String category, String text, String accel, String icon, Runnable action) {
        MenuItem mi = plain(text, accel, action);
        commands.add(new CommandPalette.Command(category, text.replace("…", ""), accel == null ? null : pretty(accel), icon, action));
        return mi;
    }

    private MenuItem plain(String text, String accel, Runnable action) {
        MenuItem mi = new MenuItem(text);
        if (accel != null) mi.setAccelerator(KeyCombination.keyCombination(accel));
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private static String pretty(String accel) {
        return accel.replace("Shortcut", "Ctrl").replace("Equals", "=").replace("Minus", "-").replace("Comma", ",");
    }

    /** Runs an action on the current workspace. */
    private Runnable ws(java.util.function.Consumer<Workspace> action) {
        return () -> { Workspace w = cur(); if (w != null) action.accept(w); };
    }

    // ================================================================== menus

    private Menu fileMenu() {
        Menu m = new Menu("File");
        m.getItems().addAll(
                item("File", "New project…", "Shortcut+N", Icons.FILE_PLUS, this::newProject),
                item("File", "Home (all projects)", "Shortcut+Shift+H", Icons.HOME, this::showHome),
                item("File", "Open project file…", "Shortcut+O", Icons.FOLDER, this::open),
                recentMenu,
                new SeparatorMenuItem(),
                item("File", "Save", "Shortcut+S", Icons.SAVE, () -> save(cur())),
                item("File", "Save all", null, Icons.SAVE, this::saveAll),
                item("File", "Export project file (.erd)…", "Shortcut+Shift+S", Icons.DOWNLOAD, () -> exportErd(cur())),
                item("File", "Import project file into library…", null, Icons.UPLOAD, this::importErdFile),
                item("File", "Close tab", "Shortcut+W", Icons.X, () -> closeTab(tabs.getSelectionModel().getSelectedItem())),
                new SeparatorMenuItem(),
                item("Import", "Import SQL / Prisma / DBML…", null, Icons.UPLOAD, () -> importDialog(null)),
                item("Import", "Import from database…", null, Icons.SERVER, this::importDatabase),
                new SeparatorMenuItem(),
                item("Export", "Export SQL…", null, Icons.CODE, this::exportSql),
                item("Export", "Export Prisma schema…", null, Icons.CODE, () -> exportCode(ExportCodeDialog.Format.PRISMA)),
                item("Export", "Export DBML…", null, Icons.CODE, () -> exportCode(ExportCodeDialog.Format.DBML)),
                item("Export", "Export Mermaid…", null, Icons.CODE, () -> exportCode(ExportCodeDialog.Format.MERMAID)),
                item("Export", "Export Markdown docs…", null, Icons.FILE, () -> exportCode(ExportCodeDialog.Format.MARKDOWN)),
                item("Export", "Screenshot (high-quality PNG)…", null, Icons.CAMERA, this::screenshot),
                item("Export", "Quick PNG export…", null, Icons.IMAGE, this::exportPng),
                item("Export", "Export SVG…", null, Icons.IMAGE, this::exportSvg),
                new SeparatorMenuItem(),
                item("App", "Settings…", "Shortcut+Comma", Icons.SETTINGS, this::settings),
                new SeparatorMenuItem(),
                plain("Exit", null, this::exit));
        return m;
    }

    private Menu editMenu() {
        Menu m = new Menu("Edit");
        undoItem = item("Edit", "Undo", "Shortcut+Z", Icons.UNDO, ws(w -> w.editor.undo()));
        redoItem = item("Edit", "Redo", "Shortcut+Y", Icons.REDO, ws(w -> w.editor.redo()));
        m.getItems().addAll(undoItem, redoItem, new SeparatorMenuItem(),
                item("Edit", "Copy", "Shortcut+C", Icons.COPY, ws(w -> w.editor.copySelection())),
                item("Edit", "Paste", "Shortcut+V", Icons.COPY, ws(w -> w.editor.paste())),
                item("Edit", "Duplicate", "Shortcut+D", Icons.COPY, ws(w -> w.editor.duplicateSelection())),
                item("Edit", "Delete selection", "Delete", Icons.TRASH, ws(w -> w.editor.deleteSelection())),
                new SeparatorMenuItem(),
                item("Edit", "Select all", "Shortcut+A", null, ws(w -> w.editor.selectAll())),
                item("Edit", "Find table…", "Shortcut+F", Icons.SEARCH, ws(w -> w.sidebar.focusSearch())),
                item("Edit", "Command palette…", "Shortcut+K", Icons.COMMAND, () -> palette.open("")));
        return m;
    }

    private Menu viewMenu() {
        Menu m = new Menu("View");
        CheckMenuItem snap = new CheckMenuItem("Snap to grid");
        snap.setSelected(Settings.snapToGrid());
        snap.selectedProperty().addListener((o, a, b) -> { for (Workspace w : workspaces.values()) w.canvas.snapToGridProperty().set(b); });
        CheckMenuItem grid = new CheckMenuItem("Show grid");
        grid.setSelected(true);
        grid.selectedProperty().addListener((o, a, b) -> { for (Workspace w : workspaces.values()) w.canvas.showGridProperty().set(b); });
        CheckMenuItem map = new CheckMenuItem("Minimap");
        map.selectedProperty().bindBidirectional(minimap);
        CheckMenuItem left = new CheckMenuItem("Explorer panel");
        left.selectedProperty().bindBidirectional(panels.leftOpen);
        left.setAccelerator(KeyCombination.keyCombination("Shortcut+B"));
        CheckMenuItem right = new CheckMenuItem("Properties panel");
        right.selectedProperty().bindBidirectional(panels.rightOpen);
        right.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+B"));
        commands.add(new CommandPalette.Command("View", "Toggle explorer panel", "Ctrl+B", null, () -> left.setSelected(!left.isSelected())));
        commands.add(new CommandPalette.Command("View", "Toggle properties panel", "Ctrl+Shift+B", null, () -> right.setSelected(!right.isSelected())));
        commands.add(new CommandPalette.Command("View", "Toggle minimap", null, Icons.MAP, () -> minimap.set(!minimap.get())));
        commands.add(new CommandPalette.Command("View", "Toggle snap to grid", null, Icons.GRID, () -> snap.setSelected(!snap.isSelected())));
        m.getItems().addAll(
                item("View", "Zoom in", "Shortcut+Equals", Icons.ZOOM_IN, ws(w -> w.canvas.zoomIn())),
                item("View", "Zoom out", "Shortcut+Minus", Icons.ZOOM_OUT, ws(w -> w.canvas.zoomOut())),
                item("View", "Actual size (100%)", "Shortcut+0", null, ws(w -> w.canvas.resetZoom())),
                item("View", "Fit diagram to screen", "Shift+1", Icons.FIT, ws(w -> w.canvas.fitToContent())),
                new SeparatorMenuItem(), snap, grid, map, left, right,
                new SeparatorMenuItem(),
                item("View", "Toggle dark mode", "Shortcut+Shift+L", Icons.MOON, Theme::toggle));
        return m;
    }

    private Menu diagramMenu() {
        Menu m = new Menu("Diagram");
        Menu align = new Menu("Align selection");
        align.getItems().addAll(
                item("Arrange", "Align left", null, Icons.ALIGN_LEFT, ws(w -> w.editor.align(Editor.Align.LEFT, w.canvas::sizeOf))),
                item("Arrange", "Align centers horizontally", null, Icons.ALIGN_HCENTER, ws(w -> w.editor.align(Editor.Align.CENTER, w.canvas::sizeOf))),
                item("Arrange", "Align right", null, Icons.ALIGN_RIGHT, ws(w -> w.editor.align(Editor.Align.RIGHT, w.canvas::sizeOf))),
                new SeparatorMenuItem(),
                item("Arrange", "Align top", null, Icons.ALIGN_TOP, ws(w -> w.editor.align(Editor.Align.TOP, w.canvas::sizeOf))),
                item("Arrange", "Align middles vertically", null, Icons.ALIGN_VCENTER, ws(w -> w.editor.align(Editor.Align.MIDDLE, w.canvas::sizeOf))),
                item("Arrange", "Align bottom", null, Icons.ALIGN_BOTTOM, ws(w -> w.editor.align(Editor.Align.BOTTOM, w.canvas::sizeOf))),
                new SeparatorMenuItem(),
                item("Arrange", "Distribute horizontally", null, Icons.DISTRIBUTE_H, ws(w -> w.editor.distribute(true, w.canvas::sizeOf))),
                item("Arrange", "Distribute vertically", null, Icons.DISTRIBUTE_V, ws(w -> w.editor.distribute(false, w.canvas::sizeOf))));
        m.getItems().addAll(
                item("Insert", "Add table", null, Icons.TABLE, ws(Workspace::addTableAtCenter)),
                item("Insert", "Add note", null, Icons.NOTE, ws(Workspace::addNoteAtCenter)),
                item("Insert", "Add area / group selection", "Shortcut+G", Icons.AREA, ws(Workspace::addAreaOrGroup)),
                new SeparatorMenuItem(),
                item("Arrange", "Tidy up (arrange everything neatly)", "Shortcut+L", Icons.SPARKLES, ws(Workspace::autoLayout)),
                item("Arrange", "Arrange selected tables", null, Icons.LAYOUT, ws(w -> w.editor.autoLayout(w.canvas::sizeOf, true))),
                align,
                new SeparatorMenuItem(),
                item("Diagram", "Versions & migrations…", "Shortcut+Shift+M", Icons.HISTORY, this::versions),
                item("Diagram", "Save version now…", null, Icons.HISTORY, this::quickSaveVersion),
                new SeparatorMenuItem(),
                item("Diagram", "Load sample schema", null, Icons.TABLE, this::loadSample));
        return m;
    }

    private Menu helpMenu() {
        Menu m = new Menu("Help");
        m.getItems().addAll(
                item("Help", "Keyboard shortcuts", "F1", Icons.KEYBOARD, this::showShortcuts),
                item("Help", "Connect an AI assistant (MCP)…", null, Icons.ZAP, () -> new McpDialog(stage).showAndWait()),
                item("Help", "Check for updates…", null, Icons.DOWNLOAD, updater::checkNow),
                item("Help", "Release notes on GitHub", null, Icons.EXTERNAL, () -> Desktops.browse(com.erdstudio.update.UpdateService.releasesPage())),
                item("Help", "About ERD Studio", null, null, () -> Dialogs.info(stage, "ERD Studio " + com.erdstudio.AppInfo.VERSION,
                        "A personal database diagram editor.\nJavaFX " + System.getProperty("javafx.runtime.version", "")
                                + " · Java " + System.getProperty("java.version")
                                + "\nProjects: " + ProjectLibrary.get().dir()
                                + "\nApp data: " + dataDir())));
        return m;
    }

    private List<CommandPalette.Command> paletteCommands() {
        List<CommandPalette.Command> out = new ArrayList<>(commands);
        Workspace w = cur();
        if (w != null) {
            for (Table t : w.editor.diagram().tables) {
                out.add(new CommandPalette.Command("Go to table", t.name, null, Icons.TABLE, () -> {
                    w.editor.select(t.id, false);
                    w.canvas.centerOn(t.id);
                }));
            }
            for (Area a : w.editor.diagram().areas) {
                out.add(new CommandPalette.Command("Go to area", a.name, null, Icons.AREA, () -> {
                    w.editor.select(a.id, false);
                    w.canvas.centerOn(a.id);
                }));
            }
            for (Dialect d : Dialect.values()) {
                out.add(new CommandPalette.Command("Set database", d.label, null, Icons.DATABASE, () -> {
                    w.editor.diagram().dialect = d;
                    w.editor.commit();
                }));
            }
        }
        for (Map.Entry<Tab, Workspace> e : workspaces.entrySet()) {
            out.add(new CommandPalette.Command("Switch to tab", e.getValue().title(), null, Icons.FILE,
                    () -> tabs.getSelectionModel().select(e.getKey())));
        }
        for (ProjectLibrary.Project pr : ProjectLibrary.get().list()) {
            if (pr.broken()) continue;
            out.add(new CommandPalette.Command("Open project", pr.name(), null, Icons.FOLDER, () -> openPath(pr.file())));
        }
        return out;
    }

    /** Shortcuts that must not fire while typing in a text field are handled here. */
    private void installShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (CommandPalette.isOpenKey(e)) {
                palette.open("");
                e.consume();
                return;
            }
            if (palette.isVisible()) return;
            boolean inText = scene.getFocusOwner() instanceof TextInputControl;
            Workspace w = cur();
            if (inText || w == null) return;
            if (e.isShortcutDown()) {
                KeyCode k = e.getCode();
                if (k == KeyCode.Z && !e.isShiftDown()) { w.editor.undo(); e.consume(); }
                else if (k == KeyCode.Y || (k == KeyCode.Z && e.isShiftDown())) { w.editor.redo(); e.consume(); }
                else if (k == KeyCode.C) { w.editor.copySelection(); e.consume(); }
                else if (k == KeyCode.X) { if (w.editor.copySelection()) w.editor.deleteSelection(); e.consume(); }
                else if (k == KeyCode.V) { w.editor.paste(); e.consume(); }
                else if (k == KeyCode.A) { w.editor.selectAll(); e.consume(); }
            } else if (e.getCode() == KeyCode.DELETE && !w.editor.selection().isEmpty()) {
                w.editor.deleteSelection();
                e.consume();
            }
        });
    }

    private void installDragAndDrop(Scene scene) {
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            List<File> files = e.getDragboard().getFiles();
            boolean ok = false;
            if (files != null) {
                for (File f : files) {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".json") || n.endsWith(ProjectLibrary.EXT)) { openPath(f.toPath()); ok = true; }
                    else if (n.endsWith(".sql") || n.endsWith(".ddl") || n.endsWith(".prisma") || n.endsWith(".dbml") || n.endsWith(".txt")) {
                        String text = ImportDialog.readFile(f.toPath());
                        ImportDialog.Format fmt = n.endsWith(".prisma") ? ImportDialog.Format.PRISMA
                                : n.endsWith(".dbml") ? ImportDialog.Format.DBML : ImportDialog.detect(text);
                        Workspace w = cur();
                        if (w == null) {
                            ImportResult r = ImportDialog.parse(text, fmt, Settings.defaultDialect());
                            String base = ProjectLibrary.stem(f.toPath());
                            importIntoNewProject(r, base.replaceAll("\\.(sql|ddl|prisma|dbml|txt)$", ""));
                        } else {
                            applyImport(w, ImportDialog.parse(text, fmt, w.editor.diagram().dialect), w.editor.isEmpty());
                        }
                        ok = true;
                    }
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    // ================================================================== chrome

    private void refreshChrome() {
        Workspace w = cur();
        if (w == null) return;
        Diagram d = w.editor.diagram();
        syncingChrome = true;
        try {
            if (!nameField.isFocused() && !d.name.equals(nameField.getText())) nameField.setText(d.name);
            if (dialectBox.getValue() != d.dialect) dialectBox.setValue(d.dialect);
        } finally {
            syncingChrome = false;
        }
        Path f = w.editor.file();
        stage.setTitle((w.editor.isDirty() ? "• " : "") + d.name + " — ERD Studio");
        int cols = 0;
        for (Table t : d.tables) cols += t.columns.size();
        statusLeft.setText(d.tables.size() + " tables  ·  " + cols + " columns  ·  " + d.relations.size()
                + " relations  ·  " + d.areas.size() + " areas  ·  " + d.notes.size() + " notes"
                + (d.versions.isEmpty() ? "" : "  ·  " + d.versions.size() + " versions"));
        String where = f == null ? "Not saved yet"
                : autosaves(w) ? (w.editor.isDirty() ? "Saving…" : "All changes saved")
                : f.toString();
        statusRight.setText(where + "   ·   " + d.dialect.label
                + "   ·   " + Math.round(w.canvas.zoom() * 100) + "%");
    }

    // ================================================================== file actions

    private void open() {
        FileChooser fc = chooser("Open project file");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("ERD Studio project", "*.erd", "*.erd.json", "*.json"));
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files != null) for (File f : files) openPath(f.toPath());
    }

    void openPath(Path p) {
        Workspace existing = workspaceFor(p);
        if (existing != null) {
            tabs.getSelectionModel().select(tabOf(existing));
            return;
        }
        try {
            Diagram d = JsonStore.load(p);
            Workspace w = targetForOpen();
            w.editor.replace(d, p);
            Platform.runLater(() -> {
                w.canvas.setView(d.viewX, d.viewY, d.zoom <= 0 ? 1 : d.zoom);
                if (d.viewX == 0 && d.viewY == 0) fitSoon(w);
            });
            ProjectLibrary lib = ProjectLibrary.get();
            if (lib.contains(p)) {
                lib.touchOpened(p);
            } else {
                addRecent(p);
                rememberDir(p.toFile());
                Platform.runLater(() -> w.showToast("Opened from a file outside your library — Ctrl+S saves to it",
                        "Add to library", () -> addToLibrary(w)));
            }
        } catch (Exception ex) {
            Dialogs.error(stage, "Could not open project", p + "\n\n" + ex.getMessage());
        }
    }

    /** Moves an externally opened file's content into the library; the tab then autosaves there. */
    private void addToLibrary(Workspace w) {
        try {
            ProjectLibrary lib = ProjectLibrary.get();
            Path target = lib.uniquePath(w.editor.diagram().name, null);
            if (writeTo(w, target, true)) {
                lib.touchOpened(target);
                w.showToast("Added to your library", null, null);
            }
        } catch (Exception ex) {
            Dialogs.error(stage, "Could not add to library", ex.getMessage());
        }
    }

    private boolean save(Workspace w) {
        if (w == null) return false;
        if (w.editor.file() == null) {
            ProjectLibrary lib = ProjectLibrary.get();
            boolean ok = writeTo(w, lib.uniquePath(w.editor.diagram().name, null), false);
            if (ok) lib.touchOpened(w.editor.file());
            return ok;
        }
        return writeTo(w, w.editor.file(), false);
    }

    private void saveAll() {
        for (Map.Entry<Tab, Workspace> e : workspaces.entrySet()) {
            if (!e.getValue().editor.isDirty()) continue;
            if (!save(e.getValue())) return;
        }
    }

    /** Writes the project; library files are renamed to follow the project name. */
    private boolean writeTo(Workspace w, Path p, boolean quiet) {
        Diagram d = w.editor.diagram();
        d.viewX = w.canvas.panX();
        d.viewY = w.canvas.panY();
        d.zoom = w.canvas.zoom();
        try {
            ProjectLibrary lib = ProjectLibrary.get();
            if (lib.contains(p)) lib.ensureDir();
            JsonStore.save(d, p);
            Path finalPath = lib.contains(p) ? lib.syncFileName(p, d.name) : p;
            w.editor.markSaved(finalPath);
            deleteRecovery(w);
            if (!lib.contains(finalPath)) addRecent(finalPath);
            if (!quiet) w.showToast(lib.contains(finalPath) ? "Saved" : "Saved " + finalPath.getFileName(), null, null);
            if (w == cur()) refreshChrome();
            return true;
        } catch (IOException ex) {
            if (quiet) {
                w.showToast("Autosave failed: " + ex.getMessage(), "Retry", () -> save(w));
            } else {
                Dialogs.error(stage, "Could not save", ex.getMessage());
            }
            return false;
        }
    }

    /** Ctrl+Shift+S: writes a standalone copy of the project somewhere else (e.g. to send it). */
    private void exportErd(Workspace w) {
        if (w == null) return;
        FileChooser fc = chooser("Export project file");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ERD Studio project", "*.erd"));
        fc.setInitialFileName(ProjectLibrary.safeName(w.editor.diagram().name) + ProjectLibrary.EXT);
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        Path target = withExt(f.toPath());
        rememberDir(target.toFile());
        Diagram d = w.editor.diagram();
        d.viewX = w.canvas.panX();
        d.viewY = w.canvas.panY();
        d.zoom = w.canvas.zoom();
        try {
            JsonStore.save(d, target);
            w.showToast("Exported " + target.getFileName(), null, null);
        } catch (IOException ex) {
            Dialogs.error(stage, "Could not export", ex.getMessage());
        }
    }

    private static Path withExt(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(ProjectLibrary.EXT) || n.endsWith(".json") ? p : p.resolveSibling(p.getFileName() + ProjectLibrary.EXT);
    }

    // ================================================================== home screen actions

    @Override
    public void newProject() {
        Dialogs.prompt(stage, "New project", "Project name", "Untitled diagram").ifPresent(name -> {
            Diagram d = new Diagram();
            d.name = name.isBlank() ? "Untitled diagram" : name.trim();
            d.dialect = Settings.defaultDialect();
            createAndOpen(d);
        });
    }

    Workspace createAndOpen(Diagram d) {
        try {
            Path p = ProjectLibrary.get().create(d);
            openPath(p);
            return workspaceFor(p);
        } catch (IOException ex) {
            Dialogs.error(stage, "Could not create project",
                    "The projects folder is not writable:\n" + ProjectLibrary.get().dir() + "\n\n" + ex.getMessage()
                            + "\n\nYou can choose another folder in Settings.");
            return null;
        }
    }

    @Override
    public void openProject(Path p) {
        openPath(p);
    }

    @Override
    public void importErdFile() {
        FileChooser fc = chooser("Import project file into your library");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ERD Studio project", "*.erd", "*.erd.json", "*.json"));
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null) return;
        for (File f : files) {
            rememberDir(f);
            try {
                openPath(ProjectLibrary.get().importFile(f.toPath()));
            } catch (Exception ex) {
                Dialogs.error(stage, "Could not import " + f.getName(), "This is not a valid ERD Studio project file.\n\n" + ex.getMessage());
            }
        }
        home.refresh();
    }

    @Override
    public void importSchema() {
        ImportDialog dlg = new ImportDialog(stage, Settings.defaultDialect(), true, null, null);
        dlg.showAndWait().ifPresent(c -> importIntoNewProject(c.result(), null));
    }

    @Override
    public void importDatabase() {
        Workspace w = cur();
        if (w == null) {
            new DatabaseDialog(stage, true).showAndWait().ifPresent(c -> importIntoNewProject(c.result(), null));
            return;
        }
        new DatabaseDialog(stage, w.editor.isEmpty()).showAndWait().ifPresent(c -> applyImport(w, c.result(), c.replace()));
    }

    private void importIntoNewProject(ImportResult r, String fallbackName) {
        if (r.tableCount() == 0) {
            Dialogs.error(stage, "Nothing to import", r.warnings().isEmpty() ? "No tables were found." : String.join("\n", r.warnings()));
            return;
        }
        Diagram d = new Diagram();
        String n = r.diagram().name;
        d.name = n != null && !n.isBlank() && !n.equals("Untitled diagram") ? n
                : fallbackName != null && !fallbackName.isBlank() ? fallbackName : "Imported schema";
        d.dialect = r.diagram().dialect != null ? r.diagram().dialect : Settings.defaultDialect();
        Workspace w = createAndOpen(d);
        if (w != null) {
            applyImport(w, r, true);
            w.editor.diagram().name = d.name;
            w.editor.commit();
        }
    }

    @Override
    public void openSample() {
        Diagram d = new Diagram();
        d.name = "Online shop (sample)";
        Workspace w = createAndOpen(d);
        if (w != null) loadSampleInto(w);
    }

    @Override
    public void exportErd(Path p) {
        Workspace w = workspaceFor(p);
        if (w != null) { exportErd(w); return; }
        FileChooser fc = chooser("Export project file");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ERD Studio project", "*.erd"));
        fc.setInitialFileName(p.getFileName().toString());
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        Path target = withExt(f.toPath());
        rememberDir(target.toFile());
        try {
            Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            home.showToast("Exported " + target.getFileName(), null, null);
        } catch (IOException ex) {
            Dialogs.error(stage, "Could not export", ex.getMessage());
        }
    }

    @Override
    public boolean isOpen(Path p) {
        return workspaceFor(p) != null;
    }

    @Override
    public Path renameProject(Path p, String newName) {
        Workspace w = workspaceFor(p);
        try {
            if (w != null) {
                w.editor.diagram().name = newName;
                w.editor.commit();
                if (!writeTo(w, w.editor.file(), true)) return null;
                Tab t = tabOf(w);
                if (t != null) updateTab(t, w);
                return w.editor.file();
            }
            return ProjectLibrary.get().rename(p, newName);
        } catch (Exception ex) {
            Dialogs.error(stage, "Could not rename", ex.getMessage());
            return null;
        }
    }

    @Override
    public boolean closeProject(Path p) {
        Workspace w = workspaceFor(p);
        if (w == null) return true;
        Tab t = tabOf(w);
        Tab homeWas = tabs.getSelectionModel().getSelectedItem();
        if (!confirmDiscard(w)) return false;
        deleteRecovery(w);
        workspaces.remove(t);
        javafx.animation.PauseTransition pt = autosavers.remove(w);
        if (pt != null) pt.stop();
        tabs.getTabs().remove(t);
        tabs.getSelectionModel().select(homeWas == t ? homeTab : homeWas);
        return true;
    }

    @Override
    public void openSettings() {
        settings();
    }

    @Override
    public void importDialog() {
        importDialog(null);
    }

    private void importDialog(ImportDialog.Format fmt) {
        Workspace w = cur();
        if (w == null) {
            new ImportDialog(stage, Settings.defaultDialect(), true, null, fmt).showAndWait()
                    .ifPresent(c -> importIntoNewProject(c.result(), null));
            return;
        }
        ImportDialog dlg = new ImportDialog(stage, w.editor.diagram().dialect, w.editor.isEmpty(), null, fmt);
        dlg.showAndWait().ifPresent(c -> applyImport(w, c.result(), c.replace()));
    }

    private void applyImport(Workspace w, ImportResult r, boolean replace) {
        if (r.tableCount() == 0) {
            Dialogs.error(stage, "Nothing to import", r.warnings().isEmpty()
                    ? "No tables were found." : String.join("\n", r.warnings()));
            return;
        }
        w.editor.applyImport(r, replace, w.canvas::sizeOf);
        fitSoon(w);
        String msg = "Imported " + r.tableCount() + " tables and " + r.relationCount() + " relations";
        if (r.warnings().isEmpty()) w.showToast(msg, null, null);
        else w.showToast(msg + " · " + r.warnings().size() + " warnings", "Details",
                () -> Dialogs.info(stage, "Import warnings", String.join("\n", r.warnings())));
    }

    private void exportSql() {
        Workspace w = cur();
        if (w != null) new ExportSqlDialog(stage, w.editor.diagram()).showAndWait();
    }

    private void exportCode(ExportCodeDialog.Format f) {
        Workspace w = cur();
        if (w != null) new ExportCodeDialog(stage, w.editor.diagram(), w.canvas.areaMembers(), f).showAndWait();
    }

    private boolean emptyCheck(Workspace w) {
        if (w == null) return false;
        if (w.editor.isEmpty()) {
            Dialogs.info(stage, "Nothing to export", "The diagram is empty.");
            return false;
        }
        return true;
    }

    private void screenshot() {
        Workspace w = cur();
        if (!emptyCheck(w)) return;
        new ScreenshotDialog(stage, w.canvas, w.editor.diagram(), !w.editor.selection().isEmpty()).showAndWait();
    }

    private void exportPng() {
        Workspace w = cur();
        if (!emptyCheck(w)) return;
        FileChooser fc = chooser("Export PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        fc.setInitialFileName(ExportSqlDialog.safe(w.editor.diagram().name) + ".png");
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            WritableImage img = w.canvas.exportImage(2, false);
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
            rememberDir(f);
            w.showToast("Exported " + f.getName(), null, null);
        } catch (Exception ex) {
            Dialogs.error(stage, "Could not export PNG", String.valueOf(ex.getMessage()));
        }
    }

    private void exportSvg() {
        Workspace w = cur();
        if (!emptyCheck(w)) return;
        FileChooser fc = chooser("Export SVG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG image", "*.svg"));
        fc.setInitialFileName(ExportSqlDialog.safe(w.editor.diagram().name) + ".svg");
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), w.canvas.exportSvg(), StandardCharsets.UTF_8);
            rememberDir(f);
            w.showToast("Exported " + f.getName(), null, null);
        } catch (Exception ex) {
            Dialogs.error(stage, "Could not export SVG", String.valueOf(ex.getMessage()));
        }
    }

    private void versions() {
        Workspace w = cur();
        if (w != null) new VersionsDialog(stage, w.editor).showAndWait();
    }

    private void quickSaveVersion() {
        Workspace w = cur();
        if (w == null) return;
        Dialogs.prompt(stage, "Save version", "Name", "Version " + (w.editor.diagram().versions.size() + 1))
                .ifPresent(n -> {
                    w.editor.saveVersion(n, "");
                    w.showToast(autosaves(w) ? "Saved version \"" + n + "\""
                            : "Saved version \"" + n + "\" — save the project to keep it", null, null);
                });
    }

    private void settings() {
        new SettingsDialog(stage).showAndWait().ifPresent(changed -> {
            if (changed) startAutosave();
            onTabChanged();
            for (Workspace w : workspaces.values()) scheduleAutosave(w);
        });
    }

    @Override
    public void loadSample() {
        Workspace w = cur();
        if (w == null) { openSample(); return; }
        if (!w.editor.isEmpty()) { openSample(); return; }
        loadSampleInto(w);
    }

    private void loadSampleInto(Workspace w) {
        try (InputStream in = MainWindow.class.getResourceAsStream("/com/erdstudio/sample.sql")) {
            if (in == null) return;
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ImportResult r = SqlImporter.parse(sql, Dialect.POSTGRESQL);
            String name = w.editor.isEmpty() && !"Untitled diagram".equals(w.editor.diagram().name)
                    ? w.editor.diagram().name : "Online shop (sample)";
            w.editor.applyImport(r, true, w.canvas::sizeOf);
            w.editor.diagram().name = name;
            w.editor.clearSelection();
            w.editor.commit();
            fitSoon(w);
        } catch (IOException ex) {
            Dialogs.error(stage, "Could not load sample", ex.getMessage());
        }
    }

    private void showShortcuts() {
        String[][] rows = {
                {"Ctrl+K", "Command palette (search every action / table)"},
                {"Ctrl+N / Ctrl+O / Ctrl+S", "New tab / open / save"},
                {"Ctrl+W / Ctrl+Tab", "Close tab / next tab"},
                {"Ctrl+I / Ctrl+Shift+I", "Import file / import from database"},
                {"Ctrl+E / Ctrl+Shift+E", "Export SQL / screenshot"},
                {"Ctrl+L", "Tidy up (arrange everything)"},
                {"Ctrl+Shift+M", "Versions & migrations"},
                {"Ctrl+Z / Ctrl+Y", "Undo / redo"},
                {"Ctrl+C / X / V / D", "Copy / cut / paste / duplicate"},
                {"Delete", "Delete selection"},
                {"Ctrl+A / Ctrl+F", "Select all / find table"},
                {"Ctrl+G", "Group selection into an area"},
                {"T / N", "New table / note (canvas focused)"},
                {"Enter or F2", "Rename selected table / area"},
                {"Arrow keys (+Shift)", "Nudge selection"},
                {"Double-click canvas", "New table at pointer"},
                {"Double-click a name", "Rename table / column / area"},
                {"Drag a row's edge", "Create relation (or Alt+drag a row)"},
                {"Mouse wheel", "Zoom at pointer (Ctrl+wheel scrolls)"},
                {"Touchpad", "Two fingers scroll, pinch zooms"},
                {"Shift+wheel", "Scroll sideways"},
                {"Right-drag, Space+drag", "Pan"},
                {"Drag on empty canvas", "Box select (Ctrl/Shift adds)"},
                {"Ctrl+0 / Shift+1", "100% / fit"},
                {"Ctrl+B / Ctrl+Shift+B", "Toggle side panels"},
                {"Ctrl+Shift+L", "Dark / light theme"},
                {"Ctrl+,", "Settings"},
                {"", ""},
                {"Quick column (properties panel)", "email varchar(255) nn uq"},
                {"", "price numeric(10,2) default 0"},
                {"", "user_id -> users   (adds FK)"},
        };
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) sb.append(String.format("%-34s %s%n", r[0], r[1]));
        TextArea ta = new TextArea(sb.toString());
        ta.setEditable(false);
        ta.setStyle("-fx-font-family: \"" + Theme.MONO + "\";");
        ta.setPrefSize(620, 560);
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.NONE, "",
                javafx.scene.control.ButtonType.CLOSE);
        a.initOwner(stage);
        a.setTitle("Keyboard shortcuts");
        a.setHeaderText("Keyboard & mouse");
        a.getDialogPane().setContent(ta);
        Theme.apply(a);
        a.showAndWait();
    }

    // ================================================================== exit, session, recovery

    /** Library projects remember where you were looking, even when nothing else changed. */
    private void persistView(Workspace w) {
        if (!autosaves(w)) return;
        Diagram d = w.editor.diagram();
        if (Math.abs(d.viewX - w.canvas.panX()) > 0.5 || Math.abs(d.viewY - w.canvas.panY()) > 0.5
                || Math.abs(d.zoom - w.canvas.zoom()) > 0.001) writeTo(w, w.editor.file(), true);
    }

    private boolean confirmDiscard(Workspace w) {
        if (!w.editor.isDirty()) return true;
        if (autosaves(w) && writeTo(w, w.editor.file(), true)) return true;
        for (Map.Entry<Tab, Workspace> e : workspaces.entrySet()) if (e.getValue() == w) tabs.getSelectionModel().select(e.getKey());
        return switch (Dialogs.confirmSave(stage, w.title())) {
            case SAVE -> save(w);
            case DISCARD -> true;
            case CANCEL -> false;
        };
    }

    private void exit() {
        exitThen(null);
    }

    /** Files handed over by a second launch (double-clicked .erd while the app is open). */
    /** Handles MCP tool calls forwarded by "ERD Studio --mcp" (runs them on the FX thread). */
    public java.util.function.Function<String, String> mcpHandler() {
        McpBridge bridge = new McpBridge(this);
        return bridge::handle;
    }

    public void openFromOutside(List<String> files) {
        for (String f : files) {
            Path p = Path.of(f);
            if (Files.isRegularFile(p)) openPath(p);
        }
        if (stage.isIconified()) stage.setIconified(false);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    /** Normal exit; {@code last} runs after everything is saved, right before the app quits. */
    private boolean exitThen(Runnable last) {
        List<String> session = new ArrayList<>();
        String selected = "";
        for (Workspace w : new ArrayList<>(workspaces.values())) {
            if (!confirmDiscard(w)) return false;
            persistView(w);
        }
        for (Map.Entry<Tab, Workspace> e : workspaces.entrySet()) {
            Path f = e.getValue().editor.file();
            if (f != null) {
                session.add(f.toAbsolutePath().toString());
                if (e.getValue() == cur()) selected = f.toAbsolutePath().toString();
            }
            deleteRecovery(e.getValue());
        }
        Settings.put("session", String.join("\n", session));
        Settings.put("sessionSelected", selected);
        savePrefsWindow();
        if (autosave != null) autosave.stop();
        if (last != null) last.run();
        com.erdstudio.SingleInstance.stop();
        Platform.exit();
        return true;
    }

    static Path dataDir() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank() ? Path.of(appData, "ERD Studio") : Path.of(System.getProperty("user.home"), ".erd-studio");
        return base;
    }

    private static Path recoveryDir() {
        return dataDir().resolve("recovery");
    }

    private void startAutosave() {
        if (autosave != null) autosave.stop();
        int sec = Settings.autosaveSeconds();
        if (sec <= 0) return;
        autosave = new Timeline(new KeyFrame(Duration.seconds(sec), e -> writeRecovery()));
        autosave.setCycleCount(Timeline.INDEFINITE);
        autosave.play();
    }

    private void writeRecovery() {
        for (Workspace w : workspaces.values()) {
            if (!w.editor.isDirty()) continue;
            try {
                Files.createDirectories(recoveryDir());
                Diagram d = w.editor.diagram();
                d.viewX = w.canvas.panX();
                d.viewY = w.canvas.panY();
                d.zoom = w.canvas.zoom();
                JsonStore.save(d, recoveryDir().resolve(w.id + ".erd.json"));
                Path f = w.editor.file();
                Files.writeString(recoveryDir().resolve(w.id + ".path"), f == null ? "" : f.toAbsolutePath().toString());
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private void deleteRecovery(Workspace w) {
        try {
            Files.deleteIfExists(recoveryDir().resolve(w.id + ".erd.json"));
            Files.deleteIfExists(recoveryDir().resolve(w.id + ".path"));
        } catch (IOException ignored) {
            // ignore
        }
    }

    /** Offers to restore diagrams that were unsaved when the app last stopped unexpectedly. */
    private void recoverCrashed() {
        Path dir = recoveryDir();
        if (!Files.isDirectory(dir)) return;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".erd.json")).toList();
        } catch (IOException e) {
            return;
        }
        if (files.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (Path p : files) {
            try {
                names.append("• ").append(JsonStore.load(p).name).append('\n');
            } catch (Exception ignored) {
                names.append("• ").append(p.getFileName()).append('\n');
            }
        }
        boolean restore = Dialogs.confirm(stage, "Restore unsaved work?",
                "ERD Studio didn't close properly last time. These diagrams had unsaved changes:\n\n" + names
                        + "\nRestore them? (They open as unsaved tabs.)", "Restore");
        for (Path p : files) {
            if (restore) {
                try {
                    Diagram d = JsonStore.load(p);
                    Path meta = Path.of(p.toString().replace(".erd.json", ".path"));
                    String orig = Files.exists(meta) ? Files.readString(meta).trim() : "";
                    Workspace w = targetForOpen();
                    w.editor.replaceUnsaved(d, orig.isEmpty() ? null : Path.of(orig));
                    Platform.runLater(() -> w.canvas.setView(d.viewX, d.viewY, d.zoom <= 0 ? 1 : d.zoom));
                } catch (Exception ignored) {
                    // unreadable backup
                }
            }
            try {
                Files.deleteIfExists(p);
                Files.deleteIfExists(Path.of(p.toString().replace(".erd.json", ".path")));
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    // ================================================================== prefs helpers

    private FileChooser chooser(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        String dir = Settings.get("lastDir", null);
        if (dir != null && new File(dir).isDirectory()) fc.setInitialDirectory(new File(dir));
        return fc;
    }

    private void rememberDir(File f) {
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null) Settings.put("lastDir", parent.getAbsolutePath());
    }

    private List<String> recent() {
        List<String> out = new ArrayList<>();
        for (String s : Settings.get("recent", "").split("\n")) if (!s.isBlank()) out.add(s);
        return out;
    }

    private void addRecent(Path p) {
        List<String> r = recent();
        String s = p.toAbsolutePath().toString();
        r.remove(s);
        r.add(0, s);
        while (r.size() > MAX_RECENT) r.remove(r.size() - 1);
        Settings.put("recent", String.join("\n", r));
        rebuildRecent();
    }

    private void rebuildRecent() {
        recentMenu.getItems().clear();
        for (String s : recent()) {
            Path p = Path.of(s);
            MenuItem mi = new MenuItem(p.getFileName() + "   —   " + (p.getParent() == null ? "" : p.getParent()));
            mi.setOnAction(e -> {
                if (Files.exists(p)) openPath(p);
                else Dialogs.error(stage, "File not found", s);
            });
            recentMenu.getItems().add(mi);
        }
        if (recentMenu.getItems().isEmpty()) {
            MenuItem none = new MenuItem("No recent files");
            none.setDisable(true);
            recentMenu.getItems().add(none);
        } else {
            recentMenu.getItems().add(new SeparatorMenuItem());
            recentMenu.getItems().add(plain("Clear list", null, () -> { Settings.put("recent", null); rebuildRecent(); }));
        }
    }

    private void restoreWindow() {
        double w = Double.parseDouble(Settings.get("w", "1480")), h = Double.parseDouble(Settings.get("h", "920"));
        stage.setWidth(Math.max(1000, w));
        stage.setHeight(Math.max(640, h));
        if (Boolean.parseBoolean(Settings.get("max", "false"))) stage.setMaximized(true);
    }

    private void savePrefsWindow() {
        Settings.put("max", String.valueOf(stage.isMaximized()));
        if (!stage.isMaximized()) {
            Settings.put("w", String.valueOf(stage.getWidth()));
            Settings.put("h", String.valueOf(stage.getHeight()));
        }
    }

    /** For tests: currently selected workspace. */
    Workspace currentWorkspace() { return cur(); }

    Optional<Workspace> workspace(int i) {
        return i < workspaces.size() ? Optional.of(new ArrayList<>(workspaces.values()).get(i)) : Optional.empty();
    }
}
