package dev.champ.kimetsu.core.judge;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.TimingRules;
import java.util.Arrays;

/**
 * One client's stamp validation (Rule 2.1), fed its input frames in arrival order.
 *
 * <p>A stamp s is checked against ref = arrival − RTT/2. Within the tolerance, clamp(3 × jitter, 20, 80) ms, it
 * stands; later than that it's clamped to the tolerance's edge. Earlier than that it's usually a frame a stall held
 * back: such a press is accepted if its stamp lies between the stamp of the frame before it and its own frame's, once
 * a later frame passes the check and so anchors the chain, back-dated at most the stall bracket (300 ms in PvE). An
 * early press that isn't bracketed, or whose anchor doesn't come within the hold cap, is clamped like a late one. The
 * judged time is the stamp minus half the client's frame time, since input is polled once a frame.
 *
 * <p>A frame is checked by the stamp it was sent at, not the nominal time of the client tick that produced it: a client
 * tick runs up to a render frame late, so its nominal time would fail the arrival check and anchor nothing. A press
 * carried in an end-of-tick frame is up to a tick older than its frame's send stamp, which is exactly what the bracket
 * between the two frames' stamps accepts.
 *
 * <p>Stamps never go backwards: a frame or press stamped before the last one is raised to it. A {@code ClockEpoch}
 * (Rule 1.8) resets that, and old-epoch presses stamped after the last tick before the stall are dropped.
 */
public final class StampCheck {
    /** {@link #press} result: the press waits for an anchor; it comes out of {@link #releasedJudged}. */
    public static final long HELD = Long.MIN_VALUE;
    /** A press stamped after the stall, sent before the client heard of the epoch. */
    public static final long DROPPED = Long.MIN_VALUE + 1;

    private static final int MAX_HELD = 64;          // 16 events a frame through a 4-frame burst

    private final TimingRules rules;
    private final JitterWindow jitter;

    private int epoch;
    private long epochCutoff = Long.MAX_VALUE;
    private int frameEpoch;
    private boolean oldEpoch;

    private long frameFloor = Long.MIN_VALUE;
    private long pressFloor = Long.MIN_VALUE;
    private long previousFrame = Long.MIN_VALUE;
    private long frame = Long.MIN_VALUE;
    private long ref;
    private long tolerance;
    private boolean passes;

    private final long[] heldToken = new long[MAX_HELD];
    private final long[] heldStamp = new long[MAX_HELD];
    private final long[] heldHalf = new long[MAX_HELD];
    private final long[] heldRef = new long[MAX_HELD];
    private final long[] heldTolerance = new long[MAX_HELD];
    private final long[] heldDeadline = new long[MAX_HELD];
    private int heldCount;

    private long[] outToken = new long[MAX_HELD];
    private long[] outJudged = new long[MAX_HELD];
    private int outCount;

    /** A client joining at the server's current {@code epoch} ({@code TickSchedule.epoch()}). */
    public StampCheck(TimingRules rules, int epoch) {
        this.rules = rules;
        this.jitter = new JitterWindow(rules.jitterFrames());
        this.epoch = epoch;
        this.frameEpoch = epoch;
    }

    /** clamp(multiple × jitter, min, max), in microseconds. */
    public static long tolerance(TimingRules rules, double jitterSdMicros) {
        long t = Math.round(rules.toleranceJitterMultiple() * jitterSdMicros);
        return Math.clamp(t, Micros.ofMillis(rules.toleranceMinMs()), Micros.ofMillis(rules.toleranceMaxMs()));
    }

    /**
     * A frame, in arrival order: the stamp it was sent at and its arrival, in combat µs, the epoch of the last record
     * the client had (absolute, so a player who joined after a backlog drop is current) and the current RTT, or 0 for
     * an arrival converted while ticking was frozen or paused: its time is pinned to 50·G, as a frozen client's clock
     * is, so nothing was in flight against it. Returns
     * the stamp the input horizon may count, never later than the tolerance allows. A frame claiming an epoch the
     * server hasn't reached counts as the current one: only a real epoch lets stamps go back, once.
     */
    public long frame(long stampMicros, long arrivalMicros, long rttMicros, int clientEpoch) {
        oldEpoch = clientEpoch < epoch;
        if (!oldEpoch && frameEpoch < epoch) {           // the first frame stamped on the new timeline
            frameEpoch = epoch;
            frameFloor = Long.MIN_VALUE;
            pressFloor = Long.MIN_VALUE;
            frame = Long.MIN_VALUE;
            jitter.clear();
        }
        long s = Math.max(stampMicros, frameFloor);
        frameFloor = s;
        previousFrame = frame != Long.MIN_VALUE ? frame : s - Micros.PER_TICK;   // a first frame brackets one tick
        frame = s;
        jitter.add(arrivalMicros, s);
        ref = arrivalMicros - rttMicros / 2;
        tolerance = tolerance(rules, jitter.sdMicros());
        passes = !oldEpoch && Math.abs(s - ref) <= tolerance;
        if (passes) {
            releaseHeld();
        }
        return Math.min(s, ref + tolerance);
    }

    /**
     * A press in the frame last given to {@link #frame}: its stamp, the frame time it was polled in and the hold cap.
     * Returns its judged time, {@link #HELD} or {@link #DROPPED}. {@code token} identifies it when it's released.
     */
    public long press(long token, long stampMicros, long frameTimeMicros, long holdCapMicros) {
        if (oldEpoch && stampMicros > epochCutoff) {
            return DROPPED;
        }
        long s = Math.max(stampMicros, pressFloor);
        pressFloor = s;
        long half = halfFrame(rules, frameTimeMicros);
        long dev = s - ref;
        if (Math.abs(dev) <= tolerance) {
            return s - half;
        }
        if (dev > 0) {
            return ref + tolerance - half;
        }
        boolean bracketed = s >= previousFrame && s <= frame;
        if (!bracketed || heldCount == MAX_HELD) {
            return ref - tolerance - half;
        }
        if (passes) {
            return bracketJudged(s, ref, half);
        }
        heldToken[heldCount] = token;
        heldStamp[heldCount] = s;
        heldHalf[heldCount] = half;
        heldRef[heldCount] = ref;
        heldTolerance[heldCount] = tolerance;
        heldDeadline[heldCount] = s + longestAnswer() + holdCapMicros;   // the last strike it could answer
        heldCount++;
        return HELD;
    }

    /**
     * Held presses whose anchor didn't come in time are clamped, as presses with no bracket are. A press answers strikes
     * up to the deflect window after it, each decided by its impact plus the hold cap, so it waits that long.
     */
    public void expire(long nowMicros) {
        int kept = 0;
        for (int i = 0; i < heldCount; i++) {
            if (heldDeadline[i] <= nowMicros) {
                release(heldToken[i], heldRef[i] - heldTolerance[i] - heldHalf[i]);
            } else {
                moveHeld(i, kept++);
            }
        }
        heldCount = kept;
    }

    /**
     * The server crossed an epoch: {@code cutoffMicros} is the end of the last tick that ran before the stall. Held
     * presses stamped after it are released as {@link #DROPPED}.
     */
    public void onEpoch(int newEpoch, long cutoffMicros) {
        epoch = newEpoch;
        epochCutoff = cutoffMicros;
        int kept = 0;
        for (int i = 0; i < heldCount; i++) {
            if (heldStamp[i] > cutoffMicros) {
                release(heldToken[i], DROPPED);
            } else {
                moveHeld(i, kept++);
            }
        }
        heldCount = kept;
    }

    public int releasedCount() {
        return outCount;
    }

    public long releasedToken(int i) {
        return outToken[i];
    }

    public long releasedJudged(int i) {
        return outJudged[i];
    }

    public void clearReleased() {
        outCount = 0;
    }

    public int heldCount() {
        return heldCount;
    }

    /** The tolerance at the last frame, in microseconds. */
    public long toleranceMicros() {
        return tolerance;
    }

    /** The half-frame correction (Rule 2.1), on a frame time no longer than the rules trust. */
    static long halfFrame(TimingRules rules, long frameTimeMicros) {
        if (!rules.halfFrameCorrection()) {
            return 0;
        }
        return Math.clamp(frameTimeMicros, 0, Micros.ofMillis(rules.maxFrameMs())) / 2;
    }

    private long longestAnswer() {
        return Micros.scaled(rules.deflectMs(), rules.assistWindowScale());
    }

    private long bracketJudged(long stamp, long pressRef, long half) {
        return Math.max(stamp, pressRef - Micros.ofMillis(rules.stallBracketMs())) - half;
    }

    private void releaseHeld() {
        for (int i = 0; i < heldCount; i++) {
            release(heldToken[i], bracketJudged(heldStamp[i], heldRef[i], heldHalf[i]));
        }
        heldCount = 0;
    }

    private void release(long token, long judged) {
        if (outCount == outToken.length) {   // the caller drains after each frame and tick; grow rather than drop
            outToken = Arrays.copyOf(outToken, outToken.length * 2);
            outJudged = Arrays.copyOf(outJudged, outJudged.length * 2);
        }
        outToken[outCount] = token;
        outJudged[outCount] = judged;
        outCount++;
    }

    private void moveHeld(int from, int to) {
        if (from == to) {
            return;
        }
        heldToken[to] = heldToken[from];
        heldStamp[to] = heldStamp[from];
        heldHalf[to] = heldHalf[from];
        heldRef[to] = heldRef[from];
        heldTolerance[to] = heldTolerance[from];
        heldDeadline[to] = heldDeadline[from];
    }
}
