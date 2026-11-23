package com.erdstudio.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/** Small themed message boxes. */
public final class Dialogs {
    private Dialogs() {}

    public enum SaveChoice { SAVE, DISCARD, CANCEL }

    public static void info(Window owner, String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setGraphic(null);
        if (text.length() > 160 || text.contains("\n")) {
            TextArea ta = new TextArea(text);
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setPrefSize(560, Math.min(420, 60 + text.split("\n").length * 20));
            a.getDialogPane().setContent(ta);
        } else {
            a.setContentText(text);
        }
        Theme.apply(a);
        a.showAndWait();
    }

    public static void error(Window owner, String title, String text) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setGraphic(null);
        a.setContentText(text);
        Theme.apply(a);
        a.showAndWait();
    }

    public static boolean confirm(Window owner, String title, String text, String okLabel) {
        ButtonType ok = new ButtonType(okLabel, ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.NONE, text, ok, ButtonType.CANCEL);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        a.getDialogPane().setMinWidth(440);
        a.setResizable(true);
        Theme.apply(a);
        ((Button) a.getDialogPane().lookupButton(ok)).getStyleClass().add("primary");
        return a.showAndWait().orElse(ButtonType.CANCEL) == ok;
    }

    public static SaveChoice confirmSave(Window owner, String name) {
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Don't save", ButtonBar.ButtonData.NO);
        Alert a = new Alert(Alert.AlertType.NONE, "Save changes to \"" + name + "\" before closing?", save, discard, ButtonType.CANCEL);
        a.initOwner(owner);
        a.setTitle("Unsaved changes");
        a.setHeaderText("You have unsaved changes");
        Theme.apply(a);
        ((Button) a.getDialogPane().lookupButton(save)).getStyleClass().add("primary");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() == ButtonType.CANCEL) return SaveChoice.CANCEL;
        return r.get() == save ? SaveChoice.SAVE : SaveChoice.DISCARD;
    }

    public static Optional<String> prompt(Window owner, String title, String label, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(title);
        d.setGraphic(null);
        TextField f = d.getEditor();
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        d.getDialogPane().setContent(new VBox(6, l, f));
        Theme.apply(d);
        d.setOnShown(e -> { f.requestFocus(); f.selectAll(); });
        return d.showAndWait().map(String::trim);
    }
}
