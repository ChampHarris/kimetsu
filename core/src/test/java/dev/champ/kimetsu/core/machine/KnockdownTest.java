package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.AttackClass;
import org.junit.jupiter.api.Test;

/**
 * Rule 9.2 (R9-05). Launched at tick 10, the body meets the floor at 2000 ms (tick 40). A Jump from 4 ticks before the
 * contact to 1 tick after it Techs; any other Jump locks Tech out for 13 ticks. Without a Tech: 20 ticks down, 8 to rise.
 */
class KnockdownTest {
    private static final long CONTACT = Rig.ms(2000);

    private static Rig launched() {
        return new Rig().effect(new Effect.Knockdown(), 10);
    }

    private static Rig landing(long... jumps) {
        Rig r = launched();
        for (long j : jumps) {
            r.press(Input.Kind.JUMP, j);
        }
        return r.effect(new Effect.Contact(Effect.Surface.FLOOR, CONTACT), 40);
    }

    @Test
    void aJumpFromFourTicksBeforeTheContactTechs_justEarlierDoesNot() {
        Rig early = landing(CONTACT - Rig.ms(200)).through(40);
        assertEquals(State.NEUTRAL, early.c.state());
        assertTrue(early.c.teched());

        Rig tooEarly = landing(CONTACT - Rig.ms(200) - 1).through(40);
        assertEquals(State.KNOCKDOWN, tooEarly.c.state());
        assertFalse(tooEarly.c.teched());
    }

    @Test
    void aJumpUpToOneTickAfterTheContactTechs_justLaterDoesNot() {
        Rig late = landing(CONTACT + Rig.ms(50)).through(41);
        assertEquals(State.NEUTRAL, late.c.state());

        Rig tooLate = landing(CONTACT + Rig.ms(50) + 1).through(41);
        assertEquals(State.KNOCKDOWN, tooLate.c.state());
    }

    @Test
    void withoutATechItsTwentyTicksDownAndEightToRise() {
        Rig r = landing().through(67);
        assertEquals(State.KNOCKDOWN, r.c.state());
        assertFalse(r.c.airborne());
        r.through(68);
        assertEquals(State.NEUTRAL, r.c.state());
    }

    @Test
    void mashingFails_aMissedJumpLocksTechOutForThirteenTicks() {
        Rig locked = landing(CONTACT - Rig.ms(650), CONTACT - Rig.ms(150)).through(40);
        assertEquals(State.KNOCKDOWN, locked.c.state()); // the first ran out at 13 ticks after it: 2000 ms

        Rig clear = landing(CONTACT - Rig.ms(850), CONTACT - Rig.ms(150)).through(40);
        assertEquals(State.NEUTRAL, clear.c.state()); // its lockout ended at 1800 ms

        long[] mash = new long[14];
        for (int i = 0; i < mash.length; i++) {
            mash[i] = Rig.ms(1500 + 30 * i); // 1500 to 1890 ms, the last inside the window
        }
        Rig masher = landing(mash).through(40);
        assertEquals(State.KNOCKDOWN, masher.c.state());
    }

    @Test
    void techIsOfferedAtEachContact_aWallFirstThenTheFloor() {
        Rig wallTech = launched().press(Input.Kind.JUMP, Rig.ms(1450));
        wallTech.effect(new Effect.Contact(Effect.Surface.WALL, Rig.ms(1500)), 30)
                .through(30);
        assertEquals(State.NEUTRAL, wallTech.c.state());

        Rig floorTech = launched();
        floorTech
                .effect(new Effect.Contact(Effect.Surface.WALL, Rig.ms(1500)), 30)
                .through(30);
        assertEquals(State.KNOCKDOWN, floorTech.c.state());
        assertTrue(floorTech.c.airborne()); // the splat without a Tech: the floor comes next
        floorTech.press(Input.Kind.JUMP, CONTACT - Rig.ms(100));
        floorTech.effect(new Effect.Contact(Effect.Surface.FLOOR, CONTACT), 40).through(40);
        assertEquals(State.NEUTRAL, floorTech.c.state());
    }

    @Test
    void aContactFromBeforeTheLaunchIsIgnored() {
        Rig r = launched()
                .effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(400)), 12)
                .through(49);
        assertEquals(State.KNOCKDOWN, r.c.state());
        assertTrue(r.c.airborne());
    }

    @Test
    void withNoLandingReported_itMeetsTheFloorAtTheAirTimeLimit_andALateReportChangesNothing() {
        // knocked down at 10, the limit 40 ticks: down at 50, up 28 ticks later (D-049)
        Rig r = launched().through(49);
        assertTrue(r.c.airborne());
        r.through(50);
        assertFalse(r.c.airborne());
        assertEquals(State.KNOCKDOWN, r.c.state());
        r.effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(2_900)), 59).through(77);
        assertEquals(State.KNOCKDOWN, r.c.state()); // the late report didn't restart its ticks down
        r.through(78);
        assertEquals(State.NEUTRAL, r.c.state());

        Rig teched = launched().press(Input.Kind.JUMP, Rig.ms(2_400)).through(50); // 2 ticks before the limit's contact
        assertEquals(State.NEUTRAL, teched.c.state());
    }

    @Test
    void sneakInMidAirCutsTheJuggleOnceForTenBreath() {
        Rig r = launched().press(Input.Kind.SNEAK, Rig.during(15, 10)).through(16);
        assertTrue(r.c.juggleCut());
        assertEquals(Rig.points(90), r.breath());
        r.press(Input.Kind.SNEAK, Rig.during(16, 10)).through(17);
        assertEquals(Rig.points(90.5), r.breath());

        Rig poor = launched();
        poor.c.breath().spend(0, Rig.points(99)); // 1 + 8 regenerated by tick 16
        poor.press(Input.Kind.SNEAK, Rig.during(15, 10)).through(16);
        assertFalse(poor.c.juggleCut());

        Rig grounded = landing().press(Input.Kind.SNEAK, Rig.during(41, 10)).through(42);
        assertFalse(grounded.c.juggleCut());
    }

    @Test
    void aHitWhileDownStunsAfterTheRiseIfItOutlastsIt() {
        Rig r = landing().effect(new Effect.Hit(AttackClass.LIGHT, 60), 45).through(68);
        assertEquals(State.HITSTUN, r.c.state());
        assertEquals(105, r.c.stateUntil());
    }
}
