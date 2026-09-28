package demo.coffee;

import demo.common.Console;
import demo.common.Executors2;
import demo.common.Log;
import demo.common.Stopwatch;
import demo.coffee.model.Drink;
import demo.coffee.model.Order;
import demo.coffee.model.Pastry;
import demo.coffee.model.Served;
import demo.coffee.service.CoffeeShop;
import demo.coffee.service.EspressoMachine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * The friendly half of the talk: the same ideas as the downloader, in a domain
 * everyone in the room already understands.
 *
 * <p>Four short scenarios, each mapped to one slide:
 * <ol>
 *   <li>One barista, blocking - the problem.</li>
 *   <li>Several baristas - {@code supplyAsync}, {@code allOf}, {@code anyOf}.</li>
 *   <li>Recipes and combos - {@code thenCompose}, {@code thenCombine}.</li>
 *   <li>The machine breaks - {@code exceptionally}, {@code orTimeout}, {@code handle}.</li>
 * </ol>
 *
 * <p>Orders can be typed in live, which is the point: taking real names from
 * classmates makes the thread-name log lines land much harder than placeholder data.
 */
public final class CoffeeDemo {

    /**
     * Grace period on top of a drink's own preparation time before the timeout
     * fallback fires. Relative, not absolute: a flat 3s deadline would cut off a
     * perfectly healthy 3s mocha.
     */
    private static final Duration PATIENCE = Duration.ofSeconds(2);

    private static final int MAX_ORDERS = 6;

    private final Options options;

    public CoffeeDemo(Options options) {
        this.options = options;
    }

    /** Parsed {@code coffee} command-line options. */
    public record Options(List<Order> orders, int baristas, boolean interactive) {
    }

    /** Used when the presenter just presses Enter at the order prompt. */
    public static List<Order> defaultOrders() {
        return List.of(
                Order.of("Sahana", Drink.LATTE, Pastry.CROISSANT),
                Order.of("Mustafa", Drink.MOCHA),
                Order.of("Priya", Drink.ESPRESSO),
                Order.of("Daniel", Drink.CAPPUCCINO, Pastry.MUFFIN),
                Order.of("Amara", Drink.TEA));
    }

    public void run() {
        Log.resetClock();
        Console.banner("COFFEE SHOP: THE SAME IDEA, IN PLAIN ENGLISH");

        List<Order> orders = options.interactive() ? takeOrders() : options.orders();
        if (orders.isEmpty()) {
            orders = defaultOrders();
        }

        printOrders(orders);

        EspressoMachine machine = new EspressoMachine();
        ExecutorService baristaPool = Executors2.named("barista", options.baristas());
        ExecutorService pastryPool = Executors2.named("pastry", 2);

        try {
            CoffeeShop shop = new CoffeeShop(machine, baristaPool, pastryPool, PATIENCE);

            double sequentialSeconds = scenario1(shop, orders);
            scenario2(shop, orders, sequentialSeconds);
            scenario3(shop, orders);
            scenario4(shop, orders);

        } finally {
            // Every pool is shut down, always - the resource-management talking point.
            Executors2.shutdown(baristaPool);
            Executors2.shutdown(pastryPool);
            Log.detail("barista and pastry pools shut down");
        }
    }

    // ------------------------------------------------------------------

    private double scenario1(CoffeeShop shop, List<Order> orders) {
        Console.heading("SCENARIO 1 - one barista, one order at a time");
        Console.println(Console.dim("  No futures. The queue moves at the speed of one person."));
        Console.pause("Press Enter to start the shift...");

        Log.resetClock();
        Stopwatch watch = Stopwatch.start();
        List<Served> results = shop.serveSequentially(orders);
        watch.stop();

        Console.blank();
        Console.println(String.format("  %d orders served in %s", results.size(), watch.format()));
        return watch.elapsedSeconds();
    }

    private void scenario2(CoffeeShop shop, List<Order> orders, double sequentialSeconds) {
        Console.heading("SCENARIO 2 - several baristas (supplyAsync + allOf + anyOf)");
        Console.println(Console.dim(
                "  Same orders, " + options.baristas() + " baristas. Watch the thread names in the log."));
        Console.pause("Press Enter to open the second till...");

        Log.resetClock();
        CoffeeShop.ShiftResult result = shop.serveConcurrently(orders);

        Console.blank();
        if (!result.firstReady().isBlank()) {
            Console.println("  First drink ready: " + Console.green(result.firstReady()));
        }
        double speedup = result.seconds() > 0 ? sequentialSeconds / result.seconds() : 0;
        Console.println(String.format("  All %d orders served in %.1fs (one barista: %.1fs) -> %s",
                result.served().size(), result.seconds(), sequentialSeconds,
                Console.green(String.format("%.1fx faster", speedup))));
        Console.println(Console.dim(
                "  Total time is now the SLOWEST drink, not the sum of all of them."));
    }

    private void scenario3(CoffeeShop shop, List<Order> orders) {
        Console.heading("SCENARIO 3 - recipes and combos (thenCompose + thenCombine)");
        Console.println(Console.dim(
                "  thenCompose chains steps that depend on each other: grind -> brew -> steam.\n"
              + "  thenCombine joins two independent futures: the drink and the pastry."));
        Console.pause("Press Enter to make one drink properly...");

        // Prefer an order with a pastry, so thenCombine has something to show.
        Order showcase = orders.stream()
                .filter(Order::hasPastry)
                .findFirst()
                .orElse(orders.get(0));

        Log.resetClock();
        Stopwatch watch = Stopwatch.start();
        Served served = shop.serveWithPipeline(showcase).join();
        watch.stop();

        Console.blank();
        Console.println("  " + served.detail() + "  " + Console.dim("(" + watch.format() + ")"));
        if (showcase.hasPastry()) {
            Console.println(Console.dim(String.format(
                    "  The pastry warmed while the coffee brewed, so the pair cost %s, not %.1fs.",
                    watch.format(),
                    (showcase.drink().prepMillis() + Pastry.WARM_MILLIS) / 1000.0)));
        }
    }

    private void scenario4(CoffeeShop shop, List<Order> orders) {
        Console.heading("SCENARIO 4 - the machine breaks (exceptionally + orTimeout + handle)");

        Order victim = chooseVictim(orders);
        boolean hang = chooseHang();

        shop.machine().repair();
        if (hang) {
            shop.machine().hangFor(victim.customer());
            Console.println(Console.dim(
                    "  The machine will stop responding for " + victim.customer() + ". Nothing throws -\n"
                  + "  only orTimeout (prep time + " + PATIENCE.toSeconds()
                  + "s grace) can notice that nothing is happening."));
        } else {
            shop.machine().breakFor(victim.customer());
            Console.println(Console.dim(
                    "  The machine will throw while making " + victim.customer() + "'s drink.\n"
                  + "  exceptionally() supplies a substitute so the pipeline still produces a result."));
        }
        Console.pause("Press Enter to run the shift with a faulty machine...");

        Log.resetClock();
        Stopwatch watch = Stopwatch.start();
        List<Served> results = shop.serveWithFailures(orders);
        watch.stop();

        Console.blank();
        Console.println(Console.bold("  Shift summary"));
        for (Served served : results) {
            String tag = switch (served.status()) {
                case SERVED -> Console.green("SERVED   ");
                case FALLBACK -> Console.yellow("FALLBACK ");
                case TIMED_OUT -> Console.yellow("TIMED_OUT");
            };
            Console.println("  " + tag + "  " + served.describe());
        }

        Map<Served.Status, Long> counts = results.stream()
                .collect(Collectors.groupingBy(Served::status, Collectors.counting()));
        Console.blank();
        Console.println(String.format("  %d served, %d fallback, %d timed out, all in %s",
                counts.getOrDefault(Served.Status.SERVED, 0L),
                counts.getOrDefault(Served.Status.FALLBACK, 0L),
                counts.getOrDefault(Served.Status.TIMED_OUT, 0L),
                watch.format()));
        Console.println(Console.dim(
                "  One broken order did not take the others down with it, and the program never crashed."));

        shop.machine().repair();
    }

    // ------------------------------------------------------------------
    // Taking orders from the room
    // ------------------------------------------------------------------

    private List<Order> takeOrders() {
        Console.println(Console.dim(
                "  Take up to " + MAX_ORDERS + " orders. Press Enter on an empty name to finish\n"
              + "  (an empty first name uses the default five orders)."));
        Console.blank();

        List<Order> orders = new ArrayList<>();
        while (orders.size() < MAX_ORDERS) {
            String name = Console.ask("  Customer name (Enter to finish): ");
            if (name.isBlank()) {
                break;
            }
            Drink drink = askDrink();
            Pastry pastry = Pastry.parse(
                    Console.ask("  Pastry, optional [" + Pastry.menu() + "]: ")).orElse(null);
            orders.add(new Order(name, drink, pastry));
            Console.blank();
        }
        return orders;
    }

    private Drink askDrink() {
        while (true) {
            String answer = Console.ask("  Drink [" + Drink.menu() + "]: ");
            if (answer.isBlank()) {
                return Drink.LATTE;         // sensible default keeps the demo moving
            }
            var parsed = Drink.parse(answer);
            if (parsed.isPresent()) {
                return parsed.get();
            }
            Console.println(Console.yellow("    Not on the menu. Try one of: " + Drink.menu()));
        }
    }

    private Order chooseVictim(List<Order> orders) {
        // An espresso-based drink is the interesting victim: tea never touches the machine.
        Order fallback = orders.stream()
                .filter(order -> order.drink().needsEspresso())
                .findFirst()
                .orElse(orders.get(0));

        String answer = Console.ask("  Which customer's order should fail? [" + fallback.customer() + "]: ");
        if (answer.isBlank()) {
            return fallback;
        }
        return orders.stream()
                .filter(order -> order.customer().equalsIgnoreCase(answer.trim()))
                .findFirst()
                .orElseGet(() -> {
                    Console.println(Console.yellow("    No such customer - using " + fallback.customer() + "."));
                    return fallback;
                });
    }

    private boolean chooseHang() {
        String answer = Console.ask("  Break or hang? [b/h] (default b): ");
        return answer.toLowerCase().startsWith("h");
    }

    private void printOrders(List<Order> orders) {
        Console.blank();
        Console.println(Console.bold("  Today's queue"));
        for (Order order : orders) {
            Console.println(String.format("   - %-28s %s",
                    order.describe(),
                    Console.dim(String.format("%.1fs", order.drink().prepMillis() / 1000.0))));
        }
        double sum = orders.stream().mapToDouble(o -> o.drink().prepMillis() / 1000.0).sum();
        Console.println(Console.dim(String.format("   One barista would need %.1fs for all of this.", sum)));
    }
}
