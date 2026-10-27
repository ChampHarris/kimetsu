package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.StampCheck;
import dev.champ.kimetsu.net.NetMessage;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The client's own presses as the server will judge them, handed to its radial ({@link RadialStroke}) and its own
 * moves ({@link OwnMoves}) in the server's order (D-112); kept free of game state, so the parity test drives exactly
 * what {@code ClientInput} does.
 *
 * <p>A press or release is judged at its stamp less half the frame time it goes out with ({@code StampCheck.press},
 * Rule 2.1), as an honest client's within the tolerance is. That frame time is the frame's ({@code ClientInput} measures
 * it a frame late), cut where half of it would judge the press before the floor: the latest moment the client already
 * acted on (a press's judged stamp, an aim's, an engine step's end it ran) or told the server its input reached (a sent
 * frame's stamp less half its frame time, {@code InputHorizon.frame}). So no press is ever judged before what its own
 * moves have run, nor before a strike the server decided on its frames. A press is physically later than the last poll,
 * so the cut only moves it toward when it was pressed. One at an exact time (frame time 0: a restamped release, the dev
 * hooks) can't be cut: stamped before what its moves have run (a dev hook fires at its due time, up to a frame late; a
 * restamped release can follow a tick's start), it acts in the next step they run, a step late, never in one ended.
 *
 * <p>Its moves run in engine steps, as the server's {@code Timeline} does: each step's presses by judged stamp, then
 * its end ({@code Combatant.advance}), where the radial opens or closes first and then what the moves held back runs.
 * A step's end runs when anything passes it: a press or an aim judged after it, a client tick's start standing after
 * it (the step its pose frame carries), a frame sent whose horizon is past it. So every step the server can settle has
 * run before its snapshot comes. A gap longer than {@link #CATCH_UP_STEPS} (the clock closed, no rules) jumps: what its
 * moves hold back is over by then.
 *
 * <p>The server opens the radial at any press stamped past its hold's edge ({@code Combatant.input}), so the radial is
 * brought up to a press's judged stamp before the press acts, so a guard press also finds it held back by an attack or
 * a Step pressed since. A guard holds it back from its press and frees it from its release, both counted at their
 * stamps; Breathe's press updates it at its own stamp.
 */
final class OwnPresses {
    /** Steps run one by one after a gap; past that it jumps. */
    static final int CATCH_UP_STEPS = 40;

    private static final long NONE = Long.MIN_VALUE;

    final OwnMoves moves = new OwnMoves();
    final RadialStroke radial = new RadialStroke();

    /** The parity test's: each engine step whose end its moves ran, as it runs. */
    LongConsumer stepped = s -> {};

    private long floor = NONE; // no press still to come is judged before it
    private long ran = NONE; // the last engine step whose end its moves ran
    private long releaseStep = NONE; // the step the last slotted release acted in

    /** The time the server judges a press stamped {@code stampMicros} in a frame of {@code frameTimeMicros} at. */
    static long judged(long stampMicros, long frameTimeMicros, TimingRules timing) {
        return stampMicros - StampCheck.halfFrame(timing, frameTimeMicros);
    }

    /** The earliest a press still to come is judged at, or {@code Long.MIN_VALUE} before anything. */
    long floor() {
        return floor;
    }

    /**
     * The frame time a press stamped {@code stampMicros}, polled in a frame of {@code frameTimeMicros}, goes out with:
     * that frame's, or the one whose half judges it at the floor if that's later; 0 stays 0. Changes nothing.
     */
    long frameTime(long stampMicros, long frameTimeMicros, TimingRules timing) {
        if (frameTimeMicros <= 0 || floor == NONE || judged(stampMicros, frameTimeMicros, timing) >= floor) {
            return frameTimeMicros;
        }
        return 2 * Math.max(0, stampMicros - floor);
    }

    /**
     * Whether the radial aims for a press or release stamped {@code stampMicros} in a frame of {@code frameTimeMicros}
     * (a render frame's own moment too), judged no earlier than the floor; {@code shown} is the last snapshot and
     * {@code inCombat} the last in combat (null out of it: a stale one, after a death or a respawn). The moves hear the
     * answer: while it aims, their presses wait in the buffer.
     */
    boolean aim(
            long stampMicros,
            long frameTimeMicros,
            OwnMoves.Rules r,
            NetMessage.@Nullable Snapshot shown,
            NetMessage.@Nullable Snapshot inCombat) {
        long at = judged(stampMicros, frameTimeMicros, r.rules().timing());
        if (floor != NONE) {
            at = Math.max(at, floor);
        }
        runBefore(at, r, shown, inCombat);
        raise(at);
        return see(at, ran, r, inCombat);
    }

    /** A client tick starts, its pose frame carrying engine step {@code step}: every step through it has ended. */
    void tick(
            long step, OwnMoves.Rules r, NetMessage.@Nullable Snapshot shown, NetMessage.@Nullable Snapshot inCombat) {
        runThrough(step, r, shown, inCombat);
    }

    /**
     * A frame went at {@code sendStampMicros} with frame time {@code frameTimeMicros}: the server's input horizon is its
     * stamp less half that ({@code InputHorizon.frame}), so no press still to come is judged at or before it.
     */
    void sent(
            long sendStampMicros,
            long frameTimeMicros,
            OwnMoves.Rules r,
            NetMessage.@Nullable Snapshot shown,
            NetMessage.@Nullable Snapshot inCombat) {
        long horizon = judged(sendStampMicros, frameTimeMicros, r.rules().timing());
        runThrough(Math.floorDiv(horizon, Micros.PER_TICK), r, shown, inCombat); // the steps ended by then
        raise(horizon + 1);
    }

    /**
     * Key {@code k} pressed ({@code down}) or let go, stamped {@code stampMicros}, polled in a frame of
     * {@code frameTimeMicros}; a Breathe release carries the slot its stroke chose ({@code slot}, −1: none). It acts in
     * the engine step it's judged into, the steps before run first. {@code shown} and {@code inCombat} as {@link #aim}.
     * Returns the frame time it goes out with ({@link #frameTime}).
     */
    long key(
            PlayerIntake.Key k,
            boolean down,
            long stampMicros,
            long frameTimeMicros,
            int slot,
            OwnMoves.Rules r,
            NetMessage.@Nullable Snapshot shown,
            NetMessage.@Nullable Snapshot inCombat) {
        long f = frameTime(stampMicros, frameTimeMicros, r.rules().timing());
        long at = judged(stampMicros, f, r.rules().timing());
        runBefore(at, r, shown, inCombat);
        long tick = Math.max(Micros.stepOf(at), ran + 1); // an exact time before the floor: the next step to run
        long last = tick - 1;
        raise(at);
        switch (k) {
            case GUARD -> {
                if (down) {
                    see(at, last, r, inCombat); // the server may open the radial at it first: it cancels it
                    radial.guardPressed(at, hold(r));
                    moves.guardDown(tick, at, shown, r);
                } else {
                    moves.guardUp();
                }
                see(at, last, r, inCombat); // held back from its press, free from its release
            }
            case ATTACK, POMMEL, STEP, INHALE -> {
                if (!down) {
                    return f;
                }
                see(at, last, r, inCombat); // the server opens the radial first, at this stamp
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
                    see(at, last, r, inCombat); // what holds it back at its press
                } else {
                    radial.cancel(); // its slot, if any, was taken from the stroke before
                    moves.release(slot >= 0 ? r.moves().radial(slot) : null, tick, at, shown, r);
                    if (slot >= 0) {
                        releaseStep = tick;
                    }
                }
            }
            default -> {} // Jump and Sneak: a radial they open on the server opens in its edge's step all the same
        }
        return f;
    }

    /** The last slotted release never went (the lost focus restamped it): the form it started didn't either. */
    void withdraw() {
        if (releaseStep != NONE) {
            moves.withdraw(releaseStep);
        }
    }

    /** A new timeline ({@code ClockEpoch}): its steps and stamps start again behind the old one's. */
    void epoch() {
        floor = NONE;
        ran = NONE;
        releaseStep = NONE;
        moves.epoch();
    }

    /** Runs every step whose end is before {@code atMicros}. */
    private void runBefore(
            long atMicros,
            OwnMoves.Rules r,
            NetMessage.@Nullable Snapshot shown,
            NetMessage.@Nullable Snapshot inCombat) {
        runThrough(Micros.stepOf(atMicros) - 1, r, shown, inCombat);
    }

    /** Runs every step's end through {@code step}, as the server's {@code advance} does, in order. */
    private void runThrough(
            long step, OwnMoves.Rules r, NetMessage.@Nullable Snapshot shown, NetMessage.@Nullable Snapshot inCombat) {
        if (ran == NONE || step - ran > CATCH_UP_STEPS) {
            ran = Math.max(ran, step - 1); // the first, or after a gap: from here
        }
        while (ran < step) {
            long s = ++ran;
            boolean aiming = radial.stepEnded(
                    s,
                    hold(r),
                    limit(r),
                    moves.blocked(s),
                    inCombat != null ? inCombat.state() : null,
                    inCombat != null ? Micros.ofTicks(inCombat.settledTick()) : Long.MIN_VALUE);
            moves.radial(aiming, inCombat);
            moves.tick(s, Micros.ofTicks(s), shown, r);
            stepped.accept(s);
        }
        if (ran != NONE) {
            raise(Micros.ofTicks(ran) + 1);
        }
    }

    private void raise(long micros) {
        floor = floor == NONE ? micros : Math.max(floor, micros);
    }

    /** The radial at judged {@code atMicros}, engine step {@code last} the last whose end its moves ran. */
    private boolean see(long atMicros, long last, OwnMoves.Rules r, NetMessage.@Nullable Snapshot inCombat) {
        boolean aiming = radial.update(
                atMicros,
                hold(r),
                limit(r),
                moves.blocked(last),
                inCombat != null ? inCombat.state() : null,
                inCombat != null ? Micros.ofTicks(inCombat.settledTick()) : Long.MIN_VALUE);
        moves.radial(aiming, inCombat);
        return aiming;
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
