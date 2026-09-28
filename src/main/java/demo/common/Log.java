package demo.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Timestamped console logging in the format {@code [+1.234s] [thread-name] message}.
 *
 * <p>The thread name is the point of this class. Every pool in the project is
 * created through {@link Executors2} with a readable prefix, so the log itself
 * becomes the proof that work really did run on several threads at once:
 *
 * <pre>
 * [+0.002s] [barista-1] Sahana: LATTE started
 * [+0.003s] [barista-2] Mustafa: MOCHA started
 * </pre>
 */
public final class Log {

    private static final AtomicLong ORIGIN = new AtomicLong(System.nanoTime());
    private static volatile boolean debug = false;

    private Log() {
    }

    /** Resets the {@code +elapsed} origin. Called at the start of each demo. */
    public static void resetClock() {
        ORIGIN.set(System.nanoTime());
    }

    public static void setDebug(boolean value) {
        debug = value;
    }

    public static boolean isDebug() {
        return debug;
    }

    /** Seconds elapsed since the last {@link #resetClock()}. */
    public static double elapsedSeconds() {
        return (System.nanoTime() - ORIGIN.get()) / 1_000_000_000.0;
    }

    public static void info(String message) {
        Console.println(prefix() + message);
    }

    public static void info(String format, Object... args) {
        info(String.format(format, args));
    }

    /** Dimmed line, used for secondary detail that should not steal attention. */
    public static void detail(String format, Object... args) {
        Console.println(Console.dim(prefix() + String.format(format, args)));
    }

    public static void warn(String format, Object... args) {
        Console.println(Console.yellow(prefix() + String.format(format, args)));
    }

    /**
     * Friendly error line. Stack traces are suppressed unless {@code --debug} is
     * set, because a wall of red text mid-presentation helps nobody.
     */
    public static void error(String message, Throwable cause) {
        Console.println(Console.red(prefix() + message
                + (cause != null ? " (" + rootCause(cause).getClass().getSimpleName()
                + ": " + rootCause(cause).getMessage() + ")" : "")));
        if (debug && cause != null) {
            cause.printStackTrace(System.out);
        }
    }

    /** Only printed when {@code --debug} is set. */
    public static void debug(String format, Object... args) {
        if (debug) {
            Console.println(Console.dim(prefix() + "[debug] " + String.format(format, args)));
        }
    }

    /** Unwraps CompletionException / ExecutionException chains to the real cause. */
    public static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String prefix() {
        return String.format("[+%06.3fs] [%-12s] ", elapsedSeconds(), Thread.currentThread().getName());
    }
}
