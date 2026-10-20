package dev.champ.kimetsu.core.meter;

import dev.champ.kimetsu.core.Ticks;

/**
 * Meter amounts in thousandths, so the client's predicted meters and the server's are the same integers (Netcode,
 * "Prediction and reconciliation"). Rates per second become amounts per tick here, rounded once: every rate in the
 * Numbers table is a whole number of thousandths per tick (Breath's 4/s is 200, Posture's 12/s is 600).
 */
public final class Fixed {
    public static final long ONE = 1000;

    private Fixed() {
    }

    public static long of(double units) {
        return Math.round(units * ONE);
    }

    public static long perTick(double perSecond) {
        return Math.round(perSecond * ONE / Ticks.PER_SECOND);
    }

    public static double toUnits(long fixed) {
        return fixed / (double) ONE;
    }
}
