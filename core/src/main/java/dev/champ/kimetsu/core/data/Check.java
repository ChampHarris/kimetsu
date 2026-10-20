package dev.champ.kimetsu.core.data;

/**
 * Validation for the rule records. A bad datapack value fails where the mod decodes it, with the JSON field's name in
 * the message, instead of surfacing later as a strange verdict.
 */
final class Check {
    private Check() {
    }

    static void positive(String field, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive, was " + value);
        }
    }

    static void atLeast(String field, long value, long min) {
        if (value < min) {
            throw new IllegalArgumentException(field + " must be at least " + min + ", was " + value);
        }
    }

    static void atMost(String field, long value, long max) {
        if (value > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + ", was " + value);
        }
    }

    static void atLeast(String field, double value, double min) {
        if (!(value >= min)) {    // also rejects NaN
            throw new IllegalArgumentException(field + " must be at least " + min + ", was " + value);
        }
    }

    static void between(String field, double value, double min, double max) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ", was " + value);
        }
    }
}
