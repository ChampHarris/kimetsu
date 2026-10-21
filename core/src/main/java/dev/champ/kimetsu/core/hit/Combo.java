package dev.champ.kimetsu.core.hit;

import dev.champ.kimetsu.core.data.HitRules;

/**
 * One target's hits in a row (Rule 7.5): each landed hit within {@code combo_window_ticks} of the last continues it,
 * from any attacker, and shortens the hitstun it deals. Landed means it reached the body: a hit the target's armour
 * absorbs counts, a block, a deflect or a clang doesn't. A late hit (D-034's held sweeps) measures its gap from the
 * newest hit seen, so it can't restart a combo or break one.
 */
public final class Combo {
    private static final long NONE = Long.MIN_VALUE;

    private long last = NONE;
    private int count;

    /** A hit landed at {@code tick}: its place in the combo, 1 for the first. */
    public int land(long tick, HitRules rules) {
        if (last == NONE || tick - last > rules.comboWindowTicks()) {
            count = 1;
        } else {
            count++;
        }
        last = last == NONE ? tick : Math.max(last, tick);
        return count;
    }

    /** Becomes an exact copy of {@code other} (a state snapshot). */
    public void copyFrom(Combo other) {
        last = other.last;
        count = other.count;
    }

    public void reset() {
        last = NONE;
        count = 0;
    }

    /**
     * The hitstun the {@code n}-th hit in a row deals: base × max(floor, 1 − step (n − 1)), in whole thousandths and
     * rounded half up, so 0.15 and 0.85 aren't left to binary rounding; never below 1 tick unless the base is 0.
     */
    public static int hitstun(int base, int n, HitRules rules) {
        if (n < 1) {
            throw new IllegalArgumentException("a hit's place in a combo starts at 1, was " + n);
        }
        if (base <= 0) {
            return 0;
        }
        long share = Math.max(rules.comboFloorMilli(), 1000L - (long) rules.comboStepMilli() * (n - 1));
        return (int) Math.max(1, (base * share + 500) / 1000);
    }
}
