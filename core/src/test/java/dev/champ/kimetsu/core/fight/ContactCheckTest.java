package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * x = 1.7. A push runs along +x from tick 10, 2 blocks over 4 ticks, splatting until tick 20; the grace is 2 ticks.
 */
class ContactCheckTest {
    private static final double HALF = 0.3;
    private static final double HEIGHT = 1.8;
    private static final long NONE = ContactCheck.NONE;

    /** The room, counting what it's asked. */
    private static final class Room implements Ground {
        int asked;

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
            if (dirX <= 0) {
                return distance;
            }
            return Math.max(0, Math.min(distance, (2.0 - (x + halfWidth)) / dirX));
        }

        @Override
        public double below(double x, double y, double z, double halfWidth, double height, double distance) {
            asked++;
            return Math.max(0, Math.min(distance, y - 64));
        }
    }

    private final Room room = new Room();
    private final ContactCheck check = new ContactCheck();
    private final PoseRing poses = new PoseRing(32);
    private final Combatant live = Arena.combatant(new PulseSchedule(13), 0);

    private static final Effect.Push ALONG_X = new Effect.Push(1, 0, 2.0, 4, 0, 10, 20);

    private void at(long tick, double x, double y) {
        poses.put(Micros.ofTicks(tick), x, y, 0, 270, 0);
    }

    private long wall() {
        return check.wall(poses, room, HALF, HEIGHT);
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
        away.push(new Effect.Push(-1, 0, 2.0, 4, 0, 10, 20), 10, 8, 2);
        flush.put(Micros.ofTicks(10), 1.7, 64, 0, 270, 0);
        assertEquals(NONE, away.wall(flush, room, HALF, HEIGHT));
    }

    @Test
    void aPushThatDoesntSplatLooksForItsWallUntilItEndsPlusTheGrace() {
        check.push(new Effect.Push(1, 0, 1.5, 4, 0, 10, Effect.Push.NO_SPLAT), 10, 8, 2);
        at(16, 1.7, 64); // 10 + 4 + 2
        assertEquals(Micros.ofTicks(16), wall());
        assertEquals(false, check.splats());

        ContactCheck late = new ContactCheck();
        PoseRing past = new PoseRing(32);
        late.push(new Effect.Push(1, 0, 1.5, 4, 0, 10, Effect.Push.NO_SPLAT), 10, 8, 2);
        past.put(Micros.ofTicks(17), 1.7, 64, 0, 270, 0);
        assertEquals(NONE, late.wall(past, room, HALF, HEIGHT));
    }

    @Test
    void aLaunchsFloorCountsOnlyOnceItHasLeftTheFloor() {
        live.effect(new Effect.Knockdown(), 8);
        check.push(new Effect.Push(1, 0, 0, 4, 0.42, 10, Effect.Push.NO_SPLAT), 10, 8, 2);
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
        check.push(new Effect.Push(1, 0, 0, 4, 0.42, 10, Effect.Push.NO_SPLAT), 10, 8, 2);
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
