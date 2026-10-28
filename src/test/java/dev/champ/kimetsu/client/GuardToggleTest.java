package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.fight.PlayerIntake;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The guard toggle (D-104's Open Question 1: every tap is a guard press, so it deflects, and the guard stays up between
 * taps until the next Attack, Pommel, Step or Breathe, or a double-tap; D-120: a double-tap while it's up). The caller
 * says whether the guard is up: here as a holder's keys leave it after what the toggle sent, in order.
 */
class GuardToggleTest {
    private static final int WINDOW = 7; // machine.double_tap_ticks
    private static final List<String> NOTHING = List.of();
    private static final List<String> PRESS = List.of("press");
    private static final List<String> REPRESS = List.of("release", "press");
    private static final List<String> RELEASE = List.of("release");

    /** A toggle and the guard its sends leave up, as the frames' held state would. */
    private static final class Guard {
        final GuardToggle t = new GuardToggle();
        boolean raised;

        List<String> down(long tick) {
            List<String> sent = new ArrayList<>();
            t.keyDown(raised, tick, WINDOW, d -> send(sent, d));
            return sent;
        }

        List<String> up() {
            List<String> sent = new ArrayList<>();
            t.keyUp(raised, d -> send(sent, d));
            return sent;
        }

        List<String> press(PlayerIntake.Key k) {
            List<String> sent = new ArrayList<>();
            GuardToggle.pressed(k, raised, d -> send(sent, d));
            return sent;
        }

        private void send(List<String> sent, boolean down) {
            sent.add(down ? "press" : "release");
            raised = down;
        }

        /** Raised, then tapped once at {@code tick}: a tap on the raised guard, which a pair can follow. */
        Guard tappedAt(long tick) {
            down(tick - 20);
            up();
            down(tick);
            up();
            return this;
        }
    }

    @Test
    void aTapRaisesTheGuard_andItStaysUpAfterTheKeyComesUp() {
        Guard g = new Guard();
        assertEquals(PRESS, g.down(100));
        assertEquals(NOTHING, g.up(), "a holder's release would drop it; the toggle keeps it up");
        assertEquals(true, g.raised);
    }

    @Test
    void eachTapWhileItsUpIsAGuardPress_aReleaseThenAPressAtItsStamp() {
        Guard g = new Guard();
        g.down(100);
        g.up();
        assertEquals(REPRESS, g.down(120), "a deflect attempt, as a holder's re-press, let go first");
        assertEquals(NOTHING, g.up());
        assertEquals(REPRESS, g.down(140));
        assertEquals(NOTHING, g.up());
        assertEquals(true, g.raised, "it stays up between taps");
    }

    @Test
    void theTapThatRaisesItStartsNoPair_soATapAtTheImpactKeepsItUp() {
        Guard g = new Guard();
        g.down(100); // raised as the cue shows
        g.up();
        assertEquals(REPRESS, g.down(104), "the deflect tap at the impact");
        assertEquals(NOTHING, g.up(), "no pair with the raising tap: still up if the deflect missed");
        assertEquals(true, g.raised);
    }

    @Test
    void aSecondTapOnTheRaisedGuardSevenTicksLaterIsStillAPress_andItDropsAtItsKeyUp_eightDoesnt() {
        Guard g = new Guard().tappedAt(100);
        assertEquals(REPRESS, g.down(107), "the pair's second tap deflects too");
        assertEquals(RELEASE, g.up(), "then the guard follows its key");
        assertEquals(false, g.raised);

        Guard late = new Guard().tappedAt(100);
        assertEquals(REPRESS, late.down(108));
        assertEquals(NOTHING, late.up(), "eight ticks: no pair, it stays up");
        assertEquals(REPRESS, late.down(112), "the late tap starts a pair of its own");
        assertEquals(RELEASE, late.up());
    }

    @Test
    void thePairsSecondTapHeldKeepsTheGuardUpUntilItsKeyUp() {
        Guard g = new Guard().tappedAt(100);
        g.down(103); // held from here: the guard follows the key, as a holder's
        assertEquals(true, g.raised);
        assertEquals(RELEASE, g.up());
    }

    @Test
    void aPairIsUsedUp_soTheNextTapsRaiseItAndKeepItUp() {
        Guard g = new Guard().tappedAt(100);
        g.down(103);
        g.up(); // dropped
        assertEquals(PRESS, g.down(105), "within the window of the pair's tap, but the pair is used up");
        assertEquals(NOTHING, g.up());
        assertEquals(REPRESS, g.down(109));
        assertEquals(NOTHING, g.up(), "the raising tap started no pair");
        assertEquals(REPRESS, g.down(112), "a pair from the tap at 109");
        assertEquals(RELEASE, g.up());
    }

    @Test
    void whateverDropsItBetweenTaps_theNextTapRaisesIt_andIsNoPair() {
        Guard g = new Guard().tappedAt(100);
        g.press(PlayerIntake.Key.ATTACK);
        assertEquals(PRESS, g.down(103), "an action dropped it: this tap raises it");
        assertEquals(NOTHING, g.up());

        Guard forced = new Guard().tappedAt(100);
        forced.raised = false; // a screen, the pause, lost focus or the blade let it go
        assertEquals(PRESS, forced.down(103));
        assertEquals(NOTHING, forced.up());
        assertEquals(true, forced.raised);

        Guard lost = new Guard().tappedAt(100);
        lost.down(103); // the pair's second tap, held; then a screen lets go of it and its key-up never comes
        lost.raised = false;
        assertEquals(PRESS, lost.down(130));
        assertEquals(NOTHING, lost.up(), "the raising tap isn't the lost pair's: it stays up");
    }

    @Test
    void anAttackPommelStepBreatheOrInhalePressDropsItFirst_noOtherKeyDoes() {
        Set<PlayerIntake.Key> drops = EnumSet.of(
                PlayerIntake.Key.ATTACK,
                PlayerIntake.Key.POMMEL,
                PlayerIntake.Key.STEP,
                PlayerIntake.Key.BREATHE,
                PlayerIntake.Key.INHALE);
        for (PlayerIntake.Key k : PlayerIntake.Key.values()) {
            Guard up = new Guard();
            up.raised = true;
            assertEquals(drops.contains(k) ? RELEASE : NOTHING, up.press(k), k + ", up");
            assertEquals(NOTHING, new Guard().press(k), k + ", down");
        }
    }

    @Test
    void anActionWhileThePairsKeyIsHeldDropsIt_andThatKeyUpThenSendsNothing() {
        Guard g = new Guard().tappedAt(100);
        g.down(104); // the pair's second tap, held
        assertEquals(RELEASE, g.press(PlayerIntake.Key.STEP));
        assertEquals(NOTHING, g.up(), "already down");
        assertEquals(PRESS, g.down(130));
        assertEquals(NOTHING, g.up(), "the follow ended with its key: this one stays up");
    }

    @Test
    void aKeyUpWithTheGuardDownSendsNothing() {
        Guard g = new Guard();
        assertEquals(NOTHING, g.up(), "a press the capture passed (Sneak's use, a screen) comes up");
    }

    @Test
    void aKeyUpWhoseKeyDownTheToggleNeverHeardSendsNothing_evenWithTheGuardUp() {
        // the capture passes a press it doesn't take (Sneak's use) without telling the toggle, but not its key-up
        Guard g = new Guard();
        g.down(100);
        g.up();
        assertEquals(NOTHING, g.up(), "Sneak's use while the toggled guard is up");
        assertEquals(true, g.raised);

        Guard paired = new Guard().tappedAt(100);
        paired.down(103);
        paired.up(); // the pair's: down
        paired.raised = true; // up by a press the toggle didn't send (the dev hook's)
        assertEquals(NOTHING, paired.up(), "only the pair's own key-up lets go");
    }
}
