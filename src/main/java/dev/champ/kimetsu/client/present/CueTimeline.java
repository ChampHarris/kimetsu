package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.machine.State;

/**
 * When each world cue shows (D-144's craft call 4; D-145's A1, A6, A8), on the frame's scheduled time
 * ({@code PresentClock.scheduled()}), never a model's time, so a hit-stop can't delay a cue. Times in µs.
 *
 * <ul>
 *   <li><b>The white glint</b> shows from its cue to its impact. Its flare shows for one cue step from half the
 *       target's beat before the impact, when that falls inside the cue (D-134 (4) as D-145's A1 reads it: at Water's
 *       650 ms beat, 3.5 ticks into a swipe's cue, 7.5 into a heavy's; a lead under a half-beat gets none).
 *   <li><b>A feint's shatter</b> (the cancel's {@code why} is a feint) plays the sheet's strip a cue step a frame from
 *       the later of its cancel tick and the first frame that knew of the cancel (A6: at 150 ms RTT the cancel arrives
 *       after its tick), and wins over a flare it overlaps. Any other cancel ends a cue at once, with no shards.
 *   <li><b>The Thread</b> shows from the tick it opened (a negative visual offset must not show it early) until the
 *       tick it closes ({@code Long.MAX_VALUE}: open for good; {@code Long.MIN_VALUE}: closed).
 *   <li><b>The clash flash</b> shows for its length from its time, and the first frame that showed it keeps its stamp.
 * </ul>
 */
public final class CueTimeline {
    /** No time: no cancel, not yet known, no flash. */
    public static final long NONE = Long.MIN_VALUE;

    /** What a white strike's glint shows. */
    public enum Look {
        NONE,
        GLINT,
        FLARE,
        SHATTER
    }

    private CueTimeline() {}

    /**
     * The white glint of a strike cued at {@code cue} to land at {@code impact}, at {@code t}: cancelled at
     * {@code cancel} ({@link #NONE} if not), a feint's if {@code feint}, the cancel first known to a frame at
     * {@code known}; the target's beat {@code period}; cue steps of {@code step}; a shatter of {@code shards} frames.
     */
    public static Look glint(
            long t, long cue, long impact, long cancel, boolean feint, long known, long period, long step, int shards) {
        if (cancel != NONE && feint) {
            long start = shatterStart(cancel, known);
            if (t >= start) {
                return t - start < shards * step ? Look.SHATTER : Look.NONE;
            }
        } else if (cancel != NONE && t >= cancel) {
            return Look.NONE;
        }
        if (t < cue || t >= impact) {
            return Look.NONE;
        }
        long flare = impact - period / 2;
        return period > 0 && flare >= cue && t >= flare && t - flare < step ? Look.FLARE : Look.GLINT;
    }

    /** Whether a red or purple cue shows at {@code t}: from its cue to its impact, until any cancel. */
    public static boolean shows(long t, long cue, long impact, long cancel) {
        return t >= cue && t < impact && (cancel == NONE || t < cancel);
    }

    /** When a feint's shatter starts: its cancel tick, or the first frame that knew of the cancel if that's later. */
    public static long shatterStart(long cancel, long known) {
        return Math.max(cancel, known);
    }

    /** The step a stepped cue is on at {@code t}, counted from {@code from} in steps of {@code step}. */
    public static int frame(long t, long from, long step) {
        return (int) Math.floorDiv(t - from, step);
    }

    /** How far a cue has come at {@code t}: 0 at its cue, 1 at its impact. */
    public static float progress(long t, long cue, long impact) {
        return impact > cue ? Math.clamp((t - cue) / (float) (impact - cue), 0f, 1f) : 1f;
    }

    /**
     * Whether the Thread shows at {@code t}: from {@code opened} (the tick its {@code MobState} first held it open, in
     * µs; {@link #NONE} if never seen opening) until {@code closesTick}, its {@code threadUntil}.
     */
    public static boolean threadShows(long t, long opened, long closesTick) {
        if (opened == NONE || closesTick == Long.MIN_VALUE || t < opened) {
            return false;
        }
        return closesTick == Long.MAX_VALUE || t < Micros.ofTicks(closesTick);
    }

    /** The neck box the Thread runs along: the kneeling one while its demon is COLLAPSED, the standing one otherwise. */
    public static Sweep.Box neck(DemonProfile.Neck neck, State state) {
        return state == State.COLLAPSED ? neck.collapsed() : neck.standing();
    }

    /** Whether the clash flash at {@code flash} ({@link #NONE}: none) shows at {@code t}, for {@code length}. */
    public static boolean flashShows(long t, long flash, long length) {
        return flash != NONE && t >= flash && t - flash < length;
    }

    /** A first-shown stamp: the one it has, or {@code t} if it has none (the frame that first showed the thing). */
    public static long stamp(long stamp, long t) {
        return stamp != NONE ? stamp : t;
    }
}
