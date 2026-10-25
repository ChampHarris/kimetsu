package dev.champ.kimetsu.client;

/**
 * The Left Alt fix (5b's known bug, D-070), kept free of game state so its cases are unit-tested. Alt-tabbing presses
 * Left Alt, Breathe's key, and the window loses its focus before the key comes up, so the release would land under
 * {@code machine.radial_hold_ms} after the press: an Inhale nobody meant. A Breathe release on the lost-focus edge (the
 * window was active at the last tick's start and isn't now) is stamped no earlier than its press plus
 * {@code radial_hold_ms}, and goes with no radial slot: a hold, so the radial opens and closes with nothing fired
 * ({@code Combatant.breatheUp}). It holds whichever comes first, SDL's key-up or the focus flag, and for the release the
 * tick's start forces when the focus goes. A window never focused (MCTest's) has no edge.
 */
final class FocusRelease {
    private static final long NONE = Long.MIN_VALUE;

    /** What a Breathe key-up the mod took does. */
    enum Up {
        /** It goes as it came. */
        TAKE,
        /** On the edge: it's restamped past the hold, and goes once that time has passed. */
        DEFER,
        /** A restamped release is still waiting: that one goes, and this one with it (code review). */
        DROP
    }

    private boolean wasActive; // the window's focus at the last tick's start: not until it's first seen active

    /**
     * A Breathe key-up between ticks, the window {@code active} now and a restamped release {@code deferred} or not:
     * once one waits, a later key-up (the tick's start deferred it while the key was still down) must not go out early
     * as a tap, an Inhale nobody meant, and then the waiting one as a second release.
     */
    Up breatheUp(boolean active, boolean deferred) {
        if (deferred) {
            return Up.DROP;
        }
        return losing(active) ? Up.DEFER : Up.TAKE;
    }

    /** The tick's start, the window {@code active} now: whether this is the lost-focus edge. */
    boolean tick(boolean active) {
        boolean edge = wasActive && !active;
        wasActive = active;
        return edge;
    }

    /** Between ticks: whether the focus went since the last tick's start (a key-up after the flag flipped). */
    boolean losing(boolean active) {
        return wasActive && !active;
    }

    /**
     * The stamp of a Breathe release at {@code releaseMicros} on the edge, its press at {@code pressMicros}
     * ({@code Long.MIN_VALUE}: unknown): no earlier than the press plus {@code holdMicros}.
     */
    static long restamp(long releaseMicros, long pressMicros, long holdMicros) {
        return pressMicros == NONE ? releaseMicros : Math.max(releaseMicros, pressMicros + holdMicros);
    }
}
