package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.machine.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a combatant's stand-in shows, from its track (5d-3a Step 3, D-138): a routed state's pose from its tick, blended
 * in over {@code pose_blend_ticks}; a hold's and a clash's poses over their windows; the newest layer to begin wins, so
 * a reaction ends a strike and a strike ends an old reaction; a cancelled strike ends at its cancel and blends out; the
 * follow-through spans the recovery; the clang's head recoil runs the data's recoil length; the guard turns to its
 * yaw. Every change blends, so no angle jumps. Times in µs of model time.
 */
class TrackTest {
    private static final Feel FEEL = new Feel(3, 3, 3, Feel.Hud.DEFAULT, Feel.Kfx.DEFAULT);
    private static final long RECOIL = Micros.ofTicks(6); // the M1 demon's clang.recoil_ticks
    private static final float EPS = 1e-4f;

    private static long t(double ticks) {
        return Math.round(ticks * Micros.PER_TICK);
    }

    /** Strike spans by start, as {@code ClientView}'s source gives them for one combatant. */
    static final class Strikes implements StrikeSource {
        private final List<long[]> spans = new ArrayList<>(); // id, start, impact, end, follow

        Strikes add(long id, double start, double impact, double end, double follow) {
            spans.add(new long[] {id, t(start), t(impact), t(end), t(follow)});
            return this;
        }

        @Override
        public boolean newest(long at, StrikeSpan out) {
            long[] best = null;
            for (long[] s : spans) {
                if (s[1] <= at && (best == null || s[1] > best[1])) {
                    best = s;
                }
            }
            if (best == null) {
                return false;
            }
            out.set(best[0], best[1], best[1], best[2], best[3], best[4], null);
            return true;
        }
    }

    private static final Strikes NONE = new Strikes();

    private static StandInPose pose(Track k, StrikeSource s, double ticks) {
        StandInPose out = new StandInPose();
        new Poser().pose(k, s, t(ticks), FEEL, RECOIL, out);
        return out;
    }

    /** The angle drawn with vanilla's own at 0: what the eye sees change. */
    private static float seen(StandInPose p, int part, int axis) {
        return p.rot[part * 3 + axis] * p.weight[part];
    }

    private static Layer.Kind kind(Track k, StrikeSource s, double ticks) {
        return new Poser().layerAt(k, s, t(ticks)).kind();
    }

    private static Stance stance(Track k, StrikeSource s, double ticks) {
        return new Poser().layerAt(k, s, t(ticks)).stance();
    }

    @Test
    void aStatesPoseShowsFromItsTick_blendingInOverPoseBlendTicks() {
        Track k = new Track();
        k.state(State.GUARD, 100);
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 99.9), "before its tick");
        assertEquals(Stance.GUARD, stance(k, NONE, 100));
        float[] guard = StandInPoses.of(Stance.GUARD).rot;
        int arm = StandInPose.RIGHT_ARM;
        assertEquals(0f, pose(k, NONE, 100).weight[arm], EPS, "blending starts at its tick");
        assertEquals(0.5f, pose(k, NONE, 101.5).weight[arm], EPS);
        assertEquals(1f, pose(k, NONE, 103).weight[arm], EPS, "in after pose_blend_ticks exactly");
        StandInPose held = pose(k, NONE, 110);
        assertEquals(1f, held.weight[arm], EPS);
        assertEquals(guard[arm * 3], held.rot[arm * 3], EPS, "the guard's own angles");
    }

    /** {@code MobState} is resent whenever its Posture changes: the same state mustn't restart its blend. */
    @Test
    void aStateResentKeepsItsStart() {
        Track k = new Track();
        k.state(State.GUARD, 100);
        k.state(State.GUARD, 102);
        assertEquals(2 / 3f, pose(k, NONE, 102).weight[StandInPose.RIGHT_ARM], EPS);
    }

    /** A state learned before its tick shows the one it replaces until then; the next blends out to vanilla's. */
    @Test
    void beforeItsTickTheStanceItLeftShows_andNeutralBlendsOut() {
        Track k = new Track();
        k.state(State.STAGGER, 100);
        k.state(State.NEUTRAL, 140);
        assertEquals(Stance.STAGGER, stance(k, NONE, 139.9));
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 140));
        assertEquals(1f, pose(k, NONE, 140).weight[StandInPose.RIGHT_ARM], EPS, "the stagger, still");
        assertEquals(0f, pose(k, NONE, 143).weight[StandInPose.RIGHT_ARM], EPS, "vanilla's own again");
        assertEquals(0f, pose(k, NONE, 143).lean, EPS);
    }

    @Test
    void theStatesAMobSendsMapToTheirStances_theRestToNeutral() {
        assertEquals(Stance.GUARD, Stance.of(State.GUARD));
        assertEquals(Stance.STAGGER, Stance.of(State.STAGGER));
        assertEquals(Stance.COLLAPSED, Stance.of(State.COLLAPSED));
        assertEquals(Stance.KNOCKDOWN, Stance.of(State.KNOCKDOWN));
        assertEquals(Stance.CLASH, Stance.of(State.CLASH));
        assertEquals(Stance.NEUTRAL, Stance.of(State.DEAD), "vanilla's death shows until 5d-5's ash");
        assertEquals(Stance.NEUTRAL, Stance.of(State.ATTACK), "its strikes come from AttackStarted");
    }

    @Test
    void aHoldShowsOverItsWindow_openUntilItEnds() {
        Track k = new Track();
        k.hold(200);
        assertEquals(Stance.HOLD, stance(k, NONE, 1000), "open until its HoldEnded");
        k.holdEnded(230);
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 199.9));
        assertEquals(Stance.HOLD, stance(k, NONE, 200));
        assertEquals(Stance.HOLD, stance(k, NONE, 229.9));
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 230));
    }

    @Test
    void aClashShowsOverItsWindow_withItsFlashKept() {
        Track k = new Track();
        k.clash(300, 15_123_000);
        assertEquals(15_123_000, k.flashMicros());
        k.clashEnded(320);
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 299.9));
        assertEquals(Stance.CLASH, stance(k, NONE, 300));
        assertEquals(Stance.CLASH, stance(k, NONE, 319.9));
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 320));
    }

    @Test
    void theNewestLayerToBeginWins() {
        Strikes s = new Strikes().add(1, 100, 110, 125, 15);
        Track k = new Track();
        k.state(State.GUARD, 90);
        assertEquals(Layer.Kind.STRIKE, kind(k, s, 105), "a strike beats the guard stance it rises from");
        k.state(State.STAGGER, 110);
        assertEquals(Stance.STAGGER, stance(k, s, 111), "a Posture break ends the strike's pose");
        Track down = new Track();
        down.state(State.KNOCKDOWN, 50); // and no NEUTRAL since, as a late message could leave it
        assertEquals(Layer.Kind.STRIKE, kind(down, s, 101), "a strike begun after the knockdown");
        Track held = new Track();
        held.hold(110); // a grab's hold begins at its impact
        assertEquals(Stance.HOLD, stance(held, s, 112));
    }

    /** On one tick, the hold beats the clash, the clash a reaction, and a reaction the strike begun with it. */
    @Test
    void aTieGoesToTheHold_thenTheClash_thenTheReaction() {
        Strikes s = new Strikes().add(1, 110, 120, 135, 15);
        Track k = new Track();
        assertEquals(Layer.Kind.STRIKE, kind(k, s, 111));
        k.state(State.STAGGER, 110);
        assertEquals(Stance.STAGGER, stance(k, s, 111), "a Posture break on the tick its strike begins");
        k.clash(110, 0);
        assertEquals(Stance.CLASH, stance(k, s, 111));
        k.hold(110);
        assertEquals(Stance.HOLD, stance(k, s, 111));
    }

    /** A change inside another's blend blends from the layer it left: here a strike cancelled a tick into its windup. */
    @Test
    void aChangeInsideAnothersBlendBlendsFromTheLayerItLeft() {
        Strikes s = new Strikes().add(1, 100, 110, 101, 15); // a Feint thrown a tick into its windup
        Track k = new Track();
        StandInPose p = pose(k, s, 102);
        int arm = StandInPose.RIGHT_ARM;
        assertEquals(2 / 3f, p.weight[arm], EPS, "a third of the way out of the strike's pose, from its cancel");
        assertEquals(StandInPoses.RAISED * 0.1f, p.rot[arm * 3], EPS, "its windup as the cancel left it");
    }

    /** A second end for one window (a repeated or late message) leaves the end it had. */
    @Test
    void aSecondEndLeavesAWindowsEnd() {
        Track k = new Track();
        k.hold(200);
        k.holdEnded(230);
        k.holdEnded(260);
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 240), "the hold ended at 230");
        k.clash(300, 0);
        k.clashEnded(320);
        k.clashEnded(350);
        assertEquals(Stance.NEUTRAL, stance(k, NONE, 330), "the clash ended at 320");
    }

    @Test
    void aCancelledStrikeEndsAtItsCancel_andBlendsOutWithNoJump() {
        Strikes s = new Strikes().add(1, 100, 110, 105, 15); // cancelled at 105, before its impact (a Feint)
        Track k = new Track();
        assertEquals(Layer.Kind.STRIKE, kind(k, s, 104.9));
        assertEquals(Layer.Kind.STANCE, kind(k, s, 105));
        int arm = StandInPose.RIGHT_ARM;
        float before = seen(pose(k, s, 105 - 1e-4), arm, 0);
        float at = seen(pose(k, s, 105), arm, 0);
        assertTrue(before < -1f, "the arm was well up its windup: " + before);
        assertEquals(before, at, 1e-3f, "no jump at the cancel");
        assertEquals(0f, pose(k, s, 108).weight[arm], EPS, "out after pose_blend_ticks");
    }

    @Test
    void theFollowThroughSpansTheRecovery() {
        Strikes s = new Strikes().add(1, 100, 110, 125, 15);
        Track k = new Track();
        StandInPose half = pose(k, s, 117.5); // halfway through its 15 ticks of active and recovery
        float expect = StandInPoses.RAISED + (StandInPoses.THROUGH - StandInPoses.RAISED) * 0.5f;
        assertEquals(expect, half.rot[StandInPose.RIGHT_ARM * 3], EPS);
        assertEquals(StandInPoses.THROUGH, pose(k, s, 125 - 1e-4).rot[StandInPose.RIGHT_ARM * 3], 1e-3f);
    }

    /** One scene through every kind of change, sampled every millisecond: nothing the eye sees jumps. */
    @Test
    void noChangeJumps() {
        Strikes s = new Strikes().add(1, 100, 110, 125, 15).add(2, 125, 135, 150, 15);
        Track k = new Track();
        k.state(State.GUARD, 90);
        k.state(State.STAGGER, 155);
        k.state(State.KNOCKDOWN, 170);
        k.state(State.NEUTRAL, 195);
        k.hold(205);
        k.holdEnded(225);
        k.clash(235, 0);
        k.clashEnded(250);
        StandInPose last = null;
        for (long u = t(85); u <= t(260); u += 1000) {
            StandInPose p = new StandInPose();
            new Poser().pose(k, s, u, FEEL, RECOIL, p);
            if (last != null) {
                for (int part = 0; part < StandInPose.PARTS; part++) {
                    for (int axis = 0; axis < 3; axis++) {
                        float d = Math.abs(seen(p, part, axis) - seen(last, part, axis));
                        assertTrue(d < 0.03f, "part " + part + " axis " + axis + " jumped " + d + " at " + u);
                    }
                }
                assertTrue(Math.abs(p.lean - last.lean) < 1f, "the lean jumped at " + u);
                assertTrue(Math.abs(p.lift - last.lift) < 0.01f, "the lift jumped at " + u);
            }
            last = p;
        }
    }

    @Test
    void aClangRecoilsTheHeadForTheDatasRecoilLength() {
        Track k = new Track();
        k.clang(100);
        assertEquals(0f, pose(k, NONE, 99.9).recoil, EPS);
        assertEquals(StandInPoses.RECOIL, pose(k, NONE, 100).recoil, EPS);
        assertEquals(StandInPoses.RECOIL / 2, pose(k, NONE, 103).recoil, EPS);
        assertEquals(0f, pose(k, NONE, 106).recoil, EPS, "over at recoil_ticks exactly");
        StandInPose none = new StandInPose();
        new Poser().pose(k, NONE, t(101), FEEL, 0, none);
        assertEquals(0f, none.recoil, EPS, "no recoil length, no recoil");
    }

    @Test
    void theGuardTurnsToItsYaw_betweenSteps_theShortWayRound() {
        Track k = new Track();
        k.state(State.GUARD, 90);
        k.guardYaw(250, 99); // 351.6°
        k.guardYaw(2, 100); // 2.8°: the short way through 0
        StandInPose mid = pose(k, NONE, 99.5);
        assertEquals(1f, mid.guardWeight, EPS);
        assertEquals(357.1875f, Poser.wrap(mid.guardYaw), 0.001f);
        assertEquals(2.8125f, pose(k, NONE, 100).guardYaw, 0.001f);
        Track idle = new Track();
        idle.guardYaw(64, 100);
        assertEquals(0f, pose(idle, NONE, 101).guardWeight, EPS, "out of its guard stance");
    }

    @Test
    void itKeepsWhatLaterSlicesUse() {
        Track k = new Track();
        assertFalse(k.targets());
        k.targeting(7, 100);
        assertTrue(k.targets());
        assertEquals(7, k.target());
        k.targeting(-1, 101);
        assertFalse(k.targets());
    }
}
