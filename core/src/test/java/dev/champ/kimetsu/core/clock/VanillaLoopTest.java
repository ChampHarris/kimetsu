package dev.champ.kimetsu.core.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.VanillaLoop.Model;
import dev.champ.kimetsu.core.clock.VanillaLoop.Run;
import java.util.Arrays;
import java.util.Random;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rule 1.2's schedule against a model of vanilla's run loop ({@link VanillaLoop}), so the lateness signal is what the
 * mod will read. The first cases wake the loop up to a ms late, as a process with a fine timer does (single player);
 * the rest wake it as Windows' default 15.625 ms timer does a headless server (D-095), where the schedule follows
 * vanilla's own while vanilla keeps up (D-096), and as that timer does when it misses an interrupt now and then (D-098).
 */
class VanillaLoopTest {
    private static final double MS = 1_000_000.0;
    private static final long N = 50_000_000L;
    private static final double[] FRACTIONS = {0.0, 0.25, 0.5, 0.75, 0.999};
    private static final int WARM = 200; // ticks before a Windows run is measured
    private static final int MEASURED = 4_000; // 200 s

    private static Run run(int ticks, IntToDoubleFunction workMs, IntToDoubleFunction packetsMs, long seed) {
        return VanillaLoop.run(ticks, Model.HALF_NORMAL, workMs, packetsMs, seed);
    }

    /**
     * The worst error (ms) over arrivals inside ticks [from, to): against wall time in step from {@code stallTick} for
     * ticks inside [stallTick, catchUpEnd), against game time elsewhere.
     */
    private static double worst(Run r, int from, int to, int stallTick, int catchUpEnd) {
        long[] pre = r.pre();
        double worst = 0;
        for (int g = from; g < to && g + 1 < pre.length; g++) {
            for (double f : FRACTIONS) {
                long w = pre[g] + Math.round(f * (pre[g + 1] - pre[g]));
                double conv = r.rec()[g].arrivalMicros(w) / 1000.0;
                double target = g >= stallTick && g < catchUpEnd
                        ? 50.0 * stallTick + (w - pre[stallTick]) / MS
                        : 50.0 * g + 50.0 * (w - pre[g]) / (pre[g + 1] - pre[g]);
                worst = Math.max(worst, Math.abs(conv - target));
            }
        }
        return worst;
    }

    /**
     * The worst error (ms) over arrivals inside ticks [from, to) against vanilla's own schedule, 50·g + (arrival − its
     * start for tick g): game time while vanilla keeps up, and wall time in step through a stall and its catch-up,
     * since vanilla's schedule moves on a tick length a tick through both.
     */
    private static double worstOnSchedule(Run r, int from, int to) {
        long[] pre = r.pre();
        double worst = 0;
        for (int g = from; g < to && g + 1 < pre.length; g++) {
            for (double f : FRACTIONS) {
                long w = pre[g] + Math.round(f * (pre[g + 1] - pre[g]));
                double conv = r.rec()[g].arrivalMicros(w) / 1000.0;
                worst = Math.max(worst, Math.abs(conv - (50.0 * g + (w - r.sched()[g]) / MS)));
            }
        }
        return worst;
    }

    /** The first tick after {@code from} that started on vanilla's schedule again (within 5 ms of it). */
    private static int caughtUp(Run r, int from) {
        int g = from + 1;
        while (g < r.late().length && r.late()[g] > 5 * MS) {
            g++;
        }
        return g;
    }

    /** The first tick after {@code from} that the loop waited for: on Windows a tick on schedule may be 15 ms late. */
    private static int waitedAgain(Run r, int from) {
        int g = from + 1;
        while (g < r.waited().length && !r.waited()[g]) {
            g++;
        }
        return g;
    }

    private static int firstDrop(Run r) {
        for (int g = 0; g < r.dropped().length; g++) {
            if (r.dropped()[g]) {
                return g;
            }
        }
        return -1;
    }

    private static double p95(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        return s[(int) (0.95 * s.length)];
    }

    @Test
    void anIdleServerConvertsWithinTenMs() {
        Run r = run(400, g -> 10, g -> 0.2, 1);
        assertTrue(worst(r, 50, 399, -1, -1) <= 10);
        assertEquals(N, r.rec()[399].tickLengthNanos(), MS);
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
            assertEquals(N, r.rec()[g].tickLengthNanos(), MS, "T̄ moved during the catch-up, tick " + g);
        }
    }

    /** A chat command takes 300 ms before tick 200's Pre: a stall between ticks, not a pause. */
    @Test
    void aStallBetweenTicksIsNotAResume() {
        Run r = run(400, g -> 10, g -> g == 200 ? 300 : 0.2, 3);
        int end = caughtUp(r, 200);
        double worst = worst(r, 50, 399, 199, end);
        assertTrue(worst <= 10, "worst " + worst + " ms");
        assertEquals(0, r.rec()[399].epoch());
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
        assertEquals(ms, r.rec()[230].tickLengthNanos() / MS, 2.0);
        double settled = worst(r, 215, 599, -1, -1);
        assertTrue(settled <= 10, ms + " ms ticks: " + settled + " ms after settling");
        double transition = worst(r, 195, 215, -1, -1);
        System.out.println(ms + " ms ticks: transition " + Math.round(transition) + " ms, settled "
                + Math.round(settled * 10) / 10.0 + " ms");
        assertTrue(transition <= 3.5 * (ms - 50), ms + " ms ticks: " + transition + " ms while it changed");
        boolean anyDrop = false;
        for (boolean d : r.dropped()) {
            anyDrop |= d;
        }
        assertTrue(anyDrop, "vanilla never dropped its backlog");
        assertEquals(0, r.rec()[599].epoch());
    }

    /** The rate comes back down when the overload ends. */
    @Test
    void anOverloadThatEndsIsLetGo() {
        Run r = run(800, g -> g >= 200 && g < 400 ? 90 : 10, g -> 0.2, 5);
        int end = caughtUp(r, 400);
        assertEquals(N, r.rec()[Math.min(799, end + 40)].tickLengthNanos(), 2 * MS);
        assertTrue(worst(r, Math.min(798, end + 40), 799, -1, -1) <= 10);
    }

    /**
     * A 3 s stall leaves vanilla far enough behind to drop its backlog: the schedule really jumps, an epoch. It
     * re-anchors on vanilla's own start for the tick, the measured start less its lateness (D-096; it used to take the
     * measured start, then steer toward vanilla's unannounced).
     */
    @Test
    void aStallVanillaGivesUpOnIsAnEpoch() {
        Run r = run(400, g -> g == 200 ? 3000 : 10, g -> 0.2, 6);
        int drop = firstDrop(r);
        assertTrue(drop > 200, "no drop");
        assertEquals(1, r.rec()[drop].epoch());
        assertEquals((double) (r.pre()[drop] - r.late()[drop]), r.rec()[drop].startNanos(), 0.0);
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
        assertEquals((double) slow, at.tickLengthNanos(), 0.0);
    }

    /**
     * The same with the lateness the mod reads: vanilla scheduled the tick at the old length, but the mod takes off the
     * new one, so a tick 2 ms late reads 18.7 ms (D-096's review). Not consecutive, it isn't on schedule: it predicts
     * at the old pace, unsteered.
     */
    @Test
    void theTickOfATickRateChangeIsNotOnSchedule() {
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        long late = 2_000_000L;
        TickRecord before = null;
        for (int g = 0; g < 30; g++) {
            long at = g * N + late;
            long end = g == 0 ? Long.MIN_VALUE : at - N + 1_200_000L;
            before = schedule.onTickStart(new TickInput(g, at, N, false, false, false, false, false, end, late));
        }
        long slow = 66_666_667L;
        long at = 30 * N + late;
        TickRecord r = schedule.onTickStart(
                new TickInput(30, at, slow, false, false, false, false, false, at - N + 1_200_000L, late + slow - N));
        assertEquals(before.startNanos() + N, r.startNanos(), 1.0);
        assertEquals((double) slow, r.tickLengthNanos(), 0.0);
    }

    /**
     * When an overload ends, vanilla runs off its backlog in fast ticks far behind its schedule, each less late than the
     * one before: catch-up, left out of T̄ and not steered (Rule 1.2). Only a tick within {@code late_ticks} of
     * vanilla's schedule (a whole tick, D-098) is on schedule.
     */
    @ParameterizedTest
    @EnumSource(Model.class)
    void theCatchUpAfterAnOverloadIsLeftOut(Model model) {
        double bar = Fixtures.clock().lateTicks() * N;
        for (long seed = 1; seed <= 5; seed++) {
            Run r = VanillaLoop.run(800, model, g -> g >= 200 && g < 400 ? 90 : 10, g -> 0.2, seed);
            int catchUp = 0;
            for (int g = 401; g < 800; g++) {
                if (r.late()[g] > bar && r.pre()[g] - r.pre()[g - 1] < N) {
                    catchUp++;
                    TickRecord last = r.rec()[g - 1];
                    String what = model + " seed " + seed + ", tick " + g;
                    assertEquals(last.tickLengthNanos(), r.rec()[g].tickLengthNanos(), 0.0, what);
                    assertEquals(last.startNanos() + last.tickLengthNanos(), r.rec()[g].startNanos(), 1.0, what);
                }
            }
            assertTrue(catchUp > 10, model + " seed " + seed + ": " + catchUp + " catch-up ticks");
        }
    }

    // ---- Windows' default timer: a headless server's ticks start 0–15.6 ms late, now and then a period or more later
    // when it misses an interrupt (D-095, D-096, D-098) ----

    /** The loads a Windows run is measured under. */
    enum Load {
        /** 1.2 ms of work a tick, a dedicated server's fight (D-095). */
        IDLE,
        /** The same, with 0–3 ms of packets before Pre. */
        BUSY,
        /** 40 ± 4 ms of work: the ticks often run past vanilla's schedule and start back to back. */
        HEAVY
    }

    /** {@link #WARM} + {@link #MEASURED} ticks under a Windows model and a load, its draws apart from the wake-ups'. */
    private static Run windowsRun(Model model, Load load, long seed) {
        int ticks = WARM + MEASURED;
        Random rng = new Random(1000 + seed);
        double[] work = new double[ticks];
        double[] packets = new double[ticks];
        for (int g = 0; g < ticks; g++) {
            work[g] = load == Load.HEAVY ? 36 + 8 * rng.nextDouble() : 1.2;
            packets[g] = load == Load.BUSY ? 3 * rng.nextDouble() : 0;
        }
        return VanillaLoop.run(ticks, model, g -> work[g], g -> packets[g], seed);
    }

    static Stream<Arguments> windowsLoads() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Model m : new Model[] {Model.WINDOWS_UNIFORM, Model.WINDOWS_TIMER, Model.WINDOWS_MISSED}) {
            for (Load l : Load.values()) {
                out.add(Arguments.of(m, l));
            }
        }
        return out.build();
    }

    /**
     * D-095: on a dedicated server T̄ read 52.4 ms against a true 50, since a tick that woke more than a fifth of a tick
     * late was late, which left the short interval after it out of T̄ but kept the long one before. While vanilla
     * keeps up, its own schedule is the tick length: T̄ stays nominal, no record is pushed for its noise, and a record
     * a second old (a client between pongs) still converts as the newest does. At half a tick, a missed interrupt
     * still made a tick late about once in 10 s, and the recheck's records were pushed for it (D-098).
     */
    @ParameterizedTest
    @MethodSource("windowsLoads")
    void windowsWakeUpsKeepTheTickLengthNominal(Model model, Load load) {
        double rateChange = Fixtures.clock().rateChange();
        for (long seed = 1; seed <= 5; seed++) {
            Run r = windowsRun(model, load, seed);
            TickRecord[] rec = r.rec();
            double[] off = new double[MEASURED];
            for (int g = WARM; g < WARM + MEASURED; g++) {
                off[g - WARM] = Math.abs(rec[g].tickLengthNanos() - N) / N;
            }
            int pushes = 0;
            TickRecord sent = rec[WARM];
            for (int g = WARM + 1; g < WARM + MEASURED; g++) {
                if (rec[g].movedFrom(sent, rateChange)) {
                    pushes++;
                    sent = rec[g];
                }
            }
            double[] stale = new double[(MEASURED - 21) * FRACTIONS.length];
            int n = 0;
            for (int g = WARM + 20; g < WARM + MEASURED - 1; g++) {
                for (double f : FRACTIONS) {
                    long w = r.pre()[g] + Math.round(f * (r.pre()[g + 1] - r.pre()[g]));
                    stale[n++] = Math.abs(rec[g - 20].arrivalMicros(w) - rec[g].arrivalMicros(w)) / 1000.0;
                }
            }
            double tbar = p95(off);
            double drift = p95(stale);
            String what = model + " " + load + " seed " + seed + ": T̄ off nominal p95 " + Math.round(tbar * 1e5) / 1e3
                    + "%, " + pushes + " pushes in " + MEASURED / 20 + " s, a 1 s old record p95 "
                    + Math.round(drift * 10) / 10.0 + " ms";
            System.out.println(what);
            assertTrue(tbar <= 0.005, what);
            assertTrue(pushes <= MEASURED / 1200, what); // at most once a minute
            assertTrue(drift <= 5, what);
        }
    }

    /**
     * A park that misses two interrupts wakes 31–47 ms late (D-098). After a tick that woke 15.6 ms late, the interval
     * into one that woke 40 ms late is 74.4 ms, inside the ordinary band: at three quarters of a tick that tick was
     * late, so the interval entered T̄, which moved 4.9% and pushed the record. Up to a whole tick late, while vanilla
     * keeps up, it's on schedule, and the schedule stays vanilla's (Champ's pick, D-098).
     */
    @Test
    void aTickNearlyATickLateIsStillOnSchedule() {
        int[] woken = {0};
        Run r = VanillaLoop.run(
                200,
                (end, deadline) -> deadline + (++woken[0] == 100 ? 40 : 15.6) * MS,
                g -> 1.2,
                g -> 0,
                turn -> false);
        assertEquals(40 * MS, (double) r.late()[100], 1.0);
        assertEquals(74.4 * MS, (double) (r.pre()[100] - r.pre()[99]), 1.0);
        for (int g = 1; g < 200; g++) {
            assertEquals((double) N, r.rec()[g].tickLengthNanos(), 0.0, "T̄ moved, tick " + g);
            assertEquals((double) r.sched()[g], r.rec()[g].startNanos(), 1.0, "off vanilla's schedule, tick " + g);
        }
    }

    /**
     * A pause that skipped the tick events (the dedicated server empty, the integrated server paused) keeps vanilla's
     * schedule and its waits, so the first tick back starts on that schedule: here 15 ms late, as Windows' timer may
     * wake it. It re-anchors on vanilla's start with no epoch. At a fifth of a tick it read as late, so as a stall, and
     * the conversion ran the pause's 5 s ahead.
     */
    @Test
    void aWindowsResumeStillReanchors() {
        Run r = VanillaLoop.run(
                60, (end, deadline) -> deadline + 15 * MS, g -> 1.2, g -> 0, turn -> turn >= 30 && turn < 130);
        assertEquals(15 * MS, (double) r.late()[30], 1.0);
        assertTrue(r.pre()[30] - r.post()[29] > 5000 * MS, "the pause");
        TickRecord back = r.rec()[30];
        assertEquals((double) r.sched()[30], back.startNanos(), 0.0);
        assertEquals(0, back.epoch());
        double worst = worstOnSchedule(r, 30, 59);
        assertTrue(worst <= 1, "worst after the pause " + worst + " ms");
    }

    /**
     * Rule 1.2's old check, a ping handled during tick n converts into [50n, 50n + 50), can't hold on vanilla's
     * schedule: an arrival in the lead-in of a tick that started late converts up to its lateness past 50n + 50 (D-096's
     * review). What holds instead: the records of ticks g and g + 1 agree where they meet, so the conversion never steps
     * back, and an arrival between their starts lands in [50g, 50g + 50 + {@code late_ticks} of a tick). When tick g + 1
     * started later than that, it is late (vanilla a whole tick behind; here a park that missed three interrupts,
     * D-098), the schedule moves on by wall time, and the span runs to that tick's start.
     */
    @ParameterizedTest
    @MethodSource("windowsLoads")
    void theConversionIsContinuousAcrossRecords(Model model, Load load) {
        long lead = Math.round(Fixtures.clock().lateTicks() * Micros.PER_TICK);
        for (long seed = 1; seed <= 5; seed++) {
            Run r = windowsRun(model, load, seed);
            TickRecord[] rec = r.rec();
            long[] pre = r.pre();
            double seam = 0;
            int back = 0;
            int outside = 0;
            int lateTicks = 0;
            long previous = Long.MIN_VALUE;
            for (int g = WARM; g < WARM + MEASURED - 1; g++) {
                double at = pre[g + 1];
                seam = Math.max(seam, Math.abs(unrounded(rec[g], at) - unrounded(rec[g + 1], at)));
                long nextLate = Math.round(r.late()[g + 1] / 1000.0);
                lateTicks += nextLate > lead ? 1 : 0;
                long span = Micros.PER_TICK + Math.max(lead, nextLate);
                for (double f : FRACTIONS) {
                    long c = rec[g].arrivalMicros(pre[g] + Math.round(f * (pre[g + 1] - pre[g])));
                    back += c < previous ? 1 : 0;
                    outside += c < Micros.ofTicks(g) || c >= Micros.ofTicks(g) + span ? 1 : 0;
                    previous = c;
                }
            }
            String what = model + " " + load + " seed " + seed + ": records disagree by up to "
                    + Math.round(seam * 1000) / 1000.0 + " µs where they meet; " + back + " steps back, " + outside
                    + " arrivals outside their tick's span; " + lateTicks + " late ticks";
            System.out.println(what);
            assertTrue(seam <= 1, what);
            assertEquals(0, back, what);
            assertEquals(0, outside, what);
        }
    }

    private static double unrounded(TickRecord r, double atNanos) {
        return TickRecord.combatMicros(r.tick(), r.startNanos(), r.tickLengthNanos(), atNanos);
    }

    /**
     * The GameTest server never waits for its schedule ({@code GameTestServer.waitUntilNextTick} only runs its tasks),
     * so its ticks start ever further ahead of vanilla's. Ahead isn't on schedule: they keep the measured rate as before
     * D-096, which a GameTest's tick scale reads (D-076).
     */
    @Test
    void ticksAheadOfVanillasScheduleKeepTheMeasuredRate() {
        Run r = VanillaLoop.run(200, (end, deadline) -> end, g -> 9.8, g -> 0.2, turn -> false);
        assertTrue(r.late()[199] < -5000 * MS, "ahead by " + r.late()[199] / MS + " ms");
        assertEquals(10 * MS, r.rec()[199].tickLengthNanos(), 0.1 * MS);
        assertEquals((double) r.pre()[199], r.rec()[199].startNanos(), 0.1 * MS);
    }

    // ---- guards: the cases above under Windows' timer, against vanilla's schedule while it keeps up ----

    /** {@link #catchUpTicksInsideTheBandAreLeftOut} with Windows wake-ups. */
    @ParameterizedTest
    @EnumSource(
            value = Model.class,
            names = {"WINDOWS_UNIFORM", "WINDOWS_TIMER", "WINDOWS_MISSED"})
    void underWindowsAStallAndItsCatchUpKeepVanillasSchedule(Model model) {
        for (long seed = 1; seed <= 5; seed++) {
            Run r = VanillaLoop.run(500, model, g -> g == 200 ? 1500 : g > 200 ? 30 : 10, g -> 0.2, seed);
            int end = waitedAgain(r, 200);
            assertTrue(end > 250, "caught up at " + end);
            double worst = worstOnSchedule(r, 50, 499);
            assertTrue(worst <= 10, model + " seed " + seed + ": worst " + worst + " ms");
            for (int g = 201; g < end; g++) {
                assertEquals(N, r.rec()[g].tickLengthNanos(), MS, "T̄ moved during the catch-up, tick " + g);
            }
        }
    }

    /** {@link #aStallBetweenTicksIsNotAResume} with Windows wake-ups. */
    @ParameterizedTest
    @EnumSource(
            value = Model.class,
            names = {"WINDOWS_UNIFORM", "WINDOWS_TIMER", "WINDOWS_MISSED"})
    void underWindowsAStallBetweenTicksIsNotAResume(Model model) {
        for (long seed = 1; seed <= 5; seed++) {
            Run r = VanillaLoop.run(400, model, g -> 10, g -> g == 200 ? 300 : 0.2, seed);
            double worst = worstOnSchedule(r, 50, 399);
            assertTrue(worst <= 10, model + " seed " + seed + ": worst " + worst + " ms");
            assertEquals(0, r.rec()[399].epoch());
        }
    }

    /** Overloads with the settled error each must keep to (ms). */
    static Stream<Arguments> overloads() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Model m : Model.values()) {
            out.add(Arguments.of(m, 60.0, 12.0));
            out.add(Arguments.of(m, 80.0, 10.0));
            out.add(Arguments.of(m, 120.0, 10.0));
        }
        return out.build();
    }

    /**
     * {@link #aSustainedSlowerRateIsFollowed} under every wake-up model, from a mild 60 ms overload up: back-to-back
     * ticks that start later each time are an overload, not vanilla keeping up, so the schedule follows the measured
     * starts. The error is against the measured starts. A 60 ms overload settles up to 11.0 ms off over these spreads,
     * as before D-096 (the overload's path is unchanged); without that check it settled 18.8 to 20.5 ms off. While the
     * overload sets in, the run detector's lag (as in the case above) adds to the lateness the schedule was anchored
     * on when it began: the last tick on schedule sits on vanilla's start, up to its lateness before its measured
     * start.
     */
    @ParameterizedTest
    @MethodSource("overloads")
    void anOverloadIsFollowedWhateverTheWakeUps(Model model, double ms, double settledMs) {
        for (long seed = 1; seed <= 5; seed++) {
            Random spread = new Random(40 + seed);
            double[] work = new double[600];
            for (int g = 0; g < 600; g++) {
                work[g] = g >= 200 ? ms + 3 * spread.nextGaussian() : 10;
            }
            Run r = VanillaLoop.run(600, model, g -> work[g], g -> 0.2, seed);
            double settled = worst(r, 215, 599, -1, -1);
            double transition = worst(r, 195, 215, -1, -1);
            double anchored = r.late()[200] / MS;
            String what = model + " " + ms + " ms ticks seed " + seed + ": transition " + Math.round(transition)
                    + " ms (anchored " + Math.round(anchored * 10) / 10.0 + " ms late), settled "
                    + Math.round(settled * 10) / 10.0 + " ms";
            System.out.println(what);
            assertEquals(ms, r.rec()[230].tickLengthNanos() / MS, 2.0, what);
            assertTrue(settled <= settledMs, what);
            assertTrue(transition <= 3.5 * (ms - 50) + anchored, what);
            assertTrue(firstDrop(r) >= 0, "vanilla never dropped its backlog");
            assertEquals(0, r.rec()[599].epoch(), what);
        }
    }

    /** {@link #anOverloadThatEndsIsLetGo} with Windows wake-ups: once vanilla waits again, its schedule. */
    @ParameterizedTest
    @EnumSource(
            value = Model.class,
            names = {"WINDOWS_UNIFORM", "WINDOWS_TIMER", "WINDOWS_MISSED"})
    void underWindowsAnOverloadThatEndsIsLetGo(Model model) {
        for (long seed = 1; seed <= 5; seed++) {
            Run r = VanillaLoop.run(800, model, g -> g >= 200 && g < 400 ? 90 : 10, g -> 0.2, seed);
            int at = Math.min(798, waitedAgain(r, 400) + 40);
            assertEquals(N, r.rec()[at].tickLengthNanos(), 2 * MS, model + " seed " + seed);
            double worst = worstOnSchedule(r, at, 799);
            assertTrue(worst <= 10, model + " seed " + seed + ": worst " + worst + " ms");
        }
    }

    static Stream<Arguments> epochStalls() {
        Stream.Builder<Arguments> out = Stream.builder();
        for (Model m : Model.values()) {
            for (double ms : new double[] {3000, 3020, 3040}) {
                out.add(Arguments.of(m, ms));
            }
        }
        return out.build();
    }

    /**
     * {@link #aStallVanillaGivesUpOnIsAnEpoch} under every wake-up model, for stalls between the 50 ms grid's points
     * too (3,000 ms lines up with it and hides the drift): the epoch's record is what clients set their clocks by, so
     * the schedule must move on from it unpushed by less than a ms. Re-anchored on the measured start, it moved 20 to
     * 50 ms toward vanilla's start after a 3,020 or 3,040 ms stall (D-096's review).
     */
    @ParameterizedTest
    @MethodSource("epochStalls")
    void aStallVanillaGivesUpOnReanchorsOnItsSchedule(Model model, double stallMs) {
        for (long seed = 1; seed <= 5; seed++) {
            Run r = VanillaLoop.run(400, model, g -> g == 200 ? stallMs : 10, g -> 0.2, seed);
            int drop = firstDrop(r);
            assertTrue(drop > 200, "no drop");
            TickRecord epoch = r.rec()[drop];
            assertEquals(1, epoch.epoch());
            assertEquals((double) r.sched()[drop], epoch.startNanos(), 0.0);
            double shift = 0;
            for (int k = 1; k < 30; k++) {
                double extrapolated = epoch.startNanos() + k * epoch.tickLengthNanos();
                shift = Math.max(shift, Math.abs(r.rec()[drop + k].startNanos() - extrapolated) / MS);
            }
            String what = model + " " + stallMs + " ms stall seed " + seed + ": the schedule moved " + shift
                    + " ms from the epoch's record";
            assertTrue(shift <= 1, what);
            double worst = worstOnSchedule(r, drop + 1, 399);
            assertTrue(worst <= 10, what + "; worst " + worst + " ms");
        }
    }
}
