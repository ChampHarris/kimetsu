package dev.champ.kimetsu.core.clock;

import dev.champ.kimetsu.core.data.ClockRules;

/**
 * A client's estimate of the server's combat clock (Netcode, "The combat clock on clients").
 *
 * <p>It estimates the server's <em>wall</em> clock, then runs the server's own conversion (Rule 1.2) on it with the
 * latest {@link TickRecord}: combat time = 50·G + (client + θ − S_G) × 50/T̄. The offset θ is NTP's, the mean over the
 * lower-delay half of the last pongs; the minimum-delay sample alone does nothing against symmetric jitter. Wall clocks
 * on both machines run at the same rate to a few parts per million, so old samples stay valid through any change of
 * tick rate. Fitting combat time directly instead (the first design) projects every old sample at the newest T̄: with
 * 1 ms of tick-start jitter that alone is 20 ms p95, and a step from 20 to 15 TPS puts the clock seconds off.
 *
 * <p>T̄ is averaged over the recent records that agree with the newest within {@code rateChange}, which halves the
 * error of extrapolating a record for the second until the next. The phase moves toward the line by at most
 * {@code slewMsPerBeat} a beat ({@code rateChangeSlewMsPerBeat} right after the rate changed), snaps forward on the
 * first record and whenever it's more than a beat behind, and never steps back: ahead, it runs slower; more than a beat
 * ahead, it holds still. Only an epoch (Rule 1.8: {@link #onEpoch}, or a record from a new epoch) lets it step back,
 * and a frozen or paused record sets it to the server's frozen time exactly; {@link #stepFrozen} advances it under
 * {@code /tick step}. It starts only once {@code firstSamples} pongs are in (the join sends a burst), and a record
 * that a newer one overtook on the wire is ignored.
 *
 * <p>Not thread-safe: the mod hands pongs (with their receipt time) to the client thread, which also reads it.
 */
public final class ClockEstimator {
    private final ClockRules rules;

    private final long[] delay;
    private final long[] offset;
    private final int[] order;
    private int count;
    private int head;
    private boolean haveOffset;
    private long theta;

    private final double[] rates;
    private int rateCount;
    private double rate;
    private long rateTick = Long.MIN_VALUE;
    private boolean rateChanged;

    private boolean anchored;
    private boolean snapNext;
    private long c0;
    private double e0;
    private double beta;
    private double d;
    private boolean hold;
    private double slewPerNano;

    private boolean frozen;
    private double frozenValue;
    private boolean clientPaused;

    private boolean seen;
    private int newestEpoch;
    private long newestTick;
    private boolean awaitingNewer;
    private long lastQuery = Long.MIN_VALUE;

    public ClockEstimator(ClockRules rules) {
        this.rules = rules;
        this.delay = new long[rules.syncSamples()];
        this.offset = new long[rules.syncSamples()];
        this.order = new int[rules.syncSamples()];
        this.rates = new double[rules.rateSamples()];
    }

    /**
     * A pong. The client sent its ping at {@code t0} and received the answer at {@code t3} (its own clock); the server
     * received the ping at {@code t1} and answered at {@code t2} (its clock), with the record in force then.
     */
    public void onPong(long t0, long t1, long t2, long t3, TickRecord record, long beatMicros) {
        delay[head] = (t3 - t0) - (t2 - t1);
        offset[head] = (t1 - t0) / 2 + (t2 - t3) / 2;
        head = (head + 1) % delay.length;
        count = Math.min(count + 1, delay.length);
        theta = lowerHalfOffset();
        haveOffset = count >= rules.firstSamples(); // one sample is up to its jitter off; the join sends a burst
        onRecord(t3, record, beatMicros);
    }

    /** A record the server pushed (its rate moved, ticking froze or resumed), received at the client's {@code now}. */
    public void onRecord(long now, TickRecord record, long beatMicros) {
        if (clientPaused) {
            return;
        }
        if (awaitingNewer) { // the unpause burst is answered before the first tick back
            if (record.epoch() == newestEpoch && record.tick() <= newestTick) {
                return;
            }
            awaitingNewer = false;
        }
        if (seen) {
            if (record.epoch() < newestEpoch || record.epoch() == newestEpoch && record.tick() < newestTick) {
                return; // overtaken on the wire by a newer record: its rate is stale
            }
            if (record.epoch() != newestEpoch) {
                snapNext = true; // a backlog drop, even if its ClockEpoch hasn't come yet
            }
        }
        seen = true;
        newestEpoch = record.epoch();
        newestTick = record.tick();
        if (record.tick() != rateTick && !(record.frozen() || record.paused())) {
            rateTick = record.tick();
            rateChanged = takeRate(record.tickLengthNanos()) || rateChanged;
        }
        if (record.frozen() || record.paused()) {
            freeze(record); // needs neither the offset nor a rate: it's 50·G exactly
            return;
        }
        if (!haveOffset || rate <= 0) {
            return;
        }
        // Continuity at the latest time the estimate was read, not the receipt: a record handled after the client
        // already read later times would otherwise start its line under values it has handed out.
        long at = Math.max(now, lastQuery);
        if (frozen) {
            frozen = false;
            e0 = frozenValue;
            c0 = at;
            d = 0;
            hold = false;
        }
        double line = TickRecord.combatMicros(record.tick(), record.startNanos(), rate, (double) at + theta);
        if (!anchored || snapNext) {
            e0 = line;
            d = 0;
            hold = false;
            anchored = true;
            snapNext = false;
        } else {
            e0 = value(at);
            d = line - e0;
            hold = false;
            if (d > beatMicros) { // more than a beat behind: snap forward
                e0 = line;
                d = 0;
            } else if (d < -beatMicros) { // more than a beat ahead: hold still until the line catches up
                hold = true;
            }
        }
        c0 = at;
        beta = Micros.PER_TICK / rate;
        int slewMs = rateChanged ? rules.rateChangeSlewMsPerBeat() : rules.slewMsPerBeat();
        rateChanged = false;
        slewPerNano = slewMs * (double) Micros.PER_MILLI / beatMicros * beta;
    }

    /**
     * The client paused the integrated server. It skips its tick events, so no frozen record can come, while its
     * network thread still answers pings with the last record from before the pause; until {@link #resume}, records
     * are ignored and the estimate stays where it is.
     */
    public void pause(long now) {
        clientPaused = true;
        awaitingNewer = seen; // after resume, only a record from after the pause counts
        if (anchored && !frozen) {
            frozenValue = value(now);
            frozen = true;
        }
    }

    /** The client unpaused: the next record (the pong burst after the unpause) sets the clock going again. */
    public void resume() {
        clientPaused = false;
    }

    /** Vanilla dropped its backlog (Rule 1.8): the next record sets the clock, even backwards. */
    public void onEpoch() {
        snapNext = true;
    }

    /** {@code /tick step} while frozen: the estimate advances with the client's own ticks. */
    public void stepFrozen(long micros) {
        if (frozen) {
            frozenValue += micros;
        }
    }

    /** Whether a record has set the clock yet. */
    public boolean ready() {
        return anchored;
    }

    public boolean frozen() {
        return frozen;
    }

    /** The estimated combat time, in microseconds, at the client's {@code clientNanos}. */
    public long estimateMicros(long clientNanos) {
        lastQuery = Math.max(lastQuery, clientNanos);
        return Micros.roundMicros(value(clientNanos));
    }

    /**
     * A frozen or paused record. The server's combat time stands exactly at 50·G then, so the estimate does too, even
     * a one-way trip back: an estimate left ahead would have every press clamped by the stamp check (Rule 2.1).
     */
    private void freeze(TickRecord record) {
        frozenValue = Micros.ofTicks(record.tick());
        anchored = true;
        frozen = true;
    }

    private double value(long clientNanos) {
        if (frozen) {
            return frozenValue;
        }
        if (!anchored) {
            return 0;
        }
        long dc = clientNanos - c0;
        double base = e0 + beta * dc;
        if (hold) {
            return Math.max(e0, base + d);
        }
        double step = slewPerNano * Math.max(0, dc);
        return base + Math.clamp(d, -step, step);
    }

    /** Adds a record's T̄; the rate is the mean of the run of recent ones that agree with it. Returns whether it moved. */
    private boolean takeRate(double tickLength) {
        if (rateCount == rates.length) {
            System.arraycopy(rates, 1, rates, 0, rates.length - 1);
            rateCount--;
        }
        rates[rateCount++] = tickLength;
        double sum = 0;
        int n = 0;
        for (int i = rateCount - 1; i >= 0 && Math.abs(rates[i] - tickLength) <= rules.rateChange() * tickLength; i--) {
            sum += rates[i];
            n++;
        }
        if (n < rateCount) { // a disagreeing older rate never comes back
            System.arraycopy(rates, rateCount - n, rates, 0, n);
            rateCount = n;
        }
        double previous = rate;
        rate = sum / n;
        return previous > 0 && Math.abs(rate - previous) > rules.rateChange() * previous;
    }

    /** The mean offset of the lower-delay half of the samples. */
    private long lowerHalfOffset() {
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        for (int i = 1; i < count; i++) { // insertion sort by delay: at most 16 samples, once a second
            int k = order[i];
            int j = i - 1;
            while (j >= 0 && delay[order[j]] > delay[k]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = k;
        }
        int half = (count + 1) / 2;
        long ref = offset[order[0]];
        long sum = 0;
        for (int i = 0; i < half; i++) {
            sum += offset[order[i]] - ref; // differences stay small whatever the clocks' origins
        }
        return ref + Math.round((double) sum / half);
    }
}
