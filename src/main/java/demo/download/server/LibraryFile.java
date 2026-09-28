package demo.download.server;

import java.nio.file.Path;

/**
 * One downloadable item in the demo's library.
 *
 * @param id          stable slug used in URLs
 * @param title       human name shown on the page
 * @param fileName    the name the browser saves it as
 * @param kind        {@code "Photo"} or {@code "Video"}, for the picker
 * @param contentType media type served with the bytes
 * @param path        where the bytes live on disk
 * @param size        length in bytes
 * @param thumbnail   small unthrottled preview, JPEG
 */
public record LibraryFile(String id, String title, String fileName, String kind,
                          String contentType, Path path, long size, byte[] thumbnail) {

    /** JSON for the picker. Hand-rolled: the project carries no JSON dependency. */
    public String toJson() {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"title\":\"" + escape(title) + "\","
                + "\"fileName\":\"" + escape(fileName) + "\","
                + "\"kind\":\"" + kind + "\","
                + "\"contentType\":\"" + contentType + "\","
                + "\"size\":" + size
                + "}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
