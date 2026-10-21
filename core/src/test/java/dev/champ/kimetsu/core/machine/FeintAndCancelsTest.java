package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.Phase;
import org.junit.jupiter.api.Test;

/**
 * Rules 3.3, 3.4 and 3.6: the Feint (with ruling Q8), Water's guard-cancel and the Step-cancel. Breath regenerates 0.5
 * a tick out of combat, which the expected values below include.
 */
class FeintAndCancelsTest {

    /** Fires the 1st form with its release acting in step {@code s}: windup s to s+5, the Feint open s to s+2. */
    private static void formAt(Rig r, long s) {
        r.form(0, Rig.during(s - 1, 10), 200);
    }

    @Test
    void aGuardPressInTheFirstHalfOfTheWindupFeintsForTenBreathAndTheFormIsRefunded() {
        Rig r = new Rig();
        r.c.breath().spend(0, Rig.points(50));
        formAt(r, 24);
        r.press(Input.guard(Rig.during(24, 10))).through(24);
        assertEquals(Rig.points(42), r.breath()); // 50 + 12 regenerated − 20
        r.through(25);
        assertEquals(State.NEUTRAL, r.c.state());
        assertEquals(Rig.points(42.5 + 20 - 10), r.breath());
        assertEquals(0, r.c.breath().grey());
    }

    @Test
    void theFeintIsOpenOnWindupFramesZeroToTwo() {
        Rig two = new Rig();
        formAt(two, 24);
        two.press(Input.guard(Rig.during(26, 10))).through(27);
        assertEquals(State.NEUTRAL, two.c.state());

        Rig three = new Rig();
        formAt(three, 24);
        three.press(Input.guard(Rig.during(27, 10))).through(28);
        assertEquals(State.ATTACK, three.c.state());
        assertEquals(Phase.WINDUP, three.c.phase(28));
        three.through(33); // the held guard cancels the recovery's first tick instead
        assertEquals(State.GUARD, three.c.state());
    }

    @Test
    void eachFeintWithinThreeSecondsOfTheLastCostsDouble() {
        Rig r = new Rig();
        r.c.breath().spend(0, Rig.points(40)); // below the cap, so every refund shows whole
        long[] at = {24, 34, 44};
        double[] cost = {10, 20, 40};
        for (int i = 0; i < at.length; i++) {
            formAt(r, at[i]);
            r.press(Input.guard(Rig.during(at[i], 10)));
            r.press(Input.Kind.GUARD_UP, Rig.during(at[i] + 1, 10));
            r.through(at[i]);
            long before = r.breath();
            r.through(at[i] + 1);
            assertEquals(before + Rig.points(0.5 + 20 - cost[i]), r.breath(), "feint " + (i + 1));
        }
        formAt(r, 106); // 61 ticks after the last: back to 10
        r.press(Input.guard(Rig.during(106, 10))).through(106);
        long before = r.breath();
        r.through(107);
        assertEquals(before + Rig.points(0.5 + 20 - 10), r.breath());
    }

    /** A white strike landing at tick 27 (1350 ms), committed at tick 18: a press at 1210 ms can deflect it. */
    private static Rig struckAt27() {
        Rig r = new Rig();
        r.c.breath().spend(0, Rig.points(40));
        r.effect(new Effect.StrikeCommitted(1, Rig.ms(1350), true), 18);
        return r;
    }

    @Test
    void aFeintWhosePressDeflectedIsFree_oneWhoseStrikeWentUnansweredPaysAfterAll() {
        Rig free = struckAt27();
        formAt(free, 24);
        long press = Rig.during(24, 10);
        free.press(Input.guard(press))
                .press(Input.Kind.GUARD_UP, Rig.during(25, 10))
                .through(24);
        long before = free.breath();
        free.through(25);
        assertEquals(press, free.c.deflects().claimOf(1)); // the press that feinted also claimed the strike
        assertEquals(before + Rig.points(0.5 + 20), free.breath()); // refunded, the Feint waits
        assertEquals(10, free.c.pendingFeint());
        free.effect(new Effect.StrikeDecided(1, true), 30).through(30);
        assertEquals(0, free.c.pendingFeint());
        assertEquals(before + Rig.points(0.5 + 20 + 2.5), free.breath());

        Rig paid = struckAt27();
        paid.effect(new Effect.StrikeCommitted(2, Rig.ms(2000), true), 18); // outside the press's window
        formAt(paid, 24);
        paid.press(Input.guard(press))
                .press(Input.Kind.GUARD_UP, Rig.during(25, 10))
                .through(24);
        long start = paid.breath();
        paid.effect(new Effect.StrikeDecided(2, true), 29); // a strike the press didn't claim: nothing to settle
        paid.effect(new Effect.StrikeDecided(1, false), 30).through(30);
        assertEquals(start + Rig.points(0.5 + 20 + 2.5 - 10), paid.breath());
        assertEquals(0, paid.c.pendingFeint());

        // its doubling counts from the Feint (tick 25), not the verdict (30): 61 ticks on, it's back to 10
        formAt(paid, 85);
        paid.press(Input.guard(Rig.during(85, 10))).through(85);
        long mid = paid.breath();
        paid.through(86);
        assertEquals(mid + Rig.points(0.5 + 20 - 10), paid.breath());
    }

    @Test
    void aFeintWhoseStrikeIsCancelledPays_oneThatClaimedTwoIsFreeIfEitherWasAnswered() {
        Rig cancelled = struckAt27();
        formAt(cancelled, 24);
        cancelled.press(Input.guard(Rig.during(24, 10))).press(Input.Kind.GUARD_UP, Rig.during(25, 10));
        cancelled.through(25);
        long start = cancelled.breath();
        cancelled.effect(new Effect.StrikeCancelled(1), 26).through(26);
        assertEquals(start + Rig.points(0.5 - 10), cancelled.breath()); // nothing answered it
        assertEquals(0, cancelled.c.pendingFeint());

        Rig two = struckAt27();
        two.effect(new Effect.StrikeCommitted(2, Rig.ms(1350), true), 18); // a second demon's strike, the same beat
        formAt(two, 24);
        two.press(Input.guard(Rig.during(24, 10)))
                .press(Input.Kind.GUARD_UP, Rig.during(25, 10))
                .through(25);
        long mid = two.breath();
        two.effect(new Effect.StrikeDecided(1, false), 27); // out of reach: not answered
        two.through(27);
        assertEquals(10, two.c.pendingFeint()); // the other strike it claimed is still to be decided
        two.effect(new Effect.StrikeDecided(2, true), 28).through(28);
        assertEquals(0, two.c.pendingFeint());
        assertEquals(mid + Rig.points(1.5), two.breath()); // free: one strike it claimed was deflected
    }

    @Test
    void aClaimedFeintWhoseStrikeIsNeverDecidedPaysOnceTheClaimRunsOut() {
        Rig r = struckAt27();
        formAt(r, 24);
        long press = Rig.during(24, 10); // 1210 ms; a claim waits at most 225 + 450 ms
        r.press(Input.guard(press))
                .press(Input.Kind.GUARD_UP, Rig.during(25, 10))
                .through(37);
        assertEquals(10, r.c.pendingFeint());
        long before = r.breath();
        r.through(38); // 1900 ms is past 1885
        assertEquals(0, r.c.pendingFeint());
        assertEquals(before + Rig.points(0.5 - 10), r.breath());
        r.through(41); // the strike, never decided, leaves its ledger once its own claim runs out (1350 + 675)
        assertFalse(r.c.deflects().holds(1));
    }

    @Test
    void aStepInAnyAttacksRecoveryCancelsIt_inItsActiveFramesItWaitsForTheRecovery() {
        Rig r = new Rig();
        r.press(Input.Kind.ATTACK, Rig.beat(1))
                .press(Input.Kind.STEP, Rig.during(18, 10))
                .through(19);
        assertEquals(State.STEP, r.c.state());
        assertEquals(Rig.points(92.5), r.breath());
        assertEquals(19 + 6, r.c.stateUntil());

        Rig early = new Rig();
        early.press(Input.Kind.ATTACK, Rig.beat(1))
                .press(Input.Kind.STEP, Rig.during(16, 10))
                .through(17);
        assertEquals(State.ATTACK, early.c.state());
        early.through(18);
        assertEquals(State.STEP, early.c.state()); // the buffer ran it on the first recovery tick
    }

    @Test
    void aStepCancelForfeitsTheGreyBar() {
        Rig r = new Rig();
        r.c.breath().spend(0, Rig.points(50));
        formAt(r, 24);
        r.press(Input.Kind.STEP, Rig.during(33, 10)).through(33);
        assertEquals(Phase.RECOVERY, r.c.phase(33));
        long before = r.breath();
        r.through(34);
        assertEquals(State.STEP, r.c.state());
        assertEquals(0, r.c.breath().grey());
        assertEquals(before + Rig.points(0.5 - 7.5), r.breath());
    }

    @Test
    void shortOfBreathAStepDoesNothingAndIsUsedUp_windedItStillSteps() {
        Rig poor = new Rig();
        poor.c.breath().spend(0, Rig.points(96)); // 4 + 0.5 a tick: 6.5 at tick 5
        poor.press(Input.Kind.STEP, Rig.during(4, 10)).through(5);
        assertEquals(State.NEUTRAL, poor.c.state());
        poor.through(9); // 8.5 now, but the press is gone
        assertEquals(State.NEUTRAL, poor.c.state());

        Rig winded = new Rig();
        formAt(winded, 24);
        winded.press(Input.Kind.INHALE, Rig.ms(1700)).through(50); // off the beat in recovery: Winded to 74
        assertTrue(winded.c.breath().winded(50));
        winded.press(Input.Kind.STEP, Rig.during(50, 10)).through(51);
        assertEquals(State.STEP, winded.c.state());
    }

    @Test
    void watersGuardCancelIsFreeAfterAMissAndFiveBreathAfterAHit() {
        Rig miss = new Rig();
        miss.press(Input.Kind.ATTACK, Rig.beat(1))
                .press(Input.guard(Rig.during(18, 10)))
                .through(19);
        assertEquals(State.NEUTRAL, miss.c.state());
        assertEquals(Rig.points(100), miss.breath());
        miss.through(20);
        assertEquals(State.GUARD, miss.c.state());

        Rig hit = new Rig();
        hit.press(Input.Kind.ATTACK, Rig.beat(1)).effect(new Effect.Connected(), 16);
        hit.press(Input.guard(Rig.during(18, 10))).through(19);
        assertEquals(State.NEUTRAL, hit.c.state());
        assertEquals(Rig.points(95), hit.breath());
    }

    @Test
    void aGuardHeldThroughTheSwingCancelsItsFirstRecoveryTick() {
        Rig r = new Rig();
        r.press(Input.Kind.ATTACK, Rig.beat(1))
                .press(Input.guard(Rig.during(13, 10)))
                .through(17);
        assertEquals(State.ATTACK, r.c.state());
        r.through(18);
        assertEquals(State.GUARD, r.c.state());
    }

    @Test
    void theGuardCancelIsLegalWhateverTheBreath() {
        Rig r = new Rig();
        r.c.breath().spend(0, Rig.points(100)); // 0 at tick 0, 8.5 by tick 17
        r.press(Input.Kind.ATTACK, Rig.beat(1)).effect(new Effect.Connected(), 16);
        r.press(Input.guard(Rig.during(18, 10))).through(19);
        assertEquals(State.NEUTRAL, r.c.state());
        assertEquals(Rig.points(9.5 - 5), r.breath());
    }
}
