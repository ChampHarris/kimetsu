package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.fight.PlayerIntake;

/**
 * The guard toggle (the CLIENT config's {@code guard_mode}, Champ's call on D-104's Open Question 1), kept free of game
 * state so its cases are unit-tested. In TOGGLE each tap of the guard key is a guard press, a deflect attempt judged as
 * a holder's, and the guard stays up between taps. It drops at the next Attack, Pommel, Step or Breathe press (the
 * Inhale key is Breathe's tap on a key of its own), or at a double-tap while it's up: a tap within
 * {@code machine.double_tap_ticks} client ticks of a tap on the raised guard is a press like any, and the guard then
 * follows its key, down at its key-up. The tap that raises the guard starts no pair, so raising it as a cue shows and
 * tapping at the impact keeps it up (D-120). A screen, the pause, lost focus or the blade leaving the hand drop it as
 * they let go of any held key.
 *
 * <p>It sends what a holder's keys would, in their order, so the server sees no toggle: a tap while the guard is up is
 * a release and then a press at the tap's stamp (a holder's quickest re-press: the guard dips for
 * {@code guard_hold_ticks}, as a holder's does, and blocks nothing a holder's couldn't), and a drop is a release at
 * the action's stamp, sent before it, so it acts first ({@code Timeline}: by stamp, then arrival). Whether the guard is
 * up is the caller's to say: the held state its frames carry.
 */
final class GuardToggle {
    /** Where the guard presses and releases go, in the order they're sent, each at the stamp of what made it. */
    interface Keys {
        void guard(boolean down);
    }

    private static final long NONE = Long.MIN_VALUE;

    private long lastTap = NONE; // the client tick of the last tap on the raised guard, or none
    private boolean followsKey; // the pair's second tap is down: its key-up drops the guard

    /**
     * The guard key pressed at client tick {@code tick}, the guard {@code raised} or not: a press, or with the guard up
     * a release and then a press. The pair's second tap ({@code doubleTapTicks} or fewer after a tap on the raised
     * guard; negative: no pair) leaves the guard to its key, which lets it go, so a pair is used up.
     */
    void keyDown(boolean raised, long tick, int doubleTapTicks, Keys out) {
        if (!raised) {
            lastTap = NONE; // the raising tap starts no pair
            followsKey = false;
            out.guard(true);
            return;
        }
        followsKey = lastTap != NONE && tick - lastTap <= doubleTapTicks;
        lastTap = tick;
        out.guard(false); // a holder's let-go before the key goes down again
        out.guard(true);
    }

    /** The guard key let go, the guard {@code raised} or not: a release only for the pair's second tap. */
    void keyUp(boolean raised, Keys out) {
        boolean drops = raised && followsKey;
        followsKey = false;
        if (drops) {
            out.guard(false);
        }
    }

    /** Key {@code k} pressed, the guard {@code raised} or not: a release first when the press drops it. */
    static void pressed(PlayerIntake.Key k, boolean raised, Keys out) {
        if (raised && drops(k)) {
            out.guard(false);
        }
    }

    private static boolean drops(PlayerIntake.Key k) {
        return switch (k) {
            case ATTACK, POMMEL, STEP, BREATHE, INHALE -> true;
            case GUARD, JUMP, SNEAK -> false;
        };
    }
}
