package dev.champ.kimetsu.core.clock;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.ClockRules;
import java.util.Random;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

/**
 * A model of vanilla's run loop ({@code MinecraftServer.runServer}) for the clock's tests, so the lateness signal is
 * what the mod reads: each tick is scheduled one nominal length after the last; the loop waits for that schedule unless
 * the last tick ran past it, waking up as its {@link Wake} says; then it handles packets, fires Pre, runs the tick's
 * work and fires Post. More than 2 s behind, and 15 s since it last did, vanilla skips its schedule forward. A paused
 * turn of the loop (the dedicated server empty, the integrated server paused) keeps the schedule and its wait but fires
 * no tick event, and game time stands.
 *
 * <p>Between ticks the loop also runs chunk tasks while they keep coming, up to a tick past the last tick's end or to
 * the next tick's schedule, whichever is later ({@code delayedTasksMaxNextTickTimeNanos}, {@code MinecraftServer.java}
 * :761, :854–856, :895): a tick may then start late without the last one running past its schedule (D-099).
 */
final class VanillaLoop {
    static final double MS = 1_000_000.0;
    static final long N = 50_000_000L;
    /** Windows' default timer: 64 interrupts a second. */
    static final double WINDOWS_PERIOD = 15.625 * MS;

    private VanillaLoop() {}

    /** When the loop, its tick done at {@code end}, wakes for the next one, scheduled at the later {@code deadline}. */
    @FunctionalInterface
    interface Wake {
        double at(double end, double deadline);
    }

    /** The wake-up models, each seeded. */
    enum Model {
        /** A process with a fine timer, as the client asks for (so single player): up to a ms late, half-normal. */
        HALF_NORMAL,
        /** Windows' default timer, which a headless JVM keeps (it never asks for a finer one): up to a period late. */
        WINDOWS_UNIFORM,
        /**
         * The same timer as HotSpot meets it. Vanilla parks for the time left ({@code waitForTasks}); HotSpot on
         * Windows waits that in whole milliseconds, at least one, and the wait ends on the first interrupt at or after
         * it; woken early, vanilla parks again. The interrupts run on a grid with a seeded phase, up to 200 ppm off
         * the server's clock.
         */
        WINDOWS_TIMER,
        /**
         * The same timer as the 5d-1 recheck's JFR met it on a dedicated server (D-098): now and then a park returns
         * one, two or three periods after the interrupt it was due on, none of them beside a GC pause or a long
         * safepoint. So a tick on schedule may start up to 62.5 ms late.
         */
        WINDOWS_MISSED;

        Wake wake(long seed) {
            Random rng = new Random(seed);
            return switch (this) {
                case HALF_NORMAL -> (end, deadline) -> deadline + Math.abs(rng.nextGaussian()) * MS;
                case WINDOWS_UNIFORM -> (end, deadline) -> deadline + rng.nextDouble() * WINDOWS_PERIOD;
                case WINDOWS_TIMER, WINDOWS_MISSED -> {
                    double phase = rng.nextDouble() * WINDOWS_PERIOD;
                    double period = WINDOWS_PERIOD * (1 + (2 * rng.nextDouble() - 1) * 200e-6);
                    Random misses = new Random(~seed); // its own draws, so the grid is WINDOWS_TIMER's for the seed
                    boolean missing = this == WINDOWS_MISSED;
                    yield (end, deadline) -> {
                        double t = end;
                        while (t < deadline) {
                            double due = t + Math.max(1, Math.floor((deadline - t) / MS)) * MS;
                            t = phase + Math.ceil((due - phase) / period) * period;
                            t += missing ? missedPeriods(misses) * period : 0;
                        }
                        return t;
                    };
                }
            };
        }

        /** The recheck's 7,052 server-thread parks: 108 returned one period late, 15 two and 1 three (D-098). */
        private static int missedPeriods(Random rng) {
            int u = rng.nextInt(7_052);
            return u < 1 ? 3 : u < 16 ? 2 : u < 124 ? 1 : 0;
        }
    }

    /**
     * A run of the loop, by game tick: each tick's Pre and Post times (ns), how late it started against vanilla's own
     * schedule and that schedule, whether vanilla dropped its backlog on it, whether the loop waited before it, and the
     * schedule's records.
     */
    @SuppressWarnings("ArrayRecordComponent") // a run's series, read by index; never compared or hashed
    record Run(
            long[] pre,
            long[] post,
            long[] late,
            long[] sched,
            boolean[] dropped,
            boolean[] waited,
            TickRecord[] rec) {}

    /** The test fixture's clock rules with another {@code late_ticks}, for the values Champ weighs (D-099). */
    static ClockRules clock(double lateTicks) {
        ClockRules c = Fixtures.clock();
        return clock(c.syncIntervalCombatMs(), c.tickWindow(), lateTicks);
    }

    /**
     * The test fixture's clock rules with another client sync interval in combat, {@code tick_window} and
     * {@code late_ticks}: a reload's new rules (D-100). The schedule reads the last two, not the first.
     */
    static ClockRules clock(int syncIntervalCombatMs, int tickWindow, double lateTicks) {
        return clock(syncIntervalCombatMs, tickWindow, Fixtures.clock().rateRunTicks(), lateTicks);
    }

    /** The same with another {@code rate_run_ticks}. */
    static ClockRules clock(int syncIntervalCombatMs, int tickWindow, int rateRunTicks, double lateTicks) {
        ClockRules c = Fixtures.clock();
        return new ClockRules(
                c.syncSamples(),
                c.firstSamples(),
                c.slewMsPerBeat(),
                c.rateChangeSlewMsPerBeat(),
                c.rateSamples(),
                c.rateChange(),
                c.pushesPerS(),
                c.pushBurst(),
                c.tempoChangeLeadBeats(),
                syncIntervalCombatMs,
                c.syncIntervalIdleMs(),
                tickWindow,
                rateRunTicks,
                lateTicks,
                c.ordinaryLow(),
                c.ordinaryHigh(),
                c.steerGain());
    }

    /**
     * A clock that swaps its schedule for {@code schedule.retuned(rules)} at the start of tick {@code at}, as the combat
     * server does when a reload changed the clock rules ({@code CombatServer.tickStart}).
     */
    static Function<TickInput, TickRecord> reloadingAt(int at, ClockRules rules) {
        TickSchedule[] schedule = {new TickSchedule(Fixtures.clock())};
        return in -> {
            if (in.tick() == at) {
                schedule[0] = schedule[0].retuned(rules);
            }
            return schedule[0].onTickStart(in);
        };
    }

    /** {@code ticks} ticks of the loop under one wake-up model: tick g's work and the packets before its Pre, in ms. */
    static Run run(int ticks, Model model, IntToDoubleFunction workMs, IntToDoubleFunction packetsMs, long seed) {
        return run(ticks, model.wake(seed), workMs, packetsMs, turn -> false);
    }

    /** The same, waking as {@code wake} says, with the loop's turns {@code paused} (counted from 0) firing no event. */
    static Run run(
            int ticks, Wake wake, IntToDoubleFunction workMs, IntToDoubleFunction packetsMs, IntPredicate paused) {
        return run(ticks, wake, workMs, packetsMs, g -> 0, paused, new TickSchedule(Fixtures.clock())::onTickStart);
    }

    /**
     * The same, with the chunk tasks that come after tick g's Post ({@code chunksMs}, in ms; infinite while chunks keep
     * loading), and each tick's record from {@code clock} (a schedule, or one swapped for a reload).
     */
    static Run run(
            int ticks,
            Wake wake,
            IntToDoubleFunction workMs,
            IntToDoubleFunction packetsMs,
            IntToDoubleFunction chunksMs,
            IntPredicate paused,
            Function<TickInput, TickRecord> clock) {
        long[] pre = new long[ticks];
        long[] post = new long[ticks];
        long[] late = new long[ticks];
        long[] sched = new long[ticks];
        boolean[] dropped = new boolean[ticks];
        boolean[] waited = new boolean[ticks];
        TickRecord[] rec = new TickRecord[ticks];
        double now = 1e12;
        double next = now; // vanilla's nextTickTimeNanos
        double lastWarn = 0;
        long prevEnd = Long.MIN_VALUE;
        boolean wait = false;
        boolean drop = false;
        int g = 0;
        for (int turn = 0; g < ticks; turn++) {
            double behind = now - next;
            if (behind > 1e9 + 20.0 * N && next - lastWarn >= 10e9 + 100.0 * N) {
                next += Math.floor(behind / N) * N;
                lastWarn = next;
                drop = true; // the mod sees it at the next Pre, a paused turn's too
            }
            double thisSched = next;
            next += N;
            if (paused.test(turn)) { // tickServer returns before Pre
                wait = now < next;
                now = wait ? wake.at(now, next) : now;
                continue;
            }
            double p = now + packetsMs.applyAsDouble(g) * MS;
            double q = p + workMs.applyAsDouble(g) * MS;
            pre[g] = Math.round(p);
            post[g] = Math.round(q);
            late[g] = Math.round(p - thisSched);
            sched[g] = Math.round(thisSched);
            dropped[g] = drop;
            waited[g] = wait;
            rec[g] = clock.apply(new TickInput(g, pre[g], N, false, false, false, dropped[g], false, prevEnd, late[g]));
            prevEnd = post[g];
            drop = false;
            double tasksDone = q + chunksMs.applyAsDouble(g) * MS; // haveTime() runs them until a tick past q at least
            double deadline = Math.max(q + N, next);
            wait = tasksDone < next;
            now = wait ? wake.at(tasksDone, next) : Math.min(tasksDone, deadline);
            g++;
        }
        return new Run(pre, post, late, sched, dropped, waited, rec);
    }
}
