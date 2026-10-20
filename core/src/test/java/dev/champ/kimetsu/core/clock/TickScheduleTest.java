package dev.champ.kimetsu.core.clock;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rule 1.2's arrival conversion against the time an arrival should get: game time inside ordinary ticks (the share of
 * the tick that has passed), and wall time in step from the stall's start through a stall and its catch-up ticks.
 */
class TickScheduleTest {
    private static final double MS = 1_000_000.0;
    private static final long NOMINAL = 50_000_000L;
    private static final double[] FRACTIONS = {0.0, 0.25, 0.5, 0.75, 0.999};

    /** Measured tick starts (ns), what each tick is, and the nominal tick length in force. */
    @SuppressWarnings("ArrayRecordComponent") // a scenario's series, read by index; never compared or hashed
    private record Scenario(long[] start, char[] kind, long[] nominal) {
        int stall() {
            for (int g = 0; g < kind.length; g++) {
                if (kind[g] == 's') {
                    return g;
                }
            }
            return -1;
        }
    }

    private static Scenario steady(double tps, long seed) {
        Random rng = new Random(seed);
        int n = 400;
        long[] start = new long[n];
        char[] kind = new char[n];
        long[] nominal = new long[n];
        double t = 1e12;
        for (int g = 0; g < n; g++) {
            start[g] = Math.round(t + rng.nextGaussian() * MS);
            kind[g] = 'o';
            nominal[g] = Math.round(1e9 / tps);
            t += 1e9 / tps;
        }
        return new Scenario(start, kind, nominal);
    }

    /** 20 TPS, then from tick 200 every tick takes 66.7 ms (an overloaded server: the nominal length stays 50 ms). */
    private static Scenario step(long seed) {
        Random rng = new Random(seed);
        int n = 400;
        long[] start = new long[n];
        char[] kind = new char[n];
        long[] nominal = new long[n];
        double t = 1e12;
        for (int g = 0; g < n; g++) {
            start[g] = Math.round(t + rng.nextGaussian() * MS);
            kind[g] = 'o';
            nominal[g] = NOMINAL;
            t += g < 200 ? 50 * MS : 1000 * MS / 15;
        }
        return new Scenario(start, kind, nominal);
    }

    /** Tick 200 runs {@code stallMs}; vanilla then runs 2 ms catch-up ticks while its 50 ms schedule is behind. */
    private static Scenario stall(double stallMs, long seed) {
        Random rng = new Random(seed);
        List<long[]> rows = new ArrayList<>();
        double t = 1e12;
        double sched = t;
        int g = 0;
        while (g < 400) {
            if (g == 200) {
                rows.add(new long[] {Math.round(t), 's'});
                t += stallMs * MS;
                sched += 50 * MS;
                g++;
                while (sched < t && g < 400) {
                    rows.add(new long[] {Math.round(t), 'c'});
                    t += 2 * MS;
                    sched += 50 * MS;
                    g++;
                }
                t = Math.max(t, sched);
                continue;
            }
            rows.add(new long[] {Math.round(t + rng.nextGaussian() * MS), 'o'});
            t += 50 * MS;
            sched += 50 * MS;
            g++;
        }
        long[] start = new long[rows.size()];
        char[] kind = new char[rows.size()];
        long[] nominal = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            start[i] = rows.get(i)[0];
            kind[i] = (char) rows.get(i)[1];
            nominal[i] = NOMINAL;
        }
        return new Scenario(start, kind, nominal);
    }

    private static TickRecord[] records(Scenario s) {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        TickRecord[] out = new TickRecord[s.start.length];
        for (int g = 0; g < s.start.length; g++) {
            out[g] =
                    schedule.onTickStart(new TickInput(g, s.start[g], s.nominal[g], false, false, false, false, false));
        }
        return out;
    }

    /** The worst error (ms) over arrivals inside ticks [from, to) that have a target. */
    private static double worst(Scenario s, TickRecord[] rec, int from, int to) {
        int stall = s.stall();
        double worst = 0;
        for (int g = from; g < to && g + 1 < s.start.length; g++) {
            long meas = s.start[g];
            long next = s.start[g + 1];
            for (double f : FRACTIONS) {
                long w = meas + Math.round(f * (next - meas));
                double conv = rec[g].arrivalMicros(w) / 1000.0;
                double target = s.kind[g] == 'o' && s.kind[g + 1] == 'o'
                        ? 50.0 * g + 50.0 * (w - meas) / (next - meas) // game time
                        : 50.0 * stall + (w - s.start[stall]) / MS; // in step with wall time
                worst = Math.max(worst, Math.abs(conv - target));
            }
        }
        return worst;
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 19, 15})
    void aSteadyTickRateConvertsWithinTenMsOfGameTime(int tps) {
        for (long seed = 1; seed <= 5; seed++) {
            Scenario s = steady(tps, seed);
            double worst = worst(s, records(s), 50, 399);
            assertTrue(worst <= 10, tps + " TPS seed " + seed + ": " + worst + " ms");
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {150, 300, 1000, 2000})
    void throughAStallAndItsCatchUpArrivalsKeepInStepWithWallTime(double stallMs) {
        for (long seed = 1; seed <= 5; seed++) {
            Scenario s = stall(stallMs, seed);
            TickRecord[] rec = records(s);
            int st = s.stall();
            double worst = worst(s, rec, 50, s.start.length - 1);
            assertTrue(worst <= 10, stallMs + " ms stall seed " + seed + ": " + worst + " ms");
            for (int g = st + 1; s.kind[g] != 'o'; g++) { // the stalled tick and the catch-up don't count
                assertEquals(rec[st].tickLengthNanos(), rec[g].tickLengthNanos(), 0.0, "T̄ moved at tick " + g);
            }
        }
    }

    /**
     * A tick rate that drops from 20 to 15 mid-fight can't be followed at once: the first long tick is known to be long
     * only when it ends. Its first ticks convert up to about 23 ms off, then within 10 ms.
     */
    @Test
    void aStepFrom20To15TpsSettlesWithinTenTicks() {
        for (long seed = 1; seed <= 5; seed++) {
            Scenario s = step(seed);
            TickRecord[] rec = records(s);
            assertTrue(worst(s, rec, 50, 199) <= 10, "before the step, seed " + seed);
            double first = worst(s, rec, 199, 210);
            assertTrue(first <= 25, "the step's first ticks, seed " + seed + ": " + first + " ms");
            double after = worst(s, rec, 210, 399);
            assertTrue(after <= 10, "after the step, seed " + seed + ": " + after + " ms");
            assertEquals(1000.0 / 15, rec[399].tickLengthNanos() / MS, 0.5);
        }
    }

    @Test
    void aNominalChangeResetsTheTickLengthAtOnce() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long t = 0;
        for (int g = 0; g < 30; g++) {
            schedule.onTickStart(TickInput.normal(g, t));
            t += NOMINAL;
        }
        long slow = 66_666_667L;
        TickRecord r = schedule.onTickStart(new TickInput(30, t, slow, false, false, false, false, false));
        assertEquals((double) slow, r.tickLengthNanos(), 0.0); // the interval that straddled the change isn't counted
        TickRecord next = schedule.onTickStart(new TickInput(31, t + slow, slow, false, false, false, false, false));
        assertEquals((double) slow, next.tickLengthNanos(), 1.0);
    }

    @Test
    void whileFrozenArrivalsPinToTheTickAndTheTickLengthHolds() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long t = 0;
        TickRecord before = null;
        for (int g = 0; g < 30; g++) {
            before = schedule.onTickStart(TickInput.normal(g, t));
            t += NOMINAL;
        }
        for (int i = 0; i < 40; i++) { // game time stands at 30; the server keeps ticking
            TickRecord frozen = schedule.onTickStart(new TickInput(30, t, NOMINAL, true, false, false, false, false));
            assertEquals(Micros.ofTicks(30), frozen.arrivalMicros(t + 37_000_000L));
            assertEquals(before.tickLengthNanos(), frozen.tickLengthNanos(), 0.0);
            t += NOMINAL;
        }
        TickRecord resumed = schedule.onTickStart(TickInput.normal(31, t)); // unflagged: detected as a resume
        assertEquals((double) t, resumed.startNanos(), 0.0);
        assertEquals(Micros.ofTicks(31) + 25_000, resumed.arrivalMicros(t + 25_000_000L));
    }

    /**
     * The integrated server's pause skips both tick events, so the first tick back looks like the next tick come 5 s
     * late. Its gap since the previous tick's end shows it was a pause, not a stall: the schedule re-anchors.
     */
    @Test
    void aPauseThatSkippedTheTickEventsIsSeenAsAResume() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long t = 0;
        for (int g = 0; g < 30; g++) {
            schedule.onTickStart(new TickInput(g, t, NOMINAL, false, false, false, false, false, t - 40_000_000L, 0));
            t += NOMINAL;
        }
        long lastEnd = t - NOMINAL + 10_000_000L; // tick 29 took 10 ms
        long back = t + 5_000_000_000L;
        TickRecord r =
                schedule.onTickStart(new TickInput(30, back, NOMINAL, false, false, false, false, false, lastEnd, 0));
        assertEquals((double) back, r.startNanos(), 0.0);
        assertEquals(Micros.ofTicks(30) + 25_000, r.arrivalMicros(back + 25_000_000L));
        assertEquals(0, r.epoch());
    }

    /** A stalled tick ends late, and the next starts at once: no resume, so arrivals keep in step with wall time. */
    @Test
    void aStallIsNotMistakenForAResume() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long t = 0;
        TickRecord last = null;
        for (int g = 0; g < 30; g++) {
            last = schedule.onTickStart(
                    new TickInput(g, t, NOMINAL, false, false, false, false, false, t - 40_000_000L, 0));
            t += NOMINAL;
        }
        long stallStart = t - NOMINAL;
        long stallEnd = stallStart + 600_000_000L; // tick 29 ran 600 ms
        TickRecord r = schedule.onTickStart(new TickInput(
                30, stallEnd + 100_000L, NOMINAL, false, false, false, false, false, stallEnd, 550_100_000L));
        assertEquals(last.startNanos() + last.tickLengthNanos(), r.startNanos(), 1.0);
        assertNear(Micros.ofTicks(29) + 600_100, r.arrivalMicros(stallEnd + 100_000L), 2_000);
    }

    /**
     * {@code /tick sprint} runs ticks 2 ms apart. Steered as ordinary ticks, each would move the schedule a whole T̄
     * on (10 s of sprint left it about 240 s ahead); it follows the measured starts instead, T̄ holds, and the first
     * tick after the sprint re-anchors.
     */
    @Test
    void aSprintNeitherRunsTheScheduleAheadNorMovesTheTickLength() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long t = 0;
        int g = 0;
        for (; g < 30; g++) {
            schedule.onTickStart(TickInput.normal(g, t));
            t += NOMINAL;
        }
        for (; g < 5030; g++) { // 5,000 sprint ticks in 10 s
            TickRecord r = schedule.onTickStart(new TickInput(g, t, NOMINAL, false, false, true, false, false));
            assertEquals((double) t, r.startNanos(), 0.0);
            assertEquals(NOMINAL, r.tickLengthNanos(), 0.0);
            t += 2_000_000L;
        }
        TickRecord after = schedule.onTickStart(TickInput.normal(g, t));
        assertEquals((double) t, after.startNanos(), 0.0);
        assertEquals(Micros.ofTicks(g) + 25_000, after.arrivalMicros(t + 25_000_000L));
    }

    @Test
    void aBacklogDropReanchorsTheScheduleAndCountsAnEpoch() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long t = 0;
        for (int g = 0; g < 30; g++) {
            schedule.onTickStart(TickInput.normal(g, t));
            t += NOMINAL;
        }
        t += 3_000_000_000L; // a 3 s stall: vanilla drops its backlog
        TickRecord r = schedule.onTickStart(new TickInput(30, t, NOMINAL, false, false, false, true, false));
        assertEquals(1, r.epoch());
        assertEquals(1, schedule.epoch());
        assertEquals((double) t, r.startNanos(), 0.0);
        assertEquals(Micros.ofTicks(30), r.arrivalMicros(t));
    }

    @Test
    void aRecordIsPushedWhenItsRateOrStateMoves() {
        TickRecord sent = new TickRecord(10, 0, 50_000_000, false, false, false, 0);
        assertTrue(new TickRecord(11, 0, 50_000_000, false, false, false, 0).movedFrom(null, 0.01));
        assertEquals(false, new TickRecord(11, 0, 50_400_000, false, false, false, 0).movedFrom(sent, 0.01));
        assertTrue(new TickRecord(11, 0, 50_600_000, false, false, false, 0).movedFrom(sent, 0.01));
        assertTrue(new TickRecord(11, 0, 50_000_000, true, false, false, 0).movedFrom(sent, 0.01));
        assertTrue(new TickRecord(11, 0, 50_000_000, false, false, false, 1).movedFrom(sent, 0.01));
        TickRecord sprinting = new TickRecord(11, 0, 50_000_000, false, false, true, 0);
        assertTrue(sprinting.movedFrom(sent, 0.01));
        assertEquals(false, new TickRecord(12, 0, 5_000_000, false, false, true, 0).movedFrom(sprinting, 0.01));
    }
}
