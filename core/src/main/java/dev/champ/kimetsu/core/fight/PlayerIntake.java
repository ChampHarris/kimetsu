package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.judge.RttWindow;
import dev.champ.kimetsu.core.judge.StampCheck;
import dev.champ.kimetsu.core.machine.Input;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One player's input on the server (Rule 2.1; Netcode, "Input frames"): its frames, in arrival order, through the stamp
 * check and the input horizon into its fighter's timeline. The mod's session feeds it on the main thread; the network
 * thread only turns each frame's arrival into combat time.
 *
 * <p>Each frame: the stamp check counts its send stamp against its arrival less half the RTT (no RTT for an arrival
 * converted while ticking was frozen or paused: its time is pinned, as the client's clock is); the hold cap follows from
 * that RTT and the tolerance the frame left; each press and release is judged, or held for a stall's anchor; what the
 * check judges goes to the fighter's timeline; and the horizon takes the counted stamp with the frame's tick time. The
 * frame's held state settles what its deltas missed: a guard or Breathe the timeline holds down that the client says is
 * up, with no release on its way, is released at the frame's stamp, so a lost release can't leave a key held. A press
 * is never made up, since one without its own stamp can't be judged.
 *
 * <p>Each step, held presses whose anchor didn't come are clamped and released (the stamp check's expiry), so a stall
 * doesn't keep every later strike waiting for its hold cap. A fighter that rejoins after a rebuild gets a fresh
 * timeline; the stamp check, the RTT and the jitter they measured carry on.
 *
 * <p>It keeps each frame until a snapshot includes it whole ({@link #settledSeq}): the latest tick one of its presses
 * acts at, and how many the stamp check still holds. A press acts at its judged tick, or after the timeline's
 * watermark if the world has read past that; one the barrier holds and releases after the watermark moved can act a
 * tick later than counted (step 5's reconciliation closes that). Not thread-safe.
 */
public final class PlayerIntake {
    /** A key on the wire: its number there, fixed whatever the order here, and its bit in a frame's held state. */
    public enum Key {
        ATTACK(0, Input.Kind.ATTACK, null),
        GUARD(1, Input.Kind.GUARD_DOWN, Input.Kind.GUARD_UP),
        BREATHE(2, Input.Kind.BREATHE_DOWN, Input.Kind.BREATHE_UP),
        INHALE(3, Input.Kind.INHALE, null),
        POMMEL(4, Input.Kind.POMMEL, null),
        STEP(5, Input.Kind.STEP, null),
        JUMP(6, Input.Kind.JUMP, null),
        SNEAK(7, Input.Kind.SNEAK, null);

        private static final Key[] ALL = values();

        private final int wire;
        private final Input.Kind press;
        private final Input.@Nullable Kind release;

        Key(int wire, Input.Kind press, Input.@Nullable Kind release) {
            this.wire = wire;
            this.press = press;
            this.release = release;
        }

        public int wire() {
            return wire;
        }

        public int bit() {
            return 1 << wire;
        }

        /** The key with wire number {@code wire}, or null for one this version doesn't know. */
        public static @Nullable Key of(int wire) {
            for (Key k : ALL) {
                if (k.wire == wire) {
                    return k;
                }
            }
            return null;
        }

        /** Whether the machine has a release for it (Guard and Breathe): only those can be left held. */
        public boolean releasable() {
            return release != null;
        }
    }

    /**
     * One press or release in a frame, at the combat time it was polled.
     *
     * @param slot for a Breathe release, the radial slot it was released toward, or −1
     * @param fallBlocks for an attack press, how far above the ground the player was while falling (0 when not): the
     *     server's own reading, never the client's (the wire doesn't carry it: a client-declared height fired the
     *     falling form from the ground). The mod's deltas carry none, so the intake's {@link FallProbe} gives it; a
     *     test's or a bot's delta can carry its own
     */
    public record Delta(Key key, boolean down, long stampMicros, int slot, double fallBlocks) {
        public static Delta press(Key key, long stampMicros) {
            return new Delta(key, true, stampMicros, -1, 0);
        }

        public static Delta release(Key key, long stampMicros) {
            return new Delta(key, false, stampMicros, -1, 0);
        }

        public static Delta release(Key key, long stampMicros, int slot) {
            return new Delta(key, false, stampMicros, slot, 0);
        }

        public static Delta attack(long stampMicros, double fallBlocks) {
            return new Delta(Key.ATTACK, true, stampMicros, -1, fallBlocks);
        }
    }

    /**
     * One input frame as the server read it.
     *
     * @param sendStampMicros the combat time it was sent at, the stamp the stamp check reads
     * @param frameTimeMicros the client's frame time, which the judge corrects by half
     * @param tickTimeMicros the start of the client tick whose pose it carries, or {@link InputHorizon#NO_TICK}
     * @param epoch the epoch of the last tick record the client had
     * @param held the keys held as it was sent ({@link Key#bit})
     */
    public record Frame(
            long seq,
            long sendStampMicros,
            long frameTimeMicros,
            long tickTimeMicros,
            int epoch,
            int held,
            List<Delta> deltas) {

        public Frame {
            deltas = List.copyOf(deltas);
        }

        /** This frame carrying {@code earlier}'s deltas before its own (a merge): its own stamps, held state and pose. */
        public Frame after(Frame earlier) {
            List<Delta> all = new ArrayList<>(earlier.deltas.size() + deltas.size());
            all.addAll(earlier.deltas);
            all.addAll(deltas);
            return new Frame(seq, sendStampMicros, frameTimeMicros, tickTimeMicros, epoch, held, all);
        }

        /** The same frame with no pose: one merged away loses its tick time. */
        public Frame withoutPose() {
            return new Frame(seq, sendStampMicros, frameTimeMicros, InputHorizon.NO_TICK, epoch, held, deltas);
        }
    }

    /** Why a press or release never reached the timeline. */
    public enum Drop {
        /** Stamped after the stall on the old timeline, sent before the client heard of the epoch (Rule 1.8). */
        OLD_EPOCH,
        /** More held for a stall's anchor than the stamp check holds: never with an honest client. */
        PENDING_FULL
    }

    /**
     * What the intake did with each press and release, for the fight log. One that comes while the player is out of
     * the fight goes to no timeline and isn't told: it's nobody's input.
     */
    public interface Sink {
        /**
         * {@code in} went to the fighter's timeline, judged from {@code d} (its stamp as sent), polled in a frame
         * {@code frameTimeMicros} long; {@code held} if it waited for a later frame to anchor a stall's bracket.
         */
        void judged(Input in, Delta d, long frameTimeMicros, boolean held);

        /** {@code d} never reached the timeline, {@code why}. */
        default void dropped(Delta d, Drop why) {}
    }

    public static final Sink NO_SINK = (in, d, frameTime, held) -> {};

    /**
     * How far above the floor the player was while falling at an attack press's judged stamp (Rule 5.5), from the
     * server's own poses and level ({@link FallHeight}); 0 when it wasn't falling. Asked for attack presses only.
     */
    @FunctionalInterface
    public interface FallProbe {
        /** No level to ask: no press is falling. */
        FallProbe NONE = atMicros -> 0;

        double fallBlocks(long atMicros);
    }

    /** The presses and releases it holds for a stall's anchor at most (the stamp check holds no more). */
    public static final int MAX_PENDING = 64;

    private static final int FRAMES = 256; // frames kept until settled: 6 s at the meter's 40 a second
    private static final long NONE = Long.MIN_VALUE;

    /** A press or release the stamp check holds, the frame it came in (by the intake's own count) and its length. */
    private record Held(Delta delta, long frame, long frameTimeMicros) {}

    private final TimingRules rules;
    private final StampCheck check;
    private final RttWindow rtt;
    private final Sink sink;
    private FallProbe fall = FallProbe.NONE;
    private @Nullable Fighter fighter;

    private final long[] pendingToken = new long[MAX_PENDING];
    private final @Nullable Held[] pendingDelta = new Held[MAX_PENDING];
    private int pendingCount;
    private long nextToken;

    // the frames no snapshot has included whole, oldest first: numbered by the intake (a client's seq is its own say)
    private final long[] frameSeq = new long[FRAMES];
    private final long[] frameActs = new long[FRAMES]; // the latest tick one of its presses acts at
    private final int[] frameHeld = new int[FRAMES]; // its presses the stamp check holds
    private int frameHead;
    private int frameCount;
    private long frameNext; // the number the next frame gets
    private long settledSeq = -1;

    private int told; // the releasable keys the timeline holds down
    private final long[] releasedAt = new long[Key.ALL.length]; // by wire number: the last release given, judged
    private int pendingDown; // releasable keys with a press the stamp check holds
    private int pendingUp; // and with a release it holds
    private long lastSeq = -1;
    private long newest = Long.MIN_VALUE;
    private long now;

    /** A player joining at the server's current clock {@code epoch}. */
    public PlayerIntake(TimingRules rules, int epoch, Sink sink) {
        this.rules = rules;
        this.check = new StampCheck(rules, epoch);
        // a rise past twice the tolerance floor puts ref off by more than the floor: honest frames start failing
        this.rtt = new RttWindow(Micros.ofMillis(rules.rttWindowMs()), 2 * Micros.ofMillis(rules.toleranceMinMs()));
        Arrays.fill(releasedAt, Long.MIN_VALUE);
        this.sink = sink;
    }

    /** Its attack presses' fall heights come from {@code probe} from now on. */
    public void fallProbe(FallProbe probe) {
        fall = probe;
    }

    /**
     * The player is in the fight as {@code f}: a fresh timeline, so nothing is held in it; the RTT, tolerance and hold
     * cap measured so far go with it.
     */
    public void join(Fighter f) {
        fighter = f;
        told = 0;
        for (int i = 0; i < frameCount; i++) {
            frameActs[(frameHead + i) % FRAMES] = NONE; // what they gave went with the old timeline
        }
        long r = rtt.minMicros(now);
        keep(f, r, holdCap(r));
    }

    /**
     * The player left the fight; its frames are still checked, so the stamp check keeps its measure of the link. What
     * it held for the timeline goes: a held press would otherwise expire into the next fighter's first tick.
     */
    public void leave() {
        fighter = null;
        check.clearHeld();
        check.clearReleased();
        for (int i = 0; i < pendingCount; i++) {
            pendingDelta[i] = null;
        }
        pendingCount = 0;
        pendingDown = 0;
        pendingUp = 0;
        told = 0;
        Arrays.fill(releasedAt, Long.MIN_VALUE);
        frameCount = 0; // out of the fight, no frame waits on anything
        settledSeq = lastSeq;
    }

    public @Nullable Fighter fighter() {
        return fighter;
    }

    /** The server's own echo came back at {@code nowMicros} after {@code rttMicros}. */
    public void echo(long nowMicros, long rttMicros) {
        now = nowMicros;
        rtt.add(nowMicros, rttMicros);
        Fighter f = fighter;
        if (f != null) {
            long r = rtt.minMicros(nowMicros);
            keep(f, r, holdCap(r));
        }
    }

    /** The RTT the judge uses now: the minimum echo over the window, 0 before the first. */
    public long rttMicros() {
        return rtt.minMicros(now);
    }

    /**
     * How far behind the server's clock its client's stamps read, in µs (negative: ahead): the mean of its frames'
     * (arrival − stamp) over the jitter window less half the RTT; NaN with no frame in the window. For {@code /kimetsu
     * debug}.
     */
    public double clockLagMicros() {
        return check.meanLagMicros() - rttMicros() / 2.0;
    }

    /** The sequence number of the last frame taken, or −1. */
    public long lastSeq() {
        return lastSeq;
    }

    /**
     * The sequence number of the newest frame the state at the end of {@code settledTick} includes whole, or −1
     * ({@code Snapshot.lastSeq}): every press it and the frames before it carried acts by then, and the stamp check holds
     * none of them. Asked with settled ticks that only move on.
     */
    public long settledSeq(long settledTick) {
        while (frameCount > 0 && frameHeld[frameHead] == 0 && frameActs[frameHead] <= settledTick) {
            settledSeq = frameSeq[frameHead];
            frameHead = (frameHead + 1) % FRAMES;
            frameCount--;
        }
        return settledSeq;
    }

    /**
     * The newest stamp the stamp check let a frame count, or {@code Long.MIN_VALUE} before the first frame of this
     * epoch: how far the player's input is known, which bounds the tick a snapshot settles.
     */
    public long newestFrameMicros() {
        return newest;
    }

    /**
     * A frame, in arrival order, and when it arrived in combat time; {@code frozen} if that arrival was converted while
     * ticking was frozen or paused. Returns whether its tick time was taken as a fresh pose, which the caller then
     * records in the fighter's pose history at that time.
     */
    public boolean frame(Frame f, long arrivalMicros, boolean frozen) {
        lastSeq = f.seq();
        now = arrivalMicros;
        long no = open(f.seq());
        long r = rtt.minMicros(arrivalMicros);
        long counted = check.frame(f.sendStampMicros(), arrivalMicros, frozen ? 0 : r, f.epoch());
        newest = Math.max(newest, counted);
        drain(); // presses an earlier stall held, anchored by this frame
        long cap = holdCap(r);
        List<Delta> deltas = f.deltas();
        for (int i = 0; i < deltas.size(); i++) {
            judge(deltas.get(i), f.frameTimeMicros(), cap, no);
        }
        int stale = told & ~f.held() & ~pendingUp;
        if (stale != 0) {
            for (Key k : Key.ALL) {
                if ((stale & k.bit()) != 0) {
                    judge(Delta.release(k, f.sendStampMicros()), f.frameTimeMicros(), cap, no);
                }
            }
        }
        Fighter ft = fighter;
        if (ft == null) {
            return false;
        }
        boolean pose = ft.horizon().frame(counted, f.frameTimeMicros(), f.tickTimeMicros(), check.vouchedMicros());
        keep(ft, r, cap);
        return pose;
    }

    /** Before step {@code n}: held presses past their deadline are clamped and released. */
    public void beforeStep(long n) {
        check.expire(Micros.ofTicks(n));
        drain();
        Fighter f = fighter;
        if (f != null) {
            f.earliestHeldMicros(check.earliestHeldJudged());
        }
    }

    /** {@link #serverPose}'s answer when the client's own poses are fresh enough. */
    public static final long NO_SERVER_POSE = Long.MIN_VALUE;

    /**
     * Before step {@code n}: the time at which the caller records the server's own pose of the player, or {@link
     * #NO_SERVER_POSE}. That's when the fighter has none yet, or the client has sent no fresh one for longer than the
     * hold cap (no demon in its view, a stall past what any strike waits for, or a client that stops): its position
     * as the server has it now, as of half the RTT ago, the age of the newest movement it has. Within the hold cap a
     * stalled client's own poses still come in time, so it isn't judged on the server's guess.
     */
    public long serverPose(long n) {
        Fighter f = fighter;
        if (f == null) {
            return NO_SERVER_POSE;
        }
        long nowMicros = Micros.ofTicks(n);
        long r = rtt.minMicros(nowMicros);
        long known = f.horizon().poseHorizonMicros();
        if (known != Long.MIN_VALUE && nowMicros - holdCap(r) <= known) {
            return NO_SERVER_POSE;
        }
        long at = nowMicros - r / 2;
        return f.horizon().serverPose(at) ? at : NO_SERVER_POSE;
    }

    /**
     * The server crossed clock epoch {@code epoch} (Rule 1.8); presses stamped after {@code cutoffMicros} on the old
     * timeline are discarded. The world resets the fighter's timeline and horizon itself.
     */
    public void onEpoch(int epoch, long cutoffMicros) {
        check.onEpoch(epoch, cutoffMicros);
        newest = Long.MIN_VALUE; // the new timeline starts behind the old one
        // the timeline drops what was stamped after the cutoff, so a release given to it then is undone: the key is
        // down again there, and the next frame's held state releases it if the client says it's up (code review)
        for (Key k : Key.ALL) {
            if (k.releasable() && releasedAt[k.wire()] > cutoffMicros) {
                told |= k.bit();
                releasedAt[k.wire()] = Long.MIN_VALUE;
            }
        }
        drain();
        Fighter f = fighter;
        if (f != null) {
            f.earliestHeldMicros(check.earliestHeldJudged());
        }
    }

    /** Frame {@code seq} came: it's kept until a snapshot includes it whole. Returns its number. */
    private long open(long seq) {
        if (frameCount == FRAMES) { // a client out of the fight whose frames nothing settles: the oldest goes
            settledSeq = frameSeq[frameHead];
            frameHead = (frameHead + 1) % FRAMES;
            frameCount--;
        }
        int i = (frameHead + frameCount) % FRAMES;
        frameSeq[i] = seq;
        frameActs[i] = NONE;
        frameHeld[i] = 0;
        frameCount++;
        return frameNext++;
    }

    /** The slot of frame number {@code no}, or −1 once it's settled. */
    private int slotOf(long no) {
        long back = frameNext - no; // 1 for the newest
        return back < 1 || back > frameCount ? -1 : (int) ((frameHead + frameCount - back) % FRAMES);
    }

    private void judge(Delta d, long frameTimeMicros, long cap, long no) {
        Key k = d.key();
        if (!d.down() && (!k.releasable() || ((told | pendingDown) & k.bit()) == 0)) {
            return; // a key with no release in the machine, or one the timeline never held
        }
        long token = ++nextToken;
        long judged = check.press(token, d.stampMicros(), frameTimeMicros, cap);
        if (judged == StampCheck.HELD) {
            hold(token, d, no, frameTimeMicros);
        } else if (judged == StampCheck.DROPPED) {
            sink.dropped(d, Drop.OLD_EPOCH);
        } else {
            give(d, judged, no, frameTimeMicros, false);
        }
    }

    private void hold(long token, Delta d, long no, long frameTimeMicros) {
        if (pendingCount == MAX_PENDING) {
            sink.dropped(d, Drop.PENDING_FULL); // the stamp check never holds more than it has room for
            return;
        }
        pendingToken[pendingCount] = token;
        pendingDelta[pendingCount] = new Held(d, no, frameTimeMicros);
        pendingCount++;
        int slot = slotOf(no);
        if (slot >= 0) {
            frameHeld[slot]++;
        }
        if (d.key().releasable()) {
            if (d.down()) {
                pendingDown |= d.key().bit();
            } else {
                pendingUp |= d.key().bit();
            }
        }
    }

    /** What the stamp check released since the last drain, in its order, to the timeline (the dropped discarded). */
    private void drain() {
        int n = check.releasedCount();
        for (int i = 0; i < n; i++) {
            Held h = take(check.releasedToken(i));
            if (h == null) {
                continue;
            }
            Delta d = h.delta();
            if (d.key().releasable()) {
                if (d.down()) {
                    pendingDown &= ~d.key().bit();
                } else {
                    pendingUp &= ~d.key().bit();
                }
            }
            int slot = slotOf(h.frame());
            if (slot >= 0) {
                frameHeld[slot]--;
            }
            long judged = check.releasedJudged(i);
            if (judged == StampCheck.DROPPED) {
                sink.dropped(d, Drop.OLD_EPOCH);
            } else {
                give(d, judged, h.frame(), h.frameTimeMicros(), true);
            }
        }
        check.clearReleased();
    }

    private @Nullable Held take(long token) {
        for (int i = 0; i < pendingCount; i++) {
            if (pendingToken[i] == token) {
                Held d = pendingDelta[i];
                pendingCount--;
                pendingToken[i] = pendingToken[pendingCount];
                pendingDelta[i] = pendingDelta[pendingCount];
                pendingDelta[pendingCount] = null;
                return d;
            }
        }
        return null;
    }

    private void give(Delta d, long judged, long no, long frameTimeMicros, boolean held) {
        Fighter f = fighter;
        if (f == null) {
            return;
        }
        Key k = d.key();
        Input.Kind kind = d.down() ? k.press : k.release;
        if (kind == null) {
            return;
        }
        double fallBlocks =
                kind != Input.Kind.ATTACK ? 0 : d.fallBlocks() > 0 ? d.fallBlocks() : fall.fallBlocks(judged);
        Input in = new Input(kind, judged, d.slot(), fallBlocks);
        if (k.releasable()) {
            told = d.down() ? told | k.bit() : told & ~k.bit();
            releasedAt[k.wire()] = d.down() ? Long.MIN_VALUE : judged;
        }
        f.timeline().receive(in);
        sink.judged(in, d, frameTimeMicros, held);
        int slot = slotOf(no);
        if (slot >= 0) {
            long w = f.timeline().watermark();
            long acts = w == NONE ? in.tick() : Math.max(in.tick(), w + 1);
            frameActs[slot] = Math.max(frameActs[slot], acts);
        }
    }

    private long holdCap(long rttMicros) {
        return InputHorizon.holdCapMicros(rules, rttMicros, tolerance());
    }

    /** The tolerance at the last frame; the floor before the first. */
    private long tolerance() {
        long t = check.toleranceMicros();
        return t > 0 ? t : Micros.ofMillis(rules.toleranceMinMs());
    }

    private void keep(Fighter f, long rttMicros, long cap) {
        f.rttMicros(rttMicros);
        f.toleranceMicros(tolerance());
        f.holdCapMicros(cap);
        f.earliestHeldMicros(check.earliestHeldJudged());
    }
}
