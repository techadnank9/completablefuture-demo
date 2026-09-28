package demo.coffee.model;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The menu, with fixed preparation times so every run of the demo takes the same
 * time and the presenter can promise a number before pressing Enter.
 *
 * <p>Each drink also declares which steps it needs. That is what makes
 * {@code thenCompose} meaningful in Scenario 3: a latte is grind, then brew, then
 * steam milk - three dependent async stages, each one needing the previous one's
 * output.
 */
public enum Drink {

    ESPRESSO("Espresso", 1000, true, false),
    TEA("Tea", 1500, false, false),
    LATTE("Latte", 2500, true, true),
    CAPPUCCINO("Cappuccino", 2500, true, true),
    MOCHA("Mocha", 3000, true, true);

    private final String displayName;
    private final long totalMillis;
    private final boolean needsEspresso;
    private final boolean needsSteamedMilk;

    Drink(String displayName, long totalMillis, boolean needsEspresso, boolean needsSteamedMilk) {
        this.displayName = displayName;
        this.totalMillis = totalMillis;
        this.needsEspresso = needsEspresso;
        this.needsSteamedMilk = needsSteamedMilk;
    }

    public String displayName() {
        return displayName;
    }

    /** Total preparation time when made in one blocking go. */
    public Duration prepTime() {
        return Duration.ofMillis(totalMillis);
    }

    public long prepMillis() {
        return totalMillis;
    }

    /** Needs the espresso machine, so it is the drink that can "break". */
    public boolean needsEspresso() {
        return needsEspresso;
    }

    public boolean needsSteamedMilk() {
        return needsSteamedMilk;
    }

    // The pipeline stages split the total time so Scenario 3 adds up to the same
    // number the audience saw in Scenarios 1 and 2.

    public long grindMillis() {
        return needsEspresso ? Math.round(totalMillis * 0.25) : 0;
    }

    public long brewMillis() {
        return needsSteamedMilk ? Math.round(totalMillis * 0.45) : totalMillis - grindMillis();
    }

    public long steamMillis() {
        return needsSteamedMilk ? totalMillis - grindMillis() - brewMillis() : 0;
    }

    /** Case-insensitive lookup for the interactive order prompt. */
    public static Optional<Drink> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String needle = text.trim().toUpperCase().replace(' ', '_');
        return Arrays.stream(values()).filter(d -> d.name().equals(needle)).findFirst();
    }

    /** e.g. {@code "LATTE / MOCHA / ESPRESSO / TEA / CAPPUCCINO"} for the prompt. */
    public static String menu() {
        return String.join(" / ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
