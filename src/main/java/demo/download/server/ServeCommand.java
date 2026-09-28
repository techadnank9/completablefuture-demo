package demo.download.server;

import demo.common.Bytes;
import demo.common.Console;
import demo.common.Log;
import demo.download.DownloadDemo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Runs {@link LocalFileServer} as a long-lived public server instead of as a
 * fixture inside the demo.
 *
 * <p>This is what lets {@code --url} real mode show a genuine speedup. Public CDNs
 * deliberately do <em>not</em> limit each connection — that is the whole point of a
 * CDN — so downloading from one in parallel gains almost nothing. Deploying this
 * server gives the demo a real server, reached over the real internet, that behaves
 * the way throttled origins actually behave.
 *
 * <h2>Tuning it for a room</h2>
 * The per-connection limit only matters if it is <em>tighter than the network the
 * audience is on</em>. If the lecture hall has 25 Mbps and each connection is
 * capped at 3 MB/s, eight chunks simply saturate the hall's uplink and no speedup
 * appears. So deployed defaults are deliberately small: an 8 MB file at 0.4 MB/s
 * per connection means the sequential round takes about 20 seconds, the eight-chunk
 * round about 2.5, and the peak aggregate is only around 26 Mbps.
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
     * Generates the file, starts the server and blocks forever.
     *
     * <p>Reads {@code PORT}, {@code DEMO_SIZE_MB} and {@code DEMO_THROTTLE_MBPS}
     * from the environment; explicit flags win over the environment.
     */
    public static void run(Integer portFlag, Integer sizeMbFlag, Double throttleFlag) throws Exception {
        int port = portFlag != null ? portFlag : envInt("PORT", DEFAULT_PORT);
        int sizeMb = sizeMbFlag != null ? sizeMbFlag : envInt("DEMO_SIZE_MB", DEFAULT_SIZE_MB);
        double throttle = throttleFlag != null ? throttleFlag
                : envDouble("DEMO_THROTTLE_MBPS", DEFAULT_THROTTLE_MBPS);

        Log.resetClock();
        Console.banner("FILE SERVER");

        Path file = DownloadDemo.generateSampleFile(sizeMb);

        // 0.0.0.0, not loopback: the platform routes external traffic to us.
        // No global cap here - the per-connection limit is the whole mechanism,
        // and a shared ceiling would cap the concurrent round we are trying to show.
        LocalFileServer server = LocalFileServer.start(file, throttle, 0, "0.0.0.0", port);

        Log.info("Serving %s on port %d", Bytes.human(server.fileSize()), port);
        Log.info("Per-connection limit: %.2f MB/s", throttle);
        Log.info("Expect roughly %.0fs sequential and %.1fs with 8 chunks",
                (sizeMb / throttle), (sizeMb / (throttle * 8)));
        Log.info("File path: /%s   Health: /health", file.getFileName());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Nothing useful to do while the JVM is going down.
            }
        }, "serve-shutdown"));

        // Park the main thread. The server's own pool does the work.
        new CountDownLatch(1).await();
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
