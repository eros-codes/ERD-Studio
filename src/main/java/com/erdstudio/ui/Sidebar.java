package com.erdstudio.ui;

import com.erdstudio.canvas.DiagramCanvas;
import com.erdstudio.editor.Editor;
import com.erdstudio.model.Area;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Relation;
import com.erdstudio.model.Table;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.Locale;

/** Left panel: searchable list of tables and relations. */
public final class Sidebar extends VBox {
    private final Editor editor;
    private final DiagramCanvas canvas;
    private final TextField search = new TextField();
    private final ListView<Table> tables = new ListView<>();
    private final ListView<Relation> relations = new ListView<>();
    private final ListView<Area> areas = new ListView<>();
    private final ToggleButton areasTab = new ToggleButton("Areas");
    private final Label count = new Label();
    private final ToggleButton tablesTab = new ToggleButton("Tables");
    private final ToggleButton relationsTab = new ToggleButton("Relations");
    private final StackPane lists = new StackPane();
    private boolean syncing;
    private Runnable onClose;

    public void setOnClose(Runnable r) { onClose = r; }

    public Sidebar(Editor editor, DiagramCanvas canvas, Runnable addTable) {
        this.editor = editor;
        this.canvas = canvas;
        getStyleClass().add("sidebar");
        setMinWidth(0);

        Label title = new Label("EXPLORER");
        title.getStyleClass().add("panel-title");
        count.getStyleClass().add("count-badge");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button add = new Button();
        add.setGraphic(Icons.icon(Icons.PLUS, 16));
        add.getStyleClass().addAll("icon-button");
        add.setTooltip(new Tooltip("Add table (T)"));
        add.setOnAction(e -> addTable.run());
        HBox header = new HBox(8, title, count, sp, add, Workspace.panelCloseButton(() -> { if (onClose != null) onClose.run(); }));
        header.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("Search tables & columns…");
        HBox searchBox = new HBox(search);
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener(o -> refresh());

        ToggleGroup g = new ToggleGroup();
        tablesTab.setToggleGroup(g);
        relationsTab.setToggleGroup(g);
        areasTab.setToggleGroup(g);
        tablesTab.setSelected(true);
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { a.setSelected(true); return; }
            tables.setVisible(b == tablesTab);
            relations.setVisible(b == relationsTab);
            areas.setVisible(b == areasTab);
        });
        tablesTab.setMaxWidth(Double.MAX_VALUE);
        relationsTab.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tablesTab, Priority.ALWAYS);
        HBox.setHgrow(relationsTab, Priority.ALWAYS);
        areasTab.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(areasTab, Priority.ALWAYS);
        HBox seg = new HBox(tablesTab, relationsTab, areasTab);
        seg.getStyleClass().add("segmented");

        tables.setCellFactory(v -> new TableCell());
        relations.setCellFactory(v -> new RelationCell());
        relations.setVisible(false);
        areas.setVisible(false);
        areas.setCellFactory(v -> new ListCell<>() {
            private final Circle dot = new Circle(5);
            private final Label name = new Label();
            private final HBox row = new HBox(dot, name);
            {
                name.getStyleClass().add("item-name");
                row.getStyleClass().add("item-row");
            }
            @Override protected void updateItem(Area a, boolean empty) {
                super.updateItem(a, empty);
                if (empty || a == null) { setGraphic(null); return; }
                dot.setFill(Color.web(a.color));
                name.setText(a.name);
                setGraphic(row);
            }
        });
        areas.setPlaceholder(placeholder("Select tables and press Ctrl+G\nto group them into an area"));
        areas.getSelectionModel().selectedItemProperty().addListener((o, a, ar) -> {
            if (syncing || ar == null) return;
            editor.select(ar.id, false);
            canvas.centerOn(ar.id);
        });
        tables.setPlaceholder(placeholder("No tables yet"));
        relations.setPlaceholder(placeholder("Drag from a column's dot to\nanother column to relate them"));
        lists.getChildren().addAll(tables, relations, areas);
        VBox.setVgrow(lists, Priority.ALWAYS);

        tables.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            if (syncing || t == null) return;
            editor.select(t.id, false);
            canvas.centerOn(t.id);
        });
        relations.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> {
            if (syncing || r == null) return;
            editor.select(r.id, false);
            canvas.centerOn(r.fromTableId);
        });
        tables.setOnMouseClicked(e -> {
            Table t = tables.getSelectionModel().getSelectedItem();
            if (t != null && e.getClickCount() == 2) canvas.startRename(t.id);
        });

        getChildren().addAll(header, searchBox, seg, lists);

        editor.addListener((change, source) -> {
            if (change == Editor.Change.SELECTION) syncSelection();
            else refresh();
        });
        refresh();
    }

    public void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    private Label placeholder(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setStyle("-fx-text-alignment: center;");
        return l;
    }

    private void refresh() {
        Diagram d = editor.diagram();
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        syncing = true;
        try {
            tables.getItems().setAll(d.tables.stream()
                    .filter(t -> q.isEmpty() || t.name.toLowerCase(Locale.ROOT).contains(q)
                            || t.columns.stream().anyMatch(c -> c.name.toLowerCase(Locale.ROOT).contains(q)))
                    .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                    .toList());
            relations.getItems().setAll(d.relations.stream()
                    .filter(r -> q.isEmpty() || describe(r).toLowerCase(Locale.ROOT).contains(q))
                    .toList());
            areas.getItems().setAll(d.areas.stream()
                    .filter(a -> q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q))
                    .toList());
        } finally {
            syncing = false;
        }
        count.setText(String.valueOf(d.tables.size()));
        relationsTab.setText("Links");
        tablesTab.setText("Tables");
        areasTab.setText("Areas");
        relationsTab.setTooltip(new javafx.scene.control.Tooltip(d.relations.size() + " relations"));
        areasTab.setTooltip(new javafx.scene.control.Tooltip(d.areas.size() + " areas"));
        syncSelection();
    }

    private void syncSelection() {
        syncing = true;
        try {
            Object o = editor.singleSelection();
            if (o instanceof Table t) {
                tables.getSelectionModel().select(t);
                tables.scrollTo(t);
            } else tables.getSelectionModel().clearSelection();
            if (o instanceof Relation r) relations.getSelectionModel().select(r);
            else relations.getSelectionModel().clearSelection();
            if (o instanceof Area a) areas.getSelectionModel().select(a);
            else areas.getSelectionModel().clearSelection();
        } finally {
            syncing = false;
        }
    }

    private String describe(Relation r) {
        Diagram d = editor.diagram();
        Table f = d.table(r.fromTableId), t = d.table(r.toTableId);
        if (f == null || t == null) return "";
        var fc = f.column(r.fromColumnId);
        var tc = t.column(r.toColumnId);
        return f.name + "." + (fc == null ? "?" : fc.name) + " → " + t.name + "." + (tc == null ? "?" : tc.name);
    }

    private final class TableCell extends ListCell<Table> {
        private final Circle dot = new Circle(5);
        private final Label name = new Label();
        private final Label sub = new Label();
        private final HBox row;

        TableCell() {
            name.getStyleClass().add("item-name");
            sub.getStyleClass().add("item-sub");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            row = new HBox(dot, name, sp, sub);
            row.getStyleClass().add("item-row");
            name.setMaxWidth(170);
        }

        @Override
        protected void updateItem(Table t, boolean empty) {
            super.updateItem(t, empty);
            if (empty || t == null) {
                setGraphic(null);
                return;
            }
            dot.setFill(Color.web(t.color));
            name.setText(t.name);
            sub.setText(String.valueOf(t.columns.size()));
            setGraphic(row);
        }
    }

    private final class RelationCell extends ListCell<Relation> {
        private final Label text = new Label();
        private final Label sub = new Label();
        private final VBox box = new VBox(2, text, sub);
        private final HBox row;

        RelationCell() {
            text.getStyleClass().add("item-name");
            text.setStyle("-fx-font-size: 12px;");
            sub.getStyleClass().add("item-sub");
            row = new HBox(Icons.icon(Icons.LINK, 14, "accent-icon"), box);
            row.getStyleClass().add("item-row");
        }

        @Override
        protected void updateItem(Relation r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setGraphic(null);
                return;
            }
            text.setText(describe(r));
            sub.setText(r.type.label + (r.onDelete != null && r.onDelete != com.erdstudio.model.RefAction.NO_ACTION
                    ? " · on delete " + r.onDelete.sql.toLowerCase(Locale.ROOT) : ""));
            setGraphic(row);
        }
    }
}
