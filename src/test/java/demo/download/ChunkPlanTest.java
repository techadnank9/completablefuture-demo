package demo.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The correctness foundation for the whole concurrent download: if the ranges are
 * wrong, the SHA-256 will not match and the demo falls apart on stage.
 */
class ChunkPlanTest {

    @ParameterizedTest(name = "{0} chunks cover every byte exactly once")
    @ValueSource(ints = {1, 2, 3, 4, 7, 8, 16, 32})
    @DisplayName("ranges are contiguous, non-overlapping, and cover the whole file")
    void rangesCoverEveryByteExactlyOnce(int chunkCount) {
        long size = 1_000_003;      // deliberately prime, so nothing divides evenly
        List<ChunkPlan> chunks = ChunkPlan.split(size, chunkCount);

        assertEquals(0, chunks.get(0).start(), "first chunk must start at byte 0");
        assertEquals(size - 1, chunks.get(chunks.size() - 1).end(), "last chunk must end at the last byte");
        assertEquals(size, chunks.stream().mapToLong(ChunkPlan::length).sum(), "lengths must sum to the file size");

        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).end() + 1, chunks.get(i).start(),
                    "chunk " + i + " must start immediately after chunk " + (i - 1));
        }
        chunks.forEach(chunk -> assertTrue(chunk.length() > 0, chunk + " must not be empty"));
    }

    @Test
    @DisplayName("size not divisible by n: the last chunk absorbs the remainder")
    void lastChunkAbsorbsRemainder() {
        List<ChunkPlan> chunks = ChunkPlan.split(100, 3);

        assertAll(
                () -> assertEquals(3, chunks.size()),
                () -> assertEquals(33, chunks.get(0).length()),
                () -> assertEquals(33, chunks.get(1).length()),
                () -> assertEquals(34, chunks.get(2).length(), "remainder lands in the final chunk"),
                () -> assertEquals(99, chunks.get(2).end()));
    }

    @Test
    @DisplayName("n = 1 produces a single range covering the file")
    void singleChunk() {
        List<ChunkPlan> chunks = ChunkPlan.split(500, 1);

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).start());
        assertEquals(499, chunks.get(0).end());
        assertEquals(500, chunks.get(0).length());
    }

    @Test
    @DisplayName("more chunks than bytes is clamped, so no chunk is ever empty")
    void moreChunksThanBytes() {
        List<ChunkPlan> chunks = ChunkPlan.split(5, 32);

        assertEquals(5, chunks.size(), "cannot have more chunks than bytes");
        chunks.forEach(chunk -> assertEquals(1, chunk.length()));
        assertEquals(4, chunks.get(4).end());
    }

    @Test
    @DisplayName("the Range header is inclusive at both ends")
    void rangeHeaderFormat() {
        assertEquals("bytes=0-1023", new ChunkPlan(0, 0, 1023).rangeHeader());
        assertEquals("bytes=1024-2047", new ChunkPlan(1, 1024, 2047).rangeHeader());
    }

    @Test
    @DisplayName("invalid input is rejected rather than silently producing bad ranges")
    void invalidInput() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> ChunkPlan.split(0, 4)),
                () -> assertThrows(IllegalArgumentException.class, () -> ChunkPlan.split(-1, 4)),
                () -> assertThrows(IllegalArgumentException.class, () -> ChunkPlan.split(100, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ChunkPlan(0, 10, 5)));
    }
}
