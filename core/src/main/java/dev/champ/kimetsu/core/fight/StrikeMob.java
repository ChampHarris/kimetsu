package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.hit.Pose;

/**
 * A mob that strikes players, as the fight loop sees its body: where it stands, how big it is, and what the strikes'
 * answers and the players' swings do to it. Slice 4's demon runs its own state machine behind this; tests use a box
 * dummy that records what happened to it.
 */
public interface StrikeMob {
    int id();

    double halfWidth();

    double height();

    double eyeHeight();

    double maxPosture();

    /**
     * Where it stood at tick {@code tick} (step 1 of that tick: after the level moved it): its feet and look, into
     * {@code out}. From a strike's cue it moves only by the strike's own displacement (Rule 7.13), so while
     * {@link CombatWorld#strikePose} has a pose for it, that's where it is: the strike's sweep, a Pommel's target and the
     * box players' swings hit must agree, on the server and on every client.
     */
    void pose(long tick, Pose out);

    /**
     * A player's swing landed on it at {@code tick} (a late sweep keeps its tick, so a stun ends when it would have):
     * {@code damage} and {@code posture} as {@code HitMath} reckons them. Slice 4 decides blocks, clangs and the neck.
     */
    void hit(long tick, int fighter, FrameData attack, int window, double damage, double posture);

    /**
     * Its strike {@code strike} was answered at its impact, tick {@code tick}: the best answer's Posture it loses then
     * and ticks added to its recovery.
     */
    void paid(long strike, long tick, double posture, int recoveryTicks);

    /**
     * A Pommel Strike interrupted its strike at {@code atMicros}: the strike won't land, and it's stunned for
     * {@code stunTicks} from then (Rule 5.2). The rest of the string is {@linkplain #cancelled cancelled}.
     */
    void interrupted(long strike, long atMicros, int stunTicks);

    /** Its strike won't land: the rest of an interrupted string, a Posture break, a {@code ClockEpoch}. */
    void cancelled(long strike);

    /**
     * The tick its Posture last broke (STAGGER), or {@code Long.MIN_VALUE}: a break cancels its strikes landing after
     * that tick, not those already landed (Rule 7.8's trades; a late hit's break keeps the tick it happened at).
     */
    long brokenAt();

    /** The tick is over (step 7). */
    void endTick(long tick);
}
