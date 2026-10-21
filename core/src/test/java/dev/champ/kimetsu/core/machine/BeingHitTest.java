package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.AttackClass;
import org.junit.jupiter.api.Test;

/**
 * Rules 3.8, 3.9, 7.5 and 7.6, Posture's break (Rule 4.2) and the states the demon and clash slices put a combatant in:
 * a hit stuns for its hitstun, or the rest of a recovery if that's longer; armour absorbs its hit class; a hit never
 * shortens a stun, a stagger or a knockdown; any hit that reaches the combatant empties the buffer and costs 4 Rhythm.
 */
class BeingHitTest {

    private static Rig seeded() {
        return new Rig().effect(new Effect.RhythmDelta(8), 1);
    }

    @Test
    void aHitStunsForItsHitstunAndCostsFourRhythm() {
        Rig r = seeded().effect(new Effect.Hit(AttackClass.LIGHT, 10), 20).through(29);
        assertEquals(State.HITSTUN, r.c.state());
        assertEquals(4, r.c.rhythm().points());
        r.through(30);
        assertEquals(State.NEUTRAL, r.c.state());
    }

    @Test
    void aHitInHitstunExtendsItButNeverShortensIt() {
        Rig shorter = seeded().effect(new Effect.Hit(AttackClass.LIGHT, 10), 20);
        shorter.effect(new Effect.Hit(AttackClass.LIGHT, 3), 22).through(22);
        assertEquals(30, shorter.c.stateUntil());

        Rig longer = seeded().effect(new Effect.Hit(AttackClass.LIGHT, 10), 20);
        longer.effect(new Effect.Hit(AttackClass.LIGHT, 15), 22).through(22);
        assertEquals(37, longer.c.stateUntil());
    }

    @Test
    void inARecoveryTheStunIsTheLongerOfTheRecoveryLeftAndTheHitstun() {
        Rig shortHit = seeded().press(Input.Kind.ATTACK, Rig.beat(1)); // Chain 1: recovery ends at 24
        shortHit.effect(new Effect.Hit(AttackClass.LIGHT, 3), 19).through(19);
        assertEquals(State.HITSTUN, shortHit.c.state());
        assertEquals(24, shortHit.c.stateUntil());

        Rig longHit = seeded().press(Input.Kind.ATTACK, Rig.beat(1));
        longHit.effect(new Effect.Hit(AttackClass.LIGHT, 10), 19).through(19);
        assertEquals(29, longHit.c.stateUntil());
        assertEquals(0, longHit.c.chainNext());
    }

    @Test
    void aFormsActiveFramesShrugOffLightHitsButNotHeavyOnes() {
        Rig light = seeded();
        light.form(0, Rig.ms(1200), 200); // active 30–32
        light.effect(new Effect.Hit(AttackClass.LIGHT, 10), 31).through(31);
        assertEquals(State.ATTACK, light.c.state());
        assertEquals(4, light.c.rhythm().points()); // the hit still landed

        Rig heavy = seeded();
        heavy.form(0, Rig.ms(1200), 200);
        heavy.effect(new Effect.Hit(AttackClass.HEAVY, 10), 31).through(31);
        assertEquals(State.HITSTUN, heavy.c.state());
        assertEquals(41, heavy.c.stateUntil());
        assertEquals(0, heavy.c.breath().grey()); // the recovery never came
    }

    @Test
    void aHitInTheWindupInterruptsIt() {
        Rig r = seeded().press(Input.Kind.ATTACK, Rig.beat(1));
        r.effect(new Effect.Hit(AttackClass.LIGHT, 8), 14).through(14);
        assertEquals(State.HITSTUN, r.c.state());
        assertNull(r.c.attack());
        assertEquals(22, r.c.stateUntil());
    }

    @Test
    void anyHitThatReachesTheCombatantEmptiesTheBuffer() {
        Rig hit = seeded().press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.STEP, Rig.during(15, 10));
        hit.effect(new Effect.Hit(AttackClass.LIGHT, 2), 16).through(20);
        assertEquals(State.NEUTRAL, hit.c.state()); // HITSTUN to 18, and the Step was dropped

        Rig blocked = seeded().press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.STEP, Rig.during(15, 10));
        blocked.effect(new Effect.Blocked(), 16).through(18);
        assertEquals(State.ATTACK, blocked.c.state()); // without the block it would have Stepped at 18
    }

    @Test
    void postureAtZeroStaggersForThirtyTicksAndResetsToHalf() {
        Rig r = seeded().effect(new Effect.PostureDamage(Rig.points(100), true), 20)
                .through(49);
        assertEquals(State.STAGGER, r.c.state());
        r.through(50);
        assertEquals(State.NEUTRAL, r.c.state());
        assertEquals(Rig.points(50), r.posture());
    }

    @Test
    void aHitInAStaggerStunsAfterItIfItsHitstunOutlastsIt() {
        Rig longer = seeded().effect(new Effect.PostureDamage(Rig.points(100), true), 20);
        longer.effect(new Effect.Hit(AttackClass.LIGHT, 40), 30).through(50);
        assertEquals(State.HITSTUN, longer.c.state());
        assertEquals(70, longer.c.stateUntil());

        Rig shorter = seeded().effect(new Effect.PostureDamage(Rig.points(100), true), 20);
        shorter.effect(new Effect.Hit(AttackClass.LIGHT, 10), 30).through(50);
        assertEquals(State.NEUTRAL, shorter.c.state());
    }

    @Test
    void aKnockdownOrAGrabDuringAStaggerTakesOverAndTheStaggerResumesIfItsStillRunning() {
        Rig tech = seeded().effect(new Effect.PostureDamage(Rig.points(100), true), 20); // STAGGER to 50
        tech.effect(new Effect.Knockdown(), 25).press(Input.Kind.JUMP, Rig.ms(1450));
        tech.effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(1500)), 30).through(29);
        assertEquals(State.KNOCKDOWN, tech.c.state());
        tech.through(30);
        assertTrue(tech.c.teched());
        assertEquals(State.STAGGER, tech.c.state());
        tech.through(50);
        assertEquals(State.NEUTRAL, tech.c.state());

        Rig down = seeded().effect(new Effect.PostureDamage(Rig.points(100), true), 20);
        down.effect(new Effect.Knockdown(), 25).effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(1500)), 30);
        down.through(57);
        assertEquals(State.KNOCKDOWN, down.c.state());
        down.through(58);
        assertEquals(State.NEUTRAL, down.c.state()); // the stagger ran out while it was down

        Rig grab = seeded().effect(new Effect.PostureDamage(Rig.points(100), true), 20);
        grab.effect(new Effect.Grabbed(15), 25).through(25);
        assertEquals(State.GRABBED, grab.c.state());
        grab.effect(new Effect.Released(), 35).through(35);
        assertEquals(State.STAGGER, grab.c.state());
        long at = grab.breath();
        grab.through(36);
        assertEquals(at + Rig.points(0.5), grab.breath()); // the drain ended with the grab
    }

    @Test
    void aPostureBreakDuringAKnockdownWaitsForItToEnd() {
        Rig early = seeded().effect(new Effect.Knockdown(), 10);
        early.effect(new Effect.PostureDamage(Rig.points(100), true), 12);
        early.effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(1500)), 30).through(30);
        assertEquals(State.KNOCKDOWN, early.c.state());
        assertTrue(!early.c.airborne());
        early.through(58);
        assertEquals(State.NEUTRAL, early.c.state()); // broken at 12, over at 42

        Rig late = seeded().effect(new Effect.Knockdown(), 10);
        late.effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(1500)), 30);
        late.effect(new Effect.PostureDamage(Rig.points(100), true), 40).through(58);
        assertEquals(State.STAGGER, late.c.state()); // broken at 40, running to 70
    }

    @Test
    void aPostureBreakInHitstunStaggersAndTheStunRunsOnAfter() {
        Rig r = seeded().effect(new Effect.Hit(AttackClass.LIGHT, 60), 20);
        r.effect(new Effect.PostureDamage(Rig.points(100), true), 25).through(54);
        assertEquals(State.STAGGER, r.c.state());
        r.through(55);
        assertEquals(State.HITSTUN, r.c.state());
        assertEquals(80, r.c.stateUntil());
    }

    @Test
    void aGuardHeldWhileWindedDrainsPostureUntilItBreaks() {
        Rig r = seeded().effect(new Effect.PostureDamage(Rig.points(95), true), 1);
        r.c.breath().spend(0, Rig.points(100)); // Winded to 40
        r.press(Input.guard(Rig.during(2, 10))).through(20);
        assertEquals(State.GUARD, r.c.state()); // 5 Posture at 0.3 a tick from tick 4
        r.through(21);
        assertEquals(State.STAGGER, r.c.state());
    }

    @Test
    void aCollapseRunsItsTicksAndNothingButDeathMovesIt() {
        Rig r = seeded().effect(new Effect.Collapse(100), 20);
        r.effect(new Effect.Hit(AttackClass.HEAVY, 40), 30);
        r.effect(new Effect.Knockdown(), 31);
        r.effect(new Effect.Grabbed(15), 32);
        r.effect(new Effect.PostureDamage(Rig.points(100), true), 33);
        r.through(119);
        assertEquals(State.COLLAPSED, r.c.state());
        r.through(120);
        assertEquals(State.NEUTRAL, r.c.state());
    }

    @Test
    void deadIgnoresEverything() {
        Rig r = seeded().effect(new Effect.Die(), 20);
        r.effect(new Effect.Hit(AttackClass.HEAVY, 40), 30).effect(new Effect.Knockdown(), 31);
        r.press(Input.Kind.ATTACK, Rig.during(31, 10)).press(Input.Kind.STEP, Rig.during(32, 10));
        r.through(40);
        assertEquals(State.DEAD, r.c.state());
        assertEquals(8, r.c.rhythm().points());
    }

    @Test
    void inAClashTheFirstAttackPressIsKeptForTheJudge() {
        Rig r = seeded().effect(new Effect.ClashStart(), 20);
        long first = Rig.during(20, 10);
        r.press(Input.Kind.ATTACK, first).press(Input.Kind.ATTACK, Rig.during(21, 10));
        r.effect(new Effect.ClashEnd(), 25).through(25);
        assertEquals(first, r.c.clashPress());
        assertEquals(State.NEUTRAL, r.c.state());
        assertNull(r.c.attack()); // the presses were the clash's, not attacks
    }
}
