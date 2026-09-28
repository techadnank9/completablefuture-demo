package demo.download;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a file of {@code totalBytes} into contiguous byte ranges, one per
 * parallel download task.
 *
 * <p>This is the piece that makes concurrent downloading <em>correct</em>: the
 * ranges must cover every byte of {@code [0, totalBytes)} exactly once and never
 * overlap. Because they never overlap, the download tasks can write into a single
 * pre-allocated output file at their own offsets with no locking at all - which is
 * why the SHA-256 of the concurrent download matches the sequential one.
 *
 * @param index zero-based chunk number, used for progress display
 * @param start first byte offset, inclusive
 * @param end   last byte offset, inclusive (HTTP Range is inclusive at both ends)
 */
public record ChunkPlan(int index, long start, long end) {

    public ChunkPlan {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid range [" + start + ", " + end + "]");
        }
    }

    /** Number of bytes in this chunk. */
    public long length() {
        return end - start + 1;
    }

    /** The value for the HTTP {@code Range} request header, e.g. {@code bytes=0-1023}. */
    public String rangeHeader() {
        return "bytes=" + start + "-" + end;
    }

    /**
     * Divides {@code [0, totalBytes)} into at most {@code requestedChunks} ranges.
     *
     * <p>Rules:
     * <ul>
     *   <li>Chunk count is clamped to {@code [1, totalBytes]} - never more chunks
     *       than bytes, so no chunk is ever empty.</li>
     *   <li>Every chunk gets {@code totalBytes / n} bytes; the final chunk absorbs
     *       the remainder, so the ranges always reach the last byte exactly.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if {@code totalBytes <= 0} or {@code requestedChunks < 1}
     */
    public static List<ChunkPlan> split(long totalBytes, int requestedChunks) {
        if (totalBytes <= 0) {
            throw new IllegalArgumentException("totalBytes must be positive, was " + totalBytes);
        }
        if (requestedChunks < 1) {
            throw new IllegalArgumentException("requestedChunks must be >= 1, was " + requestedChunks);
        }

        // More chunks than bytes would produce empty ranges, which servers reject with 416.
        int n = (int) Math.min(requestedChunks, totalBytes);

        long base = totalBytes / n;
        List<ChunkPlan> chunks = new ArrayList<>(n);
        long cursor = 0;
        for (int i = 0; i < n; i++) {
            boolean last = (i == n - 1);
            long length = last ? (totalBytes - cursor) : base;  // last chunk absorbs the remainder
            chunks.add(new ChunkPlan(i, cursor, cursor + length - 1));
            cursor += length;
        }
        return List.copyOf(chunks);
    }

    @Override
    public String toString() {
        return "chunk#" + index + "[" + start + ".." + end + "] (" + length() + " bytes)";
    }
}
