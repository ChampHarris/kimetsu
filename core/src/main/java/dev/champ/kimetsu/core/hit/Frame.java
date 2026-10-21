package dev.champ.kimetsu.core.hit;

import dev.champ.kimetsu.core.data.FrameData.Sweep;

/**
 * An attacker's frame at one moment: where its feet are and how it aims (Rule 7.1), its axes from {@link Trig}. x is its right, y up and z
 * forward; yaw ψ is Minecraft's (0 faces +z, forward = (−sin ψ, 0, cos ψ), as {@code Entity.calculateViewVector}), and
 * pitch θ is too (positive looks down). Pitch turns the frame about the eyes, so a look downward lowers the far end of a
 * sweep, not the whole sweep. The axes come straight from ψ and θ, never from a cross product: right, up and forward are
 * a left-handed set in Minecraft's world.
 */
public final class Frame {
    private double ox;
    private double oy;
    private double oz;
    private double eye;
    private double rx;
    private double rz;
    private double upx;
    private double upy;
    private double upz;
    private double fx;
    private double fy;
    private double fz;

    /** The frame at feet {@code (x, y, z)}, eyes {@code eyeHeight} above them, aiming at yaw and pitch in degrees. */
    public Frame set(double x, double y, double z, double eyeHeight, double yawDeg, double pitchDeg) {
        double sy = Trig.sinDeg(yawDeg);
        double cy = Trig.cosDeg(yawDeg);
        double sp = Trig.sinDeg(pitchDeg);
        double cp = Trig.cosDeg(pitchDeg);
        ox = x;
        oy = y;
        oz = z;
        eye = eyeHeight;
        rx = -cy; // right: (−cos ψ, 0, −sin ψ)
        rz = -sy;
        fx = -sy * cp; // forward, pitched down by θ: f cos θ − up sin θ
        fy = -sp;
        fz = cy * cp;
        upx = -sy * sp; // up, pitched: up cos θ + f sin θ
        upy = cp;
        upz = cy * sp;
        return this;
    }

    /** The world point at {@code (lx, ly, lz)} in this frame (from the feet), into {@code out}. */
    public V3 toWorld(double lx, double ly, double lz, V3 out) {
        double h = ly - eye; // about the eyes
        return out.set(
                ox + lx * rx + h * upx + lz * fx, oy + eye + h * upy + lz * fy, oz + lx * rz + h * upz + lz * fz);
    }

    /** The world box this frame puts {@code box} at, into {@code out}. */
    public Obb box(Sweep.Box box, Obb out) {
        double h = box.cy() - eye;
        return out.set(
                ox + box.cx() * rx + h * upx + box.cz() * fx,
                oy + eye + h * upy + box.cz() * fy,
                oz + box.cx() * rz + h * upz + box.cz() * fz,
                rx,
                0,
                rz,
                upx,
                upy,
                upz,
                fx,
                fy,
                fz,
                box.hx(),
                box.hy(),
                box.hz());
    }

    public double feetX() {
        return ox;
    }

    public double feetY() {
        return oy;
    }

    public double feetZ() {
        return oz;
    }

    public double eyeX() {
        return ox;
    }

    public double eyeY() {
        return oy + eye;
    }

    public double eyeZ() {
        return oz;
    }
}
