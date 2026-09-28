package demo.download.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import demo.common.Executors2;
import demo.common.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

/**
 * A tiny HTTP file server built on the JDK's {@link HttpServer}, serving exactly
 * one file with full byte-range support and a bandwidth limit.
 *
 * <p>It exists so the whole download demo runs with <b>no internet connection</b>
 * - the single most important reliability property for a live classroom. It is
 * also honest: it implements the same {@code Accept-Ranges} / {@code 206 Partial
 * Content} contract that real CDNs use, so the client code under demonstration is
 * exactly the code you would write against the real thing.
 *
 * <p>Supported:
 * <ul>
 *   <li>{@code HEAD} - {@code Content-Length} and {@code Accept-Ranges: bytes}</li>
 *   <li>{@code GET} - whole file, {@code 200}</li>
 *   <li>{@code GET} with {@code Range: bytes=a-b} - {@code 206} plus {@code Content-Range}</li>
 *   <li>Unsatisfiable range - {@code 416} with {@code Content-Range: bytes} + total</li>
 * </ul>
 *
 * <p>The server runs on its own named executor ({@code server-N}) so its threads
 * never compete for the client's download pool - otherwise the measurement would
 * be of our own thread starvation rather than of concurrency.
 */
public final class LocalFileServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService serverPool;
    private final Path file;
    private final long fileSize;
    private final String fileName;
    private final double perConnectionBytesPerSecond;
    private final ThrottledOutputStream.Limiter globalLimiter;
    /** Small unthrottled preview of the served image, or {@code null} if not an image. */
    private volatile byte[] thumbnail;

    private LocalFileServer(HttpServer server, ExecutorService serverPool, Path file, long fileSize,
                            double perConnectionBytesPerSecond,
                            ThrottledOutputStream.Limiter globalLimiter) {
        this.server = server;
        this.serverPool = serverPool;
        this.file = file;
        this.fileSize = fileSize;
        this.fileName = file.getFileName().toString();
        this.perConnectionBytesPerSecond = perConnectionBytesPerSecond;
        this.globalLimiter = globalLimiter;
    }

    /**
     * Starts a server on a random free port, bound to loopback. Used by the demo.
     *
     * @param file                  the single file to serve
     * @param perConnectionMbps     per-connection ceiling in MB/s; {@code <= 0} means unlimited
     * @param totalMbps             ceiling shared across all connections in MB/s; {@code <= 0} means unlimited
     */
    public static LocalFileServer start(Path file, double perConnectionMbps, double totalMbps) throws IOException {
        // Port 0 = "pick any free port", so repeated runs never collide.
        return start(file, perConnectionMbps, totalMbps, "127.0.0.1", 0);
    }

    /**
     * Starts a server on an explicit host and port.
     *
     * <p>Deployed mode binds {@code 0.0.0.0} and the port the platform hands us in
     * {@code $PORT}, so the same class that powers the offline demo can also run as
     * a real public server for {@code --url} mode.
     */
    public static LocalFileServer start(Path file, double perConnectionMbps, double totalMbps,
                                        String host, int port) throws IOException {
        long size = Files.size(file);

        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);

        // The server gets its own pool. Client download threads live in a separate
        // pool, so neither side can starve the other and the timings stay honest.
        ExecutorService pool = Executors2.named("server", 16);
        http.setExecutor(pool);

        ThrottledOutputStream.Limiter global =
                totalMbps > 0 ? ThrottledOutputStream.Limiter.mbPerSecond(totalMbps) : null;

        LocalFileServer instance = new LocalFileServer(http, pool, file, size,
                perConnectionMbps > 0 ? perConnectionMbps * 1024 * 1024 : 0, global);

        http.createContext("/" + instance.fileName, instance::handle);
        // Liveness probe for the hosting platform. Registered as a longer prefix than
        // "/", so it wins the match and a health check never downloads the file.
        // Built once at startup and served unthrottled: the page needs to show the
        // file immediately, and the real one is deliberately slow.
        try {
            instance.thumbnail = SampleImage.thumbnail(file, 760);
        } catch (IOException | RuntimeException e) {
            instance.thumbnail = null;      // not an image, or unreadable: no preview
        }

        http.createContext("/preview.png", instance::handlePreview);
        http.createContext("/health", LocalFileServer::handleHealth);
        http.createContext("/info", instance::handleInfo);
        // The interactive page. It runs the same comparison in the browser, against
        // this same throttled server, so anyone with the URL can watch it happen.
        http.createContext("/", instance::handleRoot);
        http.start();
        return instance;
    }

    /** The URL the download demo should point at. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + fileName;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public long fileSize() {
        return fileSize;
    }

    /** The unthrottled thumbnail. 404s when the served file is not an image. */
    private void handlePreview(HttpExchange exchange) throws IOException {
        byte[] thumb = thumbnail;
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

    /** Media type from the file extension, so a browser can open it directly. */
    private String contentType() {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        return "application/octet-stream";
    }

    /** Cheap 200 for platform health checks. */
    private static void handleHealth(HttpExchange exchange) throws IOException {
        byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }

    /**
     * Serves the interactive demo page bundled in the jar.
     *
     * <p>Falls back to the plain-text description if the resource is missing, so a
     * stripped-down build still explains itself rather than 500-ing.
     */
    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            // Unknown path: not the file, not the page.
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] page = readPage();
        if (page == null) {
            handleInfo(exchange);
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

    /** Reads the bundled page, or {@code null} if it is not on the classpath. */
    private static byte[] readPage() {
        try (java.io.InputStream in = LocalFileServer.class.getResourceAsStream("/web/index.html")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Plain-text description of what is being served - handy from curl, and the
     * fallback when the bundled page is unavailable.
     */
    private void handleInfo(HttpExchange exchange) throws IOException {
        String body = "CompletableFuture demo file server\n\n"
                + "file            /" + fileName + "\n"
                + "size            " + fileSize + " bytes\n"
                + "accept-ranges   bytes\n"
                + "per-connection  " + (perConnectionBytesPerSecond > 0
                        ? String.format("%.2f MB/s", perConnectionBytesPerSecond / (1024 * 1024))
                        : "unlimited") + "\n\n"
                + "Point the demo at it:\n"
                + "  java demo.App download --url <this-url>/" + fileName + "\n";
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().add("Content-Type", contentType());
            // Without this the browser would serve round 2 from cache and "win" instantly.
            exchange.getResponseHeaders().add("Cache-Control", "no-store");

            if ("HEAD".equalsIgnoreCase(method)) {
                handleHead(exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
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

    private void handleHead(HttpExchange exchange) throws IOException {
        // -1 would mean "no body"; sendResponseHeaders with a length but HEAD must not
        // write a body, so we set Content-Length explicitly and send "no body".
        exchange.getResponseHeaders().add("Content-Length", Long.toString(fileSize));
        exchange.sendResponseHeaders(200, -1);
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");

        if (rangeHeader == null || rangeHeader.isBlank()) {
            send(exchange, 200, 0, fileSize - 1, false);
            return;
        }

        long[] range = parseRange(rangeHeader, fileSize);
        if (range == null) {
            // RFC 9110: an unsatisfiable range gets 416 plus the true total size.
            exchange.getResponseHeaders().add("Content-Range", "bytes */" + fileSize);
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        send(exchange, 206, range[0], range[1], true);
    }

    private void send(HttpExchange exchange, int status, long start, long end, boolean partial)
            throws IOException {
        long length = end - start + 1;
        if (partial) {
            exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
        }
        exchange.sendResponseHeaders(status, length);

        ThrottledOutputStream.Limiter perConnection = perConnectionBytesPerSecond > 0
                ? new ThrottledOutputStream.Limiter(perConnectionBytesPerSecond)
                : null;

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             OutputStream raw = exchange.getResponseBody();
             OutputStream out = new ThrottledOutputStream(raw, perConnection, globalLimiter)) {

            raf.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, wanted);
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
     *
     * @return {@code {start, endInclusive}} or {@code null}
     */
    static long[] parseRange(String header, long fileSize) {
        String value = header.trim().toLowerCase();
        if (!value.startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            return null;            // multi-range: not supported here
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
                long suffix = Long.parseLong(to);       // bytes=-n  ->  last n bytes
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

    @Override
    public void close() {
        server.stop(0);
        Executors2.shutdown(serverPool);
    }
}
