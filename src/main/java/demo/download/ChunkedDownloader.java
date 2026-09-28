package demo.download;

import demo.common.Log;
import demo.common.Stopwatch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Round 2: the same file, downloaded as N parallel HTTP range requests composed
 * with {@link CompletableFuture}. This class is the centrepiece of the talk.
 *
 * <h2>The shape of the solution</h2>
 * <ol>
 *   <li><b>Plan</b> - {@link ChunkPlan#split} cuts {@code [0, size)} into N
 *       non-overlapping ranges.</li>
 *   <li><b>Pre-allocate</b> - the output file is sized up front, so every chunk
 *       has a valid offset to write into from the very first byte.</li>
 *   <li><b>Fan out</b> - one {@code supplyAsync} per chunk on our own I/O
 *       executor, each sending {@code Range: bytes=start-end} and writing its
 *       bytes at its own offset with a positional {@link FileChannel} write. The
 *       ranges never overlap, so there is no lock anywhere in the hot path.</li>
 *   <li><b>Resilience</b> - each attempt is wrapped in {@code orTimeout} and
 *       retried via {@code exceptionallyCompose}, up to {@code maxRetries}.</li>
 *   <li><b>Fan in</b> - {@code allOf(...)} then {@code thenApply} to finalize, then
 *       {@code thenApply} again to hash the result. Nothing blocks until the very
 *       last {@code join()}.</li>
 * </ol>
 *
 * <h2>Why this is faster</h2>
 * It is not more CPU. Every connection spends its life blocked on the network;
 * N connections simply wait in parallel, and a server that limits each connection
 * separately hands us N times the bandwidth.
 */
public final class ChunkedDownloader {

    /** Hook used by the tests to make specific attempts fail on purpose. */
    @FunctionalInterface
    public interface FaultInjector {
        /**
         * @param chunkIndex zero-based chunk
         * @param attempt    zero-based attempt number
         * @throws IOException to simulate a failed chunk
         */
        void maybeFail(int chunkIndex, int attempt) throws IOException;
    }

    private final HttpClient client;
    private final Executor ioExecutor;
    private final int maxRetries;
    private final Duration perChunkTimeout;
    private FaultInjector faultInjector = (chunk, attempt) -> { };

    public ChunkedDownloader(HttpClient client, Executor ioExecutor) {
        this(client, ioExecutor, 2, Duration.ofMinutes(5));
    }

    public ChunkedDownloader(HttpClient client, Executor ioExecutor, int maxRetries, Duration perChunkTimeout) {
        this.client = client;
        this.ioExecutor = ioExecutor;
        this.maxRetries = maxRetries;
        this.perChunkTimeout = perChunkTimeout;
    }

    /** Installs a fault injector (tests only). Returns {@code this} for chaining. */
    public ChunkedDownloader withFaultInjector(FaultInjector injector) {
        this.faultInjector = injector == null ? (chunk, attempt) -> { } : injector;
        return this;
    }

    /**
     * Downloads {@code url} into {@code target} using {@code chunkCount} parallel
     * range requests.
     *
     * @param tracker progress sink shared by all workers; may be {@code null}
     */
    public DownloadResult download(String url, Path target, long totalBytes, int chunkCount,
                                   ProgressTracker tracker) throws IOException {

        List<ChunkPlan> chunks = ChunkPlan.split(totalBytes, chunkCount);
        ProgressTracker progress = tracker != null ? tracker : new ProgressTracker(chunks);

        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.deleteIfExists(target);

        Log.info("Round 2: %d parallel range requests - GET %s", chunks.size(), url);
        Stopwatch watch = Stopwatch.start();

        try (FileChannel channel = FileChannel.open(target,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {

            // Pre-allocate: every chunk can then write straight to its own offset.
            channel.truncate(0);
            if (totalBytes > 0) {
                channel.write(ByteBuffer.allocate(1), totalBytes - 1);
            }

            // DEMO: fan out - one future per chunk, all submitted before any of them finishes.
            List<CompletableFuture<Long>> futures = new ArrayList<>(chunks.size());
            for (ChunkPlan chunk : chunks) {
                futures.add(downloadChunkWithRetry(url, channel, chunk, progress, 0));
            }

            // DEMO: fan in - allOf completes only when every chunk has completed.
            CompletableFuture<Void> all =
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));

            // DEMO: thenApply - the "merge / finalize" step. Because every chunk wrote
            // straight into the final file at its own offset, merging is just summing
            // the byte counts; there are no temp files to stitch together.
            CompletableFuture<Long> totalWritten = all.thenApply(ignored ->
                    futures.stream().mapToLong(CompletableFuture::join).sum());

            // DEMO: thenApply again - hashing is just one more stage of the same pipeline.
            CompletableFuture<DownloadResult> resultFuture = totalWritten.thenApply(written -> {
                watch.stop();
                try {
                    channel.force(true);
                    String sha = Integrity.sha256(target);
                    return new DownloadResult(
                            "Concurrent (" + chunks.size() + " chunks)",
                            target, written, watch.elapsedNanos(), sha, progress.distinctWorkers());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            // The one and only blocking point in the whole round.
            DownloadResult result = resultFuture.join();
            Log.info("Round 2 finished: %,d bytes in %s across %d threads",
                    result.bytes(), result.formattedTime(), result.threadsUsed());
            return result;

        } catch (CompletionException e) {
            throw asIoException(e);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * One chunk, with timeout and retry.
     *
     * <p>{@code orTimeout} completes the future exceptionally if the chunk takes too
     * long; {@code exceptionallyCompose} (Java 12+) then returns a <em>new</em>
     * future for the next attempt, which is how a retry is expressed without ever
     * blocking a thread. The chunk's progress counter is reset first, because the
     * retry re-downloads the whole range.
     */
    private CompletableFuture<Long> downloadChunkWithRetry(String url, FileChannel channel, ChunkPlan chunk,
                                                           ProgressTracker progress, int attempt) {

        // DEMO: supplyAsync on OUR executor, never the common ForkJoinPool - this is blocking I/O.
        CompletableFuture<Long> future = CompletableFuture
                .supplyAsync(() -> downloadRange(url, channel, chunk, progress, attempt), ioExecutor)
                // DEMO: orTimeout - a stalled chunk fails fast instead of hanging the whole demo.
                .orTimeout(perChunkTimeout.toMillis(), TimeUnit.MILLISECONDS);

        // DEMO: exceptionallyCompose - recover by returning another future (the retry).
        return future.exceptionallyCompose(error -> {
            if (attempt >= maxRetries) {
                Log.error("chunk#" + chunk.index() + " failed after " + (attempt + 1) + " attempts", error);
                return CompletableFuture.failedFuture(error);
            }
            Log.warn("chunk#%d failed (%s) - retry %d of %d",
                    chunk.index(), Log.rootCause(error).getClass().getSimpleName(), attempt + 1, maxRetries);
            progress.reset(chunk.index());
            return downloadChunkWithRetry(url, channel, chunk, progress, attempt + 1);
        });
    }

    /** The actual blocking work for one chunk. Runs on an {@code io-N} thread. */
    private long downloadRange(String url, FileChannel channel, ChunkPlan chunk,
                               ProgressTracker progress, int attempt) {
        progress.claim(chunk.index(), Thread.currentThread().getName());
        try {
            faultInjector.maybeFail(chunk.index(), attempt);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Range", chunk.rangeHeader())
                    .timeout(perChunkTimeout)
                    .GET()
                    .build();

            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            // 206 is the contract. A 200 here would mean the server ignored our Range
            // and is sending the whole file down every connection - fail loudly rather
            // than silently corrupting the output.
            if (response.statusCode() != 206) {
                throw new IOException("expected 206 Partial Content for " + chunk.rangeHeader()
                        + ", got " + response.statusCode());
            }

            long position = chunk.start();
            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = response.body()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    // Positional write: thread safe, and it does not touch the shared
                    // channel position. Ranges never overlap, so no lock is needed.
                    ByteBuffer slice = ByteBuffer.wrap(buffer, 0, read);
                    while (slice.hasRemaining()) {
                        position += channel.write(slice, position);
                    }
                    written += read;
                    progress.add(chunk.index(), read);
                }
            }

            if (written != chunk.length()) {
                throw new IOException("chunk#" + chunk.index() + " expected " + chunk.length()
                        + " bytes but received " + written);
            }
            return written;

        } catch (IOException e) {
            throw new CompletionException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private static IOException asIoException(Throwable error) {
        Throwable cause = Log.rootCause(error);
        return cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
    }
}
