package dev.champ.kimetsu.core.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.MoveRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.demon.DemonBrain;
import dev.champ.kimetsu.core.fight.CombatWorld;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.Timeline;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server memory behind the Performance Budget's "about 1.5 KB per combatant" (spec S596, unmeasured until slice
 * 5c), rerun with {@code ./gradlew :core:simMeasure}. Each kind is built {@value #COUNT} times as the mod builds it at
 * a join ({@code PlayerSession.join}, {@code PlayerSession}'s intake, {@code M1Demon.join}), on combat data shared as
 * the mod shares it; what it holds is the heap after a GC with them, less the heap before, and what it allocated is the
 * thread's allocation counter over the builds. Then {@value #COUNT} fights of one player and one M1 demon run 30 s in
 * one world (an arena's, the fights out of each other's range), the player guarding every strike: what a fight holds
 * once its histories and logs are full. It writes {@code memory.md} to the sim report folder; it holds no bar, since it
 * informs the spec's errata rather than guarding a number.
 */
@Tag("measure")
class CombatantMemoryMeasure {
    private static final int COUNT = 1_000;
    /** 30 s of a fight. */
    private static final int FIGHT_TICKS = 600;
    /** How far apart the fights stand: past the demon's 24-block target range. */
    private static final double APART = 100;
    /** The player's guard press against each strike, and its release (a Perfect, held past the verdict). */
    private static final long GUARD_MS = -30;

    private static final long RELEASE_MS = 200;

    // the combat data, loaded once and shared by every combatant, as the mod's CombatData is
    private static final HitRules HITS = Fixtures.hits();
    private static final StrikeRules STRIKES = Fixtures.strikes();
    private static final TimingRules TIMING = Fixtures.timing();
    private static final RhythmRules RHYTHM = Fixtures.rhythm();
    private static final MachineRules MACHINE = Fixtures.machine();
    private static final BreathRules BREATH = Fixtures.breath();
    private static final PostureRules POSTURE = Fixtures.playerPosture();
    private static final MoveRules MOVEMENT = Fixtures.movement();
    private static final Moveset WATER = Fixtures.water();
    private static final DemonProfile DEMON = Fixtures.demon();
    private static final Map<String, FrameData> ATTACKS = Fixtures.demonAttacks();

    /** One kind's bytes a copy: held after a GC, and allocated while built. */
    private record Measured(double heldBytes, double allocatedBytes) {}

    /** An M1 demon's two halves: its body and its brain. */
    private record DemonAndBrain(Demon demon, DemonBrain brain) {}

    @Test
    void bytesPerCombatant() {
        Measured fighter = measure(id -> playerFighter(id, 1));
        Measured intake = measure(id -> new PlayerIntake(TIMING, 0, PlayerIntake.NO_SINK));
        Measured demon = measure(id -> demonAndBrain(id, 0, 1));
        int[] tally = new int[2]; // strikes started, results
        Measured fights = measureFights(tally);
        double fresh = fighter.heldBytes() + demon.heldBytes();
        String out = "# Server memory per combatant\n\n"
                + String.format(
                        Locale.ROOT,
                        "%,d of each, built as the mod builds them at a join; held = the heap after a GC with them,"
                                + " less before; allocated = the thread's allocation counter while built.%n%n",
                        COUNT)
                + "| What | Held, KB each | Allocated, KB each |\n|---|---|---|\n"
                + row("A player fighter (`Fighter`, its `Timeline`, `Combatant`, `InputHorizon`)", fighter)
                + row("A player's `PlayerIntake`", intake)
                + row("An M1 demon (`Demon` and `DemonBrain`)", demon)
                + String.format(
                        Locale.ROOT,
                        "%nAfter 30 s: %,d fights of one player and one M1 demon in one world, %,d strikes started and"
                                + " %,d decided. A fight holds %.1f KB (its player fighter and its demon held %.1f KB"
                                + " fresh: %+.1f KB from its full histories, logs and the world's per-combatant"
                                + " arrays); the fight loop allocated %.1f KB a fight over the 30 s, with this test's"
                                + " own presses.%n",
                        COUNT,
                        tally[0],
                        tally[1],
                        fights.heldBytes() / 1024,
                        fresh / 1024,
                        (fights.heldBytes() - fresh) / 1024,
                        fights.allocatedBytes() / 1024);
        System.out.print(out);
        Batch.report("memory.md", out);
        assertTrue(tally[1] > COUNT, "the fights decided only " + tally[1] + " strikes: they weren't fights");
    }

    private static String row(String what, Measured m) {
        return String.format(
                Locale.ROOT, "| %s | %.1f | %.1f |%n", what, m.heldBytes() / 1024, m.allocatedBytes() / 1024);
    }

    /** {@value #COUNT} of what {@code make} builds from ids 1, 2, …, measured. */
    private static Measured measure(IntFunction<Object> make) {
        Object warm = make.apply(COUNT + 1); // the classes load and the code runs once before the measure
        Object[] keep = new Object[COUNT];
        try {
            long before = held();
            long from = allocated();
            for (int i = 0; i < COUNT; i++) {
                keep[i] = make.apply(i + 1);
            }
            long allocated = allocated() - from;
            long after = held();
            return new Measured((after - before) / (double) COUNT, allocated / (double) COUNT);
        } finally {
            Reference.reachabilityFence(warm);
            Reference.reachabilityFence(keep);
        }
    }

    /**
     * {@value #COUNT} fights in one world, run {@value #FIGHT_TICKS} ticks: a frame from each player every tick (60 fps,
     * no latency), a guard {@value #GUARD_MS} ms before each strike's impact; {@code tally} gets the strikes started and
     * decided. Measured per fight.
     */
    private static Measured measureFights(int[] tally) {
        long before = held();
        long from = allocated();
        CombatWorld world = new CombatWorld(HITS, STRIKES, TIMING, RHYTHM, WATER, Sight.OPEN, Ground.OPEN);
        Fighter[] players = new Fighter[COUNT];
        List<DemonAndBrain> demons = new ArrayList<>(COUNT);
        try {
            return fight(world, players, demons, tally, before, from);
        } finally {
            Reference.reachabilityFence(world);
            Reference.reachabilityFence(players);
            Reference.reachabilityFence(demons);
        }
    }

    private static Measured fight(
            CombatWorld world, Fighter[] players, List<DemonAndBrain> demons, int[] tally, long before, long from) {
        for (int i = 0; i < COUNT; i++) {
            players[i] = world.add(playerFighter(i + 1, 1));
            DemonAndBrain d = demonAndBrain(COUNT + i + 1, i * APART, 1);
            world.add(d.demon(), d.brain());
            demons.add(d);
        }
        Map<Long, List<Object[]>> presses = new HashMap<>(); // tick → {player index, input}
        for (long n = 1; n <= FIGHT_TICKS; n++) {
            List<Object[]> due = presses.remove(n);
            if (due != null) {
                for (Object[] p : due) {
                    players[(Integer) p[0]].timeline().receive((Input) p[1]);
                }
            }
            for (int i = 0; i < COUNT; i++) {
                Fighter f = players[i];
                f.poses().put(Micros.ofTicks(n), i * APART, 64, 2, 180, 0); // 2 blocks in front of its demon
                f.horizon().frame(Micros.ofTicks(n), 16_667, Micros.ofTicks(n));
            }
            world.tick(n);
            for (Outbox.Event e : world.outbox().drain()) {
                if (e instanceof Outbox.AttackStarted a && a.target() <= COUNT) {
                    tally[0]++;
                    long impact = Micros.ofTicks(a.impactTick());
                    press(presses, a.target() - 1, Input.guard(impact + Micros.ofMillis(GUARD_MS)), n);
                    press(
                            presses,
                            a.target() - 1,
                            Input.of(Input.Kind.GUARD_UP, impact + Micros.ofMillis(RELEASE_MS)),
                            n);
                } else if (e instanceof Outbox.StrikeResult) {
                    tally[1]++;
                }
            }
        }
        presses.clear(); // the presses for impacts past the run aren't a fight's (code review)
        long allocated = allocated() - from;
        long after = held();
        return new Measured((after - before) / (double) COUNT, allocated / (double) COUNT);
    }

    /** {@code in} for player {@code index}, received before the tick its stamp falls in (never before {@code now + 1}). */
    private static void press(Map<Long, List<Object[]>> presses, int index, Input in, long now) {
        presses.computeIfAbsent(Math.max(in.tick(), now + 1), k -> new ArrayList<>())
                .add(new Object[] {index, in});
    }

    /** A player fighter as {@code PlayerSession.join} builds it, joining at {@code tick}; its frames place it. */
    private static Fighter playerFighter(int id, long tick) {
        PulseSchedule pulse = new PulseSchedule(WATER.style().beatTicks());
        Combatant start = new Combatant(MACHINE, TIMING, BREATH, POSTURE, RHYTHM, WATER, pulse, tick - 1);
        Fighter f = new Fighter(
                id,
                new Timeline(start, tick),
                new InputHorizon(TIMING),
                pulse,
                Fixtures.PLAYER_HALF_WIDTH,
                Fixtures.PLAYER_HEIGHT,
                Fixtures.PLAYER_EYE,
                InputHorizon.holdCapMicros(TIMING, Micros.ofMillis(100), Micros.ofMillis(TIMING.toleranceMinMs())));
        f.movement(MOVEMENT, WATER.step(), MACHINE.bufferTicks());
        return f;
    }

    /** An M1 demon as {@code M1Demon.join} builds it, joining at {@code tick}, at {@code x} facing +z. */
    private static DemonAndBrain demonAndBrain(int id, double x, long tick) {
        Demon d = new Demon(id, DEMON, HITS, tick, MACHINE.historyTicks(), x, 64, 0, 0, id);
        return new DemonAndBrain(d, new DemonBrain(d, STRIKES, ATTACKS, id));
    }

    /** The heap in use after a GC: the least of three, since one may leave floating garbage. */
    private static long held() {
        MemoryMXBean m = ManagementFactory.getMemoryMXBean();
        long least = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            System.gc();
            least = Math.min(least, m.getHeapMemoryUsage().getUsed());
        }
        return least;
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }
}
