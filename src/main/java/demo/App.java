package demo;

import demo.coffee.CoffeeDemo;
import demo.coffee.model.Order;
import demo.common.Console;
import demo.common.Log;
import demo.download.DownloadDemo;
import demo.download.server.ServeCommand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Entry point for the CompletableFuture presentation.
 *
 * <pre>
 * java demo.App                    interactive menu
 * java demo.App download [flags]   the real-world download comparison
 * java demo.App coffee   [flags]   the coffee shop example
 * java demo.App both     [flags]   both, in presentation order
 * </pre>
 *
 * <p>Run it straight from IntelliJ with the green arrow on {@code main}. Add
 * {@code --plain} in the run configuration if the console mangles the progress bars.
 *
 * <p>See {@code README.md} for the full flag table.
 */
public final class App {

    /** Set once the demo ends normally, so the shutdown hook stays quiet. */
    private static final java.util.concurrent.atomic.AtomicBoolean FINISHED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void main(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            Console.println(Console.red("  " + e.getMessage()));
            Console.blank();
            printUsage();
            System.exit(2);
            return;
        }

        Console.configure(parsed.plain, parsed.noPause);
        Log.setDebug(parsed.debug);

        if (parsed.help) {
            printUsage();
            return;
        }

        // Ctrl+C still stops the local server and removes the generated test file;
        // this hook only speaks when the run was actually cut short.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!FINISHED.get()) {
                Console.println(Console.dim("\n  Interrupted - cleaning up..."));
            }
        }, "shutdown"));

        try {
            switch (parsed.command) {
                case "serve" -> {
                    // Long-lived public file server. Blocks until the platform stops it.
                    ServeCommand.run(parsed.port, parsed.serveSizeMb, parsed.serveThrottle);
                    return;
                }
                case "download" -> runDownload(parsed);
                case "coffee" -> runCoffee(parsed);
                case "both" -> {
                    runDownload(parsed);
                    Console.pause("Press Enter for the coffee shop example...");
                    runCoffee(parsed);
                }
                default -> runMenu(parsed);
            }
            FINISHED.set(true);
            Console.blank();
            Console.println(Console.bold("  Done. Thanks for watching."));
        } catch (Exception e) {
            Log.error("The demo stopped early", e);
            if (!parsed.debug) {
                Console.println(Console.dim("  Re-run with --debug for the stack trace."));
            }
            System.exit(1);
        }
    }

    private static void runMenu(Args args) throws Exception {
        while (true) {
            Console.banner("CONCURRENCY WITH CompletableFuture");
            Console.println("  1) Download demo   - one file, two ways, real timings");
            Console.println("  2) Coffee demo     - chaining, combining, timeouts, failures");
            Console.println("  3) Both");
            Console.println("  q) Quit");
            Console.blank();

            String choice = Console.ask("  Choose: ").toLowerCase(Locale.ROOT);
            switch (choice) {
                case "1" -> { runDownload(args); return; }
                case "2" -> { runCoffee(args); return; }
                case "3" -> {
                    runDownload(args);
                    Console.pause("Press Enter for the coffee shop example...");
                    runCoffee(args);
                    return;
                }
                case "q", "quit", "exit", "" -> { return; }
                default -> Console.println(Console.yellow("  Pick 1, 2, 3 or q."));
            }
        }
    }

    private static void runDownload(Args args) throws Exception {
        new DownloadDemo(new DownloadDemo.Options(
                args.url, args.chunks, args.sizeMb, args.throttleMbps,
                args.comparePools, Path.of(args.outDir))).run();
    }

    private static void runCoffee(Args args) {
        boolean interactive = args.orders.isEmpty() && !args.noPause;
        List<Order> orders = args.orders.isEmpty() ? CoffeeDemo.defaultOrders() : args.orders;
        new CoffeeDemo(new CoffeeDemo.Options(orders, args.baristas, interactive)).run();
    }

    /** Parsed command line. Deliberately hand-rolled - the project has no dependencies. */
    static final class Args {
        String command = "menu";
        String url = null;
        int chunks = 8;
        int sizeMb = 64;
        double throttleMbps = 3.0;
        boolean comparePools = false;
        String outDir = "./downloads";
        int baristas = 4;
        Integer port = null;                 // serve mode; null means "read $PORT"
        Integer serveSizeMb = null;
        Double serveThrottle = null;
        List<Order> orders = new ArrayList<>();
        boolean noPause = false;
        boolean plain = false;
        boolean debug = false;
        boolean help = false;

        static Args parse(String[] argv) {
            Args args = new Args();
            int index = 0;

            if (argv.length > 0 && !argv[0].startsWith("-")) {
                String command = argv[0].toLowerCase(Locale.ROOT);
                if (!List.of("download", "coffee", "both", "menu", "serve").contains(command)) {
                    throw new IllegalArgumentException("Unknown command '" + argv[0] + "'");
                }
                args.command = command;
                index = 1;
            }

            for (; index < argv.length; index++) {
                String flag = argv[index];
                switch (flag) {
                    case "--local" -> args.url = null;
                    case "--url" -> args.url = value(argv, ++index, "--url");
                    case "--chunks" -> args.chunks = positiveInt(value(argv, ++index, "--chunks"), "--chunks");
                    case "--size-mb" -> {
                        args.sizeMb = positiveInt(value(argv, ++index, "--size-mb"), "--size-mb");
                        args.serveSizeMb = args.sizeMb;
                    }
                    case "--throttle-mbps" -> {
                        args.throttleMbps = positiveDouble(value(argv, ++index, "--throttle-mbps"), "--throttle-mbps");
                        args.serveThrottle = args.throttleMbps;
                    }
                    case "--compare-pools" -> args.comparePools = true;
                    case "--port" -> args.port = positiveInt(value(argv, ++index, "--port"), "--port");
                    case "--out" -> args.outDir = value(argv, ++index, "--out");
                    case "--baristas" -> args.baristas = positiveInt(value(argv, ++index, "--baristas"), "--baristas");
                    case "--orders" -> args.orders = parseOrders(value(argv, ++index, "--orders"));
                    case "--no-pause" -> args.noPause = true;
                    case "--plain" -> args.plain = true;
                    case "--debug" -> args.debug = true;
                    case "-h", "--help" -> args.help = true;
                    default -> throw new IllegalArgumentException("Unknown flag '" + flag + "'");
                }
            }
            return args;
        }

        private static List<Order> parseOrders(String spec) {
            List<Order> orders = new ArrayList<>();
            for (String entry : spec.split(",")) {
                if (!entry.isBlank()) {
                    orders.add(Order.parse(entry.trim()));
                }
            }
            return orders;
        }

        private static String value(String[] argv, int index, String flag) {
            if (index >= argv.length) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return argv[index];
        }

        private static int positiveInt(String text, String flag) {
            try {
                int parsed = Integer.parseInt(text);
                if (parsed < 1) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " must be a positive whole number, got '" + text + "'");
            }
        }

        private static double positiveDouble(String text, String flag) {
            try {
                double parsed = Double.parseDouble(text);
                if (parsed <= 0) {
                    throw new NumberFormatException();
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " must be a positive number, got '" + text + "'");
            }
        }
    }

    private static void printUsage() {
        Console.println("""
              Concurrency with CompletableFuture - demo

              Usage:
                java demo.App                      interactive menu
                java demo.App download [options]
                java demo.App coffee   [options]
                java demo.App both     [options]
                java demo.App serve    [options]   run as a public file server

              Serve options (for hosting a real --url target):
                --port <n>                port to bind (default $PORT, else 10000)
                --size-mb <n>             size of the served file (default $DEMO_SIZE_MB, else 8)
                --throttle-mbps <n>       per-connection limit (default $DEMO_THROTTLE_MBPS, else 0.4)

              Download options:
                --local                   use the built-in throttled server (default)
                --url <url>               download this URL instead (must support byte ranges)
                --chunks <n>              parallel chunks (default 8)
                --size-mb <n>             size of the generated test file (default 64)
                --throttle-mbps <n>       per-connection limit in local mode (default 3)
                --compare-pools           extra round: 1/2/4/8/16/32 chunks, printed as a table
                --out <dir>               output directory (default ./downloads)

              Coffee options:
                --orders "Name:DRINK[:PASTRY],..."   orders on the command line
                --baristas <n>                       barista pool size (default 4)

              Shared:
                --no-pause                do not wait for Enter between rounds
                --plain                   no ANSI colours or cursor movement
                --debug                   print stack traces
                -h, --help                this message
              """);
    }
}
