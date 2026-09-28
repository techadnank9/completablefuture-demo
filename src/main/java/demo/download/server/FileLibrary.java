package demo.download.server;

import demo.common.Executors2;
import demo.common.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * Builds the set of files the demo offers for download: two generated photographs
 * and one video.
 *
 * <p>Serving a small library rather than a single fixed file is what makes the page
 * read as a tool. It also lets the same lesson be shown at several sizes, which is
 * the honest way to demonstrate that the speedup comes from the connection limit
 * and not from anything particular about one file.
 *
 * <p>The photographs are drawn at startup by {@link SampleImage}; the video ships
 * with the jar, because encoding H.264 is not something the JDK can do and adding
 * an encoder would cost this project its zero-dependency property.
 *
 * <p>The two photographs are generated concurrently, on a pool created here and shut
 * down before returning - the same pattern the demo itself teaches, applied to its
 * own startup.
 */
public final class FileLibrary {

    private static final int THUMB_WIDTH = 760;
    /**
     * An excerpt from Big Buck Bunny, (c) Blender Foundation, licensed CC-BY 3.0 and
     * published expressly for reuse. It is the standard test clip for exactly this
     * kind of demonstration, which is why it ships here rather than anything scraped
     * from a streaming site: this repository and the server are both public, so every
     * file in the library is redistributed to anyone who opens the page.
     */
    private static final String VIDEO_RESOURCE = "/files/drift.mp4";
    private static final String POSTER_RESOURCE = "/files/poster.jpg";

    private FileLibrary() {
    }

    /**
     * Creates every library file inside {@code workDir}.
     *
     * @param photoMb nominal size of the larger photograph; the smaller one is
     *                roughly half, so the picker shows a real spread of sizes
     */
    public static List<LibraryFile> build(Path workDir, int photoMb) throws IOException {
        Files.createDirectories(workDir);
        ExecutorService pool = Executors2.named("gen", 2);
        try {
            // DEMO: two independent renders, started together and joined with allOf.
            CompletableFuture<LibraryFile> current = CompletableFuture.supplyAsync(
                    () -> photo(workDir, "deep-current", "Deep Current",
                            SampleImage.Palette.CURRENT, photoMb), pool);
            CompletableFuture<LibraryFile> sunset = CompletableFuture.supplyAsync(
                    () -> photo(workDir, "sunset-drift", "Sunset Drift",
                            SampleImage.Palette.SUNSET, Math.max(2, photoMb / 2)), pool);

            CompletableFuture.allOf(current, sunset).join();

            List<LibraryFile> files = new ArrayList<>();
            files.add(current.join());
            files.add(sunset.join());
            video(workDir).ifPresent(files::add);

            files.forEach(f -> Log.detail("library: %s (%s, %,d bytes)", f.fileName(), f.kind(), f.size()));
            return List.copyOf(files);

        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            Executors2.shutdown(pool);
        }
    }

    /**
     * Builds a library from a folder on the presenter's own machine.
     *
     * <p>Local only, by design. Files served this way are never committed and never
     * deployed: they exist on the laptop running the command and nowhere else, so
     * whatever you point it at stays yours.
     */
    public static List<LibraryFile> fromDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("not a directory: " + dir);
        }
        List<LibraryFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path file : entries.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                String type = LocalFileServer.guessContentType(name);
                if (type.equals("application/octet-stream")) {
                    continue;                       // not something a browser can show
                }
                boolean image = type.startsWith("image/");
                byte[] thumb = null;
                if (image) {
                    try {
                        thumb = SampleImage.thumbnail(file, THUMB_WIDTH);
                    } catch (IOException | RuntimeException e) {
                        thumb = null;
                    }
                }
                if (thumb == null) {
                    thumb = SampleImage.placeholder(extensionOf(name));
                }
                files.add(new LibraryFile(slug(name), stripExtension(name), name,
                        image ? "Photo" : "Video", type, file, Files.size(file), thumb));
            }
        }
        if (files.isEmpty()) {
            throw new IOException("no images or videos found in " + dir);
        }
        files.forEach(f -> Log.detail("library: %s (%s, %,d bytes)", f.fileName(), f.kind(), f.size()));
        return List.copyOf(files);
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "file";
    }

    private static LibraryFile photo(Path dir, String id, String title,
                                     SampleImage.Palette palette, int sizeMb) {
        try {
            Path file = SampleImage.write(dir.resolve(id + ".png"), sizeMb, palette);
            return new LibraryFile(id, title, id + ".png", "Photo", "image/png",
                    file, Files.size(file), SampleImage.thumbnail(file, THUMB_WIDTH));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copies the bundled video out of the jar so it can be served with range
     * requests from a real file. Returns empty if the resource is missing, so a
     * stripped build still offers the photographs rather than failing to start.
     */
    private static java.util.Optional<LibraryFile> video(Path dir) {
        try (InputStream in = FileLibrary.class.getResourceAsStream(VIDEO_RESOURCE)) {
            if (in == null) {
                Log.warn("bundled video not found on the classpath - serving photos only");
                return java.util.Optional.empty();
            }
            Path file = dir.resolve("drift.mp4");
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);

            byte[] poster;
            try (InputStream p = FileLibrary.class.getResourceAsStream(POSTER_RESOURCE)) {
                poster = p == null ? null : p.readAllBytes();
            }
            return java.util.Optional.of(new LibraryFile("bigbuckbunny",
                    "Big Buck Bunny", "big-buck-bunny.mp4",
                    "Video", "video/mp4", file, Files.size(file), poster));
        } catch (IOException e) {
            Log.warn("could not unpack the bundled video (%s) - serving photos only", e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
