package com.erdstudio.ui;

import com.erdstudio.io.DbmlIO;
import com.erdstudio.io.DocsExporter;
import com.erdstudio.io.PrismaIO;
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
import java.util.List;
import java.util.Map;

/** Live preview + copy/save for Prisma, DBML, Mermaid and Markdown. */
public final class ExportCodeDialog extends Dialog<Void> {

    public enum Format {
        PRISMA("Prisma schema", "schema.prisma", "*.prisma"),
        DBML("DBML (dbdiagram.io)", ".dbml", "*.dbml"),
        MERMAID("Mermaid ER diagram", ".mmd", "*.mmd"),
        MARKDOWN("Markdown documentation", ".md", "*.md");

        final String label, suffix, glob;

        Format(String l, String s, String g) { label = l; suffix = s; glob = g; }

        @Override public String toString() { return label; }
    }

    public ExportCodeDialog(Window owner, Diagram diagram, Map<String, List<String>> areaMembers, Format initial) {
        initOwner(owner);
        setTitle("Export");
        setHeaderText("Export code & docs");
        setResizable(true);
        Theme.apply(this);

        ComboBox<Format> format = new ComboBox<>();
        format.getItems().setAll(Format.values());
        format.setValue(initial);
        CheckBox prismaNaming = new CheckBox("Prisma naming (PascalCase models, camelCase fields + @map)");
        prismaNaming.setSelected(true);
        ComboBox<Dialect> provider = new ComboBox<>();
        provider.getItems().setAll(Dialect.values());
        provider.setValue(diagram.dialect);
        CheckBox mermaidInMd = new CheckBox("Include Mermaid diagram");
        mermaidInMd.setSelected(true);

        TextArea code = new TextArea();
        code.setEditable(false);
        code.getStyleClass().add("code-area");
        code.setStyle("-fx-font-family: \"" + Theme.MONO + "\";");
        code.setPrefSize(820, 500);
        VBox.setVgrow(code, Priority.ALWAYS);

        Runnable render = () -> {
            Format f = format.getValue();
            prismaNaming.setVisible(f == Format.PRISMA);
            prismaNaming.setManaged(f == Format.PRISMA);
            provider.setVisible(f == Format.PRISMA);
            provider.setManaged(f == Format.PRISMA);
            mermaidInMd.setVisible(f == Format.MARKDOWN);
            mermaidInMd.setManaged(f == Format.MARKDOWN);
            code.setText(switch (f) {
                case PRISMA -> PrismaIO.export(diagram, new PrismaIO.Options(prismaNaming.isSelected(), provider.getValue()));
                case DBML -> DbmlIO.export(diagram, areaMembers);
                case MERMAID -> DocsExporter.mermaid(diagram);
                case MARKDOWN -> DocsExporter.markdown(diagram, areaMembers, mermaidInMd.isSelected());
            });
        };
        format.valueProperty().addListener(o -> render.run());
        prismaNaming.selectedProperty().addListener(o -> render.run());
        provider.valueProperty().addListener(o -> render.run());
        mermaidInMd.selectedProperty().addListener(o -> render.run());
        render.run();

        Label status = new Label();
        status.getStyleClass().add("hint");
        Button copy = new Button("Copy");
        copy.setGraphic(Icons.icon(Icons.COPY, 15));
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(code.getText());
            Clipboard.getSystemClipboard().setContent(cc);
            status.setText("Copied to clipboard");
        });
        Button save = new Button("Save…");
        save.getStyleClass().add("primary");
        save.setGraphic(Icons.icon(Icons.DOWNLOAD, 15));
        save.setOnAction(e -> {
            Format f = format.getValue();
            FileChooser fc = new FileChooser();
            fc.setTitle("Save " + f.label);
            fc.setInitialFileName(f == Format.PRISMA ? f.suffix : ExportSqlDialog.safe(diagram.name) + f.suffix);
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(f.label, f.glob));
            File file = fc.showSaveDialog(getDialogPane().getScene().getWindow());
            if (file == null) return;
            try {
                Files.writeString(file.toPath(), code.getText(), StandardCharsets.UTF_8);
                status.setText("Saved " + file.getName());
            } catch (IOException ex) {
                status.setText("Could not save: " + ex.getMessage());
            }
        });

        Label fl = new Label("Format");
        fl.getStyleClass().add("field-label");
        HBox top = new HBox(12, fl, format, provider, prismaNaming, mermaidInMd);
        top.setAlignment(Pos.CENTER_LEFT);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bottom = new HBox(10, status, sp, copy, save);
        bottom.setAlignment(Pos.CENTER_LEFT);
        getDialogPane().setContent(new VBox(12, top, code, bottom));
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    }
}
