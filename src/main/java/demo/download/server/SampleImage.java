package demo.download.server;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Generates the photographs in the demo's file library.
 *
 * <p>Drawn with Java2D and written by ImageIO, both in the JDK, so the project keeps
 * its zero-dependency promise and the library needs no bundled stock imagery.
 *
 * <p>Deliberately <em>not</em> diagrams. The page frames these files in neutral grey
 * chrome so the picture is the only saturated thing on screen; an image sharing the
 * interface's palette would camouflage into it and stop reading as a file at all.
 *
 * <p>Each is also its own proof. PNG is compressed, so a byte range written to the
 * wrong offset does not produce a tidy seam, it corrupts the stream and the file
 * stops decoding. Seeing any picture means every byte arrived where it belonged.
 */
public final class SampleImage {

    /** Colour ramps, each with its own wave geometry so the photos look distinct. */
    public enum Palette {
        SUNSET(new Color[]{ new Color(0x2B1533), new Color(0xB5296B),
                            new Color(0xF2762E), new Color(0xFFC24B) }, 5.0, 2.5, 7.5),
        CURRENT(new Color[]{ new Color(0x101B3A), new Color(0x2E6BC9),
                             new Color(0x36C9B0), new Color(0xEAF7B5) }, 3.2, 4.4, 5.0);

        private final Color[] stops;
        private final double freqA;
        private final double freqB;
        private final double freqC;

        Palette(Color[] stops, double freqA, double freqB, double freqC) {
            this.stops = stops;
            this.freqA = freqA;
            this.freqB = freqB;
            this.freqC = freqC;
        }
    }

    /** Fixed seed, so every machine and every run produces the same SHA-256. */
    private static final long DITHER_SEED = 20260927L;

    private SampleImage() {
    }

    /**
     * Draws a photograph and writes it as a PNG, sized to land near {@code targetMb}.
     *
     * <p>A clean gradient would compress far below the requested size, so a faint
     * deterministic dither is applied. It is invisible at viewing size but stops the
     * encoder collapsing large smooth regions, which keeps the file big enough for
     * the download to be worth watching.
     */
    public static Path write(Path target, int targetMb, Palette palette) throws IOException {
        // Measured: a dithered PNG of this field costs about 2.3 bytes per pixel.
        long targetBytes = (long) targetMb * 1024 * 1024;
        int pixels = (int) (targetBytes / 2.3);
        int width = (int) Math.round(Math.sqrt(pixels * 16.0 / 9.0));
        int height = (int) Math.round(width * 9.0 / 16.0);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        paint(image, width, height, palette);
        dither(image, width, height);

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, 170));
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(12, height / 38)));
        g.drawString("concurrency-demo.onrender.com", height / 25, height - height / 25);
        g.dispose();

        Files.createDirectories(target.toAbsolutePath().getParent());
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    /** Interfering waves: smooth, continuous, and with no flat regions to collapse. */
    private static void paint(BufferedImage image, int w, int h, Palette palette) {
        for (int y = 0; y < h; y++) {
            double v = y / (double) h;
            for (int x = 0; x < w; x++) {
                double u = x / (double) w;
                double a = Math.sin((u * palette.freqA + v * 2.0) * Math.PI);
                double b = Math.sin((u * palette.freqB - v * 4.5) * Math.PI + 1.7);
                double c = Math.sin(Math.hypot(u - 0.52, v - 0.44) * palette.freqC * Math.PI);
                // A finer layer, so the picture has texture up close rather than
                // reading as soft blobs, and so it compresses less predictably.
                double d = Math.sin((u * 21.0 + v * 13.0) * Math.PI) * 0.22;
                double t = Math.max(0, Math.min(1, ((a + b + c) / 3.0 + d + 1) / 2));
                image.setRGB(x, y, ramp(palette.stops, t).getRGB());
            }
        }
    }

    /** Smooth interpolation across a list of colour stops. */
    private static Color ramp(Color[] stops, double t) {
        double scaled = Math.max(0, Math.min(1, t)) * (stops.length - 1);
        int i = (int) Math.min(scaled, stops.length - 2);
        double f = scaled - i;
        Color a = stops[i], b = stops[i + 1];
        return new Color(
                (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * f),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * f));
    }

    private static void dither(BufferedImage image, int w, int h) {
        Random random = new Random(DITHER_SEED);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = clamp(((rgb >> 16) & 0xFF) + random.nextInt(7) - 3);
                int g = clamp(((rgb >> 8) & 0xFF) + random.nextInt(7) - 3);
                int b = clamp((rgb & 0xFF) + random.nextInt(7) - 3);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * A neutral tile for a file we cannot render a preview of, such as a video
     * supplied by the user. Better than a broken image in the picker.
     */
    public static byte[] placeholder(String label) throws IOException {
        int w = 480, h = 270;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x22272D));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x7D8791));
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        String text = label == null || label.isBlank() ? "FILE" : label.toUpperCase();
        int tw = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (w - tw) / 2, h / 2 + 12);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /**
     * A small JPEG preview of an image file, served unthrottled.
     *
     * <p>Necessary because the library files are deliberately rate limited: fetching
     * one just to show a thumbnail would leave the page looking broken for twenty
     * seconds.
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

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", out);
        return out.toByteArray();
    }
}
