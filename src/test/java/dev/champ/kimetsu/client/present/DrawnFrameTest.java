package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

/**
 * One drawn frame (slice 5d-4a's build step 6, D-145's A5): where a combatant's body is drawn this frame, shared by the
 * stand-in renderer, the world cues and 5d-4b's trails, so a glint never floats off the body it marks.
 *
 * <ul>
 *   <li>it is, to the bit, the math the stand-in renderer ran inline before (5d-3a: vanilla's {@code Mth.rotLerp} and
 *       {@code wrapDegrees}), so moving it moved no stand-in;
 *   <li>with no place and no guard it is the entity's own pose;
 *   <li>fully on its strike path the body stands at the path's place facing its yaw, and the head turns with it;
 *   <li>its guard turns the body and never the head, which keeps its look.
 * </ul>
 */
class DrawnFrameTest {
    @Test
    void itIsTheStandInRenderersOwnMath_toTheBit() {
        SplittableRandom r = new SplittableRandom(146);
        DrawnFrame f = new DrawnFrame();
        for (int i = 0; i < 10_000; i++) {
            double x = r.nextDouble(-30_000_000, 30_000_000);
            double y = r.nextDouble(-64, 320);
            double z = r.nextDouble(-30_000_000, 30_000_000);
            float body = (float) r.nextDouble(-1000, 1000); // vanilla's body yaw isn't wrapped
            float head = Mth.wrapDegrees((float) r.nextDouble(-200, 200));
            float w = r.nextInt(4) == 0 ? 0f : (float) r.nextDouble(0, 1);
            double px = x + r.nextDouble(-3, 3);
            double py = y + r.nextDouble(-1, 1);
            double pz = z + r.nextDouble(-3, 3);
            float pathYaw = (float) r.nextDouble(-720, 720);
            float g = r.nextInt(3) == 0 ? 0f : (float) r.nextDouble(0, 1);
            float guardYaw = (float) r.nextDouble(0, 360);

            // StandInRenderer.extractRenderState's own lines before A5 (5d-3a, D-138 to D-140)
            double ex = x;
            double ey = y;
            double ez = z;
            float bodyRot = body;
            float yRot = head;
            if (w > 0) {
                float h = bodyRot + yRot;
                ex += (px - ex) * w;
                ey += (py - ey) * w;
                ez += (pz - ez) * w;
                bodyRot = Mth.rotLerp(w, bodyRot, pathYaw);
                yRot = Mth.wrapDegrees(Mth.rotLerp(w, h, pathYaw) - bodyRot);
            }
            if (g > 0) {
                float h = bodyRot + yRot;
                bodyRot = Mth.rotLerp(g, bodyRot, guardYaw);
                yRot = Mth.wrapDegrees(h - bodyRot);
            }

            f.entity(x, y, z, body, head).place(w, px, py, pz, pathYaw).guard(g, guardYaw);
            assertEquals(ex, f.x, "x, case " + i);
            assertEquals(ey, f.y, "y, case " + i);
            assertEquals(ez, f.z, "z, case " + i);
            assertEquals(bodyRot, f.bodyYaw, "body yaw, case " + i);
            assertEquals(yRot, f.headYaw, "head yaw, case " + i);
        }
    }

    @Test
    void withNoPlaceAndNoGuard_itIsTheEntitysOwnPose() {
        DrawnFrame f = new DrawnFrame()
                .entity(10.5, 64, -3.25, 400f, -30f)
                .place(0, 99, 99, 99, 90)
                .guard(0, 270);
        assertEquals(10.5, f.x);
        assertEquals(64, f.y);
        assertEquals(-3.25, f.z);
        assertEquals(400f, f.bodyYaw);
        assertEquals(-30f, f.headYaw);
    }

    @Test
    void fullyOnItsPath_theBodyStandsAtThePathsPlaceFacingItsYaw_andTheHeadTurnsWithIt() {
        DrawnFrame f = new DrawnFrame().entity(0, 64, 0, 10f, 20f).place(1, 2, 65, 3, 100f);
        assertEquals(2, f.x);
        assertEquals(65, f.y);
        assertEquals(3, f.z);
        assertEquals(100f, f.bodyYaw);
        assertEquals(0f, f.headYaw, 1e-4, "the head faces the path's yaw too");
    }

    @Test
    void itsGuardTurnsTheBody_theHeadKeepingItsLook() {
        DrawnFrame f = new DrawnFrame().entity(0, 64, 0, 10f, 20f).guard(1, 100f);
        assertEquals(100f, f.bodyYaw);
        assertEquals(30f - 100f, f.headYaw, 1e-4, "the head still looks along 30°");
        DrawnFrame half = new DrawnFrame().entity(0, 64, 0, 350f, 0f).guard(0.5f, 30f);
        assertEquals(370f, half.bodyYaw, 1e-4, "half way, the short way round");
        assertEquals(-20f, half.headYaw, 1e-4);
    }
}
