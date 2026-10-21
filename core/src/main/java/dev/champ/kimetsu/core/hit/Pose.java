package dev.champ.kimetsu.core.hit;

/**
 * Where a body was and how it looked at one moment: its feet, its look yaw and pitch (degrees), and its eye height if
 * it wasn't standing (vanilla's crouch, swim and glide lower it). Mutable, reused.
 */
public final class Pose {
    long time;
    double x;
    double y;
    double z;
    double yaw;
    double pitch;
    double eye = Double.NaN; // NaN: standing, at the body's own eye height

    /** A standing pose. */
    public Pose set(long timeMicros, double x, double y, double z, double yaw, double pitch) {
        return set(timeMicros, x, y, z, yaw, pitch, Double.NaN);
    }

    /** A pose with its eyes {@code eye} above its feet; NaN for standing. */
    public Pose set(long timeMicros, double x, double y, double z, double yaw, double pitch, double eye) {
        this.time = timeMicros;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.eye = eye;
        return this;
    }

    public long time() {
        return time;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double yaw() {
        return yaw;
    }

    public double pitch() {
        return pitch;
    }

    /** Its eye height, or {@code standing} if it was standing. */
    public double eyeOr(double standing) {
        return Double.isNaN(eye) ? standing : eye;
    }

    /** {@code a} turned toward {@code b} by the share {@code t}, the short way round (yaw has no bound in Minecraft). */
    static double lerpAngle(double a, double b, double t) {
        return a + wrap(b - a) * t;
    }

    /** An angle difference brought into [−180, 180). */
    static double wrap(double degrees) {
        double d = degrees % 360;
        if (d >= 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }
}
