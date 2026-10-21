package dev.champ.kimetsu.core.hit;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.Arc;
import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.Moveset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sweeping an active tick (Rules 7.1–7.4): reach, one hit per target per window, multi-hit windows, line of sight, the
 * swing's capped turn, a carried attacker, the neck of a horizontal cut, a blade that can't tunnel, and determinism.
 */
class SweeperTest {
    private static final HitRules RULES = Fixtures.hits();
    private static final Moveset WATER = Fixtures.water();
    private static final long ATTACKER = 7;
    private static final int DEMON = 100;

    private final Sweeper sweeper = new Sweeper();

    private static SweepShape shape(String id) {
        return SweepShapes.compile(WATER.frame(id), null, Fixtures.PLAYER_EYE, RULES);
    }

    /** An attacker standing at (x, 64, z) from tick {@code from} to {@code to}, looking at yaw and pitch. */
    private static PoseRing standing(double x, double z, double yaw, double pitch, long from, long to) {
        PoseRing ring = new PoseRing(32);
        for (long n = from; n <= to; n++) {
            ring.put(Micros.ofTicks(n), x, 64, z, yaw, pitch);
        }
        return ring;
    }

    /** A demon-sized box with its feet at (x, 64, z). */
    private static int demon(Targets t, int id, double x, double z) {
        double w = Fixtures.DEMON_HALF_WIDTH;
        return t.add(id, x - w, 64, z - w, x + w, 64 + Fixtures.DEMON_HEIGHT, z + w);
    }

    /** Sweeps every active frame of the attack from tick {@code first}; returns the contacts. */
    private Contacts swing(
            SweepShape s,
            long swingKey,
            long first,
            PoseRing poses,
            Targets t,
            SwingTrack track,
            Sight sight,
            HitRules rules) {
        return swingWith(sweeper, s, swingKey, first, poses, t, track, sight, rules);
    }

    private static Contacts swingWith(
            Sweeper by,
            SweepShape s,
            long swingKey,
            long first,
            PoseRing poses,
            Targets t,
            SwingTrack track,
            Sight sight,
            HitRules rules) {
        Contacts out = new Contacts();
        for (int a = 0; a < s.frames().active(); a++) {
            by.sweep(ATTACKER, swingKey, s, a, first + a, poses, t, track, sight, rules, out);
        }
        return out;
    }

    @Test
    void aTargetInReachIsHitOncePerWindowAndOneOutOfReachIsNot() {
        SweepShape chain = shape("kimetsu:light_chain/1"); // reach 2.6, one window over its 2 active ticks
        Targets t = new Targets();
        demon(t, DEMON, 0, 2);
        Contacts hits = swing(chain, 1, 10, standing(0, 0, 0, 0, 0, 20), t, new SwingTrack(), Sight.OPEN, RULES);
        assertEquals(1, hits.size());
        assertEquals(DEMON, hits.target(0));
        assertEquals(SwingTrack.BODY, hits.part(0));
        assertEquals(10, hits.tick(0));
        Targets far = new Targets();
        demon(far, DEMON, 0, 3.2); // its near face at 2.9, past the box's 2.6
        assertEquals(
                0,
                swing(chain, 1, 10, standing(0, 0, 0, 0, 0, 20), far, new SwingTrack(), Sight.OPEN, RULES)
                        .size());
    }

    @Test
    void strikingTideLandsFourHitsAndTurbulentSix() {
        Targets t = new Targets();
        demon(t, DEMON, 0, 1.8);
        PoseRing still = standing(0, 0, 0, 0, 0, 40);
        assertEquals(
                4,
                swing(shape("kimetsu:water/fourth_form"), 1, 10, still, t, new SwingTrack(), Sight.OPEN, RULES)
                        .size());
        assertEquals(
                6,
                swing(shape("kimetsu:water/fourth_form_mushin"), 2, 10, still, t, new SwingTrack(), Sight.OPEN, RULES)
                        .size());
    }

    @Test
    void aNewSwingClearsTheRegistryAndAConsumedTargetIsSkipped() {
        SweepShape tide = shape("kimetsu:water/fourth_form");
        Targets t = new Targets();
        demon(t, DEMON, 0, 1.8);
        PoseRing still = standing(0, 0, 0, 0, 0, 31); // 32 entries: the history reaches every tick swept
        SwingTrack track = new SwingTrack();
        Contacts out = new Contacts();
        sweeper.sweep(ATTACKER, 1, tide, 0, 10, still, t, track, Sight.OPEN, RULES, out);
        assertEquals(1, out.size());
        track.consumeSwing(DEMON); // its guard stance blocked the form: blocked whole, once
        for (int a = 1; a < 12; a++) {
            sweeper.sweep(ATTACKER, 1, tide, a, 10 + a, still, t, track, Sight.OPEN, RULES, out);
        }
        assertEquals(1, out.size());
        sweeper.sweep(ATTACKER, 2, tide, 0, 30, still, t, track, Sight.OPEN, RULES, out); // the next swing
        assertEquals(2, out.size());
    }

    @Test
    void twoAttackersOnOneTargetBothLandAndTheRegistryGrows() {
        SweepShape tide = shape("kimetsu:water/fourth_form_mushin");
        Targets t = new Targets();
        for (int i = 0; i < 40; i++) {
            int row = i / 5; // a crowd packed in front, five abreast
            demon(t, DEMON + i, (i % 5) * 0.2 - 0.4, 1.5 + row * 0.05);
        }
        PoseRing still = standing(0, 0, 0, 0, 0, 40);
        Contacts one = swing(tide, 1, 10, still, t, new SwingTrack(), Sight.OPEN, RULES);
        Contacts other = swing(tide, 1, 10, still, t, new SwingTrack(), Sight.OPEN, RULES);
        assertEquals(240, one.size()); // 40 targets × 6 windows: nothing thrown, nothing lost
        assertEquals(240, other.size()); // a second attacker's registry is its own
    }

    @Test
    void aWallBlocksAHitAndLeavesTheWindowFreeToLandOnceItsGone() {
        SweepShape chain = shape("kimetsu:light_chain/1");
        Targets t = new Targets();
        demon(t, DEMON, 0, 2);
        PoseRing still = standing(0, 0, 0, 0, 0, 20);
        Sight wall = (x0, y0, z0, x1, y1, z1) -> !((z0 - 1.0) * (z1 - 1.0) < 0); // a wall across z = 1
        SwingTrack track = new SwingTrack();
        Contacts out = new Contacts();
        sweeper.sweep(ATTACKER, 1, chain, 0, 10, still, t, track, wall, RULES, out);
        assertEquals(0, out.size());
        sweeper.sweep(ATTACKER, 1, chain, 1, 11, still, t, track, Sight.OPEN, RULES, out); // the same window
        assertEquals(1, out.size());
    }

    @Test
    void aTargetHalfBehindALowWallIsHitThroughTheSecondLine() {
        SweepShape chain = shape("kimetsu:light_chain/1");
        Targets t = new Targets();
        demon(t, DEMON, 0, 2);
        PoseRing still = standing(0, 0, 0, 0, 0, 20);
        // a wall at z = 1 up to 65.4: a line from the eyes (65.62) to the body's middle passes under its top
        Sight lowWall = (x0, y0, z0, x1, y1, z1) -> {
            if ((z0 - 1.0) * (z1 - 1.0) >= 0) {
                return true;
            }
            double y = y0 + (y1 - y0) * (1.0 - z0) / (z1 - z0);
            return y >= 65.4;
        };
        assertEquals(
                1,
                swing(chain, 1, 10, still, t, new SwingTrack(), lowWall, RULES).size());
        HitRules oneRay = new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, 1, 1, 2.0, 0.75, 0.7);
        assertEquals(
                0,
                swing(chain, 1, 10, still, t, new SwingTrack(), lowWall, oneRay).size());
    }

    @Test
    void aMouseFlickTurnsTheSwingTwentyDegreesAndNoMore() {
        SweepShape chain = shape("kimetsu:light_chain/1");
        Targets t = new Targets();
        demon(t, DEMON, -2, -0.8); // behind and to the right: 112° round
        PoseRing flick = new PoseRing(32);
        flick.put(Micros.ofTicks(9), 0, 64, 0, 0, 0);
        flick.put(Micros.ofTicks(10), 0, 64, 0, 179, 0); // nearly a half turn in one tick (179: no doubt which way)
        flick.put(Micros.ofTicks(11), 0, 64, 0, 179, 0);
        SwingTrack track = new SwingTrack();
        Contacts out = new Contacts();
        sweeper.sweep(ATTACKER, 1, chain, 0, 10, flick, t, track, Sight.OPEN, RULES, out);
        assertEquals(0, out.size());
        assertNear(20, Pose.wrap(track.yaw()), 1e-9); // 179° asked for, 20° turned
        HitRules uncapped = new HitRules(3, 0.15, 0.25, 30, 30, 180, 4, 1, 2, 2.0, 0.75, 0.7);
        Contacts spun = new Contacts();
        sweeper.sweep(ATTACKER, 1, chain, 0, 10, flick, t, new SwingTrack(), Sight.OPEN, uncapped, spun);
        assertEquals(1, spun.size()); // without the cap the slash sweeps the half-circle behind
    }

    @Test
    void aCarriedAttackerSweepsOnlyWhereItLands() {
        SweepShape chain = shape("kimetsu:light_chain/1");
        PoseRing pearl = new PoseRing(32);
        pearl.put(Micros.ofTicks(9), 0, 64, 0, 0, 0);
        pearl.put(Micros.ofTicks(10), 10, 64, 0, 0, 0); // 10 blocks in one tick
        Targets midway = new Targets();
        demon(midway, DEMON, 10.0 / 3, 2); // where the first sub-step would stand
        assertEquals(0, sweepOnce(chain, pearl, midway));
        Targets landing = new Targets();
        demon(landing, DEMON, 10, 2);
        assertEquals(1, sweepOnce(chain, pearl, landing));
        PoseRing walk = new PoseRing(32);
        walk.put(Micros.ofTicks(9), 0, 64, 0, 0, 0);
        walk.put(Micros.ofTicks(10), 3.9, 64, 0, 0, 0); // within the 4 blocks: every sub-step sweeps
        Targets passed = new Targets();
        demon(passed, DEMON, 1.3, 2);
        assertEquals(1, sweepOnce(chain, walk, passed));
    }

    private int sweepOnce(SweepShape s, PoseRing poses, Targets t) {
        Contacts out = new Contacts();
        return sweeper.sweep(ATTACKER, 1, s, 0, 10, poses, t, new SwingTrack(), Sight.OPEN, RULES, out);
    }

    @Test
    void aHorizontalCutReachesTheNeckAndAVerticalOneDoesNot() {
        Targets t = new Targets();
        int i = demon(t, DEMON, 0, 2);
        t.neck(i, 0, 65.5, 2, 180, 0, 65.5, 2, 180, 0.18, 0.12, 0.18);
        PoseRing still = standing(0, 0, 0, 0, 0, 30);
        Contacts slash = swing(shape("kimetsu:water/first_form"), 1, 10, still, t, new SwingTrack(), Sight.OPEN, RULES);
        assertEquals(2, slash.size());
        assertEquals(SwingTrack.BODY, slash.part(0));
        assertEquals(SwingTrack.NECK, slash.part(1));
        Contacts wheel =
                swing(shape("kimetsu:water/second_form"), 2, 10, still, t, new SwingTrack(), Sight.OPEN, RULES);
        assertEquals(1, wheel.size()); // Water Wheel is a vertical cut: it can't take a head (Rule 5.3)
    }

    @Test
    void theSameSwingAlwaysFindsTheSameContacts() {
        SweepShape tide = shape("kimetsu:water/fourth_form");
        Targets t = new Targets();
        for (int i = 0; i < 6; i++) {
            demon(t, DEMON + i, i * 0.7 - 1.7, 1.6 + 0.3 * (i % 2));
        }
        PoseRing turning = new PoseRing(32);
        for (int n = 0; n <= 30; n++) {
            turning.put(Micros.ofTicks(n), 0.05 * n, 64, 0.2 * n, 4.0 * n, -10 + n);
        }
        Contacts a = swing(tide, 1, 10, turning, t, new SwingTrack(), Sight.OPEN, RULES);
        Contacts b = swingWith(new Sweeper(), tide, 1, 10, turning, t, new SwingTrack(), Sight.OPEN, RULES);
        assertTrue(a.size() > 0);
        assertEquals(a.size(), b.size());
        for (int k = 0; k < a.size(); k++) {
            assertEquals(a.target(k), b.target(k));
            assertEquals(a.window(k), b.window(k));
            assertEquals(a.tick(k), b.tick(k));
            assertEquals(a.subStep(k), b.subStep(k));
            assertEquals(Double.doubleToLongBits(a.x(k)), Double.doubleToLongBits(b.x(k)));
            assertEquals(Double.doubleToLongBits(a.z(k)), Double.doubleToLongBits(b.z(k)));
        }
    }

    // ----------------------------------------------------------------------------------------------------- arcs

    /** A test attack with an arc: one active tick, its blade's samples given, a 0.1-block radius. */
    private static SweepShape arcShape(List<Arc.Sample> samples) {
        FrameData f = new FrameData(
                "kimetsu:test/arc",
                AttackClass.HEAVY,
                0,
                1,
                5,
                0,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null,
                Cue.NONE,
                null,
                List.of(Fixtures.hit(0, 0, 1, 1, 1, 0)),
                new Sweep(
                        Sweep.Shape.ARC,
                        new Sweep.Box(0, 1.2, 1.5, 1.8, 0.6, 1.8),
                        Sweep.Pitch.LEVEL,
                        0.1,
                        "kimetsu:test/arc"),
                List.of());
        return SweepShapes.compile(f, new Arc("kimetsu:test/arc", 3, samples), Fixtures.PLAYER_EYE, RULES);
    }

    @Test
    void aBladeMovingThreeBlocksATickCantTunnelPastATarget() {
        List<Arc.Sample> lateral = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            double x = -1.5 + i; // −1.5, −0.5, 0.5, 1.5: no sample lies within 0.3 of the target's middle
            lateral.add(new Arc.Sample(x, 1.2, 1.0, x, 1.2, 2.0));
        }
        SweepShape blade = arcShape(lateral);
        Targets t = new Targets();
        t.add(DEMON, -0.3, 64, 1.2, 0.3, 64 + 1.95, 1.8); // 0.6 wide, in the blade's path
        assertEquals(1, sweepOnce(blade, standing(0, 0, 0, 0, 9, 10), t));
        // testing the samples alone (each a segment thickened by the radius) misses it: the quads are what hit
        Obb box = new Obb().setAabb(-0.3, 64, 1.2, 0.3, 65.95, 1.8);
        for (Arc.Sample s : lateral) {
            Tri segment = new Tri()
                    .set(
                            -s.baseX(),
                            64 + s.baseY(),
                            s.baseZ(),
                            -s.tipX(),
                            64 + s.tipY(),
                            s.tipZ(),
                            -s.tipX(),
                            64 + s.tipY(),
                            s.tipZ());
            assertFalse(Sat.overlap(segment, 0.1, box));
        }
    }

    @Test
    void aBladeTurningHalfACircleInATickStillHitsWhatItCrosses() {
        List<Arc.Sample> spin = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            double a = Math.toRadians(-90 + 60 * i); // from the left, through the front, to the right
            spin.add(new Arc.Sample(
                    0.5 * Math.sin(a), 1.2, 0.5 * Math.cos(a), 1.5 * Math.sin(a), 1.2, 1.5 * Math.cos(a)));
        }
        Targets t = new Targets();
        t.add(DEMON, -0.3, 64, 0.85, 0.3, 64 + 1.95, 1.15); // straight ahead, 1 block out
        assertEquals(1, sweepOnce(arcShape(spin), standing(0, 0, 0, 0, 9, 10), t));
    }

    // ------------------------------------------------------------------------------------------ compiling

    @Test
    void anArcNeedsASamplePerSubStep() {
        List<Arc.Sample> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            three.add(new Arc.Sample(0, 1, 1, 0, 1, 2));
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> arcShape(three));
        assertTrue(e.getMessage().contains("need 4"), e.getMessage());
    }

    @Test
    void aBoxThatCouldLeaveGapsIsRejected() {
        FrameData f = new FrameData(
                "kimetsu:test/thin",
                AttackClass.LIGHT,
                2,
                2,
                4,
                0,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null,
                Cue.NONE,
                null,
                List.of(Fixtures.hit(0, 1, 1, 1, 1, 0)),
                Fixtures.box(0, 1.5, 4, 0.05, 0.05, 1.0, Sweep.Pitch.LOOK), // a thin rod 5 blocks out
                List.of());
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> SweepShapes.compile(f, null, Fixtures.PLAYER_EYE, RULES));
        assertTrue(e.getMessage().contains("sweep.half") && e.getMessage().contains("slip"), e.getMessage());
    }

    @Test
    void everyM1AttackCompiles() {
        for (String id : List.of(
                "kimetsu:light_chain/1",
                "kimetsu:light_chain/2",
                "kimetsu:light_chain/3",
                "kimetsu:pommel",
                "kimetsu:water/first_form",
                "kimetsu:water/first_form_mushin",
                "kimetsu:water/second_form",
                "kimetsu:water/fourth_form",
                "kimetsu:water/fourth_form_mushin",
                "kimetsu:water/eighth_form")) {
            assertDoesNotThrow(() -> shape(id), id);
        }
        for (FrameData f : List.of(
                Fixtures.swipe(),
                Fixtures.heavy(),
                Fixtures.leap(),
                Fixtures.lowSweep(),
                Fixtures.grab(),
                Fixtures.guardCounter())) {
            assertDoesNotThrow(() -> SweepShapes.compile(f, null, Fixtures.DEMON_EYE, RULES), f.id());
        }
        assertTrue(shape("kimetsu:light_chain/1").reach() >= 2.6);
        assertTrue(shape("kimetsu:water/first_form_mushin").reach()
                > shape("kimetsu:water/first_form").reach());
    }
}
