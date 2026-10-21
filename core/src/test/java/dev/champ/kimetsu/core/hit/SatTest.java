package dev.champ.kimetsu.core.hit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The separating-axis tests (Rule 7.2), case by case and against independent references: boxes against an exact test
 * (a corner of one inside the other, or an edge of one through the other), blade triangles against a dense distance
 * check.
 */
class SatTest {

    private static Obb aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new Obb().setAabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** A box turned {@code deg} about the vertical. */
    private static Obb turned(double cx, double cy, double cz, double deg, double ex, double ey, double ez) {
        double a = Math.toRadians(deg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new Obb().set(cx, cy, cz, c, 0, -s, 0, 1, 0, s, 0, c, ex, ey, ez);
    }

    @Test
    void boxesOverlapApartAndTouching() {
        Obb a = aabb(0, 0, 0, 1, 1, 1);
        assertTrue(Sat.overlap(a, aabb(0.5, 0.5, 0.5, 1.5, 1.5, 1.5)));
        assertFalse(Sat.overlap(a, aabb(1.01, 0, 0, 2, 1, 1)));
        assertFalse(Sat.overlap(a, aabb(1, 0, 0, 2, 1, 1))); // touching faces: no contact, as vanilla's boxes
        assertTrue(Sat.overlap(a, aabb(1 - 1e-9, 0, 0, 2, 1, 1)));
        assertFalse(Sat.overlap(a, aabb(1, 1, 1, 2, 2, 2))); // touching corners
    }

    /** A box with axes {@code u}, {@code v} and {@code w}, centred {@code at} along world axis {@code k}. */
    private static Obb along(int k, double at, double[] u, double[] v, double[] w, double ex, double ey, double ez) {
        return new Obb()
                .set(
                        k == 0 ? at : 0,
                        k == 1 ? at : 0,
                        k == 2 ? at : 0,
                        u[0],
                        u[1],
                        u[2],
                        v[0],
                        v[1],
                        v[2],
                        w[0],
                        w[1],
                        w[2],
                        ex,
                        ey,
                        ez);
    }

    @Test
    void aTurnedBoxTouchingAnyOfAsFacesWithItsCornerIsNoContact() {
        Obb a = aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        // turned atan(4/3) about the vertical (for a's x and z faces) or about z (for its y face): none of its axes
        // lies along the face's normal, so only that face can separate them
        double[][] aboutY = {{0.6, 0, -0.8}, {0, 1, 0}, {0.8, 0, 0.6}};
        double[][] aboutZ = {{0.6, 0.8, 0}, {-0.8, 0.6, 0}, {0, 0, 1}};
        double[][][] turned = {aboutY, aboutZ, aboutY};
        for (int k = 0; k < 3; k++) {
            double[][] f = turned[k];
            // its centre where its corner meets a's +k face, summed as Sat sums a's face k (a's axes are the world's)
            double at = 0.5 + 0.5 * Math.abs(f[0][k]) + 0.5 * Math.abs(f[1][k]) + 0.5 * Math.abs(f[2][k]);
            assertFalse(Sat.overlap(a, along(k, at, f[0], f[1], f[2], 0.5, 0.5, 0.5)), "a's face " + k);
            assertTrue(Sat.overlap(a, along(k, at - 1e-9, f[0], f[1], f[2], 0.5, 0.5, 0.5)), "into a's face " + k);
        }
    }

    @Test
    void aCornerTouchingAnyOfBsFacesIsNoContact() {
        Obb a = aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        double[] n = {0.6, 0, -0.8}; // a wide plate's face normal, turned atan(4/3) about the vertical
        double[] m = {0.8, 0, 0.6};
        double[] up = {0, 1, 0};
        double x = 4.0 / 3; // the plate's centre, on a's x axis
        double centre = x * n[0]; // its distance along n, as Sat projects it
        double cube = 0.5 * Math.abs(n[0]) + 0.5 * Math.abs(n[1]) + 0.5 * Math.abs(n[2]); // a's reach along n
        double thin = centre - cube; // exact (they're within a factor of two), so cube + thin is centre to the bit
        for (int k = 0; k < 3; k++) {
            double[][] axes =
                    k == 0 ? new double[][] {n, m, up} : k == 1 ? new double[][] {m, n, up} : new double[][] {m, up, n};
            double ex = k == 0 ? thin : 3;
            double ey = k == 1 ? thin : 3;
            double ez = k == 2 ? thin : 3;
            assertFalse(Sat.overlap(a, along(0, x, axes[0], axes[1], axes[2], ex, ey, ez)), "b's face " + k);
            assertTrue(Sat.overlap(a, along(0, x - 1e-9, axes[0], axes[1], axes[2], ex, ey, ez)), "into b's face " + k);
        }
    }

    @Test
    void aTurnedBoxIsSeparatedByItsOwnFace() {
        Obb a = aabb(-0.5, 0, -0.5, 0.5, 1, 0.5);
        // a long slab turned 45°, thin along the diagonal, its face 0.05 beyond the cube's corner: only its own face's
        // axis separates them
        double gap = Math.sqrt(0.5) + 0.05 + 0.1;
        Obb slab = turned(gap * Math.sqrt(0.5), 0.5, gap * Math.sqrt(0.5), 45, 2, 0.5, 0.1);
        assertFalse(Sat.overlap(a, slab));
        assertFalse(Sat.overlap(slab, a));
        assertFalse(exact(a, slab));
        Obb closer = turned((gap - 0.1) * Math.sqrt(0.5), 0.5, (gap - 0.1) * Math.sqrt(0.5), 45, 2, 0.5, 0.1);
        assertTrue(Sat.overlap(a, closer));
    }

    @Test
    void onlyAnEdgeAxisSeparatesTwoSkewBars() {
        // a bar along x, and one along d = (0, sin a, cos a) whose square section is turned 45° about d, offset along
        // the bars' common perpendicular n = x × d: no face separates them, only the edge axis n does
        Obb bar = new Obb().set(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 2, 0.05, 0.05);
        double a = Math.toRadians(45);
        double[] d = {0, Math.sin(a), Math.cos(a)};
        double[] n = {0, -Math.cos(a), Math.sin(a)};
        double h = Math.sqrt(0.5);
        for (double s : new double[] {0.15, 0.12}) {
            Obb skew = new Obb()
                    .set(
                            s * n[0], s * n[1], s * n[2], h, h * n[1], h * n[2], h, -h * n[1], -h * n[2], d[0], d[1],
                            d[2], 0.05, 0.05, 2);
            assertEquals(exact(bar, skew), Sat.overlap(bar, skew), "offset " + s);
            assertEquals(exact(skew, bar), Sat.overlap(skew, bar), "offset " + s);
        }
        Obb apart = new Obb()
                .set(
                        0.15 * n[0],
                        0.15 * n[1],
                        0.15 * n[2],
                        h,
                        h * n[1],
                        h * n[2],
                        h,
                        -h * n[1],
                        -h * n[2],
                        d[0],
                        d[1],
                        d[2],
                        0.05,
                        0.05,
                        2);
        assertFalse(Sat.overlap(bar, apart)); // 0.15 apart along n, more than the 0.141 their sections reach
    }

    @Test
    void nearlyParallelAxesNeverSeparateFalsely() {
        Obb a = aabb(0, 0, 0, 1, 1, 1);
        for (double deg : new double[] {1e-12, 1e-6, 90, 90 - 1e-9, 180}) {
            assertTrue(Sat.overlap(a, turned(0.5, 0.5, 0.5, deg, 0.4, 0.4, 0.4)), "turned " + deg);
            assertTrue(Sat.overlap(a, turned(1.3, 0.5, 0.5, deg, 0.4, 0.4, 0.4)), "turned " + deg);
        }
    }

    @Test
    void theAnswerDoesNotDependOnOrderOrPlace() {
        Random rnd = new Random(11);
        for (int i = 0; i < 5000; i++) {
            Obb a = random(rnd);
            Obb b = random(rnd);
            boolean ab = Sat.overlap(a, b);
            assertEquals(ab, Sat.overlap(b, a));
            double sx = rnd.nextDouble() * 100 - 50;
            double sz = rnd.nextDouble() * 100 - 50;
            assertEquals(ab, Sat.overlap(shift(a, sx, sz), shift(b, sx, sz)));
        }
    }

    @Test
    void randomBoxesAgreeWithTheExactTest() {
        Random rnd = new Random(7);
        int overlaps = 0;
        for (int i = 0; i < 100_000; i++) {
            Obb a = random(rnd);
            Obb b = random(rnd);
            boolean truth = exact(a, b);
            assertEquals(truth, Sat.overlap(a, b), "case " + i);
            if (truth) {
                overlaps++;
            }
        }
        assertTrue(overlaps > 10_000 && overlaps < 90_000, "both answers well represented: " + overlaps);
    }

    // --------------------------------------------------------------------------------------------- triangles

    private static Tri tri(double... p) {
        return new Tri().set(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]);
    }

    @Test
    void aTriangleThroughAFaceTouches() {
        Obb box = aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        assertTrue(Sat.overlap(tri(-2, 0, 0, 2, 0, 0, 0, 0, 2), 0, box));
        assertTrue(Sat.overlap(tri(-2, 0, 0, 2, 0, 0, 0, 0, 2), 0.1, box));
    }

    @Test
    void theBladeRadiusThickensTheTriangle() {
        Obb box = aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        Tri above = tri(-1, 0.55, -1, 1, 0.55, -1, 0, 0.55, 1); // parallel to the top face, 0.05 above it
        assertTrue(Sat.overlap(above, 0.1, box));
        assertFalse(Sat.overlap(above, 0.0, box));
        Tri higher = tri(-1, 0.65, -1, 1, 0.65, -1, 0, 0.65, 1); // 0.15 above it
        assertFalse(Sat.overlap(higher, 0.1, box));
        Tri exactly = tri(-1, 0.6, -1, 1, 0.6, -1, 0, 0.6, 1); // 0.1 above: touching is no contact
        assertFalse(Sat.overlap(exactly, 0.1, box));
    }

    @Test
    void aDegenerateTriangleIsASegmentOrAPoint() {
        Obb box = aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        assertTrue(Sat.overlap(tri(-2, 0, 0, 2, 0, 0, 2, 0, 0), 0.05, box)); // a segment through the box
        assertFalse(Sat.overlap(tri(-2, 0.7, 0, 2, 0.7, 0, 2, 0.7, 0), 0.1, box)); // a segment 0.2 above it
        assertTrue(Sat.overlap(tri(0.55, 0, 0, 0.55, 0, 0, 0.55, 0, 0), 0.1, box)); // a point 0.05 out
        assertFalse(Sat.overlap(tri(0.7, 0, 0, 0.7, 0, 0, 0.7, 0, 0), 0.1, box));
    }

    @Test
    void randomTrianglesMissNoHitAndOverReachOnlyNearCorners() {
        Random rnd = new Random(3);
        double r = 0.1;
        int hits = 0;
        int misses = 0;
        for (int i = 0; i < 20_000; i++) {
            Obb box = random(rnd);
            Tri t = tri(
                    coord(rnd),
                    coord(rnd),
                    coord(rnd),
                    coord(rnd),
                    coord(rnd),
                    coord(rnd),
                    coord(rnd),
                    coord(rnd),
                    coord(rnd));
            // the grid's distance is never below the true one, and at most 0.07 above it (its covering radius)
            double sampled = nearestDistance(t, box);
            boolean says = Sat.overlap(t, r, box);
            if (sampled < r) {
                assertTrue(says, "case " + i + ": " + sampled + " from the box, inside the blade's radius");
                hits++;
            } else if (sampled > r * Math.sqrt(3) + 0.07) {
                assertFalse(says, "case " + i + ": " + sampled + " from the box, past the corner allowance");
                misses++;
            }
        }
        assertTrue(hits > 1000 && misses > 1000, hits + " hits, " + misses + " misses");
    }

    // ------------------------------------------------------------------------------------------- references

    private static double coord(Random rnd) {
        return rnd.nextDouble() * 4 - 2;
    }

    private static Obb random(Random rnd) {
        // a random rotation from a random unit quaternion
        double q0 = rnd.nextGaussian();
        double q1 = rnd.nextGaussian();
        double q2 = rnd.nextGaussian();
        double q3 = rnd.nextGaussian();
        double n = Math.sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        double w = q0 / n;
        double x = q1 / n;
        double y = q2 / n;
        double z = q3 / n;
        return new Obb()
                .set(
                        rnd.nextDouble() * 3 - 1.5,
                        rnd.nextDouble() * 3 - 1.5,
                        rnd.nextDouble() * 3 - 1.5,
                        1 - 2 * (y * y + z * z),
                        2 * (x * y + w * z),
                        2 * (x * z - w * y),
                        2 * (x * y - w * z),
                        1 - 2 * (x * x + z * z),
                        2 * (y * z + w * x),
                        2 * (x * z + w * y),
                        2 * (y * z - w * x),
                        1 - 2 * (x * x + y * y),
                        0.05 + rnd.nextDouble() * 1.2,
                        0.05 + rnd.nextDouble() * 1.2,
                        0.05 + rnd.nextDouble() * 1.2);
    }

    private static Obb shift(Obb b, double dx, double dz) {
        return new Obb()
                .set(
                        b.cx + dx, b.cy, b.cz + dz, b.ux, b.uy, b.uz, b.vx, b.vy, b.vz, b.wx, b.wy, b.wz, b.ex, b.ey,
                        b.ez);
    }

    /** Two convex boxes overlap exactly when a corner of one lies inside the other or an edge of one crosses it. */
    private static boolean exact(Obb a, Obb b) {
        return cornerInside(a, b) || cornerInside(b, a) || edgeCrosses(a, b) || edgeCrosses(b, a);
    }

    private static double[][] corners(Obb b) {
        double[][] out = new double[8][];
        for (int i = 0; i < 8; i++) {
            double sa = (i & 1) == 0 ? -b.ex : b.ex;
            double sb = (i & 2) == 0 ? -b.ey : b.ey;
            double sc = (i & 4) == 0 ? -b.ez : b.ez;
            out[i] = new double[] {
                b.cx + sa * b.ux + sb * b.vx + sc * b.wx,
                b.cy + sa * b.uy + sb * b.vy + sc * b.wy,
                b.cz + sa * b.uz + sb * b.vz + sc * b.wz
            };
        }
        return out;
    }

    private static boolean cornerInside(Obb a, Obb b) {
        for (double[] p : corners(a)) {
            double[] l = local(b, p);
            if (Math.abs(l[0]) < b.ex && Math.abs(l[1]) < b.ey && Math.abs(l[2]) < b.ez) {
                return true;
            }
        }
        return false;
    }

    private static boolean edgeCrosses(Obb a, Obb b) {
        double[][] c = corners(a);
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit < 8; bit <<= 1) {
                int j = i | bit;
                if (j != i && segmentCrosses(b, c[i], c[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The slab test: the segment's stretch strictly inside each pair of faces, intersected. */
    private static boolean segmentCrosses(Obb b, double[] p, double[] q) {
        double[] lp = local(b, p);
        double[] lq = local(b, q);
        double[] e = {b.ex, b.ey, b.ez};
        double lo = 0;
        double hi = 1;
        for (int k = 0; k < 3; k++) {
            double d = lq[k] - lp[k];
            if (Math.abs(d) < 1e-15) {
                if (Math.abs(lp[k]) >= e[k]) {
                    return false;
                }
                continue;
            }
            double t1 = (-e[k] - lp[k]) / d;
            double t2 = (e[k] - lp[k]) / d;
            lo = Math.max(lo, Math.min(t1, t2));
            hi = Math.min(hi, Math.max(t1, t2));
            if (lo >= hi) {
                return false;
            }
        }
        return true;
    }

    private static double[] local(Obb b, double[] p) {
        double dx = p[0] - b.cx;
        double dy = p[1] - b.cy;
        double dz = p[2] - b.cz;
        return new double[] {
            dx * b.ux + dy * b.uy + dz * b.uz, dx * b.vx + dy * b.vy + dz * b.vz, dx * b.wx + dy * b.wy + dz * b.wz
        };
    }

    /** The least distance from a dense grid of points on the triangle to the box (0 inside it). */
    private static double nearestDistance(Tri t, Obb box) {
        int n = 60;
        double best = Double.MAX_VALUE;
        V3 near = new V3();
        for (int i = 0; i <= n; i++) {
            for (int j = 0; i + j <= n; j++) {
                double u = (double) i / n;
                double v = (double) j / n;
                double w = 1 - u - v;
                double px = w * t.ax + u * t.bx + v * t.qx;
                double py = w * t.ay + u * t.by + v * t.qy;
                double pz = w * t.az + u * t.bz + v * t.qz;
                box.nearest(px, py, pz, near);
                double dx = px - near.x;
                double dy = py - near.y;
                double dz = pz - near.z;
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return best;
    }
}
