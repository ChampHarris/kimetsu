package dev.champ.kimetsu.core.fight;

import static dev.champ.kimetsu.core.Asserts.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.MoveRules;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.machine.TimedAnswers;
import dev.champ.kimetsu.core.machine.Timeline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The movement bound (D-070, {@link MoveBound}): honest movement is never clamped (sprinting, chained sprint-jumping,
 * a ledge, a low frame rate, a slow server, a Step the machine accepted, buffered or held behind the barrier), and
 * what no honest client does is (a teleport, a Step's speed with no Step, a Step refused, an over-rise). The honest
 * traces come from vanilla's own physics ({@link Vanilla}). The mechanism runs on open ground's numbers, tight enough
 * that a Step's envelope shows; the shipped data, measured on stairs, has its own tests (D-072).
 */
final class MoveBoundTest {
    // open ground's numbers (1.2 × chained sprint-jumping's 2.35 and a jump's 1.25: D-070's before the MCTest trace),
    // under which a Step's speed with no Step passes the bound, so every envelope and re-filing rule is visible
    private static final MoveRules RULES = new MoveRules(6, 2.82, 1.5, 2.35, 1.5, 0.225, 2, 2.0);
    private static final MoveRules SHIPPED = Fixtures.movement();
    private static final double[] STEP = {0.7, 0.6, 0.5, 0.5, 0.4, 0.3};
    private static final double STANDING = Double.NaN;
    private static final long HOLD_CAP_MAX = Micros.ofMillis(Fixtures.timing().holdCapMaxMs());

    private final Outbox out = new Outbox();
    private final List<Outbox.PoseClamped> clamped = new ArrayList<>();
    private Timeline timeline;
    private Fighter f;
    private long n = 1; // the next tick to run
    private double x;
    private double y = 64;
    private double z;

    MoveBoundTest() {
        make(0);
    }

    private void make(double breathSpent) {
        make(breathSpent, RULES);
    }

    private void make(double breathSpent, MoveRules rules) {
        PulseSchedule pulse = new PulseSchedule(13);
        timeline = new Timeline(Arena.combatant(pulse, breathSpent), 1);
        f = new Fighter(
                1,
                timeline,
                new InputHorizon(Fixtures.timing()),
                pulse,
                Fixtures.PLAYER_HALF_WIDTH,
                Fixtures.PLAYER_HEIGHT,
                Fixtures.PLAYER_EYE,
                InputHorizon.holdCapMicros(
                        Fixtures.timing(), 0, Micros.ofMillis(Fixtures.timing().toleranceMinMs())));
        f.movement(rules, Fixtures.step(), Fixtures.machine().bufferTicks());
        n = 1;
    }

    /** Runs tick {@code n}: the presses received act, then the poses staged since the last tick are filed. */
    private void tick() {
        timeline.beginTick(n);
        f.filePoses(n, out);
        timeline.endTick(n);
        for (Outbox.Event e : out.drain()) {
            if (e instanceof Outbox.PoseClamped c) {
                clamped.add(c);
            }
        }
        n++;
    }

    /** The client's tick {@code n} moved it by ({@code dx}, {@code dy}, 0): its pose is staged, then the tick runs. */
    private void move(double dx, double dy) {
        x += dx;
        y += dy;
        f.stagePose(Micros.ofTicks(n), x, y, z, 0, 0, STANDING, 1);
        tick();
    }

    private void move(double[] dx) {
        for (double d : dx) {
            move(d, 0);
        }
    }

    /** Stands still long enough that every pose so far is settled (the history and a Step's lead behind). */
    private void settle() {
        for (int i = 0; i < 25; i++) {
            move(0, 0);
        }
    }

    /** The bounded pose at tick {@code tick}. */
    private Pose filed(long tick) {
        Pose p = new Pose();
        f.poses().at(Micros.ofTicks(tick), p);
        return p;
    }

    private void step(long stampMicros) {
        timeline.receive(Input.of(Input.Kind.STEP, stampMicros));
    }

    // ------------------------------------------------------------------------------------------------- never clamped

    @Test
    void aSprintIsNeverClamped() {
        move(Vanilla.run(false, 0.1, 80));
        settle();
        assertEquals(List.of(), clamped);
        assertNear(1.718, f.takeMoveSecond().fastest(), 0.01); // vanilla's sprint: 0.286 a tick
    }

    @Test
    void chainedSprintJumpingIsNeverClamped() {
        Vanilla v = new Vanilla(true, 0.1);
        for (int i = 0; i < 120; i++) {
            v.tick();
            move(v.dx, v.dy);
        }
        settle();
        assertEquals(List.of(), clamped);
        MoveBound.Second s = f.takeMoveSecond();
        assertNear(2.379, s.fastest(), 0.01); // the honest maximum the window is 1.2 times
        assertNear(1.252, s.rise(), 0.01); // a jump
        assertTrue(s.fastest() * 1.18 < RULES.windowBlocks(), "the bound has its margin: " + s.fastest());
    }

    @Test
    void aSprintOffALedgeIsNeverClamped() {
        move(Vanilla.run(false, 0.1, 30));
        double[] fall = {-0.08, -0.16, -0.23, -0.30, -0.38, -0.45, -0.52, -0.58}; // 3 blocks down in 8 ticks
        for (double d : fall) {
            move(0.28, d);
        }
        move(Vanilla.run(false, 0.1, 10));
        settle();
        assertEquals(List.of(), clamped);
    }

    @Test
    void ticksBunchedAtALowFrameRateAreNeverClamped() {
        Vanilla v = new Vanilla(true, 0.1);
        int[] perFrame = {1, 2, 1, 1, 2, 1}; // 15 FPS: 20 client ticks in 15 frames, each frame's poses at once
        for (int frame = 0; frame < 90; frame++) {
            for (int k = 0; k < perFrame[frame % perFrame.length]; k++) {
                v.tick();
                x += v.dx;
                y += v.dy;
                f.stagePose(Micros.ofTicks(n + k), x, y, z, 0, 0, STANDING, 1);
            }
            long until = n + perFrame[frame % perFrame.length];
            while (n < until) {
                tick();
            }
        }
        settle();
        assertEquals(List.of(), clamped);
    }

    @Test
    void aSlowServerScalesTheBound() {
        // at 15 TPS a combat tick lasts 66.7 ms, and the client still runs 20 ticks a second: 4/3 of its movement each
        assertEquals(List.of(), slowServer(4.0 / 3), "scaled by the tick rate");
        assertTrue(!slowServer(1).isEmpty(), "unscaled, sustained sprint-jumping at 1.33× passes the bound");
    }

    private List<Outbox.PoseClamped> slowServer(double scale) {
        clamped.clear();
        make(0);
        x = 0;
        Vanilla v = new Vanilla(true, 0.1);
        int clientTicks = 0;
        for (int k = 0; k < 120; k++) {
            int due = (int) Math.floor((k + 1) * 4.0 / 3); // the client ticks run by the end of combat tick k
            while (clientTicks < due) {
                v.tick();
                x += v.dx;
                y += v.dy;
                clientTicks++;
            }
            f.stagePose(Micros.ofTicks(n), x, y, z, 0, 0, STANDING, scale);
            tick();
        }
        settle();
        return List.copyOf(clamped);
    }

    @Test
    void aGapInTheFramesWidensTheBound() {
        move(0, 0);
        for (int i = 0; i < 20; i++) {
            tick(); // a second with no frames (a stall, a lag spike): nothing is staged
        }
        move(5, 0); // it walked on meanwhile: 5 blocks over 21 ticks
        settle();
        assertEquals(List.of(), clamped);
    }

    @Test
    void aSpeedThatJustEndedDoesntClampItsWindow() {
        move(0, 0);
        for (int i = 0; i < 12; i++) {
            x += 0.5; // 3.0 over 6 ticks: over the bound, under its Swiftness-scaled 3.07
            f.stagePose(Micros.ofTicks(n), x, y, z, 0, 0, STANDING, 1.09);
            tick();
        }
        move(0.5, 0); // the effect ran out this tick, its momentum didn't
        settle();
        assertEquals(List.of(), clamped);
    }

    @Test
    void anAcceptedStepIsNeverClamped() {
        step(Micros.ofTicks(n - 1) + 20_000); // standing: its dash shows from the client's next tick
        move(STEP);
        settle();
        assertEquals(List.of(), clamped);

        move(Vanilla.run(false, 0.1, 30)); // out of a sprint
        step(Micros.ofTicks(n - 1) + 30_000);
        move(STEP);
        move(Vanilla.run(false, 0.1, 10));
        settle();
        assertEquals(List.of(), clamped);
        assertEquals(2, timeline.live().answers().count(TimedAnswers.Kind.STEP));
        assertEquals(0, f.takeMoveSecond().freeDashes(), "a Step paid for isn't a free dash");
    }

    @Test
    void aStepStartedUpToItsLeadAfterItsDashIsCovered() {
        move(Vanilla.run(false, 0.1, 30)); // out of a sprint
        move(new double[] {0.7, 0.6, 0.5, 0.5}); // its dash began from the client's own buffer and clock
        step(Micros.ofTicks(n - 1) + 20_000); // and the server's Step starts 4 ticks in: its envelope's lead
        move(new double[] {0.4, 0.3});
        move(Vanilla.run(false, 0.1, 10));
        settle();
        assertEquals(1, timeline.live().answers().count(TimedAnswers.Kind.STEP));
        assertEquals(List.of(), clamped);
    }

    @Test
    void aBufferedStepIsNeverClamped() {
        timeline.receive(Input.of(Input.Kind.ATTACK, Micros.ofTicks(n - 1) + 10_000)); // Chain 1: 3 + 2 + 6 ticks
        move(0, 0);
        move(0, 0);
        move(0, 0);
        step(Micros.ofTicks(n - 1) + 10_000); // in its active frames: the machine holds it for its recovery
        move(STEP); // a client that dashed at once, before the server's machine let it
        move(0, 0);
        settle();
        TimedAnswers a = timeline.live().answers();
        assertEquals(1, a.count(TimedAnswers.Kind.STEP), "the buffered Step ran");
        assertTrue(a.at(TimedAnswers.Kind.STEP, 0) > Micros.ofTicks(3) + 10_000, "later than its press");
        assertEquals(List.of(), clamped);
    }

    @Test
    void aStepHeldBehindTheBarrierIsFiledAgainNotClamped() {
        move(0, 0);
        long impact = Micros.ofTicks(n + 1);
        timeline.commitStrike(7, impact);
        move(0, 0);
        move(0, 0);
        step(Micros.ofTicks(n - 1) + 20_000); // after the impact: it waits for the strike's verdict
        move(STEP);
        assertEquals(0, timeline.live().answers().count(TimedAnswers.Kind.STEP), "held");
        assertNear(RULES.windowBlocks(), filed(9).x(), 1e-9); // its dash's end, bounded with no Step to open it
        timeline.resolve(7); // decided: the Step goes through at its stamp
        move(0, 0);
        assertEquals(1, timeline.live().answers().count(TimedAnswers.Kind.STEP));
        assertNear(3.0, filed(9).x(), 1e-9); // filed again from the raw poses
        settle();
        assertEquals(List.of(), clamped, "a pose filed again before it settled publishes nothing");
        assertEquals(0, f.takeMoveSecond().clamps());
    }

    // ------------------------------------------------------------------------------------------------- clamped

    @Test
    void aTeleportIsClamped() {
        move(0, 0);
        move(10, 0);
        Pose p = filed(n - 1);
        assertNear(RULES.windowBlocks(), p.x(), 1e-9); // pulled back along the line to the bound
        assertNear(64, p.y(), 1e-9);
        assertEquals(List.of(), clamped, "published only once settled");
        settle();
        assertTrue(!clamped.isEmpty());
        Outbox.PoseClamped c = clamped.get(0);
        assertEquals(1, c.fighter());
        assertEquals(2, c.tick());
        assertNear(10, c.rawBlocks(), 1e-9);
        assertNear(RULES.windowBlocks(), c.allowedBlocks(), 1e-9);
        assertEquals(clamped.size(), f.takeMoveSecond().clamps());
        // the engine's pose walks after the real one at the bound's speed, and meets it
        assertNear(10, filed(n - 1).x(), 1e-9);
        assertNear(2 * RULES.windowBlocks(), filed(8).x(), 1e-9);
    }

    @Test
    void aStepsSpeedWithNoStepIsClamped() {
        double[] dash = new double[12];
        Arrays.fill(dash, 0.7);
        move(dash);
        settle();
        assertTrue(clamped.size() >= 6, "sustained 0.7 a tick passes 2.82 over 6 ticks: " + clamped.size());
    }

    @Test
    void aStepRefusedForBreathIsClamped() {
        make(95); // 5 Breath left, under its 7.5
        move(0, 0);
        step(Micros.ofTicks(n - 1) + 20_000);
        move(STEP); // the client dashed anyway
        settle();
        assertEquals(0, timeline.live().answers().count(TimedAnswers.Kind.STEP), "refused");
        assertEquals(State.NEUTRAL, timeline.live().state());
        assertTrue(!clamped.isEmpty(), "3 blocks in 6 ticks with no Step");
        assertNear(RULES.windowBlocks(), filed(7).x(), 1e-9);
    }

    @Test
    void anOverRiseIsClamped() {
        move(0, 0);
        for (int i = 0; i < 6; i++) {
            move(0, 0.5); // 3 blocks up in 6 ticks: no jump does that
        }
        Pose p = filed(n - 1);
        assertNear(64 + RULES.riseBlocks(), p.y(), 1e-9);
        settle();
        assertTrue(!clamped.isEmpty());
    }

    // ------------------------------------------------------------------------------------------------- opened

    @Test
    void anImpulseOpensTheBound() {
        move(0, 0);
        f.rttMicros(100_000); // open for its round trip and the grace: 4 ticks
        f.impulse(n, 4.4, HOLD_CAP_MAX); // a vanilla knockback of 0.4: 0.4 / 0.09 blocks
        move(new double[] {0.6, 0.6, 0.6, 0.6, 0.5, 0.4}); // 3.3 in 6 ticks, carried
        settle();
        assertEquals(List.of(), clamped);

        move(new double[] {0.6, 0.6, 0.6, 0.6, 0.5, 0.4}); // the same with no impulse: over
        settle();
        assertTrue(!clamped.isEmpty());
    }

    @Test
    void aPushOpensTheBoundForItsDistance_fromItsDecisionToItsEndAndTheGrace() {
        move(0, 0);
        long decided = n - 1; // decided in the last tick, its client pushed from this one
        f.pushed(
                decided,
                decided,
                new Effect.Push(1, 0, 2.0, 4, 0, n, Effect.Push.NO_SPLAT, Micros.ofTicks(n) - 20_000),
                10);
        move(new double[] {0.5, 0.5, 0.5, 0.5, 0.6, 0.6}); // the push, then sprint-jumping: 3.2 in 6 ticks
        settle();
        assertEquals(List.of(), clamped);

        move(new double[] {0.5, 0.5, 0.5, 0.5, 0.6, 0.6}); // the same with no push: over open ground's 2.82
        settle();
        assertTrue(!clamped.isEmpty());
    }

    /**
     * A client that claims a 5 s RTT (it can delay its echoes) gets an impulse's bound open for a round trip of twice the
     * longest hold, 900 ms, plus the grace: 20 ticks, not 102 (the netcode review, D-079).
     */
    @Test
    void anImpulseOnAClaimedHugeRtt_opensTheBoundForACappedRoundTrip() {
        double[] carried = {0.6, 0.6, 0.6, 0.6, 0.5, 0.4}; // 3.3 in 6 ticks
        move(0, 0);
        f.rttMicros(5_000_000);
        f.impulse(n, 4.4, HOLD_CAP_MAX);
        for (int i = 0; i < 14; i++) {
            move(0, 0);
        }
        move(carried); // from 15 ticks on: a window still overlaps the 20
        settle();
        assertEquals(List.of(), clamped);

        make(0);
        move(0, 0);
        f.rttMicros(5_000_000);
        f.impulse(n, 4.4, HOLD_CAP_MAX);
        for (int i = 0; i < 22; i++) {
            move(0, 0);
        }
        move(carried); // from 23 ticks on: past it
        settle();
        assertTrue(!clamped.isEmpty());
    }

    @Test
    void anImpulseOpensTheBoundUpward() {
        move(0, 0);
        f.rttMicros(100_000);
        f.impulse(n, 4.4, HOLD_CAP_MAX); // an explosion's lift
        for (int i = 0; i < 6; i++) {
            move(0, 0.55); // 3.3 up in 6 ticks, carried
        }
        settle();
        assertEquals(List.of(), clamped);
    }

    @Test
    void anUnboundedScaleIsNeverClamped() {
        move(0, 0);
        for (int i = 0; i < 10; i++) {
            x += 2; // a horse, an elytra, ice
            f.stagePose(Micros.ofTicks(n), x, y, z, 0, 0, STANDING, Double.POSITIVE_INFINITY);
            tick();
        }
        settle();
        assertEquals(List.of(), clamped);
        assertNear(0, filed(n - 1).x() - x, 1e-9);
    }

    @Test
    void aServerTeleportStartsTheBoundAfresh() {
        move(0, 0);
        f.teleported();
        move(30, 0); // /tp, a pearl, chorus fruit: the server put it there
        assertNear(30, filed(n - 1).x(), 1e-9);
        move(10, 0); // but from there it's bounded again
        assertNear(30 + RULES.windowBlocks(), filed(n - 1).x(), 1e-9);
        settle();
        assertTrue(!clamped.isEmpty());
        assertEquals(3, clamped.get(0).tick(), "the teleport itself wasn't clamped, the move after it was");
    }

    @Test
    void theStageDropsItsOldestPastItsSize() {
        for (int i = 0; i < 40; i++) {
            f.stagePose(Micros.ofTicks(n + i), i * 0.2, y, z, 0, 0, STANDING, 1); // a stall's 2 s of frames at once
        }
        tick();
        assertEquals(32, f.poses().size());
        assertEquals(Micros.ofTicks(9), f.poses().oldestTime(), "the 8 oldest of 40 went");
        while (n <= 40) {
            tick();
        }
        x = 39 * 0.2;
        settle();
        assertEquals(8, f.takeMoveSecond().dropped());
    }

    @Test
    void aTeleportStaysABaselineWhenTheStageOverflows() {
        move(0, 0);
        f.teleported();
        for (int i = 0; i < 40; i++) {
            f.stagePose(Micros.ofTicks(n + i), 100 + i * 0.2, y, z, 0, 0, STANDING, 1); // a /tp, then a stall's frames
        }
        tick();
        while (n <= 41) {
            tick();
        }
        x = 100 + 39 * 0.2;
        settle();
        assertEquals(List.of(), clamped, "the 8 dropped took the teleport's pose, not the teleport");
    }

    @Test
    void anOlderPoseIsRefused() {
        move(0, 0);
        assertThrows(
                IllegalArgumentException.class, () -> f.stagePose(Micros.ofTicks(n - 5), x, y, z, 0, 0, STANDING, 1));
        assertThrows(IllegalArgumentException.class, () -> f.stagePose(Micros.ofTicks(n), x, y, z, 0, 0, STANDING, 0));
    }

    @Test
    void aFreeDashIsCountedNotClamped() {
        move(0, 0);
        move(2.6, 0); // under the bound, over the honest maximum, no Step
        move(new double[5]);
        settle();
        assertEquals(List.of(), clamped);
        assertTrue(f.takeMoveSecond().freeDashes() >= 1);
    }

    @Test
    void anEpochStartsTheBoundOver() {
        move(0, 0);
        move(1, 0);
        f.clearPoses();
        f.stagePose(Micros.ofTicks(1), 50, y, z, 0, 0, STANDING, 1); // the new timeline starts behind the old
        timeline.beginTick(n);
        f.filePoses(n, out);
        timeline.endTick(n);
        assertEquals(1, f.poses().size());
        assertNear(50, filed(1).x(), 1e-9);
    }

    @Test
    void theSlackFollowsTheScale() {
        f.stagePose(Micros.ofTicks(n), 0, y, z, 0, 0, STANDING, 1);
        tick();
        f.stagePose(Micros.ofTicks(n), 0.2, y, z, 0, 0, STANDING, 1.5);
        tick();
        assertNear(RULES.windowBlocks() / RULES.windowTicks() * 1.5, f.moveBoundBlocksPerTick(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------------- the shipped
    // data

    @Test
    void sprintJumpingUpStairsIsNeverClampedByTheShippedData() {
        make(0, SHIPPED);
        move(0, 0);
        for (int i = 0; i < 40; i++) {
            move(0.664, 0.664); // the MCTest trace's fastest: 3.98 across and 3.99 up over 6 ticks (D-072)
        }
        settle();
        assertEquals(List.of(), clamped);
        MoveBound.Second s = f.takeMoveSecond();
        assertNear(3.984, s.fastest(), 1e-6);
        assertEquals(0, s.freeDashes(), "the honest maximum isn't a free dash");
        assertTrue(s.fastest() * 1.19 < SHIPPED.windowBlocks(), "the bound has its margin across: " + s.fastest());
        assertTrue(s.rise() * 1.19 < SHIPPED.riseBlocks(), "and up: " + s.rise());
    }

    @Test
    void theShippedDataStillClampsATeleportAndSustainedSpeed() {
        make(0, SHIPPED);
        move(0, 0);
        move(10, 0); // a teleport no server made
        assertNear(SHIPPED.windowBlocks(), filed(n - 1).x(), 1e-9);
        settle();
        assertTrue(!clamped.isEmpty(), "a teleport");

        clamped.clear();
        double[] fast = new double[12];
        Arrays.fill(fast, 1.0); // a speed hack: 6 blocks over 6 ticks
        move(fast);
        settle();
        assertTrue(clamped.size() >= 6, "sustained 1.0 a tick passes 4.78 over 6 ticks: " + clamped.size());
    }

    @Test
    void theShippedDataCountsAStepFakedOnLevelGround() {
        make(0, SHIPPED);
        move(0, 0);
        move(Vanilla.run(true, 0.1, 60)); // chained sprint-jumping, the honest flat maximum
        settle();
        assertEquals(0, f.takeMoveSecond().freeDashes(), "honest sprint-jumping isn't a free dash");

        move(new double[10]);
        move(STEP); // a Step's dash with no Step pressed or paid: under the bound, so not clamped
        move(new double[10]);
        settle();
        assertEquals(List.of(), clamped);
        assertTrue(f.takeMoveSecond().freeDashes() >= 1, "the cheat the flag is for (D-073)");
    }

    /**
     * Vanilla's horizontal physics on flat ground (26.3 {@code LivingEntity.travelInAir}, {@code
     * Player.jumpFromGround}), holding forward and sprint: on the ground a sprint adds 0.13 a tick and keeps 0.546 of
     * its speed, in the air 0.026 and 0.91; a jump adds 0.2 forward and 0.42 up, then gravity 0.08 with drag 0.98.
     */
    static final class Vanilla {
        private final boolean jump;
        private final double speed;
        private double v;
        private double vy;
        private double height;
        private boolean ground = true;
        double dx;
        double dy;

        Vanilla(boolean jump, double speed) {
            this.jump = jump;
            this.speed = speed;
        }

        void tick() {
            boolean onGround = ground;
            if (onGround && jump) {
                vy = 0.42;
                v += 0.2;
            }
            v += onGround ? speed * 1.3 : 0.02 * 1.3;
            dx = v;
            double before = height;
            height += vy;
            if (height <= 0) {
                height = 0;
                vy = 0;
                ground = true;
            } else {
                ground = false;
                vy = (vy - 0.08) * 0.98;
            }
            dy = height - before;
            v *= onGround ? 0.6 * 0.91 : 0.91;
        }

        /** Its horizontal displacement over {@code ticks}, from standing. */
        static double[] run(boolean jump, double speed, int ticks) {
            Vanilla v = new Vanilla(jump, speed);
            double[] out = new double[ticks];
            for (int i = 0; i < ticks; i++) {
                v.tick();
                out[i] = v.dx;
            }
            return out;
        }
    }
}
