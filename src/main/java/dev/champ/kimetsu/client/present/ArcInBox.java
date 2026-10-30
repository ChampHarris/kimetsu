package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.hit.V3;

/**
 * A strike's blade path inside its own hitbox (D-144's craft call 2, D-145's A4), until baked arcs and the rig's locators
 * (5d-7): what the cues and trails anchor on, since only the real hitbox carries the telegraph (GDD), and the stand-in
 * chops overhead for every attack.
 *
 * <p>The path is a half-ellipse in the box's attacker frame (x right, y up, z forward, from the feet), in a plane through
 * the near face's centre rolled {@code roll} degrees about the forward axis (0: horizontal, x first; 90: vertical, up
 * first): its side axis reaches the box's edge along that direction, its depth the far face. The tip runs from
 * {@code from} to {@code to} degrees round it (0: the side axis's positive end, 90: the far face's centre, 180: the other
 * side), so the default, {@code roll} 0 from 0 to 180, starts at the attacker's right (the player's left when facing it,
 * clear of the first-person blade) and ends at its left. The base sits {@code base} of the way out from the near face's
 * centre. Every point lies in the box by construction: its terms each stay within a half extent. 5d-4b's {@code fx}
 * override feeds the same three angles, and the glint follows them ({@link #anchor}).
 *
 * <p>Mutable and reused; allocates nothing.
 */
public final class ArcInBox {
    private static final double TINY = 1e-12;

    private double cx;
    private double cy;
    private double cz;
    private double sideX; // the side axis, scaled to the box's edge
    private double sideY;
    private double depth; // near face to far face
    private double from; // radians
    private double to;
    private double base;

    /** A box's default roll: its wider cross-section's plane, horizontal unless it's taller than wide. */
    public static double defaultRoll(Sweep.Box box) {
        return box.hx() >= box.hy() ? 0 : 90;
    }

    /**
     * Fits the arc to {@code box}: rolled {@code rollDeg} about its forward axis, the tip from {@code fromDeg} to
     * {@code toDeg} (each 0 to 180), the base {@code base} (0 to 1) of the way out.
     */
    public ArcInBox set(Sweep.Box box, double rollDeg, double fromDeg, double toDeg, double base) {
        double c = Math.cos(Math.toRadians(rollDeg));
        double s = Math.sin(Math.toRadians(rollDeg));
        double reach = Math.min(
                Math.abs(c) > TINY ? box.hx() / Math.abs(c) : Double.MAX_VALUE,
                Math.abs(s) > TINY ? box.hy() / Math.abs(s) : Double.MAX_VALUE);
        cx = box.cx();
        cy = box.cy();
        cz = box.cz() - box.hz();
        sideX = Math.clamp(c * reach, -box.hx(), box.hx()); // clamped: the last rounding can't leave the face
        sideY = Math.clamp(s * reach, -box.hy(), box.hy());
        depth = 2 * box.hz();
        from = Math.toRadians(Math.clamp(fromDeg, 0, 180));
        to = Math.toRadians(Math.clamp(toDeg, 0, 180));
        this.base = Math.clamp(base, 0, 1);
        return this;
    }

    /** The blade at {@code progress} (0 at the arc's start, 1 at its end), into {@code baseOut} and {@code tipOut}. */
    public void at(double progress, V3 baseOut, V3 tipOut) {
        double theta = from + (to - from) * Math.clamp(progress, 0, 1);
        double side = Math.cos(theta);
        double out = Math.sin(theta);
        double tx = sideX * side;
        double ty = sideY * side;
        double tz = depth * Math.max(0, out);
        tipOut.set(cx + tx, cy + ty, cz + tz);
        baseOut.set(cx + tx * base, cy + ty * base, cz + tz * base);
    }

    /** Where the arc starts, the tip at progress 0: the cue's anchor, where the blow comes from. */
    public void anchor(V3 out) {
        double side = Math.cos(from);
        out.set(cx + sideX * side, cy + sideY * side, cz + depth * Math.max(0, Math.sin(from)));
    }
}
