package dev.champ.kimetsu.core.judge;

import dev.champ.kimetsu.core.clock.Micros;

/**
 * One client's input rate limit (spec Numbers, "Input rate limit"; errata B10; Netcode, "Input frames"), run on the
 * network thread before any frame reaches the server's main thread, so a flood never does.
 *
 * <p>Two token buckets, one for frames and one for the presses and releases they carry, refill by the frames' own
 * stamps, converted to wall time at the server's tick rate: a stalled honest burst (frames stamped 50 ms apart that
 * arrive at once) passes, and a 15 TPS server doesn't throttle a client's 20 frames a second (netcode sim). A frame
 * credits time up to its stamp, held between its arrival − the stamp check's range (10 s) and its arrival + the stamp
 * check's tolerance ceiling: the stamp check clamps a stamp only on the main thread, so a client spacing forged stamps,
 * ahead or far back, can't buy tokens faster than wall time (netcode review), and stamps that step back buy nothing
 * until they pass the newest credited.
 *
 * <p>The first frame of the epoch of the record that converted its arrival (a claim past it counts as it) restarts the
 * baseline with no credit, since a clock that stepped back would otherwise buy nothing for as long as it stepped. Any
 * other epoch but the one it credited last is a timeline that's gone, older or skipped: the frame credits nothing and
 * leaves the baseline where it was. So a client switching or counting up the epoch it claims gets at most one restart
 * for each of the server's epochs, each at most a bucket from a stamp it forged (netcode reviews). While ticking is
 * frozen or paused stamps stand still, so it meters by arrival; a switch between the two credits the server's own time
 * since the frame before and never moves the stamps' baseline back, so ticking frozen and stepped a tick at a time
 * neither starves a client nor sells it a bucket a step (at most twice wall time, while an operator steps).
 *
 * <p>A frame passes with a frame token and, if it carries any, one event token; it then takes all its events, going
 * into debt if they're more than the bucket holds, so a big merge always gets through and later frames wait while the
 * debt is paid. Apart from that, it counts every message's arrival: more than {@code floodPerS} in each of
 * {@code floodSeconds} seconds in a row is a flood, and stays one. Not thread-safe: one per connection.
 */
public final class FrameMeter {
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long NONE = Long.MIN_VALUE;

    /**
     * The limits (SYNCED config from slice 5d, spec Config; until then {@link #PROPOSED}).
     *
     * @param framesPerS frames a second, refilled by the frames' stamps
     * @param frameBurst the frames that may come at once
     * @param eventsPerS presses and releases a second
     * @param eventBurst the presses and releases that may come at once
     * @param floodPerS raw messages a second past which a second counts toward a flood
     * @param floodSeconds how many such seconds in a row make a flood
     */
    public record Limits(
            double framesPerS, int frameBurst, double eventsPerS, int eventBurst, int floodPerS, int floodSeconds) {
        /** The spec's PROPOSED values (Numbers; B10): 40 frames a second, burst 20; 40 events; 200 a second for 5 s. */
        public static final Limits PROPOSED = new Limits(40, 20, 40, 40, 200, 5);

        public Limits {
            if (!(framesPerS > 0) || !(eventsPerS > 0)) {
                throw new IllegalArgumentException("rates must be positive: " + framesPerS + ", " + eventsPerS);
            }
            if (frameBurst < 1 || eventBurst < 1 || floodPerS < 1 || floodSeconds < 1) {
                throw new IllegalArgumentException("bursts and the flood limit must be at least 1");
            }
        }
    }

    private final Limits limits;
    private double frames;
    private double events;
    private boolean started;
    private int epoch;
    private boolean byArrival;
    private long lastCredit = NONE; // the stamps' baseline: none until a stamped frame of the epoch
    private long lastNanos;
    private long windowStart = NONE;
    private int windowCount;
    private int overSeconds; // full seconds over the flood limit in a row, before the current one
    private boolean flooding;

    public FrameMeter(Limits limits) {
        this.limits = limits;
        this.frames = limits.frameBurst();
        this.events = limits.eventBurst();
    }

    /**
     * A frame arrived: the time it credits. {@code stampMicros} is its send stamp and {@code arrivalMicros} its
     * arrival, in combat time; {@code leadMicros} the stamp check's tolerance ceiling; {@code tickLengthNanos} the
     * server's tick length (T̄); {@code arrivalNanos} the arrival on the server's own clock; {@code byArrival} whether
     * ticking is frozen or paused; {@code claimedEpoch} the epoch the frame claims and {@code recordEpoch} the epoch of
     * the record that converted its arrival (records' epochs only rise).
     */
    public void credit(
            long stampMicros,
            long arrivalMicros,
            long leadMicros,
            double tickLengthNanos,
            long arrivalNanos,
            boolean byArrival,
            int claimedEpoch,
            int recordEpoch) {
        int epoch = Math.min(claimedEpoch, recordEpoch);
        if (started && (epoch < this.epoch || (epoch > this.epoch && epoch < recordEpoch))) {
            return; // an older epoch, or one between the last credited and the record's: no honest client claims it
        }
        boolean restart = !started || epoch > this.epoch;
        boolean switched = !restart && byArrival != this.byArrival;
        started = true;
        this.epoch = epoch;
        this.byArrival = byArrival;
        if (restart) {
            lastCredit = NONE; // a new timeline, even while ticking is frozen
        }
        double seconds = 0;
        if ((byArrival || switched) && !restart && arrivalNanos > lastNanos) {
            seconds = (arrivalNanos - lastNanos) / (double) SECOND_NANOS; // the server's clock: no client sets it
        }
        if (!byArrival) {
            long t = Math.clamp(stampMicros, arrivalMicros - StampCheck.STAMP_RANGE_MICROS, arrivalMicros + leadMicros);
            if (lastCredit == NONE) {
                lastCredit = t;
            } else if (switched) {
                lastCredit = Math.max(lastCredit, t); // only an epoch moves the timeline back
            } else if (t > lastCredit) {
                seconds = (t - lastCredit) * (tickLengthNanos / Micros.PER_TICK) / SECOND_NANOS;
                lastCredit = t;
            }
        }
        lastNanos = arrivalNanos;
        frames = Math.min(limits.frameBurst(), frames + limits.framesPerS() * seconds);
        events = Math.min(limits.eventBurst(), events + limits.eventsPerS() * seconds);
    }

    /** Whether the frame just credited, carrying {@code count} presses and releases, may go now; if so, it pays. */
    public boolean take(int count) {
        if (frames < 1 || (count > 0 && events < 1)) {
            return false;
        }
        charge(count);
        return true;
    }

    /** A frame carrying {@code count} goes whatever the buckets hold (a merge handed over at a tick start): it pays. */
    public void charge(int count) {
        frames -= 1;
        events -= count;
    }

    /** The frame tokens it holds: below zero, the debt the frames that went whatever it held left. */
    public double frames() {
        return frames;
    }

    /** The event tokens it holds: below zero, the debt the frames that went whatever it held left. */
    public double events() {
        return events;
    }

    /** Any message from the client arrived at {@code nowNanos}; returns whether the client is flooding. */
    public boolean arrived(long nowNanos) {
        if (windowStart == NONE || nowNanos - windowStart >= SECOND_NANOS) {
            boolean adjoining = windowStart != NONE && nowNanos - windowStart < 2 * SECOND_NANOS;
            overSeconds = adjoining && windowCount > limits.floodPerS() ? overSeconds + 1 : 0;
            windowStart = nowNanos;
            windowCount = 0;
        }
        windowCount++;
        if (windowCount > limits.floodPerS() && overSeconds + 1 >= limits.floodSeconds()) {
            flooding = true;
        }
        return flooding;
    }

    /** Whether it has flooded: it stays so. */
    public boolean flooding() {
        return flooding;
    }
}
