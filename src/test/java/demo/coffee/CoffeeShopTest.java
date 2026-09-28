package demo.coffee;

import demo.coffee.model.Drink;
import demo.coffee.model.Order;
import demo.coffee.model.Pastry;
import demo.coffee.model.Served;
import demo.coffee.service.CoffeeShop;
import demo.coffee.service.EspressoMachine;
import demo.coffee.service.Tempo;
import demo.common.Executors2;
import demo.common.Stopwatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the four coffee scenarios do what the slides claim: concurrency is
 * faster, a broken machine only affects its own order, and a hung machine is
 * rescued by a timeout rather than hanging the program.
 */
class CoffeeShopTest {

    /**
     * The whole suite runs the real code at a quarter speed, so a latte takes
     * 625 ms instead of 2.5 s. Every timing relationship is preserved; only the
     * wall-clock cost of the suite changes.
     */
    private static final Tempo TEMPO = new Tempo(0.25);

    /** Grace period on top of each drink's (scaled) preparation time. */
    private static final Duration PATIENCE = Duration.ofMillis(600);

    private static final List<Order> ORDERS = List.of(
            Order.of("Sahana", Drink.LATTE, Pastry.CROISSANT),
            Order.of("Mustafa", Drink.MOCHA),
            Order.of("Priya", Drink.ESPRESSO),
            Order.of("Amara", Drink.TEA));

    private EspressoMachine machine;
    private ExecutorService baristaPool;
    private ExecutorService pastryPool;
    private CoffeeShop shop;

    @BeforeEach
    void openShop() {
        machine = new EspressoMachine(TEMPO);
        baristaPool = Executors2.named("barista", 4);
        pastryPool = Executors2.named("pastry", 2);
        shop = new CoffeeShop(machine, baristaPool, pastryPool, PATIENCE, TEMPO);
    }

    @AfterEach
    void closeShop() {
        Executors2.shutdown(baristaPool);
        Executors2.shutdown(pastryPool);
    }

    @Test
    @DisplayName("concurrent service is faster than one barista, and serves the same orders")
    void concurrentIsFasterThanSequential() {
        Stopwatch sequentialWatch = Stopwatch.start();
        List<Served> sequential = shop.serveSequentially(ORDERS);
        long sequentialNanos = sequentialWatch.stop().elapsedNanos();

        CoffeeShop.ShiftResult concurrent = shop.serveConcurrently(ORDERS);

        assertAll(
                () -> assertEquals(ORDERS.size(), sequential.size()),
                () -> assertEquals(ORDERS.size(), concurrent.served().size()),
                () -> assertTrue(concurrent.served().stream().allMatch(Served::isServed)),
                () -> assertTrue(concurrent.nanos() < sequentialNanos,
                        "concurrent must beat sequential"));

        // Four baristas and four orders: the shift should cost about the SLOWEST
        // drink, not the sum. Asserted as a fraction of the sequential time so the
        // check holds at any tempo, with headroom against a loaded CI machine.
        double ratio = concurrent.nanos() / (double) sequentialNanos;
        assertTrue(ratio < 0.6,
                () -> String.format("expected the shift to cost roughly the slowest drink, "
                        + "but concurrent took %.0f%% of sequential", ratio * 100));
    }

    @Test
    @DisplayName("anyOf announces the first drink to finish - the fastest one")
    void anyOfReportsTheFirstDrink() {
        CoffeeShop.ShiftResult result = shop.serveConcurrently(ORDERS);

        // Priya's espresso is 1.0s, the shortest in the list.
        assertTrue(result.firstReady().startsWith("Priya"),
                () -> "expected the espresso to finish first, got: " + result.firstReady());
    }

    @Test
    @DisplayName("thenCombine serves a drink and its pastry together, in parallel")
    void pipelineCombinesDrinkAndPastry() {
        Order order = Order.of("Sahana", Drink.LATTE, Pastry.CROISSANT);

        Stopwatch watch = Stopwatch.start();
        Served served = shop.serveWithPipeline(order).join();
        watch.stop();

        assertAll(
                () -> assertTrue(served.isServed()),
                () -> assertTrue(served.detail().contains("Latte")),
                () -> assertTrue(served.detail().contains("Croissant")),
                // The latte and the croissant overlap, so the pair costs the slower of
                // the two, comfortably under the sum of both.
                () -> assertTrue(watch.elapsedMillis()
                                < TEMPO.scale(Drink.LATTE.prepMillis() + Pastry.WARM_MILLIS) * 0.85,
                        () -> "pastry should warm while the coffee brews, took "
                                + watch.elapsedSeconds() + "s"));
    }

    @Test
    @DisplayName("a broken machine produces a FALLBACK for that order only")
    void brokenMachineFallsBackForOneCustomerOnly() {
        machine.breakFor("Mustafa");

        List<Served> results = shop.serveWithFailures(ORDERS);

        Served mustafa = find(results, "Mustafa");
        assertAll(
                () -> assertEquals(ORDERS.size(), results.size(), "every order must produce a result"),
                () -> assertEquals(Served.Status.FALLBACK, mustafa.status()),
                () -> assertTrue(mustafa.detail().toLowerCase().contains("tea"),
                        () -> "expected a free tea, got: " + mustafa.detail()),
                () -> assertTrue(results.stream()
                                .filter(s -> !s.order().customer().equals("Mustafa"))
                                .allMatch(Served::isServed),
                        "one broken order must not affect the others"));
    }

    @Test
    @DisplayName("a hung machine triggers the timeout fallback, and does so promptly")
    void hungMachineTimesOut() {
        machine.hangFor("Mustafa");

        Stopwatch watch = Stopwatch.start();
        List<Served> results = shop.serveWithFailures(ORDERS);
        watch.stop();

        Served mustafa = find(results, "Mustafa");
        assertAll(
                () -> assertEquals(Served.Status.TIMED_OUT, mustafa.status()),
                () -> assertTrue(results.stream()
                                .filter(s -> !s.order().customer().equals("Mustafa"))
                                .allMatch(Served::isServed),
                        "the other customers are still served normally"),
                // orTimeout fires about a second in; without it this would block for
                // the machine's full 60s hang (15s at this tempo).
                () -> assertTrue(watch.elapsedSeconds() < 4.0,
                        () -> "the timeout should have rescued the shift, took "
                                + watch.elapsedSeconds() + "s"));
    }

    @Test
    @DisplayName("completeOnTimeout substitutes a default value instead of raising an error")
    void completeOnTimeoutSubstitutesDefault() {
        machine.hangFor("Mustafa");
        Order order = Order.of("Mustafa", Drink.MOCHA);

        Served served = shop.serveWithCompleteOnTimeout(order).join();

        assertAll(
                () -> assertEquals(Served.Status.FALLBACK, served.status()),
                () -> assertTrue(served.detail().toLowerCase().contains("tea")),
                () -> assertSame(order, served.order()));
    }

    @Test
    @DisplayName("a healthy shift serves everyone")
    void healthyShiftServesEveryone() {
        List<Served> results = shop.serveWithFailures(ORDERS);

        assertEquals(ORDERS.size(), results.size());
        assertTrue(results.stream().allMatch(Served::isServed));
    }

    private static Served find(List<Served> results, String customer) {
        return results.stream()
                .filter(s -> s.order().customer().equals(customer))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result for " + customer));
    }
}
