package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * {@link OwnPresses} and {@link OwnMoves} against the server's own machine (plan Risk 8, D-112): random presses of
 * Attack, Pommel, Step, guard, Breathe and the Inhale key go through a core {@link Timeline}, stepped as a player's
 * session steps it (a step's presses by judged stamp, then its timers and meters; a Breathe tap adds its Inhale at its
 * press), each judged as the server judges an honest client's (its stamp less half the frame time it goes out with,
 * {@code StampCheck.press}), and through {@code OwnPresses}, driven as {@code ClientInput} drives it. Step by engine
 * step, the attack each runs, each Step's start and whether the radial is open must agree. It sees the snapshots the
 * server sends: of the newest tick no press still to come can change ({@code PlayerSession.afterStep}). There's no
 * link delay, so only the rules and their order can differ. A slotted release goes only while the client aims, as from
 * a real client. Half the seeds press on the beat, so the Light Chain continues and Rhythm reaches Mushin; the rest
 * press anywhere, so Breath runs out and winds. Assist 0, 1 or 2.
 *
 * <p>Two drivers. {@link #theClientsOwnMovesAreTheServers}: events in the order of their judged stamps, one frame time a
 * seed (144 fps to 8, the last past the 100 ms the half-frame correction trusts), the radial aimed at each render
 * frame's start, at each client tick's start (engine step t's end) and at a Breathe release, so no press ever needs its
 * frame time cut. {@link #throughVanillasLoop}: vanilla's own loop ({@code Minecraft.runTick}: the poll, then up to ten
 * client ticks that are due, then the turn and {@code RenderFrameEvent.Pre}), each press carrying the frame time
 * {@code ClientInput} measured at the last frame's start (a frame old, so a hitch makes it the next frame's), hitches or
 * none, the client's ticks at half a frame past the step boundaries or at a random phase, inside its frames or between
 * them, and the frames {@code ClientInput} sends ({@code OwnPresses.sent}).
 *
 * <p>Left out, each where OwnMoves can't know or a session's own path decides it, and where it's covered instead:
 * <ul>
 *   <li>the held guard's Breath drain: OwnMoves runs the snapshot's Breath on at the rate it was settled with, which a
 *       guard raised or lowered since changes (step 5);
 *   <li>a Step pressed while Breathe is down short of the radial's hold: whether the server can pay for it depends on
 *       the Inhale a tap would add at that press, which only the release tells (D-109's found item, for Champ);
 *   <li>an opponent: no hit, grab or clash for a snapshot to show (the backstop and the hit rows of
 *       {@code RadialStrokeTest});
 *   <li>Jump and Sneak, and presses at an exact time (the restamped release, the dev hooks): stamped now or later, so
 *       never before what the client already ran ({@code OwnPresses}' class doc);
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
    private static final long HALF_MAX = StampCheck.halfFrame(RULES.timing(), Long.MAX_VALUE);

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

    @ParameterizedTest(name = "loop seed {0}")
    @MethodSource("seeds")
    void throughVanillasLoop(long seed) {
        new Loop(seed).run();
    }

    /** The server's state at each engine step, for {@link Loop} to compare once the client has run the step too. */
    private static final class Steps {
        final String[] attack = new String[TICKS + 64];
        final boolean[] step = new boolean[TICKS + 64];
        final boolean[] radial = new boolean[TICKS + 64];
    }

    /** The snapshot of {@code server}'s tick {@code tick}, as {@code PlayerSession} sends it. */
    private static NetMessage.Snapshot snapshotAt(Timeline server, long tick) {
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

    private static Timeline serverAt(int assist) {
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
        return new Timeline(start, 1);
    }

    private static String kind(int k, boolean guard, boolean breathe) {
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

    /** Events in the order of their judged stamps, client tick t's start at engine step t's end. */
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
            server = serverAt(assist);
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
                client.tick(t, r, shown, shown);
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
                client.aim(nextFrame, frameMicros, r, shown, shown);
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
            note("  " + t + " @" + (judged - Micros.ofTicks(t - 1)) / 1000.0 + "ms: " + kind(k, guard, breathe));
            if (k < 30) {
                server.receive(Input.of(Input.Kind.ATTACK, judged));
                key(PlayerIntake.Key.ATTACK, true, stamp, -1);
            } else if (k < 38) {
                server.receive(Input.of(Input.Kind.POMMEL, judged));
                key(PlayerIntake.Key.POMMEL, true, stamp, -1);
            } else if (k < 55) {
                if (breathe && judged - breatheAt < HOLD) {
                    return; // it may yet be a tap (the class doc)
                }
                server.receive(Input.of(Input.Kind.STEP, judged));
                key(PlayerIntake.Key.STEP, true, stamp, -1);
                took();
            } else if (k < 72) {
                guard = !guard;
                server.receive(guard ? Input.guard(judged) : Input.of(Input.Kind.GUARD_UP, judged));
                key(PlayerIntake.Key.GUARD, guard, stamp, -1);
            } else if (k < 80) {
                server.receive(Input.of(Input.Kind.INHALE, judged));
                key(PlayerIntake.Key.INHALE, true, stamp, -1);
            } else {
                breathe = !breathe;
                if (breathe) {
                    breatheAt = judged;
                    server.receive(Input.of(Input.Kind.BREATHE_DOWN, judged));
                    key(PlayerIntake.Key.BREATHE, true, stamp, -1);
                } else {
                    // a real key-up (ClientInput.slotAtRelease): the radial aimed at its stamp, then the stroke's
                    // slot, which a flick toward a slot (or none) gets only while it aims
                    client.aim(stamp, frameMicros, r, shown, shown);
                    int toward = random.nextInt(SLOTS + 1) - 1;
                    double a = Math.toRadians(toward * 360.0 / SLOTS);
                    double deg = toward >= 0 ? 3 * FLICK : 0;
                    int slot = client.radial.release(deg * Math.sin(a), -deg * Math.cos(a), SLOTS, FLICK);
                    server.receive(Input.release(judged, slot));
                    key(PlayerIntake.Key.BREATHE, false, stamp, slot);
                }
            }
        }

        private void key(PlayerIntake.Key k, boolean down, long stamp, int slot) {
            long sent = client.key(k, down, stamp, frameMicros, slot, r, shown, shown);
            assertEquals(frameMicros, sent, "in judged order nothing is cut");
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
            return tick == Long.MIN_VALUE ? null : snapshotAt(server, tick);
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

    /**
     * Vanilla's loop at combat-clock µs: each render frame polls its presses (stamped at the poll, carrying the frame time
     * of the frame before), runs the client ticks that are due (catch-up ones back to back, each standing at the step
     * its start falls after), aims at the turn and at {@code RenderFrameEvent.Pre}, where the frame time is measured.
     */
    private static final class Loop {
        private final SplittableRandom random;
        private final boolean onBeat;
        private final int assist;
        private final long base; // the frame time
        private final boolean hitches;
        private final boolean between; // the ticks start between frames, at their nominal times
        private final long phase; // the client's tick grid, µs past the step boundaries
        private final OwnMoves.Rules r;
        private final Timeline server;
        private final OwnPresses client = new OwnPresses();
        private final Steps serverSteps = new Steps();
        private final Steps clientSteps = new Steps();
        private NetMessage.@Nullable Snapshot shown;
        private long settled = Long.MIN_VALUE; // the newest snapshot's tick
        private long nextTick = 1; // the next client tick due
        private long lastPose = Long.MIN_VALUE; // the last pose frame's step
        private long frameMicros; // ClientInput.frameMicros: the next presses carry it
        private long lastFrameStart;
        private long renderFrame;
        private int pending; // presses waiting for a frame, ClientInput.pending
        private long pendingFrame = -1;
        private long pendingFrameMicros;
        private long horizon = Long.MIN_VALUE; // the server's input horizon, from the frames sent
        private long serverStep; // the last step the server ran
        private long clientRan; // the last step the client ran
        private long checked = Long.MIN_VALUE; // the last step compared: from the first the client ran
        private boolean guard;
        private boolean breathe;
        private long breatheAt;
        private long lastBeat = Long.MIN_VALUE;
        private final ArrayDeque<String> trace = new ArrayDeque<>();

        Loop(long seed) {
            random = new SplittableRandom(seed);
            base = FRAMES[(int) (seed % FRAMES.length)];
            assist = (int) (seed / 5 % 3);
            onBeat = seed / 15 % 2 == 0;
            hitches = seed / 30 % 2 == 1;
            boolean randomPhase = seed / 60 % 2 == 1;
            between = seed / 120 % 2 == 1;
            phase = randomPhase
                    ? random.nextLong(Micros.PER_TICK)
                    : Math.min(StampCheck.halfFrame(RULES.timing(), base), Micros.PER_TICK - 1);
            r = OwnMoves.Rules.of(WATER, RULES, BREATH, assist);
            server = serverAt(assist);
            frameMicros = base;
            client.stepped = s -> {
                if (s >= 0 && s < clientSteps.attack.length) {
                    FrameData f = client.moves.attackAt(s);
                    clientSteps.attack[(int) s] = f != null ? f.id() : "-";
                    clientSteps.radial[(int) s] = client.radial.aiming();
                    clientRan = Math.max(clientRan, s);
                    checked = checked == Long.MIN_VALUE ? s - 1 : checked;
                }
                took();
            };
        }

        /** The next frame's length: the base, a tenth either way, and with hitches one in 40 of 40 to 250 ms. */
        private long frame() {
            if (hitches && random.nextInt(40) == 0) {
                return Math.max(base, 40_000 + random.nextLong(210_000));
            }
            return hitches ? base + random.nextLong(-base / 10, base / 10 + 1) : base;
        }

        void run() {
            long poll = Micros.ofMillis(300) + random.nextLong(base); // none judged before the server's first step
            lastFrameStart = poll - base;
            nextTick = Math.floorDiv(poll - phase, Micros.PER_TICK); // the client's first tick, due at the first poll
            while (nextTick <= TICKS) {
                long length = frame();
                while (between && nextTick <= TICKS && Micros.ofTicks(nextTick) + phase < poll) {
                    long at = Micros.ofTicks(nextTick) + phase;
                    tickEnd(tickStart(at), at + 300);
                }
                poll(poll);
                int ran = 0;
                while (!between && nextTick <= TICKS && Micros.ofTicks(nextTick) + phase <= poll && ran < 10) {
                    tickEnd(tickStart(poll + 200 + ran * 1_000L), poll + 800 + ran * 1_000L);
                    ran++;
                }
                long turn = poll + 1_000 + ran * 1_000L; // ClientInput.turn, before the frame's start
                client.aim(turn, frameMicros, r, shown, shown);
                long start = turn + 200; // RenderFrameEvent.Pre: ClientInput.frameStart
                frameMicros = start - lastFrameStart;
                lastFrameStart = start;
                renderFrame++;
                client.aim(start, frameMicros, r, shown, shown);
                long next = poll + length;
                while (Micros.ofTicks(serverStep + 1) < next - HALF_MAX && serverStep < TICKS + 4) {
                    step(++serverStep); // no press polled from the next frame on is judged in it
                }
                compare();
                poll = next;
            }
            while (serverStep < TICKS + 4) {
                step(++serverStep);
            }
            compare();
            assertTrue(checked >= TICKS, what("the client ran every step", checked + 1));
        }

        /** ClientInput.tickStart: its own moves up to the step its pose carries, then the presses go out; its pose. */
        private long tickStart(long at) {
            long pose = Math.floorDiv(at, Micros.PER_TICK);
            note(pose, "client tick " + nextTick + " starts at " + at);
            nextTick++;
            client.tick(pose, r, shown, shown);
            took();
            if (pending > 0) {
                send(at, pendingFrameMicros);
            }
            return pose;
        }

        /** ClientInput.tickEnd at {@code at}: the pose frame of the tick whose start stood at {@code pose}. */
        private void tickEnd(long pose, long at) {
            if (pose > lastPose) {
                lastPose = pose;
                send(at, pending > 0 ? pendingFrameMicros : frameMicros);
            }
        }

        /** ClientInput.send: the server's horizon reaches the frame's stamp less half its frame time. */
        private void send(long stamp, long frame) {
            horizon = Math.max(horizon, stamp - StampCheck.halfFrame(RULES.timing(), frame));
            pending = 0;
            client.sent(stamp, frame, r, shown, shown);
        }

        private void step(long t) {
            server.beginTick(t);
            server.endTick(t);
            Combatant c = server.live();
            if (t < serverSteps.attack.length) {
                FrameData a = c.state() == State.ATTACK ? c.attack() : null;
                serverSteps.attack[(int) t] = a != null ? a.id() : "-";
                serverSteps.step[(int) t] = c.state() == State.STEP
                        && c.stateUntil() == t + WATER.step().total();
                serverSteps.radial[(int) t] = c.state() == State.RADIAL;
            }
            long tick = server.settledThrough(horizon, Long.MAX_VALUE);
            if (tick != Long.MIN_VALUE && tick > settled) {
                settled = tick;
                shown = snapshotAt(server, tick);
            }
        }

        /** Every step both sides have run, compared. */
        private void compare() {
            if (checked == Long.MIN_VALUE) {
                return;
            }
            long through = Math.min(Math.min(serverStep, clientRan), TICKS);
            for (long t = checked + 1; t <= through; t++) {
                int i = (int) t;
                assertEquals(serverSteps.attack[i], clientSteps.attack[i], what("the attack", t));
                assertEquals(serverSteps.step[i], clientSteps.step[i], what("a Step's start", t));
                assertEquals(serverSteps.radial[i], clientSteps.radial[i], what("the radial open", t));
                checked = t;
            }
        }

        private void took() {
            long s = client.moves.takeStep();
            if (s != OwnMoves.NONE && s >= 0 && s < clientSteps.step.length) {
                clientSteps.step[(int) s] = true;
            }
        }

        /** This frame's presses: on the beat, the first poll judged past each beat two times in three. */
        private void poll(long at) {
            int n = 0;
            if (onBeat) {
                long judged = at - StampCheck.halfFrame(RULES.timing(), frameMicros);
                long beat = r.pulse().nearestBeat(judged);
                if (r.pulse().beatMicros(beat) <= judged && beat > lastBeat) {
                    lastBeat = beat;
                    n = random.nextInt(3) > 0 ? 1 : 0;
                }
                if (n == 0 && random.nextLong(Micros.PER_TICK * 10) < base) {
                    n = 1;
                }
            } else {
                long u = random.nextLong(Micros.PER_TICK * 24); // about 0.42 a tick, as the other driver
                n = u < base ? 2 : u < base * 10 ? 1 : 0;
            }
            for (int i = 0; i < n; i++) {
                press(at + i);
            }
        }

        private void press(long stamp) {
            int k = random.nextInt(100);
            long early =
                    stamp - StampCheck.halfFrame(RULES.timing(), client.frameTime(stamp, frameMicros, RULES.timing()));
            if (k < 30) {
                server.receive(Input.of(Input.Kind.ATTACK, delta(PlayerIntake.Key.ATTACK, true, stamp, -1, k)));
            } else if (k < 38) {
                server.receive(Input.of(Input.Kind.POMMEL, delta(PlayerIntake.Key.POMMEL, true, stamp, -1, k)));
            } else if (k < 55) {
                if (breathe && early - breatheAt < HOLD + Micros.ofMillis(1)) {
                    return; // it may yet be a tap (the class doc); a ms to spare for a frame that cuts it
                }
                server.receive(Input.of(Input.Kind.STEP, delta(PlayerIntake.Key.STEP, true, stamp, -1, k)));
                took();
            } else if (k < 72) {
                boolean down = !guard;
                long judged = delta(PlayerIntake.Key.GUARD, down, stamp, -1, k);
                guard = down;
                server.receive(down ? Input.guard(judged) : Input.of(Input.Kind.GUARD_UP, judged));
            } else if (k < 80) {
                server.receive(Input.of(Input.Kind.INHALE, delta(PlayerIntake.Key.INHALE, true, stamp, -1, k)));
            } else if (!breathe) {
                long judged = delta(PlayerIntake.Key.BREATHE, true, stamp, -1, k);
                breathe = true;
                breatheAt = judged;
                server.receive(Input.of(Input.Kind.BREATHE_DOWN, judged));
            } else {
                // ClientInput.breatheUp: what waits goes first, then (slotAtRelease) the radial aimed at its stamp and
                // the stroke's slot, while it aims
                if (pending > 0) {
                    send(stamp, pendingFrameMicros);
                }
                client.aim(stamp, frameMicros, r, shown, shown);
                int toward = random.nextInt(SLOTS + 1) - 1;
                double a = Math.toRadians(toward * 360.0 / SLOTS);
                double deg = toward >= 0 ? 3 * FLICK : 0;
                int slot = client.radial.release(deg * Math.sin(a), -deg * Math.cos(a), SLOTS, FLICK);
                long judged = delta(PlayerIntake.Key.BREATHE, false, stamp, slot, k);
                breathe = false;
                server.receive(Input.release(judged, slot));
            }
        }

        /**
         * ClientInput.delta: a frame from another render frame or with another frame time goes first, then the press, by
         * the frame time it goes out with. Returns the stamp the server judges it at.
         */
        private long delta(PlayerIntake.Key k, boolean down, long stamp, int slot, int kind) {
            if (pending > 0 && pendingFrame != renderFrame) {
                send(stamp, pendingFrameMicros);
            }
            long f = client.frameTime(stamp, frameMicros, RULES.timing());
            if (pending > 0 && pendingFrameMicros != f) {
                send(stamp, pendingFrameMicros);
                f = client.frameTime(stamp, frameMicros, RULES.timing());
            }
            if (pending == 0) {
                pendingFrame = renderFrame;
                pendingFrameMicros = f;
            }
            pending++;
            long judged = stamp - StampCheck.halfFrame(RULES.timing(), f);
            note(
                    Micros.stepOf(judged),
                    kind(kind, guard, breathe) + " polled at " + stamp + " in a " + frameMicros
                            + " µs frame, sent with " + f + ": judged at " + judged);
            assertEquals(f, client.key(k, down, stamp, f, slot, r, shown, shown), "the frame time it goes out with");
            return judged;
        }

        private void note(long step, String line) {
            if (trace.size() == 60) {
                trace.removeFirst();
            }
            trace.addLast("[step " + step + "] " + line);
        }

        private Supplier<String> what(String check, long t) {
            return () -> {
                StringBuilder s = new StringBuilder(check + " at step " + t + ": " + base / 1000.0 + " ms frames"
                        + (hitches ? " with hitches" : "") + ", ticks " + phase + " µs past the step"
                        + (between ? " between frames" : " in the frames") + (onBeat ? ", on the beat" : "")
                        + ", assist " + assist + "\n");
                for (long u = Math.max(1, t - 8); u <= Math.min(t + 1, TICKS); u++) {
                    int i = (int) u;
                    s.append("  ")
                            .append(u)
                            .append(": server ")
                            .append(serverSteps.attack[i])
                            .append(serverSteps.step[i] ? " STEP" : "")
                            .append(serverSteps.radial[i] ? " RADIAL" : "")
                            .append(" | client ")
                            .append(clientSteps.attack[i])
                            .append(clientSteps.step[i] ? " STEP" : "")
                            .append(clientSteps.radial[i] ? " aim" : "")
                            .append('\n');
                }
                return s.append(String.join("\n", trace)).toString();
            };
        }
    }
}
