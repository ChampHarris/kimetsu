package dev.champ.kimetsu.core.hit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Whether a pushed body meets a wall (D-086, {@link PushWall}): a box 0.3 each side and 1.8 tall in a level of solid
 * columns. A wall counts only where what the box touches leaves the push under the share of its move along its heading,
 * its axes moved in vanilla's order. The server's contact check and the client's stop tick both ask it.
 */
class PushWallTest {
    private static final double HALF = 0.3;
    private static final double HEIGHT = 1.8;
    private static final double KEEP = 0.5;

    /** Solid columns, each {minX, minZ, maxX, maxZ}: how far a box moves along the heading before it overlaps one. */
    private static Ground columns(double[]... cols) {
        return (x, y, z, halfWidth, height, dirX, dirZ, distance) -> {
            double clear = distance;
            for (double[] c : cols) {
                double[] sx = span(x - halfWidth, x + halfWidth, dirX, c[0], c[2]);
                double[] sz = span(z - halfWidth, z + halfWidth, dirZ, c[1], c[3]);
                double enter = Math.max(sx[0], sz[0]);
                double exit = Math.min(sx[1], sz[1]);
                if (enter < exit && exit > 0) {
                    clear = Math.min(clear, Math.max(0, enter));
                }
            }
            return clear;
        };
    }

    /** How far along a heading component {@code d} the span [lo, hi] overlaps [min, max]: faces touching don't. */
    private static double[] span(double lo, double hi, double d, double min, double max) {
        if (d == 0) {
            boolean over = lo < max && hi > min;
            return over
                    ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}
                    : new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        }
        double a = (min - hi) / d;
        double b = (max - lo) / d;
        return new double[] {Math.min(a, b), Math.max(a, b)};
    }

    /** A long wall on side {@code s} (+1 or −1) of x, its face at x = 2s. */
    private static Ground wallX(int s) {
        return columns(s > 0 ? new double[] {2, -50, 3, 50} : new double[] {-3, -50, -2, 50});
    }

    /** A long wall on side {@code s} of z, its face at z = 2s. */
    private static Ground wallZ(int s) {
        return columns(s > 0 ? new double[] {-50, 2, 50, 3} : new double[] {-50, -3, 50, -2});
    }

    @Test
    void aFlatWallCountsWithin45DegreesOfHeadOn_onEverySide() {
        // flush against the wall, pushed deg into it from its line, sliding either way along it
        for (int s : new int[] {1, -1}) {
            for (int along : new int[] {1, -1}) {
                for (double deg : new double[] {90, 46, 44, 17, 5}) {
                    double into = s * Math.sin(Math.toRadians(deg));
                    double slide = along * Math.cos(Math.toRadians(deg));
                    boolean meets = deg > 45;
                    String what = deg + " degrees, side " + s + ", sliding " + along;
                    assertEquals(
                            meets,
                            PushWall.meets(wallX(s), 1.7 * s, 64, 0, HALF, HEIGHT, into, slide, KEEP),
                            "x " + what);
                    assertEquals(
                            meets,
                            PushWall.meets(wallZ(s), 0, 64, 1.7 * s, HALF, HEIGHT, slide, into, KEEP),
                            "z " + what);
                }
            }
        }
    }

    @Test
    void aFlatWallKeepsTheSquareOfThePushAlongIt() {
        double r = Math.toRadians(30);
        assertEquals(
                Math.cos(r) * Math.cos(r),
                PushWall.kept(wallX(1), 1.7, 64, 0, HALF, HEIGHT, Math.sin(r), Math.cos(r)),
                1e-12);
        assertEquals(0, PushWall.kept(wallX(-1), -1.7, 64, 0, HALF, HEIGHT, -1, 0), 1e-12, "head-on keeps nothing");
    }

    @Test
    void onlyWhatTheBoxTouchesCounts() {
        assertFalse(PushWall.meets(wallX(1), 1.6, 64, 0, HALF, HEIGHT, 1, 0, KEEP), "0.1 short of it, head-on");
        assertTrue(PushWall.meets(wallX(1), 1.68, 64, 0, HALF, HEIGHT, 1, 0, KEEP), "within a touch of it");
        double r = Math.toRadians(46);
        assertFalse( // 0.05 off it: within a touch along x alone, not along the push, which runs 0.07 to it
                PushWall.meets(wallX(1), 1.65, 64, 0, HALF, HEIGHT, Math.sin(r), Math.cos(r), KEEP),
                "46 degrees, 0.05 short of it");
        assertFalse(PushWall.meets(wallX(1), 1.7, 64, 0, HALF, HEIGHT, -1, 0, KEEP), "pushed away from it");
        assertTrue(PushWall.meets(wallX(1), 1.7, 64, 0, HALF, HEIGHT, 1, 0, 1.0), "any loss counts at a share of 1");
    }

    @Test
    void anInsideCornerCutsBothAxes() {
        Ground corner = columns(new double[] {2, -50, 3, 50}, new double[] {-50, 2, 50, 3});
        double r = Math.toRadians(17);
        assertEquals(0, PushWall.kept(corner, 1.7, 64, 1.7, HALF, HEIGHT, Math.sin(r), Math.cos(r)), 1e-12);
        assertTrue(PushWall.meets(corner, 1.7, 64, 1.7, HALF, HEIGHT, Math.sin(r), Math.cos(r), KEEP));
    }

    @Test
    void aPostsCornerCutsTheSmallerAxisOnceTheLargerHasMoved_asVanillasMoveDoes() {
        // the box's corner 0.02 off the post's on each side: moving diagonally it meets the corner, moving along either
        // axis alone from where it stands it passes. Vanilla moves the larger axis first, which brings the box level
        // with the post, so the smaller axis is cut: the push keeps the larger's square
        Ground post = columns(new double[] {0.32, 0.32, 1.32, 1.32});
        double dirX = 0.5;
        double dirZ = Math.sqrt(0.75); // 30 degrees from +z
        assertEquals(0.75, PushWall.kept(post, 0, 64, 0, HALF, HEIGHT, dirX, dirZ), 1e-12);
        assertFalse(PushWall.meets(post, 0, 64, 0, HALF, HEIGHT, dirX, dirZ, KEEP), "the larger axis keeps over half");
        assertTrue(PushWall.meets(post, 0, 64, 0, HALF, HEIGHT, dirX, dirZ, 1.0), "a share of 1: any loss counts");
        // the same with x the larger axis, the push mirrored onto −x and −z
        Ground mirrored = columns(new double[] {-1.32, -1.32, -0.32, -0.32});
        assertEquals(0.75, PushWall.kept(mirrored, 0, 64, 0, HALF, HEIGHT, -dirZ, -dirX), 1e-12);
    }
}
