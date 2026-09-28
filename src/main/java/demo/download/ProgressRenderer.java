package demo.download;

import demo.common.Bytes;
import demo.common.Console;
import demo.common.Executors2;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redraws one progress bar per chunk on a timer, so the audience can watch eight
 * chunks advance at once instead of staring at a frozen console.
 *
 * <p>The renderer runs on its own single-threaded scheduler and only ever
 * <em>reads</em> the {@link ProgressTracker}. The download workers never wait for
 * the screen - display is deliberately decoupled from the work being measured.
 *
 * <p>Under {@code --plain} it prints one summary line every second instead of
 * redrawing in place, because some IntelliJ console versions mishandle the
 * carriage returns and cursor movement that in-place redraw needs.
 */
public final class ProgressRenderer implements AutoCloseable {

    private static final int BAR_WIDTH = 28;
    private static final long TICK_MILLIS = 150;

    private final ProgressTracker tracker;
    private final String title;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int linesDrawn = 0;

    public ProgressRenderer(ProgressTracker tracker, String title) {
        this.tracker = tracker;
        this.title = title;
        this.scheduler = Executors2.scheduler("render");
    }

    /** Begins redrawing until {@link #close()}. */
    public ProgressRenderer start() {
        if (!running.compareAndSet(false, true)) {
            return this;
        }
        long period = Console.ansiEnabled() ? TICK_MILLIS : 1000L;
        scheduler.scheduleAtFixedRate(this::safeDraw, 0, period, TimeUnit.MILLISECONDS);
        return this;
    }

    private void safeDraw() {
        try {
            draw();
        } catch (RuntimeException e) {
            // Never let a cosmetic failure interrupt a live demo.
        }
    }

    private synchronized void draw() {
        if (!running.get()) {
            return;
        }
        if (Console.ansiEnabled()) {
            drawInPlace();
        } else {
            drawPlainLine();
        }
    }

    private void drawInPlace() {
        StringBuilder sb = new StringBuilder();
        sb.append(Console.cursorUp(linesDrawn));

        int lines = 0;
        for (int i = 0; i < tracker.chunkCount(); i++) {
            sb.append(Console.clearLine()).append(chunkLine(i)).append('\n');
            lines++;
        }
        sb.append(Console.clearLine()).append(totalLine()).append('\n');
        lines++;

        linesDrawn = lines;
        Console.print(sb.toString());
    }

    private void drawPlainLine() {
        Console.println(String.format("  %s %5.1f%%  %s / %s",
                title,
                tracker.fraction() * 100,
                Bytes.human(tracker.totalDownloaded()),
                Bytes.human(tracker.totalBytes())));
    }

    private String chunkLine(int index) {
        double fraction = tracker.fractionFor(index);
        String label = String.format("chunk %-2d", index);
        String worker = String.format("%-10s", tracker.workerFor(index));
        return String.format("  %s [%s] %5.1f%%  %-10s %s",
                label, bar(fraction), fraction * 100,
                Bytes.human(tracker.bytesFor(index)),
                Console.dim(worker));
    }

    private String totalLine() {
        double fraction = tracker.fraction();
        return String.format("  %-8s [%s] %5.1f%%  %s / %s",
                Console.bold("TOTAL"), bar(fraction), fraction * 100,
                Bytes.human(tracker.totalDownloaded()),
                Bytes.human(tracker.totalBytes()));
    }

    private String bar(double fraction) {
        int filled = (int) Math.round(fraction * BAR_WIDTH);
        String block = "█".repeat(Math.max(0, Math.min(BAR_WIDTH, filled)));
        String rest = "░".repeat(Math.max(0, BAR_WIDTH - filled));
        return Console.green(block) + Console.dim(rest);
    }

    /** Stops the timer and leaves one final, complete frame on screen. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Executors2.shutdown(scheduler);
        running.set(true);
        draw();                 // final frame, so the bars end at 100%
        running.set(false);
    }
}
