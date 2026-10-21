package dev.champ.kimetsu.core.hit;

/** A triangle: half of the quad a blade sweeps between two samples of its arc (Rule 7.2). Mutable and reused. */
public final class Tri {
    double ax;
    double ay;
    double az;
    double bx;
    double by;
    double bz;
    double qx;
    double qy;
    double qz;

    public Tri set(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.qx = cx;
        this.qy = cy;
        this.qz = cz;
        return this;
    }

    /** Its corners a, b and c as given. */
    public Tri set(V3 a, V3 b, V3 c) {
        return set(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z);
    }

    /** Whether {@code (minX..maxZ)}, widened by {@code r}, overlaps this triangle's bounds: the cheap test first. */
    public boolean boundsOverlap(
            double r, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return Math.min(ax, Math.min(bx, qx)) - r < maxX
                && Math.max(ax, Math.max(bx, qx)) + r > minX
                && Math.min(ay, Math.min(by, qy)) - r < maxY
                && Math.max(ay, Math.max(by, qy)) + r > minY
                && Math.min(az, Math.min(bz, qz)) - r < maxZ
                && Math.max(az, Math.max(bz, qz)) + r > minZ;
    }

    /** The point of this triangle nearest {@code (px, py, pz)}, into {@code out} (Ericson's regions, RTCD 5.1.5). */
    public V3 nearest(double px, double py, double pz, V3 out) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double acx = qx - ax;
        double acy = qy - ay;
        double acz = qz - az;
        double apx = px - ax;
        double apy = py - ay;
        double apz = pz - az;
        double d1 = abx * apx + aby * apy + abz * apz;
        double d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) {
            return out.set(ax, ay, az);
        }
        double bpx = px - bx;
        double bpy = py - by;
        double bpz = pz - bz;
        double d3 = abx * bpx + aby * bpy + abz * bpz;
        double d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) {
            return out.set(bx, by, bz);
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double v = d1 - d3 == 0 ? 0 : d1 / (d1 - d3);
            return out.set(ax + v * abx, ay + v * aby, az + v * abz);
        }
        double cpx = px - qx;
        double cpy = py - qy;
        double cpz = pz - qz;
        double d5 = abx * cpx + aby * cpy + abz * cpz;
        double d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) {
            return out.set(qx, qy, qz);
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double w = d2 - d6 == 0 ? 0 : d2 / (d2 - d6);
            return out.set(ax + w * acx, ay + w * acy, az + w * acz);
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            double sum = (d4 - d3) + (d5 - d6);
            double w = sum == 0 ? 0 : (d4 - d3) / sum;
            return out.set(bx + w * (qx - bx), by + w * (qy - by), bz + w * (qz - bz));
        }
        double sum = va + vb + vc;
        if (sum == 0) {
            return out.set(ax, ay, az); // a degenerate triangle: any corner will do
        }
        double v = vb / sum;
        double w = vc / sum;
        return out.set(ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w);
    }
}
