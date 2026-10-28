package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.client.CaptureRules.Action;
import org.junit.jupiter.api.Test;

/** The capture's decision (spec Netcode, "Input capture"; the key table; D-055). */
class CaptureRulesTest {
    @Test
    void withTheBladeAMouseCombatPressIsTakenAndVanillaNeverSeesIt() {
        assertEquals(Action.TAKE_AND_CANCEL, CaptureRules.press(true, false, true, false, false, false)); // Attack
        assertEquals(Action.TAKE_AND_CANCEL, CaptureRules.press(true, false, true, true, false, false)); // Guard
        assertEquals(Action.TAKE, CaptureRules.press(true, false, false, false, false, false)); // Breathe, Pommel
    }

    @Test
    void withoutTheBlade_orWithAScreenOpen_everythingIsVanillas() {
        assertEquals(Action.PASS, CaptureRules.press(false, false, true, false, false, false));
        assertEquals(Action.PASS, CaptureRules.press(true, true, true, false, false, false));
        assertEquals(Action.PASS, CaptureRules.press(true, true, false, false, false, false));
    }

    @Test
    void sneakWithTheGuardKeyIsVanillasUse_butSneakingDoesntStopAnAttack() {
        assertEquals(Action.PASS, CaptureRules.press(true, false, true, true, true, false));
        assertEquals(Action.TAKE_AND_CANCEL, CaptureRules.press(true, false, true, false, true, false));
    }

    @Test
    void theClickThatGrabsTheMouseIsVanillas_aKeyStillTheMods() {
        assertEquals(Action.PASS, CaptureRules.press(true, false, true, false, false, true)); // back from alt-tab
        assertEquals(Action.PASS, CaptureRules.press(true, false, true, true, false, true));
        assertEquals(Action.TAKE, CaptureRules.press(true, false, false, false, false, true)); // keys don't grab
    }

    /** D-055: away from fights the attack key on a block mines; fighting, off a block, or another key, it's the Attack. */
    @Test
    void theAttackKeyMinesOnABlockOnlyAwayFromFights() {
        assertTrue(CaptureRules.mines(true, true, false));
        assertFalse(CaptureRules.mines(true, true, true)); // in combat, or a demon within 32 blocks
        assertFalse(CaptureRules.mines(true, false, false)); // in the air: a practice swing
        assertFalse(
                CaptureRules.mines(false, true, false)); // the mod's Attack bound to a key vanilla doesn't attack with
    }

    /**
     * D-083: vanilla's Jump goes out as a press under the combat keys' rule (the blade in hand, no screen), and vanilla
     * always sees it too, on a mouse button as on a key: it's its jump (Sneak too, once the juggle cut drops, D-085).
     */
    @Test
    void vanillasJumpIsSentWithTheBlade_andStaysVanillas() {
        assertEquals(Action.TAKE, CaptureRules.vanillaKey(true, false));
        assertEquals(Action.PASS, CaptureRules.vanillaKey(false, false));
        assertEquals(Action.PASS, CaptureRules.vanillaKey(true, true));
    }

    @Test
    void aReleaseIsTakenWhenItsPressWas_andNeverCancelled() {
        assertEquals(Action.TAKE, CaptureRules.release(true));
        assertEquals(Action.PASS, CaptureRules.release(false));
    }

    @Test
    void vanillasAttackNeverRunsWithTheBlade_itsUseOnlyWithSneak() {
        assertTrue(CaptureRules.stopVanilla(true, true, false, false));
        assertTrue(CaptureRules.stopVanilla(true, true, false, true));
        assertTrue(CaptureRules.stopVanilla(true, false, true, false));
        assertFalse(CaptureRules.stopVanilla(true, false, true, true));
        assertFalse(CaptureRules.stopVanilla(false, true, false, false));
        assertFalse(CaptureRules.stopVanilla(true, false, false, false)); // pick block
    }

    /**
     * The key table: lock-on is middle mouse, pick block only without the blade. Its press is captured and cancelled
     * like the others; vanilla's pick block triggered anyway on the lock-on key is stopped with the blade, the backstop.
     * Lock-on bound elsewhere leaves pick block alone.
     */
    @Test
    void pickBlockOnTheLockOnKeyNeverRunsWithTheBlade() {
        assertTrue(CaptureRules.stopPick(true, true));
        assertFalse(CaptureRules.stopPick(false, true));
        assertFalse(CaptureRules.stopPick(true, false));
    }
}
