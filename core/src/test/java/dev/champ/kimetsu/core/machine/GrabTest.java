package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Rule 6.3: grabbed, Breath drains 15/s (net −5/s with the idle regeneration); three on-beat presses, one per beat,
 * break free, each taking its beat's credit; off-beat presses cost 1 (D-035, Q4). Beats 2, 3, 4 are at 1300, 1950 and
 * 2600 ms.
 */
class GrabTest {

    private static Rig grabbed() {
        return new Rig().effect(new Effect.RhythmDelta(8), 1).effect(new Effect.Grabbed(15), 20);
    }

    @Test
    void itDrainsFifteenASecond() {
        Rig r = grabbed().through(30);
        assertEquals(State.GRABBED, r.c.state());
        assertEquals(Rig.points(100 - 0.25 * 10), r.breath());
    }

    @Test
    void threeOnBeatPressesOnePerBeatBreakFreeAndStopTheDrain() {
        Rig r = grabbed();
        r.press(Input.Kind.ATTACK, Rig.beat(2))
                .press(Input.Kind.POMMEL, Rig.beat(3))
                .through(51);
        assertEquals(State.GRABBED, r.c.state());
        assertEquals(2, r.c.grabEscapes());
        r.press(Input.Kind.INHALE, Rig.beat(4)).through(52);
        assertEquals(State.NEUTRAL, r.c.state());
        assertTrue(r.c.escaped());
        assertEquals(8 + 3, r.c.rhythm().points()); // each took its beat's credit
        long free = r.breath();
        r.through(53);
        assertEquals(free + Rig.points(0.5), r.breath());
    }

    @Test
    void twoPressesOnOneBeatCountOnce_offTheBeatCostsOne() {
        Rig r = grabbed();
        r.press(Input.Kind.ATTACK, Rig.beat(2)).press(Input.Kind.ATTACK, Rig.beat(2, 50));
        r.press(Input.Kind.ATTACK, Rig.ms(1600)).through(32);
        assertEquals(1, r.c.grabEscapes());
        assertEquals(8 + 1 - 1, r.c.rhythm().points());
    }

    @Test
    void aThrowOrARescueEndsTheGrabAndItsDrain() {
        Rig thrown = grabbed().effect(new Effect.Knockdown(), 40).through(40);
        assertEquals(State.KNOCKDOWN, thrown.c.state());
        long at = thrown.breath();
        thrown.through(41);
        assertEquals(at + Rig.points(0.5), thrown.breath());

        Rig rescued = grabbed().effect(new Effect.Released(), 40).through(41);
        assertEquals(State.NEUTRAL, rescued.c.state());
    }
}
