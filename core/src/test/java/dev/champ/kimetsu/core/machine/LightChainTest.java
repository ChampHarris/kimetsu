package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.Phase;
import org.junit.jupiter.api.Test;

/**
 * Rules 3.1 and 3.2. Chain hits are 3 / 2 / 6 ticks; Water's beat is 13 ticks (650 ms). Chain 1 pressed on beat 1
 * (650 ms) acts in step 13: windup 13–15, active 16–17, recovery 18–23, NEUTRAL at 24. Its next press is open from its
 * last active tick (a press acting in step 18) to the end of beat 2's window (1300 + 120 ms).
 */
class LightChainTest {

    /** A rig with 8 Rhythm points (tier 2), so a −1 shows. */
    private static Rig seeded() {
        return new Rig().effect(new Effect.RhythmDelta(8), 1);
    }

    @Test
    void onTheBeatTheChainRunsOneTwoThreeAndThenStartsOver() {
        Rig r = seeded();
        r.press(Input.Kind.ATTACK, Rig.beat(1)).through(13);
        assertSame(r.moves.chain(0), r.c.attack());
        assertEquals(Phase.WINDUP, r.c.phase(13));
        assertEquals(1, r.c.chainNext());
        assertEquals(Rig.beat(2, 120), r.c.chainUntil());
        r.through(24);
        assertEquals(State.NEUTRAL, r.c.state());

        r.press(Input.Kind.ATTACK, Rig.beat(2)).through(26);
        assertSame(r.moves.chain(1), r.c.attack());
        r.press(Input.Kind.ATTACK, Rig.beat(3)).through(39);
        assertSame(r.moves.chain(2), r.c.attack());
        assertEquals(0, r.c.chainNext()); // Chain 3 completes it (D-035)

        r.press(Input.Kind.ATTACK, Rig.beat(4)).through(52);
        assertSame(r.moves.chain(0), r.c.attack()); // a fresh Chain 1, no stumble
        assertEquals(52 + 11, r.c.attackEnd());
        assertEquals(8, r.c.rhythm().points()); // on-beat presses score only when they land (slice 3)
    }

    @Test
    void theWindowRunsToTheEndOfTheNextBeatsOnBeatWindowInclusive() {
        Rig late = seeded();
        late.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.ATTACK, Rig.beat(2, 120));
        late.through(29);
        assertSame(late.moves.chain(1), late.c.attack());

        Rig tooLate = seeded();
        tooLate.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.ATTACK, Rig.beat(2, 121));
        tooLate.through(29);
        assertSame(tooLate.moves.chain(0), tooLate.c.attack()); // after the window: a new chain, judged off-beat
        assertEquals(29, tooLate.c.attackStart());
        assertEquals(7, tooLate.c.rhythm().points());

        Rig early = seeded();
        early.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.ATTACK, Rig.beat(2, -120));
        early.through(24);
        assertSame(early.moves.chain(1), early.c.attack()); // starts as Chain 1 ends
        assertEquals(24, early.c.attackStart());
    }

    @Test
    void offTheBeatItStumblesSixTicksAndCostsOne() {
        Rig r = seeded();
        r.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.ATTACK, Rig.beat(2, -121));
        r.through(24);
        assertEquals(State.ATTACK, r.c.state());
        assertSame(r.moves.chain(0), r.c.attack());
        assertEquals(24 + 6, r.c.attackEnd());
        assertEquals(7, r.c.rhythm().points());
        assertEquals(0, r.c.chainNext());
        r.through(29);
        assertEquals(10, r.c.frameAt(29)); // the stumble holds the last recovery frame
        r.through(30);
        assertEquals(State.NEUTRAL, r.c.state());
        r.press(Input.Kind.ATTACK, Rig.beat(3)).through(39);
        assertSame(r.moves.chain(0), r.c.attack()); // the chain broke: Chain 1 again
    }

    @Test
    void aStumbleFromNeutralGoesBackIntoTheLastHitsRecovery() {
        Rig r = seeded();
        r.press(Input.Kind.ATTACK, Rig.beat(1, -100)).through(22); // acts in step 11, ends at 22
        assertEquals(State.NEUTRAL, r.c.state());
        r.press(Input.Kind.ATTACK, Rig.ms(1120)).through(23); // 180 ms before beat 2
        assertEquals(State.ATTACK, r.c.state());
        assertSame(r.moves.chain(0), r.c.attack());
        assertEquals(Phase.RECOVERY, r.c.phase(23));
        assertEquals(23 + 6, r.c.attackEnd());
        assertEquals(7, r.c.rhythm().points());
        assertEquals(Long.MIN_VALUE, r.c.attackBeat()); // a stumble earns nothing: no beat, no hits
        assertTrue(!r.c.attackOnBeat());
    }

    @Test
    void aPressTooEarlyWaitsForTheWindowAndThenStumbles() {
        Rig r = seeded();
        r.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.ATTACK, Rig.during(15, 40)); // 140 ms after beat 1
        r.through(16);
        assertTrue(r.c.buffer().get(InputBuffer.Slot.ATTACK) != null);
        r.through(18); // the window opens in step 18: the buffered press runs, off the beat
        assertEquals(24 + 6, r.c.attackEnd());
        assertEquals(7, r.c.rhythm().points());
    }

    @Test
    void withNoPressTheChainResetsAfterTheWindow() {
        Rig r = seeded();
        r.press(Input.Kind.ATTACK, Rig.beat(1)).through(28);
        assertEquals(1, r.c.chainNext());
        r.through(29); // 1450 ms is past beat 2's window
        assertEquals(0, r.c.chainNext());
        r.press(Input.Kind.ATTACK, Rig.beat(3)).through(39);
        assertSame(r.moves.chain(0), r.c.attack());
    }

    @Test
    void auto_rhythmCountsTheNearestBeatSoAPressOnTheSameBeatStumblesFree() {
        Rig r = seeded().effect(new Effect.AssistChanged(3), 1);
        r.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.ATTACK, Rig.ms(900)); // nearest beat 1
        r.through(18);
        assertEquals(24 + 6, r.c.attackEnd());
        assertEquals(8, r.c.rhythm().points()); // nothing is off the beat at level 3

        Rig next = seeded().effect(new Effect.AssistChanged(3), 1);
        next.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.Kind.ATTACK, Rig.ms(1000)); // nearest beat 2
        next.through(13);
        assertEquals(Rig.beat(2, 180), next.c.chainUntil()); // the window is assist 2's
        next.through(20);
        assertSame(next.moves.chain(1), next.c.attack());
    }

    @Test
    void aStepAGuardOrAnotherAttackBetweenHitsEndsTheChain() {
        Rig step = new Rig();
        step.press(Input.Kind.ATTACK, Rig.beat(1))
                .press(Input.Kind.STEP, Rig.during(18, 10))
                .through(19);
        assertEquals(State.STEP, step.c.state());
        assertEquals(0, step.c.chainNext());

        Rig guard = new Rig();
        guard.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.guard(Rig.during(19, 10)));
        guard.through(21);
        assertEquals(State.GUARD, guard.c.state()); // guard-cancelled at 20, GUARD at 21
        assertEquals(0, guard.c.chainNext());

        Rig pommel = new Rig();
        pommel.press(Input.Kind.ATTACK, Rig.beat(1))
                .press(Input.Kind.POMMEL, Rig.during(24, 10))
                .through(25);
        assertSame(pommel.moves.pommel(), pommel.c.attack());
        assertEquals(0, pommel.c.chainNext());
    }

    @Test
    void falling_twoBlocksTheAttackPressFiresWaterfallBasin_lessIsChainOne() {
        Rig fall = new Rig();
        fall.press(Input.attackFalling(Rig.beat(1), 2.0)).through(13);
        assertSame(fall.moves.falling(), fall.c.attack());
        assertEquals(fall.moves.style().fallingForm(), fall.c.attack().id());

        Rig hop = new Rig();
        hop.press(Input.attackFalling(Rig.beat(1), 1.99)).through(13);
        assertSame(hop.moves.chain(0), hop.c.attack());

        Rig tired = new Rig();
        tired.c.breath().spend(0, Rig.points(90)); // 10 left: Waterfall Basin costs 25
        tired.press(Input.attackFalling(Rig.beat(1), 3.0)).through(13);
        assertSame(tired.moves.chain(0), tired.c.attack()); // it can't fire: the chain takes the press

        Rig midChain = new Rig();
        midChain.press(Input.Kind.ATTACK, Rig.beat(1)).press(Input.attackFalling(Rig.beat(2), 2.5));
        midChain.through(26);
        assertSame(midChain.moves.falling(), midChain.c.attack()); // the contextual form comes first
    }

    @Test
    void aBackDatedChainHitHoldsItsLastWindupFrameUntilTheTickItArrived() {
        Rig r = new Rig();
        r.through(19);
        r.c.input(Input.of(Input.Kind.ATTACK, Rig.during(19, 10)), 20, 25); // stamped in 20, arrived in 25
        r.through(20);
        assertEquals(2, r.c.attackHold()); // windup 20–22, held at frame 2 through 24
        assertEquals(Phase.WINDUP, r.c.phase(24));
        assertEquals(2, r.c.frameAt(24));
        assertEquals(Phase.ACTIVE, r.c.phase(25));
        assertEquals(3, r.c.frameAt(25));
        assertEquals(20 + 2 + 11, r.c.attackEnd());

        Rig onTime = new Rig();
        onTime.through(21);
        onTime.c.input(Input.of(Input.Kind.ATTACK, Rig.during(21, 10)), 22, 25); // its whole windup back
        onTime.through(22);
        assertEquals(0, onTime.c.attackHold());
        assertEquals(Phase.ACTIVE, onTime.c.phase(25));
    }
}
