package com.erdstudio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps one ERD Studio window per user: double-clicking an .erd file while the app is already
 * running hands the file to the running instance (over a loopback socket protected by a random
 * token) instead of starting a second copy. Any failure simply falls back to a normal start.
 */
public final class SingleInstance {
    private SingleInstance() {}

    private static ServerSocket server;
    private static volatile Consumer<List<String>> handler;
    private static volatile java.util.function.Function<String, String> mcpHandler;
    private static final List<List<String>> pending = new ArrayList<>();

    static Path portFile() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData, "ERD Studio")
                : Path.of(System.getProperty("user.home"), ".erd-studio");
        return base.resolve(AppInfo.isInstalled() ? "instance.port" : "instance-dev.port");
    }

    /** Returns true when a running instance accepted the arguments (this process should exit). */
    public static boolean handOff(String[] args) {
        Path f = portFile();
        try {
            if (!Files.exists(f)) return false;
            String[] parts = Files.readString(f).trim().split("\\s+");
            if (parts.length < 2) return false;
            int port = Integer.parseInt(parts[0]);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 700);
                s.setSoTimeout(2500);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out.println(parts[1]);
                for (String a : args) out.println(Path.of(a).toAbsolutePath());
                out.println("::end");
                return "OK".equals(in.readLine());
            }
        } catch (Exception e) {
            return false; // stale file or nothing listening: start normally
        }
    }

    /** Starts listening; files received before the window exists are queued. */
    public static synchronized void listen() {
        if (server != null) return;
        try {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            String token = HexFormat.of().formatHex(b);
            server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Path f = portFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, server.getLocalPort() + " " + token);
            Thread t = new Thread(() -> acceptLoop(token), "single-instance");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            server = null; // no single-instance support; harmless
        }
    }

    private static void acceptLoop(String token) {
        while (server != null && !server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(3000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                if (!token.equals(in.readLine())) continue;
                String first = in.readLine();
                if ("::mcp".equals(first)) {
                    String req = in.readLine();
                    java.util.function.Function<String, String> h = mcpHandler;
                    out.println(h == null || req == null ? "{\"error\":\"ERD Studio is still starting, try again.\"}" : h.apply(req));
                    continue;
                }
                List<String> files = new ArrayList<>();
                if (first != null && !first.equals("::end")) files.add(first);
                for (String line; (line = in.readLine()) != null && !line.equals("::end"); ) {
                    if (files.size() < 64) files.add(line);
                }
                out.println("OK");
                deliver(files);
            } catch (IOException ignored) {
                // next client
            }
        }
    }

    private static synchronized void deliver(List<String> files) {
        if (handler == null) pending.add(files);
        else handler.accept(files);
    }

    /** Handles one MCP tool call (JSON in, JSON out); runs on the listener thread. */
    public static void onMcp(java.util.function.Function<String, String> h) {
        mcpHandler = h;
    }

    /**
     * Sends one MCP request to the running window. Returns its JSON answer, or null when no
     * ERD Studio window is running (the caller then works on the files directly).
     */
    public static String request(String json) {
        Path f = portFile();
        try {
            if (!Files.exists(f)) return null;
            String[] parts = Files.readString(f).trim().split("\\s+");
            if (parts.length < 2) return null;
            int port = Integer.parseInt(parts[0]);
            try (Socket s = new Socket()) {
                try {
                    s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 700);
                } catch (IOException notRunning) {
                    return null;
                }
                s.setSoTimeout(120_000);
                PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out.println(parts[1]);
                out.println("::mcp");
                out.println(json.replace("\n", " ").replace("\r", " "));
                String answer = in.readLine();
                if (answer == null) return null; // another program on that port: not ERD Studio
                return answer;
            } catch (java.net.SocketTimeoutException e) {
                // the window is open but busy: never fall back to the files, the app would overwrite them
                return "{\"error\":\"ERD Studio is open but did not answer in time (is a dialog open?). Try again.\"}";
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The handler runs on the listener thread; it should hop to the FX thread itself. */
    public static synchronized void onFiles(Consumer<List<String>> h) {
        handler = h;
        for (List<String> p : pending) h.accept(p);
        pending.clear();
    }

    public static synchronized void stop() {
        try {
            if (server != null) server.close();
            Files.deleteIfExists(portFile());
        } catch (IOException ignored) {
            // nothing to clean up
        }
        server = null;
    }
}
