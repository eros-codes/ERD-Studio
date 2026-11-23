package com.erdstudio.canvas;

import com.erdstudio.model.Note;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** A sticky note on the canvas. Double-click to edit, drag the corner to resize. */
public final class NoteNode extends StackPane {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    interface Hooks {
        void noteText(NoteNode node, String text);
        void noteResize(NoteNode node, double w, double h, boolean finished);
    }

    private Note note;
    private final Hooks hooks;
    private final Label label = new Label();
    private final Region grip = new Region();
    private boolean editing;
    private double pressX, pressY, startW, startH;

    NoteNode(Note note, Hooks hooks) {
        this.note = note;
        this.hooks = hooks;
        getStyleClass().add("note-node");
        label.getStyleClass().add("note-text");
        label.setWrapText(true);
        label.setAlignment(Pos.TOP_LEFT);
        label.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setAlignment(label, Pos.TOP_LEFT);
        StackPane.setMargin(label, new Insets(12, 14, 14, 14));

        grip.getStyleClass().add("note-grip");
        grip.setMinSize(14, 14);
        grip.setMaxSize(14, 14);
        grip.setCursor(Cursor.SE_RESIZE);
        StackPane.setAlignment(grip, Pos.BOTTOM_RIGHT);
        grip.setOnMousePressed(e -> {
            pressX = e.getScreenX();
            pressY = e.getScreenY();
            startW = this.note.width;
            startH = this.note.height;
            e.consume();
        });
        grip.setOnMouseDragged(e -> {
            double z = getParent() == null ? 1 : localToScene(1, 0).getX() - localToScene(0, 0).getX();
            double w = Math.max(120, startW + (e.getScreenX() - pressX) / z);
            double h = Math.max(70, startH + (e.getScreenY() - pressY) / z);
            hooks.noteResize(this, Math.round(w), Math.round(h), false);
            e.consume();
        });
        grip.setOnMouseReleased(e -> {
            hooks.noteResize(this, this.note.width, this.note.height, true);
            e.consume();
        });
        getChildren().addAll(label, grip);

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                startEdit();
                e.consume();
            }
        });
        bind(note);
    }

    public Note note() { return note; }

    void bind(Note n) {
        this.note = n;
        label.setText(n.text);
        setStyle("-fx-background-color: " + n.color + ";");
        setMinSize(n.width, n.height);
        setPrefSize(n.width, n.height);
        setMaxSize(n.width, n.height);
        relocate(n.x, n.y);
    }

    void setSelected(boolean v) { pseudoClassStateChanged(SELECTED, v); }

    boolean isEditing() { return editing; }

    void startEdit() {
        if (editing) return;
        editing = true;
        TextArea area = new TextArea(note.text);
        area.getStyleClass().add("note-editor");
        area.setWrapText(true);
        StackPane.setMargin(area, new Insets(6));
        getChildren().add(1, area);
        label.setVisible(false);
        area.requestFocus();
        area.selectAll();
        Runnable finish = () -> {
            if (!editing) return;
            editing = false;
            getChildren().remove(area);
            label.setVisible(true);
            if (!area.getText().equals(note.text)) hooks.noteText(this, area.getText());
        };
        area.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE || (e.getCode() == KeyCode.ENTER && e.isShortcutDown())) {
                finish.run();
                e.consume();
            }
        });
        area.focusedProperty().addListener((o, a, b) -> { if (!b) finish.run(); });
        area.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
    }
}
