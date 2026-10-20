package dev.champ.kimetsu.core.meter;

import dev.champ.kimetsu.core.data.HungerRules;

/**
 * A demon's Hunger (Rule 4.4), in thousandths. Regeneration pays for each HP it heals and stops when Hunger runs out;
 * the leap and rising from collapse pay their costs, as much as is left (a demon at 0 Hunger still rises: Rule 8.4).
 * Above the hardening line its neck is hardened (Rule 8.2). It never regenerates in M1.
 */
public final class Hunger {
    private final HungerRules rules;
    private final long max;
    private long value;

    /** Full Hunger. */
    public Hunger(HungerRules rules) {
        this.rules = rules;
        this.max = Fixed.of(rules.max());
        this.value = max;
    }

    public long value() {
        return value;
    }

    /** Cellular Hardening: strictly above the line. */
    public boolean hardened() {
        return value > Math.round(max * rules.hardeningAbove());
    }

    /** Pays up to {@code amount}; returns what it paid. */
    public long pay(long amount) {
        long paid = Math.min(value, Math.max(0, amount));
        value -= paid;
        return paid;
    }

    public long payLeap() {
        return pay(Fixed.of(rules.leapCost()));
    }

    public long payRise() {
        return pay(Fixed.of(rules.riseCost()));
    }

    /** Heals up to {@code hpFixed} HP (thousandths) with the Hunger left; returns the HP paid for. */
    public long payForHp(long hpFixed) {
        if (rules.perHp() == 0) {
            return hpFixed;
        }
        long hp = Math.min(hpFixed, value / rules.perHp());
        value -= hp * rules.perHp();
        return hp;
    }
}
