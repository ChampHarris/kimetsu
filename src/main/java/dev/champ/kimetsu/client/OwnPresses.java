package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.StampCheck;
import dev.champ.kimetsu.net.NetMessage;
import org.jspecify.annotations.Nullable;

/**
 * The client's own presses as the server will judge them, handed to its radial ({@link RadialStroke}) and its own
 * moves ({@link OwnMoves}) in the server's order; kept free of game state, so the parity test drives exactly what
 * {@code ClientInput} does (D-110's review).
 *
 * <p>A press or release is judged at its stamp less half the frame time it goes out with ({@code StampCheck.press},
 * Rule 2.1), as an honest client's within the tolerance is; every time the radial and the moves see is a judged one.
 * The server opens the radial at any press stamped past its hold's edge ({@code Combatant.input}), so the radial is
 * brought up to a press's judged stamp before the press acts, though no frame has seen the edge yet, so a guard press
 * also finds it held back by an attack or a Step pressed since the last update. A guard holds it back from its press
 * and frees it from its release, both counted at their stamps, since a guard tapped between two updates would
 * otherwise go unseen; an attack or a Step holds it back past the next tick's start, which updates it anyway. Breathe's
 * press updates it at its own stamp, since after a hitch its edge may pass before any other update.
 * A client tick's start stands at an engine step's end
 * ({@code Combatant.advance}): the radial opens or closes there first, then what the moves held back runs.
 */
final class OwnPresses {
    final OwnMoves moves = new OwnMoves();
    final RadialStroke radial = new RadialStroke();

    /** The time the server judges a press stamped {@code stampMicros} in a frame of {@code frameTimeMicros} at. */
    static long judged(long stampMicros, long frameTimeMicros, TimingRules timing) {
        return stampMicros - StampCheck.halfFrame(timing, frameTimeMicros);
    }

    /**
     * Whether the radial aims for a press or release stamped {@code stampMicros} in a frame of {@code frameTimeMicros}
     * (a render frame's own moment too), client tick {@code tick} the last started; {@code inCombat} is the last
     * snapshot in combat (null out of it: a stale one, after a death or a respawn). The moves hear the answer: while it
     * aims, their presses wait in the buffer.
     */
    boolean aim(
            long stampMicros,
            long frameTimeMicros,
            long tick,
            OwnMoves.Rules r,
            NetMessage.@Nullable Snapshot inCombat) {
        return see(judged(stampMicros, frameTimeMicros, r.rules().timing()), tick, r, inCombat);
    }

    /**
     * Client tick {@code tick} starts, at the end of engine step {@code step}: the radial opens or closes at the step's
     * end, then the moves' tick runs what they held back (what that starts holds it back past the next tick's start).
     */
    void tick(
            long tick,
            long step,
            OwnMoves.Rules r,
            NetMessage.@Nullable Snapshot shown,
            NetMessage.@Nullable Snapshot inCombat) {
        stepEnded(step, tick, r, inCombat);
        moves.tick(tick, Micros.ofTicks(step), shown, r);
    }

    /**
     * Key {@code k} pressed ({@code down}) or let go, stamped {@code stampMicros} in a frame of {@code frameTimeMicros},
     * acting in client tick {@code tick} (the one after the tick it was polled in); a Breathe release carries the slot
     * its stroke chose ({@code slot}, −1: none). {@code shown} is the last snapshot, {@code inCombat} as {@link #aim}.
     */
    void key(
            PlayerIntake.Key k,
            boolean down,
            long tick,
            long stampMicros,
            long frameTimeMicros,
            int slot,
            OwnMoves.Rules r,
            NetMessage.@Nullable Snapshot shown,
            NetMessage.@Nullable Snapshot inCombat) {
        long at = judged(stampMicros, frameTimeMicros, r.rules().timing());
        long polled = tick - 1;
        switch (k) {
            case GUARD -> {
                if (down) {
                    see(at, polled, r, inCombat); // the server may open the radial at it first: it cancels it
                    radial.guardPressed(at, hold(r));
                    moves.guardDown(tick, at, shown, r);
                } else {
                    moves.guardUp();
                }
                see(at, polled, r, inCombat); // held back from its press, free from its release
            }
            case ATTACK, POMMEL, STEP, INHALE -> {
                if (!down) {
                    return;
                }
                see(at, polled, r, inCombat); // the server opens the radial first, at this stamp
                switch (k) {
                    case ATTACK -> moves.attack(tick, at, r);
                    case POMMEL -> moves.pommel(tick, r);
                    case STEP -> moves.step(tick, at, shown, r);
                    default -> moves.inhale(tick, at, r);
                }
            }
            case BREATHE -> {
                if (down) {
                    radial.press(at);
                    moves.breathe(tick, at, r);
                    see(at, polled, r, inCombat); // what holds it back at its press
                } else {
                    radial.cancel(); // its slot, if any, was taken from the stroke before
                    moves.release(slot >= 0 ? r.moves().radial(slot) : null, tick, at, shown, r);
                }
            }
            default -> {} // Jump and Sneak: a radial they open on the server opens in its edge's step all the same
        }
    }

    /** The radial at judged {@code atMicros}, client tick {@code tick} the last started. */
    private boolean see(long atMicros, long tick, OwnMoves.Rules r, NetMessage.@Nullable Snapshot inCombat) {
        boolean aiming = radial.update(
                atMicros,
                hold(r),
                limit(r),
                moves.blocked(tick),
                inCombat != null ? inCombat.state() : null,
                inCombat != null ? Micros.ofTicks(inCombat.settledTick()) : Long.MIN_VALUE);
        moves.radial(aiming, inCombat);
        return aiming;
    }

    private void stepEnded(long step, long tick, OwnMoves.Rules r, NetMessage.@Nullable Snapshot inCombat) {
        boolean aiming = radial.stepEnded(
                step,
                hold(r),
                limit(r),
                moves.blocked(tick),
                inCombat != null ? inCombat.state() : null,
                inCombat != null ? Micros.ofTicks(inCombat.settledTick()) : Long.MIN_VALUE);
        moves.radial(aiming, inCombat);
    }

    private static long hold(OwnMoves.Rules r) {
        return Micros.ofMillis(r.rules().machine().radialHoldMs());
    }

    /** The radial's limit, two beats of the style's (none on assist, D-036). */
    private static long limit(OwnMoves.Rules r) {
        MachineRules m = r.rules().machine();
        return r.assist() >= 1
                ? RadialStroke.NO_LIMIT
                : Micros.ofTicks((long) m.radialLimitBeats() * r.moves().style().beatTicks());
    }
}
