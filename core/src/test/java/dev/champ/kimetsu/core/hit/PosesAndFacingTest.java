package dev.champ.kimetsu.core.hit;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** An attacker's frame follows Minecraft's conventions, the pose history interpolates, and the front arc is 120°. */
class PosesAndFacingTest {
    private static final double EPS = 1e-12;

    @Test
    void theFrameUsesMinecraftsYaw() {
        Frame f = new Frame().set(0, 0, 0, 1.62, 0, 0);
        V3 p = new V3();
        f.toWorld(1, 1.62, 0, p); // one block to the right, at eye height
        assertNear(-1, p.x(), EPS); // facing +z, the right hand points to −x
        assertNear(0, p.z(), EPS);
        f.toWorld(0, 1.62, 1, p);
        assertNear(1, p.z(), EPS); // forward is +z
        f.set(0, 0, 0, 1.62, 90, 0);
        f.toWorld(0, 1.62, 1, p);
        assertNear(-1, p.x(), EPS); // yaw 90 faces −x (Entity.calculateViewVector)
        assertNear(0, p.z(), EPS);
    }

    @Test
    void theFrameMatchesVanillasViewUpAndRightAtAnyAim() {
        Random rnd = new Random(9);
        V3 p = new V3();
        for (int i = 0; i < 100_000; i++) {
            double yaw = rnd.nextDouble() * 1440 - 720;
            double pitch = rnd.nextDouble() * 180 - 90;
            double y = Math.toRadians(yaw);
            double x = Math.toRadians(pitch);
            Frame f = new Frame().set(0, 0, 0, 1.62, yaw, pitch);
            f.toWorld(0, 1.62, 1, p); // Entity.calculateViewVector: (sin(−ψ) cos θ, −sin θ, cos(−ψ) cos θ)
            assertNear(-Math.sin(y) * Math.cos(x), p.x(), 1e-12);
            assertNear(-Math.sin(x), p.y() - 1.62, 1e-12);
            assertNear(Math.cos(y) * Math.cos(x), p.z(), 1e-12);
            f.toWorld(0, 2.62, 0, p); // calculateUpVector: the view vector at θ − 90
            assertNear(-Math.sin(y) * Math.cos(x - Math.PI / 2), p.x(), 1e-12);
            assertNear(-Math.sin(x - Math.PI / 2), p.y() - 1.62, 1e-12);
            assertNear(Math.cos(y) * Math.cos(x - Math.PI / 2), p.z(), 1e-12);
            f.toWorld(1, 1.62, 0, p); // and the right hand, level whatever the pitch
            assertNear(-Math.cos(y), p.x(), 1e-12);
            assertNear(0, p.y() - 1.62, 1e-12);
            assertNear(-Math.sin(y), p.z(), 1e-12);
        }
    }

    @Test
    void pitchTurnsAboutTheEyesAndLooksDown() {
        Frame f = new Frame().set(5, 64, 5, 1.62, 0, 90); // looking straight down
        V3 p = new V3();
        f.toWorld(0, 1.62, 1, p); // a block "forward" from the eyes
        assertNear(5, p.x(), EPS);
        assertNear(64 + 1.62 - 1, p.y(), EPS);
        assertNear(5, p.z(), EPS);
        f.set(0, 0, 0, 1.62, 0, 30);
        f.toWorld(0, 1.62, 2, p);
        assertNear(1.62 - 2 * Math.sin(Math.toRadians(30)), p.y(), EPS); // the far end drops, the eyes stay
        assertNear(2 * Math.cos(Math.toRadians(30)), p.z(), EPS);
    }

    @Test
    void aBoxFrameCarriesTheBoxsCentreAndAxes() {
        Frame f = new Frame().set(10, 0, 0, 1.62, 90, 0);
        Obb o = f.box(Fixtures.box(0, 1.2, 1.5, 0.9, 0.6, 1.1, Sweep.Pitch.LOOK).box(), new Obb());
        assertNear(10 - 1.5, o.cx(), EPS); // 1.5 blocks ahead, toward −x
        assertNear(1.2, o.cy(), EPS);
        assertNear(0, o.cz(), EPS);
        assertNear(0.9 + 0, o.reachZ(), 1e-9); // its width now runs along z
        assertNear(1.1, o.reachX(), 1e-9);
    }

    @Test
    void poseHistoryInterpolatesTheShortWayRound() {
        PoseRing ring = new PoseRing(4);
        Pose out = new Pose();
        assertFalse(ring.at(0, out));
        ring.put(0, 0, 64, 0, 170, 0);
        ring.put(50_000, 2, 64, 0, -170, 10);
        assertTrue(ring.at(25_000, out));
        assertNear(1, out.x(), EPS);
        assertNear(0, Pose.wrap(out.yaw() - 180), 1e-9); // 170 → −170 passes through 180, not 0
        assertNear(5, out.pitch(), EPS);
        assertFalse(ring.at(60_000, out)); // past the newest: the newest, reported as outside
        assertNear(2, out.x(), EPS);
        assertFalse(ring.at(-1, out)); // before the oldest
        assertNear(0, out.x(), EPS);
    }

    @Test
    void poseHistoryKeepsItsNewestAndRefusesTheOld() {
        PoseRing ring = new PoseRing(3);
        for (int i = 0; i < 5; i++) {
            ring.put(i * 50_000L, i, 0, 0, 0, 0);
        }
        assertEquals(3, ring.size());
        assertEquals(100_000, ring.oldestTime());
        assertEquals(200_000, ring.newestTime());
        ring.put(200_000, 9, 0, 0, 0, 0); // the same time replaces the newest
        Pose out = new Pose();
        ring.at(200_000, out);
        assertNear(9, out.x(), EPS);
        assertEquals(3, ring.size());
        assertThrows(IllegalArgumentException.class, () -> ring.put(150_000, 0, 0, 0, 0, 0));
    }

    @Test
    void poseHistoryCarriesEyeHeightsAndRefusesWhatIsntFinite() {
        PoseRing ring = new PoseRing(8);
        ring.put(0, 0, 64, 0, 0, 0);
        ring.put(50_000, 0, 64, 0, 0, 0, 1.27); // crouched
        ring.put(100_000, 0, 64, 0, 0, 0);
        Pose out = new Pose();
        ring.at(10_000, out);
        assertNear(1.62, out.eyeOr(1.62), 0); // standing until the crouch
        ring.at(60_000, out);
        assertNear(1.27, out.eyeOr(1.62), 0); // the eyes in effect: vanilla changes them at once
        assertThrows(IllegalArgumentException.class, () -> ring.put(150_000, Double.NaN, 64, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ring.put(150_000, 0, 64, 0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> ring.put(150_000, 0, 64, 0, 0, 0, -1));
        assertFalse(ring.full());
        for (int i = 4; i <= 8; i++) {
            ring.put(i * 50_000L, 0, 64, 0, 0, 0);
        }
        assertTrue(ring.full());
    }

    @Test
    void theFrontArcIsSixtyDegreesEachSide() {
        double just = Math.toRadians(59.999);
        double past = Math.toRadians(60.001);
        // yaw 0 faces +z; a point at angle a to the right lies at (−sin a, cos a)
        assertTrue(Facing.inArc(0, 60, -Math.sin(just), Math.cos(just)));
        assertFalse(Facing.inArc(0, 60, -Math.sin(past), Math.cos(past)));
        assertTrue(Facing.inArc(0, 60, Math.sin(just), Math.cos(just))); // and to the left
        assertFalse(Facing.inArc(0, 60, 0, -1)); // behind
        assertTrue(Facing.inArc(0, 60, 0, 0)); // right on top of it
        assertTrue(Facing.inArc(720, 60, 0, 1)); // yaw has no bound
        assertTrue(Facing.inArc(90, 60, -1, 0)); // yaw 90 faces −x
    }
}
