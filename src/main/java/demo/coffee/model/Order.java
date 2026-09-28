package demo.coffee.model;

import java.util.Optional;

/**
 * One customer's order: a name, a drink, and optionally a pastry.
 *
 * @param customer the customer's name, as typed by the presenter during the talk
 * @param drink    the drink, never {@code null}
 * @param pastry   the pastry, or {@code null} for drink only
 */
public record Order(String customer, Drink drink, Pastry pastry) {

    public Order {
        if (customer == null || customer.isBlank()) {
            throw new IllegalArgumentException("customer name is required");
        }
        if (drink == null) {
            throw new IllegalArgumentException("drink is required");
        }
    }

    public static Order of(String customer, Drink drink) {
        return new Order(customer, drink, null);
    }

    public static Order of(String customer, Drink drink, Pastry pastry) {
        return new Order(customer, drink, pastry);
    }

    public Optional<Pastry> optionalPastry() {
        return Optional.ofNullable(pastry);
    }

    public boolean hasPastry() {
        return pastry != null;
    }

    /** e.g. {@code "Sahana: Latte + Croissant"}. */
    public String describe() {
        return customer + ": " + drink.displayName()
                + (hasPastry() ? " + " + pastry.displayName() : "");
    }

    /**
     * Parses one entry of {@code --orders "Name:DRINK[:PASTRY],..."}.
     *
     * @throws IllegalArgumentException if the drink is not on the menu
     */
    public static Order parse(String spec) {
        String[] parts = spec.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("expected Name:DRINK[:PASTRY] but got '" + spec + "'");
        }
        Drink drink = Drink.parse(parts[1])
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown drink '" + parts[1] + "'. Menu: " + Drink.menu()));
        Pastry pastry = parts.length > 2 ? Pastry.parse(parts[2]).orElse(null) : null;
        return new Order(parts[0].trim(), drink, pastry);
    }
}
