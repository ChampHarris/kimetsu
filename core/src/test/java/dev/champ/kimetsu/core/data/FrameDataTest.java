package dev.champ.kimetsu.core.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData.BeatLock;
import dev.champ.kimetsu.core.data.FrameData.Cancel;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.Displacement;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Into;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The frame data, style and machine-rule files reject bad values, naming the file and the field (Data & State). */
class FrameDataTest {

    private static void rejects(Executable build, String... inMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, build);
        for (String part : inMessage) {
            assertTrue(e.getMessage().contains(part), "\"" + e.getMessage() + "\" should name " + part);
        }
    }

    private static final Sweep BOX = Fixtures.box(0, 1, 1, 0.5, 0.5, 0.5, Sweep.Pitch.LOOK);

    private static FrameData frame(int windup, int active, int recovery, double cost, List<Cancel> cancels) {
        return new FrameData(
                "kimetsu:test/f",
                AttackClass.HEAVY,
                windup,
                active,
                recovery,
                cost,
                FrameData.Judge.PRESS,
                cancels,
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null,
                Cue.NONE,
                null,
                List.of(Fixtures.hit(0, 0, 1, 1, 1, 0)),
                BOX,
                List.of());
    }

    /** An attack with the hit-system fields given, the rest plain. */
    private static FrameData attack(
            AttackClass attackClass,
            FrameData.Judge judge,
            Cue cue,
            @Nullable BeatLock lock,
            List<HitWindow> hits,
            @Nullable Sweep sweep,
            List<Displacement> moves) {
        return new FrameData(
                "kimetsu:test/f",
                attackClass,
                10,
                3,
                12,
                0,
                judge,
                List.of(),
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null,
                cue,
                lock,
                hits,
                sweep,
                moves);
    }

    private static HitWindow window(int from, int to) {
        return Fixtures.hit(from, to, 1, 1, 1, 0);
    }

    @Test
    void theSpecsFrameDataIsValid() {
        assertDoesNotThrow(Fixtures::water);
        assertDoesNotThrow(Fixtures::machine);
    }

    @Test
    void timingMustBeNonNegativeWithAtLeastOneActiveTick() {
        rejects(() -> frame(-1, 2, 6, 0, List.of()), "kimetsu:test/f", "timing.windup");
        rejects(() -> frame(3, 0, 6, 0, List.of()), "kimetsu:test/f", "timing.active");
        rejects(() -> frame(3, 2, -1, 0, List.of()), "kimetsu:test/f", "timing.recovery");
        rejects(() -> frame(3, 2, 6, -0.5, List.of()), "kimetsu:test/f", "cost.breath");
    }

    @Test
    void aCancelMustLieInsideItsPhase() {
        rejects(() -> frame(6, 3, 17, 0, List.of(new Cancel(Into.FEINT, Phase.WINDUP, 6, -1))), "cancels.feint.from");
        rejects(() -> frame(6, 3, 17, 0, List.of(new Cancel(Into.FEINT, Phase.WINDUP, 0, 7))), "cancels.feint.until");
        rejects(() -> frame(6, 3, 17, 0, List.of(new Cancel(Into.FEINT, Phase.WINDUP, 2, 2))), "cancels.feint.until");
        rejects(() -> frame(6, 3, 17, 0, List.of(new Cancel(Into.STEP, Phase.RECOVERY, -1, -1))), "cancels.step.from");
    }

    @Test
    void aFeintCancelsOnlyAWindupAndTheChainOpensOnlyAfterIt() {
        rejects(() -> frame(6, 3, 17, 0, List.of(new Cancel(Into.FEINT, Phase.RECOVERY, 0, -1))), "cancels.feint");
        rejects(
                () -> frame(3, 2, 6, 0, List.of(new Cancel(Into.LIGHT_CHAIN, Phase.WINDUP, 0, -1))),
                "cancels.light_chain");
    }

    @Test
    void aContextualFormNeedsAPositiveFiniteFall() {
        for (double fall : new double[] {0, Double.POSITIVE_INFINITY, Double.NaN}) {
            rejects(
                    () -> new FrameData(
                            "kimetsu:test/f",
                            AttackClass.HEAVY,
                            8,
                            3,
                            15,
                            25,
                            FrameData.Judge.RELEASE,
                            List.of(),
                            Armour.NONE,
                            Armour.LIGHT,
                            true,
                            new FrameData.Contextual(fall),
                            null,
                            Cue.NONE,
                            null,
                            List.of(Fixtures.hit(0, 0, 1, 1, 1, 0)),
                            BOX,
                            List.of()),
                    "kimetsu:test/f",
                    "contextual.min_fall_blocks");
        }
    }

    @Test
    void theCancelWindowsReadAsTheSpecWritesThem() {
        FrameData first = Fixtures.water().radial(0);
        assertTrue(first.cancels(Into.FEINT, Phase.WINDUP, 0));
        assertTrue(first.cancels(Into.FEINT, Phase.WINDUP, 2));
        assertTrue(!first.cancels(Into.FEINT, Phase.WINDUP, 3)); // the first half of a 6-tick windup
        assertTrue(first.cancels(Into.STEP, Phase.RECOVERY, 16)); // to the recovery's end
        FrameData chain1 = Fixtures.water().chain(0);
        assertEquals(4, chain1.opens(Into.LIGHT_CHAIN)); // the last active tick of 3 / 2 / 6
        assertEquals(-1, Fixtures.water().chain(2).opens(Into.LIGHT_CHAIN)); // Chain 3 completes the chain
    }

    @Test
    void aStyleRejectsBadValues() {
        rejects(
                () -> new Style("kimetsu:test", 0, "s", List.of("c"), List.of("r"), null, null),
                "kimetsu:test",
                "beat_ticks");
        rejects(() -> new Style("kimetsu:test", 13, "s", List.of(), List.of("r"), null, null), "light_chain");
        List<String> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add("r" + i);
        }
        rejects(() -> new Style("kimetsu:test", 13, "s", List.of("c"), nine, null, null), "radial");
        rejects(() -> new Style.GuardCancel(-1, 5), "guard_cancel.after_miss");
    }

    @Test
    void aMovesetNamesTheReferenceThatDoesntExist() {
        Style water = Fixtures.water().style();
        List<FrameData> withoutTheEighth = new ArrayList<>();
        for (String id : List.of(
                "kimetsu:light_chain/1",
                "kimetsu:light_chain/2",
                "kimetsu:light_chain/3",
                "kimetsu:pommel",
                "kimetsu:water/step",
                "kimetsu:water/first_form",
                "kimetsu:water/first_form_mushin",
                "kimetsu:water/second_form",
                "kimetsu:water/fourth_form",
                "kimetsu:water/fourth_form_mushin")) {
            withoutTheEighth.add(Fixtures.water().frame(id));
        }
        rejects(() -> new Moveset(water, withoutTheEighth, "kimetsu:pommel"), "radial", "kimetsu:water/eighth_form");
        List<FrameData> noFinisher = new ArrayList<>(withoutTheEighth);
        noFinisher.remove(Fixtures.water().frame("kimetsu:water/first_form_mushin"));
        noFinisher.add(Fixtures.water().frame("kimetsu:water/eighth_form"));
        rejects(() -> new Moveset(water, noFinisher, "kimetsu:pommel"), "kimetsu:water/first_form", "mushin");
        List<FrameData> twice = new ArrayList<>(withoutTheEighth);
        twice.add(Fixtures.water().frame("kimetsu:pommel"));
        rejects(() -> new Moveset(water, twice, "kimetsu:pommel"), "kimetsu:pommel", "twice");
    }

    @Test
    void aMovesetsDataMustBeJudgedTheWayTheMachineJudgesIt() {
        Moveset water = Fixtures.water();
        List<FrameData> frames = new ArrayList<>();
        for (String id : List.of(
                "kimetsu:light_chain/1",
                "kimetsu:light_chain/2",
                "kimetsu:light_chain/3",
                "kimetsu:pommel",
                "kimetsu:water/step",
                "kimetsu:water/first_form_mushin",
                "kimetsu:water/second_form",
                "kimetsu:water/fourth_form",
                "kimetsu:water/fourth_form_mushin",
                "kimetsu:water/eighth_form")) {
            frames.add(water.frame(id));
        }
        FrameData first = water.frame("kimetsu:water/first_form");
        frames.add(new FrameData(
                first.id(),
                first.attackClass(),
                first.windup(),
                first.active(),
                first.recovery(),
                first.breathCost(),
                FrameData.Judge.PRESS,
                first.cancels(),
                first.windupArmour(),
                first.activeArmour(),
                first.recoveryBreath(),
                first.contextual(),
                first.mushin(),
                first.cue(),
                first.beatLock(),
                List.of(Fixtures.hit(0, 2, 6, 15, 8, 0.35)), // a press-judged attack can't cut horizontally
                first.sweep(),
                first.displacement()));
        rejects(() -> new Moveset(water.style(), frames, "kimetsu:pommel"), "radial", "first_form", "release");
    }

    @Test
    void mushinFiresTheFinisherOrTheFormItself() {
        Moveset water = Fixtures.water();
        assertEquals(
                "kimetsu:water/first_form_mushin",
                water.atMushin(water.radial(0)).id());
        assertSame(water.radial(1), water.atMushin(water.radial(1))); // the 2nd: the ×2.0 stand-in
        assertEquals(null, water.radial(4));
    }

    @Test
    void hitWindowsLieInsideTheActiveFramesInOrder() {
        Sweep box = BOX;
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 3)),
                        box,
                        List.of()),
                "kimetsu:test/f",
                "hits[0].to");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 1), window(1, 2)),
                        box,
                        List.of()),
                "hits[1].from");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(Fixtures.hit(0, 0, -1, 0, 0, 0)),
                        box,
                        List.of()),
                "hits[0].damage");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(new HitWindow(0, 0, 1, 1, 1, 0, false, true, false, 0, 0)),
                        box,
                        List.of()),
                "hits[0].wall_splat");
        rejects(
                () -> attack(AttackClass.LIGHT, FrameData.Judge.PRESS, Cue.NONE, null, List.of(), box, List.of()),
                "hits must list");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        null,
                        List.of()),
                "sweep is missing");
        rejects(
                () -> attack(
                        AttackClass.STEP,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        null,
                        List.of()),
                "a Step has no hits");
    }

    @Test
    void onlyAFormCutsHorizontallyAndItsSweepStaysLevel() {
        HitWindow cut = Fixtures.cut(0, 0, 1, 1, 1, 0);
        rejects(
                () -> attack(
                        AttackClass.HEAVY,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(cut),
                        Fixtures.box(0, 1, 1, 1, 1, 1, Sweep.Pitch.LOOK_CLAMPED),
                        List.of()),
                "hits[0].horizontal",
                "Slayer forms");
        rejects(
                () -> attack(
                        AttackClass.HEAVY,
                        FrameData.Judge.RELEASE,
                        Cue.NONE,
                        null,
                        List.of(cut),
                        Fixtures.box(0, 1, 1, 1, 1, 1, Sweep.Pitch.LOOK),
                        List.of()),
                "hits[0].horizontal",
                "pitch");
        assertDoesNotThrow(() -> attack(
                AttackClass.HEAVY,
                FrameData.Judge.RELEASE,
                Cue.NONE,
                null,
                List.of(cut),
                Fixtures.box(0, 1, 1, 1, 1, 1, Sweep.Pitch.LOOK_CLAMPED),
                List.of()));
    }

    @Test
    void onlyAGrabDrainsAndEveryGrabDoes() {
        HitWindow drains = new HitWindow(0, 0, 1, 0, 0, 0, false, false, false, 0, 15);
        rejects(
                () -> attack(AttackClass.LIGHT, FrameData.Judge.PRESS, Cue.NONE, null, List.of(drains), BOX, List.of()),
                "hits[0].grab_drain");
        rejects(
                () -> attack(
                        AttackClass.GRAB,
                        FrameData.Judge.PRESS,
                        new Cue(Cue.Type.PURPLE, 8, false),
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "hits[0].grab_drain");
    }

    @Test
    void aCueShowsDuringTheWindupInItsOwnColour() {
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        new Cue(Cue.Type.NONE, 4, false),
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "cue.lead");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        new Cue(Cue.Type.WHITE, 11, false),
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "cue.lead",
                "at most 10");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        new Cue(Cue.Type.PURPLE, 8, false),
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "cue.type",
                "purple");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        new Cue(Cue.Type.WHITE, 8, true),
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "cue.charged");
    }

    @Test
    void aBeatLockNeedsACueAHoldBeforeItsMinimumAndOneWindowFromTheImpact() {
        Cue white = new Cue(Cue.Type.WHITE, 10, false);
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        new BeatLock(BeatLock.Mode.TARGET, 9, 1, 3, false),
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "timing.beat_lock needs a cue");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        white,
                        new BeatLock(BeatLock.Mode.TARGET, 11, 1, 3, false),
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "timing.windup must be the larger",
                "windup_min (11)");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        white,
                        new BeatLock(BeatLock.Mode.TARGET, 9, 1, 9, false),
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "timing.beat_lock.hold_frame");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        new Cue(Cue.Type.RED, 10, false),
                        new BeatLock(BeatLock.Mode.TARGET, 9, 1, 3, true),
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "hold_guards");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        white,
                        new BeatLock(BeatLock.Mode.TARGET, 9, 1, 3, false),
                        List.of(window(1, 1)),
                        BOX,
                        List.of()),
                "hits must be one window");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        white,
                        new BeatLock(BeatLock.Mode.TARGET, 9, 1, 3, false),
                        List.of(window(0, 0)),
                        BOX,
                        List.of(new Displacement(Phase.WINDUP, 2, 5, 0.25, 0, 0))),
                "displacement[0].from",
                "hold frame");
    }

    @Test
    void aSweepIsABoxOrAnArcWithItsRadius() {
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        Fixtures.box(0, 1, 1, 0, 1, 1, Sweep.Pitch.LOOK),
                        List.of()),
                "sweep.half");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        new Sweep(Sweep.Shape.ARC, new Sweep.Box(0, 1, 1, 1, 1, 1), Sweep.Pitch.LOOK, 0, "a"),
                        List.of()),
                "sweep.radius");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        new Sweep(Sweep.Shape.ARC, new Sweep.Box(0, 1, 1, 1, 1, 1), Sweep.Pitch.LOOK, 0.1, null),
                        List.of()),
                "sweep.arc");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        new Sweep(Sweep.Shape.OBB, new Sweep.Box(0, 1, 1, 1, 1, 1), Sweep.Pitch.LOOK, 0.1, null),
                        List.of()),
                "sweep.radius");
    }

    @Test
    void displacementStaysInsideItsPhaseWithoutOverlap() {
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of(new Displacement(Phase.ACTIVE, 0, 3, 0.2, 0, 0))),
                "displacement[0].to");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of(
                                new Displacement(Phase.ACTIVE, 0, 1, 0.2, 0, 0),
                                new Displacement(Phase.ACTIVE, 1, 2, 0.2, 0, 0))),
                "displacement[1]",
                "overlaps");
    }

    @Test
    void numbersMustBeFiniteAndNotNegative() {
        for (HitWindow bad : List.of(
                Fixtures.hit(0, 0, Double.POSITIVE_INFINITY, 1, 1, 0),
                Fixtures.hit(0, 0, 1, -1, 1, 0),
                Fixtures.hit(0, 0, 1, 1, -1, 0),
                Fixtures.hit(0, 0, 1, 1, 1, -0.5),
                new HitWindow(0, 0, 1, 1, 1, 0, false, false, false, -8, 0))) {
            rejects(
                    () -> attack(
                            AttackClass.LIGHT, FrameData.Judge.PRESS, Cue.NONE, null, List.of(bad), BOX, List.of()),
                    "kimetsu:test/f",
                    "hits[0].");
        }
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        Fixtures.box(Double.NaN, 1, 1, 1, 1, 1, Sweep.Pitch.LOOK),
                        List.of()),
                "sweep.center");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        Fixtures.box(0, 1, 1, Double.POSITIVE_INFINITY, 1, 1, Sweep.Pitch.LOOK),
                        List.of()),
                "sweep.half");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        new Sweep(Sweep.Shape.OBB, new Sweep.Box(0, 1, 1, 1, 1, 1), Sweep.Pitch.LOOK, 0, "an arc"),
                        List.of()),
                "sweep.arc is an arc's");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        new Sweep(
                                Sweep.Shape.ARC,
                                new Sweep.Box(0, 1, 1, 1, 1, 1),
                                Sweep.Pitch.LOOK,
                                Double.POSITIVE_INFINITY,
                                "an arc"),
                        List.of()),
                "sweep.radius");
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        Cue.NONE,
                        null,
                        List.of(window(0, 0)),
                        BOX,
                        List.of(new Displacement(Phase.ACTIVE, 0, 1, Double.NaN, 0, 0))),
                "displacement[0].",
                "finite");
    }

    @Test
    void aBeatLockedStrikeWindsUpForTheLargerOfItsMinimumAndItsCue() {
        Cue lead10 = new Cue(Cue.Type.WHITE, 10, false);
        assertDoesNotThrow(() -> attack(
                AttackClass.LIGHT,
                FrameData.Judge.PRESS,
                lead10,
                new BeatLock(BeatLock.Mode.TARGET, 9, 1, 3, false),
                List.of(window(0, 0)),
                BOX,
                List.of()));
        Cue lead8 = new Cue(Cue.Type.WHITE, 8, false); // windup 10 but max(9, 8) = 9: the glint would show late
        rejects(
                () -> attack(
                        AttackClass.LIGHT,
                        FrameData.Judge.PRESS,
                        lead8,
                        new BeatLock(BeatLock.Mode.TARGET, 9, 1, 3, false),
                        List.of(window(0, 0)),
                        BOX,
                        List.of()),
                "timing.windup must be the larger");
    }

    /** A beat-locked white strike that winds up for exactly its cue lead. */
    private static FrameData quick(int lead) {
        return new FrameData(
                "kimetsu:test/quick",
                AttackClass.LIGHT,
                lead,
                2,
                10,
                0,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.ALL,
                Armour.NONE,
                false,
                null,
                null,
                new Cue(Cue.Type.WHITE, lead, false),
                new BeatLock(BeatLock.Mode.TARGET, lead, 1, 3, false),
                List.of(window(0, 0)),
                BOX,
                List.of());
    }

    @Test
    void strikeRulesHoldAMobsCueToItsMinimumLead() {
        StrikeRules rules = Fixtures.strikes();
        for (FrameData f : List.of(
                Fixtures.swipe(),
                Fixtures.heavy(),
                Fixtures.leap(),
                Fixtures.lowSweep(),
                Fixtures.grab(),
                Fixtures.guardCounter())) {
            assertDoesNotThrow(() -> rules.check(f), f.id());
        }
        rejects(() -> rules.check(quick(7)), "kimetsu:test/quick", "cue.lead", "at least 8");
        assertDoesNotThrow(() -> rules.check(quick(8)), "exactly the minimum lead is enough");
        assertDoesNotThrow(() -> rules.check(Fixtures.water().chain(0))); // a player's attack has no cue to hold
        rejects(
                () -> new StrikeRules(0, 8, 2, 120, 4, 8, 0.07, 8, 10, 6, 1.0, Fixtures.clash()),
                "strikes_per_target_per_beat");
        rejects(() -> new StrikeRules(2, 0, 2, 120, 4, 8, 0.07, 8, 10, 6, 1.0, Fixtures.clash()), "min_cue_lead_ticks");
        rejects(
                () -> new StrikeRules(2, 8, -1, 120, 4, 8, 0.07, 8, 10, 6, 1.0, Fixtures.clash()),
                "commit_margin_ticks");
        rejects(() -> new StrikeRules(2, 8, 2, 400, 4, 8, 0.07, 8, 10, 6, 1.0, Fixtures.clash()), "guard_arc_deg");
        rejects(
                () -> new StrikeRules(2, 8, 2, 240, 4, 8, 0.07, 8, 10, 6, 1.0, Fixtures.clash()),
                "guard_arc_deg"); // past what Facing tests
        rejects(
                () -> new StrikeRules(2, 8, 2, 120, -4, 8, 0.07, 8, 10, 6, 1.0, Fixtures.clash()),
                "deflect_defender_posture");
        rejects(
                () -> new StrikeRules(2, 8, 2, 120, 4, Double.NaN, 0.07, 8, 10, 6, 1.0, Fixtures.clash()),
                "deflect_attacker_posture");
        rejects(
                () -> new StrikeRules(2, 8, 2, 120, 4, 8, 1.5, 8, 10, 6, 1.0, Fixtures.clash()),
                "perfect_attacker_share");
        rejects(
                () -> new StrikeRules(2, 8, 2, 120, 4, 8, 0.07, -1, 10, 6, 1.0, Fixtures.clash()),
                "perfect_recovery_ticks");
        rejects(() -> new StrikeRules(2, 8, 2, 120, 4, 8, 0.07, 8, -1, 6, 1.0, Fixtures.clash()), "pommel_stun_ticks");
        rejects(() -> new StrikeRules(2, 8, 2, 120, 4, 8, 0.07, 8, 10, 0, 1.0, Fixtures.clash()), "grab_hold_beats");
        rejects(() -> new StrikeRules(2, 8, 2, 120, 4, 8, 0.07, 8, 10, 6, -1, Fixtures.clash()), "throw_damage");
        rejects(() -> new ClashRules(0, 50, 700, 100, 15, 300), "clash.flash_half_beats");
        rejects(() -> new ClashRules(3, 50, 700, 700, 15, 300), "clash.false_start_ms");
    }

    @Test
    void theDemonsStrikesAreValidAndWindUpForTheirCue() {
        for (FrameData f : List.of(
                Fixtures.swipe(),
                Fixtures.heavy(),
                Fixtures.leap(),
                Fixtures.lowSweep(),
                Fixtures.grab(),
                Fixtures.guardCounter())) {
            BeatLock lock = f.beatLock();
            assertTrue(
                    lock != null
                            && f.windup() == Math.max(lock.windupMin(), f.cue().lead()),
                    f.id());
        }
        assertEquals(1, Fixtures.water().frame("kimetsu:water/fourth_form").windowAt(4));
        assertEquals(-1, Fixtures.water().frame("kimetsu:light_chain/1").windowAt(2));
    }

    @Test
    void theMovesetTellsAFinisherFromAFormWithout() {
        Moveset water = Fixtures.water();
        assertTrue(water.isFinisher(water.frame("kimetsu:water/first_form_mushin")));
        assertTrue(water.isFinisher(water.frame("kimetsu:water/fourth_form_mushin")));
        assertTrue(!water.isFinisher(water.radial(1))); // the 2nd fires itself at Mushin: the stand-in
        assertTrue(!water.isFinisher(water.radial(0)));
    }

    @Test
    void aFinisherFiresOnlyFromItsForm() {
        Moveset water = Fixtures.water();
        Style s = water.style();
        List<FrameData> all = new ArrayList<>();
        for (String id : List.of(
                "kimetsu:light_chain/1",
                "kimetsu:light_chain/2",
                "kimetsu:light_chain/3",
                "kimetsu:pommel",
                "kimetsu:water/step",
                "kimetsu:water/first_form",
                "kimetsu:water/first_form_mushin",
                "kimetsu:water/second_form",
                "kimetsu:water/fourth_form",
                "kimetsu:water/fourth_form_mushin",
                "kimetsu:water/eighth_form")) {
            all.add(water.frame(id));
        }
        Style onTheRadial = new Style(
                s.id(),
                s.beatTicks(),
                s.step(),
                s.lightChain(),
                List.of(
                        "kimetsu:water/first_form",
                        "kimetsu:water/second_form",
                        "kimetsu:water/fourth_form_mushin",
                        "kimetsu:water/eighth_form"),
                s.fallingForm(),
                s.guardCancel());
        rejects(
                () -> new Moveset(onTheRadial, all, "kimetsu:pommel"),
                "radial",
                "kimetsu:water/fourth_form_mushin",
                "finisher");
        Style falling = new Style(
                s.id(),
                s.beatTicks(),
                s.step(),
                s.lightChain(),
                s.radial(),
                "kimetsu:water/first_form_mushin",
                s.guardCancel());
        rejects(
                () -> new Moveset(falling, all, "kimetsu:pommel"),
                "contextual.falling",
                "kimetsu:water/first_form_mushin",
                "finisher");
        assertDoesNotThrow(() -> new Moveset(s, all, "kimetsu:pommel"));
    }

    @Test
    void hitRulesRejectBadValues() {
        assertDoesNotThrow(Fixtures::hits);
        rejects(() -> new HitRules(0, 0.15, 0.25, 30, 30, 20, 4, 1, 2, 2, 0.75, 0.7), "sub_steps");
        rejects(() -> new HitRules(3, 1.5, 0.25, 30, 30, 20, 4, 1, 2, 2, 0.75, 0.7), "combo_step");
        rejects(() -> new HitRules(3, 0.15, 0, 30, 30, 20, 4, 1, 2, 2, 0.75, 0.7), "combo_floor");
        rejects(() -> new HitRules(3, 0.15, 0.25, 0, 30, 20, 4, 1, 2, 2, 0.75, 0.7), "combo_window_ticks");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 95, 20, 4, 1, 2, 2, 0.75, 0.7), "horizontal_pitch_clamp_deg");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 30, 0, 4, 1, 2, 2, 0.75, 0.7), "max_turn_deg_per_tick");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 30, 20, 0, 1, 2, 2, 0.75, 0.7), "teleport_blocks");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, -1, 2, 2, 0.75, 0.7), "reach_margin_blocks");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, 1, 3, 2, 0.75, 0.7), "los_rays");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, 1, 2, 0.5, 0.75, 0.7), "mushin_stand_in");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, 1, 2, 2, 0, 0.7), "auto_rhythm_scale");
        rejects(() -> new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, 1, 2, 2, 0.75, 1.5), "arc_envelope_share");
        rejects(
                () -> new HitRules(3, 0.15, 0.25, 30, 30, 20, Double.POSITIVE_INFINITY, 1, 2, 2, 0.75, 0.7),
                "teleport_blocks");
        assertEquals(150, Fixtures.hits().comboStepMilli());
        assertEquals(250, Fixtures.hits().comboFloorMilli());
    }

    @Test
    void machineRulesRejectBadValues() {
        rejects(() -> new MachineRules(-1, 150, 2, 1, 10, 60, 6, 4, 1, 13, 20, 8, 10, 3, 16), "buffer_ticks");
        rejects(() -> new MachineRules(3, 0, 2, 1, 10, 60, 6, 4, 1, 13, 20, 8, 10, 3, 16), "radial_hold_ms");
        rejects(() -> new MachineRules(3, 150, 0, 1, 10, 60, 6, 4, 1, 13, 20, 8, 10, 3, 16), "radial_limit_beats");
        rejects(() -> new MachineRules(3, 150, 2, -1, 10, 60, 6, 4, 1, 13, 20, 8, 10, 3, 16), "guard_hold_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, -1, 60, 6, 4, 1, 13, 20, 8, 10, 3, 16), "feint_breath");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, -1, 6, 4, 1, 13, 20, 8, 10, 3, 16), "feint_doubling_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, -1, 4, 1, 13, 20, 8, 10, 3, 16), "stumble_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, -1, 1, 13, 20, 8, 10, 3, 16), "tech_before_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, 4, -1, 13, 20, 8, 10, 3, 16), "tech_after_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, 4, 1, -1, 20, 8, 10, 3, 16), "tech_lockout_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, 4, 1, 13, -1, 8, 10, 3, 16), "knockdown_down_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, 4, 1, 13, 20, -1, 10, 3, 16), "knockdown_rise_ticks");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, 4, 1, 13, 20, 8, -1, 3, 16), "juggle_cut_breath");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, 4, 1, 13, 20, 8, 10, 0, 16), "grab_escape_inputs");
        rejects(() -> new MachineRules(3, 150, 2, 1, 10, 60, 6, 4, 1, 13, 20, 8, 10, 3, 1), "history_ticks");
    }
}
