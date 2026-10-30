package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import org.junit.jupiter.api.Test;

/**
 * The combat HUD's fade (Champ's call in D-123, D-141's call 8): shown while you're fighting or within
 * {@code fade_after_ms} (3 s) of a combat press; then it fades over {@code fade_out_ms} (0.5 s) from the later of the
 * last tick you were fighting and that press's 3 s; it comes back at once ({@code fade_in_ms} 0). Jump and Sneak aren't
 * combat presses. The winded tint eases in and out over {@code tint_fade_ms}.
 */
class HudFadeTest {
    private static final Feel.Hud HUD = Feel.Hud.DEFAULT;
    private static final long NONE = HudFade.NONE;
    private static final long PRESS = Micros.ofMillis(10_000);

    private static float visibility(long now, boolean fighting, long lastFighting, long lastPress) {
        return HudFade.visibility(now, fighting, lastFighting, lastPress, HUD);
    }

    @Test
    void theDefaultsAreChampsRule() {
        assertEquals(3000, HUD.fadeAfterMs());
        assertEquals(0, HUD.fadeInMs());
        assertEquals(500, HUD.fadeOutMs());
        assertEquals(200, HUD.tintFadeMs());
    }

    @Test
    void itShowsWhileFighting_whateverThePresses() {
        assertEquals(1f, visibility(PRESS, true, PRESS, NONE));
        assertEquals(1f, visibility(PRESS + Micros.ofMillis(40), true, PRESS, NONE)); // a frame past its last tick
        assertEquals(1f, visibility(PRESS + Micros.ofMillis(60_000), true, PRESS, PRESS));
    }

    @Test
    void aPressShowsItFor3Seconds_thenItFadesOverHalfASecond() {
        assertEquals(1f, visibility(PRESS, false, NONE, PRESS));
        assertEquals(1f, visibility(PRESS + Micros.ofMillis(3000), false, NONE, PRESS));
        assertEquals(0.5f, visibility(PRESS + Micros.ofMillis(3250), false, NONE, PRESS), 1e-6f);
        assertEquals(0f, visibility(PRESS + Micros.ofMillis(3500), false, NONE, PRESS));
        assertEquals(0f, visibility(PRESS + Micros.ofMillis(60_000), false, NONE, PRESS));
    }

    @Test
    void theFightEndingFadesItFromTheLastTickYouWereFighting() {
        long fought = PRESS + Micros.ofMillis(20_000); // the press long gone
        assertEquals(1f, visibility(fought, false, fought, PRESS));
        assertEquals(0.5f, visibility(fought + Micros.ofMillis(250), false, fought, PRESS), 1e-6f);
        assertEquals(0f, visibility(fought + Micros.ofMillis(500), false, fought, PRESS));
        long late = fought - Micros.ofMillis(1000); // a press a second before the fight ended: its 3 s run on
        assertEquals(1f, visibility(fought + Micros.ofMillis(1500), false, fought, late));
        assertEquals(0.5f, visibility(late + Micros.ofMillis(3250), false, fought, late), 1e-6f);
    }

    @Test
    void neverFought_noPress_nothingShows() {
        assertEquals(0f, visibility(PRESS, false, NONE, NONE));
        assertEquals(0f, visibility(Long.MIN_VALUE + 1, false, NONE, NONE), "no overflow at the clock's start");
    }

    @Test
    void aStepBackOfTheClock_beforeThePress_stillShows() {
        assertEquals(1f, visibility(PRESS - Micros.ofMillis(5000), false, NONE, PRESS)); // an epoch: time went back
    }

    @Test
    void itComesBackAtOnceOnAPress() {
        HudFade fade = new HudFade();
        assertEquals(1f, fade.alpha(PRESS, false, NONE, PRESS, HUD));
        assertEquals(0f, fade.alpha(PRESS + Micros.ofMillis(4000), false, NONE, PRESS, HUD));
        long again = PRESS + Micros.ofMillis(5000);
        assertEquals(1f, fade.alpha(again, false, NONE, again, HUD), "fade_in_ms 0: at once");
    }

    @Test
    void aFadeInSpreadsTheComeback() {
        Feel.Hud slow = new Feel.Hud(3000, 200, 500, 200);
        HudFade fade = new HudFade();
        assertEquals(0f, fade.alpha(PRESS, false, NONE, NONE, slow));
        assertEquals(0.5f, fade.alpha(PRESS + Micros.ofMillis(100), false, NONE, PRESS, slow), 1e-6f);
        assertEquals(1f, fade.alpha(PRESS + Micros.ofMillis(200), false, NONE, PRESS, slow));
        assertEquals(0.5f, fade.alpha(PRESS + Micros.ofMillis(3250), false, NONE, PRESS, slow), 1e-6f, "out as ever");
    }

    @Test
    void jumpAndSneakArentCombatPresses_everyOtherKeyIs() {
        for (PlayerIntake.Key k : PlayerIntake.Key.values()) {
            boolean expected = k != PlayerIntake.Key.JUMP && k != PlayerIntake.Key.SNEAK;
            assertEquals(expected, HudFade.counts(k), k.name());
        }
    }

    @Test
    void theTintEasesInAndOut_heldByAHeldClock_andAStepBackJumps() {
        Ramp tint = new Ramp();
        long span = Micros.ofMillis(HUD.tintFadeMs());
        assertEquals(0f, tint.toward(0, PRESS, span, span), "the first frame: at once");
        assertEquals(0.5f, tint.toward(1, PRESS + Micros.ofMillis(100), span, span), 1e-6f);
        assertEquals(0.5f, tint.toward(1, PRESS + Micros.ofMillis(100), span, span), 1e-6f, "a pause holds it");
        assertEquals(1f, tint.toward(1, PRESS + Micros.ofMillis(300), span, span));
        assertEquals(0.75f, tint.toward(0, PRESS + Micros.ofMillis(350), span, span), 1e-6f);
        assertEquals(0f, tint.toward(0, PRESS + Micros.ofMillis(2000), span, span), "no further than its target");
        assertEquals(1f, tint.toward(1, PRESS + Micros.ofMillis(4000), span, span), "up as far, no further");
        assertEquals(0f, tint.toward(0, PRESS, span, span), "the clock stepped back: at once");
        assertTrue(tint.toward(1, PRESS + 1, 0, span) == 1f, "no span: at once");
        assertFalse(tint.toward(0, PRESS + 2, span, 0) != 0f, "no span down: at once");
    }
}
