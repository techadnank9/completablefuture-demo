package demo.coffee.service;

import demo.common.Log;
import demo.coffee.model.Drink;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * The single point of failure in the coffee shop - and therefore the most useful
 * object in Scenario 4.
 *
 * <p>Two injectable faults, matching the two things that actually go wrong with
 * asynchronous work:
 * <ul>
 *   <li>{@link #breakFor(String)} - the task <b>throws</b>. Recovered with
 *       {@code exceptionally}.</li>
 *   <li>{@link #hangFor(String)} - the task <b>never completes</b>. Nothing throws,
 *       nothing returns; only {@code orTimeout} or {@code completeOnTimeout} can
 *       rescue it. This is the failure mode that plain try/catch cannot see.</li>
 * </ul>
 */
public final class EspressoMachine {

    /** Longer than any timeout in the demo - a hang is "forever" from the caller's point of view. */
    private static final long HANG_MILLIS = 60_000;

    private final Set<String> brokenFor = new HashSet<>();
    private final Set<String> hangingFor = new HashSet<>();
    private final Tempo tempo;

    public EspressoMachine() {
        this(Tempo.REALTIME);
    }

    public EspressoMachine(Tempo tempo) {
        this.tempo = tempo;
    }

    public Tempo tempo() {
        return tempo;
    }

    /** Makes the machine throw for this customer's drink. */
    public synchronized void breakFor(String customer) {
        brokenFor.add(normalize(customer));
    }

    /** Makes the machine stop responding for this customer's drink. */
    public synchronized void hangFor(String customer) {
        hangingFor.add(normalize(customer));
    }

    /** Clears all injected faults. */
    public synchronized void repair() {
        brokenFor.clear();
        hangingFor.clear();
    }

    public synchronized boolean isBrokenFor(String customer) {
        return brokenFor.contains(normalize(customer));
    }

    public synchronized boolean isHangingFor(String customer) {
        return hangingFor.contains(normalize(customer));
    }

    /**
     * Pulls a shot. Blocks for the brew time, or fails according to the injected faults.
     *
     * @return a short description of the shot, used by the next pipeline stage
     */
    public String pullShot(String customer, Drink drink, long brewMillis) {
        if (isHangingFor(customer)) {
            Log.warn("%s: espresso machine is not responding...", customer);
            sleep(tempo.scale(HANG_MILLIS));    // never completes within the demo's timeout
            return "shot";
        }
        if (isBrokenFor(customer)) {
            Log.warn("%s: espresso machine failure!", customer);
            throw new CompletionException(
                    new IllegalStateException("espresso machine failed mid-shot"));
        }
        sleep(tempo.scale(brewMillis));
        return "shot of " + drink.displayName().toLowerCase();
    }

    /** Sleep that converts interruption into a completion failure, so futures see it. */
    static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private static String normalize(String customer) {
        return customer == null ? "" : customer.trim().toLowerCase();
    }
}
