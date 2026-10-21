package dev.champ.kimetsu.core.strike;

/**
 * One strike's verdict on one defender (Rules 2.6, 6.1–6.3; D-039) and what it costs each side. Mutable and reused.
 *
 * <ul>
 *   <li>{@code MISS}: not in the sweep at the impact; nothing happens.
 *   <li>{@code CLEARED}: a miss after a Step or Jump that started inside the sweep after the cue: a dodge (+1).
 *   <li>{@code PERFECT}, {@code DEFLECT}: a claimed guard press, the attacker in front.
 *   <li>{@code BLOCK}: held guard from the front, against a white strike: the strike's block value in Posture.
 *   <li>{@code HIT}, {@code GRAB}, {@code LAUNCH}: it lands (the hit's −4 Rhythm is the machine's own, on its hit).
 * </ul>
 */
public final class Verdict {
    public enum Kind {
        MISS,
        CLEARED,
        PERFECT,
        DEFLECT,
        BLOCK,
        HIT,
        GRAB,
        LAUNCH
    }

    Kind kind = Kind.MISS;
    int rhythm;
    double defenderPosture;
    double attackerPosture;
    int attackerRecoveryTicks;

    void reset() {
        kind = Kind.MISS;
        rhythm = 0;
        defenderPosture = 0;
        attackerPosture = 0;
        attackerRecoveryTicks = 0;
    }

    public Kind kind() {
        return kind;
    }

    /** Rhythm the defender scores: +2 Perfect, +1 deflect, +1 a clearing dodge. */
    public int rhythm() {
        return rhythm;
    }

    /** Posture the defender loses: a deflect's 4, a block's block value. */
    public double defenderPosture() {
        return defenderPosture;
    }

    /** Posture the attacker loses: a Perfect's share of its max × the defender's multiplier, a deflect's 8. */
    public double attackerPosture() {
        return attackerPosture;
    }

    /** Ticks a Perfect on its string's last strike adds to the attacker's recovery. */
    public int attackerRecoveryTicks() {
        return attackerRecoveryTicks;
    }

    /** A deflect or a Perfect: what a claimed Feint is free for (ruling Q8). */
    public boolean answered() {
        return kind == Kind.PERFECT || kind == Kind.DEFLECT;
    }

    /** It reached the defender's body: a hit, a grab or a launch. */
    public boolean landed() {
        return kind == Kind.HIT || kind == Kind.GRAB || kind == Kind.LAUNCH;
    }
}
