package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.StampCheck;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.InputBuffer;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link OwnMoves} against the server's own machine (plan Risk 8): random presses of Attack, Pommel, Step, guard, Breathe
 * and the Inhale key go through a core {@link Timeline}, stepped as a player's session steps it (a tick's presses, then
 * its timers and meters; a Breathe tap adds its Inhale at its press), each judged as the server judges an honest
 * client's (its stamp less half its frame's time, {@code StampCheck.press}), and through {@link OwnPresses}, driven as
 * {@code ClientInput} drives it: raw stamps and the frame time each went with, the radial aimed at each render frame's
 * start, at each client tick's start and at a Breathe release, and brought up to a press's stamp only by
 * {@code OwnPresses} itself. It sees the snapshots the server sends: after each step, of the newest tick no press still
 * to come can change ({@code PlayerSession.afterStep}). Each seed runs at one frame rate, from 144 fps to 8 fps (past
 * the 100 ms the half-frame correction trusts), and at assist 0, 1 or 2. There's no link delay, so only the rules can
 * differ. Tick by tick, the attack each runs, each Step's start and whether the radial is open must agree. A slotted
 * release goes only while the client aims, as from a real client. Half the seeds press on the beat, so the Light Chain
 * continues and Rhythm reaches Mushin; the rest press anywhere, so Breath runs out and winds.
 *
 * <p>Events run in the order of their judged stamps, client tick t's start at engine step t's end: a client whose ticks
 * start half a frame after the step boundaries. A real client's tick phase is arbitrary, so a press polled early in a
 * tick can be judged into the step before, while OwnMoves runs every press in the tick after the one it was polled in
 * (step 5's prediction reconciles the two).
 *
 * <p>Left out, each where OwnMoves can't know or a session's own path decides it, and where it's covered instead:
 * <ul>
 *   <li>the held guard's Breath drain: OwnMoves runs the snapshot's Breath on at the rate it was settled with, which a
 *       guard raised or lowered since changes (step 5);
 *   <li>a Step pressed while Breathe is down short of the radial's hold: whether the server can pay for it depends on
 *       the Inhale a tap would add at that press, which only the release tells (D-109's found item, for Champ);
 *   <li>{@code withdraw}: a slotted release the lost focus restamps reaches the server as a later release with no slot
 *       ({@code FocusRelease}, the session's restamp); {@code OwnMovesTest}'s "a slotted release that never went";
 *   <li>{@code epoch}: a new timeline restarts the server's steps and the stamp check ({@code ClockEpoch}, Rule 1.8);
 *       {@code OwnMovesTest}'s "a new timeline forgets its spends";
 *   <li>{@code reset}: a level change gives the server a fresh fighter out of combat (D-068); {@code OwnMovesTest}'s
 *       "a level change forgets its moves".
 * </ul>
 */
class OwnMovesParityTest {
    private static final Moveset WATER = Fixtures.water();
    private static final CombatRules RULES = new CombatRules(
            Fixtures.timing(),
            Fixtures.clock(),
            Fixtures.machine(),
            Fixtures.hits(),
            Fixtures.strikes(),
            Fixtures.rhythm(),
            Fixtures.fights(),
            Fixtures.movement());
    private static final BreathRules BREATH = withoutGuardDrain(Fixtures.breath());
    private static final int TICKS = 1_200; // a minute of fighting
    private static final long HOLD = Micros.ofMillis(RULES.machine().radialHoldMs());
    private static final double FLICK = RULES.machine().radialFlickDeg();
    private static final int SLOTS = WATER.style().radial().size();
    // 144, 60, 30, 20 and 8 fps, the last past the frame time the correction trusts (100 ms)
    private static final long[] FRAMES = {6_944, 16_667, 33_333, 50_000, 125_000};

    private static BreathRules withoutGuardDrain(BreathRules b) {
        return new BreathRules(
                b.max(),
                b.regenIdlePerS(),
                b.regenCombatPerS(),
                b.onBeatInhale(),
                0,
                b.windedTicks(),
                b.windedGuardPosturePerS(),
                b.recoveryRefundShare());
    }

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 1_000);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void theClientsOwnMovesAreTheServers(long seed) {
        new Run(seed).run();
    }

    private static final class Run {
        private final SplittableRandom random;
        private final boolean onBeat;
        private final int assist;
        private final long frameMicros;
        private final long half; // the server's correction for that frame time
        private final OwnMoves.Rules r;
        private final Timeline server;
        private final OwnPresses client = new OwnPresses();
        private NetMessage.@Nullable Snapshot shown;
        private long ticked; // the client tick last started
        private long nextFrame; // the next render frame's start, raw
        private boolean guard;
        private boolean breathe;
        private long breatheAt; // its judged stamp
        private long clientStep = OwnMoves.NONE; // the Step the client started in this tick
        private final ArrayDeque<String> trace = new ArrayDeque<>(); // the last presses and ticks, for a failure

        Run(long seed) {
            random = new SplittableRandom(seed);
            onBeat = seed % 2 == 0;
            assist = (int) (seed / 2 % 3);
            frameMicros = FRAMES[(int) (seed / 6 % FRAMES.length)];
            half = StampCheck.halfFrame(RULES.timing(), frameMicros);
            r = OwnMoves.Rules.of(WATER, RULES, BREATH, assist);
            Combatant start = new Combatant(
                    RULES.machine(),
                    RULES.timing(),
                    BREATH,
                    Fixtures.playerPosture(),
                    RULES.rhythm(),
                    WATER,
                    new PulseSchedule(WATER.style().beatTicks()),
                    0);
            start.effect(new Effect.AssistChanged(assist), 0); // its level from its first step, as PlayerSession.join
            server = new Timeline(start, 1);
            nextFrame = 1 + random.nextLong(frameMicros);
        }

        void run() {
            for (long t = 1; t <= TICKS; t++) {
                clientStep = OwnMoves.NONE;
                for (long judged : stamps(t)) {
                    framesThrough(judged);
                    press(t, judged);
                }
                framesThrough(Micros.ofTicks(t) - 1);
                // the client tick's start, at the step's end: the radial, then the buffer (ClientInput.ownTick)
                client.tick(t, t, r, shown, shown);
                ticked = t;
                took();
                server.beginTick(t);
                server.endTick(t);
                check(t);
                NetMessage.Snapshot s = snapshot(Micros.ofTicks(t));
                if (s != null) {
                    shown = s;
                }
            }
        }

        /** The render frames that start by judged time {@code judged}: each aims the radial (ClientInput.frameStart). */
        private void framesThrough(long judged) {
            while (nextFrame - half <= judged) {
                client.aim(nextFrame, frameMicros, ticked, r, shown);
                nextFrame += frameMicros;
            }
        }

        /** The judged stamps of this tick's presses, in order: none, one or two, inside the tick's 50 ms (they act in it). */
        private List<Long> stamps(long t) {
            List<Long> out = new ArrayList<>();
            if (onBeat) {
                if (t % WATER.style().beatTicks() == 0 && random.nextInt(3) > 0) {
                    out.add(Micros.ofTicks(t)); // on the beat, its tick's last moment
                } else if (random.nextInt(10) == 0) {
                    out.add(Micros.ofTicks(t - 1) + 1 + random.nextLong(Micros.PER_TICK - 1));
                }
                return out;
            }
            int n = random.nextInt(8) == 0 ? 2 : random.nextInt(3) == 0 ? 1 : 0;
            for (int i = 0; i < n; i++) {
                out.add(Micros.ofTicks(t - 1) + 1 + random.nextLong(Micros.PER_TICK - 1));
            }
            out.sort(null);
            return out;
        }

        /** A press the server judges at {@code judged}: the client stamped it half a frame later. */
        private void press(long t, long judged) {
            long stamp = judged + half;
            int k = random.nextInt(100);
            note("  " + t + " @" + (judged - Micros.ofTicks(t - 1)) / 1000.0 + "ms: " + kind(k));
            if (k < 30) {
                server.receive(Input.of(Input.Kind.ATTACK, judged));
                key(PlayerIntake.Key.ATTACK, true, t, stamp, -1);
            } else if (k < 38) {
                server.receive(Input.of(Input.Kind.POMMEL, judged));
                key(PlayerIntake.Key.POMMEL, true, t, stamp, -1);
            } else if (k < 55) {
                if (breathe && judged - breatheAt < HOLD) {
                    return; // it may yet be a tap (the class doc)
                }
                server.receive(Input.of(Input.Kind.STEP, judged));
                key(PlayerIntake.Key.STEP, true, t, stamp, -1);
                took();
            } else if (k < 72) {
                guard = !guard;
                server.receive(guard ? Input.guard(judged) : Input.of(Input.Kind.GUARD_UP, judged));
                key(PlayerIntake.Key.GUARD, guard, t, stamp, -1);
            } else if (k < 80) {
                server.receive(Input.of(Input.Kind.INHALE, judged));
                key(PlayerIntake.Key.INHALE, true, t, stamp, -1);
            } else {
                breathe = !breathe;
                if (breathe) {
                    breatheAt = judged;
                    server.receive(Input.of(Input.Kind.BREATHE_DOWN, judged));
                    key(PlayerIntake.Key.BREATHE, true, t, stamp, -1);
                } else {
                    // a real key-up (ClientInput.slotAtRelease): the radial aimed at its stamp, then the stroke's
                    // slot, which a flick toward a slot (or none) gets only while it aims
                    client.aim(stamp, frameMicros, ticked, r, shown);
                    int toward = random.nextInt(SLOTS + 1) - 1;
                    double a = Math.toRadians(toward * 360.0 / SLOTS);
                    double deg = toward >= 0 ? 3 * FLICK : 0;
                    int slot = client.radial.release(deg * Math.sin(a), -deg * Math.cos(a), SLOTS, FLICK);
                    server.receive(Input.release(judged, slot));
                    key(PlayerIntake.Key.BREATHE, false, t, stamp, slot);
                }
            }
        }

        private void key(PlayerIntake.Key k, boolean down, long t, long stamp, int slot) {
            client.key(k, down, t, stamp, frameMicros, slot, r, shown, shown);
        }

        private void took() {
            long s = client.moves.takeStep();
            if (s != OwnMoves.NONE) {
                clientStep = s;
            }
        }

        private void check(long t) {
            Combatant c = server.live();
            FrameData a = c.state() == State.ATTACK ? c.attack() : null;
            FrameData mine = client.moves.attackAt(t);
            note(t + " server " + c.state() + (a != null ? " " + a.id() + " from " + c.attackStart() : "")
                    + " Breath " + c.breath().at(t) / (double) Fixed.ONE
                    + " buffer" + (c.buffer().get(InputBuffer.Slot.STEP) != null ? " S" : "")
                    + (c.buffer().get(InputBuffer.Slot.POMMEL) != null ? " P" : "")
                    + (c.buffer().get(InputBuffer.Slot.ATTACK) != null ? " A" : "")
                    + (c.guardDown() ? ", guard" : "")
                    + " | client " + (mine != null ? mine.id() : "-") + (clientStep != OwnMoves.NONE ? " Step" : "")
                    + (client.radial.aiming() ? " aiming" : "") + (client.moves.blocked(t) ? " blocked" : ""));
            assertEquals(a != null ? a.id() : "none", mine != null ? mine.id() : "none", what("the attack", t));
            boolean serverStep = c.state() == State.STEP
                    && c.stateUntil() == t + WATER.step().total();
            assertEquals(serverStep ? t : OwnMoves.NONE, clientStep, what("a Step's start", t));
            assertEquals(c.state() == State.RADIAL, client.radial.aiming(), what("the radial open", t));
        }

        /**
         * The snapshot the server makes after a step, every press up to {@code horizonMicros} in (PlayerSession): of the
         * newest tick no press still to come can change, so not past a Breathe press its release may make a tap.
         */
        private NetMessage.@Nullable Snapshot snapshot(long horizonMicros) {
            long tick = server.settledThrough(horizonMicros, Long.MAX_VALUE);
            if (tick == Long.MIN_VALUE) {
                return null;
            }
            Combatant c = server.endAt(tick);
            FrameData a = c.attack();
            return new NetMessage.Snapshot(
                    tick,
                    0,
                    c.state(),
                    a != null ? a.id() : "",
                    c.breath().segment(),
                    c.breath().grey(),
                    c.breath().winded(tick),
                    c.posture().segment(),
                    c.rhythm().points(),
                    c.rhythm().tier(),
                    c.combatUntil());
        }

        private String kind(int k) {
            if (k < 30) {
                return "Attack";
            }
            if (k < 38) {
                return "Pommel";
            }
            if (k < 55) {
                return "Step";
            }
            if (k < 72) {
                return guard ? "guard up" : "guard down";
            }
            return k < 80 ? "Inhale" : breathe ? "Breathe up" : "Breathe down";
        }

        private void note(String line) {
            if (trace.size() == 40) {
                trace.removeFirst();
            }
            trace.addLast(line);
        }

        private Supplier<String> what(String check, long t) {
            return () -> check + " at seed tick " + t + (onBeat ? " (on the beat)" : "") + ", assist " + assist + ", "
                    + frameMicros / 1000.0 + " ms frames\n" + String.join("\n", trace);
        }
    }
}
