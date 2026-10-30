package dev.champ.kimetsu.client.kfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.champ.kimetsu.client.KfxMark;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The world cues as quads (slice 5d-4a's build step 5; D-144's craft calls 3 and 4, D-145's A7, A8 and A11), written
 * relative to the camera for the always-on-top phase:
 *
 * <ul>
 *   <li>a billboard (the glint, its flare and shatter, the burst, the clash flash) faces the camera with its cell's inset
 *       UVs at its corners, so the sprite's own outline is drawn whole; a 16-texel cell is {@code cue_size} across, and
 *       never less on screen than {@code cue_min_px} of a 1080-line screen's height;
 *   <li>the purple ring's outer edge is a circle round the attacker at its box's centre height, grown from its body to
 *       its box's forward reach, full strength only inside the box and {@code ring_faded_alpha} outside, split exactly at
 *       the box's edge (a quad's alpha would blend full strength past the reach); its band faces the camera and grows
 *       inward on screen, so it reads edge-on and never claims more reach than the grab has;
 *   <li>the Thread's band lies along the on-screen direction of the neck's x axis at the drawn yaw, as long as the neck
 *       and at least {@code cue_min_px} (screen horizontal when edge-on);
 *   <li>a bystander's cue is scaled and dimmed, never its reach; a pixel keeps the first cue drawn on it (the pipeline's
 *       one depth and strict test), so the point cues come first, full strength before a bystander's, near to far,
 *       then every ring's full pieces, then its faded ones, and a ring's overlapping pieces never stack; positions hold 30,000,000 blocks out; nothing is
 *       allocated once warm.
 * </ul>
 */
class CueQuadsTest {
    private static final float CUE_SIZE = 0.5f; // feel: kfx.cue_size
    private static final float MIN_PX = 24f; // kfx.cue_min_px
    private static final float FADED = 0.35f; // kfx.ring_faded_alpha
    private static final float Y_SCALE = (float) (1 / Math.tan(Math.toRadians(35))); // a 70° vertical field of view
    private static final double EPS = 1e-4;
    private static final double BODY = 0.3; // the stand-in's half-width
    // m1_demon/grab.json's box in the attacker's frame: its centre height, and its footprint across and forward
    private static final double GRAB_CY = 1.0;
    private static final double GRAB_X = 0.0;
    private static final double GRAB_Z = 1.05;
    private static final double GRAB_HX = 0.6;
    private static final double GRAB_HZ = 0.95;
    private static final double REACH = GRAB_Z + GRAB_HZ;
    private static final double NECK_HX = 0.18; // m1_demon.json's neck box
    private static final int FULL = 0xFFFFFFFF;
    private static final int FADED_WHITE = 0x59FFFFFF; // 0.35 of 255, rounded

    // ------------------------------------------------------------------------------------------------ billboards

    @Test
    void aBillboardFacesTheCamera_itsCellsInsetUvsAtItsCorners_soItsOutlineIsDrawnWhole() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 10.5, 64.62, -3.25, 37, 18); // turned and pitched
        KfxMark m = new KfxMark();
        KfxMark.Cue glint = m.add();
        glint.cell = CueSheet.GLINT;
        cam.place(glint, 3, 0.4, -0.2);
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(4, r.n, "one quad");
        assertBillboard(r, 0, cam, glint, CUE_SIZE / 2, CueSheet.GLINT, 0);

        m.clear();
        KfxMark.Cue shard = m.add();
        shard.cell = CueSheet.SHATTER;
        shard.frame = 2;
        cam.place(shard, 4, -0.5, 0.3);
        r.clear();
        q.write(m, r);
        assertBillboard(r, 0, cam, shard, CUE_SIZE / 2, CueSheet.SHATTER, 2);
    }

    @Test
    void aCueKeepsItsOwnSizeUpClose_andIsNeverSmallerOnScreenThanCueMinPxOfA1080LineScreen() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.5, 70.0, 0.5, 0, 0); // level, facing +z
        double crossover = CUE_SIZE * Y_SCALE * CueQuads.REFERENCE_LINES / (2 * MIN_PX);
        assertEquals(
                16.07, crossover, 0.01, "cue_size 0.5 is 24 px of 1080 at 16 blocks with a 70° view: it reads at 16");
        for (double depth : new double[] {3, 8, 16}) {
            Recorder r = one(q, cam, CueSheet.GLINT, depth, 1, 1);
            assertEquals(CUE_SIZE, height(r, 0, cam), EPS, "its own size at " + depth);
            assertEquals(CUE_SIZE, width(r, 0, cam), EPS, "square at " + depth);
            assertTrue(px(r, 0, cam) >= MIN_PX, "above the floor at " + depth);
        }
        for (double depth : new double[] {20, 40, 120}) {
            Recorder r = one(q, cam, CueSheet.GLINT, depth, 1, 1);
            assertEquals(MIN_PX, px(r, 0, cam), 1e-3, "floored at " + depth);
            assertEquals(height(r, 0, cam), width(r, 0, cam), EPS, "still square at " + depth);
        }
    }

    @Test
    void theClashFlashsCellIsTwiceAGlints_upCloseAndAtTheFloor() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.5, 70.0, 0.5, 0, 0);
        assertEquals(2 * CUE_SIZE, height(one(q, cam, CueSheet.CLASH, 8, 1, 1), 0, cam), EPS, "32 texels: a block");
        assertEquals(2 * MIN_PX, px(one(q, cam, CueSheet.CLASH, 40, 1, 1), 0, cam), 1e-3, "its floor twice a glint's");
    }

    @Test
    void aBystandersCueIsScaledAndDimmed_itsFloorToo_whileYoursIsFullStrength() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.5, 70.0, 0.5, 0, 0);
        Recorder near = one(q, cam, CueSheet.BURST, 8, 0.6f, 0.75f);
        assertEquals(0.75 * CUE_SIZE, height(near, 0, cam), EPS, "bystander_scale");
        for (int i = 0; i < 4; i++) {
            assertEquals(0x99FFFFFF, near.colour[i], "white at 0.6 (bystander_alpha): the sheet keeps its colours");
        }
        assertEquals(0.75 * MIN_PX, px(one(q, cam, CueSheet.BURST, 40, 0.6f, 0.75f), 0, cam), 1e-3, "a smaller floor");
        Recorder own = one(q, cam, CueSheet.BURST, 8, 1, 1);
        for (int i = 0; i < 4; i++) {
            assertEquals(FULL, own.colour[i], "yours: full strength");
        }
    }

    // ------------------------------------------------------------------------------------------------ the ring

    @Test
    void theRingsOuterEdgeGrowsFromTheBodyToTheBoxsForwardReach_atTheBoxsCentreHeight() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 2.3, 71.0, -1.2, 160, 75); // high, looking down on it
        for (float p : new float[] {0, 0.5f, 1}) {
            KfxMark m = new KfxMark();
            KfxMark.Cue c = ring(m, 2.0, 64.0, 1.0, 30, p);
            Recorder r = new Recorder();
            q.write(m, r);
            assertTrue(r.n >= 4 * CueQuads.RING_SEGMENTS, "the whole ring at " + p + ": " + r.n / 4 + " quads");
            double radius = BODY * (1 - p) + REACH * p;
            for (int k = 0; k < r.n / 4; k++) {
                for (int i : new int[] {k * 4, k * 4 + 3}) { // each quad: outer a, inner a, inner b, outer b
                    assertEquals(radius, flat(r, i, c, cam), EPS, "the outer edge at progress " + p + ", vertex " + i);
                    assertEquals(c.y - cam.y, r.y[i], EPS, "at the box's centre height, vertex " + i);
                }
            }
        }
    }

    @Test
    void theRingIsFullStrengthOnlyInsideItsBox_splitExactlyAtTheBoxsEdge() {
        // at 2.0 the side faces bound it; at 0.47 the near face; at 0.725 the sides again
        float[] progress = {1, 0.1f, 0.25f};
        double[] radius = {REACH, 0.47, 0.725};
        double[] edge = {Math.asin(GRAB_HX / REACH), Math.acos((GRAB_Z - GRAB_HZ) / 0.47), Math.asin(GRAB_HX / 0.725)};
        for (int t = 0; t < progress.length; t++) {
            CueQuads q = quads();
            Cam cam = new Cam(q, -1.0, 66.62, 4.0, 200, 12);
            KfxMark m = new KfxMark();
            KfxMark.Cue c = ring(m, 0.25, 64.0, 0.75, 30, progress[t]);
            Recorder r = new Recorder();
            q.write(m, r);
            String at = "radius " + radius[t];
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            double[] loAt = null;
            double[] hiAt = null;
            double fadedNear = Double.POSITIVE_INFINITY; // the faded arc's angle nearest ahead, either side
            double[] fadedAt = null;
            double fullSpan = 0;
            double fadedSpan = 0;
            int toFaded = 0;
            for (int k = 0; k < r.n / 4; k++) {
                int colour = r.colour[k * 4];
                for (int i = 1; i < 4; i++) {
                    assertEquals(colour, r.colour[k * 4 + i], at + ": one alpha a quad, nothing to blend across");
                }
                assertTrue(colour == FULL || colour == FADED_WHITE, at + ": full or faded, quad " + k);
                double[] a = att(r, k * 4, c, cam);
                double[] b = att(r, k * 4 + 3, c, cam);
                double span = Math.atan2(b[0], b[1]) - Math.atan2(a[0], a[1]);
                if (span < 0) {
                    span += 2 * Math.PI;
                }
                if (colour == FULL) {
                    assertTrue(inside(a, 1e-5) && inside(b, 1e-5), at + ": a full quad runs inside the box, " + k);
                    fullSpan += span;
                    for (double[] p : new double[][] {a, b}) {
                        double theta = Math.atan2(p[0], p[1]);
                        if (theta < lo) {
                            lo = theta;
                            loAt = p;
                        }
                        if (theta > hi) {
                            hi = theta;
                            hiAt = p;
                        }
                    }
                } else {
                    double[] mid = {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
                    assertFalse(inside(mid, -1e-5), at + ": a faded quad runs outside it, " + k);
                    fadedSpan += span;
                    for (double[] p : new double[][] {a, b}) {
                        double theta = Math.abs(Math.atan2(p[0], p[1]));
                        if (theta < fadedNear) {
                            fadedNear = theta;
                            fadedAt = p;
                        }
                    }
                }
                if (k > 0 && colour != r.colour[k * 4 - 4]) {
                    assertEquals(FADED_WHITE, colour, at + ": every full piece comes before every faded one");
                    toFaded++;
                }
            }
            assertEquals(1, toFaded, at + ": full pieces first, then faded");
            assertEquals(-edge[t], lo, 1e-4, at + ": the full arc starts at the box's edge");
            assertEquals(edge[t], hi, 1e-4, at + ": and ends at it");
            assertTrue(onEdge(loAt) && onEdge(hiAt), at + ": both its ends sit on the box's edge");
            assertEquals(2 * edge[t], fullSpan, 1e-6, at + ": unbroken from edge to edge");
            assertEquals(edge[t], fadedNear, 1e-4, at + ": the faded arc takes over at the box's edge");
            assertTrue(onEdge(fadedAt), at + ": on the edge itself");
            assertEquals(2 * Math.PI, fullSpan + fadedSpan, 1e-6, at + ": the two close the circle, no gap or overlap");
        }
    }

    @Test
    void theRingsBandFacesTheCameraAndGrowsInwardOnScreen_soItNeverClaimsMoreReach() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.4, 65.62, 3.0, 180, 11.7f); // a player 3 blocks in front, eyes 0.62 over the ring
        KfxMark m = new KfxMark();
        KfxMark.Cue c = ring(m, 0.0, 64.0, 0.0, 0, 0.6f);
        Recorder r = new Recorder();
        q.write(m, r);
        spokes(r, c, cam, depth -> CUE_SIZE, "up close: cue_size thick");

        q = quads();
        cam = new Cam(q, 0.4, 65.62, 40.0, 180, 1); // 40 blocks off: the floor
        r.clear();
        q.write(m, r);
        spokes(r, c, cam, depth -> 2 * (MIN_PX / CueQuads.REFERENCE_LINES) * depth / Y_SCALE, "far: the floor");

        q = quads();
        cam = new Cam(q, 0.4, 65.62, 3.0, 180, 11.7f);
        m.clear();
        KfxMark.Cue start = ring(m, 0.0, 64.0, 0.0, 0, 0);
        r.clear();
        q.write(m, r);
        spokes(r, start, cam, depth -> BODY, "at its start: no thicker than its radius, never past the centre");
    }

    @Test
    void aRingSeenExactlyEdgeOnGrowsItsBandUp_withoutTwisting() {
        CueQuads q = quads();
        KfxMark m = new KfxMark();
        KfxMark.Cue c = ring(m, 0.0, 64.0, 0.0, 0, 0.6f);
        new Cam(q, 0.4, c.y, 5.0, 180, 0); // eyes exactly at the ring's height
        Recorder r = new Recorder();
        q.write(m, r);
        assertTrue(r.n > 0);
        for (int k = 0; k < r.n / 4; k++) {
            for (int[] s : new int[][] {{k * 4, k * 4 + 1}, {k * 4 + 3, k * 4 + 2}}) {
                assertEquals(0, r.x[s[1]] - r.x[s[0]], EPS, "quad " + k);
                assertEquals(CUE_SIZE, r.y[s[1]] - r.y[s[0]], EPS, "quad " + k + ": straight up");
                assertEquals(0, r.z[s[1]] - r.z[s[0]], EPS, "quad " + k);
            }
        }
    }

    @Test
    void aBystandersRingIsDimmedAndThinner_butKeepsItsReach() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.4, 65.62, 3.0, 180, 11.7f);
        KfxMark m = new KfxMark();
        KfxMark.Cue c = ring(m, 0.0, 64.0, 0.0, 0, 1);
        c.alpha = 0.6f;
        c.scale = 0.75f;
        Recorder r = new Recorder();
        q.write(m, r);
        boolean sawFull = false;
        boolean sawFaded = false;
        for (int k = 0; k < r.n / 4; k++) {
            int colour = r.colour[k * 4];
            assertTrue(colour == 0x99FFFFFF || colour == 0x36FFFFFF, "0.6 inside, 0.6 × 0.35 outside: quad " + k);
            sawFull |= colour == 0x99FFFFFF;
            sawFaded |= colour == 0x36FFFFFF;
            assertEquals(REACH, flat(r, k * 4, c, cam), EPS, "the reach is the grab's, not scaled");
        }
        assertTrue(sawFull && sawFaded);
        spokes(r, c, cam, depth -> 0.75 * CUE_SIZE, "a thinner band");
    }

    // ------------------------------------------------------------------------------------------------ the Thread

    @Test
    void theThreadsBandLiesAlongTheNecksAxisOnScreen_asLongAsTheNeck_leftToRightAndTopUp() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.0, 65.5, 3.0, 180, 0); // you face the demon from 3 blocks
        KfxMark m = new KfxMark();
        KfxMark.Cue neck = neck(m, 0.0, 65.5, 0.0, 0, 2); // it faces +z: its neck's x axis, its right, is −x
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(4, r.n, "one quad");
        assertCentred(r, 0, cam, neck);
        double[] l = edge(r, 0, 3); // +L, from u0 to u1
        double[] n = edge(r, 1, 0); // +N, from v1 to v0
        assertEquals(2 * NECK_HX, len(l), EPS, "as long as the neck box");
        assertEquals(1, cam.alongRight(l[0], l[1], l[2]) / len(l), EPS, "along the axis: left to right on screen");
        assertEquals(2 * NECK_HX, len(n), EPS, "square: the sheet's line sets its thickness");
        assertEquals(1, cam.alongUp(n[0], n[1], n[2]) / len(n), EPS, "the sheet's top up");
        assertUvs(r, 0, CueSheet.NECK, 2);
    }

    @Test
    void anEdgeOnNeckIsDrawnScreenHorizontal_atTheFloor() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.0, 65.5, -3.0, 0, 0); // level, facing +z
        KfxMark m = new KfxMark();
        KfxMark.Cue neck = neck(m, 0.0, 65.5, 0.0, 90, 0); // it faces −x: its right, −z, points at you
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(4, r.n, "one quad");
        assertCentred(r, 0, cam, neck);
        double[] l = edge(r, 0, 3);
        double[] n = edge(r, 1, 0);
        assertEquals(1, cam.alongRight(l[0], l[1], l[2]) / len(l), EPS, "screen horizontal");
        assertEquals(MIN_PX, len(l) * Y_SCALE / (2 * 3.0) * CueQuads.REFERENCE_LINES, 1e-3, "cue_min_px long");
        assertEquals(len(l), len(n), EPS, "square");
        assertEquals(1, cam.alongUp(n[0], n[1], n[2]) / len(n), EPS);
    }

    @Test
    void seenFromAboveTheThreadFollowsTheAxissOnScreenDirection() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 1.5, 68.0, 2.5, 200, 50);
        KfxMark m = new KfxMark();
        float yaw = 20;
        KfxMark.Cue neck = neck(m, 0.0, 65.5, 0.0, yaw, 1);
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(4, r.n, "one quad");
        assertCentred(r, 0, cam, neck);
        double ax = -Math.cos(Math.toRadians(yaw)); // the neck's x axis: its right, as core's Frame turns it
        double az = -Math.sin(Math.toRadians(yaw));
        double dx = ax * cam.right.x + az * cam.right.z;
        double dy = ax * cam.up.x + az * cam.up.z;
        double proj = Math.hypot(dx, dy);
        if (dx < 0) { // left to right on screen
            dx = -dx;
            dy = -dy;
        }
        double[] l = edge(r, 0, 3);
        assertEquals(dx / proj, cam.alongRight(l[0], l[1], l[2]) / len(l), EPS, "along the axis's projection (x)");
        assertEquals(dy / proj, cam.alongUp(l[0], l[1], l[2]) / len(l), EPS, "along the axis's projection (y)");
        assertEquals(0, cam.alongForward(l[0], l[1], l[2]), EPS, "in the camera's plane");
        double depth = cam.alongForward(neck.x - cam.x, neck.y - cam.y, neck.z - cam.z);
        double floor = 2 * (MIN_PX / CueQuads.REFERENCE_LINES) * depth / Y_SCALE;
        assertEquals(Math.max(2 * NECK_HX * proj, floor), len(l), EPS, "the neck's length on screen");
        assertUvs(r, 0, CueSheet.NECK, 1);
    }

    // ------------------------------------------------------------------------------------------------ the frame

    @Test
    void pointCuesAreDrawnNearToFar_soTheNearestKeepsItsPixels() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.5, 70.0, 0.5, 0, 0);
        KfxMark m = new KfxMark();
        for (double depth : new double[] {5, 20, 10}) {
            KfxMark.Cue c = m.add();
            cam.place(c, depth, 0, 0);
        }
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(12, r.n);
        double[] drawn = new double[3];
        for (int k = 0; k < 3; k++) {
            drawn[k] = cam.alongForward(r.x[k * 4], r.y[k * 4], r.z[k * 4]);
        }
        assertEquals(5, drawn[0], EPS, "a pixel keeps the first cue drawn on it: the nearest goes first");
        assertEquals(10, drawn[1], EPS);
        assertEquals(20, drawn[2], EPS);
    }

    @Test
    void yourCuesComeBeforeABystandersNearerOne_soADimmedCueNeverTakesYourPixels() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.5, 70.0, 0.5, 0, 0);
        KfxMark m = new KfxMark();
        KfxMark.Cue bystander = m.add();
        cam.place(bystander, 4, 0, 0);
        bystander.alpha = 0.6f;
        bystander.scale = 0.75f;
        for (double depth : new double[] {12, 8}) {
            KfxMark.Cue yours = m.add();
            cam.place(yours, depth, 0, 0);
        }
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(12, r.n);
        double[] drawn = new double[3];
        for (int k = 0; k < 3; k++) {
            drawn[k] = cam.alongForward(r.x[k * 4], r.y[k * 4], r.z[k * 4]);
        }
        assertEquals(8, drawn[0], EPS, "yours first, near to far: a pixel keeps the first cue drawn on it");
        assertEquals(12, drawn[1], EPS);
        assertEquals(4, drawn[2], EPS, "the nearer bystander's dimmed cue after every full-strength one");
    }

    @Test
    void ringsComeAfterThePointCues_everyRingsFullPiecesBeforeAnyFadedOne_nearToFar() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.5, 70.0, 0.5, 0, 10);
        KfxMark m = new KfxMark();
        KfxMark.Cue farRing = ring(m, 0.5, 68.38, 14.5, 180, 0.5f);
        KfxMark.Cue farGlint = m.add();
        cam.place(farGlint, 12, -1, 0);
        KfxMark.Cue nearRing = ring(m, 0.5, 68.38, 5.5, 180, 1);
        KfxMark.Cue nearGlint = m.add();
        cam.place(nearGlint, 4, -1, 0);
        Recorder r = new Recorder();
        q.write(m, r);
        int quads = r.n / 4;
        assertTrue(quads > 4, "two glints and two rings' pieces");
        assertCentred(r, 0, cam, nearGlint);
        assertCentred(r, 1, cam, farGlint);
        double nearRadius = REACH;
        double farRadius = BODY * 0.5 + REACH * 0.5;
        int stage = 0; // 0 the near ring's full pieces, 1 the far ring's, 2 the near ring's faded, 3 the far ring's
        int[] seen = new int[4];
        for (int k = 2; k < quads; k++) {
            boolean near = Math.abs(flat(r, k * 4, nearRing, cam) - nearRadius) < 1e-5;
            boolean far = Math.abs(flat(r, k * 4, farRing, cam) - farRadius) < 1e-5;
            assertTrue(near ^ far, "quad " + k + " is one ring's piece");
            boolean full = r.colour[k * 4] == FULL;
            int s = (full ? 0 : 2) + (near ? 0 : 1);
            assertTrue(s >= stage, "quad " + k + ": stage " + s + " after stage " + stage);
            stage = s;
            seen[s]++;
        }
        for (int s = 0; s < 4; s++) {
            assertTrue(seen[s] > 0, "stage " + s + " drawn");
        }
    }

    @Test
    void anEmptyMarkDrawsNothing_andOnlyThisFramesCuesAreDrawn() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 0.5, 70.0, 0.5, 0, 0);
        KfxMark m = new KfxMark();
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(0, r.n);
        cam.place(m.add(), 5, 0, 0);
        cam.place(m.add(), 6, 0, 0);
        m.clear();
        cam.place(m.add(), 7, 0, 0);
        q.write(m, r);
        assertEquals(4, r.n);
    }

    @Test
    void positionsHoldThirtyMillionBlocksOut() {
        CueQuads q = quads();
        Cam cam = new Cam(q, 29_999_990.25, 70.5, -29_999_991.75, 45, 10);
        KfxMark m = new KfxMark();
        KfxMark.Cue glint = m.add();
        glint.x = 29_999_993.5;
        glint.y = 71.25;
        glint.z = -29_999_995.125;
        KfxMark.Cue ring = ring(m, 29_999_994.0, 69.0, -29_999_993.0, 70, 0.8f);
        KfxMark.Cue neck = neck(m, 29_999_992.5, 70.5, -29_999_994.5, 15, 0);
        Recorder r = new Recorder();
        q.write(m, r); // drawn in the pipeline's order: each cue's quads found by their cell
        boolean sawGlint = false;
        boolean sawNeck = false;
        for (int k = 0; k < r.n / 4; k++) {
            if (r.u[k * 4] == CueSheet.GLINT.u0(0) && r.v[k * 4] == CueSheet.GLINT.v0()) {
                assertCentred(r, k, cam, glint);
                sawGlint = true;
            } else if (r.u[k * 4] == CueSheet.NECK.u0(0) && r.v[k * 4] == CueSheet.NECK.v0()) {
                assertCentred(r, k, cam, neck);
                sawNeck = true;
            } else {
                double radius = BODY * (1 - 0.8) + REACH * 0.8;
                assertEquals(radius, flat(r, k * 4, ring, cam), 1e-5, "the ring's edge, quad " + k);
                assertEquals(ring.y - cam.y, r.y[k * 4], 1e-5, "its height, quad " + k);
            }
        }
        assertTrue(sawGlint && sawNeck);
    }

    @Test
    void warm_itAllocatesNothingAcrossAThousandFrames() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assertTrue(
                bean instanceof com.sun.management.ThreadMXBean t && t.isThreadAllocatedMemorySupported(),
                "this JVM can't count a thread's allocations: the pinned JDK 25 (HotSpot) can, so this check never skips");
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) bean;
        CueQuads q = quads();
        new Cam(q, 3.5, 66.62, -2.25, 210, 14);
        KfxMark m = new KfxMark();
        CueSheet[] sprites = {CueSheet.GLINT, CueSheet.GLINT_FLARE, CueSheet.SHATTER, CueSheet.BURST, CueSheet.CLASH};
        for (int i = 0; i < KfxMark.CUES; i++) { // a full frame: 6 sprites, 5 rings, 5 Threads
            int row = i / 4;
            double x = (i % 4) * 3.0 - 4;
            double z = row * 3.0 - 6;
            if (i < 6) {
                KfxMark.Cue c = m.add();
                c.cell = sprites[i % sprites.length];
                c.frame = c.cell.frames() - 1;
                c.x = x;
                c.y = 65.5;
                c.z = z;
                c.alpha = i % 2 == 0 ? 1 : 0.6f;
                c.scale = i % 2 == 0 ? 1 : 0.75f;
            } else if (i < 11) {
                ring(m, x, 64.0, z, i * 37f, i / 10f);
            } else {
                neck(m, x, 65.5, z, i * 53f, i % 4);
            }
        }
        Recorder r = new Recorder();
        for (int i = 0; i < 1000; i++) {
            r.clear();
            q.write(m, r); // warm: the JIT done
        }
        long id = Thread.currentThread().threadId();
        long before = t.getThreadAllocatedBytes(id);
        for (int i = 0; i < 1000; i++) {
            r.clear();
            q.write(m, r);
        }
        long after = t.getThreadAllocatedBytes(id);
        assertTrue(r.n > 16 * 4, "it drew the frame");
        assertTrue(after - before < 1024, "allocated " + (after - before) + " bytes over 1,000 frames");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static CueQuads quads() {
        CueQuads q = new CueQuads();
        q.sizes(CUE_SIZE, MIN_PX, FADED);
        return q;
    }

    /** A mark of one cue {@code depth} blocks straight ahead, written. */
    private static Recorder one(CueQuads q, Cam cam, CueSheet cell, double depth, float alpha, float scale) {
        KfxMark m = new KfxMark();
        KfxMark.Cue c = m.add();
        c.cell = cell;
        c.alpha = alpha;
        c.scale = scale;
        cam.place(c, depth, 0, 0);
        Recorder r = new Recorder();
        q.write(m, r);
        assertEquals(4, r.n, "one quad");
        return r;
    }

    /** A ring round an attacker with its feet at ({@code x, y, z}) facing {@code yaw}, with the grab's box. */
    private static KfxMark.Cue ring(KfxMark m, double x, double y, double z, float yaw, float progress) {
        KfxMark.Cue c = m.add();
        c.cell = CueSheet.RING;
        c.x = x;
        c.y = y + GRAB_CY;
        c.z = z;
        c.yaw = yaw;
        c.boxX = GRAB_X;
        c.boxZ = GRAB_Z;
        c.boxHx = GRAB_HX;
        c.boxHz = GRAB_HZ;
        c.body = BODY;
        c.progress = progress;
        return c;
    }

    /** The Thread on a neck box centred at ({@code x, y, z}), its demon drawn at {@code yaw}, its shimmer's frame. */
    private static KfxMark.Cue neck(KfxMark m, double x, double y, double z, float yaw, int frame) {
        KfxMark.Cue c = m.add();
        c.cell = CueSheet.NECK;
        c.frame = frame;
        c.x = x;
        c.y = y;
        c.z = z;
        c.yaw = yaw;
        c.boxHx = NECK_HX;
        return c;
    }

    /** Quad {@code q} is a camera-facing square of half side {@code half} on the cue, its cell's frame at its corners. */
    private static void assertBillboard(
            Recorder r, int q, Cam cam, KfxMark.Cue c, double half, CueSheet cell, int frame) {
        double[] m = assertCentred(r, q, cam, c);
        for (int i = q * 4; i < q * 4 + 4; i++) {
            double dx = r.x[i] - m[0];
            double dy = r.y[i] - m[1];
            double dz = r.z[i] - m[2];
            double across = cam.alongRight(dx, dy, dz);
            double rise = cam.alongUp(dx, dy, dz);
            assertEquals(half, Math.abs(across), EPS, "vertex " + i + " across");
            assertEquals(half, Math.abs(rise), EPS, "vertex " + i + " up");
            assertEquals(0, cam.alongForward(dx, dy, dz), EPS, "vertex " + i + " in the camera's plane");
            assertEquals(
                    across < 0 ? cell.u0(frame) : cell.u1(frame), r.u[i], 1e-7, "vertex " + i + ": u left to right");
            assertEquals(
                    rise > 0 ? cell.v0() : cell.v1(), r.v[i], 1e-7, "vertex " + i + ": the sheet's top at the top");
        }
    }

    /** Quad {@code q}'s corners carry the cell's frame: TL (u0, v0), BL (u0, v1), BR (u1, v1), TR (u1, v0). */
    private static void assertUvs(Recorder r, int q, CueSheet cell, int frame) {
        float[] us = {cell.u0(frame), cell.u0(frame), cell.u1(frame), cell.u1(frame)};
        float[] vs = {cell.v0(), cell.v1(), cell.v1(), cell.v0()};
        for (int i = 0; i < 4; i++) {
            assertEquals(us[i], r.u[q * 4 + i], 1e-7, "u of corner " + i);
            assertEquals(vs[i], r.v[q * 4 + i], 1e-7, "v of corner " + i);
        }
    }

    /** Quad {@code q}'s centre is the cue's, camera-relative; returns it. */
    private static double[] assertCentred(Recorder r, int q, Cam cam, KfxMark.Cue c) {
        double mx = 0;
        double my = 0;
        double mz = 0;
        for (int i = q * 4; i < q * 4 + 4; i++) {
            mx += r.x[i] / 4.0;
            my += r.y[i] / 4.0;
            mz += r.z[i] / 4.0;
        }
        assertEquals(c.x - cam.x, mx, 1e-5, "centred on the cue (x)");
        assertEquals(c.y - cam.y, my, 1e-5, "centred on the cue (y)");
        assertEquals(c.z - cam.z, mz, 1e-5, "centred on the cue (z)");
        return new double[] {mx, my, mz};
    }

    /** Each spoke of the ring (outer, inner): square to the view ray, toward the centre, {@code thick} long. */
    private static void spokes(Recorder r, KfxMark.Cue c, Cam cam, Thickness thick, String what) {
        assertTrue(r.n > 0, what);
        double cx = c.x - cam.x;
        double cy = c.y - cam.y;
        double cz = c.z - cam.z;
        for (int k = 0; k < r.n / 4; k++) {
            for (int[] s : new int[][] {{k * 4, k * 4 + 1}, {k * 4 + 3, k * 4 + 2}}) {
                int o = s[0];
                double[] d = edge(r, o, s[1]);
                double ray = Math.sqrt(r.x[o] * (double) r.x[o] + r.y[o] * (double) r.y[o] + r.z[o] * (double) r.z[o]);
                double depth = cam.alongForward(r.x[o], r.y[o], r.z[o]);
                assertEquals(thick.at(depth), len(d), EPS, what + ": its thickness, vertex " + o);
                assertEquals(0, (d[0] * r.x[o] + d[1] * r.y[o] + d[2] * r.z[o]) / ray, EPS, what + ": faces you, " + o);
                double in = d[0] * (cx - r.x[o]) + d[1] * (cy - r.y[o]) + d[2] * (cz - r.z[o]);
                assertTrue(in > 0, what + ": inward from the reach, vertex " + o);
            }
        }
    }

    private interface Thickness {
        double at(double depth);
    }

    /** Vertex {@code i}'s distance from the ring's axis, across the ground. */
    private static double flat(Recorder r, int i, KfxMark.Cue c, Cam cam) {
        return Math.hypot(r.x[i] - (c.x - cam.x), r.z[i] - (c.z - cam.z));
    }

    /** Vertex {@code i} in the ring's attacker's frame: across (its right) and forward, from its axis. */
    private static double[] att(Recorder r, int i, KfxMark.Cue c, Cam cam) {
        double wx = r.x[i] - (c.x - cam.x);
        double wz = r.z[i] - (c.z - cam.z);
        double yaw = Math.toRadians(c.yaw);
        double rightX = -Math.cos(yaw); // core's Frame: right (−cos ψ, 0, −sin ψ), forward (−sin ψ, 0, cos ψ)
        double rightZ = -Math.sin(yaw);
        double fwdX = -Math.sin(yaw);
        double fwdZ = Math.cos(yaw);
        return new double[] {wx * rightX + wz * rightZ, wx * fwdX + wz * fwdZ};
    }

    /** Whether a point (across, forward) lies in the grab's footprint, by {@code slack} (negative: strictly inside). */
    private static boolean inside(double[] p, double slack) {
        return Math.abs(p[0] - GRAB_X) <= GRAB_HX + slack && Math.abs(p[1] - GRAB_Z) <= GRAB_HZ + slack;
    }

    /** Whether a point lies on the footprint's boundary. */
    private static boolean onEdge(double[] p) {
        boolean side = Math.abs(Math.abs(p[0] - GRAB_X) - GRAB_HX) < 1e-5;
        boolean face = Math.abs(Math.abs(p[1] - GRAB_Z) - GRAB_HZ) < 1e-5;
        return inside(p, 1e-5) && (side || face);
    }

    private static double[] edge(Recorder r, int from, int to) {
        return new double[] {r.x[to] - (double) r.x[from], r.y[to] - (double) r.y[from], r.z[to] - (double) r.z[from]};
    }

    private static double len(double[] d) {
        return Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
    }

    /** Quad {@code q}'s extent along the camera's up. */
    private static double height(Recorder r, int q, Cam cam) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = q * 4; i < q * 4 + 4; i++) {
            double h = cam.alongUp(r.x[i], r.y[i], r.z[i]);
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        return hi - lo;
    }

    /** Quad {@code q}'s extent along the camera's right. */
    private static double width(Recorder r, int q, Cam cam) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = q * 4; i < q * 4 + 4; i++) {
            double w = cam.alongRight(r.x[i], r.y[i], r.z[i]);
            lo = Math.min(lo, w);
            hi = Math.max(hi, w);
        }
        return hi - lo;
    }

    /** Quad {@code q}'s height on a 1080-line screen, in px. */
    private static double px(Recorder r, int q, Cam cam) {
        double depth = 0;
        for (int i = q * 4; i < q * 4 + 4; i++) {
            depth += cam.alongForward(r.x[i], r.y[i], r.z[i]) / 4;
        }
        return height(r, q, cam) * Y_SCALE / (2 * depth) * CueQuads.REFERENCE_LINES;
    }

    /** A camera as vanilla's {@code Camera.setRotation} turns one (yaw and pitch in degrees, no roll), set on the writer. */
    private static final class Cam {
        final double x;
        final double y;
        final double z;
        final Vector3f right = new Vector3f();
        final Vector3f up = new Vector3f();
        final Vector3f forward = new Vector3f();

        Cam(CueQuads q, double x, double y, double z, float yRot, float xRot) {
            this.x = x;
            this.y = y;
            this.z = z;
            Quaternionf o = new Quaternionf()
                    .rotationYXZ((float) Math.PI - yRot * (float) (Math.PI / 180), -xRot * (float) (Math.PI / 180), 0);
            o.transform(1, 0, 0, right);
            o.transform(0, 1, 0, up);
            o.transform(0, 0, -1, forward);
            q.camera(x, y, z, o, Y_SCALE);
        }

        /** Puts the cue {@code depth} ahead, {@code across} to the right and {@code rise} up. */
        void place(KfxMark.Cue c, double depth, double across, double rise) {
            c.x = x + forward.x * depth + right.x * across + up.x * rise;
            c.y = y + forward.y * depth + right.y * across + up.y * rise;
            c.z = z + forward.z * depth + right.z * across + up.z * rise;
        }

        double alongRight(double dx, double dy, double dz) {
            return dx * right.x + dy * right.y + dz * right.z;
        }

        double alongUp(double dx, double dy, double dz) {
            return dx * up.x + dy * up.y + dz * up.z;
        }

        double alongForward(double dx, double dy, double dz) {
            return dx * forward.x + dy * forward.y + dz * forward.z;
        }
    }

    /** A {@link VertexConsumer} recording positions, UVs and colours into preallocated arrays. */
    static final class Recorder implements VertexConsumer {
        static final int MAX = 1 << 14;
        final float[] x = new float[MAX];
        final float[] y = new float[MAX];
        final float[] z = new float[MAX];
        final float[] u = new float[MAX];
        final float[] v = new float[MAX];
        final int[] colour = new int[MAX];
        int n;

        void clear() {
            n = 0;
        }

        @Override
        public VertexConsumer addVertex(float vx, float vy, float vz) {
            x[n] = vx;
            y[n] = vy;
            z[n] = vz;
            u[n] = Float.NaN;
            v[n] = Float.NaN;
            colour[n] = 0;
            n++;
            return this;
        }

        @Override
        public VertexConsumer setUv(float uu, float vv) {
            u[n - 1] = uu;
            v[n - 1] = vv;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            colour[n - 1] = argb;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            throw new AssertionError("a cue sets one ARGB colour");
        }

        @Override
        public VertexConsumer setUv1(int uu, int vv) {
            throw new AssertionError("a cue's vertex is POSITION_TEX_COLOR");
        }

        @Override
        public VertexConsumer setUv2(int uu, int vv) {
            throw new AssertionError("a cue's vertex is POSITION_TEX_COLOR");
        }

        @Override
        public VertexConsumer setUv3(float uu, float vv) {
            throw new AssertionError("a cue's vertex is POSITION_TEX_COLOR");
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            throw new AssertionError("a cue's vertex is POSITION_TEX_COLOR");
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            throw new AssertionError("a cue's vertex is POSITION_TEX_COLOR");
        }
    }
}
