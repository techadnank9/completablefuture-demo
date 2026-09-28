package demo.download.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import demo.common.Executors2;
import demo.common.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * A small HTTP server built on the JDK's {@link HttpServer}, serving a library of
 * files with full byte-range support and a per-connection bandwidth limit.
 *
 * <p>It exists so the whole download demo runs with <b>no internet connection</b>,
 * the single most important reliability property for a live classroom. It is also
 * honest: it implements the same {@code Accept-Ranges} / {@code 206 Partial
 * Content} contract that real CDNs use, so the client code under demonstration is
 * exactly the code you would write against the real thing.
 *
 * <h2>Why throttle at all?</h2>
 * On localhost a file copies in well under a second, so both rounds would look
 * instant and the demo would prove nothing. Real origins limit <em>per
 * connection</em>, which is precisely why opening several is faster. This
 * reproduces that offline.
 *
 * <p>Server threads live in their own pool so they never compete with the client's
 * download threads; otherwise the measurement would be of our own thread starvation
 * rather than of concurrency.
 */
public final class LocalFileServer implements AutoCloseable {

    /** Bounds that keep an open endpoint from becoming an anonymous file host. */
    private static final long MAX_UPLOAD_BYTES = 120L * 1024 * 1024;
    private static final int MAX_UPLOADS = 6;
    private static final Duration UPLOAD_TTL = Duration.ofHours(1);

    private final HttpServer server;
    private final ExecutorService serverPool;
    private final Map<String, LibraryFile> byId = new LinkedHashMap<>();
    private final double perConnectionBytesPerSecond;
    private final ThrottledOutputStream.Limiter globalLimiter;
    private final String singleFileName;      // non-null only in single-file mode
    /** Where uploads land. Null when uploading is not permitted. */
    private volatile Path uploadDir;
    /** Ids of uploaded files, oldest first, with the instant each arrived. */
    private final Map<String, java.time.Instant> uploaded = new LinkedHashMap<>();
    private java.util.concurrent.ScheduledExecutorService sweeper;

    private LocalFileServer(HttpServer server, ExecutorService serverPool,
                            List<LibraryFile> files, double perConnectionBytesPerSecond,
                            ThrottledOutputStream.Limiter globalLimiter, String singleFileName) {
        this.server = server;
        this.serverPool = serverPool;
        this.perConnectionBytesPerSecond = perConnectionBytesPerSecond;
        this.globalLimiter = globalLimiter;
        this.singleFileName = singleFileName;
        files.forEach(f -> byId.put(f.id(), f));
    }

    /**
     * Single-file mode, on a random loopback port. Used by the offline demo and the
     * tests: the file is served at {@code /<name>} exactly as before.
     */
    public static LocalFileServer start(Path file, double perConnectionMbps, double totalMbps)
            throws IOException {
        String name = file.getFileName().toString();
        LibraryFile single = new LibraryFile(name, name, name, "File",
                guessContentType(name), file, Files.size(file), null);
        return start(List.of(single), perConnectionMbps, totalMbps, "127.0.0.1", 0, name);
    }

    /** Library mode: several files, bound to an explicit host and port. */
    public static LocalFileServer start(List<LibraryFile> files, double perConnectionMbps,
                                        double totalMbps, String host, int port) throws IOException {
        return start(files, perConnectionMbps, totalMbps, host, port, null);
    }

    /**
     * Permits adding files through the page, storing them in {@code dir}.
     *
     * <p>Open to anyone who can reach the page, and kept harmless by what it accepts
     * rather than by who is asking: images and video only, each one capped, only a
     * few kept at a time, and all of them swept away after an hour. That leaves a
     * demo feature rather than free storage for somebody else's payload.
     */
    public LocalFileServer allowUploads(Path dir) {
        this.uploadDir = dir;
        this.sweeper = Executors2.scheduler("sweep");
        this.sweeper.scheduleAtFixedRate(this::sweepExpired, 5, 5,
                java.util.concurrent.TimeUnit.MINUTES);
        return this;
    }

    /** Drops uploads older than the retention window. */
    private synchronized void sweepExpired() {
        java.time.Instant cutoff = java.time.Instant.now().minus(UPLOAD_TTL);
        uploaded.entrySet().stream()
                .filter(e -> e.getValue().isBefore(cutoff))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::forget);
    }

    /** Removes one uploaded file from the library and from disk. */
    private synchronized void forget(String id) {
        LibraryFile gone = byId.remove(id);
        uploaded.remove(id);
        if (gone != null) {
            try {
                Files.deleteIfExists(gone.path());
            } catch (IOException ignored) {
                // It will go with the container anyway.
            }
        }
    }

    public boolean uploadsAllowed() {
        return uploadDir != null;
    }

    private static LocalFileServer start(List<LibraryFile> files, double perConnectionMbps,
                                         double totalMbps, String host, int port,
                                         String singleFileName) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
        ExecutorService pool = Executors2.named("server", 16);
        http.setExecutor(pool);

        ThrottledOutputStream.Limiter global =
                totalMbps > 0 ? ThrottledOutputStream.Limiter.mbPerSecond(totalMbps) : null;

        LocalFileServer instance = new LocalFileServer(http, pool, files,
                perConnectionMbps > 0 ? perConnectionMbps * 1024 * 1024 : 0, global, singleFileName);

        // Longer prefixes win, so these never collide with the catch-all page route.
        http.createContext("/health", LocalFileServer::handleHealth);
        http.createContext("/api/files", instance::handleList);
        http.createContext("/api/config", instance::handleConfig);
        http.createContext("/api/upload", instance::handleUpload);
        http.createContext("/api/delete", instance::handleDelete);
        http.createContext("/files/", instance::handleLibraryFile);
        http.createContext("/thumb/", instance::handleThumb);

        if (singleFileName != null) {
            http.createContext("/" + singleFileName, instance::handleSingle);
        }
        http.createContext("/", instance::handleRoot);

        http.start();
        return instance;
    }

    /** The URL the offline demo should point at. Single-file mode only. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + singleFileName;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Size of the only file, in single-file mode. */
    public long fileSize() {
        return byId.values().iterator().next().size();
    }

    public List<LibraryFile> files() {
        return List.copyOf(byId.values());
    }

    // ---- routes ----------------------------------------------------------

    private static void handleHealth(HttpExchange exchange) throws IOException {
        sendText(exchange, 200, "ok", "text/plain; charset=utf-8");
    }

    /** The picker's data. Hand-rolled JSON; the project carries no JSON dependency. */
    private void handleList(HttpExchange exchange) throws IOException {
        String json;
        synchronized (this) {
            json = byId.values().stream()
                    // Only files added through the page may be taken away again; the
                    // ones shipped with the jar are not a visitor's to delete.
                    .map(f -> f.toJson().replaceFirst("\\}$",
                            ",\"removable\":" + uploaded.containsKey(f.id()) + "}"))
                    .collect(Collectors.joining(",", "[", "]"));
        }
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        sendText(exchange, 200, json, "application/json; charset=utf-8");
    }

    /** Tells the page which optional features this instance offers. */
    private void handleConfig(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        sendText(exchange, 200,
                "{\"uploads\":" + uploadsAllowed() + "}",
                "application/json; charset=utf-8");
    }

    /**
     * Accepts a file from the page and adds it to the library.
     *
     * <p>Raw body, with the name in the query string: enough for a local tool, and it
     * avoids pulling in a multipart parser for a project that carries no dependencies.
     */
    private void handleUpload(HttpExchange exchange) throws IOException {
        Path dir = uploadDir;
        if (dir == null) {
            sendText(exchange, 403, "uploads are disabled on this server",
                    "text/plain; charset=utf-8");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String query = exchange.getRequestURI().getRawQuery();
        String rawName = query == null ? "" : query.replaceFirst("^name=", "");
        String name = sanitize(java.net.URLDecoder.decode(rawName, StandardCharsets.UTF_8));
        String type = guessContentType(name);
        if (type.equals("application/octet-stream")) {
            sendText(exchange, 415, "only images and videos can be added",
                    "text/plain; charset=utf-8");
            return;
        }

        Path target = dir.resolve(name);
        long written;
        try (java.io.InputStream in = exchange.getRequestBody()) {
            written = Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (written > MAX_UPLOAD_BYTES) {
            Files.deleteIfExists(target);
            sendText(exchange, 413, "file is larger than the 120 MB limit",
                    "text/plain; charset=utf-8");
            return;
        }

        boolean image = type.startsWith("image/");
        byte[] thumb = null;
        if (image) {
            try {
                thumb = SampleImage.thumbnail(target, 760);
            } catch (IOException | RuntimeException e) {
                thumb = null;                        // a format ImageIO cannot read
            }
        }
        if (thumb == null) {
            thumb = SampleImage.placeholder(extensionOf(name));
        }
        String id = slug(name);
        synchronized (this) {
            byId.put(id, new LibraryFile(id, stripExtension(name), name,
                    image ? "Photo" : "Video", type, target, written, thumb));
            uploaded.put(id, java.time.Instant.now());
            while (uploaded.size() > MAX_UPLOADS) {
                forget(uploaded.keySet().iterator().next());
            }
        }

        Log.info("added to library: %s (%,d bytes)", name, written);
        sendText(exchange, 200, "{\"id\":\"" + id + "\"}", "application/json; charset=utf-8");
    }

    /**
     * Removes a previously uploaded file.
     *
     * <p>Refuses anything that came with the jar, so the shipped library cannot be
     * emptied by whoever happens to open the page.
     */
    private void handleDelete(HttpExchange exchange) throws IOException {
        if (uploadDir == null) {
            sendText(exchange, 403, "uploads are disabled on this server",
                    "text/plain; charset=utf-8");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        String id = query == null ? "" : query.replaceFirst("^id=", "");

        synchronized (this) {
            if (!uploaded.containsKey(id)) {
                sendText(exchange, 403, "only files you added can be removed",
                        "text/plain; charset=utf-8");
                return;
            }
            forget(id);
        }
        Log.info("removed from library: %s", id);
        sendText(exchange, 200, "{\"ok\":true}", "application/json; charset=utf-8");
    }

    /** Strips any path components, so an upload cannot escape its directory. */
    private static String sanitize(String name) {
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.isBlank() ? "upload.bin" : base;
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "file";
    }

    private void handleLibraryFile(HttpExchange exchange) throws IOException {
        LibraryFile file = byId.get(lastSegment(exchange));
        if (file == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        serve(exchange, file);
    }

    private void handleSingle(HttpExchange exchange) throws IOException {
        serve(exchange, byId.get(singleFileName));
    }

    /** Unthrottled preview, so the picker paints immediately. */
    private void handleThumb(HttpExchange exchange) throws IOException {
        LibraryFile file = byId.get(lastSegment(exchange));
        byte[] thumb = file == null ? null : file.thumbnail();
        if (thumb == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
        exchange.getResponseHeaders().add("Cache-Control", "public, max-age=300");
        exchange.sendResponseHeaders(200, thumb.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(thumb);
        }
        exchange.close();
    }

    /** The page, or a plain-text description when it is not on the classpath. */
    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] page = readPage();
        if (page == null) {
            sendText(exchange, 200, describe(), "text/plain; charset=utf-8");
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(page);
        }
        exchange.close();
    }

    // ---- the range contract ---------------------------------------------

    private void serve(HttpExchange exchange, LibraryFile file) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().add("Content-Type", file.contentType());
            // Without this the browser would serve the second round from cache and
            // "win" instantly.
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            // Serve exactly the declared type; never let a browser sniff it into
            // something it could execute.
            exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");

            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().add("Content-Length", Long.toString(file.size()));
                exchange.sendResponseHeaders(200, -1);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange, file);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        } catch (IOException e) {
            // A client that cancels mid-transfer is normal; never let it kill the server.
            Log.debug("server: connection ended early (%s)", e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleGet(HttpExchange exchange, LibraryFile file) throws IOException {
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        if (rangeHeader == null || rangeHeader.isBlank()) {
            send(exchange, file, 200, 0, file.size() - 1, false);
            return;
        }
        long[] range = parseRange(rangeHeader, file.size());
        if (range == null) {
            // RFC 9110: an unsatisfiable range gets 416 plus the true total size.
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + file.size());
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        send(exchange, file, 206, range[0], range[1], true);
    }

    private void send(HttpExchange exchange, LibraryFile file, int status,
                      long start, long end, boolean partial) throws IOException {
        long length = end - start + 1;
        if (partial) {
            exchange.getResponseHeaders().add("Content-Range",
                    "bytes " + start + "-" + end + "/" + file.size());
        }
        exchange.sendResponseHeaders(status, length);

        ThrottledOutputStream.Limiter perConnection = perConnectionBytesPerSecond > 0
                ? new ThrottledOutputStream.Limiter(perConnectionBytesPerSecond)
                : null;

        try (RandomAccessFile raf = new RandomAccessFile(file.path().toFile(), "r");
             OutputStream raw = exchange.getResponseBody();
             OutputStream out = new ThrottledOutputStream(raw, perConnection, globalLimiter)) {

            raf.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /**
     * Parses a single-range {@code Range} header.
     *
     * <p>Handles {@code bytes=a-b}, {@code bytes=a-} (to end) and {@code bytes=-n}
     * (last n bytes). Returns {@code null} for anything unsatisfiable or for
     * multi-range requests, which this server deliberately does not support.
     */
    static long[] parseRange(String header, long fileSize) {
        String value = header.trim().toLowerCase();
        if (!value.startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String from = spec.substring(0, dash).trim();
        String to = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (from.isEmpty()) {
                if (to.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(to);       // bytes=-n -> last n bytes
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, fileSize - suffix);
                end = fileSize - 1;
            } else {
                start = Long.parseLong(from);
                end = to.isEmpty() ? fileSize - 1 : Long.parseLong(to);
            }
            if (start < 0 || start >= fileSize || end < start) {
                return null;
            }
            return new long[]{start, Math.min(end, fileSize - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static String lastSegment(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void sendText(HttpExchange exchange, int status, String body, String type)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private String describe() {
        StringBuilder sb = new StringBuilder("CompletableFuture demo file server\n\n");
        byId.values().forEach(f -> sb.append(String.format("  /files/%-16s %-6s %,12d bytes%n",
                f.id(), f.kind(), f.size())));
        sb.append(String.format("%nper-connection limit  %s%n", perConnectionBytesPerSecond > 0
                ? String.format("%.2f MB/s", perConnectionBytesPerSecond / (1024 * 1024))
                : "unlimited"));
        return sb.toString();
    }

    private static byte[] readPage() {
        try (java.io.InputStream in = LocalFileServer.class.getResourceAsStream("/web/index.html")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Media type by extension.
     *
     * <p>This doubles as the upload allowlist, so it covers what phones and cameras
     * actually produce rather than only what this project generates. SVG is
     * deliberately absent: it can carry script, and serving one from this origin
     * would hand a visitor a way to run code on it.
     */
    static String guessContentType(String name) {
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "avif" -> "image/avif";
            case "heic", "heif" -> "image/heic";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "avi" -> "video/x-msvideo";
            case "ogv" -> "video/ogg";
            case "mpeg", "mpg" -> "video/mpeg";
            default -> "application/octet-stream";
        };
    }

    @Override
    public void close() {
        server.stop(0);
        Executors2.shutdown(sweeper);
        Executors2.shutdown(serverPool);
    }
}
