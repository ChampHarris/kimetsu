package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.client.present.CueTimeline.Look;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.machine.State;
import org.junit.jupiter.api.Test;

/**
 * When each world cue shows, on the frame's scheduled time (D-144's craft call 4; D-145's A1, A6, A8): the white glint
 * from its cue to its impact, its flare for one cue step from a half-beat before the impact (D-134 (4)), a feint's
 * shatter from the later of its cancel and the first frame that knew of it, any other cancel ending it at once; the
 * Thread from its opening tick to its close; the clash flash for its length and the first frame that showed it.
 */
class CueTimelineTest {
    private static final long NONE = CueTimeline.NONE;
    private static final long PERIOD = Micros.ofMillis(650); // Water's beat (m1 data): a half-beat is 325 ms
    private static final long STEP = 1_000_000 / 12; // cue_step_fps 12
    private static final int SHARDS = 4; // the sheet's shatter strip
    private static final long IMPACT = Micros.ofTicks(1_000);

    private static Look glint(long t, int leadTicks) {
        return CueTimeline.glint(
                t, IMPACT - Micros.ofTicks(leadTicks), IMPACT, NONE, false, NONE, PERIOD, STEP, SHARDS);
    }

    private static Look feint(long t, int leadTicks, int breakTicks, long known) {
        return CueTimeline.glint(
                t,
                IMPACT - Micros.ofTicks(leadTicks),
                IMPACT,
                IMPACT - Micros.ofTicks(breakTicks),
                true,
                known,
                PERIOD,
                STEP,
                SHARDS);
    }

    @Test
    void theGlintShowsFromItsCueUntilItsImpact() {
        long cue = IMPACT - Micros.ofTicks(10);
        assertEquals(Look.NONE, glint(cue - 1, 10));
        assertEquals(Look.GLINT, glint(cue, 10));
        assertEquals(Look.GLINT, glint(IMPACT - 1, 10));
        assertEquals(Look.NONE, glint(IMPACT, 10), "the impact ends it");
    }

    @Test
    void theFlareShowsForOneStepFromAHalfBeatBeforeTheImpact_A1() {
        // the swipe (lead 10): 3.5 ticks into its cue; the heavy (lead 14): 7.5; the grab (lead 12): 5.5
        for (int lead : new int[] {10, 12, 14}) {
            long flare = IMPACT - Micros.ofMillis(325);
            assertEquals(Look.GLINT, glint(flare - 1, lead), "lead " + lead);
            assertEquals(Look.FLARE, glint(flare, lead), "lead " + lead);
            assertEquals(Look.FLARE, glint(flare + STEP - 1, lead), "lead " + lead);
            assertEquals(Look.GLINT, glint(flare + STEP, lead), "one cue step only, lead " + lead);
        }
        long swipeCue = IMPACT - Micros.ofTicks(10);
        assertEquals(Micros.ofTicks(7) / 2, IMPACT - Micros.ofMillis(325) - swipeCue, "3.5 ticks in");
        long heavyCue = IMPACT - Micros.ofTicks(14);
        assertEquals(Micros.ofTicks(15) / 2, IMPACT - Micros.ofMillis(325) - heavyCue, "7.5 ticks in");
    }

    @Test
    void aLeadUnderAHalfBeatGetsNoFlare() {
        for (long t = IMPACT - Micros.ofTicks(6); t < IMPACT; t += 1_000) {
            assertEquals(Look.GLINT, glint(t, 6), "lead 6 ticks (300 ms) is under 325 ms");
        }
    }

    @Test
    void aFeintShattersFromItsCancelTick_atBreak5TheShatterWinsOverTheFlare() {
        long cancel = IMPACT - Micros.ofTicks(5); // −250 ms, inside the flare's −325 to −242
        assertEquals(Look.FLARE, feint(cancel - 1, 10, 5, cancel), "the flare shows before the break");
        assertEquals(Look.SHATTER, feint(cancel, 10, 5, cancel), "the shatter wins where they overlap");
        assertEquals(0, CueTimeline.frame(cancel, CueTimeline.shatterStart(cancel, cancel), STEP));
        long last = cancel + SHARDS * STEP - 1;
        assertEquals(Look.SHATTER, feint(last, 10, 5, cancel), "past the would-be impact: the strip plays out");
        assertEquals(SHARDS - 1, CueTimeline.frame(last, cancel, STEP));
        assertEquals(Look.NONE, feint(cancel + SHARDS * STEP, 10, 5, cancel), "then nothing");
    }

    @Test
    void atBreak8TheBreakComesFirstAndNoFlareShows() {
        long cancel = IMPACT - Micros.ofTicks(8); // −400 ms (D-132)
        for (long t = IMPACT - Micros.ofTicks(10); t < cancel + SHARDS * STEP; t += 1_000) {
            assertTrue(feint(t, 10, 8, cancel) != Look.FLARE, "no flare at " + (t - IMPACT));
        }
        assertEquals(Look.SHATTER, feint(cancel, 10, 8, cancel));
        assertEquals(Look.GLINT, feint(cancel - 1, 10, 8, cancel));
    }

    @Test
    void aCancelKnownLateStartsTheShatterWhenItsKnown_A6() {
        long cancel = IMPACT - Micros.ofTicks(5);
        long known = cancel + Micros.ofMillis(150); // the cancel reached the client after its tick
        assertEquals(known, CueTimeline.shatterStart(cancel, known));
        assertEquals(Look.SHATTER, feint(known, 10, 5, known), "all four frames still play");
        assertEquals(Look.SHATTER, feint(known + SHARDS * STEP - 1, 10, 5, known));
        assertEquals(cancel, CueTimeline.shatterStart(cancel, cancel - 1), "known early: from its tick");
    }

    @Test
    void anyOtherCancelEndsTheCueAtOnce_noShards() {
        long cue = IMPACT - Micros.ofTicks(10);
        long cancel = IMPACT - Micros.ofTicks(4); // a Pommel's interrupt, a posture break
        assertEquals(
                Look.GLINT, CueTimeline.glint(cancel - 1, cue, IMPACT, cancel, false, cancel, PERIOD, STEP, SHARDS));
        assertEquals(Look.NONE, CueTimeline.glint(cancel, cue, IMPACT, cancel, false, cancel, PERIOD, STEP, SHARDS));
        assertTrue(CueTimeline.shows(cancel - 1, cue, IMPACT, cancel), "a red or purple cue ends the same way");
        assertFalse(CueTimeline.shows(cancel, cue, IMPACT, cancel));
        assertTrue(CueTimeline.shows(IMPACT - 1, cue, IMPACT, NONE));
        assertFalse(CueTimeline.shows(IMPACT, cue, IMPACT, NONE));
        assertFalse(CueTimeline.shows(cue - 1, cue, IMPACT, NONE));
    }

    @Test
    void theThreadShowsFromItsOpeningTickUntilItCloses_A8() {
        long opened = Micros.ofTicks(500);
        assertFalse(CueTimeline.threadShows(opened - 1, opened, 540), "a negative offset can't show it early");
        assertTrue(CueTimeline.threadShows(opened, opened, 540));
        assertTrue(CueTimeline.threadShows(Micros.ofTicks(540) - 1, opened, 540));
        assertFalse(CueTimeline.threadShows(Micros.ofTicks(540), opened, 540), "it closes at threadUntil");
        assertFalse(CueTimeline.threadShows(opened, opened, Long.MIN_VALUE), "closed");
        assertTrue(CueTimeline.threadShows(Micros.ofTicks(10_000_000), opened, Long.MAX_VALUE), "open for good");
        assertFalse(CueTimeline.threadShows(opened, NONE, Long.MAX_VALUE), "never seen opening");
    }

    @Test
    void theThreadRunsAlongTheKneelingNeckWhileCollapsed() {
        Sweep.Box standing = new Sweep.Box(0, 1.5, 0, 0.18, 0.18, 0.18);
        Sweep.Box kneeling = new Sweep.Box(0, 0.9, 0.3, 0.18, 0.18, 0.18);
        DemonProfile.Neck neck = new DemonProfile.Neck(standing, kneeling);
        assertSame(kneeling, CueTimeline.neck(neck, State.COLLAPSED));
        for (State s : State.values()) {
            if (s != State.COLLAPSED) {
                assertSame(standing, CueTimeline.neck(neck, s), s.name());
            }
        }
    }

    @Test
    void theThreadsShimmerStepsThroughItsFramesAtTheCueRate() {
        long opened = Micros.ofTicks(500);
        assertEquals(0, CueTimeline.frame(opened, opened, STEP) % 4);
        assertEquals(1, CueTimeline.frame(opened + STEP, opened, STEP) % 4);
        assertEquals(0, CueTimeline.frame(opened + 4 * STEP, opened, STEP) % 4);
    }

    @Test
    void theClashFlashShowsForItsLengthAndStampsTheFirstFrameThatShowedIt() {
        long flash = Micros.ofTicks(700);
        long length = Micros.ofMillis(100); // flash_ms
        assertFalse(CueTimeline.flashShows(flash - 1, flash, length));
        assertTrue(CueTimeline.flashShows(flash, flash, length));
        assertTrue(CueTimeline.flashShows(flash + length - 1, flash, length));
        assertFalse(CueTimeline.flashShows(flash + length, flash, length));
        assertFalse(CueTimeline.flashShows(flash, NONE, length), "no clash");
        long first = flash + Micros.ofMillis(7); // the first frame drawn after it began
        long stamp = CueTimeline.stamp(NONE, first);
        assertEquals(first, stamp);
        assertEquals(first, CueTimeline.stamp(stamp, first + Micros.ofMillis(16)), "the first frame keeps it");
    }

    @Test
    void progressRunsFromTheCueToTheImpact() {
        long cue = IMPACT - Micros.ofTicks(12);
        assertEquals(0f, CueTimeline.progress(cue - 5, cue, IMPACT));
        assertEquals(0f, CueTimeline.progress(cue, cue, IMPACT));
        assertEquals(0.5f, CueTimeline.progress(cue + Micros.ofTicks(6), cue, IMPACT), 1e-6f);
        assertEquals(1f, CueTimeline.progress(IMPACT + 5, cue, IMPACT));
    }
}
