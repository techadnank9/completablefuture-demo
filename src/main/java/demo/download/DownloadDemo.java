package demo.download;

import demo.common.Bytes;
import demo.common.Console;
import demo.common.Executors2;
import demo.common.Log;
import demo.download.server.LocalFileServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * Orchestrates the real-world half of the presentation: download one file twice,
 * once the obvious way and once with {@link java.util.concurrent.CompletableFuture},
 * and put the two numbers side by side.
 *
 * <p>Rounds:
 * <ol>
 *   <li><b>Round 1</b> - {@link SequentialDownloader}, the baseline.</li>
 *   <li><b>Round 2</b> - {@link ChunkedDownloader}, N parallel range requests.</li>
 *   <li><b>Result panel</b> - time, speed, threads, and matching SHA-256.</li>
 *   <li><b>Optional</b> - {@code --compare-pools} runs 1/2/4/8/16/32 chunks to show
 *       the speedup flattening once total bandwidth, not thread count, is the limit.</li>
 * </ol>
 */
public final class DownloadDemo {

    /** Chunk counts used by the {@code --compare-pools} round. */
    private static final int[] POOL_SIZES = {1, 2, 4, 8, 16, 32};

    /** Total server bandwidth in local mode, so the pool curve flattens realistically. */
    private static final double LOCAL_TOTAL_MBPS = 24.0;

    /** Deterministic seed: the generated file - and therefore its SHA-256 - is stable across runs. */
    private static final long SAMPLE_SEED = 20260927L;

    private final Options options;

    public DownloadDemo(Options options) {
        this.options = options;
    }

    /** Parsed {@code download} command-line options. */
    public record Options(String url, int chunks, int sizeMb, double throttleMbps,
                          boolean comparePools, Path outDir) {

        public boolean localMode() {
            return url == null || url.isBlank();
        }
    }

    public void run() throws Exception {
        Log.resetClock();
        Console.banner("REAL WORLD DEMO: DOWNLOADING ONE FILE, TWO WAYS");

        LocalFileServer server = null;
        Path sampleFile = null;
        ExecutorService ioPool = null;
        ExecutorService httpPool = null;
        HttpClient client = null;

        try {
            String url;
            if (options.localMode()) {
                sampleFile = generateSampleFile(options.sizeMb());
                server = LocalFileServer.start(sampleFile, options.throttleMbps(), LOCAL_TOTAL_MBPS);
                url = server.url();
                Log.info("Local server on port %d, throttled to %.1f MB/s per connection (%.0f MB/s total)",
                        server.port(), options.throttleMbps(), LOCAL_TOTAL_MBPS);
                Log.info("Serving %s (%s)", url, Bytes.human(server.fileSize()));
            } else {
                url = options.url();
                Log.info("Real mode: %s", url);
                Console.println(Console.dim(
                        "  Note: the speedup here depends on whether the server limits speed per connection.\n"
                      + "  On an unthrottled fast link the gain can be small - that is expected, and it is\n"
                      + "  exactly the point about knowing where your bottleneck actually is."));
            }

            // Two independent pools: HTTP client internals and our download workers.
            // Neither is the common ForkJoinPool, because all of this work blocks.
            ioPool = Executors2.named("io", Math.max(options.chunks(), 8));
            httpPool = Executors2.named("http", 8);
            client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)   // one TCP connection per request, which is the point
                    .connectTimeout(Duration.ofSeconds(20))
                    .executor(httpPool)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            FileProbe probe = FileProbe.probe(client, url);
            Console.heading("Probe (HTTP HEAD)");
            Console.println("  Size          : " + probe.humanSize());
            Console.println("  Accept-Ranges : " + (probe.acceptsRanges() ? Console.green("bytes - concurrent download possible")
                                                                          : Console.yellow("not advertised")));

            Files.createDirectories(options.outDir());

            Console.pause("Press Enter to start Round 1 (sequential)...");
            DownloadResult sequential = runSequentialRound(client, probe, url);

            if (!probe.supportsConcurrentDownload()) {
                Console.blank();
                Console.println(Console.yellow(
                        "This server does not support byte ranges (or did not report a size), so the\n"
                      + "concurrent round cannot run against it. Use --local to see the comparison."));
                return;
            }

            Console.pause("Press Enter to start Round 2 (CompletableFuture)...");
            DownloadResult concurrent = runConcurrentRound(client, probe, url, ioPool, options.chunks());

            printResultPanel(sequential, concurrent);

            if (options.comparePools()) {
                Console.pause("Press Enter for the pool-size comparison...");
                runPoolComparison(client, probe, url);
            }

        } finally {
            // Every pool we created, we shut down. The HttpClient's own executor is
            // the easy one to forget: its threads are NOT daemons, so leaving it
            // running keeps the JVM alive after main() returns and the program
            // appears to hang. Creating a pool is only half of owning it.
            Executors2.shutdown(ioPool);
            Executors2.shutdown(httpPool);
            if (server != null) {
                server.close();
                Log.detail("local server stopped");
            }
            if (sampleFile != null) {
                Files.deleteIfExists(sampleFile);
                Log.detail("generated sample file removed");
            }
        }
    }

    private DownloadResult runSequentialRound(HttpClient client, FileProbe probe, String url) throws Exception {
        Console.heading("ROUND 1 - without concurrency (one stream, one thread)");
        Path target = options.outDir().resolve("sequential_" + probe.fileName());

        ProgressTracker tracker = ProgressTracker.single(
                probe.contentLength() > 0 ? probe.contentLength() : 1);

        try (ProgressRenderer renderer = new ProgressRenderer(tracker, "sequential")) {
            renderer.start();
            return new SequentialDownloader(client).download(url, target, tracker);
        }
    }

    private DownloadResult runConcurrentRound(HttpClient client, FileProbe probe, String url,
                                              ExecutorService ioPool, int chunkCount) throws Exception {
        Console.heading("ROUND 2 - with CompletableFuture (" + chunkCount + " parallel range requests)");
        Path target = options.outDir().resolve("concurrent_" + probe.fileName());

        List<ChunkPlan> plan = ChunkPlan.split(probe.contentLength(), chunkCount);
        ProgressTracker tracker = new ProgressTracker(plan);

        try (ProgressRenderer renderer = new ProgressRenderer(tracker, "concurrent")) {
            renderer.start();
            return new ChunkedDownloader(client, ioPool)
                    .download(url, target, probe.contentLength(), chunkCount, tracker);
        }
    }

    /** The side-by-side panel the audience actually looks at. */
    private void printResultPanel(DownloadResult sequential, DownloadResult concurrent) {
        boolean identical = Integrity.matches(sequential.sha256(), concurrent.sha256());
        double speedup = concurrent.nanos() > 0
                ? sequential.nanos() / (double) concurrent.nanos()
                : 0.0;

        Console.banner("RESULT");
        String format = "%-16s  %-18s  %-18s%n";
        System.out.printf(format, "", Console.bold("Sequential"), Console.bold(concurrent.mode()));
        System.out.printf(format, "Time", sequential.formattedTime(), concurrent.formattedTime());
        System.out.printf(format, "Avg speed", sequential.formattedSpeed(), concurrent.formattedSpeed());
        System.out.printf(format, "Threads used", "1", String.valueOf(concurrent.threadsUsed()));
        System.out.printf(format, "Bytes", String.format("%,d", sequential.bytes()),
                String.format("%,d", concurrent.bytes()));
        System.out.printf("%-16s  %-18s  %-18s  %s%n", "SHA-256",
                sequential.shortSha(), concurrent.shortSha(),
                identical ? Console.green("identical") : Console.red("MISMATCH"));
        System.out.printf(format, "Speedup", "", Console.green(String.format("%.1fx", speedup)));
        Console.blank();

        if (identical) {
            Console.println(Console.green(
                    "  Same bytes, same hash - " + String.format("%.1f", speedup) + " times faster."));
            Console.println(Console.dim(
                    "  " + concurrent.threadsUsed() + " threads each waited on the network at the same time."
                  + " No extra CPU was used; the waiting was simply overlapped."));
        } else {
            Console.println(Console.red("  Hashes differ - the chunk plan or the offsets are wrong."));
        }
    }

    /**
     * The resource-management round: run the same download at 1, 2, 4, 8, 16 and 32
     * chunks and print the curve. More threads are not free, and past the point where
     * total bandwidth is the limit, extra threads buy nothing and eventually cost.
     */
    private void runPoolComparison(HttpClient client, FileProbe probe, String url) throws Exception {
        Console.banner("POOL SIZE COMPARISON");
        Console.println(Console.dim(
                "  Same file, same server, increasing parallelism. Watch where the curve flattens:\n"
              + "  that is the point where the bottleneck stopped being concurrency."));
        Console.blank();
        System.out.printf("  %-8s  %-12s  %-12s  %-10s%n", "Chunks", "Time", "Speed", "vs 1 chunk");
        System.out.println("  " + "-".repeat(48));

        Path target = options.outDir().resolve("poolsize_" + probe.fileName());
        List<DownloadResult> results = new ArrayList<>();
        Double baselineNanos = null;

        for (int chunks : POOL_SIZES) {
            ExecutorService pool = Executors2.named("io" + chunks, chunks);
            try {
                DownloadResult result = new ChunkedDownloader(client, pool)
                        .download(url, target, probe.contentLength(), chunks, null);
                results.add(result);
                if (baselineNanos == null) {
                    baselineNanos = (double) result.nanos();
                }
                System.out.printf("  %-8d  %-12s  %-12s  %-10s%n",
                        chunks, result.formattedTime(), result.formattedSpeed(),
                        String.format("%.1fx", baselineNanos / result.nanos()));
            } finally {
                Executors2.shutdown(pool);
            }
        }

        Files.deleteIfExists(target);

        results.stream()
                .max((a, b) -> Double.compare(a.speedMbPerSecond(), b.speedMbPerSecond()))
                .ifPresent(best -> {
                    Console.blank();
                    Console.println("  Best: " + Console.green(best.mode())
                            + " at " + best.formattedSpeed()
                            + Console.dim(" - beyond that, more threads only add overhead."));
                });
    }

    /**
     * Generates a deterministic pseudo-random test file. The fixed seed means the
     * file - and its SHA-256 - is identical on every machine and every run, which
     * makes the integrity check reproducible rather than coincidental.
     *
     * <p>Random bytes, not zeros: zeros would compress and would not exercise the
     * transfer honestly.
     */
    public static Path generateSampleFile(int sizeMb) throws IOException {
        Path file = Files.createTempDirectory("cf-demo-").resolve("sample_video.mp4");
        long totalBytes = Bytes.megabytes(sizeMb);

        Log.info("Generating %s of test data at %s ...", Bytes.human(totalBytes), file);
        Random random = new Random(SAMPLE_SEED);
        byte[] buffer = new byte[1024 * 1024];

        try (OutputStream out = Files.newOutputStream(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long remaining = totalBytes;
            while (remaining > 0) {
                random.nextBytes(buffer);
                int write = (int) Math.min(buffer.length, remaining);
                out.write(buffer, 0, write);
                remaining -= write;
            }
        }
        file.toFile().deleteOnExit();
        return file;
    }
}
