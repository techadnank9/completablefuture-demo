package demo.download;

import demo.common.Bytes;
import demo.common.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A single HTTP {@code HEAD} request that answers the two questions the demo
 * depends on: how big is the file, and will the server serve byte ranges?
 *
 * <p>Concurrent downloading is only possible when the server advertises
 * {@code Accept-Ranges: bytes} and reports a {@code Content-Length}. If either is
 * missing we say so plainly and fall back to the sequential round rather than
 * failing mid-demo.
 *
 * @param url            the probed URL
 * @param contentLength  file size in bytes, or {@code -1} if unknown
 * @param acceptsRanges  whether the server advertises byte-range support
 * @param fileName       last path segment, used to name the local output files
 */
public record FileProbe(String url, long contentLength, boolean acceptsRanges, String fileName) {

    /** True when the file can be split into parallel range requests. */
    public boolean supportsConcurrentDownload() {
        return acceptsRanges && contentLength > 0;
    }

    public String humanSize() {
        return contentLength > 0 ? Bytes.human(contentLength) : "unknown";
    }

    /** Issues the HEAD request. Some servers reject HEAD, so a ranged GET is the fallback. */
    public static FileProbe probe(HttpClient client, String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        HttpRequest head = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<Void> response = client.send(head, HttpResponse.BodyHandlers.discarding());
        Log.debug("HEAD %s -> %d", url, response.statusCode());

        if (response.statusCode() / 100 != 2) {
            // A few CDNs answer HEAD with 403/405 but happily serve ranged GETs.
            return probeWithRangedGet(client, uri, url);
        }
        return fromHeaders(url, uri, response);
    }

    private static FileProbe fromHeaders(String url, URI uri, HttpResponse<?> response) {
        long length = response.headers().firstValueAsLong("content-length").orElse(-1L);
        boolean ranges = response.headers().firstValue("accept-ranges")
                .map(v -> v.toLowerCase().contains("bytes"))
                .orElse(false);
        return new FileProbe(url, length, ranges, fileNameOf(uri));
    }

    /**
     * Fallback probe: ask for the first byte only. A {@code 206} plus a
     * {@code Content-Range: bytes 0-0/12345} header tells us both facts at once.
     */
    private static FileProbe probeWithRangedGet(HttpClient client, URI uri, String url)
            throws IOException, InterruptedException {
        HttpRequest get = HttpRequest.newBuilder(uri)
                .header("Range", "bytes=0-0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(get, HttpResponse.BodyHandlers.ofByteArray());
        Log.debug("ranged GET %s -> %d", url, response.statusCode());

        if (response.statusCode() == 206) {
            long total = response.headers().firstValue("content-range")
                    .map(FileProbe::totalFromContentRange)
                    .orElse(-1L);
            return new FileProbe(url, total, total > 0, fileNameOf(uri));
        }
        long length = response.headers().firstValueAsLong("content-length").orElse(-1L);
        return new FileProbe(url, length, false, fileNameOf(uri));
    }

    /** Parses the total size out of {@code bytes 0-0/12345}. */
    static long totalFromContentRange(String header) {
        int slash = header.lastIndexOf('/');
        if (slash < 0 || slash == header.length() - 1) {
            return -1L;
        }
        String total = header.substring(slash + 1).trim();
        try {
            return "*".equals(total) ? -1L : Long.parseLong(total);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String fileNameOf(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.endsWith("/")) {
            return "download.bin";
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isBlank() ? "download.bin" : name;
    }
}
