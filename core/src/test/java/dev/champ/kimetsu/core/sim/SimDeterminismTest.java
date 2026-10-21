package dev.champ.kimetsu.core.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Determinism (spec Tests): a recorded bot fight (to its end or two minutes) replays from its recording alone, with no
 * bots, to the same events and the same end state, so a fight log reproduces what happened; the same seed fights the
 * same fight, and another seed another. Two players, one each side of the demon, so co-op's paths (two horizons, two
 * targets, holds, payments from both) are in it. A batch fought in parallel comes out the same as one fought again.
 */
@Tag("sim")
class SimDeterminismTest {
    // one player forces clashes, so the clash paths (its freeze, its end, the share it takes) are in the replay
    private static final List<Duel.Seat> SEATS = List.of(
            new Duel.Seat(Skill.AVERAGE, Bot.Kind.CLASHER, 1, -45),
            new Duel.Seat(Skill.CASUAL, Bot.Kind.HONEST, 2, 45));

    @Test
    void aRecordedTwoMinuteFightReplaysFromItsRecordingToTheSameEventsAndEnd() {
        Duel.Result first = Duel.run(SEATS, 81, 2_400, true);
        assertTrue(
                first.log().size() > 200,
                "a fight with something in it: " + first.log().size() + " events");
        assertTrue(first.log().stream().anyMatch(l -> l.contains("ClashEnded")), "a fight with a clash in it");
        List<String> recorded = new ArrayList<>(first.log());
        recorded.add(first.end());
        assertEquals(recorded, Duel.replay(SEATS, 81, first.recording()));

        Duel.Result again = Duel.run(SEATS, 81, 2_400, true);
        assertEquals(first.log(), again.log());
        assertEquals(first.end(), again.end());
        assertEquals(first.outcome(), again.outcome());
        assertEquals(first.ticks(), again.ticks());
        assertEquals(first.tallies(), again.tallies());
        assertNotEquals(first.log(), Duel.run(SEATS, 82, 2_400, true).log());
    }

    @Test
    void aBatchFoughtInParallelComesOutTheSameAgain() {
        List<Duel.Seat> solo = List.of(Duel.Seat.solo(Skill.CASUAL, Bot.Kind.HONEST));
        assertEquals(
                Batch.of("a", solo, 16, 500).results(),
                Batch.of("a", solo, 16, 500).results());
    }
}
