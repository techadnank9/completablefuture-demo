package demo.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hashing - the correctness proof for the concurrent download.
 *
 * <p>Talking point: parallelism is only a win if the answer is still right.
 * Eight threads wrote into one file at eight different offsets; if the hash of
 * that file equals the hash of the plain single-stream download, every byte
 * landed exactly where it belonged.
 */
public final class Integrity {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Integrity() {
    }

    /** Streaming SHA-256 of a file - constant memory regardless of file size. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digesting = new DigestInputStream(in, digest)) {
            while (digesting.read(buffer) != -1) {
                // reading is enough; DigestInputStream updates the digest as it goes
            }
        }
        return toHex(digest.digest());
    }

    /** SHA-256 of an in-memory array, used by the tests. */
    public static String sha256(byte[] data) {
        return toHex(newDigest().digest(data));
    }

    /** Null-safe, case-insensitive comparison of two hex digests. */
    public static boolean matches(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }
}
