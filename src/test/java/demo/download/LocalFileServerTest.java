package demo.download;

import demo.download.server.LocalFileServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the built-in server really implements the HTTP range contract the
 * chunked downloader depends on. If these fail, the offline demo is not honest.
 */
class LocalFileServerTest {

    private static final int SIZE = 64 * 1024;

    @TempDir
    Path tempDir;

    private LocalFileServer server;
    private HttpClient client;
    private byte[] content;

    @BeforeEach
    void startServer() throws IOException {
        content = new byte[SIZE];
        new Random(42).nextBytes(content);

        Path file = tempDir.resolve("sample.bin");
        Files.write(file, content);

        // No throttling: these tests are about correctness, not speed.
        server = LocalFileServer.start(file, 0, 0);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("HEAD reports the size and advertises byte-range support")
    void headReportsSizeAndRanges() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(server.url()))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertEquals(SIZE, response.headers().firstValueAsLong("content-length").orElse(-1)),
                () -> assertTrue(response.headers().firstValue("accept-ranges").orElse("")
                        .toLowerCase().contains("bytes"), "Accept-Ranges: bytes must be advertised"));
    }

    @Test
    @DisplayName("GET without a Range header returns the whole file")
    void getWholeFile() throws Exception {
        HttpResponse<byte[]> response = get(null);

        assertEquals(200, response.statusCode());
        assertArrayEquals(content, response.body());
    }

    @Test
    @DisplayName("GET with a Range returns 206 and exactly those bytes")
    void getRangeReturnsPartialContent() throws Exception {
        int start = 1000;
        int end = 2999;

        HttpResponse<byte[]> response = get("bytes=" + start + "-" + end);

        assertAll(
                () -> assertEquals(206, response.statusCode()),
                () -> assertEquals(end - start + 1, response.body().length),
                () -> assertArrayEquals(Arrays.copyOfRange(content, start, end + 1), response.body()),
                () -> assertEquals("bytes " + start + "-" + end + "/" + SIZE,
                        response.headers().firstValue("content-range").orElse("")),
                () -> assertEquals(end - start + 1L,
                        response.headers().firstValueAsLong("content-length").orElse(-1)));
    }

    @Test
    @DisplayName("an open-ended Range runs to the end of the file")
    void openEndedRange() throws Exception {
        int start = SIZE - 100;
        HttpResponse<byte[]> response = get("bytes=" + start + "-");

        assertEquals(206, response.statusCode());
        assertArrayEquals(Arrays.copyOfRange(content, start, SIZE), response.body());
    }

    @Test
    @DisplayName("a suffix Range returns the last n bytes")
    void suffixRange() throws Exception {
        HttpResponse<byte[]> response = get("bytes=-256");

        assertEquals(206, response.statusCode());
        assertArrayEquals(Arrays.copyOfRange(content, SIZE - 256, SIZE), response.body());
    }

    @Test
    @DisplayName("an unsatisfiable Range returns 416 with the real total size")
    void invalidRangeReturns416() throws Exception {
        HttpResponse<byte[]> beyondEnd = get("bytes=" + (SIZE + 10) + "-" + (SIZE + 20));
        HttpResponse<byte[]> backwards = get("bytes=500-100");
        HttpResponse<byte[]> nonsense = get("chunks=1-2");

        assertAll(
                () -> assertEquals(416, beyondEnd.statusCode()),
                () -> assertEquals("bytes */" + SIZE,
                        beyondEnd.headers().firstValue("content-range").orElse("")),
                () -> assertEquals(416, backwards.statusCode()),
                () -> assertEquals(416, nonsense.statusCode()));
    }

    @Test
    @DisplayName("concurrent range requests reassemble into the original file")
    void concurrentRangesReassemble() throws Exception {
        int chunks = 8;
        byte[] assembled = new byte[SIZE];

        ChunkPlan.split(SIZE, chunks).parallelStream().forEach(chunk -> {
            try {
                HttpResponse<byte[]> response = get(chunk.rangeHeader());
                assertEquals(206, response.statusCode());
                System.arraycopy(response.body(), 0, assembled, (int) chunk.start(), response.body().length);
            } catch (Exception e) {
                throw new AssertionError("chunk " + chunk + " failed", e);
            }
        });

        assertEquals(Integrity.sha256(content), Integrity.sha256(assembled));
    }

    private HttpResponse<byte[]> get(String range) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.url())).GET();
        if (range != null) {
            builder.header("Range", range);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
