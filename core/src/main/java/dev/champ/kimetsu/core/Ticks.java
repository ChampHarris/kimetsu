package dev.champ.kimetsu.core;

/**
 * Server time. One frame is one server tick, 50 ms (20 ticks a second), in every design doc and data file.
 * Beat maths runs in milliseconds (a ±120 ms window is only 2.4 ticks), so rules convert at the edges with these.
 */
public final class Ticks {
    public static final int PER_SECOND = 20;
    public static final long MILLIS_PER_TICK = 1000L / PER_SECOND;

    private Ticks() {
    }

    /** Milliseconds from the start of tick 0 to the start of {@code ticks}. */
    public static long toMillis(long ticks) {
        return ticks * MILLIS_PER_TICK;
    }

    /** The tick a moment falls in: rounds toward negative infinity, so -1 ms is in tick -1, not tick 0. */
    public static long ofMillis(long millis) {
        return Math.floorDiv(millis, MILLIS_PER_TICK);
    }
}
