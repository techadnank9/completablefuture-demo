package demo.download;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Thread-safe per-chunk byte counters shared by every download worker.
 *
 * <p>Demonstrates the other half of "concurrency done properly": several threads
 * report progress into one structure with no locks and no lost updates, using
 * {@link AtomicLongArray} (one independent slot per chunk) rather than a single
 * shared counter that every thread would contend on.
 */
public final class ProgressTracker {

    private final List<ChunkPlan> chunks;
    private final AtomicLongArray downloaded;
    private final Map<Integer, String> workerByChunk = new ConcurrentHashMap<>();
    private final long totalBytes;

    public ProgressTracker(List<ChunkPlan> chunks) {
        this.chunks = List.copyOf(chunks);
        this.downloaded = new AtomicLongArray(chunks.size());
        this.totalBytes = chunks.stream().mapToLong(ChunkPlan::length).sum();
    }

    /** Single-chunk tracker for the sequential round, so both rounds render alike. */
    public static ProgressTracker single(long totalBytes) {
        return new ProgressTracker(List.of(new ChunkPlan(0, 0, totalBytes - 1)));
    }

    /** Called from a worker thread each time bytes are written. Lock-free. */
    public void add(int chunkIndex, long bytes) {
        downloaded.addAndGet(chunkIndex, bytes);
    }

    /** Records which thread owns a chunk, so the progress bars can show it. */
    public void claim(int chunkIndex, String threadName) {
        workerByChunk.put(chunkIndex, threadName);
    }

    /** Resets a chunk's counter - used when a failed chunk is retried from scratch. */
    public void reset(int chunkIndex) {
        downloaded.set(chunkIndex, 0);
    }

    public long bytesFor(int chunkIndex) {
        return downloaded.get(chunkIndex);
    }

    public String workerFor(int chunkIndex) {
        return workerByChunk.getOrDefault(chunkIndex, "pending");
    }

    /** Distinct threads that actually moved bytes - the "Threads used" column. */
    public int distinctWorkers() {
        return (int) workerByChunk.values().stream().distinct().count();
    }

    public long totalDownloaded() {
        long sum = 0;
        for (int i = 0; i < downloaded.length(); i++) {
            sum += downloaded.get(i);
        }
        return sum;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public List<ChunkPlan> chunks() {
        return chunks;
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Fraction complete in {@code [0, 1]} for one chunk. */
    public double fractionFor(int chunkIndex) {
        long length = chunks.get(chunkIndex).length();
        return length == 0 ? 1.0 : Math.min(1.0, downloaded.get(chunkIndex) / (double) length);
    }

    /** Fraction complete in {@code [0, 1]} overall. */
    public double fraction() {
        return totalBytes == 0 ? 1.0 : Math.min(1.0, totalDownloaded() / (double) totalBytes);
    }

    public boolean isComplete() {
        return totalDownloaded() >= totalBytes;
    }
}
