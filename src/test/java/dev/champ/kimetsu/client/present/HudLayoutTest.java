package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The HUD's places that must hold at every GUI scale (1–6) and every {@code hud_scale} (0.5–2, as {@link HudScale}
 * snaps it), D-142's review leftovers: the Rhythm pips clear the open ring by a screen pixel, the cue marker keeps its
 * gap above the top pip and sits on the crosshair's axis, and the Breath arc keeps its right end clear of the off-hand
 * slot.
 */
class HudLayoutTest {
    private static final int OFFHAND_LEFT = 91 + 29; // vanilla's left off-hand slot, left of the screen's centre
    private static final float EPS = 1e-4f;

    /** Every drawn scale: {@code hud_scale} 0.5 to 2 in steps of 0.05, snapped at GUI scale {@code gui}. */
    private static float[] scales(int gui) {
        float[] s = new float[31];
        for (int i = 0; i <= 30; i++) {
            s[i] = HudScale.snap(0.5 + 0.05 * i, gui);
        }
        return s;
    }

    private static void assertWholeScreenPixels(float guiPx, int gui, String what) {
        float px = guiPx * gui;
        assertEquals(Math.round(px), px, 1e-3f, what + " at " + px + " screen px");
    }

    /** The distance from the crosshair's centre to the nearest point of pip {@code i}'s outline. */
    private static double pipNearest(int i) {
        double dx = Math.max(0, Math.abs(HudLayout.pipX(i)) - HudLayout.PIP_EDGE);
        double dy = Math.max(0, Math.abs(HudLayout.pipY(i)) - HudLayout.PIP_EDGE);
        return Math.hypot(dx, dy);
    }

    @Test
    void thePipsClearTheOpenRingsOutline_byAScreenPixelAtEveryScale() {
        double ringOut = PulseRing.OPEN_RADIUS + HudLayout.RING_EDGE; // the widest ring, just after a beat
        for (int i = 0; i < HudLayout.PIPS; i++) {
            double gap = pipNearest(i) - ringOut;
            assertTrue(gap >= 1, "pip " + i + " is " + gap + " px off the open ring at HUD scale 1");
            // snapped scales keep s * gui >= 1, so one HUD px is at least one screen px; walked to pin that
            for (int gui = 1; gui <= 6; gui++) {
                for (float s : scales(gui)) {
                    assertTrue(gap * s * gui >= 1, "pip " + i + " at GUI " + gui + ", HUD " + s);
                }
            }
        }
    }

    @Test
    void thePipsSitOnWholePixels_aPentagonFromTheTop() {
        assertEquals(0, HudLayout.pipX(0));
        assertEquals(-HudLayout.PIP_RADIUS, HudLayout.pipY(0), "the top pip, straight up: the cue's floor");
        assertEquals(HudLayout.PIP_RADIUS + HudLayout.PIP_EDGE, HudLayout.PIP_TOP);
        for (int i = 0; i < HudLayout.PIPS; i++) {
            assertEquals(Math.round(HudLayout.pipX(i)), HudLayout.pipX(i), "pip " + i);
            assertEquals(Math.round(HudLayout.pipY(i)), HudLayout.pipY(i), "pip " + i);
            double off = Math.hypot(HudLayout.pipX(i), HudLayout.pipY(i)) - HudLayout.PIP_RADIUS;
            assertTrue(Math.abs(off) <= Math.sqrt(0.5), "pip " + i + " is " + off + " px off its circle");
        }
        assertTrue(HudLayout.pipX(1) > 0 && HudLayout.pipY(1) < 0, "clockwise: the second pip upper right");
        for (int i = 1; i < HudLayout.PIPS; i++) { // mirrored about the vertical
            assertEquals(-HudLayout.pipX(i), HudLayout.pipX(HudLayout.PIPS - i), "pip " + i);
            assertEquals(HudLayout.pipY(i), HudLayout.pipY(HudLayout.PIPS - i), "pip " + i);
        }
    }

    @Test
    void theCueKeepsItsGapAboveTheTopPip_atEveryGuiAndHudScale() {
        for (int gui = 1; gui <= 6; gui++) {
            for (float s : scales(gui)) {
                float k = HudLayout.cueScale(s);
                float inset = HudLayout.cueInset(k, gui);
                float lift = HudLayout.CROSSHAIR_CENTRE - (inset + k / 2); // how far the floor puts it up and left
                for (int step = 0; step <= 20; step++) {
                    float progress = step / 20f;
                    float up = HudLayout.cueUp(s, gui, progress);
                    String at = "GUI " + gui + ", HUD " + s + ", progress " + progress;
                    assertWholeScreenPixels(up, gui, at);
                    float bottom = up + lift - (HudLayout.CUE_OUTLINE + 0.5f) * k; // its outline's lowest row
                    float gap = bottom - HudLayout.PIP_TOP * s;
                    float want = (HudLayout.CUE_GAP + HudLayout.CUE_TRAVEL * (1 - progress)) * k;
                    assertTrue(gap >= want - EPS, at + ": gap " + gap + " under " + want);
                    assertTrue(gap < want + 1.5f / gui + EPS, at + ": gap " + gap + " over " + want);
                }
            }
        }
    }

    @Test
    void theCueClosesFromFarToNear_neverShrinkingBelowScaleOne() {
        assertEquals(43, HudLayout.cueUp(1, 2, 0), EPS, "at the cue, HUD scale 1");
        assertEquals(33, HudLayout.cueUp(1, 2, 1), EPS, "at the impact");
        for (int gui = 1; gui <= 6; gui++) {
            for (float s : scales(gui)) {
                float k = HudLayout.cueScale(s);
                assertTrue(k >= 1 && k >= s, "GUI " + gui + ", HUD " + s);
                assertWholeScreenPixels(k, gui, "its scale at GUI " + gui + ", HUD " + s);
                float last = Float.MAX_VALUE;
                for (int step = 0; step <= 20; step++) {
                    float up = HudLayout.cueUp(s, gui, step / 20f);
                    assertTrue(up <= last, "GUI " + gui + ", HUD " + s + ", step " + step);
                    last = up;
                }
            }
        }
    }

    @Test
    void theCueSitsOnTheCrosshairsAxis_toHalfAScreenPixel() {
        for (int gui = 1; gui <= 6; gui++) {
            for (float s : scales(gui)) {
                float k = HudLayout.cueScale(s);
                float inset = HudLayout.cueInset(k, gui);
                float centre = inset + k / 2;
                String at = "GUI " + gui + ", HUD " + s;
                assertWholeScreenPixels(inset, gui, at);
                assertTrue(centre <= HudLayout.CROSSHAIR_CENTRE + EPS, at + ": centre " + centre);
                assertTrue(centre >= HudLayout.CROSSHAIR_CENTRE - 0.5f / gui - EPS, at + ": centre " + centre);
            }
        }
        assertEquals(7, HudLayout.cueInset(1, 2), "scale 1: the crosshair's own centre pixel");
        assertEquals(6.5f, HudLayout.cueInset(2, 2), "scale 2 on GUI 2: centred, not 0.5 px right and down");
        assertEquals(6, HudLayout.cueInset(2, 1), "scale 2 on GUI 1: half a screen pixel up and left");
    }

    @Test
    void theBreathArcScalesAboutItsRightEnd_clearOfTheOffHandSlot() {
        for (int width : new int[] {320, 321, 427, 480, 855}) {
            int centre = width / 2; // vanilla's hotbar centre, whole px
            for (int gui = 1; gui <= 6; gui++) {
                for (float s : scales(gui)) {
                    String at = "width " + width + ", GUI " + gui + ", HUD " + s;
                    float x = HudLayout.arcX(width, s);
                    float right = x + HudLayout.ARC_RIGHT * s;
                    assertEquals(centre - HudLayout.ARC_LEFT + HudLayout.ARC_RIGHT, right, EPS, at);
                    assertTrue(right <= centre - OFFHAND_LEFT - 1 + EPS, at + ": right end at " + (right - centre));
                    assertWholeScreenPixels(x, gui, at);
                }
            }
        }
    }

    @Test
    void upToHudScaleOne_theArcsLeftEndStaysOnTheNarrowestGui() {
        for (int gui = 1; gui <= 6; gui++) {
            for (float s : scales(gui)) {
                if (s <= 1) {
                    float left = HudLayout.arcX(320, s) - HudLayout.ARC_RIGHT * s;
                    assertTrue(left >= 0, "GUI " + gui + ", HUD " + s + ": left end at " + left);
                }
            }
        }
    }
}
