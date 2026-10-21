package dev.champ.kimetsu.core.machine;

import dev.champ.kimetsu.core.clock.Micros;

/**
 * One press or release, at its judged stamp (Rule 2.1: the stamp check's verdict, half a frame corrected). The mod
 * derives these from each input frame's full input state, so a stale release can't leave a key held.
 *
 * @param stampMicros the judged combat time of the press, in microseconds
 * @param slot for a Breathe release, the radial slot it was released toward, or −1 for none
 * @param fallBlocks for an attack press, how far above the ground the player was while falling (0 when not falling)
 * @param claimed for a guard press, whether the deflect judge claimed a strike with it when it arrived (a Feint it
 *     cancels is then settled by that strike's verdict, ruling Q8)
 */
public record Input(Kind kind, long stampMicros, int slot, double fallBlocks, boolean claimed) {

    public enum Kind {
        ATTACK,
        POMMEL,
        STEP,
        GUARD_DOWN,
        GUARD_UP,
        BREATHE_DOWN,
        BREATHE_UP,
        /** A tap of Breathe (shorter than the radial's hold) or of the separate Inhale key. */
        INHALE,
        JUMP,
        SNEAK
    }

    public static Input of(Kind kind, long stampMicros) {
        return new Input(kind, stampMicros, -1, 0, false);
    }

    public static Input release(long stampMicros, int slot) {
        return new Input(Kind.BREATHE_UP, stampMicros, slot, 0, false);
    }

    public static Input attackFalling(long stampMicros, double fallBlocks) {
        return new Input(Kind.ATTACK, stampMicros, -1, fallBlocks, false);
    }

    public static Input guard(long stampMicros, boolean claimed) {
        return new Input(Kind.GUARD_DOWN, stampMicros, -1, 0, claimed);
    }

    /** The engine step it acts in (Rule 2.1). */
    public long tick() {
        return Micros.stepOf(stampMicros);
    }

    /** A combat input: it restarts Rhythm's idle clock (Rule 4.3). Releases, Jump and Sneak aren't. */
    public boolean combat() {
        return switch (kind) {
            case ATTACK, POMMEL, STEP, GUARD_DOWN, BREATHE_DOWN, INHALE -> true;
            case GUARD_UP, BREATHE_UP, JUMP, SNEAK -> false;
        };
    }
}
