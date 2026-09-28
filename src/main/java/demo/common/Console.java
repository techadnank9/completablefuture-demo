package demo.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Console presentation helpers: banners, colours, and "press Enter to continue"
 * pauses that let the presenter talk between rounds.
 *
 * <p>Two global switches, both set from the command line:
 * <ul>
 *   <li>{@code --plain} turns off ANSI colour and in-place redraw. Some IntelliJ
 *       console versions render {@code \r} poorly, so this is the safe fallback
 *       on an unfamiliar presentation laptop.</li>
 *   <li>{@code --no-pause} skips every {@link #pause(String)}, so the whole demo
 *       can be rehearsed or driven by a test in one shot.</li>
 * </ul>
 */
public final class Console {

    private static volatile boolean ansi = true;
    private static volatile boolean pauses = true;

    private static final BufferedReader IN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private Console() {
    }

    public static void configure(boolean plain, boolean noPause) {
        ansi = !plain;
        pauses = !noPause;
    }

    public static boolean ansiEnabled() {
        return ansi;
    }

    public static synchronized void println(String line) {
        System.out.println(line);
    }

    public static synchronized void print(String text) {
        System.out.print(text);
        System.out.flush();
    }

    public static void blank() {
        println("");
    }

    /** Heavy section banner, e.g. {@code ---- ROUND 2 ----}. */
    public static void banner(String title) {
        String bar = "━".repeat(Math.max(8, 62 - title.length()) / 2);
        blank();
        println(bold(bar + " " + title + " " + bar));
    }

    /** Lighter sub-heading. */
    public static void heading(String title) {
        blank();
        println(bold(title));
        println(dim("─".repeat(Math.min(70, Math.max(10, title.length())))));
    }

    /**
     * Waits for Enter so the presenter controls the pace. No-op under
     * {@code --no-pause}.
     */
    public static void pause(String message) {
        if (!pauses) {
            return;
        }
        print(dim("\n" + message + " "));
        readLineOrNull();
        blank();
    }

    /** Reads a line, or {@code null} on EOF (piped input, CI). */
    public static String readLineOrNull() {
        try {
            return IN.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Prompts and returns a trimmed answer, or {@code ""} on EOF.
     *
     * <p>Under {@code --no-pause} the demo is fully non-interactive: the prompt is
     * shown for the record but nothing is read, and the caller's default applies.
     * That is what lets the whole presentation be rehearsed in one command.
     */
    public static String ask(String prompt) {
        if (!pauses) {
            println(dim(prompt + "(auto)"));
            return "";
        }
        print(prompt);
        String line = readLineOrNull();
        return line == null ? "" : line.trim();
    }

    // ---- ANSI helpers. All degrade to plain text under --plain. ----

    public static String bold(String s)   { return wrap("\u001B[1m", s); }
    public static String dim(String s)    { return wrap("\u001B[2m", s); }
    public static String red(String s)    { return wrap("\u001B[31m", s); }
    public static String green(String s)  { return wrap("\u001B[32m", s); }
    public static String yellow(String s) { return wrap("\u001B[33m", s); }
    public static String blue(String s)   { return wrap("\u001B[34m", s); }
    public static String cyan(String s)   { return wrap("\u001B[36m", s); }

    private static String wrap(String code, String s) {
        return ansi ? code + s + "\u001B[0m" : s;
    }

    /** Moves the cursor up {@code n} lines for in-place redraw. Empty under --plain. */
    public static String cursorUp(int n) {
        return ansi && n > 0 ? "\u001B[" + n + "A" : "";
    }

    /** Clears from the cursor to the end of the line. Empty under --plain. */
    public static String clearLine() {
        return ansi ? "\u001B[2K\r" : "";
    }
}
