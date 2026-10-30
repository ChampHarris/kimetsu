package dev.champ.kimetsu.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

/**
 * The HUD's shapes as quads (D-141's craft call 6): arcs clockwise from up in y-down screen space (the Pulse ring, the
 * notch's gap, the Breath arc), diamonds (the pips' shape until D-142's art review made them squares; kept for the cue's star pass) and
 * rectangles (the bars and the pips), each wound as vanilla's.
 */
class RingGeometryTest {
    private static RenderPipeline noPipeline() {
        throw new AssertionError("a test never asks for the pipeline");
    }

    private static HudQuadsTest.Recorder drawn(HudQuads q) {
        q.end();
        HudQuadsTest.Recorder r = new HudQuadsTest.Recorder();
        q.buildVertices(r);
        return r;
    }

    private static HudQuads begun() {
        HudQuads q = new HudQuads(RingGeometryTest::noPipeline);
        q.begin(new Matrix3x2f(), null);
        return q;
    }

    /** The clockwise angle from up of ({@code x}, {@code y}) about the centre, in degrees, 0 to 360. */
    private static double bearing(double x, double y, double cx, double cy) {
        double a = Math.toDegrees(Math.atan2(x - cx, -(y - cy)));
        return a < 0 ? a + 360 : a;
    }

    @Test
    void aFullRingIsItsSegmentsOfQuads_everyCornerOnItsInnerOrOuterRadius() {
        HudQuads q = begun();
        RingGeometry.arc(q, 50, 40, 9.5f, 10.5f, 0, 360, 48, 0xFFCFC4A8);
        HudQuadsTest.Recorder r = drawn(q);
        assertEquals(48 * 4, r.n);
        for (int i = 0; i < r.n; i++) {
            double d = Math.hypot(r.x[i] - 50, r.y[i] - 40);
            assertTrue(Math.abs(d - 9.5) < 1e-3 || Math.abs(d - 10.5) < 1e-3, "corner " + i + " at r " + d);
            assertEquals(0xFFCFC4A8, r.colour[i]);
        }
        for (int k = 0; k < 48; k++) {
            assertTrue(r.area2(k) < 0, "segment " + k + " wound as vanilla's");
        }
    }

    @Test
    void anArcStartsAtItsBearingAndSweepsClockwise_leavingItsGapEmpty() {
        HudQuads q = begun();
        RingGeometry.arc(q, 0, 0, 17, 19, 15, 330, 44, -1); // the notch: a 30° gap at the top
        HudQuadsTest.Recorder r = drawn(q);
        double lowest = 360;
        double highest = 0;
        for (int i = 0; i < r.n; i++) {
            double b = bearing(r.x[i], r.y[i], 0, 0);
            assertTrue(b >= 15 - 1e-3 && b <= 345 + 1e-3, "corner " + i + " at " + b + "° is in the gap");
            lowest = Math.min(lowest, b);
            highest = Math.max(highest, b);
        }
        assertEquals(15, lowest, 1e-3);
        assertEquals(345, highest, 1e-3);
        // clockwise from up on a y-down screen: 90° is to the right of the centre
        HudQuads right = begun();
        RingGeometry.arc(right, 0, 0, 1, 2, 89, 2, 1, -1);
        HudQuadsTest.Recorder rr = drawn(right);
        for (int i = 0; i < rr.n; i++) {
            assertTrue(rr.x[i] > 0.9, "x " + rr.x[i]);
            assertEquals(0, rr.y[i], 0.1);
        }
    }

    @Test
    void theBreathArcOverTheTopRunsFromItsLeftEnd() {
        HudQuads q = begun();
        RingGeometry.arc(q, 0, 0, 14, 18, 270, 90, 12, -1); // the left half of the arc: 270° to 360°
        HudQuadsTest.Recorder r = drawn(q);
        for (int i = 0; i < r.n; i++) {
            assertTrue(r.x[i] <= 1e-3 && r.y[i] <= 1e-3, "(" + r.x[i] + ", " + r.y[i] + ") is upper left");
        }
    }

    @Test
    void anArcWithNoSweepDrawsNothing() {
        HudQuads q = begun();
        RingGeometry.arc(q, 0, 0, 14, 18, 270, 0, 12, -1);
        assertEquals(0, q.quads());
    }

    @Test
    void aDiamondIsOneQuad_itsTipsHalfItsWidthFromItsCentre() {
        HudQuads q = begun();
        RingGeometry.diamond(q, 10, 20, 1.5f, 0xFF2D2D33);
        HudQuadsTest.Recorder r = drawn(q);
        assertEquals(4, r.n);
        for (int i = 0; i < 4; i++) {
            assertEquals(1.5, Math.abs(r.x[i] - 10) + Math.abs(r.y[i] - 20), 1e-5, "tip " + i);
        }
        assertTrue(r.area2(0) < 0);
    }

    @Test
    void aRectangleCoversItsCorners() {
        HudQuads q = begun();
        RingGeometry.rect(q, 3, 4, 35, 6, 0x991A1A1E);
        HudQuadsTest.Recorder r = drawn(q);
        assertEquals(4, r.n);
        assertEquals(-2 * 32 * 2, r.area2(0), 1e-4);
        RingGeometry.rect(begun(), 3, 4, 3, 6, -1); // no width: nothing, and no throw
    }
}
