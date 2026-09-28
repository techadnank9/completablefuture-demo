package demo.common;

/**
 * Human readable formatting for byte counts and transfer speeds.
 *
 * <p>Purely cosmetic, but the result panel is projected on a screen during the
 * talk, so "64.0 MB" reads better than "67108864".
 */
public final class Bytes {

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    private Bytes() {
    }

    /** Converts megabytes to bytes (binary megabytes, 1 MB = 1048576 bytes). */
    public static long megabytes(long mb) {
        return mb * MB;
    }

    /** e.g. {@code 67108864 -> "64.0 MB"}. */
    public static String human(long bytes) {
        if (bytes < KB) {
            return bytes + " B";
        }
        if (bytes < MB) {
            return String.format("%.1f KB", bytes / (double) KB);
        }
        if (bytes < GB) {
            return String.format("%.1f MB", bytes / (double) MB);
        }
        return String.format("%.2f GB", bytes / (double) GB);
    }

    /** Average speed in MB/s, e.g. {@code "20.0 MB/s"}. */
    public static String speed(long bytes, long nanos) {
        return String.format("%.1f MB/s", speedMbPerSecond(bytes, nanos));
    }

    /** Average speed as a raw number of megabytes per second. */
    public static double speedMbPerSecond(long bytes, long nanos) {
        if (nanos <= 0) {
            return 0.0;
        }
        double seconds = nanos / 1_000_000_000.0;
        return (bytes / (double) MB) / seconds;
    }
}
