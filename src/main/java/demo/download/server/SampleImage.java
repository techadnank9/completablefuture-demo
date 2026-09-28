package demo.download.server;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
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
    private static final Color TEAL = new Color(0x00A896);
    private static final Color AMBER = new Color(0xF2A65A);

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
        // Measured: a dithered PNG of this field costs about 2.3 bytes per pixel,
        // so this picks a resolution that lands near the requested size.
        long targetBytes = (long) targetMb * 1024 * 1024;
        int pixels = (int) (targetBytes / 2.3);
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

    /**
     * A continuous wave field.
     *
     * <p>Deliberately <em>not</em> a diagram. The page already draws progress bars;
     * an illustration of progress bars sitting inside them reads as interface rather
     * than as a downloaded file, which is the one thing this needs to look like.
     *
     * <p>It is also its own proof. PNG is compressed, so a range written to the wrong
     * offset does not produce a tidy visible seam - it corrupts the stream and the
     * file stops decoding altogether. Seeing any picture at all therefore means every
     * byte arrived where it belonged; the hash underneath only confirms it.
     */
    private static void draw(Graphics2D g, int w, int h) {
        // Three stops, deep blue through teal to amber, walked smoothly by the field.
        final Color[] stops = { new Color(0x10243A), TEAL, AMBER };

        for (int y = 0; y < h; y++) {
            double v = y / (double) h;
            for (int x = 0; x < w; x++) {
                double u = x / (double) w;

                // Interfering waves: cheap, smooth, and with no flat regions.
                double a = Math.sin((u * 5.0 + v * 2.0) * Math.PI);
                double b = Math.sin((u * 2.5 - v * 4.5) * Math.PI + 1.7);
                double c = Math.sin(Math.hypot(u - 0.52, v - 0.44) * 7.5 * Math.PI);
                // A finer layer, so the picture has texture up close rather than
                // reading as soft blobs - and so it compresses less predictably.
                double d = Math.sin((u * 21.0 + v * 13.0) * Math.PI) * 0.22;
                double t = (a + b + c) / 3.0 + d;          // roughly -1 .. 1
                t = Math.max(0, Math.min(1, (t + 1) / 2));  // 0 .. 1

                g.setColor(ramp(stops, t));
                g.fillRect(x, y, 1, 1);
            }
        }

        // A quiet caption, small enough that it never reads as interface.
        double unit = h / 100.0;
        g.setColor(new Color(255, 255, 255, 190));
        g.setFont(new Font("SansSerif", Font.PLAIN, (int) (unit * 2.6)));
        g.drawString("concurrency-demo.onrender.com", (int) (unit * 4), (int) (h - unit * 4));
    }

    /** Smooth interpolation across a list of colour stops. */
    private static Color ramp(Color[] stops, double t) {
        t = Math.max(0, Math.min(1, t));
        double scaled = t * (stops.length - 1);
        int i = (int) Math.min(scaled, stops.length - 2);
        double f = scaled - i;
        Color a = stops[i], b = stops[i + 1];
        return new Color(
                (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * f),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * f));
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
