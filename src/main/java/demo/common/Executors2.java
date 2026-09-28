package demo.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for named, deliberately sized thread pools - the "resource management"
 * half of this presentation.
 *
 * <h2>Why not just use the default?</h2>
 * {@code CompletableFuture.supplyAsync(task)} with no executor runs on the shared
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}. That pool is sized to
 * {@code availableProcessors() - 1} and is shared with parallel streams and every
 * other library in the JVM. It is built for short, CPU-bound, non-blocking work.
 * Blocking I/O (a socket read, a file write, a sleeping barista) parks those
 * threads, starves everything else in the process, and caps our download
 * concurrency at the core count no matter how many chunks we ask for.
 *
 * <p>So every {@code supplyAsync} / {@code runAsync} in this project passes an
 * explicit executor created here, and every pool is shut down when its demo ends.
 */
public final class Executors2 {

    private Executors2() {
    }

    /**
     * A fixed pool of {@code size} threads named {@code prefix-1 .. prefix-size}.
     *
     * @param prefix readable thread-name prefix, e.g. {@code "io"} or {@code "barista"}
     * @param size   number of threads; size it to the work, not to the machine
     */
    public static ExecutorService named(String prefix, int size) {
        return Executors.newFixedThreadPool(size, factory(prefix, false));
    }

    /** A single scheduled daemon thread, used for the progress renderer ticks. */
    public static ScheduledExecutorService scheduler(String prefix) {
        return Executors.newSingleThreadScheduledExecutor(factory(prefix, true));
    }

    /** Thread factory producing {@code prefix-N} names so logs stay readable. */
    public static ThreadFactory factory(String prefix, boolean daemon) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(daemon);
            return thread;
        };
    }

    /**
     * Orderly shutdown: stop accepting work, wait briefly, then force.
     * Every demo calls this in a {@code finally} block so no pool outlives its demo.
     */
    public static void shutdown(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
