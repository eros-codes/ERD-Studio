package com.erdstudio.ui;

import com.erdstudio.AppInfo;
import com.erdstudio.editor.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Help → Connect an AI assistant: explains MCP, adds ERD Studio to Claude Desktop, shows the commands. */
final class McpDialog extends Dialog<Void> {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    McpDialog(Window owner) {
        initOwner(owner);
        setTitle("Connect an AI assistant");
        setHeaderText("Let Claude (or another MCP client) read and edit your diagrams");

        Label intro = new Label("ERD Studio includes an MCP server. Once connected, you can ask the assistant things like "
                + "“design a schema for a blog”, “add a comments table” or “review this diagram for missing indexes”. "
                + "When ERD Studio is open, changes appear live on the canvas and can be undone with Ctrl+Z. "
                + "Everything stays on this computer; projects can never be deleted through it.");
        intro.setWrapText(true);
        intro.setMaxWidth(600);

        CheckBox ro = new CheckBox("Read-only: assistants may read diagrams but not change them");
        ro.setSelected(Settings.mcpReadOnly());
        ro.selectedProperty().addListener((o, a, b) -> Settings.setMcpReadOnly(b));

        List<String> cmd = command();
        String desktopJson = desktopSnippet(cmd);
        String codeCmd = "claude mcp add erd-studio -- " + String.join(" ", quoted(cmd));

        Label status = new Label();
        status.setWrapText(true);
        status.setMaxWidth(600);
        status.getStyleClass().add("muted");

        Button addDesktop = new Button("Add to Claude Desktop");
        addDesktop.getStyleClass().add("primary");
        addDesktop.setGraphic(Icons.icon(Icons.ZAP, 14));
        addDesktop.setOnAction(e -> {
            try {
                Path f = addToClaudeDesktop(cmd);
                status.setText("Added to " + f + ". Quit Claude Desktop completely (also from the tray icon) and open it again; "
                        + "“erd-studio” then appears under the tools button in a chat.");
            } catch (Exception ex) {
                status.setText("Could not update the Claude Desktop config: " + ex.getMessage()
                        + "\nCopy the JSON below into claude_desktop_config.json yourself (Claude → Settings → Developer → Edit Config).");
            }
        });
        if (!AppInfo.isInstalled()) {
            status.setText("You are running ERD Studio from the IDE. The commands below use the built jar "
                    + "(run “mvn package” first). The installed app uses erd-studio-mcp.exe instead.");
        }

        VBox box = new VBox(12, intro, ro, new HBox(10, addDesktop), status,
                block("Claude Desktop — claude_desktop_config.json", desktopJson, 7),
                block("Claude Code — run once in a terminal", codeCmd, 2),
                block("Any other MCP client (Cursor, VS Code, …) — stdio command", String.join(" ", quoted(cmd)), 2));
        getDialogPane().setContent(box);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setMinWidth(560);
        Theme.apply(this);
    }

    private VBox block(String title, String text, int rows) {
        Label l = new Label(title);
        l.getStyleClass().add("field-label");
        TextArea ta = new TextArea(text);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(rows);
        ta.setStyle("-fx-font-family: '" + Theme.MONO + "';");
        Button copy = new Button("Copy");
        copy.setGraphic(Icons.icon(Icons.COPY, 13));
        copy.getStyleClass().add("small");
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(text);
            Clipboard.getSystemClipboard().setContent(c);
            copy.setText("Copied");
        });
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(8, l, sp, copy);
        head.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, head, ta);
    }

    /** The command an MCP client should start. */
    static List<String> command() {
        String app = System.getProperty("jpackage.app-path");
        if (app != null) {
            Path exe = Path.of(app).toAbsolutePath().getParent().resolve("erd-studio-mcp.exe");
            return List.of(exe.toString());
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        Path jar = null;
        try {
            Path src = Path.of(McpDialog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            jar = Files.isRegularFile(src) ? src : findJar(src);
        } catch (Exception ignored) {
            // fall back to a relative path
        }
        return List.of(java, "-jar", jar == null ? "target\\erd-studio.jar" : jar.toString(), "--mcp");
    }

    private static Path findJar(Path classesDir) {
        Path target = classesDir.getParent();
        Path jar = target == null ? null : target.resolve("erd-studio.jar");
        return jar != null && Files.isRegularFile(jar) ? jar : target == null ? null : jar;
    }

    private static List<String> quoted(List<String> cmd) {
        List<String> out = new ArrayList<>();
        for (String c : cmd) out.add(c.contains(" ") ? "\"" + c + "\"" : c);
        return out;
    }

    private static ObjectNode serverEntry(List<String> cmd) {
        ObjectNode e = JSON.createObjectNode();
        e.put("command", cmd.get(0));
        ArrayNode args = e.putArray("args");
        for (int i = 1; i < cmd.size(); i++) args.add(cmd.get(i));
        return e;
    }

    private static String desktopSnippet(List<String> cmd) {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("mcpServers").set("erd-studio", serverEntry(cmd));
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            return "";
        }
    }

    /** Claude Desktop keeps its config in %APPDATA%\Claude (or the Store app's private copy of it). */
    static Path claudeConfig() {
        String appData = System.getenv("APPDATA");
        String local = System.getenv("LOCALAPPDATA");
        List<Path> candidates = new ArrayList<>();
        if (local != null) {
            Path pk = Path.of(local, "Packages");
            if (Files.isDirectory(pk)) {
                try (Stream<Path> s = Files.list(pk)) {
                    s.filter(p -> p.getFileName().toString().startsWith("Claude_"))
                            .forEach(p -> candidates.add(p.resolve("LocalCache").resolve("Roaming").resolve("Claude")));
                } catch (Exception ignored) {
                    // no Store version
                }
            }
        }
        if (appData != null) candidates.add(Path.of(appData, "Claude"));
        else candidates.add(Path.of(System.getProperty("user.home"), "Library", "Application Support", "Claude"));
        for (Path c : candidates) if (Files.isDirectory(c)) return c.resolve("claude_desktop_config.json");
        return candidates.get(candidates.size() - 1).resolve("claude_desktop_config.json");
    }

    /** Merges our entry into the config, keeping everything else (a .bak copy is written first). */
    static Path addToClaudeDesktop(List<String> cmd) throws Exception {
        Path f = claudeConfig();
        ObjectNode root;
        if (Files.exists(f)) {
            String text = Files.readString(f);
            Files.writeString(f.resolveSibling("claude_desktop_config.json.bak"), text);
            var node = text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
            if (!node.isObject()) throw new IllegalStateException("the file is not a JSON object");
            root = (ObjectNode) node;
        } else {
            Files.createDirectories(f.getParent());
            root = JSON.createObjectNode();
        }
        var servers = root.get("mcpServers");
        ObjectNode s = servers != null && servers.isObject() ? (ObjectNode) servers : root.putObject("mcpServers");
        s.set("erd-studio", serverEntry(cmd));
        Files.writeString(f, JSON.writeValueAsString(root));
        return f;
    }
}
