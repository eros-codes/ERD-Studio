package com.erdstudio.canvas;

import com.erdstudio.model.Column;
import com.erdstudio.model.Table;
import com.erdstudio.ui.Icons;
import com.erdstudio.ui.Theme;
import javafx.css.PseudoClass;
import javafx.scene.Cursor;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A table card. Its geometry is deterministic (fixed header/row heights, width computed from text),
 * so relation anchors can be computed straight from the model without waiting for a layout pass.
 */
public final class TableNode extends Region {

    static final double STRIPE = 4;
    static final double HEADER = 38;
    static final double ROW = 28;
    static final double PAD_BOTTOM = 6;
    static final double MIN_WIDTH = 210;
    static final double MAX_WIDTH = 460;
    static final double EDGE_ZONE = 14;

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass HIGHLIGHT = PseudoClass.getPseudoClass("highlight");
    private static final PseudoClass DIMMED = PseudoClass.getPseudoClass("dimmed");

    private static final Font HEADER_FONT = Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, 13.5);
    private static final Font NAME_FONT = Font.font(Font.getDefault().getFamily(), 12.5);
    private static final Font TYPE_FONT = Font.font(Theme.MONO, 11.5);

    /** Callbacks into the canvas. */
    interface Hooks {
        void connectStart(TableNode node, Column column, MouseEvent e);
        void connectDrag(MouseEvent e);
        void connectEnd(MouseEvent e);
        void renameTable(TableNode node, String newName);
        void renameColumn(TableNode node, Column column, String newName);
        void rowContextMenu(TableNode node, Column column, double screenX, double screenY);
    }

    private final Hooks hooks;
    private Table table;
    private String signature = "";
    private double width = MIN_WIDTH;

    private final Region stripe = new Region();
    private final HBox header = new HBox();
    private final Label title = new Label();
    private final VBox body = new VBox();
    private final List<ColumnRow> rows = new ArrayList<>();
    private boolean editing;

    TableNode(Table table, Hooks hooks) {
        this.hooks = hooks;
        this.table = table;
        getStyleClass().add("table-node");
        stripe.getStyleClass().add("table-stripe");
        header.getStyleClass().add("table-header");
        header.setAlignment(Pos.CENTER_LEFT);
        title.getStyleClass().add("table-title");
        body.getStyleClass().add("table-body");
        getChildren().addAll(stripe, header, body);

        title.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                startRenameTable();
                e.consume();
            }
        });
    }

    public Table table() { return table; }

    public String tableId() { return table.id; }

    double nodeWidth() { return width; }

    double nodeHeight() { return height(table); }

    public static double height(Table t) {
        return STRIPE + HEADER + t.columns.size() * ROW + PAD_BOTTOM;
    }

    /** Width that fits the longest line of text. */
    public static double computeWidth(Table t) {
        double w = measure(t.name, HEADER_FONT) + 56;
        for (Column c : t.columns) {
            double row = 14 + 18 + measure(c.name, NAME_FONT) + 24 + measure(typeText(c), TYPE_FONT) + 16;
            if (c.unique && !c.primaryKey) row += 26;
            if (c.autoIncrement) row += 24;
            w = Math.max(w, row);
        }
        return Math.ceil(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, w)));
    }

    private static double measure(String s, Font f) {
        Text t = new Text(s == null ? "" : s);
        t.setFont(f);
        return t.getLayoutBounds().getWidth();
    }

    private static String typeText(Column c) {
        return c.type == null || c.type.isBlank() ? "?" : c.type.toLowerCase();
    }

    /** Y (world) of the middle of a column row. */
    double rowCenterY(int index) {
        return table.y + STRIPE + HEADER + index * ROW + ROW / 2;
    }

    /** Re-binds to a (possibly new) model instance; rebuilds content only if something visible changed. */
    void bind(Table t, Set<String> fkColumnIds) {
        boolean newInstance = t != this.table;
        this.table = t;
        StringBuilder sig = new StringBuilder(t.name).append('|').append(t.color).append('|').append(t.comment);
        for (Column c : t.columns) {
            sig.append('|').append(c.id).append(c.name).append(c.type).append(c.primaryKey).append(c.notNull)
                    .append(c.unique).append(c.autoIncrement).append(fkColumnIds.contains(c.id)).append(c.comment)
                    .append(c.defaultValue);
        }
        if (newInstance || !sig.toString().equals(signature)) {
            signature = sig.toString();
            rebuild(fkColumnIds);
        }
        relocate(t.x, t.y);
    }

    private void rebuild(Set<String> fkColumnIds) {
        width = computeWidth(table);
        double h = height(table);
        setMinSize(width, h);
        setPrefSize(width, h);
        setMaxSize(width, h);

        stripe.setStyle("-fx-background-color: " + table.color + ";");
        setStyle("-table-color: " + table.color + ";");

        title.setText(table.name);
        header.getChildren().setAll(title);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().add(spacer);
        if (!table.comment.isBlank()) {
            javafx.scene.Node commentIcon = icon(Icons.COMMENT, 13, "muted-icon");
            header.getChildren().add(commentIcon);
            Tooltip tip = new Tooltip(table.comment);
            tip.setShowDelay(Duration.millis(250));
            Tooltip.install(header, tip);
        }

        rows.clear();
        body.getChildren().clear();
        for (Column c : table.columns) {
            ColumnRow row = new ColumnRow(c, fkColumnIds.contains(c.id));
            rows.add(row);
            body.getChildren().add(row);
        }
    }

    private static javafx.scene.Node icon(String path, double size, String cls) {
        javafx.scene.Node n = Icons.icon(path, size);
        n.getStyleClass().add(cls);
        return n;
    }

    @Override
    protected void layoutChildren() {
        stripe.resizeRelocate(0, 0, width, STRIPE);
        header.resizeRelocate(0, STRIPE, width, HEADER);
        body.resizeRelocate(0, STRIPE + HEADER, width, table.columns.size() * ROW);
    }

    void setSelected(boolean selected) { pseudoClassStateChanged(SELECTED, selected); }

    void setDimmed(boolean dimmed) { pseudoClassStateChanged(DIMMED, dimmed); }

    void highlightColumns(Set<String> columnIds) {
        for (ColumnRow r : rows) r.pseudoClassStateChanged(HIGHLIGHT, columnIds.contains(r.column.id));
    }

    boolean isEditing() { return editing; }

    /** Column under a world-space point, or null. */
    Column columnAt(double worldX, double worldY) {
        double localY = worldY - table.y - STRIPE - HEADER;
        if (worldX < table.x || worldX > table.x + width || localY < 0) return null;
        int idx = (int) (localY / ROW);
        return idx >= 0 && idx < table.columns.size() ? table.columns.get(idx) : null;
    }

    boolean containsWorld(double worldX, double worldY) {
        return worldX >= table.x && worldX <= table.x + width && worldY >= table.y && worldY <= table.y + nodeHeight();
    }

    // ------------------------------------------------------------------ inline editing

    void startRenameTable() {
        if (editing) return;
        editing = true;
        TextField field = new TextField(table.name);
        field.getStyleClass().add("inline-edit");
        field.setPrefWidth(width - 24);
        int idx = header.getChildren().indexOf(title);
        header.getChildren().set(idx, field);
        field.requestFocus();
        field.selectAll();
        Runnable finish = () -> {
            if (!editing) return;
            editing = false;
            String v = field.getText().trim();
            header.getChildren().set(header.getChildren().indexOf(field), title);
            if (!v.isEmpty() && !v.equals(table.name)) hooks.renameTable(this, v);
        };
        field.setOnAction(e -> finish.run());
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                field.setText(table.name);
                finish.run();
                e.consume();
            }
        });
        field.focusedProperty().addListener((o, was, is) -> { if (!is) finish.run(); });
        field.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
    }

    void startRenameColumn(Column c) {
        for (ColumnRow r : rows) if (r.column == c) r.startRename();
    }

    // ------------------------------------------------------------------ rows

    private final class ColumnRow extends StackPane {
        final Column column;
        final HBox content = new HBox(6);
        final Label name = new Label();
        boolean connecting;

        ColumnRow(Column c, boolean fk) {
            this.column = c;
            getStyleClass().add("column-row");
            setMinHeight(ROW);
            setPrefHeight(ROW);
            setMaxHeight(ROW);

            content.setAlignment(Pos.CENTER_LEFT);
            content.getStyleClass().add("column-content");

            StackPane keySlot = new StackPane();
            keySlot.setMinWidth(14);
            keySlot.setPrefWidth(14);
            if (c.primaryKey) keySlot.getChildren().add(icon(Icons.KEY, 12, "pk-icon"));
            else if (fk) keySlot.getChildren().add(icon(Icons.LINK, 12, "fk-icon"));

            name.setText(c.name);
            name.getStyleClass().add("column-name");
            if (c.primaryKey) name.getStyleClass().add("pk");
            name.setMinWidth(Region.USE_PREF_SIZE);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            content.getChildren().addAll(keySlot, name, spacer);
            if (c.unique && !c.primaryKey) content.getChildren().add(chip("UQ"));
            if (c.autoIncrement) content.getChildren().add(chip("AI"));

            Label type = new Label(typeText(c));
            type.getStyleClass().add("column-type");
            type.setStyle("-fx-font-family: \"" + Theme.MONO + "\";");
            type.setMinWidth(Region.USE_PREF_SIZE);
            content.getChildren().add(type);
            Label nullMark = new Label(c.isRequired() ? " " : "?");
            nullMark.getStyleClass().add("null-mark");
            nullMark.setMinWidth(8);
            content.getChildren().add(nullMark);

            Region left = handle(), right = handle();
            StackPane.setAlignment(left, Pos.CENTER_LEFT);
            StackPane.setAlignment(right, Pos.CENTER_RIGHT);
            left.setTranslateX(-5);
            right.setTranslateX(5);
            getChildren().addAll(content, left, right);
            installConnectGesture();

            StringBuilder tip = new StringBuilder(c.name + " : " + (c.type == null ? "" : c.type));
            if (c.primaryKey) tip.append("\nPrimary key");
            if (fk) tip.append("\nForeign key");
            tip.append(c.isRequired() ? "\nNOT NULL" : "\nNullable");
            if (c.unique && !c.primaryKey) tip.append("\nUnique");
            if (c.autoIncrement) tip.append("\nAuto increment");
            if (!c.defaultValue.isBlank()) tip.append("\nDefault: ").append(c.defaultValue);
            if (!c.comment.isBlank()) tip.append("\n\n").append(c.comment);
            Tooltip tt = new Tooltip(tip.toString());
            tt.setShowDelay(Duration.millis(600));
            Tooltip.install(content, tt);

            content.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    startRename();
                    e.consume();
                }
            });
            setOnContextMenuRequested(e -> {
                hooks.rowContextMenu(TableNode.this, column, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }

        private Label chip(String text) {
            Label l = new Label(text);
            l.getStyleClass().add("column-chip");
            l.setMinWidth(Region.USE_PREF_SIZE);
            return l;
        }

        private Region handle() {
            Region h = new Region();
            h.getStyleClass().add("conn-handle");
            h.setMinSize(10, 10);
            h.setPrefSize(10, 10);
            h.setMaxSize(10, 10);
            h.setMouseTransparent(true); // the row's edge zone handles the gesture
            return h;
        }

        private boolean inEdgeZone(MouseEvent e) {
            return e.getX() <= EDGE_ZONE || e.getX() >= getWidth() - EDGE_ZONE;
        }

        private void installConnectGesture() {
            setOnMouseMoved(e -> setCursor(inEdgeZone(e) || e.isAltDown() ? Cursor.CROSSHAIR : null));
            setOnMouseExited(e -> setCursor(null));
            setOnMousePressed(e -> {
                if (e.getButton() != MouseButton.PRIMARY || editing) return;
                if (!inEdgeZone(e) && !e.isAltDown()) return; // let the table drag handle it
                connecting = true;
                hooks.connectStart(TableNode.this, column, e);
                e.consume();
            });
            setOnMouseDragged(e -> {
                if (!connecting) return;
                hooks.connectDrag(e);
                e.consume();
            });
            setOnMouseReleased(e -> {
                if (!connecting) return;
                connecting = false;
                hooks.connectEnd(e);
                e.consume();
            });
        }

        void startRename() {
            if (editing) return;
            editing = true;
            TextField field = new TextField(column.name);
            field.getStyleClass().add("inline-edit");
            field.setPrefWidth(Math.max(80, name.getWidth() + 30));
            int idx = content.getChildren().indexOf(name);
            content.getChildren().set(idx, field);
            field.requestFocus();
            field.selectAll();
            Runnable finish = () -> {
                if (!editing) return;
                editing = false;
                String v = field.getText().trim();
                int i = content.getChildren().indexOf(field);
                if (i >= 0) content.getChildren().set(i, name);
                if (!v.isEmpty() && !v.equals(column.name)) hooks.renameColumn(TableNode.this, column, v);
            };
            field.setOnAction(e -> finish.run());
            field.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    field.setText(column.name);
                    finish.run();
                    e.consume();
                }
            });
            field.focusedProperty().addListener((o, was, is) -> { if (!is) finish.run(); });
            field.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        }
    }
}
