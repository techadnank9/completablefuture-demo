package demo.download;

import demo.common.Bytes;
import demo.common.Stopwatch;

import java.nio.file.Path;

/**
 * The outcome of one download round - everything the result panel needs.
 *
 * @param mode        human label for the round, e.g. {@code "Sequential"} or {@code "Concurrent (8 chunks)"}
 * @param path        where the file was written
 * @param bytes       number of bytes downloaded
 * @param nanos       wall-clock duration of the round
 * @param sha256      hex SHA-256 of the written file, the correctness proof
 * @param threadsUsed how many distinct worker threads actually moved bytes
 */
public record DownloadResult(String mode, Path path, long bytes, long nanos, String sha256, int threadsUsed) {

    public double seconds() {
        return nanos / 1_000_000_000.0;
    }

    public String formattedTime() {
        return Stopwatch.format(nanos);
    }

    public String formattedSpeed() {
        return Bytes.speed(bytes, nanos);
    }

    public double speedMbPerSecond() {
        return Bytes.speedMbPerSecond(bytes, nanos);
    }

    /** First and last four hex characters, e.g. {@code a91f...c2e0} - fits on a slide. */
    public String shortSha() {
        if (sha256 == null || sha256.length() < 12) {
            return String.valueOf(sha256);
        }
        return sha256.substring(0, 4) + "..." + sha256.substring(sha256.length() - 4);
    }
}
