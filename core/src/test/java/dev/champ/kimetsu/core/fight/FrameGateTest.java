package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.judge.FrameMeter;
import dev.champ.kimetsu.core.judge.InputHorizon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import java.util.function.LongBinaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The frame gate (slice 5c-1, D-061): the frame meter in front of the main thread. A frame over the limit is merged,
 * never dropped; a merge goes with the next frame let through, at a tick start when frames stop, or before a frame of
 * a newer epoch; and more than the intake holds, or a flood, is abuse. Times are combat µs; wall time runs with them at
 * 20 TPS (a combat µs is 1000 ns).
 */
class FrameGateTest {
    private static final long LEAD = 80_000; // tolerance_max_ms
    private static final double TICK = 50_000_000; // T̄ at 20 TPS, ns
    private static final int BURST = FrameMeter.Limits.PROPOSED.frameBurst();

    private static FrameGate gate() {
        return new FrameGate(FrameMeter.Limits.PROPOSED, true);
    }

    /** Five seconds of 201 messages a second, one over the flood line, ending just before 5 s. */
    private static long floodMessages(FrameGate g) {
        long nanos = 0;
        for (int s = 0; s < 5; s++) {
            for (int i = 0; i < 201; i++) {
                nanos = s * 1_000_000_000L + i * 4_000_000L;
                g.arrived(nanos);
            }
        }
        return nanos;
    }

    /** Frame {@code seq} sent at {@code stamp}, carrying the pose of the client tick 10 ms before it. */
    private static PlayerIntake.Frame frame(long seq, long stamp, int epoch, int held, PlayerIntake.Delta... deltas) {
        return new PlayerIntake.Frame(seq, stamp, 16_000, stamp - 10_000, epoch, held, List.of(deltas));
    }

    private static List<FrameGate.Pass> offer(FrameGate g, PlayerIntake.Frame f, long arrival) {
        return offer(g, f, arrival, 0);
    }

    /** Offers {@code f} arriving at {@code arrival} while the server is at epoch {@code serverEpoch}. */
    private static List<FrameGate.Pass> offer(FrameGate g, PlayerIntake.Frame f, long arrival, int serverEpoch) {
        return offer(g, f, arrival, serverEpoch, serverEpoch);
    }

    /** Offers {@code f} converted by a record of epoch {@code record} while the server has announced {@code announced}. */
    private static List<FrameGate.Pass> offer(
            FrameGate g, PlayerIntake.Frame f, long arrival, int record, int announced) {
        return g.frame(f, arrival, false, false, LEAD, TICK, arrival * 1000, record, announced);
    }

    /** Offers {@code f} at epoch 0, arriving at {@code arrival} and {@code wallNanos}, frozen or sprinting as given. */
    private static List<FrameGate.Pass> offer(
            FrameGate g, PlayerIntake.Frame f, long arrival, boolean frozen, boolean sprinting, long wallNanos) {
        return g.frame(f, arrival, frozen, sprinting, LEAD, TICK, wallNanos, 0, 0);
    }

    /**
     * A cheat's flood, the netcode reviews' repro: 1,000 empty frames at 200 a second for 5 s, just under the flood
     * line, sent at {@code stamp(seq, send time)} claiming {@code claim(seq)}, with the server at epoch 1 and the main
     * thread running its queue at each tick start. Returns how many frames reached the main thread.
     */
    private static int flood(FrameGate g, LongBinaryOperator stamp, IntUnaryOperator claim) {
        int reached = 0;
        int queued = 0;
        long t = 1_000_000;
        long nextTickStart = t + 50_000;
        for (int seq = 0; seq < 1_000; seq++) {
            t += 5_000;
            while (t >= nextTickStart) { // the main thread runs its queue, then the tick starts
                ran(g, queued);
                queued = 0;
                if (g.takeMerge() != null) {
                    reached++;
                }
                nextTickStart += 50_000;
            }
            int out = offer(g, frame(seq, stamp.applyAsLong(seq, t), claim.applyAsInt(seq), 0), t + 50_000, 1)
                    .size();
            reached += out;
            queued += out;
        }
        return reached;
    }

    /** Spends the frame burst: {@code BURST} empty frames sent and arriving at {@code stamp}, so none credits time. */
    private static long drain(FrameGate g, long stamp) {
        for (int i = 0; i < BURST; i++) {
            assertEquals(1, offer(g, frame(i, stamp, 0, 0), stamp).size(), "frame " + i + " of the burst");
        }
        return BURST;
    }

    /** The main thread ran the {@code n} frames the gate let through. */
    private static void ran(FrameGate g, int n) {
        for (int i = 0; i < n; i++) {
            g.ran();
        }
    }

    @Test
    void aFrameWithinTheLimitPassesAsItIs() {
        FrameGate g = gate();
        PlayerIntake.Frame f = frame(
                0,
                1_000_000,
                0,
                PlayerIntake.Key.GUARD.bit(),
                PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 990_000));
        List<FrameGate.Pass> out = offer(g, f, 1_050_000);
        assertEquals(1, out.size());
        assertSame(f, out.get(0).frame());
        assertEquals(1_050_000, out.get(0).arrivalMicros());
        assertFalse(out.get(0).frozen());
    }

    @Test
    void framesOverTheLimitMergeAndGoWithTheNextOneLetThrough() {
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        PlayerIntake.Delta guard = PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, t);
        PlayerIntake.Delta attack = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
        PlayerIntake.Delta inhale = PlayerIntake.Delta.press(PlayerIntake.Key.INHALE, t);
        assertTrue(offer(g, frame(seq++, t, 0, PlayerIntake.Key.GUARD.bit(), guard), t)
                .isEmpty());
        assertTrue(offer(g, frame(seq++, t, 0, PlayerIntake.Key.GUARD.bit(), attack), t)
                .isEmpty());
        assertTrue(offer(g, frame(seq++, t, 0, 0), t).isEmpty()); // no deltas: still merged, in order
        // 30 ms of stamps credit 1.2 frames: the merge goes with this one
        PlayerIntake.Delta release = PlayerIntake.Delta.release(PlayerIntake.Key.GUARD, t + 20_000);
        PlayerIntake.Frame last = frame(seq, t + 30_000, 0, 0, inhale, release);
        List<FrameGate.Pass> out = offer(g, last, t + 30_000);
        assertEquals(1, out.size());
        PlayerIntake.Frame merged = out.get(0).frame();
        assertEquals(List.of(guard, attack, inhale, release), merged.deltas());
        assertEquals(seq, merged.seq());
        assertEquals(0, merged.held()); // the newest held state
        assertEquals(last.sendStampMicros(), merged.sendStampMicros());
        assertEquals(last.tickTimeMicros(), merged.tickTimeMicros()); // its own pose: it runs after its movement
    }

    @Test
    void itCountsTheFramesItMergesTheLongestGapAndTheLongestSilence_eachSinceTheLastTake() {
        FrameGate g = gate();
        long t = 1_000_000;
        offer(g, frame(0, t, 0, 0), t);
        offer(g, frame(1, t + 50_000, 0, 0), t + 50_000);
        offer(g, frame(2, t + 100_000, 0, 0), t + 400_000); // a 350 ms stall: TCP delivers the burst together
        offer(g, frame(3, t + 120_000, 0, 0), t + 400_000);
        // 350 ms between the arrivals, 50 of them the client's own spacing: the network held the frame 300 ms longer
        // than the one before it (D-089; the gap was the 350 between arrivals until then)
        assertEquals(300_000, g.takeLongestGapMicros());
        assertEquals(50_000, g.takeLongestSilenceMicros(), "the client's longest spacing");
        assertEquals(0, g.takeLongestGapMicros(), "taken");
        assertEquals(0, g.takeLongestSilenceMicros(), "taken");
        assertEquals(0, g.takeMerged(), "nothing over the limit yet");
        assertEquals(4, g.framesArrived());
        assertEquals(0, g.framesMerged());

        FrameGate h = gate();
        long seq = drain(h, t);
        assertEquals(0, h.takeMerged(), "the burst went through");
        assertTrue(offer(h, frame(seq++, t, 0, 0), t).isEmpty());
        assertTrue(offer(h, frame(seq, t, 0, 0), t).isEmpty());
        assertEquals(2, h.takeMerged());
        assertEquals(0, h.takeMerged(), "taken");
        assertEquals(BURST + 2, h.framesArrived(), "for its lifetime: the takes don't reset it");
        assertEquals(2, h.framesMerged(), "for its lifetime: the takes don't reset it");
    }

    @Test
    void aBurstsFollowersAddNoGap_onlyItsFirstFrameCameLate() {
        FrameGate g = gate();
        long t = 1_000_000;
        for (int seq = 0; seq < 5; seq++) { // 20 ms on the way
            offer(g, frame(seq, t + seq * 50_000L, 0, 0), t + seq * 50_000L + 20_000);
        }
        // a 1 s stall: the next 20 frames, sent 50 ms apart, arrive together with the last of them
        long together = t + 24 * 50_000L + 20_000;
        offer(g, frame(5, t + 5 * 50_000L, 0, 0), together);
        assertEquals(950_000, g.takeLongestGapMicros(), "the burst's first frame, 950 ms later on the way");
        assertEquals(50_000, g.takeLongestSilenceMicros());
        for (int seq = 6; seq < 25; seq++) {
            offer(g, frame(seq, t + seq * 50_000L, 0, 0), together);
        }
        assertEquals(0, g.takeLongestGapMicros(), "each follower came sooner on the way than the frame before it");
        assertEquals(50_000, g.takeLongestSilenceMicros());
    }

    @Test
    void aQuietSpellIsSilence_notAGap() {
        // out of a fight and away from hostiles the client sends nothing (ClientInput.flowing); a minute later it
        // starts again, its frames as long on the way as before
        FrameGate g = gate();
        long t = 1_000_000;
        offer(g, frame(0, t, 0, 0), t + 30_000);
        offer(g, frame(1, t + 60_000_000, 0, 0), t + 60_030_000);
        offer(g, frame(2, t + 60_050_000, 0, 0), t + 60_085_000); // 5 ms longer on the way: jitter
        assertEquals(5_000, g.takeLongestGapMicros());
        assertEquals(60_000_000, g.takeLongestSilenceMicros());
    }

    @Test
    void aFreezeAddsToNeither_howeverLongItLasts() {
        // ticking freezes at P for 10 s of wall time. Arrivals pin to P, and so do the client's stamps once the frozen
        // record reaches it; a frame it stamped before then arrives frozen, its stamp past P. Ticking resumes from P,
        // and the client sends again once the record reaches it
        FrameGate g = gate();
        long p = 10_000_000;
        long wall = p * 1000; // wall ns run with combat µs until the freeze
        offer(g, frame(0, p - 100_000, 0, 0), p - 80_000, false, false, wall - 80_000_000);
        offer(g, frame(1, p - 50_000, 0, 0), p - 30_000, false, false, wall - 30_000_000);
        offer(g, frame(2, p + 20_000, 0, 0), p, true, false, wall + 40_000_000);
        offer(g, frame(3, p, 0, 0), p, true, false, wall + 5_000_000_000L);
        offer(g, frame(4, p + 30_000, 0, 0), p + 50_000, false, false, wall + 10_050_000_000L);
        offer(g, frame(5, p + 80_000, 0, 0), p + 100_000, false, false, wall + 10_100_000_000L);
        // a pinned arrival isn't when the frame arrived, so a pair from one counts no gap: frame 2 to 3 would count
        // 20 ms, and 3 to 4 another 20
        assertEquals(0, g.takeLongestGapMicros());
        assertEquals(70_000, g.takeLongestSilenceMicros(), "frame 2, stamped 20 ms past P: the 10 s add nothing");
    }

    @Test
    void aPairAcrossTheClientsEpochCountsForNeither_andOneAcrossTheRecordsOnlyForSilence() {
        // a backlog drop steps the timeline back 30 s (Rule 1.8). The client's ClockEpoch comes before the record that
        // converts arrivals on the new timeline, so frame 1 is stamped on the new one and arrives on the old
        FrameGate g = gate();
        long t = 40_000_000;
        long back = 30_000_000;
        offer(g, frame(0, t, 0, 0), t + 20_000, 0, 0);
        offer(g, frame(1, t + 50_000 - back, 1, 0), t + 70_000, 0, 1); // would count a 30 s gap
        offer(g, frame(2, t + 100_000 - back, 1, 0), t + 120_000 - back, 1, 1);
        assertEquals(0, g.takeLongestGapMicros());
        assertEquals(50_000, g.takeLongestSilenceMicros(), "frame 1 to 2, both stamped on the new timeline");
        // the gate doesn't know which way a timeline steps: epoch 2's is 30 s ahead of epoch 1's, record first
        long two = t - back + 30_000_000;
        offer(g, frame(3, t + 150_000 - back, 1, 0), two + 170_000, 2, 2); // would count a 30 s gap
        offer(g, frame(4, two + 200_000, 2, 0), two + 220_000, 2, 2); // would count 30 s of silence
        offer(g, frame(5, two + 250_000, 2, 0), two + 270_000, 2, 2);
        assertEquals(0, g.takeLongestGapMicros());
        assertEquals(50_000, g.takeLongestSilenceMicros(), "frame 2 to 3 and 4 to 5");
    }

    @Test
    void underTickSprintNeitherCounts() {
        // /tick sprint runs ticks back to back: arrivals advance at the game's pace, here 5× wall time, while the
        // client's clock keeps wall pace (ClockEstimator)
        FrameGate g = gate();
        long t = 1_000_000;
        offer(g, frame(0, t, 0, 0), t + 20_000, false, false, 0);
        offer(g, frame(1, t + 50_000, 0, 0), t + 270_000, false, true, 0); // would count a 200 ms gap
        offer(g, frame(2, t + 1_100_000, 0, 0), t + 520_000, false, true, 0); // a second's quiet at wall pace
        offer(g, frame(3, t + 1_150_000, 0, 0), t + 1_170_000, false, false, 0); // would count a 600 ms gap
        offer(g, frame(4, t + 1_200_000, 0, 0), t + 1_220_000, false, false, 0);
        assertEquals(0, g.takeLongestGapMicros());
        assertEquals(50_000, g.takeLongestSilenceMicros(), "frame 3 to 4 only");
    }

    @Test
    void aMergeLeftWhenFramesStopGoesAtTheNextTickStart_withoutItsPose_onceNothingIsQueuedAhead() {
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        PlayerIntake.Delta release = PlayerIntake.Delta.release(PlayerIntake.Key.GUARD, t);
        assertTrue(offer(g, frame(seq, t, 0, 0, release), t).isEmpty());
        ran(g, BURST - 1);
        assertNull(g.takeMerge(), "a frame let through is still queued ahead of the merge");
        g.ran();
        FrameGate.Pass m = g.takeMerge();
        assertNotNull(m);
        assertEquals(List.of(release), m.frame().deltas());
        assertEquals(seq, m.frame().seq());
        assertEquals(InputHorizon.NO_TICK, m.frame().tickTimeMicros()); // movement may have moved on since
        assertEquals(t, m.arrivalMicros());
        assertNull(g.takeMerge(), "the merge goes once");
        // it went whatever the meter held, so it was charged: one credited frame pays that back first
        assertTrue(offer(g, frame(seq + 1, t + 30_000, 0, 0), t + 30_000).isEmpty());
        assertEquals(1, offer(g, frame(seq + 2, t + 60_000, 0, 0), t + 60_000).size());
    }

    @Test
    void anOlderEpochsMergeGoesFirstByItself_withoutItsPose() {
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        PlayerIntake.Delta old = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
        assertTrue(offer(g, frame(seq, t, 0, 0, old), t).isEmpty());
        PlayerIntake.Delta fresh =
                PlayerIntake.Delta.press(PlayerIntake.Key.POMMEL, t - 400_000); // the new timeline stepped back
        List<FrameGate.Pass> out = offer(g, frame(seq + 1, t - 400_000, 1, 0, fresh), t + 5_000, 1);
        assertEquals(1, out.size(), "the old merge alone: the new epoch's frame credits nothing, and waits");
        PlayerIntake.Frame flushed = out.get(0).frame();
        assertEquals(0, flushed.epoch());
        assertEquals(List.of(old), flushed.deltas());
        assertEquals(InputHorizon.NO_TICK, flushed.tickTimeMicros());
        ran(g, BURST + 1);
        FrameGate.Pass m = g.takeMerge();
        assertNotNull(m);
        assertEquals(1, m.frame().epoch());
        assertEquals(List.of(fresh), m.frame().deltas());
    }

    /** The epochs a cheating client claims, frame by frame, while the server is at epoch 1. */
    private enum Claims {
        ALTERNATING_BELOW,
        ALTERNATING_PAST,
        COUNTING_UP;

        int of(int seq) {
            return switch (this) {
                case ALTERNATING_BELOW -> seq % 2;
                case ALTERNATING_PAST -> 1 + seq % 2;
                case COUNTING_UP -> seq;
            };
        }
    }

    @ParameterizedTest
    @EnumSource(Claims.class)
    void aClientChangingTheEpochItClaimsEveryFrame_staysMetered(Claims claims) {
        // honestly stamped; each change of epoch used to send the merge to the main thread on its own: 999 reached it
        FrameGate g = gate();
        int reached = flood(g, (seq, t) -> t, claims::of);
        assertNull(g.abuse());
        // the burst and 40 a second over the 5 s the stamps span, whatever the claims
        assertTrue(reached <= BURST + 40 * 5 + 1, reached + " reached the main thread");
    }

    @Test
    void backDatedStamps_eachASecondOnFromTheLast_stayMetered() {
        // stamped 31 years back, then a second on each frame: with no floor each frame bought a full bucket, and all
        // 1,000 reached the main thread (the netcode re-review)
        FrameGate g = gate();
        int reached = flood(g, (seq, t) -> -1_000_000_000_000_000L + seq * 1_000_000L, seq -> 1);
        assertNull(g.abuse());
        assertTrue(reached <= BURST + 40 * 5 + 1, reached + " reached the main thread");
    }

    @Test
    void pairsOfClaimsCountingUpFromFarBelowTheServersEpoch_stayMetered() {
        // round 2 of the re-review: claims MIN_VALUE, MIN_VALUE, MIN_VALUE + 1, ... each pair stamped 10 s back, then
        // 80 ms ahead; each pair was a rise, each bought a bucket, and all 1,000 reached the main thread. Only a claim
        // of the server's own epoch starts one, so this cheat credits nothing after its first pair and is cut off
        FrameGate g = gate();
        int reached =
                flood(g, (seq, t) -> seq % 2 == 0 ? t - 10_000_000 : t + 80_000, seq -> Integer.MIN_VALUE + seq / 2);
        assertTrue(reached <= BURST + 40 * 5 + 1, reached + " reached the main thread");
        assertEquals(FrameGate.Abuse.FRAME_DEBT, g.abuse());
    }

    @Test
    void aClaimBelowTheAnnouncedEpochNeverFlushes_howeverItRises() {
        FrameGate g = gate();
        long t = 1_000_000;
        for (int i = 0; i < BURST; i++) { // the burst, claiming far below the server's epoch 1 from the first frame
            assertEquals(1, offer(g, frame(i, t, Integer.MIN_VALUE, 0), t, 1).size());
        }
        List<PlayerIntake.Delta> sent = new ArrayList<>();
        int[] claims = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 2, -5, 0};
        for (int i = 0; i < claims.length; i++) {
            PlayerIntake.Delta d = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
            sent.add(d);
            assertTrue(offer(g, frame(BURST + i, t, claims[i], 0, d), t, 1).isEmpty(), "claim " + claims[i]);
        }
        ran(g, BURST);
        FrameGate.Pass m = g.takeMerge();
        assertNotNull(m);
        assertEquals(sent, m.frame().deltas(), "one merge");
    }

    @Test
    void anEpochAnnouncedBeforeItsRecordIsPublished_stillFlushesTheOldMerge() {
        // ClockEpoch goes out before the record carrying the epoch: a frame of the new epoch in between mustn't join
        // the old merge, or the old press would be judged on the new timeline (the netcode re-review)
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        PlayerIntake.Delta old = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
        assertTrue(offer(g, frame(seq++, t, 0, 0, old), t).isEmpty());
        PlayerIntake.Delta fresh = PlayerIntake.Delta.press(PlayerIntake.Key.POMMEL, t - 400_000);
        List<FrameGate.Pass> out = offer(g, frame(seq++, t - 400_000, 1, 0, fresh), t + 5_000, 0, 1);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).frame().epoch());
        assertEquals(List.of(old), out.get(0).frame().deltas());
        // the record catches up: no second flush, the new epoch's frames stay one merge
        PlayerIntake.Delta guard = PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, t - 350_000);
        assertTrue(offer(g, frame(seq, t - 350_000, 1, 0, guard), t - 300_000, 1, 1)
                .isEmpty());
        ran(g, BURST + 1);
        FrameGate.Pass m = g.takeMerge();
        assertNotNull(m);
        assertEquals(List.of(fresh, guard), m.frame().deltas());
    }

    @Test
    void anHonestClientAcrossAnEpochAndFreezeToggles_isNeverCut() {
        // 20 frames a second for 10 s, each with a press; at 3 s a backlog drop steps the timeline back 30 s, announced
        // a tick before its record; from 6 s to 9 s ticking freezes and unfreezes every tick (/tick step)
        FrameGate g = gate();
        List<PlayerIntake.Delta> sent = new ArrayList<>();
        List<PlayerIntake.Delta> got = new ArrayList<>();
        ArrayDeque<FrameGate.Pass> queue = new ArrayDeque<>();
        for (int seq = 0; seq < 200; seq++) {
            while (!queue.isEmpty()) { // the main thread runs its queue, then the tick starts
                g.ran();
                got.addAll(queue.poll().frame().deltas());
            }
            FrameGate.Pass m = g.takeMerge();
            if (m != null) {
                got.addAll(m.frame().deltas());
            }
            long wall = 1_000_000 + seq * 50_000L;
            int announced = seq >= 60 ? 1 : 0;
            int record = seq >= 61 ? 1 : 0;
            int claim = announced; // the client has the ClockEpoch: it stamps on the new timeline
            long stamp = wall - 50_000 - (claim == 1 ? 30_000_000 : 0);
            long arrival = wall - (record == 1 ? 30_000_000 : 0); // converted by the record it has
            boolean frozen = seq >= 120 && seq < 180 && seq % 2 == 0;
            PlayerIntake.Delta press = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, stamp);
            sent.add(press);
            queue.addAll(g.frame(
                    frame(seq, stamp, claim, 0, press),
                    arrival,
                    frozen,
                    false,
                    LEAD,
                    TICK,
                    wall * 1000,
                    record,
                    announced));
            assertNull(g.abuse(), "frame " + seq);
        }
        while (!queue.isEmpty()) {
            g.ran();
            got.addAll(queue.poll().frame().deltas());
        }
        FrameGate.Pass m = g.takeMerge();
        if (m != null) {
            got.addAll(m.frame().deltas());
        }
        assertEquals(sent, got);
    }

    @Test
    void onlyARiseInTheServersEpochFlushesTheMerge() {
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        PlayerIntake.Delta attack = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
        PlayerIntake.Delta pommel = PlayerIntake.Delta.press(PlayerIntake.Key.POMMEL, t);
        assertTrue(offer(g, frame(seq++, t, 0, 0, attack), t).isEmpty());
        assertTrue(
                offer(g, frame(seq++, t, 7, 0, pommel), t).isEmpty(),
                "an epoch the server hasn't announced counts as its latest: merged, not flushed");
        // the server reaches epoch 1, and the client's first frame of it flushes the merge by itself
        PlayerIntake.Delta inhale = PlayerIntake.Delta.press(PlayerIntake.Key.INHALE, t - 400_000);
        List<FrameGate.Pass> out = offer(g, frame(seq++, t - 400_000, 1, 0, inhale), t + 5_000, 1);
        assertEquals(1, out.size());
        assertEquals(List.of(attack, pommel), out.get(0).frame().deltas());
        // a claim of the old epoch after that flushes nothing, and its stamp a second on credits nothing
        PlayerIntake.Delta guard = PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, t + 1_000_000);
        assertTrue(
                offer(g, frame(seq, t + 1_000_000, 0, 0, guard), t + 10_000, 1).isEmpty());
        ran(g, BURST + 1);
        FrameGate.Pass m = g.takeMerge();
        assertNotNull(m);
        assertEquals(List.of(inhale, guard), m.frame().deltas(), "one merge, in order");
    }

    @Test
    void aFrameDebtDeeperThanTheBurstIsAbuse_soTickStartsCantFeedPastTheMeter() {
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        ran(g, BURST);
        int handed = 0;
        while (g.abuse() == null) {
            // stamps that never move credit nothing, and each tick start hands the merge over all the same
            assertTrue(offer(g, frame(seq, t, 0, 0), t + seq * 1_000).isEmpty());
            seq++;
            if (g.takeMerge() != null) {
                handed++;
            }
            assertTrue(handed < 100, "the frame debt never counted");
        }
        // the burst spent, then a debt of at most a burst: 20 merges handed over; the 21st is abuse
        assertEquals(BURST, handed);
        assertEquals(FrameGate.Abuse.FRAME_DEBT, g.abuse());
    }

    @Test
    void aMergePastWhatTheIntakeHoldsIsAbuse() {
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        int held = 0;
        while (held <= PlayerIntake.MAX_PENDING) {
            PlayerIntake.Delta[] sixteen = new PlayerIntake.Delta[16];
            for (int i = 0; i < sixteen.length; i++) {
                sixteen[i] = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
            }
            assertNull(g.abuse(), "abuse after " + held + " held");
            assertTrue(offer(g, frame(seq++, t, 0, 0, sixteen), t).isEmpty());
            held += sixteen.length;
        }
        assertEquals(FrameGate.Abuse.MERGE_OVERFLOW, g.abuse());
        ran(g, BURST);
        assertNull(g.takeMerge(), "nothing passes after abuse");
        assertTrue(offer(g, frame(seq, t + 1_000_000, 0, 0), t + 1_000_000).isEmpty());
    }

    @Test
    void anEventDebtDeeperThanTheIntakeHoldsIsAbuse_soTickStartsCantFeedPastTheMeter() {
        FrameGate g = gate();
        long t = 1_000_000;
        long seq = drain(g, t);
        ran(g, BURST);
        int handed = 0;
        while (g.abuse() == null) {
            PlayerIntake.Delta[] sixteen = new PlayerIntake.Delta[16];
            for (int i = 0; i < sixteen.length; i++) {
                sixteen[i] = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
            }
            assertTrue(offer(g, frame(seq++, t, 0, 0, sixteen), t).isEmpty()); // stamps that credit nothing
            if (g.takeMerge() != null) {
                handed++;
            }
            assertTrue(handed < 100, "the debt never counted");
        }
        // 40 events in the bucket, then a debt of at most 64: six merges of 16 (40 − 96 = −56); the seventh is abuse
        assertEquals(6, handed);
        assertEquals(FrameGate.Abuse.EVENT_DEBT, g.abuse());
    }

    @Test
    void aFloodOfMessagesIsAbuse_andNothingPassesAfterIt() {
        FrameGate g = gate();
        long nanos = floodMessages(g);
        assertEquals(FrameGate.Abuse.FLOOD, g.abuse());
        assertTrue(g.flooded());
        assertFalse(g.arrived(nanos + 1));
        assertTrue(offer(g, frame(0, 6_000_000, 0, 0), 6_000_000).isEmpty());
    }

    /**
     * With {@code input.flood_kicks} off (D-104), a flood is recorded, not a cut-off: messages still arrive, and the
     * frames go on through the meter, which still merges what's over its limit, so the main thread stays bounded.
     */
    @Test
    void withFloodKicksOff_aFloodIsOnlyRecorded_andTheFramesStayMetered() {
        FrameGate g = new FrameGate(FrameMeter.Limits.PROPOSED, false);
        long nanos = floodMessages(g);
        assertTrue(g.flooded(), "the flood is recorded");
        assertNull(g.abuse(), "and isn't abuse");
        assertTrue(g.arrived(nanos + 1), "messages still arrive");
        long t = 6_000_000;
        long seq = drain(g, t); // the burst passes as it would without the flood
        assertTrue(offer(g, frame(seq, t, 0, 0), t).isEmpty(), "the frame past the burst is merged, as ever");
        assertNull(g.abuse());
    }

    /** Merge overflow and both debts cut the client off whatever the flood switch says: they bound the main thread. */
    @Test
    void withFloodKicksOff_aMergePastWhatTheIntakeHoldsIsStillAbuse() {
        FrameGate g = new FrameGate(FrameMeter.Limits.PROPOSED, false);
        floodMessages(g);
        long t = 6_000_000;
        long seq = drain(g, t);
        int held = 0;
        while (held <= PlayerIntake.MAX_PENDING) {
            PlayerIntake.Delta[] sixteen = new PlayerIntake.Delta[16];
            for (int i = 0; i < sixteen.length; i++) {
                sixteen[i] = PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t);
            }
            offer(g, frame(seq++, t, 0, 0, sixteen), t);
            held += sixteen.length;
        }
        assertEquals(FrameGate.Abuse.MERGE_OVERFLOW, g.abuse());
        assertFalse(g.arrived(7_000_000_000L), "nothing arrives after abuse");
    }

    @Test
    void twoHundredMessagesASecondIsNoFlood() {
        FrameGate g = gate();
        for (int s = 0; s < 10; s++) {
            for (int i = 0; i < 200; i++) {
                assertTrue(g.arrived(s * 1_000_000_000L + i * 4_000_000L));
            }
        }
        assertNull(g.abuse());
    }

    @Test
    void everyPressAndReleaseSurvivesAFlood_inOrder() {
        Random rng = new Random(61);
        FrameGate g = gate();
        List<PlayerIntake.Delta> sent = new ArrayList<>();
        List<PlayerIntake.Frame> ranOnMain = new ArrayList<>();
        ArrayDeque<FrameGate.Pass> queue = new ArrayDeque<>(); // let through, waiting for the main thread
        long t = 1_000_000;
        long nextTickStart = t + 50_000;
        PlayerIntake.Frame last = null;
        for (int seq = 0; seq < 2_000; seq++) { // 100 frames a second for 20 s, 30 events a second
            t += 10_000;
            while (t >= nextTickStart) { // the main thread runs its queue, then the tick starts
                while (!queue.isEmpty()) {
                    g.ran();
                    ranOnMain.add(queue.poll().frame());
                }
                FrameGate.Pass m = g.takeMerge();
                if (m != null) {
                    ranOnMain.add(m.frame());
                }
                nextTickStart += 50_000;
            }
            List<PlayerIntake.Delta> deltas = new ArrayList<>();
            if (rng.nextInt(10) < 3) {
                deltas.add(PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, t - rng.nextInt(10_000)));
            }
            sent.addAll(deltas);
            last = new PlayerIntake.Frame(seq, t, 16_000, t - 10_000, 0, rng.nextInt(4), deltas);
            queue.addAll(g.frame(last, t + 50_000, false, false, LEAD, TICK, (t + 50_000) * 1000, 0, 0));
        }
        while (!queue.isEmpty()) {
            g.ran();
            ranOnMain.add(queue.poll().frame());
        }
        FrameGate.Pass m = g.takeMerge();
        if (m != null) {
            ranOnMain.add(m.frame());
        }
        assertNull(g.abuse());
        List<PlayerIntake.Delta> got = new ArrayList<>();
        long seqBefore = -1;
        for (PlayerIntake.Frame f : ranOnMain) {
            got.addAll(f.deltas());
            assertTrue(f.seq() > seqBefore, "frames run in order");
            seqBefore = f.seq();
            assertTrue(
                    f.tickTimeMicros() == InputHorizon.NO_TICK || f.tickTimeMicros() == f.sendStampMicros() - 10_000,
                    "a frame carries its own pose or none");
        }
        assertEquals(sent, got);
        assertNotNull(last);
        PlayerIntake.Frame lastRan = ranOnMain.get(ranOnMain.size() - 1);
        assertEquals(last.seq(), lastRan.seq());
        assertEquals(last.held(), lastRan.held());
        assertTrue(ranOnMain.size() < 2_000 * 0.6, "the flood was metered: " + ranOnMain.size() + " ran");
    }
}
