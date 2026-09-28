package demo.coffee.service;

import demo.common.Log;
import demo.coffee.model.Drink;
import demo.coffee.model.Order;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The individual brewing steps, each one an independent asynchronous task.
 *
 * <p>Splitting a drink into grind / brew / steam is what gives Scenario 3 a real
 * pipeline to compose: each step depends on the one before it, so they chain with
 * {@code thenCompose} rather than running in parallel. Contrast with the pastry,
 * which depends on nothing and therefore joins with {@code thenCombine}.
 *
 * <p>Every method here takes an explicit {@link Executor}. None of this work ever
 * touches the common ForkJoinPool: sleeping to simulate brewing is blocking work,
 * and blocking the common pool would stall everything else in the JVM.
 */
public final class Barista {

    private final EspressoMachine machine;
    private final Executor pool;
    private final Tempo tempo;

    public Barista(EspressoMachine machine, Executor pool, Tempo tempo) {
        this.machine = machine;
        this.pool = pool;
        this.tempo = tempo;
    }

    /**
     * Makes a whole drink in one blocking call. Used by Scenario 1 (the baseline)
     * and, wrapped in {@code supplyAsync}, by Scenario 2.
     */
    public String prepare(Order order) {
        Drink drink = order.drink();
        Log.info("%s: %s started", order.customer(), drink.name());

        if (drink.needsEspresso()) {
            EspressoMachine.sleep(tempo.scale(drink.grindMillis()));
            machine.pullShot(order.customer(), drink, drink.brewMillis());
            EspressoMachine.sleep(tempo.scale(drink.steamMillis()));
        } else {
            EspressoMachine.sleep(tempo.scale(drink.prepMillis()));
        }

        Log.info("%s: %s ready (%.1fs)", order.customer(), drink.name(), drink.prepMillis() / 1000.0);
        return drink.displayName();
    }

    // ---- Scenario 3: the same drink, as three composable stages ----

    /** Stage 1. Produces ground coffee, which stage 2 consumes. */
    public CompletableFuture<String> grind(Order order) {
        // DEMO: supplyAsync on the barista pool - the first stage of the pipeline.
        return CompletableFuture.supplyAsync(() -> {
            Log.info("%s: grinding beans", order.customer());
            EspressoMachine.sleep(tempo.scale(order.drink().grindMillis()));
            return "ground " + order.drink().displayName().toLowerCase() + " beans";
        }, pool);
    }

    /** Stage 2. Takes the grounds from stage 1 and pulls a shot. */
    public CompletableFuture<String> brew(Order order, String grounds) {
        return CompletableFuture.supplyAsync(() -> {
            Log.info("%s: brewing from %s", order.customer(), grounds);
            return machine.pullShot(order.customer(), order.drink(), order.drink().brewMillis());
        }, pool);
    }

    /** Stage 3. Takes the shot from stage 2 and finishes the drink. */
    public CompletableFuture<String> steamMilk(Order order, String shot) {
        return CompletableFuture.supplyAsync(() -> {
            if (!order.drink().needsSteamedMilk()) {
                return order.drink().displayName();
            }
            Log.info("%s: steaming milk for the %s", order.customer(), shot);
            EspressoMachine.sleep(tempo.scale(order.drink().steamMillis()));
            return order.drink().displayName();
        }, pool);
    }
}
