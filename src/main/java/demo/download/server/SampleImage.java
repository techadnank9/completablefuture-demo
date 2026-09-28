package demo.download.server;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Generates the file the demo downloads: a real, openable PNG rather than a blob
 * of random bytes.
 *
 * <p>This matters for the hosted page. Two matching SHA-256 strings ask the
 * audience to trust the arithmetic; two matching <em>pictures</em>, one of which
 * appeared several seconds before the other, prove the same thing by being looked
 * at. The visitor can also save the file and open it, which is the difference
 * between a demo and an animation.
 *
 * <p>Drawn with Java2D and written by ImageIO - both in the JDK, so the project
 * keeps its zero-dependency promise.
 */
public final class SampleImage {

    /** Brand colours, matching the slides and the web page. */
    private static final Color INK = new Color(0x0E1B2A);
    private static final Color INK_2 = new Color(0x1B3550);
    private static final Color TEAL = new Color(0x00A896);
    private static final Color SLATE = new Color(0x6B7C8C);
    private static final Color AMBER = new Color(0xF2A65A);
    private static final Color PAPER = new Color(0xE8EEF3);

    private SampleImage() {
    }

    /**
     * Draws the image and writes it as a PNG, sized to land near {@code targetMb}.
     *
     * <p>PNG compresses aggressively, so a clean illustration would come out far
     * smaller than asked. A faint per-pixel dither is applied - invisible at
     * viewing size, but enough to stop the encoder from collapsing large flat
     * areas - and the resolution is derived from the target so the finished file
     * lands in the right neighbourhood.
     *
     * @return the written file
     */
    public static Path write(Path target, int targetMb) throws IOException {
        // Measured: a dithered PNG of this illustration costs about 1.75 bytes per
        // pixel, so this picks a resolution that lands near the requested size.
        long targetBytes = (long) targetMb * 1024 * 1024;
        int pixels = (int) (targetBytes / 1.75);
        int width = (int) Math.round(Math.sqrt(pixels * 16.0 / 9.0));
        int height = (int) Math.round(width * 9.0 / 16.0);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        draw(g, width, height);
        g.dispose();

        dither(image, width, height);

        Files.createDirectories(target.toAbsolutePath().getParent());
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    /** The illustration itself: the demo's own progress bars, drawn large. */
    private static void draw(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0, 0, INK, w, h, INK_2));
        g.fillRect(0, 0, w, h);

        double unit = h / 100.0;          // everything scales with the canvas
        int left = (int) (unit * 10);
        int barWidth = (int) (w - unit * 20);

        // Title
        g.setColor(PAPER);
        g.setFont(new Font("SansSerif", Font.BOLD, (int) (unit * 9)));
        g.drawString("Concurrency with", left, (int) (unit * 20));
        g.setColor(TEAL);
        g.setFont(new Font("SansSerif", Font.BOLD, (int) (unit * 13)));
        g.drawString("CompletableFuture", left, (int) (unit * 34));

        // One long bar: the sequential round, one thread, all the way across.
        g.setColor(SLATE);
        g.setFont(new Font("SansSerif", Font.PLAIN, (int) (unit * 4)));
        g.drawString("1 connection", left, (int) (unit * 48));
        g.setColor(new Color(0x0D1F31));
        g.fillRoundRect(left, (int) (unit * 51), barWidth, (int) (unit * 5),
                (int) unit, (int) unit);
        g.setColor(SLATE);
        // Part-filled: the single connection is still going.
        g.fillRoundRect(left, (int) (unit * 51), (int) (barWidth * 0.34), (int) (unit * 5),
                (int) unit, (int) unit);

        // Eight short bars: the concurrent round, all in flight at once.
        g.setColor(TEAL);
        g.drawString("8 connections", left, (int) (unit * 66));
        int gap = (int) (unit * 1.2);
        int slice = (barWidth - gap * 7) / 8;
        for (int i = 0; i < 8; i++) {
            int x = left + i * (slice + gap);
            g.setColor(new Color(0x0D1F31));
            g.fillRoundRect(x, (int) (unit * 69), slice, (int) (unit * 5),
                    (int) unit, (int) unit);
            g.setColor(TEAL);
            // All eight already finished, in the time the one above is still running.
            g.fillRoundRect(x, (int) (unit * 69), slice, (int) (unit * 5),
                    (int) unit, (int) unit);
        }

        g.setColor(AMBER);
        g.setFont(new Font("SansSerif", Font.PLAIN, (int) (unit * 3.6)));
        g.drawString("Same bytes. Same hash. A fraction of the time.",
                left, (int) (unit * 86));

        g.setColor(SLATE);
        g.setFont(new Font("SansSerif", Font.PLAIN, (int) (unit * 2.8)));
        g.drawString("github.com/techadnank9/completablefuture-demo", left, (int) (unit * 93));
    }

    /**
     * Adds a faint deterministic dither.
     *
     * <p>Invisible at any sane viewing size, but it stops PNG's filters from
     * collapsing the large flat regions - which is what keeps the file big enough
     * for the download to be worth watching. The fixed seed keeps the SHA-256
     * identical on every machine and every run.
     */
    private static void dither(BufferedImage image, int w, int h) {
        Random random = new Random(20260927L);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = clamp(((rgb >> 16) & 0xFF) + random.nextInt(7) - 3);
                int gg = clamp(((rgb >> 8) & 0xFF) + random.nextInt(7) - 3);
                int b = clamp((rgb & 0xFF) + random.nextInt(7) - 3);
                image.setRGB(x, y, (r << 16) | (gg << 8) | b);
            }
        }
    }

    /**
     * A small, undithered copy of the served image, for the page to show
     * immediately.
     *
     * <p>Necessary because the real file is deliberately throttled: fetching it
     * just to preview it would leave the page looking broken for twenty seconds.
     * This is served unthrottled and is a few tens of kilobytes.
     */
    public static byte[] thumbnail(Path source, int width) throws IOException {
        BufferedImage full = ImageIO.read(source.toFile());
        if (full == null) {
            return null;
        }
        int height = (int) Math.round(width * (full.getHeight() / (double) full.getWidth()));
        BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(full, 0, 0, width, height, null);
        g.dispose();

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(small, "jpg", out);
        return out.toByteArray();
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
