package dev.champ.kimetsu.core.hit;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import java.util.Arrays;

/**
 * Sweeps one attacker's active tick (Rules 7.1–7.4; the tick order's step 4): for each of the tick's sub-steps, it
 * places the attack's box (or the quad its blade sweeps between two arc samples) where the attacker stood and aimed,
 * and tests it against every target's box there; a contact counts once a clear line runs from the attacker's eyes to
 * it. Each hit window hits each target once, its body and (for a horizontal cut) its neck apart. D-034's late sweeps
 * run through it too, a tick at a time and in order, reading the pose history at the tick being swept.
 *
 * <p>The swing's aim follows the look, but turns at most {@code max_turn_deg_per_tick} a tick (yaw and pitch together),
 * so flicking the mouse can't turn a slash into a half-circle. The pitch it follows is the sweep's own: none for a
 * level sweep, the look clamped for a {@code look_clamped} one, so the turn isn't spent on pitch the sweep ignores. An
 * attacker that moved further than {@code teleport_blocks} in the tick was carried, not swinging: only the tick's end
 * is swept. The eyes are the pose's own (a crouch lowers them), clamped to the body's standing height.
 *
 * <p>Line of sight is decided once per target per tick, at its first contact, with up to {@code los_rays} rays (the
 * point of the target nearest the sweep, then one high on its body); the target's later contacts that tick, its neck's
 * included, keep that verdict. So a tick costs at most {@code los_rays} block raycasts per target. One sweeper per
 * world, not shared across threads; it allocates nothing once its per-target arrays have grown to the crowd's size.
 */
public final class Sweeper {
    private static final byte UNSEEN = 0;
    private static final byte SEEN = 1;
    private static final byte HIDDEN = 2;

    private final Pose start = new Pose();
    private final Pose end = new Pose();
    private Frame previous = new Frame();
    private Frame current = new Frame();
    private final Obb box = new Obb();
    private final Obb target = new Obb();
    private final Tri first = new Tri();
    private final Tri second = new Tri();
    private boolean secondHit;
    private final V3 b0 = new V3();
    private final V3 t0 = new V3();
    private final V3 b1 = new V3();
    private final V3 t1 = new V3();
    private final V3 near = new V3();
    private final V3 hit = new V3();
    private final V3 scratch = new V3();
    private byte[] verdicts = new byte[16]; // each target's line of sight this tick
    private double slack;

    /**
     * Sweeps {@code shape}'s attack at active frame {@code activeFrame} (the frame from its first active tick, holds
     * left out) in engine step {@code tick}, for the swing {@code swingKey} of attacker {@code attacker}, whose poses
     * are {@code poses}. Contacts go to {@code out}; returns how many it added. An active frame with no hit window
     * open sweeps nothing but still turns the swing's aim.
     *
     * @throws IllegalStateException if {@code shape} was compiled for other sub-steps than {@code rules} now has (a
     *     reload changed them), or if the pose history, full, no longer reaches back to the tick before
     *     {@code tick}: a sweep later than its history, which D-037's tick-by-tick sweeping never asks for
     */
    public int sweep(
            long attacker,
            long swingKey,
            SweepShape shape,
            int activeFrame,
            long tick,
            PoseRing poses,
            Targets targets,
            SwingTrack track,
            Sight sight,
            HitRules rules,
            Contacts out) {
        int subSteps = rules.subSteps();
        if (shape.subSteps != subSteps) {
            throw new IllegalStateException(shape.frames.id() + "'s sweep was compiled for " + shape.subSteps
                    + " sub-steps, and the rules now have " + subSteps + ": compile it again");
        }
        long from = Micros.ofTicks(tick - 1);
        if (poses.full() && from < poses.oldestTime()) {
            throw new IllegalStateException("the pose history no longer reaches tick " + (tick - 1) + " to sweep "
                    + tick + ": it starts at " + poses.oldestTime() + " µs");
        }
        FrameData f = shape.frames;
        int window = f.windowAt(activeFrame);
        boolean horizontal = window >= 0 && f.hits().get(window).horizontal();
        Sweep s = shape.sweep;
        track.begin(swingKey);
        poses.at(from, start); // a young history (a fight just begun) clamps to its first pose
        poses.at(Micros.ofTicks(tick), end); // a pose not yet arrived clamps to the newest, the server's own view
        if (!track.aimed()) {
            track.aim(start.yaw, pitchFor(s, start.pitch, rules)); // the swing starts aimed where the look was
        }
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean carried = moved > rules.teleportBlocks();
        slack = shape.arc && !carried
                ? moved / subSteps
                : 0; // a quad's trailing edge stands where the last sub-step did
        double eye0 = eye(start, shape);
        double eye1 = eye(end, shape);
        double step = rules.maxTurnDegPerTick() / subSteps;
        double aimYaw = track.yaw();
        double aimPitch = track.pitch();
        previous.set(start.x, start.y, start.z, eye0, aimYaw, aimPitch);
        int added = 0;
        resetSight(targets.size());
        for (int k = 1; k <= subSteps; k++) {
            double t = (double) k / subSteps;
            double turnYaw = Pose.wrap(Pose.lerpAngle(start.yaw, end.yaw, t) - aimYaw);
            double turnPitch = pitchFor(s, start.pitch + (end.pitch - start.pitch) * t, rules) - aimPitch;
            double turn = Math.sqrt(turnYaw * turnYaw + turnPitch * turnPitch);
            if (turn > step) {
                turnYaw *= step / turn;
                turnPitch *= step / turn;
            }
            aimYaw += turnYaw;
            aimPitch += turnPitch;
            if (window < 0) {
                continue;
            }
            double px = start.x + dx * t;
            double py = start.y + dy * t;
            double pz = start.z + dz * t;
            double eye = eye0 + (eye1 - eye0) * t;
            current.set(px, py, pz, eye, aimYaw, aimPitch);
            if (carried) {
                if (k < subSteps) {
                    swap();
                    continue;
                }
                previous.set(px, py, pz, eye, aimYaw, aimPitch); // no quad across the jump
            }
            if (shape.arc) {
                quad(shape, activeFrame * subSteps + k - 1);
            } else {
                current.box(s.box(), box);
            }
            for (int j = 0; j < targets.size(); j++) {
                added += touch(
                        attacker,
                        swingKey,
                        shape,
                        window,
                        horizontal,
                        tick,
                        k,
                        t,
                        j,
                        targets,
                        track,
                        sight,
                        rules,
                        out);
            }
            swap();
        }
        track.aim(aimYaw, aimPitch);
        return added;
    }

    /** Target {@code j} at sub-step {@code k}: its body, then (for a horizontal cut) its neck. */
    private int touch(
            long attacker,
            long swingKey,
            SweepShape s,
            int window,
            boolean horizontal,
            long tick,
            int k,
            double t,
            int j,
            Targets targets,
            SwingTrack track,
            Sight lines,
            HitRules rules,
            Contacts out) {
        int id = targets.id(j);
        int added = 0;
        if (!track.has(id, window, SwingTrack.BODY)) {
            targets.boxAt(j, t, target);
            if (inReach(s) && overlaps(s) && seen(s, j, t, targets, lines, rules)) {
                track.mark(id, window, SwingTrack.BODY);
                out.add(attacker, swingKey, window, id, tick, k, SwingTrack.BODY, hit);
                added++;
            }
        }
        if (horizontal && targets.hasNeck(j) && !track.has(id, window, SwingTrack.NECK)) {
            targets.neckAt(j, t, target);
            if (inReach(s) && overlaps(s) && seen(s, j, t, targets, lines, rules)) {
                track.mark(id, window, SwingTrack.NECK);
                out.add(attacker, swingKey, window, id, tick, k, SwingTrack.NECK, hit);
                added++;
            }
        }
        return added;
    }

    /**
     * Whether the target lies within the shape's reach of the attacker's feet: the cheapest test first. An arc's quad
     * also reaches back to where the previous sub-step stood.
     */
    private boolean inReach(SweepShape s) {
        double fx = current.feetX();
        double fy = current.feetY();
        double fz = current.feetZ();
        target.nearest(fx, fy, fz, near);
        double ddx = near.x - fx;
        double ddy = near.y - fy;
        double ddz = near.z - fz;
        double r = s.reach + slack;
        return ddx * ddx + ddy * ddy + ddz * ddz <= r * r;
    }

    /** The bounds first, then the separating axes. */
    private boolean overlaps(SweepShape s) {
        double bx = target.reachX();
        double by = target.reachY();
        double bz = target.reachZ();
        double minX = target.cx - bx;
        double minY = target.cy - by;
        double minZ = target.cz - bz;
        double maxX = target.cx + bx;
        double maxY = target.cy + by;
        double maxZ = target.cz + bz;
        if (!s.arc) {
            return box.boundsOverlap(minX, minY, minZ, maxX, maxY, maxZ) && Sat.overlap(box, target);
        }
        double r = s.radius;
        if (first.boundsOverlap(r, minX, minY, minZ, maxX, maxY, maxZ) && Sat.overlap(first, r, target)) {
            secondHit = false;
            return true;
        }
        secondHit = true;
        return second.boundsOverlap(r, minX, minY, minZ, maxX, maxY, maxZ) && Sat.overlap(second, r, target);
    }

    /**
     * Whether the attacker's eyes see target {@code j} this tick (Rule 7.3), deciding it at its first contact: the
     * point of the part touched nearest the sweep, then, if a wall is in the way and a second ray is allowed, one aimed
     * from high on its body. Leaves the contact point in {@code hit} either way.
     */
    private boolean seen(SweepShape s, int j, double t, Targets targets, Sight lines, HitRules rules) {
        contactPoint(s, target.cx, target.cy, target.cz);
        byte verdict = verdicts[j];
        if (verdict != UNSEEN) {
            return verdict == SEEN;
        }
        boolean clear = lines.clear(current.eyeX(), current.eyeY(), current.eyeZ(), hit.x, hit.y, hit.z);
        if (!clear && rules.losRays() >= 2) {
            targets.upperAt(j, t, scratch);
            contactPoint(s, scratch.x, scratch.y, scratch.z);
            clear = lines.clear(current.eyeX(), current.eyeY(), current.eyeZ(), hit.x, hit.y, hit.z);
        }
        verdicts[j] = clear ? SEEN : HIDDEN;
        return clear;
    }

    /** The point of the target nearest the sweep, starting from {@code (px, py, pz)}: into {@code hit}. */
    private void contactPoint(SweepShape s, double px, double py, double pz) {
        if (!s.arc) {
            box.nearest(px, py, pz, near);
        } else if (secondHit) {
            second.nearest(px, py, pz, near);
        } else {
            first.nearest(px, py, pz, near);
        }
        target.nearest(near.x, near.y, near.z, hit);
    }

    /** The quad the blade sweeps from sample {@code i} (placed at the previous sub-step) to {@code i + 1}. */
    private void quad(SweepShape s, int i) {
        double[] p = s.samples;
        int a = 6 * i;
        int b = 6 * (i + 1);
        previous.toWorld(p[a], p[a + 1], p[a + 2], b0);
        previous.toWorld(p[a + 3], p[a + 4], p[a + 5], t0);
        current.toWorld(p[b], p[b + 1], p[b + 2], b1);
        current.toWorld(p[b + 3], p[b + 4], p[b + 5], t1);
        first.set(b0, t0, t1);
        second.set(b0, t1, b1);
    }

    private void resetSight(int targets) {
        if (verdicts.length < targets) {
            verdicts = new byte[Math.max(targets, verdicts.length * 2)];
        }
        Arrays.fill(verdicts, 0, targets, UNSEEN);
    }

    /** The pose's eyes, clamped to the body's standing height (the shape's reach is computed for no higher). */
    private static double eye(Pose p, SweepShape s) {
        return Math.clamp(p.eyeOr(s.maxEye), 0, s.maxEye);
    }

    /** The pitch a sweep follows: none for a level one, the look clamped for a {@code look_clamped} one. */
    private static double pitchFor(Sweep sweep, double pitch, HitRules rules) {
        return switch (sweep.pitch()) {
            case LEVEL -> 0;
            case LOOK -> pitch;
            case LOOK_CLAMPED -> Math.clamp(pitch, -rules.horizontalPitchClampDeg(), rules.horizontalPitchClampDeg());
        };
    }

    private void swap() {
        Frame f = previous;
        previous = current;
        current = f;
    }
}
