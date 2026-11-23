package com.erdstudio.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Ctrl+K overlay: fuzzy-search every action, table, area and open tab. */
public final class CommandPalette extends StackPane {

    public record Command(String category, String label, String shortcut, String icon, Runnable action) {}

    private final Supplier<List<Command>> source;
    private final TextField search = new TextField();
    private final ListView<Command> list = new ListView<>();
    private List<Command> all = List.of();
    private Runnable onClose = () -> {};

    public CommandPalette(Supplier<List<Command>> source) {
        this.source = source;
        getStyleClass().add("palette-backdrop");
        setVisible(false);

        search.setPromptText("Type a command, a table or an area…");
        search.getStyleClass().add("palette-search");
        list.getStyleClass().add("palette-list");
        list.setCellFactory(v -> new Cell());
        list.setPrefHeight(420);
        list.setFocusTraversable(false);
        HBox searchRow = new HBox(10, Icons.icon(Icons.SEARCH, 17, "muted-icon"), search);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.getStyleClass().add("palette-search-row");
        HBox.setHgrow(search, Priority.ALWAYS);
        Label hint = new Label("↑ ↓ to move · Enter to run · Esc to close");
        hint.getStyleClass().add("hint");
        hint.setPadding(new Insets(6, 12, 8, 12));
        VBox panel = new VBox(searchRow, list, hint);
        panel.getStyleClass().add("palette");
        panel.setMaxSize(640, Region.USE_PREF_SIZE);
        StackPane.setAlignment(panel, Pos.TOP_CENTER);
        StackPane.setMargin(panel, new Insets(90, 0, 0, 0));
        getChildren().add(panel);

        search.textProperty().addListener((o, a, b) -> filter(b));
        search.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case DOWN -> { move(1); e.consume(); }
                case UP -> { move(-1); e.consume(); }
                case PAGE_DOWN -> { move(8); e.consume(); }
                case PAGE_UP -> { move(-8); e.consume(); }
                case ENTER -> { run(list.getSelectionModel().getSelectedItem()); e.consume(); }
                case ESCAPE -> { close(); e.consume(); }
                default -> { }
            }
        });
        list.setOnMouseClicked(e -> { if (e.getClickCount() >= 1) run(list.getSelectionModel().getSelectedItem()); });
        setOnMouseClicked(e -> { if (e.getTarget() == this) close(); });
    }

    public void setOnClose(Runnable r) { onClose = r; }

    public void open(String initial) {
        all = source.get();
        setVisible(true);
        search.setText(initial == null ? "" : initial);
        filter(search.getText());
        Platform.runLater(() -> { search.requestFocus(); search.end(); });
    }

    public void close() {
        setVisible(false);
        onClose.run();
    }

    private void move(int d) {
        int n = list.getItems().size();
        if (n == 0) return;
        int i = Math.max(0, Math.min(n - 1, list.getSelectionModel().getSelectedIndex() + d));
        list.getSelectionModel().select(i);
        list.scrollTo(Math.max(0, i - 4));
    }

    private void run(Command c) {
        if (c == null) return;
        close();
        Platform.runLater(c.action());
    }

    private void filter(String q) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Command> out = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Command c : all) {
            int s = query.isEmpty() ? 0 : score((c.category() + " " + c.label()).toLowerCase(Locale.ROOT), c.label().toLowerCase(Locale.ROOT), query);
            if (s < 0) continue;
            out.add(c);
            scores.add(s);
        }
        if (!query.isEmpty()) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) idx.add(i);
            idx.sort(Comparator.comparingInt(scores::get).reversed());
            List<Command> sorted = new ArrayList<>();
            for (int i : idx) sorted.add(out.get(i));
            out = sorted;
        }
        list.getItems().setAll(out.size() > 200 ? out.subList(0, 200) : out);
        if (!list.getItems().isEmpty()) list.getSelectionModel().select(0);
    }

    /** Subsequence match; higher is better, -1 = no match. Rewards prefix, word starts and contiguity. */
    static int score(String haystack, String label, String q) {
        if (label.startsWith(q)) return 1000 - label.length();
        int idx = label.indexOf(q);
        if (idx >= 0) return 800 - idx;
        idx = haystack.indexOf(q);
        if (idx >= 0) return 600 - idx;
        int s = 0, pos = 0, run = 0;
        for (char ch : q.toCharArray()) {
            if (ch == ' ') continue;
            int f = haystack.indexOf(ch, pos);
            if (f < 0) return -1;
            run = f == pos ? run + 1 : 0;
            boolean wordStart = f == 0 || haystack.charAt(f - 1) == ' ' || haystack.charAt(f - 1) == '_';
            s += 10 + run * 5 + (wordStart ? 8 : 0);
            pos = f + 1;
        }
        return s;
    }

    private static final class Cell extends ListCell<Command> {
        private final Label cat = new Label(), label = new Label(), key = new Label();
        private final StackPane icon = new StackPane();
        private final HBox row;

        Cell() {
            cat.getStyleClass().add("palette-cat");
            label.getStyleClass().add("palette-label");
            key.getStyleClass().add("kbd");
            icon.setMinWidth(20);
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            row = new HBox(10, icon, label, cat, sp, key);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("palette-row");
        }

        @Override
        protected void updateItem(Command c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) { setGraphic(null); return; }
            icon.getChildren().setAll(Icons.icon(c.icon() == null ? Icons.ZAP : c.icon(), 15, "muted-icon"));
            label.setText(c.label());
            cat.setText(c.category());
            key.setText(c.shortcut() == null ? "" : c.shortcut());
            key.setVisible(c.shortcut() != null && !c.shortcut().isEmpty());
            setGraphic(row);
        }
    }

    public static boolean isOpenKey(KeyEvent e) {
        return e.isShortcutDown() && (e.getCode() == KeyCode.K || (e.getCode() == KeyCode.P && e.isShiftDown()));
    }
}
