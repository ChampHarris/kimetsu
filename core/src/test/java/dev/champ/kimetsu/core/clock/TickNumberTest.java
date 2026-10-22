package dev.champ.kimetsu.core.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which engine tick a server tick runs (Rule 1.1, 1.2): guessed in {@code ServerTickEvent.Pre}, before vanilla decides
 * whether the tick runs the game, for the record it publishes; decided in {@code Post} from the game time reached.
 */
class TickNumberTest {
    @Test
    void preGuessesTheGameTimeATickWillReach() {
        assertTrue(TickNumber.runs(false, 0));
        assertFalse(TickNumber.runs(true, 0), "frozen");
        assertTrue(TickNumber.runs(true, 3), "/tick step 3: frozen, three ticks to run");
        assertEquals(101, TickNumber.expected(100, true));
        assertEquals(100, TickNumber.expected(100, false));
    }

    @Test
    void postStepsTheNextGameTime_holdsAStillOne_andStepsTheFirstWhateverItIs() {
        assertEquals(TickNumber.Step.STEP, TickNumber.atEnd(101, 100));
        assertEquals(TickNumber.Step.HOLD, TickNumber.atEnd(100, 100));
        assertEquals(TickNumber.Step.STEP, TickNumber.atEnd(40, TickNumber.NONE));
    }

    @Test
    void gameTimeMovedByAnythingButATickReanchors() {
        assertEquals(TickNumber.Step.REANCHOR, TickNumber.atEnd(150, 100), "moved on");
        assertEquals(TickNumber.Step.REANCHOR, TickNumber.atEnd(51, 100), "moved back");
    }

    /**
     * Pre guessed wrong: another mod's Pre listener froze the game after ours, or stepped it while frozen. Post still
     * holds the one and steps the other exactly once; only the record was wrong.
     */
    @Test
    void aWrongGuessNeverDoubleStepsOrStepsWhileFrozen() {
        long g = TickNumber.expected(100, TickNumber.runs(false, 0)); // guessed it would run: 101
        assertEquals(TickNumber.Step.HOLD, TickNumber.atEnd(100, 100), "frozen after all");
        assertNotEquals(100, g, "so the record it published was wrong");
        g = TickNumber.expected(100, TickNumber.runs(true, 0)); // guessed frozen: 100
        assertEquals(TickNumber.Step.STEP, TickNumber.atEnd(101, 100), "stepped after all");
        assertNotEquals(101, g);
    }

    /**
     * Against a model of vanilla's ticking ({@code TickRateManager.tick} right after Pre, then the overworld advancing
     * its game time only on a tick that runs): through a freeze, a {@code /tick step 3}, an unfreeze and a sprint, the
     * engine steps exactly the game times the world reached, each once and in order, and Pre's guess is always right.
     */
    @Test
    void throughFreezeStepAndSprint_theEngineStepsEveryGameTimeOnceInOrder() {
        long gameTime = 1000;
        boolean frozen = false;
        int toRun = 0;
        long last = TickNumber.NONE;
        List<Long> reached = new ArrayList<>();
        List<Long> stepped = new ArrayList<>();
        for (int t = 0; t < 200; t++) {
            // commands run while vanilla handles packets, before the tick's Pre
            if (t == 30) {
                frozen = true; // /tick freeze
            } else if (t == 60) {
                toRun = 3; // /tick step 3
            } else if (t == 90) {
                frozen = false; // /tick unfreeze
            } else if (t == 120) {
                frozen = true;
                toRun = 0; // /tick freeze while a step runs
            } else if (t == 150) {
                frozen = false; // a sprint unfreezes the game for its length
            }
            long g = TickNumber.expected(gameTime, TickNumber.runs(frozen, toRun)); // Pre
            boolean vanillaRuns = !frozen || toRun > 0; // TickRateManager.tick
            if (toRun > 0) {
                toRun--;
            }
            if (vanillaRuns) {
                gameTime++; // ServerLevel.tickTime, the overworld only
                reached.add(gameTime);
            }
            assertEquals(gameTime, g, "Pre's guess, tick " + t);
            TickNumber.Step s = TickNumber.atEnd(gameTime, last); // Post
            assertNotEquals(TickNumber.Step.REANCHOR, s, "tick " + t);
            if (s == TickNumber.Step.STEP) {
                stepped.add(gameTime);
                last = gameTime;
            }
        }
        assertEquals(reached, stepped, "the game times reached, each once");
        assertEquals(30 + 3 + 30 + 50, reached.size(), "frozen ticks don't step; the three stepped ones do");
    }
}
