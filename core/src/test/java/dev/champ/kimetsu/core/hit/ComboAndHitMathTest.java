package dev.champ.kimetsu.core.hit;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.Moveset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/** Combo scaling (Rule 7.5) counts and rounds exactly; a hit deals what the duel sim says (Rules 4.3, 5.3, 2.12). */
class ComboAndHitMathTest {
    private static final HitRules RULES = Fixtures.hits();

    @Test
    void hitsInARowCountAcrossAttackersAndAThirtyTickGap() {
        Combo c = new Combo();
        assertEquals(1, c.land(100, RULES));
        assertEquals(2, c.land(110, RULES)); // another attacker's hit continues it: the combo is the target's
        assertEquals(3, c.land(140, RULES)); // 30 ticks after the last continues
        assertEquals(1, c.land(171, RULES)); // 31 resets
        assertEquals(2, c.land(165, RULES)); // a late hit from before the newest continues it, and moves nothing
        assertEquals(3, c.land(201, RULES)); // measured from 171, the newest seen
        c.reset();
        assertEquals(1, c.land(201, RULES));
    }

    @Test
    void comboHitstunRoundsHalfUpWithAFloor() {
        assertEquals(10, Combo.hitstun(10, 1, RULES));
        assertEquals(9, Combo.hitstun(10, 2, RULES)); // 8.5 → 9
        assertEquals(2, Combo.hitstun(6, 6, RULES)); // 6 × 0.25 = 1.5 → 2
        assertEquals(2, Combo.hitstun(6, 7, RULES)); // the floor
        assertEquals(1, Combo.hitstun(2, 6, RULES)); // 0.5 → 1, and never below 1
        assertEquals(0, Combo.hitstun(0, 3, RULES)); // a hit with no stun stays without
        assertThrows(IllegalArgumentException.class, () -> Combo.hitstun(10, 0, RULES)); // n = 0 would deal 12
    }

    @Test
    void comboHitstunMatchesExactDecimalArithmetic() {
        BigDecimal step = new BigDecimal("0.15");
        BigDecimal floor = new BigDecimal("0.25");
        for (int base = 0; base <= 40; base++) {
            for (int n = 1; n <= 8; n++) {
                BigDecimal share = BigDecimal.ONE
                        .subtract(step.multiply(BigDecimal.valueOf(n - 1L)))
                        .max(floor);
                int expected = BigDecimal.valueOf(base)
                        .multiply(share)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValue();
                if (base > 0) {
                    expected = Math.max(1, expected);
                }
                assertEquals(expected, Combo.hitstun(base, n, RULES), "base " + base + ", hit " + n);
            }
        }
    }

    @Test
    void aHitScalesWithRhythmTheStandInAndAutoRhythm() {
        HitWindow cut = Fixtures.cut(0, 2, 6, 15, 8, 0.35);
        assertNear(6 * 1.32, HitMath.damage(cut, 1.32, false, 0, RULES), 1e-12);
        assertNear(6 * 1.6 * 2.0, HitMath.damage(cut, 1.6, true, 0, RULES), 1e-12); // the ×2.0 stand-in
        assertNear(6 * 1.6 * 2.0 * 0.75, HitMath.damage(cut, 1.6, true, 3, RULES), 1e-12);
        assertNear(6 * 1.6, HitMath.damage(cut, 1.6, false, 2, RULES), 1e-12); // assist 2 deals in full
        assertNear(15 * 1.2, HitMath.posture(cut, 1.2, 0, RULES), 1e-12); // the stand-in is damage only
        assertNear(15 * 1.2 * 0.75, HitMath.posture(cut, 1.2, 3, RULES), 1e-12);
    }

    @Test
    void onlyAFormThatFiredItselfAtMushinTakesTheStandIn() {
        Moveset water = Fixtures.water();
        assertTrue(HitMath.standIn(water.radial(1), true, water)); // the 2nd has no finisher
        assertFalse(HitMath.standIn(water.frame("kimetsu:water/first_form_mushin"), true, water));
        assertFalse(HitMath.standIn(water.radial(1), false, water));
    }
}
