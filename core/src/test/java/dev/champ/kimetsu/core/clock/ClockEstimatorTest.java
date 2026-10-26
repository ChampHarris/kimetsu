package dev.champ.kimetsu.core.clock;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClockEstimatorTest {
    private static final long MS = 1_000_000L;
    private static final long BEAT = 650_000L; // Water's beat in combat µs

    // ---- exact cases: the server and the client share one wall clock, so combat µs = wall ns / 1000 at 20 TPS ----

    /** Tick G's record at 20 TPS, the server's combat clock shifted by {@code shiftMs} (positive: later). */
    private static TickRecord at20(long tick, long shiftMs) {
        return new TickRecord(tick, tick * 50.0 * MS - shiftMs * MS, 50.0 * MS, false, false, false, 0);
    }

    private static void pong(ClockEstimator est, long now, TickRecord record) {
        est.onPong(now - 10 * MS, now - 5 * MS, now - 5 * MS, now, record, BEAT);
    }

    /** The join burst: four pongs 50 ms apart, the last received at {@code now}. */
    private static ClockEstimator started(long now) {
        ClockEstimator est = new ClockEstimator(Fixtures.clock());
        for (long k = 3; k >= 0; k--) {
            long at = now - k * 50 * MS;
            pong(est, at, at20(at / (50 * MS), 0));
        }
        return est;
    }

    @Test
    void itWaitsForTheJoinBurstThenSnapsToTheLine() {
        ClockEstimator est = new ClockEstimator(Fixtures.clock());
        long now = 10_000 * MS;
        for (long k = 3; k >= 1; k--) {
            pong(est, now - k * 50 * MS, at20(199 - k, 0));
            assertFalse(est.ready(), "ready after " + (4 - k) + " pongs");
        }
        pong(est, now, at20(199, 0));
        assertTrue(est.ready());
        assertEquals(now / 1000, est.estimateMicros(now));
        assertEquals((now + 300 * MS) / 1000, est.estimateMicros(now + 300 * MS));
    }

    @Test
    void aRecordOvertakenOnTheWireIsIgnored() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        est.onRecord(later, at20(220, 0), BEAT);
        est.onRecord(later + MS, at20(219, 400), BEAT); // older, and it would have pulled the clock 400 ms on
        assertEquals((later + 500 * MS) / 1000, est.estimateMicros(later + 500 * MS));
    }

    @Test
    void aRecordFromANewEpochSetsTheClockEvenWithoutItsClockEpoch() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        TickRecord dropped = new TickRecord(221, 221 * 50.0 * MS + 3000 * MS, 50.0 * MS, false, false, false, 1);
        est.onRecord(later, dropped, BEAT); // the server lost 3 s of ticks against wall time
        assertEquals(later / 1000 - 3_000_000, est.estimateMicros(later));
    }

    @Test
    void behindItCatchesUpTenMsABeat() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        est.onRecord(later, at20(219, 100), BEAT); // the server's clock is 100 ms later than ours
        long base = later / 1000;
        assertEquals(base, est.estimateMicros(later));
        assertEquals(base + 650_000 + 10_000, est.estimateMicros(later + 650 * MS));
        assertEquals(base + 3_250_000 + 50_000, est.estimateMicros(later + 3250 * MS));
        assertEquals(base + 6_500_000 + 100_000, est.estimateMicros(later + 6500 * MS));
        assertEquals(base + 7_000_000 + 100_000, est.estimateMicros(later + 7000 * MS)); // caught up
    }

    @Test
    void aheadItRunsSlowerButNeverBackwards() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        est.onRecord(later, at20(219, -100), BEAT); // the server is 100 ms behind us
        long base = later / 1000;
        assertEquals(base + 650_000 - 10_000, est.estimateMicros(later + 650 * MS));
        long previous = Long.MIN_VALUE;
        for (long t = later; t < later + 8000 * MS; t += 5 * MS) {
            long v = est.estimateMicros(t);
            assertTrue(v >= previous, "stepped back at " + t);
            previous = v;
        }
        assertEquals(base + 8_000_000 - 100_000, est.estimateMicros(later + 8000 * MS));
    }

    @Test
    void moreThanABeatBehindItSnapsForward() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        est.onRecord(later, at20(219, 651), BEAT);
        assertEquals(later / 1000 + 651_000, est.estimateMicros(later));
    }

    @Test
    void moreThanABeatAheadItHoldsStillUntilTheLineCatchesUp() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        long e0 = est.estimateMicros(later);
        est.onRecord(later, at20(219, -700), BEAT);
        assertEquals(e0, est.estimateMicros(later));
        assertEquals(e0, est.estimateMicros(later + 500 * MS));
        assertEquals(e0 + 100_000, est.estimateMicros(later + 800 * MS));
    }

    @Test
    void onlyAnEpochSetsItBack() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        est.onRecord(later, at20(219, -5000), BEAT);
        assertEquals(later / 1000, est.estimateMicros(later)); // held, not stepped back
        est.onEpoch();
        long after = later + 100 * MS;
        est.onRecord(after, at20(221, -5000), BEAT);
        assertEquals(after / 1000 - 5_000_000, est.estimateMicros(after));
    }

    @Test
    void aFrozenRecordStopsItAndTickStepsAdvanceIt() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long at = now + 1000 * MS;
        est.onRecord(at, new TickRecord(219, 219 * 50.0 * MS, 50.0 * MS, true, false, false, 0), BEAT);
        assertTrue(est.frozen());
        long e = Micros.ofTicks(219); // the server's frozen time, exactly
        assertEquals(e, est.estimateMicros(at));
        assertEquals(e, est.estimateMicros(at + 5000 * MS));
        est.stepFrozen(50_000);
        assertEquals(e + 50_000, est.estimateMicros(at + 6000 * MS));
        long resume = at + 7000 * MS; // ticking resumes at tick 221
        est.onRecord(resume, new TickRecord(221, resume, 50.0 * MS, false, false, false, 0), BEAT);
        assertFalse(est.frozen());
        long v = est.estimateMicros(resume);
        // a tick behind the line, which slewing would take 3 s to close with every stamp early: it snaps forward
        assertEquals(Micros.ofTicks(221), v);
        assertTrue(est.estimateMicros(resume + 2000 * MS) > v);
    }

    @Test
    void leavingAFreezeAheadOfTheLine_holdsRatherThanStepsBack() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long at = now + 1000 * MS;
        est.onRecord(at, new TickRecord(219, 219 * 50.0 * MS, 50.0 * MS, true, false, false, 0), BEAT);
        est.stepFrozen(100_000); // two steps the server's record hasn't caught up with
        long e = Micros.ofTicks(219) + 100_000;
        long resume = at + 7000 * MS;
        est.onRecord(resume, new TickRecord(220, resume, 50.0 * MS, false, false, false, 0), BEAT);
        assertEquals(e, est.estimateMicros(resume)); // 50 ms ahead: never back
        long previous = e;
        for (long t = resume; t < resume + 2000 * MS; t += 5 * MS) {
            long v = est.estimateMicros(t);
            assertTrue(v >= previous, "stepped back at " + t);
            previous = v;
        }
    }

    @Test
    void aClientPauseIgnoresTheStaleRecordsPongsStillCarry() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long at = now + 1000 * MS;
        long e = est.estimateMicros(at);
        est.pause(at);
        pong(est, at + 2000 * MS, at20(219, 0)); // answered on the network thread mid-pause
        assertTrue(est.frozen());
        assertEquals(e, est.estimateMicros(at + 4000 * MS));
        est.resume();
        long back = at + 5000 * MS; // the server re-anchored at its first tick back
        est.onRecord(back, new TickRecord(220, back, 50.0 * MS, false, false, false, 0), BEAT);
        assertFalse(est.frozen());
        assertEquals(e, est.estimateMicros(back)); // it picks up where it stopped
        assertTrue(est.estimateMicros(back + 1000 * MS) > e);
    }

    /** The unpause burst is answered on the network thread before the first tick back publishes a new record. */
    @Test
    void afterAPauseTheRecordFromBeforeItIsIgnored() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now); // newest record: tick 200
        long at = now + 50 * MS;
        long e = est.estimateMicros(at);
        est.pause(at);
        est.resume();
        long back = at + 10_000 * MS; // a 10 s pause
        pong(est, back, at20(200, 0)); // still tick 200: it would put the clock 10 s on
        assertEquals(e, est.estimateMicros(back));
        est.onRecord(back + 50 * MS, new TickRecord(201, back + 50 * MS, 50.0 * MS, false, false, false, 0), BEAT);
        assertTrue(est.estimateMicros(back + 1000 * MS) < e + 2_000_000, "ran ahead after the pause");
    }

    @Test
    void aRecordHandledAfterLaterReadsNeverStepsBack() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long receipt = now + 1000 * MS;
        long read = est.estimateMicros(receipt + 40 * MS); // the client read a later time before handling it
        double slow = 1e9 / 15;
        double start = receipt - (receipt / 1000.0 - 10_000 - Micros.ofTicks(219)) * slow / Micros.PER_TICK;
        est.onRecord(receipt, new TickRecord(219, start, slow, false, false, false, 0), BEAT);
        assertTrue(est.estimateMicros(receipt + 40 * MS) >= read, "stepped back");
    }

    @Test
    void aClientWhoJoinsWhileTickingIsFrozenStillStarts() {
        ClockEstimator est = new ClockEstimator(Fixtures.clock());
        long now = 10_000 * MS;
        TickRecord frozen = new TickRecord(200, 200 * 50.0 * MS, 50.0 * MS, true, false, false, 0);
        for (long k = 3; k >= 0; k--) {
            pong(est, now - k * 50 * MS, frozen);
        }
        assertTrue(est.ready());
        assertEquals(Micros.ofTicks(200), est.estimateMicros(now));
        long go = now + 5000 * MS;
        est.onRecord(go, new TickRecord(201, go, 50.0 * MS, false, false, false, 0), BEAT);
        assertFalse(est.frozen());
        assertTrue(est.estimateMicros(go + 500 * MS) > Micros.ofTicks(200));
    }

    @Test
    void rightAfterARateChangeItCorrectsFourTimesFaster() {
        long now = 10_000 * MS;
        ClockEstimator est = started(now);
        long later = now + 1000 * MS;
        long e0 = est.estimateMicros(later);
        double slow = 1e9 / 15; // /tick rate 15
        // A record at 15 TPS whose line at `later` is 100 ms behind the estimate.
        double start = later - (e0 - 100_000 - Micros.ofTicks(219)) * slow / Micros.PER_TICK;
        est.onRecord(later, new TickRecord(219, start, slow, false, false, false, 0), BEAT);
        long beatWall = Math.round(BEAT * slow / Micros.PER_TICK); // one combat beat at 15 TPS, in ns
        long v = est.estimateMicros(later + beatWall);
        assertNear(e0 + BEAT - 40_000, v, 2);
    }

    // ---- the spec's bound: synthetic delays against a real tick schedule ----

    /** A downstream message: a pong (answering the ping sent at {@code ping}, which arrived at {@code arrive}) or a push. */
    private record Event(long send, boolean pong, long ping, long arrive, int tick) {}

    /**
     * The client's estimate minus the server's own conversion, sampled every 10 ms over 160 s, for one seed. Ticks
     * wake up to a ms late (half-normal), the tick rate follows {@code scenario}, the network has 150 ms RTT with
     * 10 ms of normal or lognormal jitter each way, the client's clock is offset and drifts up to 50 ppm, pings go
     * once a second and the server pushes its record whenever it moves. {@code hold} lets the server answer a ping up
     * to a tick late. Returns |error| per sample, in µs, and fails if the estimate ever stepped back.
     */
    private static double[] errors(String scenario, boolean lognormal, boolean hold, long seed) {
        Random rng = new Random(seed);
        long t0 = 1_000_000_000_000L;
        long dur = 180_000 * MS;
        List<Long> starts = new ArrayList<>();
        List<Long> nominals = new ArrayList<>();
        double ideal = t0;
        double last = t0;
        while (ideal < t0 + dur + 2000 * MS) {
            double since = ideal - t0;
            boolean overloaded = scenario.equals("load15")
                    || (scenario.equals("load") && since >= 60_000 * MS && since < 120_000 * MS);
            if (overloaded) { // back to back, each tick taking 67 ± 3 ms
                last += Math.max(20 * MS, (66.7 + 3 * rng.nextGaussian()) * MS);
                ideal = last;
                starts.add(Math.round(last));
                nominals.add(50 * MS); // no /tick rate: vanilla just can't keep up
                continue;
            }
            double n = switch (scenario) {
                case "19" -> 1e9 / 19;
                case "15" -> 1e9 / 15;
                case "step" -> since >= 60_000 * MS && since < 120_000 * MS ? 1e9 / 15 : 50.0 * MS;
                default -> 50.0 * MS;
            };
            ideal += n;
            last = Math.max(last, ideal + Math.abs(rng.nextGaussian()) * MS);
            starts.add(Math.round(last));
            nominals.add(Math.round(n));
        }
        int ticks = starts.size();
        long[] m = new long[ticks];
        TickRecord[] rec = new TickRecord[ticks];
        TickSchedule schedule = new TickSchedule(Fixtures.clock());
        for (int g = 0; g < ticks; g++) {
            m[g] = starts.get(g);
            rec[g] = schedule.onTickStart(new TickInput(g, m[g], nominals.get(g), false, false, false, false, false));
        }
        return errors(m, rec, lognormal, hold, rng);
    }

    /**
     * {@link #errors(String, boolean, boolean, long)} against a server whose ticks come from a model of vanilla's loop
     * waking as {@code model} says, 1.2 ms of work a tick ({@link VanillaLoop}): the schedule gets each tick's lateness
     * and the previous tick's end, as the mod gives it.
     */
    private static double[] errors(VanillaLoop.Model model, boolean lognormal, long seed) {
        VanillaLoop.Run r = VanillaLoop.run(3_700, model, g -> 1.2, g -> 0, seed); // 185 s
        return errors(r.pre(), r.rec(), lognormal, false, new Random(seed));
    }

    /** The client's part: tick g of the server started at {@code m[g]} and published {@code rec[g]}. */
    private static double[] errors(long[] m, TickRecord[] rec, boolean lognormal, boolean hold, Random rng) {
        long t0 = 1_000_000_000_000L;
        long dur = 180_000 * MS;
        int ticks = m.length;
        double theta = (rng.nextDouble() * 2 - 1) * 1e11;
        double rho = (rng.nextDouble() * 2 - 1) * 50e-6;

        List<Event> events = new ArrayList<>();
        long lastUp = Long.MIN_VALUE;
        for (long ping = t0; ping < t0 + dur; ping += 1000 * MS) {
            long arrive = Math.max(lastUp, ping + delay(rng, lognormal));
            lastUp = arrive;
            long answer = hold ? arrive + (long) (rng.nextDouble() * 50 * MS) : arrive;
            events.add(new Event(answer, true, ping, arrive, latest(m, answer)));
        }
        TickRecord sent = null;
        for (int g = 0; g < ticks; g++) {
            if (rec[g].movedFrom(sent, Fixtures.clock().rateChange())) {
                if (sent != null) {
                    events.add(new Event(m[g], false, 0, 0, g));
                }
                sent = rec[g];
            }
        }
        events.sort(Comparator.comparingLong(Event::send));
        long[] recv = new long[events.size()];
        long lastDown = Long.MIN_VALUE;
        for (int i = 0; i < events.size(); i++) {
            lastDown = Math.max(lastDown, events.get(i).send() + delay(rng, lognormal));
            recv[i] = lastDown;
        }

        ClockEstimator est = new ClockEstimator(Fixtures.clock());
        int next = 0;
        double[] out = new double[16_000];
        int n = 0;
        long previous = Long.MIN_VALUE;
        for (long t = t0 + 20_000 * MS; t < t0 + dur - 1000 * MS; t += 10 * MS) {
            while (next < events.size() && recv[next] <= t) {
                Event ev = events.get(next);
                long c3 = client(recv[next], t0, theta, rho);
                if (ev.pong()) {
                    est.onPong(client(ev.ping(), t0, theta, rho), ev.arrive(), ev.send(), c3, rec[ev.tick()], BEAT);
                } else {
                    est.onRecord(c3, rec[ev.tick()], BEAT);
                }
                next++;
            }
            if (!est.ready()) {
                continue;
            }
            long v = est.estimateMicros(client(t, t0, theta, rho));
            assertTrue(v >= previous, "the estimate stepped back at " + t);
            previous = v;
            int g = latest(m, t);
            double conv = TickRecord.combatMicros(g, rec[g].startNanos(), rec[g].tickLengthNanos(), (double) t);
            out[n++] = Math.abs(v - conv);
        }
        return Arrays.copyOf(out, n);
    }

    private static long client(long t, long t0, double theta, double rho) {
        return Math.round(t + theta + rho * (t - t0));
    }

    private static long delay(Random rng, boolean lognormal) {
        double z = lognormal
                ? (Math.exp(rng.nextGaussian()) - Math.exp(0.5)) / Math.sqrt((Math.E - 1) * Math.E)
                : rng.nextGaussian();
        return Math.max(MS, Math.round(75 * MS + 10 * MS * z));
    }

    private static int latest(long[] starts, long t) {
        int i = Arrays.binarySearch(starts, t);
        return i >= 0 ? i : Math.max(0, -i - 2);
    }

    private static double p95(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        return s[(int) (0.95 * s.length)];
    }

    private static double[] pooled(String scenario, boolean lognormal, boolean hold) {
        double[] all = new double[0];
        for (long seed = 1; seed <= 4; seed++) {
            double[] e = errors(scenario, lognormal, hold, seed);
            double[] joined = Arrays.copyOf(all, all.length + e.length);
            System.arraycopy(e, 0, joined, all.length, e.length);
            all = joined;
        }
        return all;
    }

    @ParameterizedTest
    @CsvSource({"20,false", "20,true", "19,false", "19,true", "15,false", "15,true", "step,false", "step,true"})
    void itStaysWithinSixMsP95(String scenario, boolean lognormal) {
        double[] e = pooled(scenario, lognormal, false);
        double p95 = p95(e);
        System.out.println(scenario + (lognormal ? " lognormal" : " normal") + " p95_us=" + Math.round(p95));
        assertTrue(p95 <= 6000, scenario + (lognormal ? " lognormal" : " normal") + ": p95 " + p95 + " µs");
    }

    /**
     * An overloaded server (ticks back to back, 67 ± 3 ms each, no {@code /tick rate}): combat time itself wanders
     * against wall time between records, so this is a degraded mode with a looser bound, for a step into the overload
     * and back and for one that lasts.
     */
    @ParameterizedTest
    @CsvSource({"load,false", "load,true", "load15,false", "load15,true"})
    void anOverloadedServerStaysWithinTwelveMsP95(String scenario, boolean lognormal) {
        double[] e = pooled(scenario, lognormal, false);
        double p95 = p95(e);
        double max = Arrays.stream(e).max().orElseThrow();
        System.out.println(scenario + (lognormal ? " lognormal" : " normal") + " p95_us=" + Math.round(p95) + " max_us="
                + Math.round(max));
        assertTrue(p95 <= 12_000, scenario + ": p95 " + p95 + " µs");
        assertTrue(max <= 100_000, scenario + ": max " + max + " µs");
    }

    @Test
    void throughAChangeOfTickRateItNeverStraysFiftyMs() {
        for (long seed = 1; seed <= 4; seed++) {
            double max = Arrays.stream(errors("step", false, false, seed)).max().orElseThrow();
            System.out.println("step seed " + seed + " max_us=" + Math.round(max));
            assertTrue(max <= 50_000, "seed " + seed + ": max " + max + " µs");
        }
    }

    @Test
    void aPongHeldUpToATickChangesNothing() {
        double p95 = p95(pooled("20", false, true));
        System.out.println("20 held pong p95_us=" + Math.round(p95));
        assertTrue(p95 <= 6000, "p95 " + p95 + " µs");
    }

    /**
     * A dedicated server on Windows' default 15.6 ms timer (D-095): its ticks start 0–15.6 ms late, now and then a
     * period or more later (D-098), and the client still stays within the spec's 6 ms p95 at 10 ms of network jitter.
     * Before the schedule followed vanilla's own (D-096), T̄ read about 52.5 ms there and the client's clock lagged
     * 10–53 ms; at half a tick late, the missed interrupts left it 8.6–9.0 ms off at p95.
     */
    @ParameterizedTest
    @CsvSource({
        "WINDOWS_UNIFORM,false",
        "WINDOWS_UNIFORM,true",
        "WINDOWS_TIMER,false",
        "WINDOWS_TIMER,true",
        "WINDOWS_MISSED,false",
        "WINDOWS_MISSED,true"
    })
    void againstAServerOnWindowsTimerItStaysWithinSixMsP95(VanillaLoop.Model model, boolean lognormal) {
        double[] all = new double[0];
        for (long seed = 1; seed <= 4; seed++) {
            double[] e = errors(model, lognormal, seed);
            double[] joined = Arrays.copyOf(all, all.length + e.length);
            System.arraycopy(e, 0, joined, all.length, e.length);
            all = joined;
        }
        double p95 = p95(all);
        System.out.println(model + (lognormal ? " lognormal" : " normal") + " p95_us=" + Math.round(p95));
        assertTrue(p95 <= 6000, model + (lognormal ? " lognormal" : " normal") + ": p95 " + p95 + " µs");
    }
}
