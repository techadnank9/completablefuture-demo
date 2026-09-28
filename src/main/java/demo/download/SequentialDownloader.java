package demo.download;

import demo.common.Log;
import demo.common.Stopwatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Round 1: the baseline. One GET, one stream, one thread, start to finish.
 *
 * <p>This is deliberately the boring, obvious implementation - the code almost
 * everyone writes first. It is here to be measured, not admired. Its whole job in
 * the presentation is to produce the number that Round 2 beats, and the SHA-256
 * that Round 2 must match.
 *
 * <p>Note what the calling thread does for most of this method: nothing. It sits
 * blocked in {@code read()} waiting for the network, which is exactly the waste
 * that {@code CompletableFuture} lets us reclaim.
 */
public final class SequentialDownloader {

    private final HttpClient client;

    public SequentialDownloader(HttpClient client) {
        this.client = client;
    }

    /**
     * Downloads {@code url} into {@code target}, blocking until finished.
     *
     * @param tracker optional progress sink; may be {@code null}
     */
    public DownloadResult download(String url, Path target, ProgressTracker tracker)
            throws IOException, InterruptedException {

        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.deleteIfExists(target);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        Log.info("Round 1: single stream, one thread - GET %s", url);
        Stopwatch watch = Stopwatch.start();

        if (tracker != null) {
            tracker.claim(0, Thread.currentThread().getName());
        }

        // BodyHandlers.ofInputStream() streams the response instead of buffering the
        // whole file in memory - the same discipline the chunked version uses.
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("unexpected status " + response.statusCode() + " for " + url);
        }

        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);         // one thread, blocked here for most of the run
                total += read;
                if (tracker != null) {
                    tracker.add(0, read);
                }
            }
        }

        watch.stop();
        String sha = Integrity.sha256(target);
        Log.info("Round 1 finished: %,d bytes in %s", total, watch.format());

        return new DownloadResult("Sequential", target, total, watch.elapsedNanos(), sha, 1);
    }
}
