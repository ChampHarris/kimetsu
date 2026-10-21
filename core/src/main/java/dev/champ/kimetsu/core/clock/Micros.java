package dev.champ.kimetsu.core.clock;

/**
 * Combat time in whole microseconds, the unit every verdict compares. Windows sit on ±1 ms edges, so the judge
 * compares exact integers; the only fractional maths (the server's arrival conversion and the client's clock
 * estimate) rounds into this unit once, here, so a client and the server round an edge the same way.
 */
public final class Micros {
    public static final long PER_MILLI = 1_000L;
    public static final long PER_TICK = 50_000L;

    private Micros() {}

    public static long ofMillis(long millis) {
        return millis * PER_MILLI;
    }

    public static long ofTicks(long ticks) {
        return ticks * PER_TICK;
    }

    /** The one rounding: a fractional millisecond time to the nearest microsecond, halves upward. */
    public static long round(double millis) {
        return Math.round(millis * PER_MILLI);
    }

    /** The same rounding for a time already in fractional microseconds (the client's clock estimate). */
    public static long roundMicros(double micros) {
        return Math.round(micros);
    }

    /** A millisecond window scaled by a factor (assist's ×1.5), rounded once. */
    public static long scaled(int millis, double factor) {
        return round(millis * factor);
    }

    /**
     * The engine step an input stamped {@code micros} acts in: the first tick at or after it (Rule 2.1). A press stamped
     * inside tick t−1's 50 ms is applied in step t, to the state that tick t−1 left.
     */
    public static long stepOf(long micros) {
        return Math.ceilDiv(micros, PER_TICK);
    }

    /** The tick a moment falls in: rounds toward negative infinity, like {@code Ticks.ofMillis}. */
    public static long tickOf(long micros) {
        return Math.floorDiv(micros, PER_TICK);
    }

    public static double toMillis(long micros) {
        return micros / (double) PER_MILLI;
    }
}
