package dev.champ.kimetsu.core.strike;

import dev.champ.kimetsu.core.judge.DeflectJudge;

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
 *
 * <p>Against a white strike it also holds the guard's record for the fight log (D-089): the gates the verdict passed
 * through and the press that decided it, so a guard that came back as a block or a hit says why.
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
    boolean guardJudged;
    boolean inSweep;
    boolean inArc;
    boolean stateOk;
    DeflectJudge.Attempt attempt = DeflectJudge.Attempt.NONE;
    long pressMicros = DeflectJudge.NO_PRESS;
    int assist;

    void reset() {
        kind = Kind.MISS;
        rhythm = 0;
        defenderPosture = 0;
        attackerPosture = 0;
        attackerRecoveryTicks = 0;
        guardJudged = false;
        inSweep = false;
        inArc = false;
        stateOk = false;
        attempt = DeflectJudge.Attempt.NONE;
        pressMicros = DeflectJudge.NO_PRESS;
        assist = 0;
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

    /** Whether it judged a guard (a white strike on a living defender): the record below is filled. */
    public boolean guardJudged() {
        return guardJudged;
    }

    /** The defender's body was inside the strike's sweep, in sight, at the impact. */
    public boolean inSweep() {
        return inSweep;
    }

    /** The attacker was inside the defender's front guard arc at the impact. */
    public boolean inArc() {
        return inArc;
    }

    /** The defender could still guard at the impact: standing, guarding or in the radial. */
    public boolean stateOk() {
        return stateOk;
    }

    /** What became of its guard ({@link DeflectJudge#attemptOf}). */
    public DeflectJudge.Attempt attempt() {
        return attempt;
    }

    /** That attempt's press, judged time, or {@link DeflectJudge#NO_PRESS}. */
    public long pressMicros() {
        return pressMicros;
    }

    /** The defender's assist level. */
    public int assist() {
        return assist;
    }
}
