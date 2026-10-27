package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Segment;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.net.NetMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The client's own presses as {@code ClientInput} hands them over (D-110's review): raw stamps and frame times, judged
 * as the server judges them (the stamp less half the frame's time, Rule 2.1), with the radial brought up to a press's
 * judged stamp, since the server opens it at any press past its hold's edge, and closed at the end of the server's
 * closing step. Water's rules: the radial's hold 150 ms, its limit 2 beats (26 ticks), the beat 13 ticks (0.65 s), on it
 * within 120 ms; the Light Chain's window from a hit's 6th tick.
 *
 * <p>Client tick t starts at the end of engine step t, after the presses that act in it; the harness runs a press in the
 * tick after the last one started, as {@code ClientInput} does, and a test that skips a tick's start models a client
 * whose ticks start late in the step.
 */
class OwnPressesTest {
    private static final Moveset WATER = Fixtures.water();
    private static final OwnMoves.Rules RULES = OwnMoves.Rules.of(
            WATER,
            new CombatRules(
                    Fixtures.timing(),
                    Fixtures.clock(),
                    Fixtures.machine(),
                    Fixtures.hits(),
                    Fixtures.strikes(),
                    Fixtures.rhythm(),
                    Fixtures.fights(),
                    Fixtures.movement()),
            Fixtures.breath(),
            0);
    private static final double FLICK = Fixtures.machine().radialFlickDeg();
    private static final String C1 = "kimetsu:light_chain/1";
    private static final String C2 = "kimetsu:light_chain/2";
    private static final String NONE = "none";

    // ------------------------------------------------------------------------------------------- finding 1

    @ParameterizedTest
    @EnumSource(
            value = PlayerIntake.Key.class,
            names = {"ATTACK", "POMMEL", "STEP"})
    void aPressPastTheHoldsEdgeMeetsTheRadial_thoughNoFrameSawTheEdge(PlayerIntake.Key k) {
        // the server opens the radial at a press stamped past its edge (Combatant.input) and holds the press; a client
        // that ran it from NEUTRAL would dash or start Chain 1, never aim, and fire nothing at the release
        Run r = new Run();
        r.key(PlayerIntake.Key.BREATHE, true, 10_000, 0); // its edge at 160 ms, in step 4
        r.to(3); // step 3's end, 150 ms: short of it
        r.key(k, true, 170_000, 0);
        assertTrue(r.p.radial.aiming(), "the radial aims from its edge");
        r.to(4);
        r.running(4, NONE);
        assertEquals(OwnMoves.NONE, r.p.moves.takeStep(), "no Step");
        r.release(200_000, 0, 1); // Water Wheel, to the right
        r.to(5);
        r.running(5, WATER.radial(1));
    }

    @Test
    void anInhalePressedPastTheEdgeMeetsTheRadial_andGivesNothing() {
        // Combatant.inhale: in RADIAL an Inhale does nothing, so it can't pay for a Step
        Run r = new Run().shows(5);
        r.key(PlayerIntake.Key.BREATHE, true, 510_000, 0); // its edge at 660 ms, in step 14
        r.to(13);
        r.key(PlayerIntake.Key.INHALE, true, 670_000, 0); // on beat 1, 20 ms past the edge
        r.release(700_000, 0, -1);
        r.to(14);
        r.key(PlayerIntake.Key.STEP, true, 720_000, 0);
        r.to(15);
        assertEquals(OwnMoves.NONE, r.p.moves.takeStep(), "5 Breath: no Step");
    }

    @Test
    void aGuardLetGoAcrossTheEdgeOpensItFromItsOwnStep_beforeAFrameSeesIt() {
        // the server opens it at the end of the guard's release's step (Combatant.guardUp, then advance); the next
        // frame, in a later step on a client whose ticks start late, would put the limit a step too late
        Run r = new Run();
        r.key(PlayerIntake.Key.BREATHE, true, 0, 0);
        r.key(PlayerIntake.Key.GUARD, true, 20_000, 0);
        r.to(6);
        r.key(PlayerIntake.Key.GUARD, false, 305_000, 0); // step 7: open from step 7, closed at step 33's end
        assertTrue(r.frame(360_000, 0), "a frame in step 8, before step 7's tick start");
        r.to(32);
        assertTrue(r.frame(1_650_000, 0), "a release in step 33 meets it");
        r.to(33);
        assertFalse(r.frame(1_650_001, 0), "step 33's end closed it");
    }

    @Test
    void aGuardPressedBetweenUpdatesHoldsTheRadialFromItsOwnStamp() {
        // the radial's view of what holds it back is brought up after a press too: a guard pressed before the edge and
        // let go after it, with no frame or tick start between, opens it at its release's step, not at the edge's
        Run r = new Run();
        r.key(PlayerIntake.Key.BREATHE, true, 0, 0); // its edge at 150 ms, in step 3
        r.to(2);
        r.frame(120_000, 0);
        r.key(PlayerIntake.Key.GUARD, true, 140_000, 0);
        r.key(PlayerIntake.Key.GUARD, false, 160_000, 0); // step 4: open from step 4, closed at step 30's end
        r.to(29);
        assertTrue(r.p.radial.aiming(), "open through step 29's end");
        r.to(30);
        assertFalse(r.p.radial.aiming());
    }

    @Test
    void aGuardPressedPastTheEdgeInAPommelTheRadialNeverOpenedInSpendsNothing() {
        // the Pommel, pressed before the edge with no update since, holds the server in ATTACK across it: the guard
        // press past the edge finds no radial to cancel, and its release opens it (the parity test's seed 713)
        Run r = new Run();
        r.key(PlayerIntake.Key.BREATHE, true, 0, 0); // its edge at 150 ms
        r.to(2);
        r.key(PlayerIntake.Key.POMMEL, true, 140_000, 0); // runs at once, from tick 3
        r.key(PlayerIntake.Key.GUARD, true, 160_000, 0);
        r.to(3);
        r.running(3, "kimetsu:pommel");
        r.to(7); // the guard held into its recovery cancels it (Water's guard-cancel)
        r.running(7, NONE);
        r.key(PlayerIntake.Key.GUARD, false, 380_000, 0); // step 8
        assertTrue(r.p.radial.aiming(), "not spent: it opens at the guard's release");
    }

    @Test
    void freeAtItsPress_itOpensFromTheEdgesStep_thoughAHitchHidTheEdge() {
        // nothing held it back from its press to the edge, so the server opened it in the edge's step (4); the first
        // update after the press comes 290 ms later, past the edge, so only the press itself saw it free
        Run r = new Run();
        r.key(PlayerIntake.Key.BREATHE, true, 10_000, 0);
        assertTrue(r.frame(300_000, 0), "open");
        assertTrue(r.frame(1_500_000, 0), "a release in step 30 meets it");
        assertFalse(r.frame(1_500_001, 0), "step 30's end closed it, two beats from step 4");
    }

    // ------------------------------------------------------------------------------------------- finding 2

    @Test
    void aPressIsJudgedHalfItsFrameEarly_soTheLightChainContinuesWhereTheServerSaysSo() {
        // the next beat's window ends at 1,420 ms; a press stamped 1,440 ms in a 50 ms frame is judged at 1,415
        Run r = new Run();
        r.to(12);
        r.key(PlayerIntake.Key.ATTACK, true, 650_000, 0); // beat 1: Chain 1
        r.to(13);
        r.running(13, C1);
        r.to(28);
        r.key(PlayerIntake.Key.ATTACK, true, 1_440_000, 50_000);
        r.to(29);
        r.running(29, C2);
    }

    @Test
    void aTapIsJudgedFromItsJudgedStamps_eachFramesHalfItsOwn() {
        // pressed in a 16.7 ms frame (judged 8.3 ms early, on beat 2), let go 160 ms later in a 100 ms frame (judged
        // 50 ms early): 118 ms judged, a tap, so its on-beat Inhale pays for the Step
        Run r = new Run().shows(5);
        r.to(25);
        r.key(PlayerIntake.Key.BREATHE, true, 1_308_333, 16_667);
        r.to(28);
        r.release(1_468_333, 100_000, -1);
        r.to(29);
        r.key(PlayerIntake.Key.STEP, true, 1_500_000, 16_667); // 5 + 6 ≥ 7.5
        r.to(30);
        assertEquals(30, r.p.moves.takeStep(), "the Step's first tick");
    }

    @Test
    void theRadialsEdgeIsCountedFromThePresssJudgedStamp() {
        // pressed at 160 ms in a 40 ms frame: judged at 140, its edge at 290, so step 6's end opens it on the server
        Run r = new Run();
        r.to(2);
        r.key(PlayerIntake.Key.BREATHE, true, 160_000, 40_000);
        r.to(5);
        assertFalse(r.p.radial.aiming());
        r.to(6);
        assertTrue(r.p.radial.aiming(), "open from step 6");
    }

    // ------------------------------------------------------------------------------------------- finding 5

    @Test
    void aReleaseInTheClosingStepsLastTickStillFiresItsForm() {
        // open from step 4 (its edge at 160 ms), closed at step 30's end (1,500 ms): a release at 1,470 ms meets it
        Run r = new Run();
        r.key(PlayerIntake.Key.BREATHE, true, 10_000, 0);
        r.to(3);
        assertTrue(r.frame(170_000, 0));
        r.to(29);
        r.release(1_470_000, 0, 0); // up: Water Surface Slash
        r.to(30);
        r.running(30, WATER.radial(0));
    }

    /** One run: presses act in the tick after the last one started, each tick's start at its step's end. */
    private static final class Run {
        final OwnPresses p = new OwnPresses();
        private NetMessage.@Nullable Snapshot shown; // none yet: a fresh fighter, full Breath, standing
        private long ticked = 0; // the last tick started

        /** The last snapshot shows NEUTRAL at {@code breath}, settled at step 5, no regeneration. */
        Run shows(double breath) {
            long max = Fixed.of(100);
            shown = new NetMessage.Snapshot(
                    5,
                    0,
                    State.NEUTRAL,
                    "",
                    new Segment(Fixed.of(breath), 0, 5, max),
                    0,
                    false,
                    new Segment(max, 0, 5, max),
                    0,
                    0,
                    Long.MIN_VALUE);
            return this;
        }

        /** Every client tick up to {@code tick} starts. */
        void to(long tick) {
            for (long t = ticked + 1; t <= tick; t++) {
                p.tick(t, t, RULES, shown, shown);
            }
            ticked = Math.max(ticked, tick);
        }

        /** A render frame's own aim at {@code micros}, in a frame of {@code frameMicros}. */
        boolean frame(long micros, long frameMicros) {
            return p.aim(micros, frameMicros, ticked, RULES, shown);
        }

        void key(PlayerIntake.Key k, boolean down, long stampMicros, long frameMicros) {
            p.key(k, down, ticked + 1, stampMicros, frameMicros, -1, RULES, shown, shown);
        }

        /**
         * A real Breathe key-up as {@code ClientInput.slotAtRelease} takes it: the radial aimed at its stamp, then a
         * 45° stroke toward {@code slot} (−1: none), which fires only if it aims.
         */
        void release(long stampMicros, long frameMicros, int slot) {
            p.aim(stampMicros, frameMicros, ticked, RULES, shown);
            double a = Math.toRadians(slot * 90.0);
            double deg = slot >= 0 ? 3 * FLICK : 0;
            int s = p.radial.release(
                    deg * Math.sin(a),
                    -deg * Math.cos(a),
                    WATER.style().radial().size(),
                    FLICK);
            p.key(PlayerIntake.Key.BREATHE, false, ticked + 1, stampMicros, frameMicros, s, RULES, shown, shown);
        }

        void running(long tick, @Nullable FrameData expected) {
            running(tick, expected != null ? expected.id() : NONE);
        }

        void running(long tick, String id) {
            FrameData f = p.moves.attackAt(tick);
            assertEquals(id, f != null ? f.id() : NONE, "the attack running at tick " + tick);
        }
    }
}
