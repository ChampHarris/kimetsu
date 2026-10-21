package dev.champ.kimetsu.core.demon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The demon's strings (Rules 11.3, 11.5) and its generator, over many draws. */
class StringDrawTest {
    private static final int DRAWS = 100_000;

    private static StringDraw draw() {
        return new StringDraw(Fixtures.demon(), Fixtures.demonAttacks());
    }

    @Test
    void firstStrikesComeByWeight_andEightPercentOfStringsOpenWithAWhiteFeint() {
        StringDraw d = draw();
        Rng rng = new Rng(7);
        List<FrameData> out = new ArrayList<>();
        Map<String, Integer> first = new HashMap<>();
        int feints = 0;
        for (int i = 0; i < DRAWS; i++) {
            boolean feint = d.draw(rng, 100, out);
            if (feint) {
                feints++;
                assertEquals(FrameData.Cue.Type.WHITE, out.get(0).cue().type());
                assertTrue(out.size() >= 2, "a feint opens a real string");
            } else {
                first.merge(out.get(0).id(), 1, Integer::sum);
            }
        }
        assertEquals(0.08, feints / (double) DRAWS, 0.003);
        int real = DRAWS - feints;
        Map<String, Integer> weights = Map.of(
                Fixtures.SWIPE_ID, 52,
                Fixtures.HEAVY_ID, 18,
                Fixtures.LEAP_ID, 11,
                Fixtures.SWEEP_ID, 9,
                Fixtures.GRAB_ID, 10);
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            assertEquals(e.getValue() / 100.0, first.getOrDefault(e.getKey(), 0) / (double) real, 0.006, e.getKey());
        }
    }

    @Test
    void noHeavyFollowsAHeavy_andNothingFollowsALeapOrAGrab() {
        StringDraw d = draw();
        Rng rng = new Rng(11);
        List<FrameData> out = new ArrayList<>();
        int[] lengths = new int[5];
        for (int i = 0; i < DRAWS; i++) {
            boolean feint = d.draw(rng, 100, out);
            for (int k = 1; k < out.size(); k++) {
                String prev = out.get(k - 1).id();
                String next = out.get(k).id();
                assertFalse(prev.equals(Fixtures.HEAVY_ID) && next.equals(Fixtures.HEAVY_ID), "heavy after heavy");
                if (k > 1 || !feint) {
                    assertFalse(prev.equals(Fixtures.LEAP_ID) || prev.equals(Fixtures.GRAB_ID), "after " + prev);
                }
            }
            lengths[out.size()]++;
        }
        assertEquals(0, lengths[0]);
        assertEquals(0, lengths[4]); // a feint counts in its string's length: none is longer than a real one can be
        // 45% are drawn one long; of the longer real ones, a leap or grab first ends some early (21% by weight); a
        // feint
        // opens only strings two or more long, and what follows it always lands
        assertEquals(0.45 + (0.55 - 0.08) * 0.21, lengths[1] / (double) DRAWS, 0.01);
        assertTrue(lengths[3] > 0.1 * DRAWS);
    }

    @Test
    void withTooLittleHungerForALeapItNeverLeaps() {
        StringDraw d = draw();
        Rng rng = new Rng(3);
        List<FrameData> out = new ArrayList<>();
        for (int i = 0; i < DRAWS / 10; i++) {
            d.draw(rng, 4.9, out);
            for (FrameData f : out) {
                assertNotEquals(Fixtures.LEAP_ID, f.id());
            }
        }
    }

    @Test
    void theSameSeedDrawsTheSameStrings_anotherDoesnt() {
        List<String> a = strings(42);
        assertEquals(a, strings(42));
        assertNotEquals(a, strings(43));
    }

    private static List<String> strings(long seed) {
        StringDraw d = draw();
        Rng rng = new Rng(seed);
        List<FrameData> out = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            d.draw(rng, 100, out);
            for (FrameData f : out) {
                all.add(f.id());
            }
            all.add("|");
        }
        return all;
    }

    @Test
    void theGeneratorsGaussianHasItsMeanAndDeviation_andItsBoundsHold() {
        Rng rng = new Rng(1);
        double sum = 0;
        double sq = 0;
        for (int i = 0; i < DRAWS; i++) {
            double g = rng.nextGaussian();
            sum += g;
            sq += g * g;
            int k = rng.nextInt(5);
            assertTrue(k >= 0 && k < 5);
            double u = rng.nextDouble();
            assertTrue(u >= 0 && u < 1);
        }
        assertEquals(0, sum / DRAWS, 0.01);
        assertEquals(1, Math.sqrt(sq / DRAWS), 0.01);
        assertEquals(new Rng(9).nextLong(), new Rng(9).nextLong());
    }
}
