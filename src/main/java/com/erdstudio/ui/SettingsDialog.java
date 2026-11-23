package com.erdstudio.ui;

import com.erdstudio.editor.Settings;
import com.erdstudio.model.Dialect;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Preferences window. Returns true when the user pressed Save. */
public final class SettingsDialog extends Dialog<Boolean> {

    public SettingsDialog(Window owner) {
        initOwner(owner);
        setTitle("Settings");
        setHeaderText("Settings");
        Theme.apply(this);

        ComboBox<Dialect> dialect = new ComboBox<>();
        dialect.getItems().setAll(Dialect.values());
        dialect.setValue(Settings.defaultDialect());
        TextField idName = new TextField(Settings.idColumnName());
        CheckBox timestamps = new CheckBox("Add created_at / updated_at to new tables");
        timestamps.setSelected(Settings.timestampColumns());
        timestamps.setMnemonicParsing(false);
        TextField fk = new TextField(Settings.fkPattern());
        fk.setPromptText("{table}_id");
        Spinner<Integer> autosave = new Spinner<>(0, 600, Settings.autosaveSeconds(), 5);
        autosave.setEditable(true);
        CheckBox reopen = new CheckBox("Reopen the diagrams that were open last time");
        reopen.setSelected(Settings.reopenLastSession());
        ComboBox<Settings.WheelMode> wheel = new ComboBox<>();
        wheel.getItems().setAll(Settings.WheelMode.values());
        wheel.setValue(Settings.wheelMode());
        wheel.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Settings.WheelMode m) {
                if (m == null) return "";
                return switch (m) {
                    case AUTO -> "Automatic (mouse zooms, touchpad scrolls)";
                    case ZOOM -> "Always zoom";
                    case PAN -> "Always scroll (Ctrl+wheel zooms)";
                };
            }
            @Override public Settings.WheelMode fromString(String s) { return null; }
        });
        ComboBox<com.erdstudio.layout.AutoLayout.Style> tidy = new ComboBox<>();
        tidy.getItems().setAll(com.erdstudio.layout.AutoLayout.Style.values());
        tidy.setValue(Settings.tidyStyle());
        CheckBox autoProjects = new CheckBox("Save projects automatically after every change");
        autoProjects.setSelected(Settings.autosaveProjects());
        TextField folder = new TextField(Settings.libraryDir());
        folder.setPromptText(com.erdstudio.io.ProjectLibrary.defaultDir().toString());
        folder.setPrefColumnCount(26);
        javafx.scene.control.Button browse = new javafx.scene.control.Button("Browse…");
        browse.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser();
            dc.setTitle("Projects folder");
            java.io.File cur = com.erdstudio.io.ProjectLibrary.configuredDir().toFile();
            if (cur.isDirectory()) dc.setInitialDirectory(cur);
            java.io.File f = dc.showDialog(getDialogPane().getScene().getWindow());
            if (f != null) folder.setText(f.getAbsolutePath());
        });
        browse.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.HBox folderRow = new javafx.scene.layout.HBox(8, folder, browse);
        javafx.scene.layout.HBox.setHgrow(folder, javafx.scene.layout.Priority.ALWAYS);
        CheckBox updates = new CheckBox("Check for new versions when ERD Studio starts");
        updates.setSelected(Settings.checkUpdates());
        CheckBox dark = new CheckBox("Dark theme");
        dark.setSelected(Theme.isDark());

        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(12);
        int r = 0;
        g.add(section("Projects"), 0, r++, 2, 1);
        g.add(autoProjects, 1, r++);
        g.addRow(r++, label("Projects folder"), folderRow);
        g.add(hint("Leave empty for the default (Documents\\ERD Studio\\Projects). Existing projects are not moved."), 1, r++);
        g.add(section("New tables"), 0, r++, 2, 1);
        g.addRow(r++, label("Default database for new diagrams"), dialect);
        g.addRow(r++, label("Primary key column of new tables"), idName);
        g.add(timestamps, 1, r++);
        g.addRow(r++, label("Foreign key column name"), fk);
        g.add(hint("{table} becomes the referenced table in singular, e.g. user_id"), 1, r++);
        g.add(section("Editor"), 0, r++, 2, 1);
        g.addRow(r++, label("Recovery backup (seconds)"), autosave);
        g.add(hint("For projects that are not saved automatically. 0 turns backups off."), 1, r++);
        g.addRow(r++, label("Mouse wheel"), wheel);
        g.add(hint("Touchpad: two-finger scroll pans, pinch zooms. Right-drag pans the canvas."), 1, r++);
        g.addRow(r++, label("Tidy up layout"), tidy);
        g.add(reopen, 1, r++);
        g.add(updates, 1, r++);
        g.add(hint("Uses GitHub Releases. Works offline too: if GitHub can't be reached, nothing happens."), 1, r++);
        g.add(dark, 1, r++);

        javafx.scene.control.ScrollPane sc = new javafx.scene.control.ScrollPane(new VBox(g));
        sc.setFitToWidth(true);
        sc.getStyleClass().add("dialog-scroll");
        sc.setPrefViewportHeight(560);
        getDialogPane().setContent(sc);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
        getDialogPane().lookupButton(save).getStyleClass().add("primary");
        setResultConverter(bt -> {
            if (bt != save) return false;
            Settings.setAutosaveProjects(autoProjects.isSelected());
            Settings.setLibraryDir(folder.getText());
            Settings.setDefaultDialect(dialect.getValue());
            Settings.setIdColumnName(idName.getText());
            Settings.setTimestampColumns(timestamps.isSelected());
            Settings.setFkPattern(fk.getText());
            Settings.setAutosaveSeconds(autosave.getValue());
            Settings.setReopenLastSession(reopen.isSelected());
            Settings.setCheckUpdates(updates.isSelected());
            Settings.setWheelMode(wheel.getValue());
            Settings.setTidyStyle(tidy.getValue());
            if (dark.isSelected() != Theme.isDark()) Theme.toggle();
            return true;
        });
    }

    private Label label(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("field-label");
        l.setStyle("-fx-font-size: 12.5px;");
        return l;
    }

    private Label section(String s) {
        Label l = new Label(s.toUpperCase());
        l.getStyleClass().add("section-title");
        l.setStyle("-fx-padding: 6 0 0 0;");
        return l;
    }

    private Label hint(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        l.setMaxWidth(340);
        return l;
    }
}
