package dev.champ.kimetsu.core.hit;

/**
 * Whether something lies in front: the guard's 120° arc (Rule 6.1), the deflect's (Rule 2.6) and the demon's guard
 * stance's (Rule 11.4). Flat: only yaw counts, and a point straight above or below counts as in front.
 */
public final class Facing {
    private Facing() {}

    /**
     * Whether the point {@code (dx, dz)} away (not normalised) lies within {@code halfArcDeg} of yaw {@code yawDeg}
     * (Minecraft's: forward = (−sin ψ, cos ψ)). No square root: f·d ≥ 0 and (f·d)² ≥ cos²(half) |d|², which holds for
     * half-arcs up to 90°.
     */
    public static boolean inArc(double yawDeg, double halfArcDeg, double dx, double dz) {
        double fx = -Trig.sinDeg(yawDeg);
        double fz = Trig.cosDeg(yawDeg);
        double c = Trig.cosDeg(halfArcDeg);
        double dot = fx * dx + fz * dz;
        return dot >= 0 && dot * dot >= c * c * (dx * dx + dz * dz);
    }
}
