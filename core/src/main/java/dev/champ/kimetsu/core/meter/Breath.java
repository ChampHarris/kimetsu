package dev.champ.kimetsu.core.meter;

import dev.champ.kimetsu.core.data.BreathRules;

/**
 * A Slayer's Breath (Rule 4.1). It regenerates at the idle or the in-combat rate; held guard drains it on top of that,
 * except while Winded, when the guard drains Posture instead (the engine moves that drain to {@link Posture}). Reaching
 * 0 by any cost or drain winds the Slayer for the Winded time. A form's cost shows as a grey bar until its recovery
 * ends: an on-beat Inhale in the recovery refunds it (a Recovery Breath), an off-beat one forfeits it and winds.
 */
public final class Breath {
    private final BreathRules rules;
    private final long max;
    private Segment seg;
    private boolean inCombat;
    private boolean guardHeld;
    private long windedUntil = Long.MIN_VALUE;
    private long grey;
    private long inhaleBeat = Long.MIN_VALUE;
    private int version;

    /** Full Breath at {@code tick}, out of combat. */
    public Breath(BreathRules rules, long tick) {
        this.rules = rules;
        this.max = Fixed.of(rules.max());
        this.seg = new Segment(max, rate(tick), tick, max);
    }

    public long at(long tick) {
        return seg.at(tick);
    }

    /** The segment to sync; it changes only when {@link #version} does. */
    public Segment segment() {
        return seg;
    }

    public int version() {
        return version;
    }

    /** The grey bar: the cost of the form in recovery, refundable. */
    public long grey() {
        return grey;
    }

    public boolean winded(long tick) {
        return tick < windedUntil;
    }

    public boolean canAfford(long tick, long cost) {
        return at(tick) >= cost;
    }

    /** The in-combat flag sets the regeneration rate. */
    public void setInCombat(long tick, boolean value) {
        if (inCombat != value) {
            inCombat = value;
            retune(tick);
        }
    }

    public void setGuard(long tick, boolean held) {
        if (guardHeld != held) {
            guardHeld = held;
            retune(tick);
        }
    }

    /** A Step, a Feint or a guard-cancel: spent at once; reaching 0 winds. */
    public void spend(long tick, long cost) {
        set(tick, at(tick) - cost);
    }

    /** A form's cost, shown grey until its recovery ends. */
    public void spendForm(long tick, long cost) {
        spend(tick, cost);
        grey = cost;
    }

    /** An on-beat Inhale tap restores the Inhale amount, once per beat. Returns whether it did. */
    public boolean inhale(long tick, long beat) {
        if (beat == inhaleBeat) {
            return false;
        }
        inhaleBeat = beat;
        set(tick, at(tick) + Fixed.of(rules.onBeatInhale()));
        return true;
    }

    /** An Inhale tap in a form's recovery: on the beat it refunds the grey bar; off it, the bar is lost and it winds. */
    public void recoveryBreath(long tick, boolean onBeat) {
        long refund = Math.round(grey * rules.recoveryRefundShare());
        grey = 0;
        if (onBeat) {
            set(tick, at(tick) + refund);
        } else {
            wind(tick);
        }
    }

    /** The form's recovery ended or was cancelled: the grey bar is forfeited. */
    public void endRecovery() {
        grey = 0;
    }

    public void wind(long tick) {
        windedUntil = tick + rules.windedTicks();
        retune(tick);
    }

    /** Once per tick, after the tick's events: Winded runs out, and held guard draining Breath to 0 winds. */
    public void tick(long now) {
        if (windedUntil != Long.MIN_VALUE && now >= windedUntil) {
            windedUntil = Long.MIN_VALUE;
            retune(now);
        }
        if (seg.ratePerTick() < 0 && at(now) == 0 && !winded(now)) {
            set(now, 0);
        }
    }

    private long rate(long tick) {
        long regen = Fixed.perTick(inCombat ? rules.regenCombatPerS() : rules.regenIdlePerS());
        long drain = guardHeld && !winded(tick) ? Fixed.perTick(rules.heldGuardPerS()) : 0;
        return regen - drain;
    }

    private void retune(long tick) {
        long r = rate(tick);
        if (r != seg.ratePerTick()) {
            replace(new Segment(at(tick), r, tick, max));
        }
    }

    private void set(long tick, long value) {
        long v = Math.clamp(value, 0, max);
        if (v == 0 && !winded(tick)) {
            windedUntil = tick + rules.windedTicks();
        }
        replace(new Segment(v, rate(tick), tick, max));
    }

    private void replace(Segment s) {
        seg = s;
        version++;
    }
}
