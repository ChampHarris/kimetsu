package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.machine.InputBuffer.Slot;
import org.junit.jupiter.api.Test;

/** Rule 3's input buffer on its own: one press per class, the newest, kept for 3 ticks. */
class InputBufferTest {

    @Test
    void itKeepsTheNewestPressOfEachClassForThreeTicks() {
        InputBuffer b = new InputBuffer(3);
        Input first = Input.of(Input.Kind.ATTACK, 1_000);
        Input second = Input.of(Input.Kind.ATTACK, 2_000);
        b.offer(Slot.ATTACK, first, 10, 10);
        b.offer(Slot.ATTACK, second, 11, 12);
        assertEquals(second, b.get(Slot.ATTACK));
        assertEquals(11, b.pressedAt(Slot.ATTACK));
        assertEquals(12, b.minActive(Slot.ATTACK));
        b.expire(14);
        assertEquals(second, b.get(Slot.ATTACK)); // pressed in 11: tried in 12, 13 and 14
        b.expire(15);
        assertNull(b.get(Slot.ATTACK));
    }

    @Test
    void defenceDropsOffenceButNotTheStep() {
        InputBuffer b = new InputBuffer(3);
        for (Slot s : Slot.values()) {
            b.offer(s, Input.of(Input.Kind.ATTACK, 1_000), 10, 10);
        }
        b.dropOffence();
        assertTrue(b.get(Slot.STEP) != null);
        assertNull(b.get(Slot.POMMEL));
        assertNull(b.get(Slot.FORM));
        assertNull(b.get(Slot.ATTACK));
        b.clear();
        assertTrue(b.isEmpty());
    }

    @Test
    void aCopyIsTheSameUntilEitherChanges() {
        InputBuffer a = new InputBuffer(3);
        a.offer(Slot.POMMEL, Input.of(Input.Kind.POMMEL, 5_000), 7, 9);
        InputBuffer b = new InputBuffer(3);
        b.copyFrom(a);
        assertTrue(b.sameAs(a));
        b.offer(Slot.POMMEL, Input.of(Input.Kind.POMMEL, 5_000), 8, 9);
        assertFalse(b.sameAs(a)); // same press, kept from another tick
        b.take(Slot.POMMEL);
        assertTrue(b.isEmpty());
        assertFalse(b.sameAs(a));
    }
}
