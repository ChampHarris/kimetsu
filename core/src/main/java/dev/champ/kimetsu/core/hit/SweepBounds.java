package dev.champ.kimetsu.core.hit;

import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;

/**
 * A beat-locked strike's sweep at one moment: an NPC strike is judged entirely as of its impact (Rules 2.10 and 6.2),
 * so its box is placed once, for the attacker's pose then, and asked whether a body lies inside it (a hit or an answer;
 * D-039: an answer needs the defender inside), within a margin of it (Rule 2.9's involvement), and in sight of the
 * attacker's eyes (Rule 7.3, tried as the {@link Sweeper} tries it). A strike's baked arc, when it has one, is judged by
 * its box, the arc's envelope. Reused; allocates nothing.
 */
public final class SweepBounds {
    private final Frame frame = new Frame();
    private final Obb box = new Obb();
    private final Obb grown = new Obb();
    private final Obb body = new Obb();
    private final V3 near = new V3();
    private final V3 contact = new V3();
    private double bodyX;
    private double bodyZ;
    private double bodyMinY;
    private double bodyMaxY;

    /**
     * Places {@code sweep}'s box for an attacker with its feet at {@code (x, y, z)}, eyes {@code eyeHeight} above them,
     * aiming at yaw and pitch in degrees (a strike's locked direction).
     */
    public SweepBounds place(
            Sweep sweep, double x, double y, double z, double eyeHeight, double yawDeg, double pitchDeg) {
        frame.set(x, y, z, eyeHeight, yawDeg, pitchDeg);
        frame.box(sweep.box(), box);
        return this;
    }

    /**
     * Whether the body given by its corners lies inside the placed box: overlapping it, not merely touching (as
     * vanilla's boxes). Remembers the body for {@link #seen}.
     */
    public boolean holds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        setBody(minX, minY, minZ, maxX, maxY, maxZ);
        return box.boundsOverlap(minX, minY, minZ, maxX, maxY, maxZ) && Sat.overlap(box, body);
    }

    /** Whether the body comes within {@code margin} blocks of the placed box (the box grown by it on every side). */
    public boolean within(double margin, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        setBody(minX, minY, minZ, maxX, maxY, maxZ);
        grown.set(
                box.cx,
                box.cy,
                box.cz,
                box.ux,
                box.uy,
                box.uz,
                box.vx,
                box.vy,
                box.vz,
                box.wx,
                box.wy,
                box.wz,
                box.ex + margin,
                box.ey + margin,
                box.ez + margin);
        return Sat.overlap(grown, body);
    }

    /**
     * Whether the attacker's eyes see the body last given to {@link #holds}: a clear line to its point nearest the
     * sweep, or, if a wall is in the way and the rules allow a second line, to the point nearest the sweep from high
     * on its body.
     */
    public boolean seen(Sight sight, HitRules rules) {
        contactFrom(body.cx, body.cy, body.cz);
        if (sight.clear(frame.eyeX(), frame.eyeY(), frame.eyeZ(), contact.x, contact.y, contact.z)) {
            return true;
        }
        if (rules.losRays() < 2) {
            return false;
        }
        contactFrom(bodyX, bodyMinY + 0.9 * (bodyMaxY - bodyMinY), bodyZ);
        return sight.clear(frame.eyeX(), frame.eyeY(), frame.eyeZ(), contact.x, contact.y, contact.z);
    }

    /**
     * The pitch a sweep follows for a look pitched {@code lookPitch}: none for a level sweep, the look for a {@code look}
     * one, the look clamped to the rules' ±30° for a {@code look_clamped} one (Rule 7.1).
     */
    public static double pitchFor(Sweep sweep, double lookPitch, HitRules rules) {
        return switch (sweep.pitch()) {
            case LEVEL -> 0;
            case LOOK -> lookPitch;
            case LOOK_CLAMPED ->
                Math.clamp(lookPitch, -rules.horizontalPitchClampDeg(), rules.horizontalPitchClampDeg());
        };
    }

    private void setBody(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        body.setAabb(minX, minY, minZ, maxX, maxY, maxZ);
        bodyX = (minX + maxX) / 2;
        bodyZ = (minZ + maxZ) / 2;
        bodyMinY = minY;
        bodyMaxY = maxY;
    }

    /** The body's point nearest the box's point nearest {@code (px, py, pz)}: into {@code contact}. */
    private void contactFrom(double px, double py, double pz) {
        box.nearest(px, py, pz, near);
        body.nearest(near.x, near.y, near.z, contact);
    }
}
