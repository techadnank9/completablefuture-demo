package demo.coffee.service;

import java.time.Duration;

/**
 * A simulation-speed multiplier for every simulated wait in the coffee shop.
 *
 * <p>The demo runs at {@link #REALTIME}, where a latte really does take 2.5
 * seconds - the audience needs to see the clock move. The tests run the identical
 * code at a fraction of that, so the whole suite finishes quickly while every
 * timing <em>relationship</em> the tests assert on (concurrent beats sequential,
 * the pastry overlaps the brew, the timeout fires before the hang ends) is
 * preserved exactly.
 *
 * <p>Scaling the clock rather than stubbing the waits means the tests exercise the
 * real {@code CompletableFuture} composition, not a mock of it.
 *
 * @param factor multiplier applied to every simulated duration; {@code 1.0} is real time
 */
public record Tempo(double factor) {

    /** Presentation speed: drinks take exactly as long as the menu says. */
    public static final Tempo REALTIME = new Tempo(1.0);

    public Tempo {
        if (factor <= 0) {
            throw new IllegalArgumentException("factor must be positive, was " + factor);
        }
    }

    /** Scales a duration in milliseconds, never below zero. */
    public long scale(long millis) {
        return Math.max(0, Math.round(millis * factor));
    }

    public Duration scale(Duration duration) {
        return Duration.ofMillis(scale(duration.toMillis()));
    }
}
