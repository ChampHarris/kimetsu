package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Displacement;
import dev.champ.kimetsu.core.data.MoveRules;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.machine.TimedAnswers;
import org.jspecify.annotations.Nullable;

/**
 * A player's movement bound (spec Netcode, "The movement bound"; D-070). The client moves itself, so the mod stages
 * the positions vanilla accepted ({@link #stage}) and the loop files them into the fighter's pose history once its
 * machine has run ({@link #pass}, after {@code Timeline.beginTick} and the tick's queued cancels, landings and effects,
 * before any reader), each one bounded: over a window of
 * {@code window_ticks} it may have moved at most {@code window_blocks} horizontally and {@code rise_blocks} up from the
 * bounded pose at the window's start, each times its scale (the tick rate and its speed, read when it was staged), plus
 * the envelope of every accepted Step whose ticks overlap the window and of every impulse in it. Downward it moves
 * freely. A pose over the bound is pulled back along the line from that start toward where it really was; its look and
 * eye height are kept. Only the engine's poses change (D-052): vanilla's position is never set back, so the worst a
 * misjudged bound does is misjudge a dodge, and the log says so.
 *
 * <p><b>Time, not count.</b> The window is combat time: the newest pose at or before its start is its reference, and a
 * longer gap than the window (frames that stopped, merged ticks) widens the bound in proportion. Low frame rates bunch
 * frames, not their tick times, so they change nothing. A teleport the server made ({@link #teleported}) makes the
 * next pose a new reference: nothing before it counts.
 *
 * <p><b>Envelopes.</b> A Step's opens from its start − ({@code buffer_ticks} + 1) ticks to its end + 1, since the
 * client starts its dash from its own clock and buffer; it needs a Step the machine accepted, and paid for, so the
 * slack costs no security. When a pass meets a Step start it hasn't seen (one the barrier released in {@code resolve},
 * or a late press replayed), it files every pose from that start's envelope again from the raw positions it keeps, so
 * an honest Step held behind a strike isn't cut. A Step can't start before the Timeline's history, so a pose older
 * than that and the envelope's lead is settled: only then is a clamp published ({@link Outbox.PoseClamped}) and the
 * pose counted in the second's figures, so an honest Step filed again is never reported.
 *
 * <p><b>Known limit</b> (Champ, D-070, D-072): a modified client can move up to {@code window_blocks} in one tick and
 * stand for the rest of the window, a free dash with no Step paid: with the measured numbers, more than a whole Step,
 * since honest stair-running covers more than one. The second's figures count each pose whose window, with no envelope
 * open, moved farther than level ground's honest maximum allows ({@code free_dash_blocks}) and climbed less than
 * {@code free_dash_rise} (stairs climb more), so a Step faked on level ground counts, for 5c-4's report (D-073).
 *
 * <p>Allocation-free but for the clamps it publishes. Unconfigured ({@link #configure} not called), it files every pose
 * as it came.
 */
public final class MoveBound {
    /** The figures of one second's settled poses, for the fight log's {@code second} line. */
    public record Second(double fastest, double rise, int freeDashes, int clamps, int dropped) {}

    private static final int STAGE = 32;
    private static final int STEPS = 8; // TimedAnswers keeps 8 of each kind
    private static final int IMPULSES = 8;
    private static final long NONE = Long.MIN_VALUE;

    private @Nullable MoveRules rules;
    private double[] step = new double[0]; // the Step's displacement in each of its ticks
    private long leadMicros; // how far before a Step's start its envelope opens: buffer_ticks + 1
    private long windowMicros;
    private long settleMicros; // a pose this far behind the tick can't be filed again: the history and the lead

    // poses staged since the last pass, oldest first (a ring)
    private final long[] sTime = new long[STAGE];
    private final double[] sX = new double[STAGE];
    private final double[] sY = new double[STAGE];
    private final double[] sZ = new double[STAGE];
    private final double[] sYaw = new double[STAGE];
    private final double[] sPitch = new double[STAGE];
    private final double[] sEye = new double[STAGE];
    private final double[] sScale = new double[STAGE];
    private final boolean[] sBase = new boolean[STAGE];
    private int sHead;
    private int sCount;
    private boolean nextBase;

    // the filed poses, parallel to the fighter's pose history (a ring of the same size): raw, bounded and judged
    private final int size;
    private final long[] hTime;
    private final double[] hX;
    private final double[] hY;
    private final double[] hZ;
    private final double[] hYaw;
    private final double[] hPitch;
    private final double[] hEye;
    private final double[] hScale;
    private final boolean[] hBase;
    private final double[] bX;
    private final double[] bY;
    private final double[] bZ;
    private final double[] hMoved; // its window's horizontal displacement, scaled to window_ticks
    private final double[] hRose;
    private final boolean[] hFree; // over the honest maximum with no envelope
    private final double[] hRaw; // its window's displacement, raw
    private final double[] hAllowed; // and what the bound allowed of it (equal: not clamped)
    private int hHead; // the next slot written
    private int hCount;
    private long settledThrough = NONE;

    private final long[] iFrom = new long[IMPULSES];
    private final long[] iUntil = new long[IMPULSES];
    private final double[] iBlocks = new double[IMPULSES];
    private int iHead;
    private int iCount;

    private final long[] known = new long[STEPS]; // the Step starts the last pass saw
    private int knownCount;

    private double fastest;
    private double rise;
    private int freeDashes;
    private int clamps;
    private int dropped;
    private double blocksPerTick;

    /** One for a pose history of {@code historySize} entries: it keeps the raw pose of each. */
    public MoveBound(int historySize) {
        size = historySize;
        hTime = new long[size];
        hX = new double[size];
        hY = new double[size];
        hZ = new double[size];
        hYaw = new double[size];
        hPitch = new double[size];
        hEye = new double[size];
        hScale = new double[size];
        hBase = new boolean[size];
        bX = new double[size];
        bY = new double[size];
        bZ = new double[size];
        hMoved = new double[size];
        hRose = new double[size];
        hFree = new boolean[size];
        hRaw = new double[size];
        hAllowed = new double[size];
    }

    /**
     * Bounds every pose from now on by {@code rules}, with {@code step}'s displacement as a Step's envelope, its lead
     * {@code bufferTicks} + 1, and a pose settled once it's {@code historyTicks} + that lead behind the tick.
     */
    public void configure(MoveRules rules, FrameData step, int bufferTicks, int historyTicks) {
        this.rules = rules;
        double[] perTick = new double[step.total()];
        for (Displacement d : step.displacement()) {
            double blocks = Math.sqrt(d.forward() * d.forward() + d.right() * d.right() + d.up() * d.up());
            for (int k = d.from(); k <= d.to(); k++) {
                perTick[step.start(d.phase()) + k] += blocks;
            }
        }
        this.step = perTick;
        this.leadMicros = Micros.ofTicks(bufferTicks + 1L);
        this.windowMicros = Micros.ofTicks(rules.windowTicks());
        this.settleMicros = Micros.ofTicks(historyTicks) + leadMicros;
    }

    // ------------------------------------------------------------------------------------------------- the mod

    /**
     * A position vanilla accepted at {@code timeMicros}, its eyes {@code eye} above its feet (NaN: standing), and its
     * scale: the tick rate times its speed's gain (and ice's), or {@code Double.POSITIVE_INFINITY} when nothing bounds it
     * (riding, gliding, a riptide spin, flying). No earlier than the newest staged or filed; one at the newest staged time replaces
     * it. The stage holds 32: past that the oldest is dropped, and counted.
     */
    public void stage(
            long timeMicros, double x, double y, double z, double yaw, double pitch, double eye, double scale) {
        if (!(Double.isFinite(x)
                && Double.isFinite(y)
                && Double.isFinite(z)
                && Double.isFinite(yaw)
                && Double.isFinite(pitch))) {
            throw new IllegalArgumentException(
                    "a pose must be finite, was (" + x + ", " + y + ", " + z + ") at " + yaw + "°, " + pitch + "°");
        }
        if (!(scale > 0)) {
            throw new IllegalArgumentException("a pose's scale must be positive, was " + scale);
        }
        if (!Double.isNaN(eye) && !(eye >= 0 && Double.isFinite(eye))) {
            throw new IllegalArgumentException("an eye height must be finite and not negative, was " + eye);
        }
        long newest = sCount > 0 ? sTime[staged(sCount - 1)] : hCount > 0 ? hTime[back(0)] : NONE;
        if (newest != NONE && timeMicros < newest) {
            throw new IllegalArgumentException(
                    "pose at " + timeMicros + " µs is older than the newest, at " + newest + " µs");
        }
        int at;
        boolean base = nextBase;
        nextBase = false;
        if (sCount > 0 && timeMicros == newest) {
            at = staged(sCount - 1);
            base |= sBase[at];
        } else {
            if (sCount == STAGE) {
                boolean lost = sBase[sHead];
                sHead = (sHead + 1) % STAGE;
                sCount--;
                dropped++;
                if (lost) {
                    sBase[sHead] = true; // the teleport is still behind what's left
                }
            }
            at = staged(sCount);
            sCount++;
        }
        sTime[at] = timeMicros;
        sX[at] = x;
        sY[at] = y;
        sZ[at] = z;
        sYaw[at] = yaw;
        sPitch[at] = pitch;
        sEye[at] = eye;
        sScale[at] = scale;
        sBase[at] = base;
    }

    /**
     * The server teleported the player (a command, a pearl, chorus fruit; never vanilla's set-backs, which a client can
     * provoke): the next pose staged is a new reference.
     */
    public void teleported() {
        nextBase = true;
    }

    /**
     * A vanilla impulse (a knockback, an explosion) at {@code fromMicros} that can carry the player {@code blocks}: the
     * bound opens by that much, up and across, for every window overlapping the next {@code ticks}. The last 8 count.
     */
    public void impulse(long fromMicros, double blocks, int ticks) {
        if (!(blocks >= 0 && Double.isFinite(blocks)) || ticks < 0) {
            throw new IllegalArgumentException(
                    "an impulse must be finite and not negative, was " + blocks + " over " + ticks + " ticks");
        }
        iFrom[iHead] = fromMicros;
        iUntil[iHead] = fromMicros + Micros.ofTicks(ticks);
        iBlocks[iHead] = blocks;
        iHead = (iHead + 1) % IMPULSES;
        iCount = Math.min(iCount + 1, IMPULSES);
    }

    /**
     * The farthest it may move in a tick by the bound (blocks), for the stale-pose slack ({@code CombatWorld.slack}):
     * the window's blocks per tick times the largest scale in the newest pose's window, or what it was seen to move when
     * nothing bounds it.
     */
    public double blocksPerTick() {
        return blocksPerTick;
    }

    /** The figures of the poses settled since the last call, which starts the next second. */
    public Second takeSecond() {
        Second s = new Second(fastest, rise, freeDashes, clamps, dropped);
        fastest = 0;
        rise = 0;
        freeDashes = 0;
        clamps = 0;
        dropped = 0;
        return s;
    }

    // ------------------------------------------------------------------------------------------------- the loop

    /**
     * Tick {@code n}, after its machine ran: a Step start {@code answers} holds that the last pass didn't files the
     * poses from its envelope again, the staged poses are filed into {@code poses} bounded, and those now settled
     * publish their clamps for player {@code fighter} into {@code out}.
     */
    public void pass(long n, PoseRing poses, TimedAnswers answers, int fighter, Outbox out) {
        if (rules != null) {
            long fresh = Long.MAX_VALUE;
            int c = answers.count(TimedAnswers.Kind.STEP);
            for (int i = 0; i < c; i++) {
                long s = answers.at(TimedAnswers.Kind.STEP, i);
                if (!knows(s)) {
                    fresh = Math.min(fresh, s);
                }
            }
            for (int i = 0; i < c; i++) {
                known[i] = answers.at(TimedAnswers.Kind.STEP, i);
            }
            knownCount = c;
            if (fresh != Long.MAX_VALUE) {
                refile(fresh - leadMicros, poses, answers, fighter, out);
            }
        }
        for (int i = 0; i < sCount; i++) {
            int s = staged(i);
            file(
                    sTime[s], sX[s], sY[s], sZ[s], sYaw[s], sPitch[s], sEye[s], sScale[s], sBase[s], poses, answers,
                    fighter, out);
        }
        sHead = 0;
        sCount = 0;
        if (rules != null) {
            settle(Micros.ofTicks(n) - settleMicros, fighter, out);
        }
    }

    /** An epoch (Rule 1.8): the stage and the history start over with the pose ring; the Steps it knew stay known. */
    public void clear() {
        sHead = 0;
        sCount = 0;
        nextBase = false;
        hHead = 0;
        hCount = 0;
        settledThrough = NONE;
        iHead = 0;
        iCount = 0;
    }

    private boolean knows(long start) {
        for (int i = 0; i < knownCount; i++) {
            if (known[i] == start) {
                return true;
            }
        }
        return false;
    }

    /** Files again every unsettled pose at or after {@code fromMicros}, from its raw position. */
    private void refile(long fromMicros, PoseRing poses, TimedAnswers answers, int fighter, Outbox out) {
        long from = settledThrough == NONE ? fromMicros : Math.max(fromMicros, settledThrough + 1);
        if (poses.size() != hCount || (hCount > 0 && poses.newestTime() != hTime[back(0)])) {
            return; // poses filed around the bound (a test's): not its to file again
        }
        int k = 0;
        while (k < hCount && hTime[back(k)] >= from) {
            k++;
        }
        if (k == 0) {
            return;
        }
        poses.dropFrom(from);
        hHead = Math.floorMod(hHead - k, size);
        hCount -= k;
        for (int i = 0; i < k; i++) {
            int s = hHead; // the slot it's filed into again holds its raw pose until then
            file(
                    hTime[s], hX[s], hY[s], hZ[s], hYaw[s], hPitch[s], hEye[s], hScale[s], hBase[s], poses, answers,
                    fighter, out);
        }
    }

    private void file(
            long t,
            double x,
            double y,
            double z,
            double yaw,
            double pitch,
            double eye,
            double scale,
            boolean base,
            PoseRing poses,
            TimedAnswers answers,
            int fighter,
            Outbox out) {
        if (hCount > 0 && hTime[back(0)] == t) { // the same tick again: it replaces the newest
            base |= hBase[back(0)];
            poses.dropFrom(t);
            hHead = back(0);
            hCount--;
        }
        if (hCount == size) {
            int oldest = oldest(0);
            if (settledThrough == NONE || hTime[oldest] > settledThrough) {
                settleOne(oldest, fighter, out); // it leaves the history unsettled: what it showed counts now
            }
        }
        double fx = x;
        double fy = y;
        double fz = z;
        double moved = 0;
        double rose = 0;
        boolean free = false;
        double raw = 0;
        double allowed = 0;
        MoveRules r = rules;
        if (r != null && !base && hCount > 0) {
            int ref = back(0);
            double top = scale;
            for (int i = 0; i < hCount; i++) {
                int s = back(i);
                ref = s;
                top = Math.max(top, hScale[s]);
                if (hTime[s] <= t - windowMicros || hBase[s]) {
                    break; // the window's start, or a teleport inside it: nothing before counts
                }
            }
            long start = hTime[ref];
            double stretch = Math.max(1, (double) (t - start) / windowMicros);
            double dx = x - bX[ref];
            double dy = y - bY[ref];
            double dz = z - bZ[ref];
            double across = Math.sqrt(dx * dx + dz * dz);
            moved = across / stretch;
            rose = Math.max(0, dy) / stretch;
            raw = Math.sqrt(dx * dx + dy * dy + dz * dz);
            allowed = raw;
            if (Double.isInfinite(top)) {
                blocksPerTick = Math.max(
                        r.windowBlocks() / r.windowTicks(),
                        across / Math.max(1, (double) (t - start) / Micros.PER_TICK));
            } else {
                double impulses = impulses(start, t);
                double lead = steps(answers, start, t) + impulses;
                double limitAcross = r.windowBlocks() * top * stretch + lead;
                double limitUp = r.riseBlocks() * top * stretch + impulses;
                double share = 1;
                if (across > limitAcross) {
                    share = limitAcross / across;
                }
                if (dy > limitUp) {
                    share = Math.min(share, limitUp / dy);
                }
                if (share < 1) {
                    fx = bX[ref] + dx * share;
                    fy = bY[ref] + dy * share;
                    fz = bZ[ref] + dz * share;
                    allowed = raw * share;
                }
                free = lead == 0 && moved > r.freeDashBlocks() * top && rose < r.freeDashRise() * top;
                blocksPerTick = r.windowBlocks() / r.windowTicks() * top;
            }
        }
        int s = hHead;
        hTime[s] = t;
        hX[s] = x;
        hY[s] = y;
        hZ[s] = z;
        hYaw[s] = yaw;
        hPitch[s] = pitch;
        hEye[s] = eye;
        hScale[s] = scale;
        hBase[s] = base;
        bX[s] = fx;
        bY[s] = fy;
        bZ[s] = fz;
        hMoved[s] = moved;
        hRose[s] = rose;
        hFree[s] = free;
        hRaw[s] = raw;
        hAllowed[s] = allowed;
        hHead = (hHead + 1) % size;
        hCount = Math.min(hCount + 1, size);
        if (Double.isNaN(eye)) {
            poses.put(t, fx, fy, fz, yaw, pitch);
        } else {
            poses.put(t, fx, fy, fz, yaw, pitch, eye);
        }
    }

    /** The Steps' envelope over the window from {@code from} to {@code to}: each tick of a Step whose span meets it. */
    private double steps(TimedAnswers answers, long from, long to) {
        double sum = 0;
        int c = answers.count(TimedAnswers.Kind.STEP);
        for (int i = 0; i < c; i++) {
            long start = answers.at(TimedAnswers.Kind.STEP, i);
            for (int k = 0; k < step.length; k++) {
                long opens = start + Micros.ofTicks(k) - leadMicros;
                long closes = start + Micros.ofTicks(k + 2L); // its tick's end, and one more
                if (opens < to && closes > from) {
                    sum += step[k];
                }
            }
        }
        return sum;
    }

    /** The impulses' envelope over the window from {@code from} to {@code to}. */
    private double impulses(long from, long to) {
        double sum = 0;
        for (int i = 0; i < iCount; i++) {
            if (iFrom[i] < to && iUntil[i] > from) {
                sum += iBlocks[i];
            }
        }
        return sum;
    }

    /** Every pose at or before {@code horizonMicros} not yet settled: its figures count, its clamp is published. */
    private void settle(long horizonMicros, int fighter, Outbox out) {
        for (int i = 0; i < hCount; i++) {
            int s = oldest(i);
            long t = hTime[s];
            if (t > horizonMicros) {
                return;
            }
            if (settledThrough == NONE || t > settledThrough) {
                settleOne(s, fighter, out);
            }
        }
    }

    private void settleOne(int s, int fighter, Outbox out) {
        settledThrough = hTime[s];
        fastest = Math.max(fastest, hMoved[s]);
        rise = Math.max(rise, hRose[s]);
        if (hFree[s]) {
            freeDashes++;
        }
        if (hAllowed[s] < hRaw[s]) {
            clamps++;
            out.add(new Outbox.PoseClamped(fighter, Micros.tickOf(hTime[s]), hRaw[s], hAllowed[s]));
        }
    }

    /** The history's {@code i}th newest slot (0: the newest). */
    private int back(int i) {
        return Math.floorMod(hHead - 1 - i, size);
    }

    /** The history's {@code i}th oldest slot. */
    private int oldest(int i) {
        return Math.floorMod(hHead - hCount + i, size);
    }

    /** The stage's {@code i}th oldest slot. */
    private int staged(int i) {
        return (sHead + i) % STAGE;
    }
}
