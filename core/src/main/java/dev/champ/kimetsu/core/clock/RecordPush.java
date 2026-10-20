package dev.champ.kimetsu.core.clock;

import dev.champ.kimetsu.core.data.ClockRules;

/**
 * Which tick records the server pushes to one client (Netcode, {@code ClockRecord}). A record goes when T̄ has moved
 * more than {@code rateChange} from the last one this client got ({@link TickRecord#movedFrom}), at most one a tick,
 * from a token bucket of {@code pushesPerS} with a burst of {@code pushBurst}; a change of ticking state or epoch goes
 * whatever the bucket holds. A steady server pushes about once a minute and the bucket never binds; an overloaded one
 * whose tick lengths spread 5 ms pushes 5.7 a second unlimited, 4 with it, at 15 against 12 ms p95 of clock error
 * ({@code tools/sims/clock_sim.py}). One instance per client: the server thread offers each tick's record and the
 * network thread notes the record each pong carried, so both are synchronized (20 and 1 a second, uncontended).
 */
public final class RecordPush {
    private final ClockRules rules;
    private TickRecord sent;
    private double tokens;
    private long lastNanos;
    private boolean started;

    public RecordPush(ClockRules rules) {
        this.rules = rules;
        this.tokens = rules.pushBurst();
    }

    /** The tick's record, published at {@code nowNanos}. Returns whether this client gets it pushed now. */
    public synchronized boolean offer(TickRecord record, long nowNanos) {
        if (started) {
            tokens = Math.min(rules.pushBurst(), tokens + rules.pushesPerS() * (nowNanos - lastNanos) / 1e9);
        }
        lastNanos = nowNanos;
        started = true;
        if (sent == null) { // the join's pong burst carries the first record
            return false;
        }
        if (!record.movedFrom(sent, rules.rateChange())) {
            return false;
        }
        if (!record.stateChangedFrom(sent)) {
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
        }
        sent = record;
        return true;
    }

    /** A pong carried {@code record} to this client: it has it. */
    public synchronized void sentInPong(TickRecord record) {
        sent = record;
    }
}
