package com.erdstudio.ui;

import com.erdstudio.io.DbmlIO;
import com.erdstudio.io.ImportResult;
import com.erdstudio.io.PrismaIO;
import com.erdstudio.io.SqlImporter;
import com.erdstudio.model.Dialect;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** Paste or open SQL, Prisma or DBML; returns the parse result plus whether to replace the diagram. */
public final class ImportDialog extends Dialog<ImportDialog.Choice> {

    public enum Format {
        AUTO("Auto-detect"), SQL("SQL (DDL)"), PRISMA("Prisma schema"), DBML("DBML (dbdiagram.io)");

        final String label;

        Format(String l) { label = l; }

        @Override public String toString() { return label; }
    }

    public record Choice(ImportResult result, boolean replace, Format format) {}

    private static final String PROMPT = """
            Paste SQL, a Prisma schema or DBML here — the format is detected automatically.

            CREATE TABLE users (                 model User {
              id SERIAL PRIMARY KEY,               id    Int    @id @default(autoincrement())
              email VARCHAR(255) UNIQUE            email String @unique
            );                                   }""";

    private final ComboBox<Format> format = new ComboBox<>();
    private final ComboBox<Dialect> dialect = new ComboBox<>();
    private final TextArea text = new TextArea();

    public ImportDialog(Window owner, Dialect current, boolean diagramEmpty, String initialText, Format initialFormat) {
        initOwner(owner);
        setTitle("Import");
        setHeaderText("Import SQL, Prisma or DBML");
        setResizable(true);
        Theme.apply(this);

        format.getItems().setAll(Format.values());
        format.setValue(initialFormat == null ? Format.AUTO : initialFormat);
        dialect.getItems().setAll(Dialect.values());
        dialect.setValue(current);
        dialect.setTooltip(new javafx.scene.control.Tooltip("Dialect used for SQL (tolerant either way)"));

        text.setPromptText(PROMPT);
        text.getStyleClass().add("code-area");
        text.setStyle("-fx-font-family: \"" + Theme.MONO + "\";");
        text.setPrefSize(820, 460);
        if (initialText != null) text.setText(initialText);
        VBox.setVgrow(text, Priority.ALWAYS);

        Button load = new Button("Open file…");
        load.setGraphic(Icons.icon(Icons.FOLDER, 15));
        load.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Open schema file");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Schema files", "*.sql", "*.ddl", "*.prisma", "*.dbml", "*.txt"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            File f = fc.showOpenDialog(getDialogPane().getScene().getWindow());
            if (f == null) return;
            text.setText(readFile(f.toPath()));
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".prisma")) format.setValue(Format.PRISMA);
            else if (n.endsWith(".dbml")) format.setValue(Format.DBML);
            else if (n.endsWith(".sql") || n.endsWith(".ddl")) format.setValue(Format.SQL);
        });

        ToggleGroup mode = new ToggleGroup();
        RadioButton replace = new RadioButton("Replace current diagram");
        RadioButton merge = new RadioButton("Add to current diagram");
        replace.setToggleGroup(mode);
        merge.setToggleGroup(mode);
        (diagramEmpty ? replace : merge).setSelected(true);
        merge.setDisable(diagramEmpty);

        Label fl = new Label("Format");
        fl.getStyleClass().add("field-label");
        Label dl = new Label("SQL dialect");
        dl.getStyleClass().add("field-label");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(10, fl, format, dl, dialect, sp, load);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox modes = new HBox(18, replace, merge);

        Label status = new Label("Tables, columns, keys, relations, indexes and comments are imported. Anything unknown is skipped.");
        status.getStyleClass().add("hint");
        PauseTransition debounce = new PauseTransition(Duration.millis(300));
        Runnable preview = () -> {
            String b = text.getText();
            if (b == null || b.isBlank()) { status.setText("Paste something to preview."); return; }
            Format f = detect();
            ImportResult r = parse(b, f, dialect.getValue());
            status.setText((format.getValue() == Format.AUTO ? "Detected " + f.label + " · " : "") + "Found " + r.tableCount()
                    + " table" + (r.tableCount() == 1 ? "" : "s") + " and " + r.relationCount() + " relation"
                    + (r.relationCount() == 1 ? "" : "s") + (r.diagram().areas.isEmpty() ? "" : " · " + r.diagram().areas.size() + " groups")
                    + (r.warnings().isEmpty() ? "" : " · " + r.warnings().size() + " warning(s)"));
        };
        debounce.setOnFinished(ev -> preview.run());
        text.textProperty().addListener(o -> debounce.playFromStart());
        format.valueProperty().addListener(o -> preview.run());
        dialect.valueProperty().addListener(o -> preview.run());
        if (initialText != null) preview.run();

        getDialogPane().setContent(new VBox(12, top, text, modes, status));
        ButtonType importBtn = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, importBtn);
        Button ok = (Button) getDialogPane().lookupButton(importBtn);
        ok.getStyleClass().add("primary");
        ok.disableProperty().bind(text.textProperty().isEmpty());

        setResultConverter(bt -> {
            if (bt != importBtn) return null;
            Format f = detect();
            return new Choice(parse(text.getText(), f, dialect.getValue()), replace.isSelected(), f);
        });
        setOnShown(e -> text.requestFocus());
    }

    private Format detect() {
        return format.getValue() == Format.AUTO ? detect(text.getText()) : format.getValue();
    }

    public static Format detect(String s) {
        if (s == null) return Format.SQL;
        if (Pattern.compile("(?m)^\\s*(model|datasource|generator)\\s+\\w+\\s*\\{").matcher(s).find()) return Format.PRISMA;
        if (Pattern.compile("(?im)^\\s*(Table|Ref|TableGroup|Enum|Project)\\b[^\\n;(]*(\\{|:)").matcher(s).find()
                && !Pattern.compile("(?i)CREATE\\s+TABLE").matcher(s).find()) return Format.DBML;
        return Format.SQL;
    }

    public static ImportResult parse(String text, Format f, Dialect dialect) {
        return switch (f == Format.AUTO ? detect(text) : f) {
            case PRISMA -> PrismaIO.parse(text);
            case DBML -> DbmlIO.parse(text);
            default -> SqlImporter.parse(text, dialect);
        };
    }

    public static String readFile(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            try {
                return Files.readString(p, StandardCharsets.ISO_8859_1);
            } catch (IOException ex2) {
                return "-- Could not read file: " + ex2.getMessage();
            }
        }
    }
}
