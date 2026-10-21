package dev.champ.kimetsu.core.hit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.Arc;
import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Compiling a sweep checks what one file can't: an arc's samples and envelope, a box's gaps (Rules 7.1–7.2, R6-08). */
class SweepShapesTest {
    private static final HitRules RULES = Fixtures.hits();

    private static void rejects(Executable build, String... inMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, build);
        for (String part : inMessage) {
            assertTrue(e.getMessage().contains(part), "\"" + e.getMessage() + "\" should name " + part);
        }
    }

    private static FrameData attack(Sweep sweep) {
        return new FrameData(
                "kimetsu:test/f",
                AttackClass.HEAVY,
                0,
                1,
                5,
                0,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null,
                Cue.NONE,
                null,
                List.of(Fixtures.hit(0, 0, 1, 1, 1, 0)),
                sweep,
                List.of());
    }

    private static Sweep arcSweep(String id) {
        return new Sweep(Sweep.Shape.ARC, new Sweep.Box(0, 1.2, 1.5, 0.5, 0.5, 0.6), Sweep.Pitch.LEVEL, 0.1, id);
    }

    private static List<Arc.Sample> held(int count, double z) {
        List<Arc.Sample> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new Arc.Sample(0, 1.2, z, 0, 1.2, z + 1));
        }
        return out;
    }

    @Test
    void anArcMustBeTheOneItsSweepNamesSampledEverySubStep() {
        FrameData f = attack(arcSweep("kimetsu:test/arc"));
        rejects(() -> SweepShapes.compile(f, null, 1.62, RULES), "sweep.arc", "isn't loaded");
        rejects(
                () -> SweepShapes.compile(f, new Arc("kimetsu:test/other", 3, held(4, 1)), 1.62, RULES),
                "isn't loaded");
        rejects(
                () -> SweepShapes.compile(f, new Arc("kimetsu:test/arc", 2, held(3, 1)), 1.62, RULES),
                "samples 2 times a tick");
        rejects(() -> SweepShapes.compile(f, new Arc("kimetsu:test/arc", 3, held(3, 1)), 1.62, RULES), "need 4");
        assertDoesNotThrow(() -> SweepShapes.compile(f, new Arc("kimetsu:test/arc", 3, held(4, 1)), 1.62, RULES));
    }

    @Test
    void anArcMustStayInsideItsEnvelope() {
        FrameData f = attack(arcSweep("kimetsu:test/arc"));
        // the box runs z 0.9 to 2.1: a blade from z 1.5 to 2.5 leaves half its sampled points outside
        rejects(
                () -> SweepShapes.compile(f, new Arc("kimetsu:test/arc", 3, held(4, 1.5)), 1.62, RULES),
                "kimetsu:test/arc",
                "inside its box",
                "70%");
        HitRules lenient = new HitRules(3, 0.15, 0.25, 30, 30, 20, 4, 1, 2, 2.0, 0.75, 0.5);
        assertDoesNotThrow(() -> SweepShapes.compile(f, new Arc("kimetsu:test/arc", 3, held(4, 1.5)), 1.62, lenient));
    }

    @Test
    void theGapCheckAcceptsABoxJustUnderItsLimitAndRejectsOneJustOver() {
        double h = 0.25; // a level cube's half extent: its thickness is 2h
        double step = Math.toRadians(RULES.maxTurnDegPerTick()) / RULES.subSteps();
        for (double share : new double[] {0.99, 1.01}) {
            double corner = share * 2 * h / step; // the far corner's turning radius for that share of the thickness
            double cz = Math.sqrt(corner * corner - h * h) - h;
            FrameData f = attack(Fixtures.box(0, 1, cz, h, h, h, Sweep.Pitch.LEVEL));
            if (share < 1) {
                assertDoesNotThrow(() -> SweepShapes.compile(f, null, 1.62, RULES));
            } else {
                rejects(() -> SweepShapes.compile(f, null, 1.62, RULES), "sweep.half", "slip");
            }
        }
    }

    @Test
    void theReachCoversALowerEyeAndAPitchedSweep() {
        SweepShape level = SweepShapes.compile(
                attack(Fixtures.box(0, 1.2, 1.5, 0.9, 0.6, 1.1, Sweep.Pitch.LEVEL)), null, 1.62, RULES);
        SweepShape pitched = SweepShapes.compile(
                attack(Fixtures.box(0, 1.2, 1.5, 0.9, 0.6, 1.1, Sweep.Pitch.LOOK)), null, 1.62, RULES);
        // a corner 2.93 from the eyes, which stand 1.62 up: turned straight up it's 4.55 from the feet
        assertTrue(pitched.reach() >= Math.sqrt(0.81 + 1.02 * 1.02 + 6.76) + 1.62 - 1e-9);
        assertTrue(level.reach() >= Math.sqrt(0.81 + 1.8 * 1.8 + 6.76) - 1e-9); // its farthest corner, unturned
        rejects(
                () -> SweepShapes.compile(attack(Fixtures.box(0, 1, 1, 1, 1, 1, Sweep.Pitch.LOOK)), null, -1, RULES),
                "eye height");
    }
}
