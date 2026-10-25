package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import org.junit.jupiter.api.Test;

/**
 * Where a pushed or knocked-down player's filed poses met the level ({@link ContactCheck}, Rule 9.2): a room with a
 * wall whose face is at x = 2 and a floor at y = 64. The player's box is 0.3 each side, so it touches the wall from
 * x = 1.7. A push runs along +x from tick 10, 2 blocks over 4 ticks, splatting until tick 20; the grace is 2 ticks. A
 * wall counts only where it leaves the push under half its move along its heading (D-086); a corner room adds a second
 * wall whose face is at z = 2.
 */
class ContactCheckTest {
    private static final double HALF = 0.3;
    private static final double HEIGHT = 1.8;
    private static final long NONE = ContactCheck.NONE;
    private static final double KEEP = 0.5; // a wall counts if it leaves the push under half its move (Fixtures')

    /** The room, counting what it's asked: a wall at x = 2 and, in a corner room, another at z = 2. */
    private static final class Room implements Ground {
        private final boolean corner;
        int asked;

        Room(boolean corner) {
            this.corner = corner;
        }

        @Override
        public double clear(
                double x,
                double y,
                double z,
                double halfWidth,
                double height,
                double dirX,
                double dirZ,
                double distance) {
            asked++;
            double d = distance;
            if (dirX > 0) {
                d = Math.min(d, (2.0 - (x + halfWidth)) / dirX);
            }
            if (corner && dirZ > 0) {
                d = Math.min(d, (2.0 - (z + halfWidth)) / dirZ);
            }
            return Math.max(0, d);
        }

        @Override
        public double below(double x, double y, double z, double halfWidth, double height, double distance) {
            asked++;
            return Math.max(0, Math.min(distance, y - 64));
        }
    }

    private final Room room = new Room(false);
    private final ContactCheck check = new ContactCheck();
    private final PoseRing poses = new PoseRing(32);
    private final Combatant live = Arena.combatant(new PulseSchedule(13), 0);

    private static final long SHOWN = 480_000; // its first tick less a 20 ms tolerance: the Jumps' business, not ours
    private static final Effect.Push ALONG_X = new Effect.Push(1, 0, 2.0, 4, 0, 10, 20, SHOWN);

    private void at(long tick, double x, double y) {
        poses.put(Micros.ofTicks(tick), x, y, 0, 270, 0);
    }

    /** A pose on the floor at {@code (x, z)}. */
    private void slid(long tick, double x, double z) {
        poses.put(Micros.ofTicks(tick), x, 64, z, 270, 0);
    }

    private long wall() {
        return check.wall(poses, room, HALF, HEIGHT, KEEP);
    }

    /** A push like {@link #ALONG_X} but turned from +z by {@code deg} into the wall at x = 2: 0° runs along it. */
    private static Effect.Push into(double deg) {
        double r = Math.toRadians(deg);
        return new Effect.Push(Math.sin(r), Math.cos(r), 2.0, 4, 0, 10, 20, SHOWN);
    }

    /** Where push {@code p}'s wall is met by a body flush against the wall at x = 2 on its first tick. */
    private static long flushOnItsFirstTick(Effect.Push p, double keep) {
        ContactCheck c = new ContactCheck();
        PoseRing flush = new PoseRing(8);
        c.push(p, 10, 8, 2);
        flush.put(Micros.ofTicks(10), 1.7, 64, 0, 270, 0);
        return c.wall(flush, new Room(false), HALF, HEIGHT, keep);
    }

    private long floor() {
        return check.floor(poses, live, room, HALF, HEIGHT);
    }

    @Test
    void theFirstPoseInTheWindowTouchingTheWallAlongThePushIsTheContact_once() {
        at(8, 0, 64);
        check.push(ALONG_X, 10, 8, 2);
        at(9, 0, 64);
        at(10, 0.5, 64);
        assertEquals(NONE, wall());
        at(11, 1.0, 64);
        at(12, 1.5, 64);
        at(13, 1.7, 64); // its box's face reaches the wall
        at(14, 1.7, 64);
        assertEquals(Micros.ofTicks(13), wall());
        assertEquals(NONE, wall()); // one wall a push
        at(15, 1.7, 64);
        assertEquals(NONE, wall());
        assertEquals(true, check.splats());
        assertEquals(10, check.mob());
    }

    @Test
    void aBodyAlreadyAtTheWallMeetsItOnThePushsFirstTick_notBefore() {
        at(6, 1.7, 64);
        check.push(ALONG_X, 10, 8, 2);
        at(9, 1.7, 64);
        at(10, 1.7, 64);
        assertEquals(Micros.ofTicks(10), wall());
    }

    @Test
    void aWallMetAfterTheWindowOrAwayFromItCountsForNothing() {
        check.push(ALONG_X, 10, 8, 2); // splats to 20, so the window holds to 20
        for (long t = 10; t <= 20; t++) {
            at(t, 1.0, 64);
        }
        assertEquals(NONE, wall());
        at(21, 1.7, 64);
        assertEquals(NONE, wall());

        ContactCheck away = new ContactCheck();
        PoseRing flush = new PoseRing(32);
        away.push(new Effect.Push(-1, 0, 2.0, 4, 0, 10, 20, SHOWN), 10, 8, 2);
        flush.put(Micros.ofTicks(10), 1.7, 64, 0, 270, 0);
        assertEquals(NONE, away.wall(flush, room, HALF, HEIGHT, KEEP));
    }

    @Test
    void aPushThatDoesntSplatLooksForItsWallUntilItEndsPlusTheGrace() {
        check.push(new Effect.Push(1, 0, 1.5, 4, 0, 10, Effect.Push.NO_SPLAT, SHOWN), 10, 8, 2);
        at(16, 1.7, 64); // 10 + 4 + 2
        assertEquals(Micros.ofTicks(16), wall());
        assertEquals(false, check.splats());

        ContactCheck late = new ContactCheck();
        PoseRing past = new PoseRing(32);
        late.push(new Effect.Push(1, 0, 1.5, 4, 0, 10, Effect.Push.NO_SPLAT, SHOWN), 10, 8, 2);
        past.put(Micros.ofTicks(17), 1.7, 64, 0, 270, 0);
        assertEquals(NONE, late.wall(past, room, HALF, HEIGHT, KEEP));
    }

    @Test
    void aHeadOnPushThatReachesTheWallLateInATickMeetsItOnThatTicksPose() {
        check.push(ALONG_X, 10, 8, 2); // 0.5 blocks a tick
        at(9, -0.2, 64);
        at(10, 0.3, 64);
        at(11, 0.8, 64);
        at(12, 1.3, 64);
        assertEquals(NONE, wall());
        at(13, 1.7, 64); // the wall cut its last move at 0.4, more than half of it: still head-on, still the contact
        assertEquals(Micros.ofTicks(13), wall());
    }

    @Test
    void aPushGrazingAWallItsFlushAgainstSlidesAlongIt_meetingNone() {
        for (double deg : new double[] {5, 17}) { // the two heavies D-086's MCTest saw splat as they slid
            ContactCheck graze = new ContactCheck();
            PoseRing slide = new PoseRing(32);
            Room flush = new Room(false);
            slide.put(Micros.ofTicks(9), 1.7, 64, 0, 270, 0);
            graze.push(into(deg), 10, 8, 2);
            double along = 0.5 * Math.cos(Math.toRadians(deg)); // vanilla keeps the move along the wall
            for (long t = 10; t <= 20; t++) {
                slide.put(Micros.ofTicks(t), 1.7, 64, Math.min(t - 9, 4) * along, 270, 0);
                assertEquals(NONE, graze.wall(slide, flush, HALF, HEIGHT, KEEP), deg + " degrees, tick " + t);
            }
            assertTrue(flush.asked > 11, deg + " degrees: its box touches the wall all along, so each pose asks more");
        }
    }

    @Test
    void aPushMoreThanHalfIntoTheWallItTouchesMeetsIt_aShallowerOneGrazesOn() {
        // at a flat wall a push keeps cos² of its angle into it along its heading: half at 45°
        assertEquals(Micros.ofTicks(10), flushOnItsFirstTick(into(46), KEEP));
        assertEquals(NONE, flushOnItsFirstTick(into(44), KEEP));
        assertEquals(Micros.ofTicks(10), flushOnItsFirstTick(into(90), KEEP)); // head-on, unchanged
        assertEquals(Micros.ofTicks(10), flushOnItsFirstTick(into(17), 1.0)); // the data's share: any loss counts
        assertEquals(NONE, flushOnItsFirstTick(into(46), 0.4));
    }

    @Test
    void aClientThatStartsAGrazeLateMeetsNoWallOnThePoseBeforeItMoves() {
        check.push(into(17), 10, 8, 2);
        slid(9, 1.7, 0);
        slid(10, 1.7, 0); // a tick late (D-084): its first push pose hasn't moved, and it touches the wall
        assertEquals(NONE, wall());
        double along = 0.5 * Math.cos(Math.toRadians(17));
        for (long t = 11; t <= 20; t++) {
            slid(t, 1.7, Math.min(t - 10, 4) * along);
            assertEquals(NONE, wall(), "tick " + t);
        }
    }

    @Test
    void aGrazeThatSlidesIntoACornerMeetsItsOtherWallOnceItTouchesIt_notATickAhead() {
        Room corner = new Room(true); // the push runs mostly along +z: into the wall at z = 2 nearly head-on
        check.push(into(17), 10, 8, 2);
        slid(9, 1.7, 1.022);
        slid(10, 1.7, 1.5); // its face 0.2 short of the second wall: the next tick's slide will be cut, this one wasn't
        assertEquals(NONE, check.wall(poses, corner, HALF, HEIGHT, KEEP));
        slid(11, 1.7, 1.7); // in the corner: both faces take their parts of the push
        assertEquals(Micros.ofTicks(11), check.wall(poses, corner, HALF, HEIGHT, KEEP));
    }

    @Test
    void aWallAcrossZIsJudgedAlike() {
        // flush against the corner room's wall at z = 2 only, pushed mostly along +x: x is the axis that slides on
        for (double deg : new double[] {46, 44}) {
            double r = Math.toRadians(deg);
            ContactCheck c = new ContactCheck();
            PoseRing flush = new PoseRing(8);
            c.push(new Effect.Push(Math.cos(r), Math.sin(r), 2.0, 4, 0, 10, 20, SHOWN), 10, 8, 2);
            flush.put(Micros.ofTicks(10), 0, 64, 1.7, 270, 0);
            assertEquals(
                    deg > 45 ? Micros.ofTicks(10) : NONE,
                    c.wall(flush, new Room(true), HALF, HEIGHT, KEEP),
                    deg + " degrees into it");
        }
    }

    @Test
    void aPostsCornerClippedMovingAcrossBothAxesCutsOnlyTheSmallerOne_asVanillasMoveDoes() {
        // a post filling x ≥ 0.32 and z ≥ 0.32, the box's corner 0.02 off it on each side: moving diagonally the box
        // meets the post's corner, moving along either axis alone from where it stands it passes. Vanilla moves the
        // larger axis (z) first, which brings the box level with the post, then cuts the smaller: the push keeps z's
        // share (0.75), a wall only on rules where any loss counts
        Ground post = (x, y, z, halfWidth, height, dirX, dirZ, distance) -> {
            double inX = enters(x + halfWidth, dirX, 0.32);
            double inZ = enters(z + halfWidth, dirZ, 0.32);
            double enter = Math.max(inX, inZ);
            return enter == Double.POSITIVE_INFINITY ? distance : Math.max(0, Math.min(distance, enter));
        };
        for (double keep : new double[] {KEEP, 1.0}) {
            ContactCheck c = new ContactCheck();
            PoseRing poses = new PoseRing(8);
            c.push(into(30), 10, 8, 2);
            poses.put(Micros.ofTicks(10), 0, 64, 0, 270, 0);
            assertEquals(keep < 0.75 ? NONE : Micros.ofTicks(10), c.wall(poses, post, HALF, HEIGHT, keep), "" + keep);
        }
    }

    /** How far a face at {@code face} moving along {@code d} goes before it passes {@code at}: −∞ already past. */
    private static double enters(double face, double d, double at) {
        if (face > at) {
            return Double.NEGATIVE_INFINITY;
        }
        return d > 0 ? (at - face) / d : Double.POSITIVE_INFINITY;
    }

    @Test
    void wallsOnTheNegativeSidesAreJudgedAlike() {
        // the corner room turned half about the y axis: walls whose faces are at x = −2 and z = −2
        Room room = new Room(true);
        Ground turned = (x, y, z, halfWidth, height, dirX, dirZ, distance) ->
                room.clear(-x, y, -z, halfWidth, height, -dirX, -dirZ, distance);
        for (int along : new int[] {1, -1}) {
            for (double deg : new double[] {90, 46, 44, 17}) {
                double r = Math.toRadians(deg);
                long expected = deg > 45 ? Micros.ofTicks(10) : NONE;
                assertEquals(
                        expected,
                        firstTick(turned, -1.7, 0, -Math.sin(r), along * Math.cos(r)),
                        "the wall at x = -2, " + deg + " degrees into it, sliding " + along);
                assertEquals(
                        expected,
                        firstTick(turned, 0, -1.7, along * Math.cos(r), -Math.sin(r)),
                        "the wall at z = -2, " + deg + " degrees into it, sliding " + along);
            }
        }
    }

    /** Where a push along {@code (dirX, dirZ)} is met by a body at {@code (x, z)} on its first tick, in {@code g}. */
    private static long firstTick(Ground g, double x, double z, double dirX, double dirZ) {
        ContactCheck c = new ContactCheck();
        PoseRing at = new PoseRing(8);
        c.push(new Effect.Push(dirX, dirZ, 2.0, 4, 0, 10, 20, SHOWN), 10, 8, 2);
        at.put(Micros.ofTicks(10), x, 64, z, 270, 0);
        return c.wall(at, g, HALF, HEIGHT, KEEP);
    }

    @Test
    void aLaunchsFloorCountsOnlyOnceItHasLeftTheFloor() {
        live.effect(new Effect.Knockdown(), 8);
        check.push(new Effect.Push(1, 0, 0, 4, 0.42, 10, Effect.Push.NO_SPLAT, SHOWN), 10, 8, 2);
        at(8, 0, 64);
        at(9, 0, 64);
        at(10, 0, 64); // the push's first tick, its client still on the floor
        assertEquals(NONE, floor());
        at(11, 0, 64.4);
        at(12, 0, 64.8);
        assertEquals(NONE, floor());
        assertEquals(NONE, wall()); // a launch with no distance has no wall
        at(13, 0, 64.3);
        at(14, 0, 64.0);
        assertEquals(Micros.ofTicks(14), floor());
        at(15, 0, 64.0);
        assertEquals(NONE, floor()); // once a knockdown
    }

    @Test
    void aClientThatNeverLeavesTheFloorLandsNothing() {
        live.effect(new Effect.Knockdown(), 8);
        check.push(new Effect.Push(1, 0, 0, 4, 0.42, 10, Effect.Push.NO_SPLAT, SHOWN), 10, 8, 2);
        for (long t = 8; t < 48; t++) {
            at(t, 0, 64);
            assertEquals(NONE, floor()); // the machine's backstop lands it
        }
    }

    @Test
    void aThrowsFloorCountsFromTheTickAfterIt_lookingBackAtPosesAlreadyFiled() {
        at(8, 0, 64);
        at(9, 0, 64);
        at(10, 0, 64);
        at(11, 0, 64);
        live.effect(new Effect.Knockdown(), 9); // decided late: tick 10 is the first that counts
        assertEquals(Micros.ofTicks(10), floor());
        assertEquals(NONE, floor());

        live.effect(new Effect.Knockdown(), 20); // a new knockdown looks again
        at(20, 0, 64);
        assertEquals(NONE, floor());
        at(21, 0, 64);
        assertEquals(Micros.ofTicks(21), floor());
    }

    @Test
    void outsideEveryWindowItAsksTheLevelNothing() {
        for (long t = 1; t <= 30; t++) {
            at(t, 1.7, 64);
            wall();
            floor();
        }
        assertEquals(0, room.asked);

        poses.clear();
        check.push(ALONG_X, 10, 30, 2); // from tick 10, but every pose is past its window
        for (long t = 31; t <= 40; t++) {
            at(t, 1.7, 64);
            assertEquals(NONE, wall());
        }
        assertEquals(0, room.asked); // the window closed at the first pose past it, asking nothing
    }

    @Test
    void anEpochForgetsThePushAndTheKnockdown() {
        check.push(ALONG_X, 10, 8, 2);
        live.effect(new Effect.Knockdown(), 8);
        check.clear();
        poses.clear();
        at(12, 1.7, 64);
        assertEquals(NONE, wall());
        assertEquals(Micros.ofTicks(12), floor()); // a knockdown seen afresh: from its tick + 1
    }
}
