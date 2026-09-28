package demo.coffee.service;

import demo.common.Log;
import demo.coffee.model.Order;
import demo.coffee.model.Pastry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Warms pastries, on its own thread pool, completely independently of the drinks.
 *
 * <p>That independence is the whole point: the pastry and the drink have no
 * relationship to each other, so they are two futures that start at the same time
 * and are joined at the end with {@code thenCombine}. Serving them together takes
 * as long as the slower of the two, not the sum.
 */
public final class PastryStation {

    private final Executor pool;
    private final Tempo tempo;

    public PastryStation(Executor pool, Tempo tempo) {
        this.pool = pool;
        this.tempo = tempo;
    }

    /** Warms the order's pastry. The caller only calls this when there is one. */
    public CompletableFuture<String> warm(Order order) {
        Pastry pastry = order.pastry();
        // DEMO: an independent future - it starts now and runs alongside the drink.
        return CompletableFuture.supplyAsync(() -> {
            Log.info("%s: warming a %s", order.customer(), pastry.name());
            EspressoMachine.sleep(tempo.scale(Pastry.WARM_MILLIS));
            Log.info("%s: %s is warm", order.customer(), pastry.name());
            return pastry.displayName();
        }, pool);
    }
}
