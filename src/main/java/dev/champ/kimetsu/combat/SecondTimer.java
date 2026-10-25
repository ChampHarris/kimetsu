package dev.champ.kimetsu.combat;

/**
 * The server time one piece of the combat work took (spec Performance Budget; slice 5c-4a): each stepped tick's pieces
 * add up, and each second of steps, 20k to 20k + 19, closes into its figures after its last step, the mean per stepped
 * tick and the longest tick, which the fight log's {@code clock} line and {@code /kimetsu debug} read. Every timer
 * closes the same seconds, whenever it started, so the arena's steps and the server's tick work they sit inside are
 * figures for one second; a tick that steps no fight (frozen, held) is skipped. Two {@code nanoTime} calls per
 * piece; nothing is allocated. Main thread only.
 */
final class SecondTimer {
    private static final int SECOND = 20; // ticks

    private long tickNanos; // the tick under way
    private long second = Long.MIN_VALUE; // the second under way: its steps / 20
    private long sumNanos;
    private long maxNanos;
    private int ticks;
    private double meanMicros; // the last second closed
    private double maxMicros;

    /** A piece of the tick under way took {@code nanos}. */
    void add(long nanos) {
        tickNanos += nanos;
    }

    /**
     * The tick under way was step {@code step}: it joins its second, which closes after the second's last step, or once
     * a step of another second comes first (a re-anchor renumbers the steps).
     */
    void tick(long step) {
        long s = Math.floorDiv(step, SECOND);
        if (s != second) {
            close();
            second = s;
        }
        sumNanos += tickNanos;
        maxNanos = Math.max(maxNanos, tickNanos);
        tickNanos = 0;
        ticks++;
        if (Math.floorMod(step, SECOND) == SECOND - 1) {
            close();
        }
    }

    /** The tick under way stepped no fight: its time is no fight's, and it isn't counted. */
    void skip() {
        tickNanos = 0;
    }

    private void close() {
        if (ticks == 0) {
            return;
        }
        meanMicros = sumNanos / 1000.0 / ticks;
        maxMicros = maxNanos / 1000.0;
        sumNanos = 0;
        maxNanos = 0;
        ticks = 0;
    }

    /** The last second's mean per stepped tick, in µs (0 before the first second closes). */
    double meanMicros() {
        return meanMicros;
    }

    /** The last second's longest tick, in µs (0 before the first second closes). */
    double maxMicros() {
        return maxMicros;
    }
}
