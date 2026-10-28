package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.clock.ClockEstimator;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.net.NetMessage;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The client's combat clock (spec Netcode, "The combat clock on clients"): core's {@link ClockEstimator} fed the
 * server's pongs and pushed records, on the same clock the pings and every press stamp read ({@code Util.getNanos()},
 * SDL's counter, which the integrated server shares). Pings go once every sync interval (its in-combat one in combat,
 * Rule 13.1), and in a burst of 4, one a
 * client tick, when the first record arrives, after an unpause, an unfreeze, a sprint and an epoch. No ping goes before
 * a record (the server answers none without one) or while the game is paused. After an unpause, input waits until a
 * record from a tick that started after the pause arrives ({@link ClockEstimator#awaitingResume}), since the estimate
 * stands still until then, and a ping goes every tick until it does: the unpause burst can all be answered before the
 * server's first tick back. Main thread only.
 */
final class ClientClock {
    private static final int BURST = 4;

    private final ClockRules rules;
    private final ClockEstimator estimator;
    private final long beatMicros;
    private @Nullable TickRecord newest;
    private int epoch;
    private int burst;
    private long lastPingNanos = Long.MIN_VALUE;
    private long intervalNanos; // between the last two pings, for the overlay (S524's MCTest check)
    private boolean paused;

    ClientClock(ClockRules rules, int beatTicks, int epoch) {
        this.rules = rules;
        this.estimator = new ClockEstimator(rules);
        this.beatMicros = Micros.ofTicks(beatTicks);
        this.epoch = epoch;
    }

    ClockRules rules() {
        return rules;
    }

    void onRecord(TickRecord r, long nowNanos) {
        TickRecord before = newest;
        note(r);
        estimator.onRecord(nowNanos, r, beatMicros);
        if (before == null || (before.frozen() && !r.frozen()) || before.sprinting() != r.sprinting()) {
            burst = BURST; // the first record, an unfreeze, a sprint's start or end
        }
    }

    void onPong(NetMessage.ClockPong p, long t3) {
        note(p.record());
        estimator.onPong(p.t0(), p.t1(), p.t2(), t3, p.record(), beatMicros);
    }

    private void note(TickRecord r) {
        TickRecord n = newest;
        if (n == null || r.epoch() > n.epoch() || (r.epoch() == n.epoch() && r.tick() >= n.tick())) {
            newest = r;
        }
        epoch = Math.max(epoch, r.epoch());
    }

    void onEpoch(int e) {
        epoch = Math.max(epoch, e);
        estimator.onEpoch();
        burst = BURST;
    }

    /** The game paused or unpaused (single player's pause also stops the integrated server's ticks). */
    void paused(boolean now, long nowNanos) {
        if (now == paused) {
            return;
        }
        paused = now;
        if (now) {
            estimator.pause(nowNanos);
        } else {
            estimator.resume(nowNanos);
            burst = BURST;
        }
    }

    /** Each client tick: the ping due, if any ({@link #pingDue}). */
    void tick(long nowNanos, boolean inCombat) {
        if (pingDue(nowNanos, inCombat)) {
            ClientPacketDistributor.sendToServer(new NetMessage.ClockPing(nowNanos));
        }
    }

    /**
     * Whether a ping goes on the client tick at {@code nowNanos}, counted as sent if so: a second after the last in
     * combat and five out of it (S524), so a fight that starts shortens the wait at once; the burst's one a tick; and
     * after an unpause, one every tick until a record from after it comes (the server allows 4/s).
     */
    boolean pingDue(long nowNanos, boolean inCombat) {
        if (newest == null || paused) {
            return false;
        }
        long interval = (inCombat ? rules.syncIntervalCombatMs() : rules.syncIntervalIdleMs()) * 1_000_000L;
        if (burst > 0) {
            burst--;
        } else if (!estimator.awaitingResume()
                && lastPingNanos != Long.MIN_VALUE
                && nowNanos - lastPingNanos < interval) {
            return false;
        }
        if (lastPingNanos != Long.MIN_VALUE) {
            intervalNanos = nowNanos - lastPingNanos;
        }
        lastPingNanos = nowNanos;
        return true;
    }

    /** The time between the last two pings, in ms (0 before the second): the overlay shows it. */
    long pingIntervalMillis() {
        return intervalNanos / 1_000_000;
    }

    /** Whether a record has set the clock. */
    boolean ready() {
        return estimator.ready();
    }

    /** Whether presses may be stamped and sent: the clock is set, the game runs and no unpause is being waited out. */
    boolean inputOpen() {
        return ready() && !paused && !estimator.awaitingResume();
    }

    /** Whether the server's ticking is frozen ({@code /tick freeze}), which pins the clock at 50·G. */
    boolean frozen() {
        TickRecord n = newest;
        return n != null && (n.frozen() || n.paused());
    }

    /** The combat time at the client's {@code nanos}, in microseconds. */
    long micros(long nanos) {
        return estimator.estimateMicros(nanos);
    }

    /**
     * The estimate less the newest record's own conversion at {@code nanos}: in single player, where both ends read
     * the same counter, the clock's error; online it also holds the two clocks' offset. The debug overlay shows it.
     */
    long errorMicros(long nanos) {
        TickRecord n = newest;
        return n == null || !ready() ? 0 : micros(nanos) - n.arrivalMicros(nanos);
    }

    /** The epoch of the newest record or {@code ClockEpoch} the client had, which each input frame carries. */
    int epoch() {
        return epoch;
    }

    @Nullable
    TickRecord newest() {
        return newest;
    }
}
