package demo.coffee.model;

import demo.common.Stopwatch;

import java.time.Duration;

/**
 * The outcome of one order. Deliberately a value, not an exception: Scenario 4 is
 * about failures that are <em>handled</em>, so a broken machine still produces a
 * {@code Served} record - just with status {@link Status#FALLBACK}.
 *
 * @param order   the original order
 * @param status  how it ended
 * @param detail  what the customer was actually handed
 * @param nanos   how long it took
 */
public record Served(Order order, Status status, String detail, long nanos) {

    /** How an order finished. */
    public enum Status {
        /** Made as ordered. */
        SERVED,
        /** Something failed and {@code exceptionally} supplied a substitute. */
        FALLBACK,
        /** The step never completed and {@code orTimeout} fired. */
        TIMED_OUT
    }

    public static Served served(Order order, long nanos) {
        return new Served(order, Status.SERVED, order.drink().displayName(), nanos);
    }

    public static Served fallback(Order order, String detail, long nanos) {
        return new Served(order, Status.FALLBACK, detail, nanos);
    }

    public static Served timedOut(Order order, String detail, long nanos) {
        return new Served(order, Status.TIMED_OUT, detail, nanos);
    }

    public boolean isServed() {
        return status == Status.SERVED;
    }

    public Duration elapsed() {
        return Duration.ofNanos(nanos);
    }

    public String formattedTime() {
        return Stopwatch.format(nanos);
    }

    public String describe() {
        return String.format("%-10s %-14s %-8s %s",
                order.customer(), order.drink().displayName(), formattedTime(), detail);
    }
}
