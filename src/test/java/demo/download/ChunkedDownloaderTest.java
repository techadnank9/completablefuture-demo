package demo.download;

import demo.common.Executors2;
import demo.download.server.LocalFileServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the centrepiece: correctness first, then the speedup claim,
 * then the retry path.
 *
 * <p>Sizes and throttles are kept small so the whole suite stays well inside the
 * 30-second budget, and the speed assertion uses a generous margin - it must prove
 * the effect is real without being flaky on a loaded CI machine.
 */
class ChunkedDownloaderTest {

    /** Small enough to be quick, big enough that throttling dominates the measurement. */
    private static final int SIZE = 512 * 1024;

    /** Per-connection ceiling, in MB/s. 0.5 MB/s means the whole file takes ~1s on one stream. */
    private static final double PER_CONNECTION_MBPS = 0.5;

    @TempDir
    Path tempDir;

    private LocalFileServer server;
    private HttpClient client;
    private ExecutorService ioPool;
    private String sourceSha;

    @BeforeEach
    void setUp() throws IOException {
        byte[] content = new byte[SIZE];
        new Random(7).nextBytes(content);

        Path source = tempDir.resolve("source.bin");
        Files.write(source, content);
        sourceSha = Integrity.sha256(content);

        server = LocalFileServer.start(source, PER_CONNECTION_MBPS, 0);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        ioPool = Executors2.named("test-io", 8);
    }

    @AfterEach
    void tearDown() {
        Executors2.shutdown(ioPool);
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("the concurrently downloaded file is byte-for-byte identical to the source")
    void downloadedFileMatchesSourceHash() throws Exception {
        Path target = tempDir.resolve("chunked.bin");

        DownloadResult result = new ChunkedDownloader(client, ioPool)
                .download(server.url(), target, SIZE, 8, null);

        assertEquals(SIZE, result.bytes());
        assertEquals(SIZE, Files.size(target));
        assertEquals(sourceSha, result.sha256(), "concurrent download must not corrupt a single byte");
        assertEquals(sourceSha, Integrity.sha256(target));
    }

    @Test
    @DisplayName("under a per-connection throttle, chunked beats sequential")
    void chunkedIsFasterThanSequential() throws Exception {
        Path sequentialTarget = tempDir.resolve("sequential.bin");
        Path chunkedTarget = tempDir.resolve("parallel.bin");

        DownloadResult sequential = new SequentialDownloader(client)
                .download(server.url(), sequentialTarget, null);
        DownloadResult chunked = new ChunkedDownloader(client, ioPool)
                .download(server.url(), chunkedTarget, SIZE, 8, null);

        assertEquals(sequential.sha256(), chunked.sha256(), "both rounds must produce the same file");

        // 8 connections at 0.5 MB/s each should be near 8x. Assert only 2x so a
        // busy CI machine cannot make this flaky - the point is that the effect is real.
        double speedup = sequential.nanos() / (double) chunked.nanos();
        assertTrue(speedup > 2.0,
                () -> String.format("expected chunked to be clearly faster, but speedup was %.2fx "
                        + "(sequential %s, chunked %s)", speedup, sequential.formattedTime(), chunked.formattedTime()));
    }

    @Test
    @DisplayName("a chunk that fails once is retried and the file still verifies")
    void failingChunkIsRetried() throws Exception {
        Path target = tempDir.resolve("retried.bin");
        AtomicInteger attemptsOnChunkThree = new AtomicInteger();

        // Chunk 3 fails on its first attempt only; the retry must succeed.
        ChunkedDownloader downloader = new ChunkedDownloader(client, ioPool)
                .withFaultInjector((chunkIndex, attempt) -> {
                    if (chunkIndex == 3) {
                        attemptsOnChunkThree.incrementAndGet();
                        if (attempt == 0) {
                            throw new IOException("injected failure on chunk 3");
                        }
                    }
                });

        DownloadResult result = downloader.download(server.url(), target, SIZE, 8, null);

        assertEquals(2, attemptsOnChunkThree.get(), "chunk 3 should have been attempted twice");
        assertEquals(sourceSha, result.sha256(), "the retried chunk must land at the right offset");
    }

    @Test
    @DisplayName("a chunk that always fails gives up after the retry budget and reports the cause")
    void permanentlyFailingChunkFailsTheDownload() {
        Path target = tempDir.resolve("doomed.bin");

        ChunkedDownloader downloader = new ChunkedDownloader(client, ioPool, 2, Duration.ofSeconds(10))
                .withFaultInjector((chunkIndex, attempt) -> {
                    if (chunkIndex == 1) {
                        throw new IOException("chunk 1 is permanently broken");
                    }
                });

        IOException failure = assertThrows(IOException.class,
                () -> downloader.download(server.url(), target, SIZE, 4, null));
        assertTrue(failure.getMessage().contains("permanently broken"),
                () -> "expected the real cause to survive, got: " + failure.getMessage());
    }

    @Test
    @DisplayName("progress adds up to the full file across all chunks")
    void progressTrackerSeesEveryByte() throws Exception {
        Path target = tempDir.resolve("tracked.bin");
        ProgressTracker tracker = new ProgressTracker(ChunkPlan.split(SIZE, 4));

        new ChunkedDownloader(client, ioPool).download(server.url(), target, SIZE, 4, tracker);

        assertEquals(SIZE, tracker.totalDownloaded());
        assertTrue(tracker.isComplete());
        assertTrue(tracker.distinctWorkers() > 1, "more than one thread should have moved bytes");
    }
}
