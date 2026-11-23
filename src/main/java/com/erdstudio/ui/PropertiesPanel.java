package com.erdstudio.ui;

import com.erdstudio.canvas.DiagramCanvas;
import com.erdstudio.editor.Editor;
import javafx.scene.control.MenuItem;
import com.erdstudio.model.Area;
import com.erdstudio.model.Column;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import com.erdstudio.model.Note;
import com.erdstudio.model.Palette;
import com.erdstudio.model.RefAction;
import com.erdstudio.model.Relation;
import com.erdstudio.model.RelationType;
import com.erdstudio.model.Table;
import com.erdstudio.model.TableIndex;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Right panel: context-sensitive editor for the current selection. */
public final class PropertiesPanel extends ScrollPane {
    private final Editor editor;
    private final DiagramCanvas canvas;
    private final VBox content = new VBox();
    private final Set<String> expandedColumns = new HashSet<>();
    private String focusColumnId;
    private boolean focusQuickAdd;

    public PropertiesPanel(Editor editor, DiagramCanvas canvas) {
        this.editor = editor;
        this.canvas = canvas;
        getStyleClass().add("props");
        content.getStyleClass().add("props-content");
        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setPrefWidth(362);
        setMinWidth(300);

        editor.addListener((change, source) -> {
            if (change == Editor.Change.CONTENT && (source == this || focusInside())) return;
            rebuild();
        });
        rebuild();
    }

    private boolean focusInside() {
        if (getScene() == null) return false;
        Node f = getScene().getFocusOwner();
        while (f != null) {
            if (f == this) return true;
            f = f.getParent();
        }
        return false;
    }

    public void rebuild() {
        double scroll = getVvalue();
        content.getChildren().clear();
        Object single = editor.singleSelection();
        if (single instanceof Table t) buildTable(t);
        else if (single instanceof Relation r) buildRelation(r);
        else if (single instanceof Note n) buildNote(n);
        else if (single instanceof Area a) buildArea(a);
        else if (editor.selection().size() > 1) buildMulti();
        else buildDiagram();
        Platform.runLater(() -> setVvalue(scroll));
    }

    // ------------------------------------------------------------------ builders

    private void buildDiagram() {
        Diagram d = editor.diagram();
        content.getChildren().add(heading("Diagram", null, null));

        TextField name = text(d.name, v -> d.name = v);
        ComboBox<Dialect> dialect = new ComboBox<>();
        dialect.getItems().setAll(Dialect.values());
        dialect.setValue(d.dialect);
        dialect.setMaxWidth(Double.MAX_VALUE);
        dialect.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            d.dialect = b;
            editor.commit(this);
        });
        content.getChildren().add(section("GENERAL", null, field("Name", name), field("Database", dialect)));

        int cols = 0;
        for (Table t : d.tables) cols += t.columns.size();
        GridPane stats = new GridPane();
        stats.setHgap(12);
        stats.setVgap(6);
        addStat(stats, 0, "Tables", d.tables.size());
        addStat(stats, 1, "Columns", cols);
        addStat(stats, 2, "Relations", d.relations.size());
        addStat(stats, 3, "Notes", d.notes.size());
        content.getChildren().add(section("OVERVIEW", null, stats));

        VBox keys = new VBox(6);
        String[][] rows = {
                {"Command palette", "Ctrl + K"},
                {"Double-click canvas", "New table"},
                {"T  /  N", "New table / note"},
                {"Drag column dot", "Create relation"},
                {"Right-drag", "Pan"},
                {"Wheel / pinch", "Zoom"},
                {"Drag empty area", "Box select"},
                {"Ctrl + I", "Import SQL"},
                {"Ctrl + E", "Export SQL"},
                {"Ctrl + L", "Tidy up"},
                {"Ctrl + Shift + E", "Screenshot"},
                {"Ctrl + G", "Group into area"},
                {"Ctrl + D", "Duplicate"},
                {"Enter / F2", "Rename"},
        };
        for (String[] r : rows) {
            Label k = new Label(r[0]);
            k.getStyleClass().add("kbd");
            Label v = new Label(r[1]);
            v.getStyleClass().add("hint");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox row = new HBox(8, v, sp, k);
            row.setAlignment(Pos.CENTER_LEFT);
            keys.getChildren().add(row);
        }
        content.getChildren().add(section("SHORTCUTS", null, keys));
    }

    private void addStat(GridPane g, int row, String label, int value) {
        Label l = new Label(label);
        l.getStyleClass().add("hint");
        Label v = new Label(String.valueOf(value));
        v.setStyle("-fx-font-weight: bold;");
        g.add(l, 0, row);
        g.add(v, 1, row);
    }

    private void buildTable(Table t) {
        Diagram d = editor.diagram();
        content.getChildren().add(heading("Table", t.color, editor::deleteSelection));

        TextField name = text(t.name, v -> t.name = v);
        name.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) canvas.requestFocus(); });
        TextArea comment = area(t.comment, v -> t.comment = v, "Describe what this table stores…");
        content.getChildren().add(section("GENERAL", null,
                field("Name", name), field("Color", tableSwatches(List.of(t), t.color)), field("Comment", comment)));

        // columns
        Button addCol = smallButton("Add", Icons.PLUS, () -> {
            Column c = editor.addColumn(t);
            focusColumnId = c.id;
            rebuild();
        });
        VBox cols = new VBox(6);
        Set<String> fk = new HashSet<>();
        for (Relation r : d.relations) fk.add(r.fromColumnId);
        for (int i = 0; i < t.columns.size(); i++) cols.getChildren().add(columnCard(t, t.columns.get(i), i, fk.contains(t.columns.get(i).id)));
        TextField quick = new TextField();
        quick.setPromptText("Quick add:  email varchar(255) nn uq   ·   user_id -> users");
        quick.getStyleClass().add("compact");
        quick.setStyle("-fx-font-family: \"" + Theme.MONO + "\";");
        quick.setTooltip(new javafx.scene.control.Tooltip("name [type] [pk nn uq ai null] [default <value>] [-> table]\n"
                + "Type is guessed from the name when omitted (created_at → timestamp, is_x → boolean, …).\n"
                + "-> table adds a foreign key to that table's primary key."));
        quick.setOnAction(e -> {
            if (editor.quickAddColumn(t, quick.getText()) != null) {
                focusQuickAdd = true;
                rebuild();
            }
        });
        if (focusQuickAdd) {
            focusQuickAdd = false;
            Platform.runLater(quick::requestFocus);
        }
        content.getChildren().add(section("COLUMNS  " + t.columns.size(), addCol, cols, quick));

        // indexes
        Button addIdx = smallButton("Add", Icons.PLUS, () -> {
            TableIndex idx = new TableIndex();
            if (!t.columns.isEmpty()) idx.columnIds.add(t.columns.get(t.columns.size() > 1 ? 1 : 0).id);
            t.indexes.add(idx);
            editor.commit(this);
            rebuild();
        });
        VBox idxBox = new VBox(6);
        for (TableIndex idx : t.indexes) idxBox.getChildren().add(indexCard(t, idx));
        if (t.indexes.isEmpty()) idxBox.getChildren().add(hint("No indexes. Primary keys and UNIQUE columns are indexed automatically."));
        content.getChildren().add(section("INDEXES  " + t.indexes.size(), addIdx, idxBox));

        // relations
        VBox rels = new VBox(6);
        MenuButton addFk = new MenuButton("Add foreign key to…");
        addFk.setGraphic(Icons.icon(Icons.LINK, 14));
        addFk.getStyleClass().add("small");
        for (Table other : d.tables) {
            if (other.primaryKey().isEmpty()) continue;
            MenuItem mi = new MenuItem(other.name + (other == t ? "  (self)" : ""));
            mi.setGraphic(new Circle(5, Color.web(other.color)));
            mi.setOnAction(e -> { editor.addForeignKey(t, other); rebuild(); });
            addFk.getItems().add(mi);
        }
        for (Relation r : d.relationsOf(t.id)) rels.getChildren().add(relationLink(r));
        if (rels.getChildren().isEmpty()) rels.getChildren().add(hint("Drag the dot at the side of a column onto a column of another table to create a foreign key."));
        content.getChildren().add(section("RELATIONS  " + d.relationsOf(t.id).size(), addFk, rels));
    }

    private Node columnCard(Table t, Column c, int index, boolean isFk) {
        VBox card = new VBox();
        card.getStyleClass().add("column-card");

        TextField name = text(c.name, v -> c.name = v);
        name.getStyleClass().add("compact");
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setPrefWidth(120);
        ComboBox<String> type = typeBox(c);

        HBox row1 = new HBox(6, name, type);
        row1.setAlignment(Pos.CENTER_LEFT);

        ToggleButton pk = chip("PK", c.primaryKey, "Primary key", v -> { c.primaryKey = v; if (v) c.notNull = true; }, true);
        pk.getStyleClass().add("pk-chip");
        ToggleButton nn = chip("NN", c.notNull, "Not null", v -> c.notNull = v, true);
        ToggleButton uq = chip("UQ", c.unique, "Unique", v -> c.unique = v, false);
        ToggleButton ai = chip("AI", c.autoIncrement, "Auto increment / identity", v -> c.autoIncrement = v, false);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button up = iconButton(Icons.CHEVRON_UP, "Move up", () -> move(t, c, -1));
        up.setDisable(index == 0);
        Button down = iconButton(Icons.CHEVRON_DOWN, "Move down", () -> move(t, c, 1));
        down.setDisable(index == t.columns.size() - 1);
        ToggleButton more = new ToggleButton();
        more.setGraphic(Icons.icon(Icons.MORE, 15));
        more.getStyleClass().addAll("icon-button", "small");
        more.setTooltip(new Tooltip("Default value & comment"));
        more.setSelected(expandedColumns.contains(c.id));
        more.setMinWidth(Region.USE_PREF_SIZE);
        Button del = iconButton(Icons.TRASH, "Delete column", () -> {
            editor.deleteColumn(t, c);
            rebuild();
        });
        del.getStyleClass().add("danger-ghost");

        HBox row2 = new HBox(3, pk, nn, uq, ai);
        if (isFk) {
            Label fkl = new Label("FK");
            fkl.getStyleClass().add("count-badge");
            fkl.setMinWidth(Region.USE_PREF_SIZE);
            fkl.setStyle("-fx-text-fill: -c-accent;");
            fkl.setTooltip(new Tooltip("Foreign key (part of a relation)"));
            row2.getChildren().add(fkl);
        }
        row2.getChildren().addAll(sp, up, down, more, del);
        row2.setAlignment(Pos.CENTER_LEFT);

        TextField def = text(c.defaultValue, v -> c.defaultValue = v);
        def.setPromptText("e.g. 0, 'draft', now()");
        def.getStyleClass().add("compact");
        TextField com = text(c.comment, v -> c.comment = v);
        com.setPromptText("Column comment");
        com.getStyleClass().add("compact");
        VBox extra = new VBox(6, field("Default", def), field("Comment", com));
        extra.setVisible(more.isSelected());
        extra.setManaged(more.isSelected());
        more.selectedProperty().addListener((o, a, b) -> {
            extra.setVisible(b);
            extra.setManaged(b);
            if (b) expandedColumns.add(c.id);
            else expandedColumns.remove(c.id);
        });

        card.getChildren().addAll(row1, row2, extra);
        card.setSpacing(7);
        if (c.id.equals(focusColumnId)) {
            focusColumnId = null;
            Platform.runLater(() -> { name.requestFocus(); name.selectAll(); });
        }
        return card;
    }

    private ComboBox<String> typeBox(Column c) {
        ComboBox<String> type = new ComboBox<>();
        type.setEditable(true);
        type.getItems().setAll(editor.diagram().dialect.types);
        type.setValue(c.type);
        type.setPrefWidth(132);
        type.setMinWidth(110);
        type.getStyleClass().add("compact");
        type.setVisibleRowCount(12);
        type.getEditor().textProperty().addListener((o, a, b) -> {
            if (b == null || b.equals(c.type)) return;
            c.type = b.trim();
            editor.changed(this);
        });
        type.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            c.type = b.trim();
            editor.commit(this);
        });
        type.getEditor().focusedProperty().addListener((o, a, b) -> { if (!b) editor.commit(this); });
        return type;
    }

    private void move(Table t, Column c, int delta) {
        int i = t.columns.indexOf(c), j = i + delta;
        if (j < 0 || j >= t.columns.size()) return;
        t.columns.remove(i);
        t.columns.add(j, c);
        editor.commit(this);
        rebuild();
    }

    private Node indexCard(Table t, TableIndex idx) {
        VBox card = new VBox(7);
        card.getStyleClass().add("column-card");
        TextField name = text(idx.name, v -> idx.name = v);
        name.setPromptText("auto name");
        name.getStyleClass().add("compact");
        HBox.setHgrow(name, Priority.ALWAYS);
        ToggleButton uq = chip("UNIQUE", idx.unique, "Unique index", v -> idx.unique = v, false);
        Button del = iconButton(Icons.TRASH, "Delete index", () -> {
            t.indexes.remove(idx);
            editor.commit(this);
            rebuild();
        });
        del.getStyleClass().add("danger-ghost");
        HBox row1 = new HBox(6, name, uq, del);
        row1.setAlignment(Pos.CENTER_LEFT);

        MenuButton cols = new MenuButton();
        cols.setMaxWidth(Double.MAX_VALUE);
        cols.getStyleClass().add("flat");
        Runnable label = () -> {
            List<String> names = new ArrayList<>();
            for (String id : idx.columnIds) {
                Column c = t.column(id);
                if (c != null) names.add(c.name);
            }
            cols.setText(names.isEmpty() ? "Choose columns…" : String.join(", ", names));
        };
        for (Column c : t.columns) {
            CheckMenuItem mi = new CheckMenuItem(c.name);
            mi.setSelected(idx.columnIds.contains(c.id));
            mi.selectedProperty().addListener((o, a, b) -> {
                if (b && !idx.columnIds.contains(c.id)) idx.columnIds.add(c.id);
                if (!b) idx.columnIds.remove(c.id);
                label.run();
                editor.commit(this);
            });
            cols.getItems().add(mi);
        }
        label.run();
        card.getChildren().addAll(row1, cols);
        return card;
    }

    private Node relationLink(Relation r) {
        Diagram d = editor.diagram();
        Table f = d.table(r.fromTableId), t = d.table(r.toTableId);
        String text = f.name + "." + f.column(r.fromColumnId).name + "  →  " + t.name + "." + t.column(r.toColumnId).name;
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 12px;");
        Label sub = new Label(r.type.label);
        sub.getStyleClass().add("item-sub");
        VBox box = new VBox(2, l, sub);
        HBox row = new HBox(8, Icons.icon(Icons.LINK, 14, "accent-icon"), box);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("relation-link");
        row.setOnMouseClicked(e -> editor.select(r.id, false));
        return row;
    }

    private void buildRelation(Relation r) {
        Diagram d = editor.diagram();
        content.getChildren().add(heading("Relation", null, editor::deleteSelection));

        ComboBox<Table> fromT = tableBox(d, r.fromTableId);
        ComboBox<Column> fromC = columnBox(d.table(r.fromTableId), r.fromColumnId);
        ComboBox<Table> toT = tableBox(d, r.toTableId);
        ComboBox<Column> toC = columnBox(d.table(r.toTableId), r.toColumnId);
        fromT.valueProperty().addListener((o, a, b) -> {
            if (b == null || b.columns.isEmpty()) return;
            r.fromTableId = b.id;
            r.fromColumnId = b.columns.get(0).id;
            editor.commit(this);
            rebuild();
        });
        toT.valueProperty().addListener((o, a, b) -> {
            if (b == null || b.columns.isEmpty()) return;
            r.toTableId = b.id;
            List<Column> pk = b.primaryKey();
            r.toColumnId = (pk.isEmpty() ? b.columns.get(0) : pk.get(0)).id;
            editor.commit(this);
            rebuild();
        });
        fromC.valueProperty().addListener((o, a, b) -> { if (b != null) { r.fromColumnId = b.id; editor.commit(this); } });
        toC.valueProperty().addListener((o, a, b) -> { if (b != null) { r.toColumnId = b.id; editor.commit(this); } });

        content.getChildren().add(section("FOREIGN KEY (MANY SIDE)", null, field("Table", fromT), field("Column", fromC)));
        content.getChildren().add(section("REFERENCES (ONE SIDE)", null, field("Table", toT), field("Column", toC)));

        ComboBox<RelationType> type = new ComboBox<>();
        type.getItems().setAll(RelationType.values());
        type.setValue(r.type);
        type.setMaxWidth(Double.MAX_VALUE);
        type.valueProperty().addListener((o, a, b) -> { if (b != null) { r.type = b; editor.commit(this); } });
        ComboBox<RefAction> del = actionBox(r.onDelete, v -> r.onDelete = v);
        ComboBox<RefAction> upd = actionBox(r.onUpdate, v -> r.onUpdate = v);
        TextField name = text(r.name, v -> r.name = v);
        name.setPromptText("fk_" + d.table(r.fromTableId).name + "_" + d.table(r.fromTableId).column(r.fromColumnId).name);
        Button swap = smallButton("Swap direction", Icons.SWAP, () -> {
            String tt = r.fromTableId, cc = r.fromColumnId;
            r.fromTableId = r.toTableId;
            r.fromColumnId = r.toColumnId;
            r.toTableId = tt;
            r.toColumnId = cc;
            editor.commit(this);
            rebuild();
        });
        content.getChildren().add(section("OPTIONS", null, field("Cardinality", type), field("On delete", del),
                field("On update", upd), field("Constraint name", name), swap));
    }

    private ComboBox<Table> tableBox(Diagram d, String selectedId) {
        ComboBox<Table> box = new ComboBox<>();
        box.getItems().setAll(d.tables);
        box.setValue(d.table(selectedId));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(new StringConverter<>() {
            @Override public String toString(Table t) { return t == null ? "" : t.name; }
            @Override public Table fromString(String s) { return d.tableByName(s); }
        });
        box.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Table t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.name);
                setGraphic(empty || t == null ? null : new Circle(4, Color.web(t.color)));
            }
        });
        return box;
    }

    private ComboBox<Column> columnBox(Table t, String selectedId) {
        ComboBox<Column> box = new ComboBox<>();
        if (t != null) box.getItems().setAll(t.columns);
        box.setValue(t == null ? null : t.column(selectedId));
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(new StringConverter<>() {
            @Override public String toString(Column c) { return c == null ? "" : c.name + "   " + c.type.toLowerCase(); }
            @Override public Column fromString(String s) { return null; }
        });
        return box;
    }

    private ComboBox<RefAction> actionBox(RefAction value, Consumer<RefAction> set) {
        ComboBox<RefAction> box = new ComboBox<>();
        box.getItems().setAll(RefAction.values());
        box.setValue(value == null ? RefAction.NO_ACTION : value);
        box.setMaxWidth(Double.MAX_VALUE);
        box.valueProperty().addListener((o, a, b) -> { if (b != null) { set.accept(b); editor.commit(this); } });
        return box;
    }

    private void buildNote(Note n) {
        content.getChildren().add(heading("Note", null, editor::deleteSelection));
        TextArea text = area(n.text, v -> n.text = v, "Write something…");
        text.setPrefRowCount(8);
        FlowPane colors = new FlowPane(8, 8);
        for (String hex : Palette.NOTE_COLORS) {
            colors.getChildren().add(swatch(hex, hex.equalsIgnoreCase(n.color), () -> {
                n.color = hex;
                editor.commit(this);
                rebuild();
            }));
        }
        content.getChildren().add(section("CONTENT", null, field("Text", text), field("Color", colors)));
    }

    private void buildArea(Area a) {
        content.getChildren().add(heading("Area", a.color, editor::deleteSelection));
        TextField name = text(a.name, v -> a.name = v);
        FlowPane colors = new FlowPane(7, 7);
        for (String hex : Palette.AREA_COLORS) {
            colors.getChildren().add(swatch(hex, hex.equalsIgnoreCase(a.color), () -> {
                a.color = hex;
                editor.commit(this);
                rebuild();
            }));
        }
        content.getChildren().add(section("GENERAL", null, field("Name", name), field("Color", colors)));
        VBox inside = new VBox(6);
        for (String id : editor.contentsOf(a, canvas::sizeOf)) {
            Table t = editor.diagram().table(id);
            if (t == null) continue;
            Label l = new Label(t.name);
            HBox row = new HBox(8, new Circle(5, Color.web(t.color)), l);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("relation-link");
            row.setOnMouseClicked(e -> { editor.select(t.id, false); canvas.centerOn(t.id); });
            inside.getChildren().add(row);
        }
        if (inside.getChildren().isEmpty()) inside.getChildren().add(hint("Drag tables into the area. Moving the area by its title moves everything inside it."));
        content.getChildren().add(section("TABLES INSIDE  " + inside.getChildren().stream().filter(n -> n instanceof HBox).count(), null, inside));
    }

    private void buildMulti() {
        List<Table> tables = editor.selectedTables();
        int notes = editor.selectedNotes().size();
        int count = editor.selection().size();
        content.getChildren().add(heading(count + " items selected", null, editor::deleteSelection));
        List<String> parts = new ArrayList<>();
        if (!tables.isEmpty()) parts.add(tables.size() + " table" + (tables.size() == 1 ? "" : "s"));
        if (notes > 0) parts.add(notes + " note" + (notes == 1 ? "" : "s"));
        int rels = count - tables.size() - notes;
        if (rels > 0) parts.add(rels + " relation" + (rels == 1 ? "" : "s"));
        content.getChildren().add(hint(String.join(" · ", parts)));
        if (!tables.isEmpty()) {
            content.getChildren().add(section("COLOR", null, tableSwatches(tables, null)));
            Button arrange = smallButton("Arrange selected", Icons.LAYOUT, () -> editor.autoLayout(canvas::sizeOf, true));
            arrange.setDisable(tables.size() < 2);
            Button dup = smallButton("Duplicate", Icons.COPY, editor::duplicateSelection);
            Button group = smallButton("Group into area", Icons.AREA, canvas::groupSelection);
            FlowPane actions = new FlowPane(8, 8, arrange, dup, group);
            content.getChildren().add(section("ACTIONS", null, actions));
        }
        HBox alignRow = new HBox(2,
                iconButton(Icons.ALIGN_LEFT, "Align left", () -> editor.align(Editor.Align.LEFT, canvas::sizeOf)),
                iconButton(Icons.ALIGN_HCENTER, "Align centers", () -> editor.align(Editor.Align.CENTER, canvas::sizeOf)),
                iconButton(Icons.ALIGN_RIGHT, "Align right", () -> editor.align(Editor.Align.RIGHT, canvas::sizeOf)),
                iconButton(Icons.ALIGN_TOP, "Align top", () -> editor.align(Editor.Align.TOP, canvas::sizeOf)),
                iconButton(Icons.ALIGN_VCENTER, "Align middles", () -> editor.align(Editor.Align.MIDDLE, canvas::sizeOf)),
                iconButton(Icons.ALIGN_BOTTOM, "Align bottom", () -> editor.align(Editor.Align.BOTTOM, canvas::sizeOf)),
                iconButton(Icons.DISTRIBUTE_H, "Distribute horizontally (3+)", () -> editor.distribute(true, canvas::sizeOf)),
                iconButton(Icons.DISTRIBUTE_V, "Distribute vertically (3+)", () -> editor.distribute(false, canvas::sizeOf)));
        content.getChildren().add(section("ALIGN & DISTRIBUTE", null, alignRow));
    }

    // ------------------------------------------------------------------ small widgets

    private Node heading(String title, String color, Runnable onDelete) {
        Label l = new Label(title);
        l.getStyleClass().add("panel-heading");
        HBox row = new HBox(9);
        row.setAlignment(Pos.CENTER_LEFT);
        if (color != null) row.getChildren().add(new Circle(6, Color.web(color)));
        row.getChildren().add(l);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        row.getChildren().add(sp);
        if (onDelete != null) {
            Button del = new Button("Delete");
            del.setGraphic(Icons.icon(Icons.TRASH, 14));
            del.getStyleClass().addAll("danger", "small");
            del.setOnAction(e -> onDelete.run());
            row.getChildren().add(del);
        }
        return row;
    }

    private VBox section(String title, Node action, Node... children) {
        Label l = new Label(title);
        l.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(8, l, sp);
        head.setAlignment(Pos.CENTER_LEFT);
        if (action != null) head.getChildren().add(action);
        VBox box = new VBox(head);
        box.getStyleClass().add("section");
        box.getChildren().addAll(children);
        return box;
    }

    private VBox field(String label, Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        if (control instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, l, control);
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }

    private TextField text(String value, Consumer<String> live) {
        TextField f = new TextField(value == null ? "" : value);
        bindInput(f, live);
        f.setOnAction(e -> editor.commit(this));
        return f;
    }

    private TextArea area(String value, Consumer<String> live, String prompt) {
        TextArea a = new TextArea(value == null ? "" : value);
        a.setWrapText(true);
        a.setPrefRowCount(2);
        a.setPromptText(prompt);
        bindInput(a, live);
        return a;
    }

    private void bindInput(TextInputControl c, Consumer<String> live) {
        c.textProperty().addListener((o, a, b) -> {
            live.accept(b);
            editor.changed(this);
        });
        c.focusedProperty().addListener((o, a, b) -> { if (!b) editor.commit(this); });
    }

    private ToggleButton chip(String text, boolean value, String tip, Consumer<Boolean> set, boolean rebuildAfter) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("chip");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setSelected(value);
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.selectedProperty().addListener((o, a, v) -> {
            set.accept(v);
            editor.commit(this);
            if (rebuildAfter) Platform.runLater(this::rebuild);
        });
        return b;
    }

    private Button iconButton(String icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.icon(icon, 15));
        b.getStyleClass().addAll("icon-button", "small");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button smallButton(String text, String icon, Runnable action) {
        Button b = new Button(text);
        b.setGraphic(Icons.icon(icon, 14));
        b.getStyleClass().add("small");
        b.setOnAction(e -> action.run());
        return b;
    }

    private Node tableSwatches(List<Table> tables, String current) {
        FlowPane pane = new FlowPane(7, 7);
        for (String hex : Palette.TABLE_COLORS) {
            pane.getChildren().add(swatch(hex, hex.equalsIgnoreCase(current), () -> {
                for (Table t : tables) t.color = hex;
                editor.commit(this);
                rebuild();
            }));
        }
        ColorPicker custom = new ColorPicker(current == null ? Color.web(Palette.DEFAULT_TABLE) : Color.web(current));
        custom.getStyleClass().add("button");
        custom.setStyle("-fx-color-label-visible: false; -fx-padding: 2 4 2 4;");
        custom.setTooltip(new Tooltip("Custom color"));
        custom.setOnAction(e -> {
            Color c = custom.getValue();
            String hex = String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
                    (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
            for (Table t : tables) t.color = hex;
            editor.commit(this);
            rebuild();
        });
        pane.getChildren().add(custom);
        return pane;
    }

    private Node swatch(String hex, boolean selected, Runnable onClick) {
        Circle c = new Circle(10, Color.web(hex));
        c.getStyleClass().add("swatch");
        if (selected) c.getStyleClass().add("selected");
        c.setOnMouseClicked(e -> onClick.run());
        Tooltip.install(c, new Tooltip(hex));
        return c;
    }
}
