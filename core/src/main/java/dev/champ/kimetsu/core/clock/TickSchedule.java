package dev.champ.kimetsu.core.clock;

import dev.champ.kimetsu.core.data.ClockRules;

/**
 * The server's own tick schedule (Rule 1.2). Vanilla's {@code getNextTickTime()} can't serve: it advances one tick
 * length per tick whether or not the tick ran on time, so under sustained lag arrivals converted against it run up to
 * 2 s ahead of game time. This schedule puts tick G at S_G = S_{G−1} + T̄ and steers it toward the measured tick
 * starts on ordinary ticks only, so it follows a change of tick rate but not a stall.
 *
 * <p>T̄ is the mean of the last few ordinary intervals between tick starts. A mean, not a median: start-time jitter
 * cancels in a mean of consecutive intervals, and a median's error drifts the schedule through the catch-up after a
 * stall (12 to 18 ms after a 2 s stall in the design sim, against 4 ms for the mean). An interval is ordinary when it
 * follows the previous tick directly, lies within {@code ordinaryLow}–{@code ordinaryHigh} × T̄, and isn't catch-up:
 * shorter than the nominal length with vanilla behind its own schedule at either end. Through a stall and its
 * catch-up the schedule just moves on by T̄ a tick, in step with wall time, as vanilla's own schedule does.
 *
 * <p>A slower rate the band can't reach in one step (ticks going from 50 to 80 ms) shows as a run of
 * {@code rateRunTicks} consecutive intervals outside it that aren't catch-up and agree within {@code ordinaryHigh} of
 * each other: T̄ takes their mean and steering resumes. A stall is one long interval with catch-up after it, so it
 * never makes a run. Frozen, paused or sprinting, the schedule follows the measured starts, and the first tick after
 * re-anchors it, as does the first tick after a long gap that started on vanilla's schedule (a pause that skipped the
 * tick events). Vanilla dropping its backlog is an epoch only when it moves the schedule by more than the band: under
 * a sustained overload the schedule already tracks the measured starts, and nothing jumps.
 */
public final class TickSchedule {
    private final ClockRules rules;
    private final double[] intervals;
    private int count;
    private int head;
    private final double[] runs;
    private int runCount;
    private double tickLength;
    private long nominal;
    private double schedule;
    private long lastTick;
    private long lastMeasured;
    private int epoch;
    private boolean started;
    private boolean wasOffPace;
    private boolean wasLate;

    public TickSchedule(ClockRules rules) {
        this.rules = rules;
        this.intervals = new double[rules.tickWindow()];
        this.runs = new double[rules.rateRunTicks()];
    }

    /** Called at each tick start, before the record is published. */
    public TickRecord onTickStart(TickInput in) {
        boolean nominalChanged = started && in.nominalTickNanos() != nominal;
        double paceBefore = tickLength;                  // the interval ending now ran at the pace before
        if (!started || nominalChanged) {
            nominal = in.nominalTickNanos();
            tickLength = nominal;
            clearIntervals();
        }
        boolean offPace = in.frozen() || in.paused() || in.sprinting();
        boolean known = in.lateNanos() != Long.MIN_VALUE;
        boolean late = known && in.lateNanos() > rules.lateTicks() * nominal;
        boolean idleGap = started && !late && in.previousEndNanos() != Long.MIN_VALUE
                && in.measuredNanos() - in.previousEndNanos() > rules.ordinaryHigh() * tickLength;
        boolean resumed = in.resumed() || (wasOffPace && !offPace) || idleGap;
        wasOffPace = offPace;
        double predicted = schedule + (nominalChanged ? paceBefore : tickLength);
        boolean jumped = started && in.backlogDropped()
                && Math.abs(in.measuredNanos() - predicted) > rules.ordinaryHigh() * tickLength;
        if (!started || resumed || jumped) {
            if (jumped) {
                epoch++;
            }
            schedule = in.measuredNanos();
            runCount = 0;
            started = true;
        } else if (offPace) {
            schedule = in.measuredNanos();
            runCount = 0;
        } else {
            double interval = in.measuredNanos() - lastMeasured;
            boolean consecutive = in.tick() == lastTick + 1 && !nominalChanged;
            boolean catchUp = (late || wasLate) && interval < nominal;   // behind at either end: shortened
            boolean inBand = interval >= rules.ordinaryLow() * tickLength
                    && interval <= rules.ordinaryHigh() * tickLength;
            if (consecutive && !catchUp && inBand) {
                runCount = 0;
                addInterval(interval);
                schedule = predicted + rules.steerGain() * (in.measuredNanos() - predicted);
            } else if (known && consecutive && !catchUp && joinRun(interval)) {
                tickLength = takeRun();                  // a new rate: T̄ starts from the run, and steering resumes
                schedule = predicted + rules.steerGain() * (in.measuredNanos() - predicted);
            } else {
                if (catchUp || !consecutive) {
                    runCount = 0;
                }
                schedule = predicted;
            }
        }
        lastTick = in.tick();
        lastMeasured = in.measuredNanos();
        wasLate = late;
        return new TickRecord(in.tick(), schedule, tickLength, in.frozen(), in.paused(), in.sprinting(), epoch);
    }

    public int epoch() {
        return epoch;
    }

    /** Adds an out-of-band interval to the run; returns true once the run is long enough to be a new rate. */
    private boolean joinRun(double interval) {
        if (runCount > 0) {
            double lo = interval;
            double hi = interval;
            for (int i = 0; i < runCount; i++) {
                lo = Math.min(lo, runs[i]);
                hi = Math.max(hi, runs[i]);
            }
            if (hi > rules.ordinaryHigh() * lo) {        // it disagrees with the run: a new run starts here
                runCount = 0;
            }
        }
        runs[runCount++] = interval;
        return runCount == runs.length;
    }

    private double takeRun() {
        clearIntervals();
        for (int i = 0; i < runCount; i++) {
            addInterval(runs[i]);
        }
        runCount = 0;
        return tickLength;
    }

    private void addInterval(double interval) {
        intervals[head] = interval;
        head = (head + 1) % intervals.length;
        count = Math.min(count + 1, intervals.length);
        double sum = 0;                              // summed afresh: a running sum would drift over a long session
        for (int i = 0; i < count; i++) {
            sum += intervals[i];
        }
        tickLength = sum / count;
    }

    private void clearIntervals() {
        count = 0;
        head = 0;
    }
}
