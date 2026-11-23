package com.erdstudio.ui;

import com.erdstudio.AppInfo;
import com.erdstudio.editor.Settings;
import com.erdstudio.update.UpdateService;
import com.erdstudio.update.UpdateService.Release;
import com.erdstudio.update.UpdateService.UpdateException;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Update flow: quiet background check on start (at most twice a day), "Help → Check for updates"
 * on demand, a dialog with Update / Later / Skip, a download with progress and SHA-256 check,
 * then the installer replaces the app. Failures during the automatic check are silent.
 */
final class Updater {
    private static final long MIN_INTERVAL_MS = 12L * 3600 * 1000;

    private final Stage stage;
    /** Saves everything and closes the app; runs the given action right before exiting. False if the user cancelled. */
    private final Predicate<Runnable> exitThen;
    private final AtomicBoolean busy = new AtomicBoolean();

    Updater(Stage stage, Predicate<Runnable> exitThen) {
        this.stage = stage;
        this.exitThen = exitThen;
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    void checkOnStartup() {
        if (!Settings.checkUpdates()) return;
        long last = Long.parseLong(Settings.get("update.lastCheck", "0"));
        if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) return;
        background(6000, false);
    }

    void checkNow() {
        background(0, true);
    }

    private void background(long delayMs, boolean manual) {
        if (!busy.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                if (delayMs > 0) Thread.sleep(delayMs);
                Release r = UpdateService.latest();
                Settings.put("update.lastCheck", String.valueOf(System.currentTimeMillis()));
                Platform.runLater(() -> {
                    busy.set(false);
                    if (UpdateService.isNewer(r)) {
                        if (manual || !r.version().equals(Settings.get("update.skip", ""))) offer(r);
                    } else if (manual) {
                        Dialogs.info(stage, "You're up to date", "ERD Studio " + AppInfo.VERSION + " is the latest version.");
                    }
                });
            } catch (UpdateException e) {
                Platform.runLater(() -> {
                    busy.set(false);
                    if (manual) Dialogs.error(stage, "Could not check for updates", e.getMessage());
                });
            } catch (Throwable e) {
                Platform.runLater(() -> busy.set(false)); // never let the updater take the app down
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private void offer(Release r) {
        ButtonType update = new ButtonType("Update now", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType skip = new ButtonType("Skip this version", ButtonBar.ButtonData.LEFT);
        Alert a = new Alert(Alert.AlertType.NONE, "", update, later, skip);
        a.initOwner(stage);
        a.setTitle("Update available");
        a.setHeaderText("ERD Studio " + r.version() + " is available");
        Label current = new Label("You have version " + AppInfo.VERSION + ". Your projects are kept; the new version replaces the old one.");
        current.setWrapText(true);
        current.getStyleClass().add("muted");
        VBox box = new VBox(10, current);
        String notes = r.notes() == null ? "" : r.notes().replace("\r", "").trim();
        if (!notes.isEmpty()) {
            Label wl = new Label("What's new");
            wl.getStyleClass().add("field-label");
            TextArea ta = new TextArea(notes);
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setPrefSize(520, Math.min(300, 40 + notes.split("\n").length * 20));
            box.getChildren().addAll(wl, ta);
        }
        a.getDialogPane().setContent(box);
        a.getDialogPane().setMinWidth(480);
        Theme.apply(a);
        ((Button) a.getDialogPane().lookupButton(update)).getStyleClass().add("primary");
        Optional<ButtonType> res = a.showAndWait();
        if (res.isEmpty() || res.get() == later) return;
        if (res.get() == skip) {
            Settings.put("update.skip", r.version());
            return;
        }
        Settings.put("update.skip", "");
        if (!AppInfo.isInstalled() || !windows() || r.installer() == null) {
            Dialogs.info(stage, "Download the new version",
                    (AppInfo.isInstalled() ? "" : "This copy is not running from the installed app (for example from IntelliJ), so it cannot replace itself.\n")
                            + "The release page will open in your browser.");
            Desktops.browse(r.pageUrl());
            return;
        }
        download(r);
    }

    private void download(Release r) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Downloading update");
        d.setHeaderText("Downloading ERD Studio " + r.version());
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(420);
        Label status = new Label("Connecting…");
        status.getStyleClass().add("muted");
        VBox box = new VBox(10, bar, status);
        box.setAlignment(Pos.CENTER_LEFT);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Theme.apply(d);
        AtomicBoolean cancelled = new AtomicBoolean();
        Path[] result = new Path[1];
        String[] error = new String[1];
        Thread t = new Thread(() -> {
            try {
                result[0] = UpdateService.download(r, (done, total) -> Platform.runLater(() -> {
                    bar.setProgress(total > 0 ? (double) done / total : ProgressBar.INDETERMINATE_PROGRESS);
                    status.setText(mb(done) + (total > 0 ? " of " + mb(total) : "") + (done == total ? "  ·  verifying…" : ""));
                }), cancelled::get);
            } catch (UpdateException e) {
                error[0] = e.getMessage();
            } catch (Throwable e) {
                error[0] = "Download failed: " + e;
            }
            Platform.runLater(() -> {
                d.setResult(ButtonType.OK);
                d.close();
            });
        }, "update-download");
        t.setDaemon(true);
        t.start();
        Optional<ButtonType> res = d.showAndWait();
        if (res.isPresent() && res.get() == ButtonType.CANCEL) {
            cancelled.set(true);
            return;
        }
        if (cancelled.get()) return;
        if (error[0] != null) {
            if (!error[0].equals("Cancelled."))
                Dialogs.error(stage, "Update failed", error[0] + "\n\nYou can also download it from:\n" + r.pageUrl());
            return;
        }
        if (result[0] == null) return;
        if (!Dialogs.confirm(stage, "Install ERD Studio " + r.version() + "?",
                "The download is verified (SHA-256). ERD Studio will save your work, close, and start the installer.", "Install and restart")) return;
        Path installer = result[0];
        exitThen.test(() -> {
            try {
                new ProcessBuilder(installer.toString()).start();
            } catch (Exception e) {
                Desktops.browse(r.pageUrl());
            }
        });
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
