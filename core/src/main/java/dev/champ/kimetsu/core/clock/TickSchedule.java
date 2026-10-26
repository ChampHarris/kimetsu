package dev.champ.kimetsu.core.clock;

import dev.champ.kimetsu.core.data.ClockRules;

/**
 * The server's own tick schedule (Rule 1.2). Vanilla's {@code getNextTickTime()} can't serve: it advances one tick
 * length per tick whether or not the tick ran on time, so under sustained lag arrivals converted against it run up to
 * 2 s ahead of game time. This schedule puts tick G at S_G = S_{G−1} + T̄ and steers it toward the tick starts on
 * ordinary ticks only, so it follows a change of tick rate but not a stall.
 *
 * <p>While vanilla keeps up, its own schedule is the truth (D-096). A tick is on schedule when its lateness against
 * vanilla's schedule is known and within {@code lateTicks} of a tick (but not ahead of it), it follows the previous
 * tick directly, vanilla didn't drop its backlog on it, and it isn't a back-to-back tick (the previous one ended after
 * this one was due) that started later than the one before, which is an overload growing. On schedule, the interval
 * counts as the nominal length and S steers toward vanilla's scheduled start, the measured start less its lateness.
 * A headless server on Windows keeps the default 15.6 ms timer, so its ticks wake 0–15.6 ms late: counting the
 * measured intervals, T̄ read 52.4 ms against a true 50 (D-095).
 *
 * <p>Otherwise T̄ is the mean of the last few ordinary intervals between tick starts. A mean, not a median: start-time
 * jitter cancels in a mean of consecutive intervals, and a median's error drifts the schedule through the catch-up
 * after a stall (12 to 18 ms after a 2 s stall in the design sim, against 4 ms for the mean). An interval is ordinary
 * when it follows the previous tick directly, lies within {@code ordinaryLow}–{@code ordinaryHigh} × T̄, and isn't
 * catch-up: shorter than the nominal length with vanilla more than {@code lateTicks} behind its own schedule at either
 * end. Through a stall and its catch-up the schedule just moves on by T̄ a tick, in step with wall time, as vanilla's
 * own schedule does. Ticks that start ahead of vanilla's schedule (the GameTest server never waits) take this path.
 *
 * <p>A slower rate the band can't reach in one step (ticks going from 50 to 80 ms) shows as a run of
 * {@code rateRunTicks} consecutive intervals outside it that aren't catch-up and agree within {@code ordinaryHigh} of
 * each other: T̄ takes their mean and steering resumes. A stall is one long interval with catch-up after it, so it
 * never makes a run. Frozen, paused or sprinting, the schedule follows the measured starts, and the first tick after
 * re-anchors it, as does the first tick after paused turns that skipped the tick events (the dedicated server empty,
 * the integrated server paused): vanilla's schedule moves on a tick each turn of its loop, paused or not, so a tick
 * whose start on it moved on more than {@link #PAUSED_TURNS} ticks since the last one's, with no backlog drop, came
 * after paused turns, however late it started; a stall or an overload moves it a tick (D-099). Vanilla dropping its
 * backlog is an epoch only when it moves the schedule by more than the band: under a sustained overload the schedule
 * already tracks the measured starts, and nothing jumps. Re-anchoring, the schedule takes vanilla's start for the tick
 * wherever it's known, so the record an epoch announces is the one the schedule keeps to. New clock rules don't
 * re-anchor it: it carries on under them ({@link #retuned}).
 */
public final class TickSchedule {
    /**
     * Between one tick and two of vanilla's schedule: consecutive ticks are a tick apart on it, a tick after paused
     * turns two or more. Structural, not a tuning number: any bound strictly between gives the same verdict.
     */
    private static final double PAUSED_TURNS = 1.5;

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
    private long lastLate = Long.MIN_VALUE; // the previous tick's lateness, if known
    private long lastVanilla = Long.MIN_VALUE; // the previous tick's start on vanilla's schedule, if known

    public TickSchedule(ClockRules rules) {
        this(rules, 0);
    }

    private TickSchedule(ClockRules rules, int epoch) {
        this.rules = rules;
        this.intervals = new double[rules.tickWindow()];
        this.runs = new double[rules.rateRunTicks()];
        this.epoch = epoch;
    }

    /**
     * This schedule carried on under new clock rules (a reload): S, T̄ and the last tick's state as they are, the newest
     * intervals that fit the new window and the newest of a run under way that leave it a tick to go. It doesn't
     * re-anchor: a reload blocks the server thread, so its first tick starts late by the reload, and anchored there the
     * conversion jumped forward up to 0.75 s once vanilla caught up (D-100). It still counts an epoch (Rule 1.8), one
     * more than this one did (Champ, D-100, call a).
     */
    public TickSchedule retuned(ClockRules newRules) {
        TickSchedule s = new TickSchedule(newRules, epoch + 1);
        int keep = Math.min(count, s.intervals.length);
        for (int i = keep; i > 0; i--) { // written oldest first, so the oldest are overwritten first
            s.intervals[s.count++] = intervals[Math.floorMod(head - i, intervals.length)];
        }
        s.head = s.count % s.intervals.length;
        int run = Math.min(runCount, s.runs.length - 1);
        System.arraycopy(runs, runCount - run, s.runs, 0, run);
        s.runCount = run;
        s.tickLength = tickLength;
        s.nominal = nominal;
        s.schedule = schedule;
        s.lastTick = lastTick;
        s.lastMeasured = lastMeasured;
        s.started = started;
        s.wasOffPace = wasOffPace;
        s.wasLate = wasLate;
        s.lastLate = lastLate;
        s.lastVanilla = lastVanilla;
        return s;
    }

    /** Called at each tick start, before the record is published. */
    public TickRecord onTickStart(TickInput in) {
        boolean nominalChanged = started && in.nominalTickNanos() != nominal;
        double paceBefore = tickLength; // the interval ending now ran at the pace before
        long nominalBefore = nominal;
        if (!started || nominalChanged) {
            nominal = in.nominalTickNanos();
            tickLength = nominal;
            clearIntervals();
        }
        boolean offPace = in.frozen() || in.paused() || in.sprinting();
        boolean known = in.lateNanos() != Long.MIN_VALUE;
        boolean late = known && in.lateNanos() > rules.lateTicks() * nominal;
        long vanilla = known ? in.measuredNanos() - in.lateNanos() : Long.MIN_VALUE;
        boolean pausedTurns = started
                && known
                && lastVanilla != Long.MIN_VALUE
                && !in.backlogDropped()
                && vanilla - lastVanilla > PAUSED_TURNS * Math.max(nominal, nominalBefore);
        boolean resumed = in.resumed() || (wasOffPace && !offPace) || pausedTurns;
        wasOffPace = offPace;
        double predicted = schedule + (nominalChanged ? paceBefore : tickLength);
        boolean jumped = started
                && in.backlogDropped()
                && Math.abs(in.measuredNanos() - predicted) > rules.ordinaryHigh() * tickLength;
        boolean behind = known && in.lateNanos() >= 0;
        double vanillaStart = behind ? in.measuredNanos() - in.lateNanos() : in.measuredNanos();
        if (!started || resumed || jumped) {
            if (jumped) {
                epoch++;
            }
            schedule = vanillaStart;
            runCount = 0;
            started = true;
        } else if (offPace) {
            schedule = in.measuredNanos();
            runCount = 0;
        } else {
            double interval = in.measuredNanos() - lastMeasured;
            boolean consecutive = in.tick() == lastTick + 1 && !nominalChanged;
            boolean backToBack = in.previousEndNanos() != Long.MIN_VALUE && in.previousEndNanos() >= vanillaStart;
            boolean growing = backToBack && lastLate != Long.MIN_VALUE && in.lateNanos() > lastLate;
            boolean onSchedule = behind && !late && consecutive && !in.backlogDropped() && !growing;
            boolean catchUp = (late || wasLate) && interval < nominal; // behind at either end: shortened
            boolean inBand =
                    interval >= rules.ordinaryLow() * tickLength && interval <= rules.ordinaryHigh() * tickLength;
            if (onSchedule) { // vanilla keeps up: its own schedule is the tick length
                runCount = 0;
                addInterval((double) nominal);
                schedule = predicted + rules.steerGain() * (vanillaStart - predicted);
            } else if (consecutive && !catchUp && inBand) {
                runCount = 0;
                addInterval(interval);
                schedule = predicted + rules.steerGain() * (in.measuredNanos() - predicted);
            } else if (known && consecutive && !catchUp && joinRun(interval)) {
                tickLength = takeRun(); // a new rate: T̄ starts from the run, and steering resumes
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
        lastLate = in.lateNanos();
        lastVanilla = vanilla;
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
            if (hi > rules.ordinaryHigh() * lo) { // it disagrees with the run: a new run starts here
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
        double sum = 0; // summed afresh: a running sum would drift over a long session
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
