package dev.champ.kimetsu.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertions JUnit doesn't have. It has no {@code assertEquals(long, long, long)}, so a long comparison with a delta
 * resolves to the float overload. A float is exact only to 2^24: in microseconds, its spacing reaches 2 µs past 16.8 s
 * and about a millisecond past 2.4 hours, and live combat times are 50 µs × the world's game time in ticks (Error
 * Prone's LongFloatConversion flagged three such calls; their values were still small enough to be exact).
 */
public final class Asserts {
    private Asserts() {}

    /**
     * {@code actual} lies within {@code tolerance} of {@code expected}, compared exactly in long arithmetic. The
     * difference throws rather than wrap, so a {@code Long.MIN_VALUE} sentinel can never pass as near.
     */
    public static void assertNear(long expected, long actual, long tolerance) {
        if (tolerance < 0) {
            throw new IllegalArgumentException("tolerance must not be negative, was " + tolerance);
        }
        assertTrue(
                Math.absExact(Math.subtractExact(actual, expected)) <= tolerance,
                () -> "expected " + expected + " ± " + tolerance + " but was " + actual);
    }

    /** {@code actual} lies within {@code tolerance} of {@code expected}: geometry's doubles. NaN is never near. */
    public static void assertNear(double expected, double actual, double tolerance) {
        assertTrue(
                Math.abs(actual - expected) <= tolerance,
                () -> "expected " + expected + " ± " + tolerance + " but was " + actual);
    }
}
