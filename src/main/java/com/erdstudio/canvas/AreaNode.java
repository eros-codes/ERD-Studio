package com.erdstudio.canvas;

import com.erdstudio.model.Area;
import javafx.css.PseudoClass;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * A coloured group region. Only the title pill and the resize grip are interactive, so clicks
 * on the body fall through to the canvas (pan / box select still work inside an area).
 */
public final class AreaNode extends Pane {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    interface Hooks {
        void areaRename(AreaNode node, String name);
        void areaResize(AreaNode node, double w, double h, boolean finished);
    }

    private Area area;
    private final Hooks hooks;
    private final Region body = new Region();
    private final Label title = new Label();
    private final Region grip = new Region();
    private boolean editing;
    private double pressX, pressY, startW, startH;

    AreaNode(Area area, Hooks hooks) {
        this.area = area;
        this.hooks = hooks;
        getStyleClass().add("area-node");
        setPickOnBounds(false);
        body.getStyleClass().add("area-body");
        body.setMouseTransparent(true);
        title.getStyleClass().add("area-title");
        title.setCursor(Cursor.MOVE);
        grip.getStyleClass().add("area-grip");
        grip.setCursor(Cursor.SE_RESIZE);
        grip.setPrefSize(16, 16);
        getChildren().addAll(body, title, grip);

        title.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                startRename();
                e.consume();
            }
        });
        grip.setOnMousePressed(e -> {
            pressX = e.getScreenX();
            pressY = e.getScreenY();
            startW = this.area.width;
            startH = this.area.height;
            e.consume();
        });
        grip.setOnMouseDragged(e -> {
            double z = localToScene(1, 0).getX() - localToScene(0, 0).getX();
            double w = Math.max(160, startW + (e.getScreenX() - pressX) / z);
            double h = Math.max(100, startH + (e.getScreenY() - pressY) / z);
            hooks.areaResize(this, Math.round(w), Math.round(h), false);
            e.consume();
        });
        grip.setOnMouseReleased(e -> {
            hooks.areaResize(this, this.area.width, this.area.height, true);
            e.consume();
        });
        bind(area);
    }

    public Area area() { return area; }

    /** The draggable title pill (the canvas installs its press/drag handlers here). */
    Label handle() { return title; }

    void bind(Area a) {
        this.area = a;
        title.setText(a.name);
        Color c = Color.web(a.color);
        body.setStyle("-fx-background-color: " + rgba(c, 0.06) + "; -fx-border-color: " + rgba(c, 0.55) + ";");
        title.setStyle("-fx-background-color: " + rgba(c, 0.16) + "; -fx-text-fill: " + a.color + ";");
        grip.setStyle("-fx-background-color: " + rgba(c, 0.5) + ";");
        relocate(a.x, a.y);
        setPrefSize(a.width, a.height);
        resize(a.width, a.height);
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        body.resizeRelocate(0, 0, area.width, area.height);
        double tw = Math.min(area.width - 16, title.prefWidth(-1)), th = title.prefHeight(-1);
        title.resizeRelocate(10, 10, Math.max(40, tw), th);
        grip.resizeRelocate(area.width - 18, area.height - 18, 14, 14);
    }

    @Override
    protected double computePrefWidth(double h) { return area.width; }

    @Override
    protected double computePrefHeight(double w) { return area.height; }

    void setSelected(boolean v) { pseudoClassStateChanged(SELECTED, v); }

    boolean isEditing() { return editing; }

    void startRename() {
        if (editing) return;
        editing = true;
        TextField field = new TextField(area.name);
        field.getStyleClass().add("inline-edit");
        field.relocate(10, 8);
        field.setPrefWidth(Math.max(140, title.getWidth() + 40));
        getChildren().add(field);
        title.setVisible(false);
        field.requestFocus();
        field.selectAll();
        Runnable finish = () -> {
            if (!editing) return;
            editing = false;
            getChildren().remove(field);
            title.setVisible(true);
            String v = field.getText().trim();
            if (!v.isEmpty() && !v.equals(area.name)) hooks.areaRename(this, v);
        };
        field.setOnAction(e -> finish.run());
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                field.setText(area.name);
                finish.run();
                e.consume();
            }
        });
        field.focusedProperty().addListener((o, was, is) -> { if (!is) finish.run(); });
        field.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
    }

    static String rgba(Color c, double a) {
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), a);
    }
}
