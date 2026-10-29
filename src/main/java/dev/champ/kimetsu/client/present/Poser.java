package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.strike.StrikeView;

/**
 * Works out a stand-in from its track and strikes (5d-3a Step 3, D-138), all at its model time.
 *
 * <ul>
 *   <li><b>The layer up</b> ({@link #layerAt}): of its strike (from its windup's start to its end), its hold and clash
 *       windows and a reaction stance (from its tick), the newest to begin wins; ties go to the hold, then the clash,
 *       then the reaction. With none up, its guard or neutral stance shows. So a Posture break ends a strike's pose and
 *       a strike begun after a knockdown ends that one, whatever order the messages came in.
 *   <li><b>The blend</b> ({@link #pose}): from the last change of layer within {@code pose_blend_ticks}, the old layer
 *       frozen where it was left blends into the new one, so nothing jumps; a change inside another's blend starts from
 *       the old layer's own pose, a small step (known limit).
 *   <li><b>The body's place</b> ({@link #place}, spec:534): on the path of its newest strike to have locked, as the
 *       server's mob rides it (Rule 7.13; a strike not locked yet leaves the body to the one before, D-140): onto it over
 *       {@code blend_in_ticks} from its lock, from where the strike before left the body and otherwise from the entity's
 *       own position (D-139), on it through the strike, then back to the entity's own position over
 *       {@code blend_back_ticks} from the path's last place and the weight it had there, at its recovery's end or its
 *       cancel. A strike that never locked is passed over for the newest one before it that did.
 * </ul>
 *
 * One per client, reused; main thread only; allocates nothing.
 */
public final class Poser {
    private static final int EDGES = 8;
    private static final int WALK = 4; // how many strikes back the place looks for one that locked

    private final Layer to = new Layer();
    private final Layer from = new Layer();
    private final Layer probe = new Layer();
    private final Layer probeBefore = new Layer();
    private final Joints a = new Joints();
    private final Joints b = new Joints();
    private final StrikeSpan span = new StrikeSpan();
    // the strike placing the body, the newest locked before it, and the one before that: as deep as a blend can reach
    private final StrikeSpan[] chain = {new StrikeSpan(), new StrikeSpan(), new StrikeSpan()};
    private final Pose pose = new Pose();
    private final long[] edges = new long[EDGES];
    private long fromAt;

    /** The layer up at {@code t}, into an instance this reuses (good until its next call). */
    public Layer layerAt(Track k, StrikeSource strikes, long t) {
        select(k, strikes, t, to);
        return to;
    }

    /** Fills {@code out} with the stand-in's pose at {@code t}; the clang recoil runs {@code recoilMicros}. */
    public void pose(Track k, StrikeSource strikes, long t, Feel feel, long recoilMicros, StandInPose out) {
        float f = transition(k, strikes, t, feel.poseBlendMicros());
        joints(to, t, b);
        if (f < 1) {
            joints(from, fromAt, a);
            out.blend(a, b, f);
        } else {
            out.blend(b, b, 1);
        }
        out.guardYaw = k.guardYawAt(t);
        long r = k.recoilFrom();
        out.recoil = r != Track.NONE && recoilMicros > 0 && t >= r && t < r + recoilMicros
                ? StandInPoses.RECOIL * (1 - (t - r) / (float) recoilMicros)
                : 0f;
    }

    /**
     * Fills {@code out} with where the body is drawn at {@code t}: by the newest strike to have locked by then, as the
     * server's mob rides it (Rule 7.13), on its path while it runs and from its last place after.
     */
    public void place(StrikeSource strikes, long t, Feel feel, Placement out) {
        out.weight = 0;
        if (!locked(strikes, t, t, chain[0])) {
            return; // nothing placed it: the entity's own
        }
        StrikeSpan s = chain[0];
        StrikeView path = s.path();
        float w = weight(strikes, 0, t, feel);
        if (path != null && w > 0) {
            at(path, Math.min(t, s.end()), w, out);
        }
    }

    /**
     * The newest strike begun by {@code at} whose path had locked by {@code by}, into {@code out}: past any that never
     * locked, as the server's mob stays on the last locked path (a string's next strike broken before its cue).
     */
    private static boolean locked(StrikeSource strikes, long at, long by, StrikeSpan out) {
        long from = at;
        for (int i = 0; i < WALK && strikes.newest(from, out); i++) {
            if (out.path() != null && out.lock() <= by) {
                return true;
            }
            from = out.start() - 1;
        }
        return false;
    }

    /**
     * The weight {@code chain[d]}'s path holds the body at at {@code t}: its blend in while it runs, then its blend back
     * over {@code blend_back_ticks} from the weight it ended at, so a cancel inside the blend in doesn't jump.
     */
    private float weight(StrikeSource strikes, int d, long t, Feel feel) {
        long end = chain[d].end();
        if (t < end) {
            return onto(strikes, d, t, feel);
        }
        long back = feel.blendBackMicros();
        if (back <= 0 || t - end >= back) {
            return 0f;
        }
        return onto(strikes, d, end, feel) * (1 - (t - end) / (float) back);
    }

    /**
     * How far onto {@code chain[d]}'s path the body is at {@code t}, over {@code blend_in_ticks} from the path's lock. The
     * server locks a path where the strike before held its mob (Rule 7.13), so the blend starts from the weight the
     * newest strike locked before it still held the body at there (D-140): all of it while that one ran (a string's next
     * strike takes over at once), its blend back's if it had ended, none if none did. The rest comes from the entity,
     * which trails the server's by its interpolation (about 2 ticks, 0.35 blocks at a walk, D-139).
     */
    private float onto(StrikeSource strikes, int d, long t, Feel feel) {
        StrikeSpan s = chain[d];
        long lock = s.lock();
        long in = feel.blendInMicros();
        if (in <= 0 || t - lock >= in) {
            return 1f;
        }
        float from = d + 1 < chain.length && locked(strikes, s.start() - 1, lock, chain[d + 1])
                ? weight(strikes, d + 1, lock, feel)
                : 0f;
        return from + (1 - from) * Math.max(0f, (t - lock) / (float) in);
    }

    private void at(StrikeView path, long t, float weight, Placement out) {
        path.attackerAtMicros(t, pose);
        out.weight = weight;
        out.x = pose.x();
        out.y = pose.y();
        out.z = pose.z();
        out.yaw = (float) path.yaw();
    }

    /** {@code degrees} in [0, 360). */
    public static float wrap(float degrees) {
        float w = degrees % 360f;
        return w < 0 ? w + 360f : w;
    }

    /** The turn from one heading to another the short way round, in (−180, 180]. */
    static float shortest(float delta) {
        float w = wrap(delta);
        return w > 180f ? w - 360f : w;
    }

    private void select(Track k, StrikeSource strikes, long t, Layer out) {
        boolean current = k.stanceFrom != Track.NONE && t >= k.stanceFrom;
        Stance base = current ? k.stance : k.before;
        long baseFrom = current ? k.stanceFrom : k.beforeFrom;
        out.stance(base.reaction() ? Stance.NEUTRAL : base);
        long best = Long.MIN_VALUE;
        int rank = -1;
        if (strikes.newest(t, out.strike) && t < out.strike.end()) {
            out.striking();
            best = out.strike.start();
            rank = 0;
        }
        if (base.reaction() && baseFrom != Track.NONE && wins(baseFrom, 1, best, rank)) {
            out.stance(base);
            best = baseFrom;
            rank = 1;
        }
        if (k.clashFrom != Track.NONE && t >= k.clashFrom && t < k.clashUntil && wins(k.clashFrom, 2, best, rank)) {
            out.stance(Stance.CLASH);
            best = k.clashFrom;
            rank = 2;
        }
        if (k.holdFrom != Track.NONE && t >= k.holdFrom && t < k.holdUntil && wins(k.holdFrom, 3, best, rank)) {
            out.stance(Stance.HOLD);
        }
    }

    private static boolean wins(long start, int rank, long best, int bestRank) {
        return bestRank < 0 || start > best || (start == best && rank > bestRank);
    }

    /**
     * Selects the layer up at {@code t} into {@link #to} and, for the latest change of layer within {@code blend}
     * before it, the layer it left into {@link #from} at {@link #fromAt}; the blend's fraction, 1 when none runs.
     */
    private float transition(Track k, StrikeSource strikes, long t, long blend) {
        select(k, strikes, t, to);
        if (blend <= 0) {
            return 1;
        }
        int n = 0;
        n = edge(k.stanceFrom, t, blend, n);
        n = edge(k.beforeFrom, t, blend, n);
        n = edge(k.holdFrom, t, blend, n);
        n = edge(k.holdUntil, t, blend, n);
        n = edge(k.clashFrom, t, blend, n);
        n = edge(k.clashUntil, t, blend, n);
        if (strikes.newest(t, span)) {
            n = edge(span.start(), t, blend, n);
            n = edge(span.end(), t, blend, n);
        }
        for (int i = 0; i < n; i++) { // latest first
            long c = edges[i];
            select(k, strikes, c - 1, probeBefore);
            select(k, strikes, c, probe);
            if (!probeBefore.same(probe)) {
                from.copy(probeBefore);
                fromAt = c;
                return (t - c) / (float) blend;
            }
        }
        return 1;
    }

    /** Adds {@code c} to the edges, latest first, if it lies in ({@code t − blend}, {@code t}]; returns the count. */
    private int edge(long c, long t, long blend, int n) {
        if (c == Track.NONE || c > t || c <= t - blend || n == EDGES) {
            return n;
        }
        int i = n;
        while (i > 0 && edges[i - 1] < c) {
            edges[i] = edges[i - 1];
            i--;
        }
        edges[i] = c;
        return n + 1;
    }

    private static void joints(Layer l, long t, Joints out) {
        if (l.kind() == Layer.Kind.STRIKE) {
            StandInPoses.strike(l.strike.windup(t), l.strike.swing(t), out);
        } else {
            out.copy(StandInPoses.of(l.stance()));
        }
    }
}
