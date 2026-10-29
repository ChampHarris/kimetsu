package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A model's own time (5d-3a Step 1, spec:569): scheduled time less what holds have taken. A hold keeps it flat; the debt
 * is repaid at a raised rate by a deadline (absorbed in recovery) or at once; a hold inside a hold extends it; a hold
 * learned late keeps only what is still to come, and one still to come starts on time; a new epoch clears the debt,
 * while any other backward step of the clock (a freeze's pin at 50·G) takes model time back along its curve, holds kept
 * (D-136). Model time never runs ahead of scheduled time, never steps back while scheduled time doesn't, and outside an
 * epoch never moves on when it does. Times in µs.
 */
class ModelTimeTest {
    private static final long MS = 1000;
    private static final long T0 = 60_000 * MS; // a minute into the fight

    @Test
    void withNoHoldItIsScheduledTime() {
        ModelTime m = new ModelTime();
        assertEquals(T0, m.at(T0));
        assertEquals(T0 + 17 * MS, m.at(T0 + 17 * MS));
    }

    @Test
    void flatDuringAHold_thenCaughtUpAtOnceWithNoDeadline() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0, 140 * MS);
        for (long t = T0; t < T0 + 140 * MS; t += 10 * MS) {
            assertEquals(T0, m.at(t), "held at " + (t - T0) / MS + " ms");
        }
        assertEquals(T0 + 150 * MS, m.at(T0 + 150 * MS), "repaid at once when it ends");
    }

    @Test
    void repaidByTheDeadline_fasterThanScheduledTime_andFreeAfter() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0, 140 * MS);
        long deadline = T0 + 640 * MS; // the strike's recovery ends 500 ms after the hold
        m.repayBy(deadline);
        assertEquals(T0, m.at(T0 + 140 * MS), "the hold's last instant");
        long mid = m.at(T0 + 390 * MS);
        assertEquals(T0 + 390 * MS - 70 * MS, mid, "half the 140 ms debt repaid halfway to the deadline");
        assertEquals(deadline, m.at(deadline), "no debt at the deadline");
        assertEquals(deadline + 20 * MS, m.at(deadline + 20 * MS));
    }

    @Test
    void aHoldInsideAHoldExtendsIt_andAShorterOneDoesntShortenIt() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0, 100 * MS);
        m.at(T0 + 50 * MS);
        m.hold(T0 + 50 * MS, 100 * MS); // to T0 + 150
        m.at(T0 + 60 * MS);
        m.hold(T0 + 60 * MS, 10 * MS); // to T0 + 70: inside, changes nothing
        assertEquals(T0, m.at(T0 + 149 * MS), "still held");
        assertEquals(T0 + 150 * MS, m.at(T0 + 150 * MS));
    }

    @Test
    void aHoldLearnedLateKeepsOnlyWhatIsStillToCome() {
        ModelTime m = new ModelTime();
        m.at(T0 + 50 * MS); // already shown 50 ms past the hold's start
        m.hold(T0, 140 * MS);
        assertEquals(T0 + 50 * MS, m.at(T0 + 60 * MS), "held from the last read: no step back");
        assertEquals(T0 + 50 * MS, m.at(T0 + 139 * MS));
        assertEquals(T0 + 140 * MS, m.at(T0 + 140 * MS), "and it ends when it would have");
        m.hold(T0, 100 * MS); // over before the last read: nothing left to hold
        assertEquals(T0 + 150 * MS, m.at(T0 + 150 * MS));
    }

    @Test
    void aHoldStillToComeStartsOnTime_andDoesntCutTheCurrentOneShort() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0, 100 * MS); // now, to T0 + 100
        m.hold(T0 + 200 * MS, 50 * MS); // learned early: from T0 + 200 to T0 + 250
        assertEquals(T0, m.at(T0 + 99 * MS), "the current hold runs its course");
        assertEquals(T0 + 150 * MS, m.at(T0 + 150 * MS), "repaid at once in between");
        assertEquals(T0 + 199 * MS, m.at(T0 + 199 * MS));
        assertEquals(T0 + 200 * MS, m.at(T0 + 230 * MS), "the second hold, on time");
        assertEquals(T0 + 260 * MS, m.at(T0 + 260 * MS));
    }

    @Test
    void twoHoldsStillToComeMergeIntoOneWindow() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0 + 100 * MS, 50 * MS);
        m.hold(T0 + 120 * MS, 60 * MS);
        assertEquals(T0 + 99 * MS, m.at(T0 + 99 * MS));
        assertEquals(T0 + 100 * MS, m.at(T0 + 110 * MS), "from the first's start");
        assertEquals(T0 + 100 * MS, m.at(T0 + 179 * MS), "to the last's end");
        assertEquals(T0 + 180 * MS, m.at(T0 + 180 * MS));
    }

    @Test
    void aNewDeadlineMidRepaymentBendsTheLineWithoutAJump() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0, 100 * MS);
        m.repayBy(T0 + 500 * MS);
        long before = m.at(T0 + 300 * MS); // 50 ms of the 100 still owed
        assertEquals(T0 + 250 * MS, before);
        m.repayBy(T0 + 400 * MS);
        assertEquals(before, m.at(T0 + 300 * MS), "no jump at the change");
        assertEquals(T0 + 400 * MS, m.at(T0 + 400 * MS), "paid at the new deadline");
    }

    @Test
    void aNewEpochClearsTheDebt() {
        ModelTime m = new ModelTime();
        m.at(T0, 0);
        m.hold(T0, 200 * MS);
        m.repayBy(T0 + 1_000 * MS);
        assertEquals(T0, m.at(T0 + 150 * MS, 0));
        long back = T0 - 2_000 * MS; // an epoch (Rule 1.8) steps the clock back 2 s
        assertEquals(back, m.at(back, 1), "no debt carried over the epoch");
        assertEquals(back + 300 * MS, m.at(back + 300 * MS, 1), "and none after it");
        m.hold(back + 400 * MS, 100 * MS);
        assertEquals(back + 400 * MS, m.at(back + 450 * MS, 1), "a hold in the new numbering holds");
        assertEquals(back + 500 * MS, m.at(back + 500 * MS, 1), "repaid at once: the old deadline went too");
    }

    @Test
    void aNewEpochClearsAHoldStillToCome_evenBeforeTheClockSteps() {
        ModelTime m = new ModelTime();
        m.at(T0, 3);
        m.hold(T0 + 100 * MS, 50 * MS);
        assertEquals(T0 + 20 * MS, m.at(T0 + 20 * MS, 4), "ClockEpoch comes a record before the clock steps");
        assertEquals(T0 + 120 * MS, m.at(T0 + 120 * MS, 4), "the hold went with the old numbering");
    }

    @Test
    void aHoldArmedBeforeTheFirstReadTakesThatReadsEpoch() {
        ModelTime m = new ModelTime();
        m.hold(T0 + 100 * MS, 50 * MS);
        assertEquals(T0, m.at(T0, 7));
        assertEquals(T0 + 100 * MS, m.at(T0 + 120 * MS, 7), "nothing to clear before a first read");
    }

    @Test
    void aBackwardStepKeepsAHoldStillToCome() {
        // /tick freeze pins the clock at 50·G, so every /tick step ends up to a tick back (D-136)
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0 + 400 * MS, 140 * MS);
        m.at(T0 + 98 * MS); // the step's run on wall time
        assertEquals(T0 + 50 * MS, m.at(T0 + 50 * MS), "back with the clock");
        assertEquals(T0 + 399 * MS, m.at(T0 + 399 * MS));
        assertEquals(T0 + 400 * MS, m.at(T0 + 450 * MS), "the hold, on time");
        assertEquals(T0 + 400 * MS, m.at(T0 + 539 * MS));
        assertEquals(T0 + 540 * MS, m.at(T0 + 540 * MS));
    }

    @Test
    void aBackwardStepInsideAHoldKeepsIt() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0, 200 * MS);
        m.repayBy(T0 + 1_000 * MS);
        assertEquals(T0, m.at(T0 + 150 * MS));
        assertEquals(T0, m.at(T0 + 100 * MS), "stepped back 50 ms: still held");
        assertEquals(T0, m.at(T0 + 199 * MS));
        assertEquals(T0 + 500 * MS, m.at(T0 + 600 * MS), "half the 200 ms debt repaid halfway to the deadline");
        assertEquals(T0 + 1_000 * MS, m.at(T0 + 1_000 * MS), "paid at the deadline");
    }

    @Test
    void aBackwardStepWhileRepayingGoesBackAlongTheLine() {
        ModelTime m = new ModelTime();
        m.at(T0);
        m.hold(T0, 200 * MS);
        m.repayBy(T0 + 1_000 * MS);
        assertEquals(T0 + 500 * MS, m.at(T0 + 600 * MS));
        assertEquals(T0 + 375 * MS, m.at(T0 + 500 * MS), "the debt kept, not scheduled time");
        assertEquals(T0 + 1_000 * MS, m.at(T0 + 1_000 * MS));
    }

    @Test
    void neverAheadOfScheduledTime_andNeverBackWhileItRunsForward() {
        for (int seed = 0; seed < 200; seed++) {
            Random r = new Random(seed);
            ModelTime m = new ModelTime();
            long s = T0;
            int epoch = 0;
            long last = m.at(s, epoch);
            for (int i = 0; i < 400; i++) {
                int op = r.nextInt(10);
                if (op == 0) {
                    m.hold(s + (r.nextInt(400) - 200) * MS, r.nextInt(300) * MS);
                } else if (op == 1) {
                    m.repayBy(r.nextBoolean() ? ModelTime.NONE : s + r.nextInt(800) * MS);
                }
                boolean back = r.nextInt(40) == 0;
                boolean newEpoch = r.nextInt(80) == 0;
                epoch += newEpoch ? 1 : 0;
                s += back ? -r.nextInt(2_000) * MS : r.nextInt(30) * MS;
                long now = m.at(s, epoch);
                assertTrue(now <= s, "seed " + seed + " step " + i + ": model " + now + " ahead of " + s);
                if (!back) {
                    assertTrue(now >= last, "seed " + seed + " step " + i + ": model stepped back to " + now);
                } else if (!newEpoch) {
                    assertTrue(now <= last, "seed " + seed + " step " + i + ": a step back moved it on to " + now);
                }
                last = now;
            }
        }
    }
}
