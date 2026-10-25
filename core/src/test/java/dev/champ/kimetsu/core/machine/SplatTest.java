package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.AttackClass;
import org.junit.jupiter.api.Test;

/**
 * Rule 9.4's wall splat with Rule 9.2's Tech as written (D-078). The heavy lands at tick 5 with 20 ticks of hitstun (to
 * 25) and pushes from tick 7, splatting on a wall met before tick 17; the wall comes at 700 ms (tick 14). A Jump from 4
 * ticks before the contact to 1 after Techs it, the stored ones judged with their lockout; without one, it's a KNOCKDOWN
 * in the air, and the floor comes next.
 */
class SplatTest {
    private static final long WALL = Rig.ms(700);

    private static Rig pushed() {
        return new Rig()
                .effect(new Effect.Hit(AttackClass.HEAVY, 20), 5)
                .effect(new Effect.Push(0, 1, 2.0, 4, 0, 7, 17), 5);
    }

    private static Rig atTheWall(long... jumps) {
        Rig r = pushed();
        for (long j : jumps) {
            r.press(Input.Kind.JUMP, j);
        }
        return r.effect(new Effect.Contact(Effect.Surface.WALL, WALL), 14);
    }

    @Test
    void aWallInTheWindowWithNoJumpSplats() {
        Rig r = atTheWall().through(14);
        assertEquals(State.KNOCKDOWN, r.c.state());
        assertTrue(r.c.airborne()); // the floor comes next
        assertEquals(14, r.c.knockdownTick());
        assertEquals(WALL, r.c.wallContactMicros());
        assertFalse(r.c.wallTeched());
        assertEquals(0, r.c.wallJumps());
        assertEquals(Long.MIN_VALUE, r.c.wallTechOffsetMicros());
        assertEquals(Long.MIN_VALUE, r.c.splatUntil()); // one splat per push
    }

    @Test
    void aJumpThreeTicksBeforeTechs_andTheHitstunRunsOn() {
        Rig r = atTheWall(WALL - Rig.ms(150)).through(14);
        assertEquals(State.HITSTUN, r.c.state());
        assertEquals(25, r.c.stateUntil());
        assertTrue(r.c.wallTeched());
        assertEquals(-Rig.ms(150), r.c.wallTechOffsetMicros());
        assertEquals(1, r.c.wallJumps());
        r.through(25);
        assertEquals(State.NEUTRAL, r.c.state());

        Rig edge = atTheWall(WALL - Rig.ms(200)).through(14); // exactly 4 ticks before
        assertTrue(edge.c.wallTeched());
        Rig tooEarly = atTheWall(WALL - Rig.ms(200) - 1).through(14);
        assertEquals(State.KNOCKDOWN, tooEarly.c.state());
    }

    @Test
    void aJumpUpToOneTickAfterTechs_justLaterDoesNot() {
        Rig late = atTheWall().press(Input.Kind.JUMP, WALL + Rig.ms(50)).through(15);
        assertEquals(State.HITSTUN, late.c.state()); // the knockdown Teched, the hit's stun still running
        assertEquals(25, late.c.stateUntil());
        assertTrue(late.c.wallTeched());
        assertEquals(Rig.ms(50), late.c.wallTechOffsetMicros());
        assertEquals(1, late.c.wallJumps());

        Rig tooLate = atTheWall().press(Input.Kind.JUMP, WALL + Rig.ms(50) + 1).through(16); // acts in step 16
        assertEquals(State.KNOCKDOWN, tooLate.c.state());
        assertFalse(tooLate.c.wallTeched());
        assertEquals(0, tooLate.c.wallJumps());
    }

    @Test
    void aJumpSixTicksBeforeLocksTechOut_soOneInTheWindowFails() {
        Rig r = atTheWall(WALL - Rig.ms(300), WALL - Rig.ms(50)).press(Input.Kind.JUMP, WALL + Rig.ms(40));
        r.through(15);
        assertEquals(State.KNOCKDOWN, r.c.state()); // locked out until 1050 ms by the first
        assertFalse(r.c.wallTeched());
        assertEquals(2, r.c.wallJumps()); // the one before the contact and the one after, both in the window
    }

    @Test
    void mashingTechsWithTheFirstJumpInTheWindow_andCountsEveryOne() {
        Rig r = atTheWall(Rig.ms(560), Rig.ms(600), Rig.ms(650)).through(14);
        assertTrue(r.c.wallTeched());
        assertEquals(Rig.ms(560) - WALL, r.c.wallTechOffsetMicros());
        assertEquals(3, r.c.wallJumps());
    }

    @Test
    void aJumpAfterATechedWallStillCountsAsMashing() {
        Rig r = atTheWall(WALL - Rig.ms(150))
                .press(Input.Kind.JUMP, WALL + Rig.ms(50))
                .through(15);
        assertTrue(r.c.wallTeched()); // by the first: the hitstun runs on
        assertEquals(State.HITSTUN, r.c.state());
        assertEquals(-Rig.ms(150), r.c.wallTechOffsetMicros());
        assertEquals(2, r.c.wallJumps()); // the one after the contact is in its window too (D-078)

        Rig late = atTheWall(WALL - Rig.ms(150))
                .press(Input.Kind.JUMP, WALL + Rig.ms(50) + 1)
                .through(16);
        assertEquals(1, late.c.wallJumps()); // past the window
    }

    @Test
    void aPlayerDownOnTheFloorIsntSplattedAgain_oneStillInTheAirIs() {
        Rig down = new Rig()
                .effect(new Effect.Knockdown(), 2)
                .effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(150)), 3) // down at 3, up at 31
                .effect(new Effect.Hit(AttackClass.HEAVY, 20), 5)
                .effect(new Effect.Push(0, 1, 2.0, 4, 0, 7, 17), 5)
                .effect(new Effect.Contact(Effect.Surface.WALL, WALL), 14)
                .through(14);
        assertEquals(State.KNOCKDOWN, down.c.state());
        assertEquals(31, down.c.stateUntil()); // its ticks down run on, not restarted at the wall
        assertEquals(Long.MIN_VALUE, down.c.wallContactMicros()); // no splat, so no 2.0

        Rig air = new Rig()
                .effect(new Effect.Hit(AttackClass.HEAVY, 0), 5)
                .effect(new Effect.Push(0, 1, 2.0, 4, 0.42, 7, 17), 5) // a splatting launch: none in M1's data
                .effect(new Effect.Knockdown(), 5)
                .effect(new Effect.Contact(Effect.Surface.WALL, WALL), 14)
                .through(14);
        assertEquals(WALL, air.c.wallContactMicros());
        assertEquals(14, air.c.knockdownTick()); // knocked down again from the wall, still in the air
        assertTrue(air.c.airborne());
    }

    @Test
    void outsideTheWindowAWallDoesNothing() {
        Rig late = pushed().effect(new Effect.Contact(Effect.Surface.WALL, Rig.ms(850)), 17)
                .through(17);
        assertEquals(State.HITSTUN, late.c.state()); // tick 17 is past the window
        assertEquals(Long.MIN_VALUE, late.c.wallContactMicros());

        Rig early = pushed().effect(new Effect.Contact(Effect.Surface.WALL, Rig.ms(340)), 7)
                .through(7);
        assertEquals(State.HITSTUN, early.c.state()); // before the push could move it

        Rig floor = pushed().effect(new Effect.Contact(Effect.Surface.FLOOR, WALL), 14)
                .through(14);
        assertEquals(State.HITSTUN, floor.c.state());

        Rig noSplat = new Rig()
                .effect(new Effect.Hit(AttackClass.HEAVY, 20), 5)
                .effect(new Effect.Push(0, 1, 1.5, 4, 0, 7, Effect.Push.NO_SPLAT), 5)
                .effect(new Effect.Contact(Effect.Surface.WALL, WALL), 14)
                .through(14);
        assertEquals(State.HITSTUN, noSplat.c.state()); // a push that doesn't splat (a clash's tie)
    }

    @Test
    void aJumpBeforeThePushIsntKeptForIt() {
        Rig r = atTheWall(Rig.ms(240)).through(14); // pressed in tick 4, before the hit
        assertEquals(State.KNOCKDOWN, r.c.state());
        assertEquals(0, r.c.wallJumps());
    }

    @Test
    void afterASplatTheFloorIsTwentyTicksDownAndEightToRise() {
        Rig r = atTheWall().effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(1000)), 20);
        r.through(47);
        assertEquals(State.KNOCKDOWN, r.c.state());
        assertFalse(r.c.airborne());
        r.through(48);
        assertEquals(State.NEUTRAL, r.c.state()); // the hit's stun ended at 25
    }

    @Test
    void aTechAtTheFloorAfterASplatEndsTheKnockdown_theSplatStands() {
        Rig r = atTheWall().press(Input.Kind.JUMP, Rig.ms(900));
        r.effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(1000)), 20).through(20);
        assertEquals(State.HITSTUN, r.c.state()); // Teched at the floor: the hit's stun runs to 25
        assertTrue(r.c.teched());
        assertFalse(r.c.wallTeched()); // the wall's splat landed
        assertEquals(WALL, r.c.wallContactMicros());
    }

    @Test
    void aGrabEndsThePush_soItsWallSplatsNothing() {
        Rig r = pushed().effect(new Effect.Grabbed(15), 10).effect(new Effect.Released(), 12);
        r.effect(new Effect.Contact(Effect.Surface.WALL, WALL), 14).through(14);
        assertEquals(Long.MIN_VALUE, r.c.wallContactMicros());
        assertFalse(r.c.state() == State.KNOCKDOWN);
    }
}
