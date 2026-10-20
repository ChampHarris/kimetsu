package dev.champ.kimetsu.core.meter;

import dev.champ.kimetsu.core.data.PostureRules;

/**
 * Posture (Rule 4.2), for any combatant profile. It recovers at the profile's rate once the delay after the last
 * Posture damage has passed, scaled by vitality: floor + (1 − floor) × (1 − the share of max HP taken this fight), so a
 * wounded demon recovers more slowly (R6-06; a floor of 1 turns it off). Damage that the demon's guard stance blocks
 * doesn't restart the delay. A Rhythm credit restores a little at once (players). Held guard while Winded drains it
 * and holds recovery off until the delay after the drain stops. At 0 it breaks: STAGGER, then it resets to the reset
 * fraction of max.
 */
public final class Posture {
    private final PostureRules rules;
    private final long max;
    private Segment seg;
    private long regenFrom;
    private long staggerUntil = Long.MIN_VALUE;
    private double vitality = 1.0;
    private long drain;
    private int version;

    /** Full Posture at {@code tick}. */
    public Posture(PostureRules rules, long tick) {
        this.rules = rules;
        this.max = Fixed.of(rules.max());
        this.regenFrom = tick;
        this.seg = new Segment(max, 0, tick, max);
    }

    public long at(long tick) {
        if (staggerUntil != Long.MIN_VALUE && tick >= staggerUntil) {
            return afterStagger().at(tick);
        }
        return seg.at(tick);
    }

    public Segment segment() {
        return seg;
    }

    public int version() {
        return version;
    }

    public boolean staggered(long tick) {
        return tick < staggerUntil;
    }

    /** Posture damage. Returns true when it breaks Posture (the caller puts the combatant in STAGGER). */
    public boolean damage(long tick, long amount, boolean restartDelay) {
        settle(tick);
        if (staggered(tick)) {
            return false;
        }
        if (restartDelay) {
            regenFrom = tick + rules.delayTicks();
        }
        long v = at(tick) - amount;
        if (v <= 0) {
            breakAt(tick);
            return true;
        }
        replace(new Segment(v, rate(tick), tick, max));
        return false;
    }

    /** A Rhythm credit's restore. */
    public void restore(long tick, long amount) {
        settle(tick);
        if (!staggered(tick)) {
            replace(new Segment(Math.min(max, at(tick) + amount), rate(tick), tick, max));
        }
    }

    /** Vitality coupling: the share of max HP taken this fight, 0 to 1. */
    public void setDamageTaken(long tick, double share) {
        settle(tick);
        double v = rules.vitalityFloor() + (1 - rules.vitalityFloor()) * Math.max(0, 1 - share);
        if (v != vitality) {
            vitality = v;
            retune(tick);
        }
    }

    /** Held guard while Winded: {@code perTick} > 0 drains, 0 stops the drain and starts the delay. */
    public void setDrain(long tick, long perTick) {
        settle(tick);
        if (perTick == drain) {
            return;
        }
        if (perTick == 0) {
            regenFrom = tick + rules.delayTicks();
        }
        drain = perTick;
        retune(tick);
    }

    /** Once per tick, after the tick's events: the stagger ends, recovery starts, a drain reaching 0 breaks. */
    public void tick(long now) {
        settle(now);
        if (seg.ratePerTick() < 0 && at(now) == 0 && !staggered(now)) {
            breakAt(now);
            return;
        }
        retune(now);
    }

    /**
     * Ends a stagger that's due. Damage is applied before timers in a server tick (Rule 3's order), so a hit on the
     * tick a stagger ends must find Posture already reset, not at 0, or it would break it again at once.
     */
    private void settle(long tick) {
        if (staggerUntil != Long.MIN_VALUE && tick >= staggerUntil) {
            Segment reset = afterStagger();
            staggerUntil = Long.MIN_VALUE;
            replace(reset);
        }
    }

    private Segment afterStagger() {
        long from = staggerUntil;
        long rate = drain > 0 ? -drain : from < regenFrom ? 0 : Math.round(Fixed.perTick(rules.regenPerS()) * vitality);
        return new Segment(Math.round(max * rules.resetFraction()), rate, from, max);
    }

    private void breakAt(long tick) {
        staggerUntil = tick + rules.staggerTicks();
        replace(new Segment(0, 0, tick, max));
    }

    private long rate(long tick) {
        if (staggered(tick)) {
            return 0;
        }
        if (drain > 0) {
            return -drain;
        }
        if (tick < regenFrom) {
            return 0;
        }
        return Math.round(Fixed.perTick(rules.regenPerS()) * vitality);
    }

    private void retune(long tick) {
        long r = rate(tick);
        if (r != seg.ratePerTick()) {
            replace(new Segment(at(tick), r, tick, max));
        }
    }

    private void replace(Segment s) {
        seg = s;
        version++;
    }
}
