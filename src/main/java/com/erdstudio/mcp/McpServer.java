package com.erdstudio.mcp;

import com.erdstudio.AppInfo;
import com.erdstudio.SingleInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * MCP server over stdio (newline-delimited JSON-RPC 2.0), started with {@code --mcp}.
 * Tool calls go to the running ERD Studio window when there is one (live edits, undo),
 * otherwise straight to the project files. Nothing but protocol messages is written to stdout.
 */
public final class McpServer {
    private McpServer() {}

    private static final Set<String> VERSIONS = Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
    private static final String LATEST = "2025-06-18";

    public static void run() throws Exception {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err); // anything printed by libraries goes to stderr, never into the protocol
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        FileBackend files = new FileBackend();
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try {
                msg = McpTools.JSON.readTree(line);
            } catch (Exception e) {
                send(out, error(null, -32700, "Parse error"));
                continue;
            }
            if (msg.isArray()) { // batches (older spec)
                for (JsonNode m : msg) { ObjectNode r = handle(m, files); if (r != null) send(out, r); }
                continue;
            }
            ObjectNode r = handle(msg, files);
            if (r != null) send(out, r);
        }
    }

    private static synchronized void send(PrintStream out, ObjectNode msg) {
        try {
            out.println(McpTools.JSON.writeValueAsString(msg));
            out.flush();
        } catch (Exception ignored) {
            // client gone
        }
    }

    private static ObjectNode handle(JsonNode m, FileBackend files) {
        JsonNode id = m.get("id");
        String method = m.path("method").asText("");
        if (id == null || id.isNull()) return null; // notification (initialized, cancelled, …)
        try {
            switch (method) {
                case "initialize" -> {
                    String asked = m.path("params").path("protocolVersion").asText(LATEST);
                    ObjectNode res = McpTools.JSON.createObjectNode();
                    res.put("protocolVersion", VERSIONS.contains(asked) ? asked : LATEST);
                    res.putObject("capabilities").putObject("tools").put("listChanged", false);
                    ObjectNode info = res.putObject("serverInfo");
                    info.put("name", "erd-studio");
                    info.put("title", "ERD Studio");
                    info.put("version", AppInfo.VERSION);
                    res.put("instructions", McpTools.serverInstructions());
                    return result(id, res);
                }
                case "ping" -> { return result(id, McpTools.JSON.createObjectNode()); }
                case "tools/list" -> {
                    ObjectNode res = McpTools.JSON.createObjectNode();
                    res.set("tools", McpTools.list());
                    return result(id, res);
                }
                case "tools/call" -> {
                    JsonNode p = m.path("params");
                    return result(id, callTool(p.path("name").asText(""), p.get("arguments"), files));
                }
                case "resources/list" -> {
                    ObjectNode res = McpTools.JSON.createObjectNode();
                    res.putArray("resources");
                    return result(id, res);
                }
                case "prompts/list" -> {
                    ObjectNode res = McpTools.JSON.createObjectNode();
                    res.putArray("prompts");
                    return result(id, res);
                }
                default -> { return error(id, -32601, "Method not found: " + method); }
            }
        } catch (Exception e) {
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    /** Live first (the open window), files otherwise. */
    static ObjectNode callTool(String name, JsonNode args, FileBackend files) {
        ObjectNode req = McpTools.JSON.createObjectNode();
        req.put("tool", name);
        req.set("args", args == null ? McpTools.JSON.createObjectNode() : args);
        String text;
        boolean isError;
        String live = null;
        try {
            live = SingleInstance.request(McpTools.JSON.writeValueAsString(req));
        } catch (Exception ignored) {
            // app not reachable
        }
        if (live != null) {
            try {
                JsonNode r = McpTools.JSON.readTree(live);
                isError = r.has("error");
                text = isError ? r.path("error").asText() : r.path("result").asText();
            } catch (Exception e) {
                isError = true;
                text = "ERD Studio sent an unreadable answer.";
            }
        } else {
            try {
                text = McpTools.call(name, args, files);
                isError = false;
            } catch (Backend.ToolError e) {
                text = e.getMessage();
                isError = true;
            } catch (Exception e) {
                text = "Error: " + e;
                isError = true;
            }
        }
        ObjectNode res = McpTools.JSON.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", text);
        res.put("isError", isError);
        return res;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = McpTools.JSON.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = McpTools.JSON.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id == null) r.putNull("id"); else r.set("id", id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r;
    }
}
