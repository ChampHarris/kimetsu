package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A player's input through the wire (Netcode, "Input frames"; Rule 2.1): frames stamped on the client, delayed by a
 * link, fed to the intake in arrival order, as the mod's session will. The string committed at tick 100 lands on beats
 * 117, 130 and 143 (the fight loop's own test, {@link CombatWorldTest}).
 */
class PlayerIntakeTest {
    private static final long FRAME = 16_667; // 60 frames a second
    private static final long PHASE = 7_000; // the client's ticks start 7 ms into the server's
    private static final FrameData SWIPE = Fixtures.swipe();

    private static long at(long tick, long offsetMs) {
        return Micros.ofTicks(tick) + offsetMs * 1000;
    }

    /** A client over a link: it polls input once a frame, ticks every 50 ms and sends its frames down an ordered link. */
    private static final class WireClient {
        private record Press(long stamp, PlayerIntake.Key key, boolean down) {}

        private record Sent(long arrival, PlayerIntake.Frame frame) {}

        final Arena.Player p;
        final PlayerIntake intake;
        long oneWay;
        long riseAt = Long.MAX_VALUE;
        long risenOneWay;
        final int jitterMicros;
        final Random rnd = new Random(7);
        final List<Press> presses = new ArrayList<>();
        final ArrayDeque<Sent> inFlight = new ArrayDeque<>();
        long nextTick = 1;
        long seq;
        int held;
        long lastArrival = Long.MIN_VALUE;

        WireClient(Arena.Player p, long oneWayMicros, int jitterMicros) {
            this.p = p;
            this.oneWay = oneWayMicros;
            this.jitterMicros = jitterMicros;
            this.intake = new PlayerIntake(Fixtures.timing(), 0, PlayerIntake.NO_SINK);
            intake.echo(0, 2 * oneWayMicros);
            intake.join(p.f);
        }

        /** Guard {@code offsetMs} from impact tick {@code impact}, let go 20 ms after both (CombatWorldTest's). */
        WireClient guard(long impact, long offsetMs) {
            presses.add(new Press(at(impact, offsetMs), PlayerIntake.Key.GUARD, true));
            presses.add(new Press(at(impact, Math.max(offsetMs, 0) + 20), PlayerIntake.Key.GUARD, false));
            return this;
        }

        /** From {@code atMicros} on, the link's one-way delay is {@code oneWayMicros} (bufferbloat). */
        WireClient rise(long atMicros, long oneWayMicros) {
            riseAt = atMicros;
            risenOneWay = oneWayMicros;
            return this;
        }

        long oneWayAt(long micros) {
            return micros >= riseAt ? risenOneWay : oneWay;
        }

        private void send(PlayerIntake.Frame f) {
            long jitter = jitterMicros == 0 ? 0 : rnd.nextInt(jitterMicros + 1);
            long arrival = Math.max(lastArrival, f.sendStampMicros() + oneWayAt(f.sendStampMicros()) + jitter);
            lastArrival = arrival;
            inFlight.add(new Sent(arrival, f));
        }

        /** Its client ticks that start before {@code untilMicros}: presses polled since go at the start, a pose at the end. */
        void runTo(long untilMicros) {
            while (Micros.ofTicks(nextTick) + PHASE < untilMicros) {
                long start = Micros.ofTicks(nextTick) + PHASE;
                List<PlayerIntake.Delta> deltas = new ArrayList<>();
                for (Press pr : presses) {
                    if (pr.stamp() <= start && pr.stamp() > start - Micros.PER_TICK) {
                        deltas.add(
                                pr.down()
                                        ? PlayerIntake.Delta.press(pr.key(), pr.stamp())
                                        : PlayerIntake.Delta.release(pr.key(), pr.stamp()));
                        held = pr.down()
                                ? held | pr.key().bit()
                                : held & ~pr.key().bit();
                    }
                }
                if (!deltas.isEmpty()) {
                    send(new PlayerIntake.Frame(seq++, start, FRAME, InputHorizon.NO_TICK, 0, held, deltas));
                }
                send(new PlayerIntake.Frame(seq++, start + 1000, FRAME, Micros.ofTicks(nextTick), 0, held, List.of()));
                nextTick++;
            }
        }

        /** Hands the intake every frame that arrived by {@code micros}, recording the poses it accepts. */
        void deliver(long micros) {
            while (!inFlight.isEmpty() && inFlight.peek().arrival() <= micros) {
                Sent s = inFlight.poll();
                if (intake.frame(s.frame(), s.arrival(), false)) {
                    long tick = Micros.tickOf(s.frame().tickTimeMicros());
                    double[] st = p.standAt(tick);
                    p.f.poses().put(s.frame().tickTimeMicros(), st[0], st[1], st[2], st[3], 0);
                }
            }
        }
    }

    /** Runs the server through tick {@code last}: before each step, what reached it, then the intake's expiry. */
    private static void run(Arena a, WireClient c, long last) {
        for (long n = a.now + 1; n <= last; n++) {
            long start = Micros.ofTicks(n);
            if (n % 20 == 0) { // the server's echo, each second
                c.intake.echo(start, 2 * c.oneWayAt(start));
            }
            c.runTo(start);
            c.deliver(start);
            c.intake.beforeStep(n);
            a.through(n);
        }
    }

    private static void threeVerdicts(long oneWayMicros, int jitterMicros, long decidedWithinTicks) {
        Arena a = new Arena();
        Arena.Player p = CombatWorldTest.backingOff(a.player(1).silentFrom(1)); // fed by the wire alone
        WireClient c = new WireClient(p, oneWayMicros, jitterMicros)
                .guard(117, -30)
                .guard(130, -100)
                .guard(143, 20);
        a.demon(10, new Arena.Script().at(100, 1, SWIPE, SWIPE, SWIPE));
        run(a, c, 160);

        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(Kind.DEFLECT, a.result(2, 1).kind());
        assertEquals(Kind.HIT, a.result(3, 1).kind()); // 20 ms late
        long[] impacts = {117, 130, 143};
        for (int k = 0; k < 3; k++) {
            long decided = a.tickOf(a.result(k + 1, 1));
            assertTrue(
                    decided > impacts[k] && decided <= impacts[k] + decidedWithinTicks,
                    "strike " + (k + 1) + " decided at " + decided + ", impact " + impacts[k]
                            + " (the hold cap would be about 8 ticks)");
        }
    }

    @Test
    void singlePlayer_theThreeVerdictsComeBackTheTickAfterTheirImpact() {
        threeVerdicts(1_000, 0, 1);
    }

    @Test
    void at150msRtt_theThreeVerdictsComeBackTwoTicksAfterTheirImpact_notAtTheHoldCap() {
        threeVerdicts(75_000, 10_000, 2);
    }

    /**
     * Bufferbloat 3 s before the string: the RTT rises from 20 to 150 ms. The minimum alone would keep ref 65 ms early
     * for 10 s, every frame failing the stamp check and every guard clamped late: Perfect turned to Hit.
     */
    @Test
    void afterTheRttRisesFrom20To150ms_theThreeVerdictsStillComeBackRight() {
        Arena a = new Arena();
        Arena.Player p = CombatWorldTest.backingOff(a.player(1).silentFrom(1));
        WireClient c = new WireClient(p, 10_000, 2_000)
                .rise(Micros.ofTicks(41), 75_000)
                .guard(117, -30)
                .guard(130, -100)
                .guard(143, 20);
        a.demon(10, new Arena.Script().at(100, 1, SWIPE, SWIPE, SWIPE));
        run(a, c, 160);

        assertEquals(Kind.PERFECT, a.result(1, 1).kind());
        assertEquals(Kind.DEFLECT, a.result(2, 1).kind());
        assertEquals(Kind.HIT, a.result(3, 1).kind());
        assertEquals(150_000, c.intake.rttMicros());
    }

    private static final class Judged implements PlayerIntake.Sink {
        final List<Input> inputs = new ArrayList<>();
        final List<Long> sent = new ArrayList<>();
        final List<Boolean> held = new ArrayList<>();
        final List<String> dropped = new ArrayList<>();

        @Override
        public void judged(Input in, PlayerIntake.Delta d, long frameTimeMicros, boolean wasHeld) {
            inputs.add(in);
            sent.add(d.stampMicros());
            held.add(wasHeld);
            assertEquals(FRAME, frameTimeMicros, "the frame time it was polled in");
        }

        @Override
        public void dropped(PlayerIntake.Delta d, PlayerIntake.Drop why) {
            dropped.add(d.key() + (d.down() ? " down " : " up ") + d.stampMicros() + " " + why);
        }
    }

    private static PlayerIntake.Frame frame(long seq, long send, int held, PlayerIntake.Delta... deltas) {
        return new PlayerIntake.Frame(seq, send, FRAME, InputHorizon.NO_TICK, 0, held, List.of(deltas));
    }

    private static PlayerIntake intake(Arena.Player p, Judged sink, int epoch) {
        PlayerIntake in = new PlayerIntake(Fixtures.timing(), epoch, sink);
        in.echo(0, 150_000);
        in.join(p.f);
        return in;
    }

    @Test
    void anArrivalConvertedWhileFrozenIsCheckedWithNoRtt_soAPressStandsAtItsStamp() {
        Judged frozen = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), frozen, 0);
        // frozen: the client's clock and the arrival are both pinned at 50·G, nothing in flight against it
        in.frame(
                frame(
                        0,
                        1_000_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_000_000)),
                1_000_000,
                true);
        assertEquals(1_000_000 - FRAME / 2, frozen.inputs.get(0).stampMicros());

        Judged running = new Judged();
        PlayerIntake same = intake(new Arena().player(1).silentFrom(1), running, 0);
        same.frame(
                frame(
                        0,
                        1_000_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_000_000)),
                1_000_000,
                false);
        // read as running, half the RTT makes the stamp 75 ms late: clamped to the 20 ms tolerance's edge
        assertEquals(925_000 + 20_000 - FRAME / 2, running.inputs.get(0).stampMicros());
    }

    @Test
    void afterAnEpoch_oldTimelinePressesAfterTheCutoffAreDropped_andNewOnesMayGoBack() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        in.frame(frame(0, 1_000_000, 0), 1_075_000, false);
        in.onEpoch(1, 1_000_000);

        // sent before the client heard of the epoch, stamped after the last tick before the stall
        in.frame(
                frame(
                        1,
                        1_020_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_020_000)),
                1_095_000,
                false);
        assertTrue(sink.inputs.isEmpty());
        assertEquals(List.of("GUARD down 1020000 OLD_EPOCH"), sink.dropped, "the log sees the drop");

        // the new timeline starts behind the old one's floors
        PlayerIntake.Frame next = new PlayerIntake.Frame(
                2,
                900_000,
                FRAME,
                InputHorizon.NO_TICK,
                1,
                PlayerIntake.Key.GUARD.bit(),
                List.of(PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 900_000)));
        in.frame(next, 975_000, false);
        assertEquals(List.of(Input.guard(900_000 - FRAME / 2)), sink.inputs);
    }

    @Test
    void aStalledPressWhoseAnchorNeverComesIsClampedAtItsDeadline_andNoLongerHoldsStrikesBack() {
        Judged sink = new Judged();
        Arena.Player p = new Arena().player(1).silentFrom(1);
        PlayerIntake in = intake(p, sink, 0);
        in.frame(frame(0, 1_000_000, 0), 1_075_000, false);
        // a 375 ms stall: the frame fails the check, its press waits for a later frame to anchor it
        in.frame(
                frame(
                        1,
                        1_050_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_050_000)),
                1_500_000,
                false);
        assertTrue(sink.inputs.isEmpty());
        assertEquals(1_050_000 - FRAME / 2, p.f.earliestHeldMicros());

        // the deflect window it could answer (150 ms × the assist scale 1.5), then the hold cap
        long deadline = 1_050_000 + 225_000 + p.f.holdCapMicros();
        long due = (deadline + Micros.PER_TICK - 1) / Micros.PER_TICK;
        in.beforeStep(due - 1);
        assertTrue(sink.inputs.isEmpty());
        in.beforeStep(due);
        long ref = 1_500_000 - 75_000;
        assertEquals(List.of(Input.guard(ref - p.f.toleranceMicros() - FRAME / 2)), sink.inputs);
        assertEquals(List.of(true), sink.held, "it waited for an anchor");
        assertEquals(Long.MAX_VALUE, p.f.earliestHeldMicros());
    }

    @Test
    void aStalledPressStampedAfterAnEpochsCutoff_isDroppedWhenTheEpochComes_andTheLogIsTold() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        in.frame(frame(0, 1_000_000, 0), 1_075_000, false);
        in.frame(
                frame(
                        1,
                        1_050_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_050_000)),
                1_500_000,
                false); // held for an anchor
        in.onEpoch(1, 1_020_000); // the server stalled: the press was stamped after its last tick
        assertTrue(sink.inputs.isEmpty());
        assertEquals(List.of("GUARD down 1050000 OLD_EPOCH"), sink.dropped);
    }

    @Test
    void aGuardTheClientSaysIsUpWithNoReleaseSent_isReleasedAtTheFramesStamp_once() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        in.frame(
                frame(
                        0,
                        1_000_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_000_000)),
                1_075_000,
                false);
        in.frame(frame(1, 1_050_000, 0), 1_125_000, false); // its release was lost
        in.frame(frame(2, 1_100_000, 0), 1_175_000, false);

        assertEquals(
                List.of(Input.guard(1_000_000 - FRAME / 2), Input.of(Input.Kind.GUARD_UP, 1_050_000 - FRAME / 2)),
                sink.inputs);
        assertEquals(List.of(1_000_000L, 1_050_000L), sink.sent);
        assertEquals(List.of(false, false), sink.held, "judged as they came");
        assertTrue(sink.dropped.isEmpty());
    }

    @Test
    void aReleaseOfAKeyTheTimelineNeverHeld_isNotPassedOn() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        in.frame(
                frame(0, 1_000_000, 0, PlayerIntake.Delta.release(PlayerIntake.Key.GUARD, 1_000_000)),
                1_075_000,
                false);
        assertTrue(sink.inputs.isEmpty());
    }

    @Test
    void aBreatheReleaseCarriesItsSlot_andAnAttackItsFall() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        in.frame(
                frame(
                        0,
                        1_000_000,
                        0,
                        PlayerIntake.Delta.press(PlayerIntake.Key.BREATHE, 990_000),
                        PlayerIntake.Delta.release(PlayerIntake.Key.BREATHE, 1_000_000, 2),
                        PlayerIntake.Delta.attack(1_000_000, 3.5)),
                1_075_000,
                false);
        assertEquals(
                List.of(
                        Input.of(Input.Kind.BREATHE_DOWN, 990_000 - FRAME / 2),
                        Input.release(1_000_000 - FRAME / 2, 2),
                        Input.attackFalling(1_000_000 - FRAME / 2, 3.5)),
                sink.inputs);
    }

    @Test
    void onlyAnAttackPressAsksTheFallProbe_atItsJudgedStamp() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        List<Long> asked = new ArrayList<>();
        in.fallProbe(at -> {
            asked.add(at);
            return 2.5;
        });
        in.frame(
                frame(
                        0,
                        1_000_000,
                        0,
                        PlayerIntake.Delta.press(PlayerIntake.Key.JUMP, 980_000),
                        PlayerIntake.Delta.press(PlayerIntake.Key.SNEAK, 985_000),
                        PlayerIntake.Delta.press(PlayerIntake.Key.ATTACK, 1_000_000)),
                1_075_000,
                false);
        assertEquals(List.of(1_000_000 - FRAME / 2), asked);
        assertEquals(
                List.of(
                        Input.of(Input.Kind.JUMP, 980_000 - FRAME / 2),
                        Input.of(Input.Kind.SNEAK, 985_000 - FRAME / 2),
                        Input.attackFalling(1_000_000 - FRAME / 2, 2.5)),
                sink.inputs);
    }

    @Test
    void aRejoinedFighter_takesTheRttToleranceAndHoldCapTheIntakeMeasured() {
        Arena.Player old = new Arena().player(1).silentFrom(1);
        PlayerIntake in = intake(old, new Judged(), 0);
        in.frame(frame(0, 1_000_000, 0), 1_075_000, false);
        in.frame(frame(1, 1_050_000, 0), 1_131_000, false); // 6 ms of jitter

        Arena.Player fresh = new Arena().player(1).silentFrom(1); // a rebuild's new fighter
        in.join(fresh.f);
        assertEquals(150_000, fresh.f.rttMicros());
        assertEquals(old.f.toleranceMicros(), fresh.f.toleranceMicros());
        assertEquals(old.f.holdCapMicros(), fresh.f.holdCapMicros());
    }

    private static PlayerIntake fastIntake(Arena.Player p) { // a 20 ms link
        PlayerIntake in = new PlayerIntake(Fixtures.timing(), 0, PlayerIntake.NO_SINK);
        in.echo(0, 20_000);
        in.join(p.f);
        return in;
    }

    private static PlayerIntake.Frame pose(long seq, long send, long tickTime) {
        return new PlayerIntake.Frame(seq, send, FRAME, tickTime, 0, 0, List.of());
    }

    /**
     * A client holds its pose frames back past a red strike's impact, moves out of the sweep, then files where it
     * moved to under a tick before it moved (netcode review): refused, two ticks past what the server can vouch for.
     */
    @Test
    void aPoseHeldBack_cantBeFiledUnderATimeTheServerCantVouchFor() {
        PlayerIntake in = fastIntake(new Arena().player(1).silentFrom(1));
        assertTrue(in.frame(pose(0, 1_008_000, 1_000_000), 1_018_000, false)); // an honest pose
        // 250 ms later, a frame claiming it was sent at tick 21 with tick 21's pose
        assertFalse(in.frame(pose(1, 1_050_000, 1_050_000), 1_268_000, false));
        assertTrue(in.frame(pose(2, 1_258_000, 1_250_000), 1_268_000, false)); // the tick it's really from
    }

    @Test
    void aFighterWithNoPosesFromItsClient_getsTheServersOwn_atOnceAndEachHoldCapAfter() {
        Arena.Player p = new Arena().player(1).silentFrom(1);
        PlayerIntake in = fastIntake(p);
        assertEquals(1_000_000 - 10_000, in.serverPose(20)); // none yet: as of half the RTT ago
        assertEquals(PlayerIntake.NO_SERVER_POSE, in.serverPose(21));
        long cap = p.f.holdCapMicros();
        long next = (990_000 + cap) / Micros.PER_TICK + 1; // the first step whose hold cap reaches past it
        assertEquals(PlayerIntake.NO_SERVER_POSE, in.serverPose(next - 1));
        assertEquals(Micros.ofTicks(next) - 10_000, in.serverPose(next));
        // the client's own poses again: none from the server, and none of the client's filed before the server's
        long tick = Micros.ofTicks(next + 1);
        assertTrue(in.frame(pose(0, tick + 8_000, tick), tick + 18_000, false));
        assertEquals(PlayerIntake.NO_SERVER_POSE, in.serverPose(next + 2));
        long before = Micros.ofTicks(next) - 50_000;
        assertFalse(in.frame(pose(1, tick + 9_000, before), tick + 19_000, false));
    }

    @Test
    void afterAnEpoch_poseHistoriesStartOver_soTheNewTimelinesPosesGoIn() {
        Arena a = new Arena();
        Arena.Player p = a.player(1).silentFrom(1);
        p.f.poses().put(2_000_000, 0, 64, 0, 0, 0); // ahead of everything the new timeline sends first
        a.world.onEpoch(1_900_000);
        assertEquals(0, p.f.poses().size());
        p.f.poses().put(1_000_000, 0, 64, 0, 0, 0);
    }

    /**
     * The release came before the stall, and the timeline dropped it with the rest stamped after the cutoff: the next
     * frame's held state releases the guard again (code review), once, and nothing it didn't drop.
     */
    @Test
    void afterAnEpoch_aReleaseTheTimelineDropped_isSentAgainFromTheHeldState() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        in.frame(
                frame(
                        0,
                        1_000_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_000_000)),
                1_075_000,
                false);
        in.frame(
                frame(1, 1_050_000, 0, PlayerIntake.Delta.release(PlayerIntake.Key.GUARD, 1_050_000)),
                1_125_000,
                false);
        in.onEpoch(1, 1_020_000);
        sink.inputs.clear();
        in.frame(new PlayerIntake.Frame(2, 900_000, FRAME, InputHorizon.NO_TICK, 1, 0, List.of()), 975_000, false);
        assertEquals(List.of(Input.of(Input.Kind.GUARD_UP, 900_000 - FRAME / 2)), sink.inputs);
        sink.inputs.clear();
        in.frame(new PlayerIntake.Frame(3, 950_000, FRAME, InputHorizon.NO_TICK, 1, 0, List.of()), 1_025_000, false);
        assertTrue(sink.inputs.isEmpty(), "released once: " + sink.inputs);
    }

    @Test
    void aPressHeldForItsAnchorWhenThePlayerLeaves_neverReachesTheNextFighter() {
        Judged sink = new Judged();
        Arena.Player p = new Arena().player(1).silentFrom(1);
        PlayerIntake in = intake(p, sink, 0);
        in.frame(frame(0, 1_000_000, 0), 1_075_000, false);
        in.frame(
                frame(
                        1,
                        1_050_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_050_000)),
                1_500_000,
                false);
        in.leave(); // died with the press held for a stall's anchor
        Arena.Player respawned = new Arena().player(1).silentFrom(1);
        in.join(respawned.f);
        in.beforeStep(60); // past its deadline
        assertTrue(sink.inputs.isEmpty(), sink.inputs.toString());
        assertEquals(Long.MAX_VALUE, respawned.f.earliestHeldMicros());
    }

    /** No honest client sends a press before it happens: one stamped after its frame is taken at the frame's stamp. */
    @Test
    void aPressStampedAfterTheFrameCarryingIt_isTakenAtTheFramesStamp() {
        Judged sink = new Judged();
        PlayerIntake in = intake(new Arena().player(1).silentFrom(1), sink, 0);
        in.frame(
                frame(
                        0,
                        1_000_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_040_000)),
                1_075_000,
                false);
        assertEquals(List.of(Input.guard(1_000_000 - FRAME / 2)), sink.inputs);
    }

    @Test
    void framesBeforeAnyFighter_areCheckedButFeedNothing() {
        Judged sink = new Judged();
        PlayerIntake in = new PlayerIntake(Fixtures.timing(), 0, sink);
        in.echo(0, 150_000);
        in.frame(
                frame(
                        0,
                        1_000_000,
                        PlayerIntake.Key.GUARD.bit(),
                        PlayerIntake.Delta.press(PlayerIntake.Key.GUARD, 1_000_000)),
                1_075_000,
                false);
        in.beforeStep(30);
        assertTrue(sink.inputs.isEmpty());
        assertEquals(0, in.lastSeq());
    }
}
