package dev.champ.kimetsu.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertions JUnit doesn't have. It has no {@code assertEquals(long, long, long)}, so a long comparison with a delta
 * resolves to the float overload, whose 24-bit mantissa can't hold a combat time in microseconds past about 16 s:
 * "within 2 µs" quietly became "within a millisecond" (Error Prone's LongFloatConversion caught it).
 */
public final class Asserts {
    private Asserts() {}

    /** {@code actual} lies within {@code tolerance} of {@code expected}, compared exactly in long arithmetic. */
    public static void assertNear(long expected, long actual, long tolerance) {
        assertTrue(
                Math.abs(actual - expected) <= tolerance,
                () -> "expected " + expected + " ± " + tolerance + " but was " + actual);
    }
}
