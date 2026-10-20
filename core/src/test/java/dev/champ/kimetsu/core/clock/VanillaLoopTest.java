package dev.champ.kimetsu.core.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import java.util.Random;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rule 1.2's schedule against a model of vanilla's run loop ({@code MinecraftServer.runServer}), so the lateness signal
 * is what the mod will read: each tick is scheduled one nominal length after the last; the loop waits for that
 * schedule (waking up to a ms late) unless the last tick ran past it, handles packets, fires Pre, runs the tick's work
 * and fires Post. More than 2 s behind, and 15 s since it last did, vanilla skips its schedule forward.
 */
class VanillaLoopTest {
    private static final double MS = 1_000_000.0;
    private static final long N = 50_000_000L;
    private static final double[] FRACTIONS = {0.0, 0.25, 0.5, 0.75, 0.999};

    /** A run of the loop: each tick's Pre and Post times (ns), whether vanilla was behind, and the schedule's records. */
    private record Run(long[] pre, long[] post, long[] late, boolean[] dropped, TickRecord[] rec) {}

    private static Run run(int ticks, IntToDoubleFunction workMs, IntToDoubleFunction packetsMs, long seed) {
        Random rng = new Random(seed);
        long[] pre = new long[ticks];
        long[] post = new long[ticks];
        long[] late = new long[ticks];
        boolean[] dropped = new boolean[ticks];
        TickRecord[] rec = new TickRecord[ticks];
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        double now = 1e12;
        double sched = now;
        double lastWarn = 0;
        long prevEnd = Long.MIN_VALUE;
        for (int g = 0; g < ticks; g++) {
            double behind = now - sched;
            if (behind > 1e9 + 20.0 * N && sched - lastWarn >= 10e9 + 100.0 * N) {
                sched += Math.floor(behind / N) * N;
                lastWarn = sched;
                dropped[g] = true;
            }
            double thisSched = sched;
            sched += N;
            double p = now + packetsMs.applyAsDouble(g) * MS;
            double q = p + workMs.applyAsDouble(g) * MS;
            pre[g] = Math.round(p);
            post[g] = Math.round(q);
            late[g] = Math.round(p - thisSched);
            rec[g] = schedule.onTickStart(
                    new TickInput(g, pre[g], N, false, false, false, dropped[g], false, prevEnd, late[g]));
            prevEnd = post[g];
            now = q < sched ? sched + Math.abs(rng.nextGaussian()) * MS : q;
        }
        return new Run(pre, post, late, dropped, rec);
    }

    /**
     * The worst error (ms) over arrivals inside ticks [from, to): against wall time in step from {@code stallTick} for
     * ticks inside [stallTick, catchUpEnd), against game time elsewhere.
     */
    private static double worst(Run r, int from, int to, int stallTick, int catchUpEnd) {
        double worst = 0;
        for (int g = from; g < to && g + 1 < r.pre.length; g++) {
            for (double f : FRACTIONS) {
                long w = r.pre[g] + Math.round(f * (r.pre[g + 1] - r.pre[g]));
                double conv = r.rec[g].arrivalMicros(w) / 1000.0;
                double target = g >= stallTick && g < catchUpEnd
                        ? 50.0 * stallTick + (w - r.pre[stallTick]) / MS
                        : 50.0 * g + 50.0 * (w - r.pre[g]) / (r.pre[g + 1] - r.pre[g]);
                worst = Math.max(worst, Math.abs(conv - target));
            }
        }
        return worst;
    }

    /** The first tick after {@code from} that started on vanilla's schedule again (within 5 ms of it). */
    private static int caughtUp(Run r, int from) {
        int g = from + 1;
        while (g < r.late.length && r.late[g] > 5 * MS) {
            g++;
        }
        return g;
    }

    @Test
    void anIdleServerConvertsWithinTenMs() {
        Run r = run(400, g -> 10, g -> 0.2, 1);
        assertTrue(worst(r, 50, 399, -1, -1) <= 10);
        assertEquals(N, r.rec[399].tickLengthNanos(), MS);
    }

    /**
     * A 1.5 s stall on a server at 30 ms a tick: vanilla catches up 20 ms a tick for over 70 ticks, inside the
     * ordinary band. Counted as ordinary they took T̄ to 30 ms and the conversion 1.8 s off wall time (code review).
     */
    @Test
    void catchUpTicksInsideTheBandAreLeftOut() {
        Run r = run(500, g -> g == 200 ? 1500 : g > 200 ? 30 : 10, g -> 0.2, 2);
        int end = caughtUp(r, 200);
        assertTrue(end > 250, "caught up at " + end);
        double worst = worst(r, 50, 499, 200, end);
        assertTrue(worst <= 10, "worst " + worst + " ms");
        for (int g = 201; g < end; g++) {
            assertEquals(N, r.rec[g].tickLengthNanos(), MS, "T̄ moved during the catch-up, tick " + g);
        }
    }

    /** A chat command takes 300 ms before tick 200's Pre: a stall between ticks, not a pause. */
    @Test
    void aStallBetweenTicksIsNotAResume() {
        Run r = run(400, g -> 10, g -> g == 200 ? 300 : 0.2, 3);
        int end = caughtUp(r, 200);
        double worst = worst(r, 50, 399, 199, end);
        assertTrue(worst <= 10, "worst " + worst + " ms");
        assertEquals(0, r.rec[399].epoch());
    }

    /**
     * Ticks that suddenly take 80 or 120 ms, back to back: outside the band, so before the run detector T̄ stayed at
     * 50 ms and the conversion ran 12 s ahead of game time over 400 ticks (code review). Vanilla drops its backlog
     * along the way, which moves nothing here and so isn't an epoch.
     */
    @ParameterizedTest
    @ValueSource(doubles = {80, 120})
    void aSustainedSlowerRateIsFollowed(double ms) {
        Random spread = new Random(40);
        double[] work = new double[600];
        for (int g = 0; g < 600; g++) {
            work[g] = g >= 200 ? ms + 3 * spread.nextGaussian() : 10; // overloaded ticks vary
        }
        Run r = run(600, g -> work[g], g -> 0.2, 4);
        assertEquals(ms, r.rec[230].tickLengthNanos() / MS, 2.0);
        double settled = worst(r, 215, 599, -1, -1);
        assertTrue(settled <= 10, ms + " ms ticks: " + settled + " ms after settling");
        double transition = worst(r, 195, 215, -1, -1);
        System.out.println(ms + " ms ticks: transition " + Math.round(transition) + " ms, settled "
                + Math.round(settled * 10) / 10.0 + " ms");
        assertTrue(transition <= 3.5 * (ms - 50), ms + " ms ticks: " + transition + " ms while it changed");
        boolean anyDrop = false;
        for (boolean d : r.dropped) {
            anyDrop |= d;
        }
        assertTrue(anyDrop, "vanilla never dropped its backlog");
        assertEquals(0, r.rec[599].epoch());
    }

    /** The rate comes back down when the overload ends. */
    @Test
    void anOverloadThatEndsIsLetGo() {
        Run r = run(800, g -> g >= 200 && g < 400 ? 90 : 10, g -> 0.2, 5);
        int end = caughtUp(r, 400);
        assertEquals(N, r.rec[Math.min(799, end + 40)].tickLengthNanos(), 2 * MS);
        assertTrue(worst(r, Math.min(798, end + 40), 799, -1, -1) <= 10);
    }

    /** A 3 s stall leaves vanilla far enough behind to drop its backlog: the schedule really jumps, an epoch. */
    @Test
    void aStallVanillaGivesUpOnIsAnEpoch() {
        Run r = run(400, g -> g == 200 ? 3000 : 10, g -> 0.2, 6);
        int drop = -1;
        for (int g = 0; g < 400; g++) {
            if (r.dropped[g]) {
                drop = g;
                break;
            }
        }
        assertTrue(drop > 200, "no drop");
        assertEquals(1, r.rec[drop].epoch());
        assertEquals(r.pre[drop], r.rec[drop].startNanos(), 0.0);
        assertTrue(worst(r, drop + 1, 399, -1, -1) <= 10);
    }

    /** The interval that straddles a {@code /tick rate} change ran at the old pace, so it predicts at the old pace. */
    @Test
    void theTickThatStraddlesATickRateChangeKeepsTheOldPace() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long t = 0;
        TickRecord before = null;
        for (int g = 0; g < 30; g++) {
            before = schedule.onTickStart(TickInput.normal(g, t));
            t += N;
        }
        long slow = 66_666_667L;
        TickRecord at = schedule.onTickStart(new TickInput(30, t, slow, false, false, false, false, false));
        assertEquals(before.startNanos() + N, at.startNanos(), 1.0);
        assertEquals(slow, at.tickLengthNanos(), 0.0);
    }
}
