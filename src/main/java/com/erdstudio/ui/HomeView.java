package com.erdstudio.ui;

import com.erdstudio.canvas.Thumbnail;
import com.erdstudio.editor.Settings;
import com.erdstudio.io.ProjectLibrary;
import com.erdstudio.io.ProjectLibrary.Project;
import com.erdstudio.io.ProjectLibrary.TrashItem;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Table;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The start screen: a library of projects with previews, search, sort, pins and a trash. */
public final class HomeView extends StackPane {

    /** What the home screen asks the main window to do. */
    public interface Actions {
        void newProject();
        void openProject(Path p);
        void importErdFile();
        void importSchema();
        void importDatabase();
        void openSample();
        void exportErd(Path p);
        boolean isOpen(Path p);
        /** Renames (also when open in a tab); returns the new path or null. */
        Path renameProject(Path p, String newName);
        /** Closes the project's tab if open; false when the user cancelled. */
        boolean closeProject(Path p);
        void openSettings();
    }

    private static final double CARD_W = 264, THUMB_H = 156;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private enum Sort {
        OPENED("Last opened"), EDITED("Last edited"), NAME("Name"), SIZE("Most tables");
        final String label;
        Sort(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    private final Window owner;
    private final Actions actions;
    private final TextField search = new TextField();
    private final ComboBox<Sort> sort = new ComboBox<>();
    private final ToggleButton allTab = new ToggleButton("Projects");
    private final ToggleButton trashTab = new ToggleButton("Trash");
    private final VBox body = new VBox(14);
    private final Label countLabel = new Label();
    private final Label folderLabel = new Label();
    private final HBox toast = new HBox(12);
    private final Label toastText = new Label();
    private final Button toastAction = new Button();
    private SequentialTransition toastAnim;

    public HomeView(Window owner, Actions actions) {
        this.owner = owner;
        this.actions = actions;
        getStyleClass().add("home");

        // ---------- header
        StackPane logo = new StackPane(Icons.icon(Icons.DATABASE, 20));
        logo.getStyleClass().addAll("logo", "home-logo");
        Label title = new Label("Your projects");
        title.getStyleClass().add("home-title");
        countLabel.getStyleClass().add("home-count");
        countLabel.visibleProperty().bind(countLabel.textProperty().isNotEmpty());
        countLabel.managedProperty().bind(countLabel.visibleProperty());
        HBox titleBox = new HBox(12, logo, title, countLabel);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Button newBtn = new Button("New project");
        newBtn.setGraphic(Icons.icon(Icons.PLUS, 15));
        newBtn.getStyleClass().add("primary");
        newBtn.setOnAction(e -> actions.newProject());
        newBtn.setTooltip(new Tooltip("Ctrl+N"));

        MenuButton importBtn = new MenuButton("Import");
        importBtn.setGraphic(Icons.icon(Icons.UPLOAD, 15));
        importBtn.getItems().addAll(
                item("Project file (.erd)…", Icons.FILE, actions::importErdFile),
                item("SQL, Prisma or DBML…", Icons.CODE, actions::importSchema),
                item("From a database (PostgreSQL / MySQL)…", Icons.SERVER, actions::importDatabase),
                new SeparatorMenuItem(),
                item("Sample schema (online shop)", Icons.TABLE, actions::openSample));

        Region hsp = new Region();
        HBox.setHgrow(hsp, Priority.ALWAYS);
        HBox header = new HBox(12, titleBox, hsp, importBtn, newBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("home-header");

        // ---------- toolbar: tabs, search, sort
        ToggleGroup tg = new ToggleGroup();
        allTab.setToggleGroup(tg);
        trashTab.setToggleGroup(tg);
        allTab.setSelected(true);
        tg.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) a.setSelected(true); else refresh(); });
        HBox seg = new HBox(allTab, trashTab);
        seg.getStyleClass().addAll("segmented", "home-seg");

        search.setPromptText("Search projects or table names…");
        search.getStyleClass().add("home-search");
        search.setPrefColumnCount(26);
        search.textProperty().addListener(o -> refresh());
        StackPane searchBox = new StackPane(search, Icons.icon(Icons.SEARCH, 14));
        StackPane.setAlignment(searchBox.getChildren().get(1), Pos.CENTER_LEFT);
        StackPane.setMargin(searchBox.getChildren().get(1), new Insets(0, 0, 0, 11));
        searchBox.setMaxWidth(340);
        HBox.setHgrow(searchBox, Priority.SOMETIMES);

        sort.getItems().setAll(Sort.values());
        try {
            sort.setValue(Sort.valueOf(Settings.get("home.sort", "OPENED")));
        } catch (IllegalArgumentException e) {
            sort.setValue(Sort.OPENED);
        }
        sort.valueProperty().addListener((o, a, b) -> { Settings.put("home.sort", b.name()); refresh(); });
        Label sortL = new Label("Sort");
        sortL.getStyleClass().add("muted");

        Region tsp = new Region();
        HBox.setHgrow(tsp, Priority.ALWAYS);
        HBox toolbar = new HBox(10, seg, tsp, searchBox, sortL, sort);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("home-toolbar");

        // ---------- body
        body.getStyleClass().add("home-body");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("home-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // ---------- footer
        folderLabel.getStyleClass().add("muted");
        Button openFolder = linkButton("Open folder", () -> Desktops.open(ProjectLibrary.get().dir()));
        CheckBox autosave = new CheckBox("Save changes automatically");
        autosave.setSelected(Settings.autosaveProjects());
        autosave.selectedProperty().addListener((o, a, b) -> Settings.setAutosaveProjects(b));
        autosave.setTooltip(new Tooltip("Projects in the library are saved a moment after every change."));
        Button settings = linkButton("Settings", actions::openSettings);
        Region fsp = new Region();
        HBox.setHgrow(fsp, Priority.ALWAYS);
        HBox footer = new HBox(14, Icons.icon(Icons.FOLDER, 14), folderLabel, openFolder, fsp, autosave, settings);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("home-footer");

        VBox page = new VBox(header, toolbar, scroll, footer);
        page.getStyleClass().add("home-page");

        toast.getStyleClass().add("toast");
        toastText.getStyleClass().add("toast-text");
        toastAction.getStyleClass().add("toast-action");
        toast.getChildren().addAll(toastText, toastAction);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 64, 0));

        getChildren().addAll(page, toast);
        Theme.darkProperty().addListener(o -> refresh());
    }

    public void focusSearch() {
        search.requestFocus();
    }

    // ================================================================== content

    public void refresh() {
        ProjectLibrary lib = ProjectLibrary.get();
        folderLabel.setText(lib.dir().toString());
        List<TrashItem> trash = lib.trashItems();
        trashTab.setText(trash.isEmpty() ? "Trash" : "Trash (" + trash.size() + ")");
        body.getChildren().clear();
        if (trashTab.isSelected()) showTrash(trash);
        else showProjects(lib.list());
    }

    private void showProjects(List<Project> all) {
        countLabel.setText(all.isEmpty() ? "" : String.valueOf(all.size()));
        if (all.isEmpty()) {
            body.getChildren().add(emptyState());
            return;
        }
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Project> shown = new ArrayList<>();
        for (Project p : all) if (matches(p, q)) shown.add(p);
        shown.sort(comparator());
        if (shown.isEmpty()) {
            Label none = new Label("No project matches “" + search.getText().trim() + "”.");
            none.getStyleClass().add("home-none");
            body.getChildren().add(none);
            return;
        }
        List<Project> pinned = new ArrayList<>(), rest = new ArrayList<>();
        for (Project p : shown) (p.pinned() ? pinned : rest).add(p);
        if (!pinned.isEmpty()) {
            body.getChildren().addAll(section("PINNED"), grid(pinned));
            if (!rest.isEmpty()) body.getChildren().add(section("ALL PROJECTS"));
        }
        if (!rest.isEmpty()) body.getChildren().add(grid(rest));
    }

    private boolean matches(Project p, String q) {
        if (q.isEmpty()) return true;
        if (p.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        if (p.diagram() != null) for (Table t : p.diagram().tables) if (t.name.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private Comparator<Project> comparator() {
        Comparator<Project> byName = Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT));
        return switch (sort.getValue() == null ? Sort.OPENED : sort.getValue()) {
            case OPENED -> Comparator.comparingLong(Project::lastActivity).reversed().thenComparing(byName);
            case EDITED -> Comparator.comparingLong(Project::modified).reversed().thenComparing(byName);
            case NAME -> byName;
            case SIZE -> Comparator.comparingInt(Project::tables).reversed().thenComparing(byName);
        };
    }

    private FlowPane grid(List<? extends Object> items) {
        FlowPane f = new FlowPane(18, 18);
        f.getStyleClass().add("home-grid");
        for (Object o : items) f.getChildren().add(o instanceof Project p ? card(p) : trashCard((TrashItem) o));
        return f;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("home-section");
        return l;
    }

    private Node card(Project p) {
        StackPane thumb = thumbnail(p.diagram());
        ToggleButton pin = new ToggleButton();
        pin.setGraphic(Icons.icon(Icons.STAR, 15));
        pin.getStyleClass().addAll("icon-button", "card-pin");
        pin.setSelected(p.pinned());
        pin.setTooltip(new Tooltip(p.pinned() ? "Unpin" : "Pin to top"));
        pin.setFocusTraversable(false);
        pin.setOnAction(e -> { ProjectLibrary.get().setPinned(p.file(), pin.isSelected()); refresh(); });
        StackPane.setAlignment(pin, Pos.TOP_RIGHT);
        StackPane.setMargin(pin, new Insets(8));
        thumb.getChildren().add(pin);
        if (actions.isOpen(p.file())) {
            Label open = new Label("Open");
            open.getStyleClass().add("card-badge");
            StackPane.setAlignment(open, Pos.TOP_LEFT);
            StackPane.setMargin(open, new Insets(10));
            thumb.getChildren().add(open);
        }

        Label name = new Label(p.name());
        name.getStyleClass().add("card-name");
        name.setMaxWidth(CARD_W - 60);
        Tooltip.install(name, new Tooltip(p.name()));
        Label meta = new Label(p.broken() ? "Could not read this file"
                : plural(p.tables(), "table") + "  ·  " + plural(p.relations(), "relation") + "  ·  " + p.diagram().dialect.label);
        meta.getStyleClass().add(p.broken() ? "card-error" : "card-meta");
        Label time = new Label("Edited " + ago(p.modified()));
        time.getStyleClass().add("card-time");

        MenuButton more = new MenuButton();
        more.setGraphic(Icons.icon(Icons.MORE, 16));
        more.getStyleClass().addAll("icon-button", "overflow-button", "card-more");
        more.setFocusTraversable(false);
        more.getItems().addAll(menu(p));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox nameRow = new HBox(6, name, sp, more);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        VBox info = new VBox(3, nameRow, meta, time);
        info.getStyleClass().add("card-info");
        VBox card = new VBox(thumb, info);
        card.getStyleClass().add("project-card");
        card.setPrefWidth(CARD_W);
        card.setMaxWidth(CARD_W);
        card.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !p.broken()) actions.openProject(p.file());
        });
        ContextMenu cm = new ContextMenu();
        cm.getItems().addAll(menu(p));
        card.setOnContextMenuRequested(e -> { cm.show(card, e.getScreenX(), e.getScreenY()); e.consume(); });
        return card;
    }

    private List<MenuItem> menu(Project p) {
        List<MenuItem> items = new ArrayList<>();
        if (!p.broken()) items.add(item("Open", Icons.FOLDER, () -> actions.openProject(p.file())));
        items.add(item("Rename…", Icons.EDIT, () -> rename(p)));
        if (!p.broken()) items.add(item("Duplicate", Icons.COPY, () -> duplicate(p)));
        items.add(item(p.pinned() ? "Unpin" : "Pin to top", Icons.STAR, () -> { ProjectLibrary.get().setPinned(p.file(), !p.pinned()); refresh(); }));
        items.add(new SeparatorMenuItem());
        if (!p.broken()) items.add(item("Export project file (.erd)…", Icons.DOWNLOAD, () -> actions.exportErd(p.file())));
        items.add(item("Show in folder", Icons.EXTERNAL, () -> Desktops.reveal(p.file())));
        items.add(new SeparatorMenuItem());
        MenuItem del = item("Move to trash", Icons.TRASH, () -> trash(p));
        del.getStyleClass().add("danger-item");
        items.add(del);
        return items;
    }

    private void rename(Project p) {
        Dialogs.prompt(owner, "Rename project", "Name", p.name()).ifPresent(n -> {
            if (n.isBlank() || n.equals(p.name())) return;
            if (actions.renameProject(p.file(), n.trim()) != null) refresh();
        });
    }

    private void duplicate(Project p) {
        try {
            ProjectLibrary.get().duplicate(p.file());
            refresh();
            showToast("Duplicated “" + p.name() + "”", null, null);
        } catch (Exception ex) {
            Dialogs.error(owner, "Could not duplicate", ex.getMessage());
        }
    }

    private void trash(Project p) {
        if (!actions.closeProject(p.file())) return;
        try {
            ProjectLibrary lib = ProjectLibrary.get();
            lib.trash(p.file());
            refresh();
            showToast("“" + p.name() + "” moved to trash", "Undo", () -> {
                for (TrashItem t : lib.trashItems()) {
                    if (t.name().equals(p.name())) {
                        try {
                            lib.restore(t.file());
                        } catch (Exception ignored) {
                            // still in trash
                        }
                        break;
                    }
                }
                refresh();
            });
        } catch (Exception ex) {
            Dialogs.error(owner, "Could not move to trash", ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ trash view

    private void showTrash(List<TrashItem> items) {
        countLabel.setText("");
        if (items.isEmpty()) {
            VBox box = new VBox(10, Icons.icon(Icons.TRASH, 34, "empty-icon"), bigLabel("Trash is empty"),
                    mutedLabel("Deleted projects stay here for 30 days, then they are removed for good."));
            box.setAlignment(Pos.CENTER);
            box.getStyleClass().add("home-empty");
            body.getChildren().add(box);
            return;
        }
        Button empty = new Button("Empty trash");
        empty.setGraphic(Icons.icon(Icons.TRASH, 14));
        empty.getStyleClass().add("danger-ghost");
        empty.setOnAction(e -> {
            if (!Dialogs.confirm(owner, "Empty trash?", items.size() + " project(s) will be deleted permanently. This cannot be undone.", "Delete forever")) return;
            try {
                ProjectLibrary.get().emptyTrash();
            } catch (Exception ex) {
                Dialogs.error(owner, "Could not empty trash", ex.getMessage());
            }
            refresh();
        });
        Label hint = mutedLabel("Projects in the trash are deleted automatically after 30 days.");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(10, hint, sp, empty);
        top.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().addAll(top, grid(items));
    }

    private Node trashCard(TrashItem t) {
        StackPane thumb = thumbnail(t.diagram());
        thumb.setOpacity(0.55);
        Label name = new Label(t.name());
        name.getStyleClass().add("card-name");
        name.setMaxWidth(CARD_W - 32);
        Label time = new Label("Deleted " + ago(t.deletedAt()));
        time.getStyleClass().add("card-time");
        Button restore = new Button("Restore");
        restore.setGraphic(Icons.icon(Icons.RESTORE, 13));
        restore.getStyleClass().add("small");
        restore.setOnAction(e -> {
            try {
                ProjectLibrary.get().restore(t.file());
                refresh();
                showToast("Restored “" + t.name() + "”", null, null);
            } catch (Exception ex) {
                Dialogs.error(owner, "Could not restore", ex.getMessage());
            }
        });
        Button del = new Button("Delete forever");
        del.getStyleClass().addAll("small", "danger-ghost");
        del.setOnAction(e -> {
            if (!Dialogs.confirm(owner, "Delete permanently?", "“" + t.name() + "” will be deleted permanently. This cannot be undone.", "Delete forever")) return;
            try {
                ProjectLibrary.get().deletePermanently(t.file());
            } catch (Exception ex) {
                Dialogs.error(owner, "Could not delete", ex.getMessage());
            }
            refresh();
        });
        HBox buttons = new HBox(8, restore, del);
        VBox info = new VBox(4, name, time, buttons);
        info.getStyleClass().add("card-info");
        VBox card = new VBox(thumb, info);
        card.getStyleClass().addAll("project-card", "trash-card");
        card.setPrefWidth(CARD_W);
        card.setMaxWidth(CARD_W);
        return card;
    }

    // ------------------------------------------------------------------ pieces

    private StackPane thumbnail(Diagram d) {
        Canvas c = Thumbnail.render(d, CARD_W, THUMB_H, Theme.isDark());
        StackPane box = new StackPane(c);
        box.getStyleClass().add("card-thumb");
        box.setMinSize(CARD_W, THUMB_H);
        box.setPrefSize(CARD_W, THUMB_H);
        box.setMaxSize(CARD_W, THUMB_H);
        Rectangle clip = new Rectangle(CARD_W, THUMB_H);
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        c.setClip(clip);
        if (d != null && d.tables.isEmpty() && d.notes.isEmpty() && d.areas.isEmpty()) {
            Label l = new Label("Empty diagram");
            l.getStyleClass().add("card-empty");
            box.getChildren().add(l);
        }
        return box;
    }

    private Node emptyState() {
        Button create = new Button("New project");
        create.setGraphic(Icons.icon(Icons.PLUS, 15));
        create.getStyleClass().add("primary");
        create.setOnAction(e -> actions.newProject());
        Button imp = new Button("Import SQL / Prisma / DBML");
        imp.setGraphic(Icons.icon(Icons.UPLOAD, 15));
        imp.setOnAction(e -> actions.importSchema());
        Button sample = new Button("Open sample");
        sample.setGraphic(Icons.icon(Icons.TABLE, 15));
        sample.setOnAction(e -> actions.openSample());
        HBox buttons = new HBox(10, create, imp, sample);
        buttons.setAlignment(Pos.CENTER);
        VBox box = new VBox(12, Icons.icon(Icons.DATABASE, 40, "empty-icon"), bigLabel("No projects yet"),
                mutedLabel("Create a diagram from scratch, import an existing schema, or start from the sample."), buttons);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("home-empty");
        return box;
    }

    private static Label bigLabel(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("home-empty-title");
        return l;
    }

    private static Label mutedLabel(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }

    private static MenuItem item(String text, String icon, Runnable r) {
        MenuItem mi = new MenuItem(text);
        if (icon != null) mi.setGraphic(Icons.icon(icon, 14));
        mi.setOnAction(e -> r.run());
        return mi;
    }

    private static Button linkButton(String text, Runnable r) {
        Button b = new Button(text);
        b.getStyleClass().add("link-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> r.run());
        return b;
    }

    static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    static String ago(long millis) {
        if (millis <= 0) return "—";
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        LocalDate d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (d.equals(today.minusDays(1))) return "yesterday";
        long days = today.toEpochDay() - d.toEpochDay();
        if (days < 7) return days + " days ago";
        return "on " + DATE.format(d);
    }

    public void showToast(String text, String actionText, Runnable action) {
        toastText.setText(text);
        toastAction.setVisible(actionText != null);
        toastAction.setManaged(actionText != null);
        toastAction.setText(actionText == null ? "" : actionText);
        toastAction.setOnAction(e -> {
            if (action != null) action.run();
            toast.setOpacity(0);
            toast.setMouseTransparent(true);
        });
        toast.setMouseTransparent(actionText == null);
        if (toastAnim != null) toastAnim.stop();
        FadeTransition in = new FadeTransition(Duration.millis(160), toast);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.millis(actionText == null ? 2200 : 7000));
        FadeTransition out = new FadeTransition(Duration.millis(300), toast);
        out.setToValue(0);
        toastAnim = new SequentialTransition(in, hold, out);
        toastAnim.setOnFinished(e -> toast.setMouseTransparent(true));
        toastAnim.play();
    }
}
