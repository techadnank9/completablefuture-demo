package demo.download.server;

import demo.common.Bytes;
import demo.common.Console;
import demo.common.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

/**
 * Runs {@link LocalFileServer} as a long-lived public server with a small library
 * of downloadable files, instead of as a fixture inside the offline demo.
 *
 * <p>This is what lets {@code --url} real mode, and the browser page, show a genuine
 * speedup. Public CDNs deliberately do <em>not</em> limit each connection - that is
 * the whole point of a CDN - so downloading from one in parallel gains almost
 * nothing. Serving the files ourselves gives the demo a real server, reached over
 * the real internet, that behaves the way throttled origins actually behave.
 *
 * <h2>Tuning it for a room</h2>
 * The per-connection limit only matters if it is <em>tighter than the network the
 * audience is on</em>. If the hall has 25 Mbps and each connection is capped at
 * 3 MB/s, eight ranges simply saturate the hall's uplink and no speedup appears. So
 * the deployed defaults are deliberately small: at 0.4 MB/s an 8 MB file takes about
 * twenty seconds on one connection and the peak aggregate is only around 26 Mbps.
 *
 * <p>Both are environment variables, so they can be retuned from the hosting
 * dashboard after testing on the actual network, without a redeploy.
 */
public final class ServeCommand {

    /** Defaults chosen for a shared room network, not for a datacentre. */
    private static final int DEFAULT_SIZE_MB = 8;
    private static final double DEFAULT_THROTTLE_MBPS = 0.4;
    private static final int DEFAULT_PORT = 10000;

    private ServeCommand() {
    }

    /**
     * Builds the library, starts the server and blocks forever.
     *
     * <p>Reads {@code PORT}, {@code DEMO_SIZE_MB} and {@code DEMO_THROTTLE_MBPS} from
     * the environment; explicit flags win over the environment.
     */
    public static void run(Integer portFlag, Integer sizeMbFlag, Double throttleFlag) throws Exception {
        int port = portFlag != null ? portFlag : envInt("PORT", DEFAULT_PORT);
        int sizeMb = sizeMbFlag != null ? sizeMbFlag : envInt("DEMO_SIZE_MB", DEFAULT_SIZE_MB);
        double throttle = throttleFlag != null ? throttleFlag
                : envDouble("DEMO_THROTTLE_MBPS", DEFAULT_THROTTLE_MBPS);

        Log.resetClock();
        Console.banner("FILE SERVER");

        Path workDir = Files.createTempDirectory("cf-demo-");
        List<LibraryFile> library = FileLibrary.build(workDir, sizeMb);

        // 0.0.0.0, not loopback: the platform routes external traffic to us. No global
        // cap - the per-connection limit is the whole mechanism, and a shared ceiling
        // would cap the concurrent round this is meant to show.
        // Bind to 0.0.0.0 when hosted so the platform can route to us; bind to
        // loopback when run locally, which is also the only case where adding your
        // own files through the page is enabled.
        boolean hosted = System.getenv("PORT") != null;
        String host = hosted ? "0.0.0.0" : "127.0.0.1";
        LocalFileServer server = LocalFileServer.start(library, throttle, 0, host, port);

        // Open to anyone who can reach the page, bounded by what it accepts.
        server.allowUploads(workDir);
        Log.info("Drag your own photos or videos onto the page to add them");

        long largest = library.stream().mapToLong(LibraryFile::size).max().orElse(0);
        Log.info("Serving %d files on port %d, largest %s", library.size(), port, Bytes.human(largest));
        Log.info("Per-connection limit: %.2f MB/s", throttle);
        Log.info("Largest file: about %.0fs on one connection, %.1fs across 8",
                (largest / 1048576.0) / throttle, (largest / 1048576.0) / (throttle * 8));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            deleteTree(workDir);
        }, "serve-shutdown"));

        // Park the main thread. The server's own pool does the work.
        new CountDownLatch(1).await();
    }

    /** Best-effort cleanup of the generated library on the way out. */
    private static void deleteTree(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Nothing useful to do while the JVM is going down.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            Log.warn("%s is not a number ('%s') - using %d", name, value, fallback);
            return fallback;
        }
    }

    private static double envDouble(String name, double fallback) {
        String value = System.getenv(name);
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            Log.warn("%s is not a number ('%s') - using %s", name, value, fallback);
            return fallback;
        }
    }
}
