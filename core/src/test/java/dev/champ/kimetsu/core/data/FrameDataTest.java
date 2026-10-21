package dev.champ.kimetsu.core.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData.Cancel;
import dev.champ.kimetsu.core.data.FrameData.Into;
import java.util.ArrayList;
import java.util.List;
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
                null);
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
    void aContextualFormNeedsAPositiveFall() {
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
                        new FrameData.Contextual(0),
                        null),
                "kimetsu:test/f",
                "contextual.min_fall_blocks");
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
                first.mushin()));
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
