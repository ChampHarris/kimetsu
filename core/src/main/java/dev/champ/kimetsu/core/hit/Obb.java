package dev.champ.kimetsu.core.hit;

/**
 * An oriented box: a centre, three orthonormal axes and the half extents along them (Rule 7.1). Mutable and reused: a
 * sweep builds one per sub-step. An entity's box is the case with the world's axes ({@link #setAabb}).
 */
public final class Obb {
    double cx;
    double cy;
    double cz;
    double ux = 1;
    double uy;
    double uz;
    double vx;
    double vy = 1;
    double vz;
    double wx;
    double wy;
    double wz = 1;
    double ex;
    double ey;
    double ez;

    /** The centre, the axes u, v and w (orthonormal, not checked) and the half extents along them. */
    public Obb set(
            double cx,
            double cy,
            double cz,
            double ux,
            double uy,
            double uz,
            double vx,
            double vy,
            double vz,
            double wx,
            double wy,
            double wz,
            double ex,
            double ey,
            double ez) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.ux = ux;
        this.uy = uy;
        this.uz = uz;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.wx = wx;
        this.wy = wy;
        this.wz = wz;
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
        return this;
    }

    /** A box along the world's axes, by its corners. */
    public Obb setAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return set(
                (minX + maxX) / 2,
                (minY + maxY) / 2,
                (minZ + maxZ) / 2,
                1,
                0,
                0,
                0,
                1,
                0,
                0,
                0,
                1,
                (maxX - minX) / 2,
                (maxY - minY) / 2,
                (maxZ - minZ) / 2);
    }

    /** Half the width of its world-aligned bounds along x. */
    public double reachX() {
        return ex * Math.abs(ux) + ey * Math.abs(vx) + ez * Math.abs(wx);
    }

    public double reachY() {
        return ey * Math.abs(vy) + ex * Math.abs(uy) + ez * Math.abs(wy);
    }

    public double reachZ() {
        return ez * Math.abs(wz) + ex * Math.abs(uz) + ey * Math.abs(vz);
    }

    /** Whether its world-aligned bounds overlap the box given by its corners: the cheap test before the full one. */
    public boolean boundsOverlap(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double hx = reachX();
        double hy = reachY();
        double hz = reachZ();
        return cx - hx < maxX && cx + hx > minX && cy - hy < maxY && cy + hy > minY && cz - hz < maxZ && cz + hz > minZ;
    }

    /** The point of this box nearest {@code (px, py, pz)}, into {@code out}. */
    public V3 nearest(double px, double py, double pz, V3 out) {
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        double a = Math.clamp(dx * ux + dy * uy + dz * uz, -ex, ex);
        double b = Math.clamp(dx * vx + dy * vy + dz * vz, -ey, ey);
        double c = Math.clamp(dx * wx + dy * wy + dz * wz, -ez, ez);
        return out.set(cx + a * ux + b * vx + c * wx, cy + a * uy + b * vy + c * wy, cz + a * uz + b * vz + c * wz);
    }

    public double cx() {
        return cx;
    }

    public double cy() {
        return cy;
    }

    public double cz() {
        return cz;
    }
}
