package dev.champ.kimetsu.core.hit;

/**
 * Separating-axis tests (Rule 7.2). Touching faces aren't overlapping, as with vanilla's boxes ({@code
 * AABB.intersects} is strict), so a Step that just clears a strike clears it. The edge axes are the exception: their
 * epsilon counts a separation under about 10^-8 blocks as a contact.
 */
public final class Sat {
    /**
     * Added to the rotation terms of the nine edge axes only (Ericson, RTCD 4.4.1): when two edges are nearly parallel
     * their cross product nearly vanishes and rounding could invent a separation. On the face axes it would make boxes
     * that only touch overlap, so they use the exact terms.
     */
    private static final double EDGE_EPSILON = 1e-9;

    /** An axis shorter than this (squared) is two parallel edges' cross product: it separates nothing. */
    private static final double DEGENERATE = 1e-20;

    private Sat() {}

    /** Whether two oriented boxes overlap (Gottschalk's 15 axes). */
    public static boolean overlap(Obb a, Obb b) {
        double r00 = a.ux * b.ux + a.uy * b.uy + a.uz * b.uz;
        double r01 = a.ux * b.vx + a.uy * b.vy + a.uz * b.vz;
        double r02 = a.ux * b.wx + a.uy * b.wy + a.uz * b.wz;
        double r10 = a.vx * b.ux + a.vy * b.uy + a.vz * b.uz;
        double r11 = a.vx * b.vx + a.vy * b.vy + a.vz * b.vz;
        double r12 = a.vx * b.wx + a.vy * b.wy + a.vz * b.wz;
        double r20 = a.wx * b.ux + a.wy * b.uy + a.wz * b.uz;
        double r21 = a.wx * b.vx + a.wy * b.vy + a.wz * b.vz;
        double r22 = a.wx * b.wx + a.wy * b.wy + a.wz * b.wz;
        double dx = b.cx - a.cx;
        double dy = b.cy - a.cy;
        double dz = b.cz - a.cz;
        double t0 = dx * a.ux + dy * a.uy + dz * a.uz;
        double t1 = dx * a.vx + dy * a.vy + dz * a.vz;
        double t2 = dx * a.wx + dy * a.wy + dz * a.wz;
        double f00 = Math.abs(r00);
        double f01 = Math.abs(r01);
        double f02 = Math.abs(r02);
        double f10 = Math.abs(r10);
        double f11 = Math.abs(r11);
        double f12 = Math.abs(r12);
        double f20 = Math.abs(r20);
        double f21 = Math.abs(r21);
        double f22 = Math.abs(r22);
        // a's faces, then b's
        if (Math.abs(t0) >= a.ex + b.ex * f00 + b.ey * f01 + b.ez * f02
                || Math.abs(t1) >= a.ey + b.ex * f10 + b.ey * f11 + b.ez * f12
                || Math.abs(t2) >= a.ez + b.ex * f20 + b.ey * f21 + b.ez * f22
                || Math.abs(t0 * r00 + t1 * r10 + t2 * r20) >= a.ex * f00 + a.ey * f10 + a.ez * f20 + b.ex
                || Math.abs(t0 * r01 + t1 * r11 + t2 * r21) >= a.ex * f01 + a.ey * f11 + a.ez * f21 + b.ey
                || Math.abs(t0 * r02 + t1 * r12 + t2 * r22) >= a.ex * f02 + a.ey * f12 + a.ez * f22 + b.ez) {
            return false;
        }
        double e00 = f00 + EDGE_EPSILON;
        double e01 = f01 + EDGE_EPSILON;
        double e02 = f02 + EDGE_EPSILON;
        double e10 = f10 + EDGE_EPSILON;
        double e11 = f11 + EDGE_EPSILON;
        double e12 = f12 + EDGE_EPSILON;
        double e20 = f20 + EDGE_EPSILON;
        double e21 = f21 + EDGE_EPSILON;
        double e22 = f22 + EDGE_EPSILON;
        // the nine edge pairs a_i × b_j
        return !(Math.abs(t2 * r10 - t1 * r20) > a.ey * e20 + a.ez * e10 + b.ey * e02 + b.ez * e01
                || Math.abs(t2 * r11 - t1 * r21) > a.ey * e21 + a.ez * e11 + b.ex * e02 + b.ez * e00
                || Math.abs(t2 * r12 - t1 * r22) > a.ey * e22 + a.ez * e12 + b.ex * e01 + b.ey * e00
                || Math.abs(t0 * r20 - t2 * r00) > a.ex * e20 + a.ez * e00 + b.ey * e12 + b.ez * e11
                || Math.abs(t0 * r21 - t2 * r01) > a.ex * e21 + a.ez * e01 + b.ex * e12 + b.ez * e10
                || Math.abs(t0 * r22 - t2 * r02) > a.ex * e22 + a.ez * e02 + b.ex * e11 + b.ey * e10
                || Math.abs(t1 * r00 - t0 * r10) > a.ex * e10 + a.ey * e00 + b.ey * e22 + b.ez * e21
                || Math.abs(t1 * r01 - t0 * r11) > a.ex * e11 + a.ey * e01 + b.ex * e22 + b.ez * e20
                || Math.abs(t1 * r02 - t0 * r12) > a.ex * e12 + a.ey * e02 + b.ex * e21 + b.ey * e20);
    }

    /**
     * Whether a triangle thickened by {@code r} (a blade's radius) overlaps a box: 13 axes, each triangle projection
     * widened by r × the axis's length, compared squared so no square root is taken. Near a corner of the box it can
     * report a contact up to r(√3 − 1) further out than the rounded thickening would, never one closer in.
     */
    public static boolean overlap(Tri t, double r, Obb b) {
        // the corners in the box's frame
        double ax = t.ax - b.cx;
        double ay = t.ay - b.cy;
        double az = t.az - b.cz;
        double bx = t.bx - b.cx;
        double by = t.by - b.cy;
        double bz = t.bz - b.cz;
        double cx = t.qx - b.cx;
        double cy = t.qy - b.cy;
        double cz = t.qz - b.cz;
        double p0 = ax * b.ux + ay * b.uy + az * b.uz;
        double p1 = ax * b.vx + ay * b.vy + az * b.vz;
        double p2 = ax * b.wx + ay * b.wy + az * b.wz;
        double q0 = bx * b.ux + by * b.uy + bz * b.uz;
        double q1 = bx * b.vx + by * b.vy + bz * b.vz;
        double q2 = bx * b.wx + by * b.wy + bz * b.wz;
        double s0 = cx * b.ux + cy * b.uy + cz * b.uz;
        double s1 = cx * b.vx + cy * b.vy + cz * b.vz;
        double s2 = cx * b.wx + cy * b.wy + cz * b.wz;
        double ex = b.ex;
        double ey = b.ey;
        double ez = b.ez;
        // the box's faces: unit axes, widened by r itself
        if (Math.min(p0, Math.min(q0, s0)) >= ex + r
                || Math.max(p0, Math.max(q0, s0)) <= -(ex + r)
                || Math.min(p1, Math.min(q1, s1)) >= ey + r
                || Math.max(p1, Math.max(q1, s1)) <= -(ey + r)
                || Math.min(p2, Math.min(q2, s2)) >= ez + r
                || Math.max(p2, Math.max(q2, s2)) <= -(ez + r)) {
            return false;
        }
        double f0x = q0 - p0;
        double f0y = q1 - p1;
        double f0z = q2 - p2;
        double f1x = s0 - q0;
        double f1y = s1 - q1;
        double f1z = s2 - q2;
        double f2x = p0 - s0;
        double f2y = p1 - s1;
        double f2z = p2 - s2;
        double rr = r * r;
        // the triangle's normal
        if (separates(
                f0y * f1z - f0z * f1y,
                f0z * f1x - f0x * f1z,
                f0x * f1y - f0y * f1x,
                p0,
                p1,
                p2,
                q0,
                q1,
                q2,
                s0,
                s1,
                s2,
                ex,
                ey,
                ez,
                rr)) {
            return false;
        }
        // each box axis × each edge: x × f = (0, −fz, fy), y × f = (fz, 0, −fx), z × f = (−fy, fx, 0)
        return !(separates(0, -f0z, f0y, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(0, -f1z, f1y, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(0, -f2z, f2y, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(f0z, 0, -f0x, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(f1z, 0, -f1x, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(f2z, 0, -f2x, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(-f0y, f0x, 0, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(-f1y, f1x, 0, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr)
                || separates(-f2y, f2x, 0, p0, p1, p2, q0, q1, q2, s0, s1, s2, ex, ey, ez, rr));
    }

    /**
     * Whether axis L separates the triangle (p, q, s), thickened by √rr, from the box of half extents (ex, ey, ez) at the
     * origin: the triangle's projection lies at least rb + r|L| beyond the box's on one side.
     */
    private static boolean separates(
            double lx,
            double ly,
            double lz,
            double p0,
            double p1,
            double p2,
            double q0,
            double q1,
            double q2,
            double s0,
            double s1,
            double s2,
            double ex,
            double ey,
            double ez,
            double rr) {
        double ll = lx * lx + ly * ly + lz * lz;
        if (ll < DEGENERATE) {
            return false;
        }
        double a = lx * p0 + ly * p1 + lz * p2;
        double b = lx * q0 + ly * q1 + lz * q2;
        double c = lx * s0 + ly * s1 + lz * s2;
        double rb = ex * Math.abs(lx) + ey * Math.abs(ly) + ez * Math.abs(lz);
        double above = Math.min(a, Math.min(b, c)) - rb; // the gap past the box's positive side
        double below = -Math.max(a, Math.max(b, c)) - rb; // and past its negative side
        double reach = rr * ll; // (r |L|)²
        return (above >= 0 && above * above >= reach) || (below >= 0 && below * below >= reach);
    }
}
