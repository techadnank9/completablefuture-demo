package demo.common;

import java.time.Duration;

/**
 * Minimal elapsed-time measurement around {@link System#nanoTime()}.
 *
 * <p>Used everywhere in this project to produce the numbers the presentation is
 * built around: sequential vs concurrent download time, coffee shop round times.
 */
public final class Stopwatch {

    private final long startNanos;
    private volatile long stopNanos = -1L;

    private Stopwatch(long startNanos) {
        this.startNanos = startNanos;
    }

    /** Starts and returns a running stopwatch. */
    public static Stopwatch start() {
        return new Stopwatch(System.nanoTime());
    }

    /** Stops the stopwatch (idempotent) and returns it for chaining. */
    public Stopwatch stop() {
        if (stopNanos < 0) {
            stopNanos = System.nanoTime();
        }
        return this;
    }

    /** Nanoseconds elapsed, frozen at {@link #stop()} if the watch was stopped. */
    public long elapsedNanos() {
        long end = stopNanos >= 0 ? stopNanos : System.nanoTime();
        return end - startNanos;
    }

    public double elapsedSeconds() {
        return elapsedNanos() / 1_000_000_000.0;
    }

    public long elapsedMillis() {
        return elapsedNanos() / 1_000_000L;
    }

    public Duration elapsed() {
        return Duration.ofNanos(elapsedNanos());
    }

    /** Human readable, e.g. {@code "3.21 s"} or {@code "412 ms"}. */
    public String format() {
        return format(elapsedNanos());
    }

    public static String format(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds < 1.0) {
            return String.format("%d ms", nanos / 1_000_000L);
        }
        return String.format("%.2f s", seconds);
    }

    @Override
    public String toString() {
        return format();
    }
}
