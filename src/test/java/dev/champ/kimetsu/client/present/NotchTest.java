package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import org.junit.jupiter.api.Test;

/**
 * The impact notch (spec:582 and :585, D-123's craft call, D-141's call 9): at assist level 1 or higher, a gap in the
 * closing ring from the later of a strike's cue and one beat before its impact, to its impact, on the scheduled time.
 * So it marks the one closing that lands it, never two (a lead longer than a beat), and never before its cue.
 */
class NotchTest {
    private static final long BEAT = Micros.ofTicks(13);

    @Test
    void itShowsFromTheCueToTheImpact_whenTheLeadIsUnderABeat() {
        long cue = Micros.ofTicks(100);
        long impact = Micros.ofTicks(110); // a 10-tick lead on a 13-tick beat
        assertEquals(cue, Notch.from(cue, impact, BEAT));
        assertFalse(Notch.shows(cue - 1, cue, impact, BEAT, 1));
        assertTrue(Notch.shows(cue, cue, impact, BEAT, 1));
        assertTrue(Notch.shows(impact, cue, impact, BEAT, 1));
        assertFalse(Notch.shows(impact + 1, cue, impact, BEAT, 1));
    }

    @Test
    void aLeadLongerThanABeatShowsOnlyItsLastBeat() {
        long cue = Micros.ofTicks(100);
        long impact = Micros.ofTicks(114); // the guard counter's 14-tick lead on Water's 13
        assertEquals(Micros.ofTicks(101), Notch.from(cue, impact, BEAT), "impact − 13 ticks");
        assertFalse(Notch.shows(Micros.ofTicks(101) - 1, cue, impact, BEAT, 1));
        assertTrue(Notch.shows(Micros.ofTicks(101), cue, impact, BEAT, 1));
    }

    @Test
    void assistZeroShowsNone_everyLevelFromOneDoes() {
        long cue = Micros.ofTicks(100);
        long impact = Micros.ofTicks(110);
        assertFalse(Notch.shows(impact, cue, impact, BEAT, 0));
        for (int level = 1; level <= 3; level++) {
            assertTrue(Notch.shows(impact, cue, impact, BEAT, level), "level " + level);
        }
    }
}
