package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Input;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code Snapshot.lastSeq} (slice 5c-1, D-061; Netcode, "Prediction and reconciliation"): the newest input frame the
 * snapshot's settled tick includes whole, so the client can replay only the frames after it on the server's state. A
 * frame is in once every press it carried acts by that tick (at its judged tick, or after the timeline's watermark when
 * the world had read past it) and the stamp check holds none of them, nor any of an earlier frame's.
 */
class LastSeqTest {
    private static final long RTT = 40_000;
    private static final long FRAME = 16_667;

    private final Arena a = new Arena();
    private final Arena.Player p = a.player(1).silentFrom(1); // fed by the intake alone
    private final List<Input> judged = new ArrayList<>();
    private final PlayerIntake intake = new PlayerIntake(Fixtures.timing(), 0, (in, sent) -> judged.add(in));

    LastSeqTest() {
        intake.echo(0, RTT);
        intake.join(p.f);
    }

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    private static final int GUARD = PlayerIntake.Key.GUARD.bit();

    /** Frame {@code seq}, sent at {@code stamp} with the keys {@code held} down, arriving at {@code arrival}. */
    private void send(long seq, long stamp, long arrival, int held, PlayerIntake.Delta... deltas) {
        intake.frame(
                new PlayerIntake.Frame(seq, stamp, FRAME, InputHorizon.NO_TICK, 0, held, List.of(deltas)),
                arrival,
                false);
    }

    /** The same, on time: half the RTT on the way. */
    private void send(long seq, long stamp, int held, PlayerIntake.Delta... deltas) {
        send(seq, stamp, stamp + RTT / 2, held, deltas);
    }

    @Test
    void itNamesTheNewestFrameWhosePressesAllActByTheSettledTick() {
        a.through(100);
        send(0, at(101, 0), 0);
        send(1, at(101, 10), GUARD, PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, at(101, 5)));
        send(2, at(101, 40), GUARD);
        send(3, at(102, 20), 0, PlayerIntake.Delta.release(PlayerIntake.Key.GUARD, at(102, 15)));
        long down = judged.get(0).tick();
        long up = judged.get(1).tick();
        assertEquals(101, down); // 101.005 less half a frame acts in step 101
        assertEquals(103, up);
        assertEquals(0, intake.settledSeq(down - 1));
        assertEquals(2, intake.settledSeq(down)); // frame 2 carried nothing: it's in with frame 1
        assertEquals(2, intake.settledSeq(up - 1));
        assertEquals(3, intake.settledSeq(up));
        assertEquals(3, intake.lastSeq());
    }

    @Test
    void aPressTheStampCheckHoldsKeepsItsFrameAndEveryLaterOneOut_untilItsAnchorComes() {
        a.through(100);
        send(0, at(101, 0), 0);
        // a stall: frame 1 comes 300 ms late, its press waiting for a frame on time to anchor it
        send(1, at(102, 0), at(108, 0), GUARD, PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, at(101, 45)));
        assertEquals(List.of(), judged);
        assertEquals(0, intake.settledSeq(1_000));
        send(2, at(107, 30), at(108, 0), GUARD); // on time: the anchor
        assertEquals(1, judged.size());
        assertEquals(0, intake.settledSeq(judged.get(0).tick() - 1));
        assertEquals(2, intake.settledSeq(judged.get(0).tick()));
    }

    @Test
    void aPressStampedBeforeWhatTheWorldHasReadCountsWhereItActs_afterTheWatermark() {
        a.through(110);
        p.f.timeline().swept(110); // the world read tick 110: nothing can act there any more
        send(0, at(108, 0), GUARD, PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, at(107, 40)));
        assertEquals(108, judged.get(0).tick());
        assertEquals(-1, intake.settledSeq(110));
        assertEquals(0, intake.settledSeq(111));
    }

    @Test
    void aRejoinedFightersFreshTimelineHoldsNoneOfTheFramesBefore() {
        a.through(100);
        send(0, at(101, 10), GUARD, PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, at(101, 5)));
        assertEquals(-1, intake.settledSeq(100));
        intake.join(p.f); // a rebuild: its presses went with the old world
        assertEquals(0, intake.settledSeq(100));
    }

    @Test
    void leavingTheFightSettlesEveryFrame() {
        a.through(100);
        send(0, at(101, 0), 0);
        send(1, at(102, 0), at(108, 0), GUARD, PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, at(101, 45)));
        intake.leave();
        assertEquals(1, intake.settledSeq(0));
    }
}
