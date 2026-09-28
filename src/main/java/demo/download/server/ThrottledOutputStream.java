package demo.download.server;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Bandwidth-limited {@link OutputStream} used by {@link LocalFileServer}.
 *
 * <h2>Why throttle at all?</h2>
 * On localhost a 64 MB file copies in well under a second, so a sequential
 * download and an eight-way concurrent download would both look instant and the
 * demo would prove nothing. Real servers limit <em>per connection</em>, not per
 * client - which is precisely why opening eight connections is faster. This class
 * reproduces that behaviour offline, so the classroom demo needs no internet and
 * still shows an honest speedup.
 *
 * <p>Two limiters apply to each write:
 * <ul>
 *   <li>a <b>per-connection</b> {@link Limiter}, private to this stream;</li>
 *   <li>an optional <b>global</b> {@link Limiter} shared by every connection,
 *       which models a finite total pipe. That shared ceiling is what makes the
 *       {@code --compare-pools} curve flatten out once the link is saturated.</li>
 * </ul>
 */
public final class ThrottledOutputStream extends FilterOutputStream {

    /** Written in small slices so pacing stays smooth rather than bursty. */
    private static final int SLICE = 16 * 1024;

    private final Limiter perConnection;
    private final Limiter global;

    public ThrottledOutputStream(OutputStream out, Limiter perConnection, Limiter global) {
        super(out);
        this.perConnection = perConnection;
        this.global = global;
    }

    @Override
    public void write(int b) throws IOException {
        acquire(1);
        out.write(b);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        int remaining = length;
        int cursor = offset;
        while (remaining > 0) {
            int slice = Math.min(SLICE, remaining);
            acquire(slice);
            out.write(buffer, cursor, slice);
            cursor += slice;
            remaining -= slice;
        }
    }

    /** Blocks until both limiters allow {@code bytes} to go out. */
    private void acquire(int bytes) throws IOException {
        try {
            if (global != null) {
                global.acquire(bytes);
            }
            if (perConnection != null) {
                perConnection.acquire(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("throttle interrupted", e);
        }
    }

    /**
     * A simple token bucket. Tokens are bytes; they refill continuously at
     * {@code bytesPerSecond} and the bucket holds up to one second's worth, so a
     * short idle period is allowed to burst but the long-run average is exact.
     *
     * <p>Thread safe: every caller synchronises on the bucket, and a caller that
     * must wait sleeps outside the lock so it never blocks the refill for others.
     */
    public static final class Limiter {

        private final double bytesPerSecond;
        private final double capacity;
        private double tokens;
        private long lastRefillNanos;

        public Limiter(double bytesPerSecond) {
            if (bytesPerSecond <= 0) {
                throw new IllegalArgumentException("bytesPerSecond must be positive");
            }
            this.bytesPerSecond = bytesPerSecond;
            // Only a small burst is allowed. A full second of credit would let any
            // file smaller than one second of bandwidth through untouched - which
            // would silently disable the throttle for small files and make the
            // whole comparison meaningless.
            this.capacity = Math.max(32 * 1024, bytesPerSecond * 0.05);
            this.tokens = this.capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        /** Convenience factory taking a limit in megabytes per second. */
        public static Limiter mbPerSecond(double mbps) {
            return new Limiter(mbps * 1024 * 1024);
        }

        public double bytesPerSecond() {
            return bytesPerSecond;
        }

        /** Blocks until {@code bytes} tokens have been consumed. */
        public void acquire(int bytes) throws InterruptedException {
            long sleepNanos;
            synchronized (this) {
                refill();
                tokens -= bytes;                     // may go negative: that is the debt to sleep off
                sleepNanos = tokens >= 0 ? 0L : (long) ((-tokens / bytesPerSecond) * 1_000_000_000L);
            }
            if (sleepNanos > 0) {
                // Sleeping outside the lock keeps other connections refilling normally.
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            }
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * bytesPerSecond);
        }
    }
}
