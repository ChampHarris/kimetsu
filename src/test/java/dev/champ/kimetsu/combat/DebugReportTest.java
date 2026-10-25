package dev.champ.kimetsu.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@code /kimetsu debug}'s combat flag (Rule 13.1): a fighter that has never been in combat holds no tick to count
 * down from ({@code Long.MIN_VALUE}), which a subtraction from now overflows into some 10^17 s of combat left (the 5c-4a
 * playtest).
 */
class DebugReportTest {
    @Test
    void neverInCombatIsOutOfCombat() {
        assertEquals("out of combat", DebugReport.combatFlag(Long.MIN_VALUE, 1015));
    }

    @Test
    void theFlagCountsDownToItsTick_thenIsOut() {
        assertEquals("in combat 14.6 s more", DebugReport.combatFlag(1307, 1015));
        assertEquals("in combat 0.1 s more", DebugReport.combatFlag(1016, 1015));
        assertEquals("out of combat", DebugReport.combatFlag(1015, 1015));
        assertEquals("out of combat", DebugReport.combatFlag(900, 1015));
    }
}
