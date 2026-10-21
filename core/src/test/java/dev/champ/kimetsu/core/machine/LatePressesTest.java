package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.AttackClass;
import org.junit.jupiter.api.Test;

/**
 * Presses the world decided without: a press that arrives after a strike's verdict is applied after the tick the
 * world read, so after the effect it predates. On time it would have come first, and the machine treats it so (the
 * netcode review's M1). Here the rig plays the Timeline's part by applying a press in a later step than its stamp's.
 * Also the buffer's priority order (Rule 3).
 */
class LatePressesTest {

    @Test
    void offencePressedBeforeAHitButAppliedAfterItComesToNothing() {
        Rig r = new Rig().effect(new Effect.Hit(AttackClass.LIGHT, 3), 20).through(20); // HITSTUN to 23
        r.c.input(Input.of(Input.Kind.ATTACK, Rig.during(19, 10)), 21, 21); // stamped before the hit
        r.through(26);
        assertNull(r.c.attack());

        Rig after = new Rig().effect(new Effect.Hit(AttackClass.LIGHT, 3), 20).through(20);
        after.c.input(Input.of(Input.Kind.ATTACK, Rig.during(20, 10)), 21, 21); // stamped after it: buffered
        after.through(23);
        assertSame(after.moves.chain(0), after.c.attack());
    }

    @Test
    void aGrabCountsOnlyTheEscapePressesMadeAfterIt() {
        Rig r = new Rig().effect(new Effect.Grabbed(15), 26).through(26); // grabbed on beat 2's tick
        r.c.input(Input.of(Input.Kind.ATTACK, Rig.beat(2, -10)), 27, 27);
        assertEquals(0, r.c.grabEscapes());
        r.c.input(Input.of(Input.Kind.ATTACK, Rig.beat(2, 10)), 27, 27);
        assertEquals(1, r.c.grabEscapes());
    }

    @Test
    void aClashCountsOnlyThePressesAfterItStarted() {
        Rig r = new Rig().effect(new Effect.ClashStart(), 20).through(20);
        r.c.input(Input.of(Input.Kind.ATTACK, Rig.ms(990)), 21, 21);
        assertEquals(Long.MIN_VALUE, r.c.clashPress());
        r.c.input(Input.of(Input.Kind.ATTACK, Rig.ms(1010)), 21, 21);
        assertEquals(Rig.ms(1010), r.c.clashPress());
    }

    @Test
    void aJumpFromBeforeTheLaunchDoesntLockTechOut() {
        Rig r = new Rig().effect(new Effect.Knockdown(), 40).through(40); // launched at 2000 ms
        r.c.input(Input.of(Input.Kind.JUMP, Rig.ms(1925)), 41, 41); // on time it was a hop before the launch
        r.press(Input.Kind.JUMP, Rig.ms(2350)); // 3 ticks before the floor
        r.effect(new Effect.Contact(Effect.Surface.FLOOR, Rig.ms(2500)), 50).through(50);
        assertTrue(r.c.teched());
    }

    @Test
    void whenTwoBufferedPressesCanRunTogetherThePommelGoesBeforeTheAttack() {
        Rig r = new Rig();
        r.form(0, Rig.ms(1200), 200); // the 1st form: recovery 33–49, NEUTRAL at 50
        r.press(Input.Kind.ATTACK, Rig.during(47, 10)).press(Input.Kind.POMMEL, Rig.during(48, 10));
        r.through(50);
        assertSame(r.moves.pommel(), r.c.attack());
        r.through(61); // the Pommel's last tick: the attack press ran out in the buffer meanwhile
        assertSame(r.moves.pommel(), r.c.attack());
        r.through(62);
        assertNull(r.c.attack());
    }
}
