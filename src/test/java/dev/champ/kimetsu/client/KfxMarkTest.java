package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.client.kfx.CueSheet;
import org.junit.jupiter.api.Test;

/**
 * One frame's kfx (D-144's craft call 11): at most 16 cues (D-145's A7), each slot reused frame to frame and handed out
 * reset, so a cue never inherits the last frame's bystander dimming, strip frame, ring or tether (D-147).
 */
class KfxMarkTest {
    @Test
    void itHoldsSixteenCues_andRefusesASeventeenth() {
        KfxMark m = new KfxMark();
        assertEquals(16, KfxMark.CUES);
        for (int i = 0; i < KfxMark.CUES; i++) {
            assertFalse(m.full(), "room for cue " + i);
            m.add();
        }
        assertTrue(m.full());
        assertEquals(16, m.cueCount());
        assertThrows(IllegalStateException.class, m::add, "the fill must keep to 16 (yours first, then nearest)");
    }

    @Test
    void aSlotComesBackResetEachFrame() {
        KfxMark m = new KfxMark();
        KfxMark.Cue c = m.add();
        c.cell = CueSheet.RING;
        c.frame = 3;
        c.x = 1;
        c.y = 2;
        c.z = 3;
        c.yaw = 45;
        c.boxX = 0.1;
        c.boxZ = 1.05;
        c.boxHx = 0.6;
        c.boxHz = 0.95;
        c.body = 0.3;
        c.progress = 0.5f;
        c.alpha = 0.6f;
        c.scale = 0.75f;
        c.framed = true;
        c.tethered = true;
        c.tetherX = 4;
        c.tetherY = 5;
        c.tetherZ = 6;
        m.clear();
        assertEquals(0, m.cueCount());
        KfxMark.Cue again = m.add();
        assertSame(c, again, "the same slot: nothing allocated");
        assertSame(CueSheet.GLINT, again.cell);
        assertEquals(0, again.frame);
        assertEquals(0, again.x);
        assertEquals(0, again.y);
        assertEquals(0, again.z);
        assertEquals(0, again.yaw);
        assertEquals(0, again.boxX);
        assertEquals(0, again.boxZ);
        assertEquals(0, again.boxHx);
        assertEquals(0, again.boxHz);
        assertEquals(0, again.body);
        assertEquals(0, again.progress);
        assertEquals(1, again.alpha, "full strength until the fill dims it");
        assertEquals(1, again.scale);
        assertFalse(again.framed, "drawn where it stands until the fill keeps it on screen (D-147)");
        assertFalse(again.tethered);
        assertEquals(0, again.tetherX);
        assertEquals(0, again.tetherY);
        assertEquals(0, again.tetherZ);
    }

    @Test
    void onlyTheCuesAddedThisFrameAreReachable() {
        KfxMark m = new KfxMark();
        KfxMark.Cue a = m.add();
        m.add();
        assertSame(a, m.cue(0));
        m.clear();
        m.add();
        assertEquals(1, m.cueCount());
        assertThrows(IndexOutOfBoundsException.class, () -> m.cue(1), "last frame's second cue is gone");
    }
}
