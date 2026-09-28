package demo.coffee.service;

import demo.common.Log;
import demo.common.Stopwatch;
import demo.coffee.model.Drink;
import demo.coffee.model.Order;
import demo.coffee.model.Served;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 * All of the {@link CompletableFuture} orchestration for the coffee shop example,
 * in one readable place so it can be put on a projector.
 *
 * <p>One method per scenario, each demonstrating one idea:
 * <ul>
 *   <li>{@link #serveSequentially} - the blocking baseline, no futures at all.</li>
 *   <li>{@link #serveConcurrently} - {@code supplyAsync}, {@code allOf},
 *       {@code anyOf}, {@code thenAccept}, {@code runAsync}.</li>
 *   <li>{@link #serveWithPipeline} - {@code thenCompose} for dependent steps and
 *       {@code thenCombine} for independent ones.</li>
 *   <li>{@link #serveWithFailures} - {@code exceptionally}, {@code orTimeout} and
 *       {@code handle}, plus {@link #serveWithCompleteOnTimeout} as the
 *       "default value instead of an error" variant.</li>
 * </ul>
 *
 * <p>Every async call takes an explicit executor. The common ForkJoinPool is never
 * used, because brewing blocks a thread and blocking a shared pool starves the
 * rest of the JVM.
 */
public final class CoffeeShop {

    private final Barista barista;
    private final PastryStation pastryStation;
    private final EspressoMachine machine;
    private final Executor baristaPool;
    private final Duration patience;
    private final Tempo tempo;

    /**
     * @param baristaPool   the pool sized by {@code --baristas}
     * @param patience      grace period ON TOP OF a drink's expected preparation
     *                      time before the timeout fallback fires. It is relative,
     *                      not absolute: a flat deadline shorter than the slowest
     *                      drink would time out healthy orders too.
     */
    public CoffeeShop(EspressoMachine machine, Executor baristaPool, Executor pastryPool, Duration patience) {
        this(machine, baristaPool, pastryPool, patience, machine.tempo());
    }

    /** Full constructor. Tests pass a faster {@link Tempo}; the demo uses real time. */
    public CoffeeShop(EspressoMachine machine, Executor baristaPool, Executor pastryPool,
                      Duration patience, Tempo tempo) {
        this.machine = machine;
        this.baristaPool = baristaPool;
        this.tempo = tempo;
        this.barista = new Barista(machine, baristaPool, tempo);
        this.pastryStation = new PastryStation(pastryPool, tempo);
        this.patience = patience;
    }

    public EspressoMachine machine() {
        return machine;
    }

    // ------------------------------------------------------------------
    // Scenario 1: one barista, blocking
    // ------------------------------------------------------------------

    /**
     * Serves every order one after another on the calling thread.
     *
     * <p>No concurrency at all. Total time is the sum of every prep time, and the
     * thread spends nearly all of it doing nothing but waiting. This is the number
     * Scenario 2 has to beat.
     */
    public List<Served> serveSequentially(List<Order> orders) {
        List<Served> results = new ArrayList<>(orders.size());
        for (Order order : orders) {
            Stopwatch watch = Stopwatch.start();
            barista.prepare(order);
            results.add(Served.served(order, watch.stop().elapsedNanos()));
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Scenario 2: several baristas
    // ------------------------------------------------------------------

    /** What {@link #serveConcurrently} produces: every order, plus whoever finished first. */
    public record ShiftResult(List<Served> served, String firstReady, long nanos) {
        public double seconds() {
            return nanos / 1_000_000_000.0;
        }
    }

    /**
     * Serves every order at the same time on the barista pool.
     *
     * <p>Shows the four calls that cover most real use of the API:
     * {@code supplyAsync} to start work, {@code anyOf} + {@code thenAccept} to react
     * to the first result, {@code allOf} to wait for all of them, and
     * {@code runAsync} for a side effect that returns nothing.
     */
    public ShiftResult serveConcurrently(List<Order> orders) {
        Stopwatch watch = Stopwatch.start();

        // DEMO: supplyAsync - fan out. Every order starts now, on its own barista.
        List<CompletableFuture<Served>> futures = orders.stream()
                .map(order -> CompletableFuture.supplyAsync(() -> {
                    Stopwatch each = Stopwatch.start();
                    barista.prepare(order);
                    return Served.served(order, each.stop().elapsedNanos());
                }, baristaPool))
                .toList();

        // DEMO: anyOf + thenAccept - react to the FIRST order to finish, without
        // waiting for the rest. thenAccept consumes a value and returns nothing.
        StringBuilder firstReady = new StringBuilder();
        CompletableFuture<Void> announcement = CompletableFuture
                .anyOf(futures.toArray(CompletableFuture[]::new))
                .thenAccept(first -> {
                    Served served = (Served) first;
                    String message = served.order().customer() + "'s " + served.order().drink().displayName();
                    synchronized (firstReady) {
                        if (firstReady.length() == 0) {
                            firstReady.append(message);
                        }
                    }
                    Log.info("First drink ready: %s!", message);
                });

        // DEMO: allOf - completes when every order is done. Total time is the
        // SLOWEST drink, not the sum of all of them.
        CompletableFuture<List<Served>> all = CompletableFuture
                .allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());

        List<Served> results = all.join();
        announcement.join();
        watch.stop();

        // DEMO: runAsync - a side effect with no return value.
        CompletableFuture<Void> cleanUp = CompletableFuture.runAsync(
                () -> Log.detail("counter wiped, machine flushed"), baristaPool);
        cleanUp.join();

        return new ShiftResult(results, firstReady.toString(), watch.elapsedNanos());
    }

    // ------------------------------------------------------------------
    // Scenario 3: recipes and combos
    // ------------------------------------------------------------------

    /**
     * Makes one drink as a chain of dependent stages, and joins an independent
     * pastry alongside it.
     *
     * <p>The distinction this scenario exists to teach:
     * <ul>
     *   <li>{@code thenCompose} flattens a future returned by the next step. Use it
     *       when step 2 <b>needs step 1's result</b> - you cannot brew before you
     *       grind. ({@code thenApply} here would leave you holding a
     *       {@code CompletableFuture<CompletableFuture<String>>}.)</li>
     *   <li>{@code thenCombine} joins two <b>unrelated</b> futures that have been
     *       running side by side. The pastry never needed the coffee.</li>
     * </ul>
     */
    public CompletableFuture<Served> serveWithPipeline(Order order) {
        Stopwatch watch = Stopwatch.start();

        // DEMO: thenCompose - each stage returns a future and depends on the last one.
        CompletableFuture<String> drinkFuture = barista.grind(order)
                .thenCompose(grounds -> barista.brew(order, grounds))
                .thenCompose(shot -> barista.steamMilk(order, shot));

        if (!order.hasPastry()) {
            return drinkFuture.thenApply(drink -> {
                Log.info("%s: %s served", order.customer(), drink);
                return Served.served(order, watch.stop().elapsedNanos());
            });
        }

        // The pastry starts NOW, in parallel with the grinding.
        CompletableFuture<String> pastryFuture = pastryStation.warm(order);

        // DEMO: thenCombine - two independent futures joined into one result.
        return drinkFuture.thenCombine(pastryFuture, (drink, pastry) -> {
            String detail = drink + " + " + pastry + " served together";
            Log.info("%s: %s", order.customer(), detail);
            return new Served(order, Served.Status.SERVED, detail, watch.stop().elapsedNanos());
        });
    }

    // ------------------------------------------------------------------
    // Scenario 4: the machine breaks
    // ------------------------------------------------------------------

    /**
     * Serves every order while the espresso machine misbehaves for one customer.
     *
     * <p>Two failure modes, two different tools:
     * <ul>
     *   <li>A task that <b>throws</b> is recovered by {@code exceptionally}, which
     *       supplies a replacement value and lets the pipeline carry on.</li>
     *   <li>A task that <b>never finishes</b> throws nothing at all, so there is
     *       nothing for {@code exceptionally} to catch until {@code orTimeout} turns
     *       the silence into a {@link TimeoutException}.</li>
     * </ul>
     *
     * <p>Either way the other orders are untouched: one broken future does not
     * poison its siblings.
     */
    public List<Served> serveWithFailures(List<Order> orders) {
        List<CompletableFuture<Served>> futures = orders.stream()
                .map(this::serveResiliently)
                .toList();

        return CompletableFuture
                .allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList())
                // DEMO: handle - runs on success AND on failure, so the summary step is
                // written once instead of twice. Here it can only ever see success,
                // because every order was already made safe individually.
                .handle((results, error) -> {
                    if (error != null) {
                        Log.error("unexpected failure while closing out the shift", error);
                        return List.<Served>of();
                    }
                    return results;
                })
                .join();
    }

    /** One order, made resilient. This is the method worth showing on the projector. */
    private CompletableFuture<Served> serveResiliently(Order order) {
        Stopwatch watch = Stopwatch.start();

        return CompletableFuture
                .supplyAsync(() -> {
                    barista.prepare(order);
                    return Served.served(order, watch.stop().elapsedNanos());
                }, baristaPool)
                // DEMO: orTimeout - turns "never finishes" into a TimeoutException we can act on.
                .orTimeout(deadlineMillisFor(order), java.util.concurrent.TimeUnit.MILLISECONDS)
                // DEMO: exceptionally - supply a fallback value so the pipeline still produces a drink.
                .exceptionally(error -> {
                    Throwable cause = Log.rootCause(error);
                    long nanos = watch.stop().elapsedNanos();
                    if (cause instanceof TimeoutException) {
                        String detail = String.format("machine stopped responding - free %s after %.1fs",
                                Drink.TEA.displayName(), deadlineMillisFor(order) / 1000.0);
                        Log.warn("%s: %s", order.customer(), detail);
                        return Served.timedOut(order, detail, nanos);
                    }
                    String detail = "machine broke - free " + Drink.TEA.displayName() + " instead";
                    Log.warn("%s: %s", order.customer(), detail);
                    return Served.fallback(order, detail, nanos);
                });
    }

    /**
     * How long this order is allowed to take: its own expected preparation time
     * plus the shop's patience. Relative rather than absolute, so a slow drink is
     * not punished for being slow - only a genuinely stuck one trips the timeout.
     */
    long deadlineMillisFor(Order order) {
        return tempo.scale(order.drink().prepMillis()) + patience.toMillis();
    }

    /**
     * The gentler alternative to {@code orTimeout}: instead of failing when time runs
     * out, {@code completeOnTimeout} simply completes the future with a default value.
     * No exception is ever created, so no recovery stage is needed.
     */
    public CompletableFuture<Served> serveWithCompleteOnTimeout(Order order) {
        Stopwatch watch = Stopwatch.start();
        Served substitute = Served.fallback(order,
                "took too long - " + Drink.TEA.displayName() + " served instead", 0);

        return CompletableFuture
                .supplyAsync(() -> {
                    barista.prepare(order);
                    return Served.served(order, watch.stop().elapsedNanos());
                }, baristaPool)
                // DEMO: completeOnTimeout - a default value, not an error.
                .completeOnTimeout(substitute, deadlineMillisFor(order), java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(result -> result.nanos() == 0
                        ? new Served(result.order(), result.status(), result.detail(), watch.stop().elapsedNanos())
                        : result);
    }
}
