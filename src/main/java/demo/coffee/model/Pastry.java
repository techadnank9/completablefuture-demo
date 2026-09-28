package demo.coffee.model;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The optional side order. Pastries exist purely so Scenario 3 has two genuinely
 * independent tasks to join with {@code thenCombine}: the drink and the pastry are
 * prepared at the same time by different stations and served together.
 */
public enum Pastry {

    CROISSANT("Croissant"),
    MUFFIN("Muffin"),
    COOKIE("Cookie"),
    BAGEL("Bagel");

    /** Every pastry warms for the same time, so the demo stays predictable. */
    public static final long WARM_MILLIS = 1500;

    private final String displayName;

    Pastry(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public Duration warmTime() {
        return Duration.ofMillis(WARM_MILLIS);
    }

    public static Optional<Pastry> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String needle = text.trim().toUpperCase();
        return Arrays.stream(values()).filter(p -> p.name().equals(needle)).findFirst();
    }

    public static String menu() {
        return String.join(" / ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
