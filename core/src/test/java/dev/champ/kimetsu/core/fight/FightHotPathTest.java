package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.strike.Scheduler;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The fight loop's quiet ticks (Performance Budget) allocate nothing: four players and two demons with nothing
 * committed, every player's frame arriving and its machine running; and three players under a demon's strikes, on the
 * ticks a strike waits for its impact. Run by {@code :core:hotPathTest} with escape analysis off,
 * like the sweep's check; the time a tick takes is printed.
 */
@Tag("hot-path")
class FightHotPathTest {
    private final CombatWorld world = new CombatWorld(
            Fixtures.hits(), Fixtures.strikes(), Fixtures.timing(), Fixtures.rhythm(), Arena.WATER, Sight.OPEN);
    private final Fighter[] fighters = new Fighter[4];
    private long n;

    FightHotPathTest() {
        PulseSchedule pulse = new PulseSchedule(Arena.BEAT);
        for (int i = 0; i < fighters.length; i++) {
            fighters[i] = world.add(new Fighter(
                    i + 1,
                    new Timeline(Arena.combatant(pulse, 0), 1),
                    new InputHorizon(Fixtures.timing()),
                    pulse,
                    Fixtures.PLAYER_HALF_WIDTH,
                    Fixtures.PLAYER_HEIGHT,
                    Fixtures.PLAYER_EYE,
                    Micros.ofMillis(300)));
        }
        world.add(new BoxDummy(10), (tick, self, w) -> {});
        world.add(new BoxDummy(11).at(4, 0, 0), (tick, self, w) -> {});
    }

    private void tick() {
        long t = ++n;
        for (int i = 0; i < fighters.length; i++) {
            Fighter f = fighters[i];
            f.poses().put(Micros.ofTicks(t), i, 64, 2, 180, 0);
            f.horizon().frame(Micros.ofTicks(t), 16_667, Micros.ofTicks(t));
        }
        world.tick(t);
        world.outbox().drain();
    }

    @Test
    void aTickWithAStrikeUnderWayAllocatesNothing() {
        // a demon swipes at player 1 every two beats; ticks that record nothing (no commit, cue, result or effect) are
        // measured: those with a strike locked and waiting for its impact run involve's path and every per-player check
        CombatWorld w = new CombatWorld(
                Fixtures.hits(), Fixtures.strikes(), Fixtures.timing(), Fixtures.rhythm(), Arena.WATER, Sight.OPEN);
        PulseSchedule pulse = new PulseSchedule(Arena.BEAT);
        Fighter[] players = new Fighter[3];
        for (int i = 0; i < players.length; i++) {
            players[i] = w.add(new Fighter(
                    i + 1,
                    new Timeline(Arena.combatant(pulse, 0), 1),
                    new InputHorizon(Fixtures.timing()),
                    pulse,
                    Fixtures.PLAYER_HALF_WIDTH,
                    Fixtures.PLAYER_HEIGHT,
                    Fixtures.PLAYER_EYE,
                    Micros.ofMillis(300)));
        }
        List<FrameData> swipe = List.of(Fixtures.swipe());
        w.add(new BoxDummy(10).follow(w), (tick, self, world) -> {
            if (tick % 26 == 0) {
                world.commit(self.id(), 1, swipe, Scheduler.ANY_BEAT);
            }
        });
        long[] t = {0};
        Runnable tick = () -> {
            long n = ++t[0];
            for (int i = 0; i < players.length; i++) {
                players[i].poses().put(Micros.ofTicks(n), i - 1, 64, 2, 180, 0); // all three in its sweep
                players[i].horizon().frame(Micros.ofTicks(n), 16_667, Micros.ofTicks(n));
            }
            w.tick(n);
        };
        for (int i = 0; i < 50_000; i++) {
            tick.run();
            w.outbox().drain();
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] windows = new long[10];
        int waiting = 0;
        for (int win = 0; win < windows.length; win++) {
            for (int i = 0; i < 2_000; i++) {
                long before = bean.getCurrentThreadAllocatedBytes();
                tick.run();
                long after = bean.getCurrentThreadAllocatedBytes();
                if (w.outbox().peekEmpty()) {
                    windows[win] += after - before;
                    if (!w.pending().isEmpty() && w.pending().get(0).locked()) {
                        waiting++;
                    }
                }
                w.outbox().drain();
            }
        }
        System.out.println("quiet fight ticks with a strike under way: " + waiting
                + "; bytes allocated on quiet ticks per window of 2,000: " + Arrays.toString(windows));
        assertTrue(waiting > 5_000, "too few ticks with a strike waiting: " + waiting);
        int clean = 0;
        for (long bytes : windows) {
            clean += bytes == 0 ? 1 : 0;
        }
        assertTrue(
                clean >= 6,
                "quiet ticks allocated in " + (windows.length - clean) + " of 10 windows: " + Arrays.toString(windows));
    }

    @Test
    void anIdleDemonsTickAllocatesNothing() {
        // two M1 demons in their guard stance, one turned toward its target: their snapshots, regeneration checks and
        // guard yaw run every tick and allocate nothing while nothing changes
        CombatWorld w = new CombatWorld(
                Fixtures.hits(), Fixtures.strikes(), Fixtures.timing(), Fixtures.rhythm(), Arena.WATER, Sight.OPEN);
        PulseSchedule pulse = new PulseSchedule(Arena.BEAT);
        Fighter[] players = new Fighter[2];
        for (int i = 0; i < players.length; i++) {
            players[i] = w.add(new Fighter(
                    i + 1,
                    new Timeline(Arena.combatant(pulse, 0), 1),
                    new InputHorizon(Fixtures.timing()),
                    pulse,
                    Fixtures.PLAYER_HALF_WIDTH,
                    Fixtures.PLAYER_HEIGHT,
                    Fixtures.PLAYER_EYE,
                    Micros.ofMillis(300)));
        }
        Demon d = new Demon(10, Fixtures.demon(), Fixtures.hits(), 1, 34, 0, 64, 0, 0);
        d.target(1);
        w.add(d, (tick, self, world) -> {});
        w.add(new Demon(11, Fixtures.demon(), Fixtures.hits(), 1, 34, 4, 64, 0, 0), (tick, self, world) -> {});
        long[] t = {0};
        Runnable tick = () -> {
            long n = ++t[0];
            for (int i = 0; i < players.length; i++) {
                players[i].poses().put(Micros.ofTicks(n), i, 64, 3, 180, 0);
                players[i].horizon().frame(Micros.ofTicks(n), 16_667, Micros.ofTicks(n));
            }
            w.tick(n);
            w.outbox().drain();
        };
        for (int i = 0; i < 50_000; i++) {
            tick.run();
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] windows = new long[10];
        long start = System.nanoTime();
        for (int win = 0; win < windows.length; win++) {
            long before = bean.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 20_000; i++) {
                tick.run();
            }
            windows[win] = bean.getCurrentThreadAllocatedBytes() - before;
        }
        long perTick = (System.nanoTime() - start) / (windows.length * 20_000L);
        System.out.println("idle demon ticks (2 players, 2 demons): " + perTick
                + " ns each; bytes allocated per window of 20,000: " + Arrays.toString(windows));
        int clean = 0;
        for (long bytes : windows) {
            clean += bytes == 0 ? 1 : 0;
        }
        assertTrue(
                clean >= 6,
                "idle demon ticks allocated in " + (windows.length - clean) + " of 10 windows: "
                        + Arrays.toString(windows));
    }

    /**
     * Quiet ticks of a world with one player 3 blocks in front of an M1 demon whose brain is {@code brain}: at least 6
     * of 10 windows allocate nothing; returns how many quiet ticks {@code counted} saw.
     */
    private static int quietDemonTicks(
            Demon d, Brain brain, java.util.function.LongConsumer before, java.util.function.LongPredicate counted) {
        CombatWorld w = new CombatWorld(
                Fixtures.hits(), Fixtures.strikes(), Fixtures.timing(), Fixtures.rhythm(), Arena.WATER, Sight.OPEN);
        PulseSchedule pulse = new PulseSchedule(Arena.BEAT);
        Fighter player = w.add(new Fighter(
                1,
                new Timeline(Arena.combatant(pulse, 0), 1),
                new InputHorizon(Fixtures.timing()),
                pulse,
                Fixtures.PLAYER_HALF_WIDTH,
                Fixtures.PLAYER_HEIGHT,
                Fixtures.PLAYER_EYE,
                Micros.ofMillis(300)));
        d.target(1);
        w.add(d, brain);
        long[] t = {0};
        Runnable tick = () -> {
            long n = ++t[0];
            before.accept(n);
            player.poses().put(Micros.ofTicks(n), 0, 64, 3, 180, 0);
            player.horizon().frame(Micros.ofTicks(n), 16_667, Micros.ofTicks(n));
            w.tick(n);
        };
        for (int i = 0; i < 50_000; i++) {
            tick.run();
            w.outbox().drain();
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] windows = new long[10];
        int seen = 0;
        for (int win = 0; win < windows.length; win++) {
            for (int i = 0; i < 2_000; i++) {
                long b = bean.getCurrentThreadAllocatedBytes();
                tick.run();
                long a = bean.getCurrentThreadAllocatedBytes();
                if (w.outbox().peekEmpty()) {
                    windows[win] += a - b;
                    seen += counted.test(t[0]) ? 1 : 0;
                }
                w.outbox().drain();
            }
        }
        System.out.println(
                "quiet demon ticks counted: " + seen + "; bytes per window of 2,000: " + Arrays.toString(windows));
        int clean = 0;
        for (long bytes : windows) {
            clean += bytes == 0 ? 1 : 0;
        }
        assertTrue(
                clean >= 6, "quiet demon ticks allocated in " + (10 - clean) + " of 10: " + Arrays.toString(windows));
        return seen;
    }

    @Test
    void aRegeneratingDemonsQuietTicksAllocateNothing() {
        // wounded from outside every 400 ticks (no state change to send), it regrows and pays Hunger on every tick
        DemonProfile p =
                DemonFightTest.profile(100, Fixtures.demonPosture(), new HungerRules(1_000_000, 1, 5, 25, 0.25));
        Demon d = new Demon(10, p, Fixtures.hits(), 1, 34, 0, 64, 0, 0);
        int regenerating = quietDemonTicks(
                d,
                (tick, self, world) -> {},
                n -> {
                    if (n % 400 == 0) {
                        d.outsideDamage(n, 20, false);
                    }
                },
                n -> d.hp() < 100);
        assertTrue(regenerating > 10_000, "too few regenerating ticks: " + regenerating);
    }

    @Test
    void aDemonsStrikeUnderWayAllocatesNothingOnItsQuietTicks() {
        // a swipe every two beats on a player who never answers: commits, cues, results and state changes record
        // something; the ticks between, with a strike locked and waiting, its windup and recovery running, don't
        List<FrameData> swipe = List.of(Fixtures.swipe());
        Demon d = new Demon(10, Fixtures.demon(), Fixtures.hits(), 1, 34, 0, 64, 0, 0);
        int striking = quietDemonTicks(
                d,
                (tick, self, world) -> {
                    if (tick % 26 == 0) {
                        world.commit(self.id(), 1, swipe, Scheduler.ANY_BEAT);
                    }
                },
                n -> {},
                n -> d.state(n) == State.ATTACK);
        assertTrue(striking > 2_000, "too few quiet ticks mid-strike: " + striking);
    }

    @Test
    void anIdleTickAllocatesNothing() {
        for (int i = 0; i < 50_000; i++) {
            tick();
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] windows = new long[10];
        long start = System.nanoTime();
        for (int w = 0; w < windows.length; w++) {
            long before = bean.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 20_000; i++) {
                tick();
            }
            windows[w] = bean.getCurrentThreadAllocatedBytes() - before;
        }
        long perTick = (System.nanoTime() - start) / (windows.length * 20_000L);
        System.out.println("idle fight ticks (4 players, 2 demons): " + perTick
                + " ns each; bytes allocated per window of 20,000: " + Arrays.toString(windows));
        int clean = 0;
        for (long bytes : windows) {
            clean += bytes == 0 ? 1 : 0;
        }
        assertTrue(
                clean >= 6,
                "idle ticks allocated in " + (windows.length - clean) + " of 10 windows: "
                        + Arrays.toString(windows)); // the JIT's tier changes allocate now and then (docs/gotchas.md)
    }
}
