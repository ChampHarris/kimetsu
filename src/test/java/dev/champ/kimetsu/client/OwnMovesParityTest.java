package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.machine.Combatant;
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
 * {@link OwnMoves} against the server's own machine (plan Risk 8): random presses of Attack, Pommel, Step, guard and
 * Breathe go through a core {@link Timeline}, stepped as a player's session steps it (a tick's presses, then its timers
 * and meters; a Breathe tap adds its Inhale at its press), and through {@link OwnMoves} and {@link RadialStroke}, driven
 * as {@code ClientInput} drives them, which see the snapshots the server sends: after each step, of the newest tick no
 * press still to come can change ({@code PlayerSession.afterStep}). There's no link delay, so only the rules can
 * differ. Tick by tick, the attack each runs, each Step's start and whether the radial is open must agree. A slotted
 * release goes only while the client aims, as from a real client. Half the seeds press on the beat, so the Light Chain
 * continues and Rhythm reaches Mushin; the rest press anywhere, so Breath runs out and winds.
 *
 * <p>Two things OwnMoves can't know are left out, both step 5's to reconcile. The held guard's Breath drain is off:
 * OwnMoves runs the snapshot's Breath on at the rate it was settled with, which a guard raised or lowered since
 * changes. And no Step is pressed while Breathe is down short of the radial's hold: whether the server can pay for it
 * depends on the Inhale a tap would add at that press, which only the release tells.
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
    private static final OwnMoves.Rules R = OwnMoves.Rules.of(WATER, RULES, BREATH, 0);
    private static final int TICKS = 1_200; // a minute of fighting
    private static final long HOLD = Micros.ofMillis(RULES.machine().radialHoldMs());
    private static final long LIMIT = Micros.ofTicks(
            (long) RULES.machine().radialLimitBeats() * WATER.style().beatTicks());

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
        private final Timeline server = new Timeline(
                new Combatant(
                        RULES.machine(),
                        RULES.timing(),
                        BREATH,
                        Fixtures.playerPosture(),
                        RULES.rhythm(),
                        WATER,
                        new PulseSchedule(WATER.style().beatTicks()),
                        0),
                1);
        private final OwnMoves own = new OwnMoves();
        private final RadialStroke radial = new RadialStroke();
        private NetMessage.@Nullable Snapshot shown;
        private boolean guard;
        private boolean breathe;
        private long breatheAt;
        private long clientStep = OwnMoves.NONE; // the Step the client started in this tick
        private final ArrayDeque<String> trace = new ArrayDeque<>(); // the last presses and ticks, for a failure

        Run(long seed) {
            random = new SplittableRandom(seed);
            onBeat = seed % 2 == 0;
        }

        void run() {
            for (long t = 1; t <= TICKS; t++) {
                clientStep = OwnMoves.NONE;
                for (long stamp : stamps(t)) {
                    press(t, stamp);
                }
                // the client tick's start: the radial opens first, then the buffer runs (ClientInput.ownTick)
                aim(Micros.ofTicks(t), t);
                own.tick(t, Micros.ofTicks(t), shown, R);
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

        /** The stamps of this tick's presses, in order: none, one or two, inside the tick's 50 ms (they act in it). */
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

        private void press(long t, long stamp) {
            aim(stamp, t - 1); // the frame's own aim, before the press is polled
            int k = random.nextInt(100);
            note("  " + t + " @" + (stamp - Micros.ofTicks(t - 1)) / 1000.0 + "ms: " + kind(k));
            if (k < 30) {
                server.receive(Input.of(Input.Kind.ATTACK, stamp));
                own.attack(t, stamp, R);
            } else if (k < 38) {
                server.receive(Input.of(Input.Kind.POMMEL, stamp));
                own.pommel(t, R);
            } else if (k < 55) {
                if (breathe && stamp - breatheAt < HOLD) {
                    return; // it may yet be a tap (the class doc)
                }
                server.receive(Input.of(Input.Kind.STEP, stamp));
                own.step(t, stamp, shown, R);
                took();
            } else if (k < 75) {
                guard = !guard;
                if (guard) {
                    server.receive(Input.guard(stamp));
                    radial.guardPressed(stamp, HOLD);
                    own.guardDown(t, stamp, shown, R);
                } else {
                    server.receive(Input.of(Input.Kind.GUARD_UP, stamp));
                    own.guardUp();
                }
            } else {
                breathe = !breathe;
                if (breathe) {
                    breatheAt = stamp;
                    server.receive(Input.of(Input.Kind.BREATHE_DOWN, stamp));
                    radial.press(stamp);
                    own.breathe(t, stamp, R);
                } else {
                    // a real key-up: a slot only while the client aims (its stroke), as ClientInput sends it
                    int slot = radial.aiming() ? random.nextInt(5) - 1 : -1;
                    radial.cancel();
                    server.receive(Input.release(stamp, slot));
                    own.release(slot >= 0 ? WATER.radial(slot) : null, t, stamp, shown, R);
                }
            }
        }

        /** ClientInput.aim: the radial's aim at {@code micros}, the client tick last run being {@code clientTick}. */
        private void aim(long micros, long clientTick) {
            NetMessage.Snapshot s = shown;
            boolean aiming = radial.update(
                    micros,
                    HOLD,
                    LIMIT,
                    own.blocked(clientTick),
                    s != null ? s.state() : null,
                    s != null ? Micros.ofTicks(s.settledTick()) : Long.MIN_VALUE);
            own.radial(aiming, s);
        }

        private void took() {
            long s = own.takeStep();
            if (s != OwnMoves.NONE) {
                clientStep = s;
            }
        }

        private void check(long t) {
            Combatant c = server.live();
            FrameData a = c.state() == State.ATTACK ? c.attack() : null;
            FrameData mine = own.attackAt(t);
            note(t + " server " + c.state() + (a != null ? " " + a.id() + " from " + c.attackStart() : "")
                    + " Breath " + c.breath().at(t) / (double) Fixed.ONE
                    + " buffer" + (c.buffer().get(InputBuffer.Slot.STEP) != null ? " S" : "")
                    + (c.buffer().get(InputBuffer.Slot.POMMEL) != null ? " P" : "")
                    + (c.buffer().get(InputBuffer.Slot.ATTACK) != null ? " A" : "")
                    + (c.guardDown() ? ", guard" : "")
                    + " | client " + (mine != null ? mine.id() : "-") + (clientStep != OwnMoves.NONE ? " Step" : "")
                    + (radial.aiming() ? " aiming" : "") + (own.blocked(t) ? " blocked" : ""));
            assertEquals(a != null ? a.id() : "none", mine != null ? mine.id() : "none", what("the attack", t));
            boolean serverStep = c.state() == State.STEP
                    && c.stateUntil() == t + WATER.step().total();
            assertEquals(serverStep ? t : OwnMoves.NONE, clientStep, what("a Step's start", t));
            assertEquals(c.state() == State.RADIAL, radial.aiming(), what("the radial open", t));
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
            return k < 75 ? (guard ? "guard up" : "guard down") : (breathe ? "Breathe up" : "Breathe down");
        }

        private void note(String line) {
            if (trace.size() == 40) {
                trace.removeFirst();
            }
            trace.addLast(line);
        }

        private Supplier<String> what(String check, long t) {
            return () ->
                    check + " at seed tick " + t + (onBeat ? " (on the beat)" : "") + "\n" + String.join("\n", trace);
        }
    }
}
