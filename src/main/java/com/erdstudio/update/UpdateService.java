package com.erdstudio.update;

import com.erdstudio.AppInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks GitHub Releases of {@link AppInfo#REPO} for a newer version and downloads the installer.
 * Everything is defensive: no network, a blocked GitHub, rate limits or odd releases all end in a
 * readable {@link UpdateException}, never in a crash. Downloads are verified with SHA-256 against
 * the checksum file published in the same release; an unverifiable download is never run.
 */
public final class UpdateService {
    private UpdateService() {}

    public record Asset(String name, String url, long size) {}

    public record Release(String version, String tag, String title, String notes, String pageUrl,
                          Asset installer, Asset checksums) {}

    public static final class UpdateException extends Exception {
        public UpdateException(String message) { super(message); }
        public UpdateException(String message, Throwable cause) { super(message, cause); }
    }

    public interface Progress {
        void update(long done, long total);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEX64 = Pattern.compile("\\b([a-fA-F0-9]{64})\\b");

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ERD-Studio/" + AppInfo.VERSION)
                .header("Accept", "application/vnd.github+json");
    }

    public static String releasesPage() {
        return "https://github.com/" + AppInfo.REPO + "/releases";
    }

    /** Latest published (non-draft, non-prerelease) release, or null if the repo has none yet. */
    public static Release latest() throws UpdateException {
        String url = "https://api.github.com/repos/" + AppInfo.REPO + "/releases/latest";
        HttpResponse<String> r;
        try {
            r = client().send(request(url).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new UpdateException("GitHub did not answer in time. Check your internet connection or VPN.", e);
        } catch (IOException e) {
            throw new UpdateException("Could not reach GitHub (" + reason(e) + "). Check your internet connection or VPN.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Cancelled.");
        } catch (Exception e) {
            throw new UpdateException("Could not check for updates: " + reason(e), e);
        }
        if (r.statusCode() == 404) return null;
        boolean limited = r.statusCode() == 429 || (r.statusCode() == 403
                && "0".equals(r.headers().firstValue("x-ratelimit-remaining").orElse("")));
        if (limited) throw new UpdateException("GitHub is limiting requests right now. Please try again in an hour.");
        if (r.statusCode() == 403)
            throw new UpdateException("GitHub refused the request (HTTP 403). It may be blocked on this network — try with a VPN.");
        if (r.statusCode() / 100 != 2) throw new UpdateException("GitHub answered with HTTP " + r.statusCode() + ".");
        try {
            JsonNode n = JSON.readTree(r.body());
            String tag = n.path("tag_name").asText("");
            String version = normalize(tag);
            if (version.isEmpty()) throw new UpdateException("The latest release has no version tag.");
            Asset installer = null, sums = null;
            for (JsonNode a : n.path("assets")) {
                String name = a.path("name").asText("");
                String low = name.toLowerCase(Locale.ROOT);
                Asset asset = new Asset(name, a.path("browser_download_url").asText(""), a.path("size").asLong(0));
                if (low.endsWith(".exe") && (installer == null || low.contains("setup"))) installer = asset;
                else if (low.equals("sha256sums.txt") || low.endsWith(".sha256") || low.endsWith(".sha256.txt")) sums = asset;
            }
            String title = n.path("name").asText("");
            return new Release(version, tag, title.isBlank() ? tag : title, n.path("body").asText(""),
                    n.path("html_url").asText(releasesPage()), installer, sums);
        } catch (UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new UpdateException("GitHub sent an unexpected answer.", e);
        }
    }

    /** "v2.3.0" -> "2.3.0"; keeps only the dotted number part. */
    public static String normalize(String tag) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(tag == null ? "" : tag);
        return m.find() ? m.group(1) : "";
    }

    /** Numeric, dotted comparison: 2.10.0 > 2.9.1; missing parts count as 0. */
    public static int compare(String a, String b) {
        String[] x = normalize(a).split("\\."), y = normalize(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    public static boolean isNewer(Release r) {
        return r != null && compare(r.version(), AppInfo.VERSION) > 0;
    }

    /** Downloads the installer, verifies its SHA-256, and returns the ready file. */
    public static Path download(Release r, Progress progress, BooleanSupplier cancelled) throws UpdateException {
        if (r.installer() == null) throw new UpdateException("This release has no Windows installer attached.");
        if (r.checksums() == null) throw new UpdateException("This release has no SHA-256 checksum file, so the download cannot be verified.");
        String expected = expectedHash(r);
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ERD Studio", "updates");
        String safe = r.installer().name().replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(safe), part = dir.resolve(safe + ".part");
        try {
            Files.createDirectories(dir);
            if (Files.exists(target) && expected.equalsIgnoreCase(sha256(target))) return target; // already downloaded
            HttpResponse<InputStream> resp = client().send(request(r.installer().url())
                            .header("Accept", "application/octet-stream").timeout(Duration.ofMinutes(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) throw new UpdateException("Download failed (HTTP " + resp.statusCode() + ").");
            long total = resp.headers().firstValueAsLong("Content-Length").orElse(r.installer().size());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0, lastReport = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (cancelled.getAsBoolean()) throw new UpdateException("Cancelled.");
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (done - lastReport > 128 * 1024 || done == total) {
                        progress.update(done, total);
                        lastReport = done;
                    }
                }
                progress.update(done, total);
            }
            String actual = HexFormat.of().formatHex(md.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(part);
                throw new UpdateException("The downloaded file is damaged or was changed (SHA-256 does not match). Nothing was installed.");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (UpdateException e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) { /* temp file */ }
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Cancelled.");
        } catch (Exception e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) { /* temp file */ }
            throw new UpdateException("Download failed: " + reason(e) + ". Check your connection and try again.", e);
        }
    }

    private static String expectedHash(Release r) throws UpdateException {
        String text;
        try {
            HttpResponse<String> resp = client().send(request(r.checksums().url()).header("Accept", "application/octet-stream")
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) throw new UpdateException("Could not download the checksum file (HTTP " + resp.statusCode() + ").");
            text = resp.body();
        } catch (UpdateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Cancelled.");
        } catch (Exception e) {
            throw new UpdateException("Could not download the checksum file: " + reason(e), e);
        }
        String hash = parseChecksum(text, r.installer().name());
        if (hash == null) throw new UpdateException("The checksum file does not list " + r.installer().name() + ".");
        return hash;
    }

    /** Accepts "HASH  name", "HASH *name", "SHA256 (name) = HASH" or a bare hash. */
    static String parseChecksum(String text, String fileName) {
        String only = null;
        int count = 0;
        for (String line : text.split("\\R")) {
            Matcher m = HEX64.matcher(line);
            if (!m.find()) continue;
            count++;
            only = m.group(1);
            if (line.contains(fileName)) return m.group(1);
        }
        return count == 1 ? only : null;
    }

    static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String reason(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        String type = t.getClass().getSimpleName();
        if (t instanceof java.net.UnknownHostException) return "no internet or DNS blocked";
        if (t instanceof java.net.ConnectException || t instanceof java.nio.channels.ClosedChannelException) return "connection failed";
        if (t instanceof javax.net.ssl.SSLException) return "secure connection failed";
        return m == null || m.isBlank() ? type : m;
    }
}
