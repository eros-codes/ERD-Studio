package com.erdstudio.ui;

import com.erdstudio.io.SqlExporter;
import com.erdstudio.model.Diagram;
import com.erdstudio.model.Dialect;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Live DDL preview with dialect switch, copy and save. */
public final class ExportSqlDialog extends Dialog<Void> {

    public ExportSqlDialog(Window owner, Diagram diagram) {
        initOwner(owner);
        setTitle("Export SQL");
        setHeaderText("Export SQL");
        setResizable(true);
        Theme.apply(this);

        ComboBox<Dialect> dialect = new ComboBox<>();
        dialect.getItems().setAll(Dialect.values());
        dialect.setValue(diagram.dialect);
        CheckBox drop = new CheckBox("DROP TABLE IF EXISTS");
        CheckBox comments = new CheckBox("Comments");
        comments.setSelected(true);
        CheckBox map = new CheckBox("Convert types");
        map.setSelected(true);

        TextArea sql = new TextArea();
        sql.setEditable(false);
        sql.getStyleClass().add("code-area");
        sql.setStyle("-fx-font-family: \"" + Theme.MONO + "\";");
        sql.setPrefSize(780, 480);
        VBox.setVgrow(sql, Priority.ALWAYS);

        Runnable render = () -> sql.setText(SqlExporter.export(diagram, dialect.getValue(),
                new SqlExporter.Options(drop.isSelected(), comments.isSelected(), map.isSelected())));
        dialect.valueProperty().addListener(o -> render.run());
        drop.selectedProperty().addListener(o -> render.run());
        comments.selectedProperty().addListener(o -> render.run());
        map.selectedProperty().addListener(o -> render.run());
        render.run();

        Label copied = new Label();
        copied.getStyleClass().add("hint");
        Button copy = new Button("Copy");
        copy.setGraphic(Icons.icon(Icons.COPY, 15));
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(sql.getText());
            Clipboard.getSystemClipboard().setContent(cc);
            copied.setText("Copied to clipboard");
        });
        Button save = new Button("Save .sql…");
        save.getStyleClass().add("primary");
        save.setGraphic(Icons.icon(Icons.DOWNLOAD, 15));
        save.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save SQL");
            fc.setInitialFileName(safe(diagram.name) + "-" + dialect.getValue().name().toLowerCase() + ".sql");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL files", "*.sql"));
            File f = fc.showSaveDialog(getDialogPane().getScene().getWindow());
            if (f == null) return;
            try {
                Files.writeString(f.toPath(), sql.getText(), StandardCharsets.UTF_8);
                copied.setText("Saved " + f.getName());
            } catch (IOException ex) {
                copied.setText("Could not save: " + ex.getMessage());
            }
        });

        Label dl = new Label("Dialect");
        dl.getStyleClass().add("field-label");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(12, dl, dialect, drop, comments, map);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox bottom = new HBox(10, copied, sp, copy, save);
        bottom.setAlignment(Pos.CENTER_LEFT);
        getDialogPane().setContent(new VBox(12, top, sql, bottom));
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }

    static String safe(String name) {
        String s = name == null ? "diagram" : name.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        return s.isEmpty() ? "diagram" : s;
    }
}
