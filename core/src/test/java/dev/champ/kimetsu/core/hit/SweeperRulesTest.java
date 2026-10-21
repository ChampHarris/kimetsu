package dev.champ.kimetsu.core.hit;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.Arc;
import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.Moveset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sweep's finer rules, each pinned by a case that fails without it: how the aim follows the look (the vector cap,
 * the sweep's own pitch, carrying across ticks), the line-of-sight budget, the eyes' height, moving targets and necks,
 * multi-tick arcs, an arc's trailing edge, the guards against a stale shape or history, and bit-exact results.
 */
class SweeperRulesTest {
    private static final HitRules RULES = Fixtures.hits();
    private static final Moveset WATER = Fixtures.water();
    private static final int DEMON = 100;

    private final Sweeper sweeper = new Sweeper();

    private static SweepShape shape(String id) {
        return SweepShapes.compile(WATER.frame(id), null, Fixtures.PLAYER_EYE, RULES);
    }

    /** The look at tick 9 and at tick 10 onward (to tick 20), the feet at the origin at y 64. */
    private static PoseRing look(double yaw0, double pitch0, double yaw1, double pitch1) {
        PoseRing ring = new PoseRing(32);
        ring.put(Micros.ofTicks(9), 0, 64, 0, yaw0, pitch0);
        for (long n = 10; n <= 20; n++) {
            ring.put(Micros.ofTicks(n), 0, 64, 0, yaw1, pitch1);
        }
        return ring;
    }

    private static int demon(Targets t, int id, double x, double z) {
        double w = Fixtures.DEMON_HALF_WIDTH;
        return t.add(id, x - w, 64, z - w, x + w, 64 + Fixtures.DEMON_HEIGHT, z + w);
    }

    private int sweep(
            SweepShape s,
            int frame,
            long tick,
            PoseRing poses,
            Targets t,
            SwingTrack track,
            Sight sight,
            Contacts out) {
        return sweeper.sweep(7, 1, s, frame, tick, poses, t, track, sight, RULES, out);
    }

    // ------------------------------------------------------------------------------------------------- the aim

    @Test
    void aLevelSweepSpendsItsWholeTurnOnYaw() {
        SwingTrack track = new SwingTrack();
        sweep(
                shape("kimetsu:water/eighth_form"),
                0,
                10,
                look(0, 0, 20, 60),
                new Targets(),
                track,
                Sight.OPEN,
                new Contacts());
        assertNear(20, track.yaw(), 1e-9); // the 60° of pitch costs a level sweep nothing
        assertNear(0, track.pitch(), 0);
    }

    @Test
    void aClampedSweepFollowsTheLookWithinItsClamp() {
        SweepShape slash = shape("kimetsu:water/first_form");
        PoseRing ring = new PoseRing(32);
        ring.put(Micros.ofTicks(9), 0, 64, 0, 0, 80);
        ring.put(Micros.ofTicks(10), 0, 64, 0, 0, 80);
        ring.put(Micros.ofTicks(11), 0, 64, 0, 0, -80);
        SwingTrack track = new SwingTrack();
        sweep(slash, 0, 10, ring, new Targets(), track, Sight.OPEN, new Contacts());
        assertNear(30, track.pitch(), 1e-9); // starts at the clamp, not at 80
        sweep(slash, 1, 11, ring, new Targets(), track, Sight.OPEN, new Contacts());
        // the clamped look runs 26.7, −26.7, −30 across the tick: the aim follows to 26.7, then turns 6.7° a sub-step
        // to 13.3, where an aim kept at the raw 80 would still be crawling back down at 60
        assertNear(40.0 / 3, track.pitch(), 1e-9);
    }

    @Test
    void aDiagonalFlickTurnsTheCapAlongTheDiagonal() {
        SwingTrack track = new SwingTrack();
        sweep(
                shape("kimetsu:light_chain/1"),
                0,
                10,
                look(0, 0, 60, 60),
                new Targets(),
                track,
                Sight.OPEN,
                new Contacts());
        double each = 20 / Math.sqrt(2); // one 20° turn, shared by yaw and pitch
        assertNear(each, track.yaw(), 1e-9);
        assertNear(each, track.pitch(), 1e-9);
    }

    @Test
    void theAimCarriesIntoTheNextTickAndTurnsThroughWindowlessFrames() {
        SweepShape chain = shape("kimetsu:light_chain/1");
        SwingTrack track = new SwingTrack();
        PoseRing flick = look(0, 0, 179, 0);
        sweep(chain, 0, 10, flick, new Targets(), track, Sight.OPEN, new Contacts());
        assertNear(20, track.yaw(), 1e-9);
        sweep(chain, 1, 11, flick, new Targets(), track, Sight.OPEN, new Contacts());
        assertNear(40, track.yaw(), 1e-9); // the second tick carries on from the first

        FrameData late = attack(
                "kimetsu:test/late",
                3,
                List.of(Fixtures.hit(2, 2, 1, 1, 1, 0)),
                Fixtures.box(0, 1.2, 1.5, 0.9, 0.6, 1.1, Sweep.Pitch.LOOK));
        SweepShape lateShape = SweepShapes.compile(late, null, Fixtures.PLAYER_EYE, RULES);
        SwingTrack lateTrack = new SwingTrack();
        Targets t = new Targets();
        demon(t, DEMON, 0, 2);
        Contacts out = new Contacts();
        assertEquals(0, sweep(lateShape, 0, 10, flick, t, lateTrack, Sight.OPEN, out)); // no window open
        sweep(lateShape, 1, 11, flick, t, lateTrack, Sight.OPEN, out);
        assertNear(40, lateTrack.yaw(), 1e-9); // the aim still turned through them
    }

    // ------------------------------------------------------------------------------------- line of sight, eyes

    @Test
    void aTargetCostsAtMostTwoLinesOfSightATick() {
        SweepShape slash = shape("kimetsu:water/first_form");
        Targets t = new Targets();
        int i = demon(t, DEMON, 0, 2);
        t.neck(i, 0, 65.5, 2, 180, 0, 65.5, 2, 180, 0.18, 0.12, 0.18);
        int[] rays = {0};
        Sight wall = (x0, y0, z0, x1, y1, z1) -> {
            rays[0]++;
            return false;
        };
        SwingTrack track = new SwingTrack();
        Contacts out = new Contacts();
        PoseRing still = look(0, 0, 0, 0);
        for (int frame = 0; frame < 3; frame++) {
            int before = rays[0];
            sweep(slash, frame, 10 + frame, still, t, track, wall, out);
            assertTrue(rays[0] - before <= 2, "rays in tick " + (10 + frame) + ": " + (rays[0] - before));
        }
        assertEquals(0, out.size());
    }

    @Test
    void aCrouchedAttackerSeesOverCoverOnlyFromItsCrouchedEyes() {
        SweepShape chain = shape("kimetsu:light_chain/1");
        Targets t = new Targets();
        demon(t, DEMON, 0, 2);
        // a wall at z = 1 up to y 65.55: from standing eyes (65.62) the line to the target's upper body clears it
        Sight wall = (x0, y0, z0, x1, y1, z1) -> {
            if ((z0 - 1.0) * (z1 - 1.0) >= 0) {
                return true;
            }
            return y0 + (y1 - y0) * (1.0 - z0) / (z1 - z0) >= 65.55;
        };
        assertEquals(1, sweep(chain, 0, 10, look(0, 0, 0, 0), t, new SwingTrack(), wall, new Contacts()));
        PoseRing crouched = new PoseRing(32);
        crouched.put(Micros.ofTicks(9), 0, 64, 0, 0, 0, 1.27);
        crouched.put(Micros.ofTicks(10), 0, 64, 0, 0, 0, 1.27);
        assertEquals(0, sweep(chain, 0, 10, crouched, t, new SwingTrack(), wall, new Contacts()));
    }

    @Test
    void eyesHigherThanStandingAreTheStandingEyes() {
        SweepShape chain = shape("kimetsu:light_chain/1");
        Targets t = new Targets();
        demon(t, DEMON, 0, 2);
        // a wall at z = 1 up to y 65.8: standing eyes (65.62) can't see over it; eyes 2.5 up (66.5) would
        Sight wall = (x0, y0, z0, x1, y1, z1) ->
                (z0 - 1.0) * (z1 - 1.0) >= 0 || y0 + (y1 - y0) * (1.0 - z0) / (z1 - z0) >= 65.8;
        assertEquals(0, sweep(chain, 0, 10, look(0, 0, 0, 0), t, new SwingTrack(), wall, new Contacts()));
        PoseRing tall = new PoseRing(32);
        tall.put(Micros.ofTicks(9), 0, 64, 0, 0, 0, 2.5);
        tall.put(Micros.ofTicks(10), 0, 64, 0, 0, 0, 2.5);
        assertEquals(0, sweep(chain, 0, 10, tall, t, new SwingTrack(), wall, new Contacts()));
    }

    // ------------------------------------------------------------------------------------ moving bodies, necks

    @Test
    void aTargetCrossingTheSweepIsHitAtTheSubStepItsInside() {
        Targets t = new Targets();
        double w = Fixtures.DEMON_HALF_WIDTH;
        t.add(DEMON, -3 - w, 64, 2 - w, -3 + w, 65.95, 2 + w, -w, 64, 2 - w, w, 65.95, 2 + w); // −3 to 0 in the tick
        Contacts out = new Contacts();
        sweep(shape("kimetsu:light_chain/1"), 0, 10, look(0, 0, 0, 0), t, new SwingTrack(), Sight.OPEN, out);
        assertEquals(1, out.size());
        assertEquals(2, out.subStep(0)); // at a third of the way it's still at −2, at two thirds at −1: inside
    }

    @Test
    void aTurningNeckIsMetAsItTurns() {
        SweepShape slash = shape("kimetsu:water/first_form"); // 1.6 to each side
        Targets turning = new Targets();
        int i = demon(turning, DEMON, 6, 2); // its body out of reach: only the neck can be met
        turning.neck(i, 1.9, 65.5, 2, 0, 1.9, 65.5, 2, 90, 0.05, 0.12, 0.6); // a long neck turning a quarter
        Contacts out = new Contacts();
        sweep(slash, 0, 10, look(0, 0, 0, 0), turning, new SwingTrack(), Sight.OPEN, out);
        assertEquals(1, out.size());
        assertEquals(SwingTrack.NECK, out.part(0));
        Targets still = new Targets();
        int j = demon(still, DEMON, 6, 2);
        still.neck(j, 1.9, 65.5, 2, 0, 1.9, 65.5, 2, 0, 0.05, 0.12, 0.6); // the same neck, not turning: out of reach
        assertEquals(0, sweep(slash, 0, 10, look(0, 0, 0, 0), still, new SwingTrack(), Sight.OPEN, new Contacts()));
    }

    // ----------------------------------------------------------------------------------------------------- arcs

    private static FrameData attack(String id, int active, List<HitWindow> hits, Sweep sweep) {
        return new FrameData(
                id,
                AttackClass.HEAVY,
                0,
                active,
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
                hits,
                sweep,
                List.of());
    }

    private static SweepShape arc(int active, Sweep.Box box, List<Arc.Sample> samples) {
        FrameData f = attack(
                "kimetsu:test/arc",
                active,
                List.of(Fixtures.hit(0, active - 1, 1, 1, 1, 0)),
                new Sweep(Sweep.Shape.ARC, box, Sweep.Pitch.LEVEL, 0.1, "kimetsu:test/arc"));
        return SweepShapes.compile(f, new Arc("kimetsu:test/arc", 3, samples), Fixtures.PLAYER_EYE, RULES);
    }

    @Test
    void aTwoTickArcSweepsEachTicksOwnSamples() {
        List<Arc.Sample> lateral = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            double x = -3 + i; // the blade runs from 3 to its left to 3 to its right over two ticks
            lateral.add(new Arc.Sample(x, 1.2, 1.0, x, 1.2, 2.0));
        }
        SweepShape blade = arc(2, new Sweep.Box(0, 1.2, 1.5, 3.1, 0.6, 0.6), lateral);
        Targets t = new Targets();
        t.add(DEMON, -1.8, 64, 1.2, -1.2, 65.95, 1.8); // 1.5 to the attacker's right (−x at yaw 0)
        Contacts out = new Contacts();
        PoseRing still = look(0, 0, 0, 0);
        SwingTrack track = new SwingTrack();
        sweep(blade, 0, 10, still, t, track, Sight.OPEN, out);
        assertEquals(0, out.size()); // the first tick's samples stay on its left
        sweep(blade, 1, 11, still, t, track, Sight.OPEN, out);
        assertEquals(1, out.size());
        assertEquals(11, out.tick(0));
        assertEquals(2, out.subStep(0)); // samples 4 to 5: 1 to 2 blocks right
    }

    @Test
    void aBackingAttackersArcReachesWhatItsTrailingEdgeCrosses() {
        List<Arc.Sample> held = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            held.add(new Arc.Sample(0, 1.2, 1.0, 0, 1.2, 2.0)); // the blade held still, 1 to 2 blocks ahead
        }
        SweepShape blade = arc(1, new Sweep.Box(0, 1.2, 1.5, 0.3, 0.3, 0.6), held);
        PoseRing backing = new PoseRing(32);
        backing.put(Micros.ofTicks(9), 0, 64, 0, 0, 0);
        backing.put(Micros.ofTicks(10), 0, 64, -3, 0, 0); // driven back 3 blocks in the tick
        Targets t = new Targets();
        t.add(DEMON, -0.3, 64, 1.6, 0.3, 65.95, 2.2);
        assertEquals(1, sweep(blade, 0, 10, backing, t, new SwingTrack(), Sight.OPEN, new Contacts()));
    }

    @Test
    void theTrailingEdgeReachesBackTheWholeSubStep() {
        List<Arc.Sample> held = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            held.add(new Arc.Sample(0, 1.2, 1.0, 0, 1.2, 2.0));
        }
        SweepShape blade = arc(1, new Sweep.Box(0, 1.2, 1.5, 0.3, 0.3, 0.6), held);
        PoseRing backing = new PoseRing(32);
        backing.put(Micros.ofTicks(9), 0, 64, 0, 0, 0);
        backing.put(Micros.ofTicks(10), 0, 64, -3.9, 0, 0); // driven back just under the 4 blocks that would carry it
        Targets t = new Targets();
        t.add(DEMON, -0.3, 64, 2.0, 0.3, 65.95, 2.6); // only the first sub-step's quad reaches it, at its old tip
        double fromFeet = 2.0 + 3.9 / 3; // from where the first sub-step stands
        assertTrue(fromFeet > blade.reach + 3.9 / 6, "half the sub-step's movement must fall short");
        assertEquals(1, sweep(blade, 0, 10, backing, t, new SwingTrack(), Sight.OPEN, new Contacts()));
    }

    // -------------------------------------------------------------------------------------------- the guards

    @Test
    void aShapeCompiledForOtherSubStepsIsRefused() {
        HitRules four = new HitRules(4, 0.15, 0.25, 30, 30, 20, 4, 1, 2, 2.0, 0.75, 0.7);
        assertThrows(
                IllegalStateException.class,
                () -> sweeper.sweep(
                        7,
                        1,
                        shape("kimetsu:light_chain/1"),
                        0,
                        10,
                        look(0, 0, 0, 0),
                        new Targets(),
                        new SwingTrack(),
                        Sight.OPEN,
                        four,
                        new Contacts()));
    }

    @Test
    void aSweepPastAFullHistoryIsRefusedButAYoungOneClamps() {
        PoseRing full = new PoseRing(4);
        for (long n = 10; n <= 13; n++) {
            full.put(Micros.ofTicks(n), 0, 64, 0, 0, 0);
        }
        SweepShape chain = shape("kimetsu:light_chain/1");
        assertThrows(
                IllegalStateException.class,
                () -> sweep(chain, 0, 5, full, new Targets(), new SwingTrack(), Sight.OPEN, new Contacts()));
        PoseRing young = new PoseRing(32);
        young.put(Micros.ofTicks(9), 0, 64, 0, 0, 0); // a fight just begun: tick 8 was never recorded
        assertDoesNotThrow(
                () -> sweep(chain, 0, 9, young, new Targets(), new SwingTrack(), Sight.OPEN, new Contacts()));
    }

    // ----------------------------------------------------------------------------------------- golden results

    @Test
    void theSweepsContactsAreTheSameBitsOnEveryMachine() {
        // A turning, moving Striking Tide against a crowd: the contact points' exact bits, recorded once. Java's
        // + − × ÷ are the same everywhere, so a change here means an expression or a trig function changed.
        SweepShape tide = shape("kimetsu:water/fourth_form");
        Targets t = new Targets();
        for (int i = 0; i < 6; i++) {
            demon(t, DEMON + i, i * 0.7 - 1.7, 1.6 + 0.3 * (i % 2));
        }
        PoseRing turning = new PoseRing(32);
        for (int n = 0; n <= 30; n++) {
            turning.put(Micros.ofTicks(n), 0.05 * n, 64, 0.2 * n, 4.0 * n, -10 + n);
        }
        SwingTrack track = new SwingTrack();
        Contacts out = new Contacts();
        for (int a = 0; a < 12; a++) {
            sweeper.sweep(7, 1, tide, a, 10 + a, turning, t, track, Sight.OPEN, RULES, out);
        }
        StringBuilder bits = new StringBuilder();
        for (int k = 0; k < out.size(); k++) {
            bits.append(out.target(k))
                    .append('@')
                    .append(out.tick(k))
                    .append('.')
                    .append(out.subStep(k))
                    .append(':')
                    .append(Long.toHexString(Double.doubleToLongBits(out.x(k))))
                    .append(',')
                    .append(Long.toHexString(Double.doubleToLongBits(out.y(k))))
                    .append(',')
                    .append(Long.toHexString(Double.doubleToLongBits(out.z(k))))
                    .append(' ');
        }
        assertEquals(GOLDEN, bits.toString().trim());
        assertEquals(Long.toHexString(GOLDEN_YAW_BITS), Long.toHexString(Double.doubleToLongBits(track.yaw())));
    }

    private static final String GOLDEN = "101@10.1:bff0000000000001,40503e6666666666,3ffe666666666667 "
            + "102@10.1:bfd551c187bae6d8,40503e70cf620e6d,3ffa4b6fd9214d95 "
            + "103@10.1:3fd0436253352327,40503e9442e96d1b,4000baed898c5dae "
            + "101@13.1:bfec2d475dde76fd,40503e6666666666,400050008875e981 "
            + "102@13.1:bfda26c0d0a49668,40503e06cdb2dfb8,3ffb17d07069d058";
    private static final long GOLDEN_YAW_BITS = 0x4055000000000000L; // the swing's final aim: exactly 84°
}
