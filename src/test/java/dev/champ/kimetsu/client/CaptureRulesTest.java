package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.client.CaptureRules.Action;
import org.junit.jupiter.api.Test;

/** The capture's decision (spec Netcode, "Input capture"; the key table; D-055 until 5c). */
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
}
