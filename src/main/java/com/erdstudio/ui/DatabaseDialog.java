package com.erdstudio.ui;

import com.erdstudio.editor.Settings;
import com.erdstudio.io.DbReverse;
import com.erdstudio.io.ImportResult;
import com.erdstudio.model.Dialect;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Connect to PostgreSQL or MySQL/MariaDB and read the schema (read-only connection, nothing is changed).
 * Connection details except the password are remembered.
 */
public final class DatabaseDialog extends Dialog<DatabaseDialog.Choice> {

    public record Choice(ImportResult result, boolean replace) {}

    private final ComboBox<Dialect> type = new ComboBox<>();
    private final TextField host = new TextField(), port = new TextField(), database = new TextField(),
            user = new TextField(), schema = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox ssl = new CheckBox("Use SSL");
    private final Label status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private ImportResult result;

    public DatabaseDialog(Window owner, boolean diagramEmpty) {
        initOwner(owner);
        setTitle("Connect to database");
        setHeaderText("Import from a live database");
        Theme.apply(this);

        type.getItems().setAll(Dialect.POSTGRESQL, Dialect.MYSQL);
        type.setValue(Dialect.valueOf(Settings.get("db.type", Dialect.POSTGRESQL.name())));
        host.setText(Settings.get("db.host", "localhost"));
        port.setText(Settings.get("db.port", type.getValue() == Dialect.MYSQL ? "3306" : "5432"));
        database.setText(Settings.get("db.database", ""));
        user.setText(Settings.get("db.user", type.getValue() == Dialect.MYSQL ? "root" : "postgres"));
        schema.setText(Settings.get("db.schema", "public"));
        ssl.setSelected(Boolean.parseBoolean(Settings.get("db.ssl", "false")));
        type.valueProperty().addListener((o, a, b) -> {
            if (b == Dialect.MYSQL && port.getText().equals("5432")) port.setText("3306");
            if (b == Dialect.POSTGRESQL && port.getText().equals("3306")) port.setText("5432");
            schema.setDisable(b == Dialect.MYSQL);
        });
        schema.setDisable(type.getValue() == Dialect.MYSQL);
        database.setPromptText("shop");
        schema.setPromptText("public");

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        int r = 0;
        g.addRow(r++, label("Database"), type);
        g.addRow(r++, label("Host"), host, label("Port"), port);
        g.addRow(r++, label("Database name"), database, label("Schema"), schema);
        g.addRow(r++, label("User"), user, label("Password"), password);
        g.add(ssl, 1, r++);
        port.setPrefColumnCount(6);
        host.setPrefColumnCount(16);
        GridPane.setHgrow(host, Priority.ALWAYS);

        ToggleGroup mode = new ToggleGroup();
        RadioButton replace = new RadioButton("Replace current diagram");
        RadioButton merge = new RadioButton("Add to current diagram");
        replace.setToggleGroup(mode);
        merge.setToggleGroup(mode);
        (diagramEmpty ? replace : merge).setSelected(true);
        merge.setDisable(diagramEmpty);

        Button test = new Button("Test connection");
        test.setGraphic(Icons.icon(Icons.ZAP, 15));
        spinner.setPrefSize(18, 18);
        spinner.setVisible(false);
        status.getStyleClass().add("hint");
        status.setWrapText(true);
        status.setMaxWidth(560);
        HBox testRow = new HBox(10, test, spinner, status);
        testRow.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("The connection is opened read-only; nothing in the database is changed. "
                + "The password is never stored.");
        note.getStyleClass().add("hint");
        note.setWrapText(true);
        note.setMaxWidth(560);

        getDialogPane().setContent(new VBox(14, g, new HBox(18, replace, merge), testRow, note));
        ButtonType importBtn = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, importBtn);
        Button ok = (Button) getDialogPane().lookupButton(importBtn);
        ok.getStyleClass().add("primary");

        test.setOnAction(e -> run(false, null));
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (result == null) {
                e.consume(); // read first, close when done
                run(true, () -> ok.fire());
            }
        });
        setResultConverter(bt -> bt == importBtn && result != null ? new Choice(result, replace.isSelected()) : null);
        setOnShown(e -> (database.getText().isBlank() ? database : password).requestFocus());
    }

    private Label label(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("field-label");
        return l;
    }

    private DbReverse.Conn conn() {
        int p;
        try {
            p = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            p = type.getValue() == Dialect.MYSQL ? 3306 : 5432;
        }
        return new DbReverse.Conn(type.getValue(), host.getText().trim(), p, database.getText().trim(), user.getText().trim(),
                password.getText(), schema.getText().trim(), ssl.isSelected());
    }

    private void remember() {
        Settings.put("db.type", type.getValue().name());
        Settings.put("db.host", host.getText().trim());
        Settings.put("db.port", port.getText().trim());
        Settings.put("db.database", database.getText().trim());
        Settings.put("db.user", user.getText().trim());
        Settings.put("db.schema", schema.getText().trim());
        Settings.put("db.ssl", String.valueOf(ssl.isSelected()));
    }

    private void run(boolean read, Runnable onDone) {
        if (database.getText().isBlank()) {
            status.setText("Enter the database name.");
            return;
        }
        DbReverse.Conn c = conn();
        remember();
        spinner.setVisible(true);
        status.setText(read ? "Reading schema…" : "Connecting…");
        Task<Object> task = new Task<>() {
            @Override protected Object call() throws Exception {
                if (!read) return DbReverse.test(c);
                return DbReverse.read(c, msg -> Platform.runLater(() -> status.setText(msg)));
            }
        };
        task.setOnSucceeded(e -> {
            spinner.setVisible(false);
            if (read) {
                result = (ImportResult) task.getValue();
                status.setText("Read " + result.tableCount() + " tables and " + result.relationCount() + " relations.");
                if (onDone != null) onDone.run();
            } else {
                status.setText("✓ Connected: " + task.getValue());
            }
        });
        task.setOnFailed(e -> {
            spinner.setVisible(false);
            Throwable ex = task.getException();
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (msg.contains("No suitable driver")) msg = "JDBC driver missing (run the packaged app or mvn javafx:run).";
            status.setText("✗ " + msg);
        });
        Thread t = new Thread(task, "db-reverse");
        t.setDaemon(true);
        t.start();
    }
}
