package com.erdstudio.ui;

import com.erdstudio.editor.Editor;
import com.erdstudio.io.MigrationGenerator;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.Version;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Named schema versions + migration SQL between any two of them (or the current design). */
public final class VersionsDialog extends Dialog<Void> {

    /** null = the current design */
    private record Point(Version v) {
        @Override public String toString() { return v == null ? "Current design" : v.name + "  (" + v.createdAt + ")"; }
    }

    private final Editor editor;
    private final ListView<Version> list = new ListView<>();
    private final ComboBox<Point> from = new ComboBox<>(), to = new ComboBox<>();
    private final ComboBox<Dialect> dialect = new ComboBox<>();
    private final TextArea sql = new TextArea();
    private final Window owner;

    public VersionsDialog(Window owner, Editor editor) {
        this.owner = owner;
        this.editor = editor;
        initOwner(owner);
        setTitle("Versions & migrations");
        setHeaderText("Versions & migrations");
        setResizable(true);
        Theme.apply(this);

        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Version item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }
                Label name = new Label(item.name);
                name.getStyleClass().add("item-name");
                name.setStyle("-fx-font-weight: bold;");
                Label sub = new Label(item.createdAt + (item.note.isBlank() ? "" : "  ·  " + item.note));
                sub.getStyleClass().add("item-sub");
                VBox box = new VBox(2, name, sub);
                box.getStyleClass().add("item-row");
                setGraphic(box);
                setText(null);
            }
        });
        list.setPrefWidth(300);
        list.setPlaceholder(hint("No versions yet.\nSave one before a big change; then you can\ngenerate the ALTER script from it."));
        VBox.setVgrow(list, Priority.ALWAYS);

        Button saveBtn = new Button("Save current as version…");
        saveBtn.getStyleClass().add("primary");
        saveBtn.setGraphic(Icons.icon(Icons.PLUS, 15));
        saveBtn.setMaxWidth(Double.MAX_VALUE);
        saveBtn.setOnAction(e -> Dialogs.prompt(owner(), "Save version", "Name (e.g. v1.2 – before payments)",
                "Version " + (editor.diagram().versions.size() + 1)).ifPresent(n -> {
            editor.saveVersion(n, "");
            refresh();
            list.getSelectionModel().selectLast();
        }));
        Button restore = new Button("Restore");
        restore.setOnAction(e -> {
            Version v = list.getSelectionModel().getSelectedItem();
            if (v != null && Dialogs.confirm(owner(), "Restore version", "Replace the current design with \"" + v.name
                    + "\"? You can undo this with Ctrl+Z.", "Restore")) {
                editor.restoreVersion(v);
                refresh();
            }
        });
        Button rename = new Button("Rename");
        rename.setOnAction(e -> {
            Version v = list.getSelectionModel().getSelectedItem();
            if (v != null) Dialogs.prompt(owner(), "Rename version", "Name", v.name).ifPresent(n -> { editor.renameVersion(v, n); refresh(); });
        });
        Button delete = new Button("Delete");
        delete.getStyleClass().add("danger");
        delete.setOnAction(e -> {
            Version v = list.getSelectionModel().getSelectedItem();
            if (v != null && Dialogs.confirm(owner(), "Delete version", "Delete \"" + v.name + "\"?", "Delete")) {
                editor.deleteVersion(v);
                refresh();
            }
        });
        restore.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        rename.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        HBox actions = new HBox(6, restore, rename, delete);
        Label lt = new Label("SAVED VERSIONS");
        lt.getStyleClass().add("section-title");
        VBox left = new VBox(10, lt, saveBtn, list, actions);
        left.setPrefWidth(310);

        // migration side
        StringConverter<Point> conv = new StringConverter<>() {
            @Override public String toString(Point p) { return p == null ? "" : p.toString(); }
            @Override public Point fromString(String s) { return null; }
        };
        from.setConverter(conv);
        to.setConverter(conv);
        dialect.getItems().setAll(Dialect.values());
        dialect.setValue(editor.diagram().dialect);
        sql.setEditable(false);
        sql.getStyleClass().add("code-area");
        sql.setStyle("-fx-font-family: \"" + Theme.MONO + "\";");
        sql.setPrefSize(640, 480);
        VBox.setVgrow(sql, Priority.ALWAYS);
        from.valueProperty().addListener(o -> generate());
        to.valueProperty().addListener(o -> generate());
        dialect.valueProperty().addListener(o -> generate());

        Label status = new Label();
        status.getStyleClass().add("hint");
        Button copy = new Button("Copy");
        copy.setGraphic(Icons.icon(Icons.COPY, 15));
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(sql.getText());
            Clipboard.getSystemClipboard().setContent(cc);
            status.setText("Copied");
        });
        Button saveSql = new Button("Save .sql…");
        saveSql.getStyleClass().add("primary");
        saveSql.setGraphic(Icons.icon(Icons.DOWNLOAD, 15));
        saveSql.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save migration");
            fc.setInitialFileName("migration-" + java.time.LocalDate.now() + ".sql");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL", "*.sql"));
            File f = fc.showSaveDialog(owner());
            if (f == null) return;
            try {
                Files.writeString(f.toPath(), sql.getText(), StandardCharsets.UTF_8);
                status.setText("Saved " + f.getName());
            } catch (IOException ex) {
                status.setText("Could not save: " + ex.getMessage());
            }
        });
        Label fl = new Label("From");
        fl.getStyleClass().add("field-label");
        Label tl = new Label("to");
        tl.getStyleClass().add("field-label");
        HBox pick = new HBox(8, fl, from, tl, to, dialect);
        pick.setAlignment(Pos.CENTER_LEFT);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bottom = new HBox(8, status, sp, copy, saveSql);
        bottom.setAlignment(Pos.CENTER_LEFT);
        Label mt = new Label("MIGRATION SQL");
        mt.getStyleClass().add("section-title");
        VBox right = new VBox(10, mt, pick, sql, bottom);
        HBox.setHgrow(right, Priority.ALWAYS);

        getDialogPane().setContent(new HBox(18, left, right));
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        refresh();
    }

    private Window owner() {
        return getDialogPane().getScene() == null ? owner : getDialogPane().getScene().getWindow();
    }

    private Label hint(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("hint");
        return l;
    }

    private void refresh() {
        List<Version> vs = editor.diagram().versions;
        list.getItems().setAll(vs);
        List<Point> pts = new ArrayList<>();
        for (Version v : vs) pts.add(new Point(v));
        pts.add(new Point(null));
        Point oldFrom = from.getValue(), oldTo = to.getValue();
        from.getItems().setAll(pts);
        to.getItems().setAll(pts);
        from.setValue(find(pts, oldFrom, vs.isEmpty() ? pts.get(pts.size() - 1) : pts.get(pts.size() - 2)));
        to.setValue(find(pts, oldTo, pts.get(pts.size() - 1)));
        generate();
    }

    private static Point find(List<Point> pts, Point old, Point def) {
        if (old != null) for (Point p : pts) if (p.v == old.v || (p.v != null && old.v != null && p.v.id.equals(old.v.id))) return p;
        return def;
    }

    private void generate() {
        Point a = from.getValue(), b = to.getValue();
        if (a == null || b == null || dialect.getValue() == null) return;
        if (editor.diagram().versions.isEmpty()) {
            sql.setText("-- Save a version first (left). Later, compare it with the current design to get the ALTER script.");
            return;
        }
        Diagram da = a.v == null ? editor.currentSchema() : Editor.versionDiagram(a.v);
        Diagram db = b.v == null ? editor.currentSchema() : Editor.versionDiagram(b.v);
        sql.setText(MigrationGenerator.generate(da, db, dialect.getValue(), a.toString(), b.toString()));
    }
}
