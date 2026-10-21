package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.hit.SwingTrack;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Late events (D-042): a hit swept late and a payment decided after its impact are judged as of their own tick, so a
 * demon that learns them late, in any order, ends exactly as one that learnt each on its tick.
 */
class DemonReplayTest {
    private static final long MIN = Long.MIN_VALUE;
    private static final FrameData CHAIN = Fixtures.chainHit(1, true);

    /** Something that happens to the demon at {@code tick}, learnt {@code delay} ticks later. */
    private record Event(long tick, int delay, boolean payment, int fighter, double damage, double posture) {}

    private static void deliver(Demon d, Event e, SwingHit h) {
        if (e.payment()) {
            d.paid(1000 + e.tick(), e.tick(), Verdict.Kind.DEFLECT, e.posture(), 0);
        } else {
            h.set(e.tick(), e.fighter(), e.tick(), CHAIN, 0, SwingTrack.BODY, e.damage(), e.posture(), 0, -2, 0);
            assertEquals(HitResult.LANDED, d.hit(h));
        }
    }

    private static Demon demon() {
        return new Demon(10, Fixtures.demon(), Fixtures.hits(), 1, 34, 0, 64, 0, 0);
    }

    @Test
    void hitsAndPaymentsLearntLate_inAnyOrder_endAsIfLearntOnTime() {
        SwingHit h = new SwingHit(CHAIN);
        for (int seed = 0; seed < 200; seed++) {
            Random r = new Random(seed);
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                events.add(new Event(
                        10 + r.nextInt(170),
                        r.nextInt(10),
                        r.nextInt(4) == 0,
                        1 + r.nextInt(3),
                        r.nextInt(3) * 0.5,
                        r.nextInt(40)));
            }
            Demon onTime = demon();
            Demon late = demon();
            for (long n = 1; n <= 220; n++) {
                onTime.beginTick(n);
                late.beginTick(n);
                for (Event e : events) {
                    if (e.tick() == n) {
                        deliver(onTime, e, h);
                    }
                }
                List<Event> due = new ArrayList<>();
                for (Event e : events) {
                    if (e.tick() + e.delay() == n) {
                        due.add(e);
                    }
                }
                Collections.shuffle(due, r);
                for (Event e : due) {
                    deliver(late, e, h);
                }
                onTime.endTick(n);
                late.endTick(n);
            }
            String why = "seed " + seed;
            assertEquals(onTime.hp(), late.hp(), 1e-9, why);
            assertEquals(onTime.hunger(), late.hunger(), 1e-9, why);
            assertEquals(onTime.posture(220), late.posture(220), 1e-9, why);
            assertEquals(onTime.stoppedAt(), late.stoppedAt(), why);
            for (long t = 190; t <= 220; t++) {
                assertEquals(onTime.state(t), late.state(t), why + " at " + t);
            }
        }
    }

    @Test
    void anAllysBreakReachesTheCutHeldBehindTheSameStrike() {
        // a swipe lands at 117 on two players side by side. Player 1, on time, deflects it: its 8 Posture is all a
        // demon of 8 has. Player 2, 3 ticks late, is in Water Surface Slash's active frames from 117 (armour takes the
        // swipe), so those ticks are held until its own verdict at 120 (D-034). The demon pays for the deflect before
        // they're swept, so the cut finds the Thread the break opened at 117
        Arena a = new Arena();
        Arena.Player deflects = a.player(1).stand(MIN, -0.4, 64, 2, 180);
        deflects.press(Input.guard(Micros.ofTicks(117) - 100_000));
        deflects.press(Input.Kind.GUARD_UP, Micros.ofTicks(117));
        Arena.Player cuts = a.player(2, 3).stand(MIN, 0.4, 64, 2, 180);
        cuts.press(Input.Kind.BREATHE_DOWN, Micros.ofTicks(110) + 10_000 - 200_000);
        cuts.press(Input.release(Micros.ofTicks(110) + 10_000, 0)); // the 1st form from 111: active 117 to 119
        PostureRules eight = new PostureRules(8, 0, 60, 0, 30, 0.5, 0.25);
        Demon d = a.demon(
                10,
                DemonFightTest.profile(100, eight, Fixtures.hunger()),
                DemonFightTest.guarding(1, new Arena.Script().at(100, 1, Fixtures.swipe())));
        a.through(125);
        assertEquals(Verdict.Kind.DEFLECT, a.result(1, 1).kind());
        assertEquals(Verdict.Kind.HIT, a.result(1, 2).kind());
        assertEquals(120, a.tickOf(a.result(1, 2)));
        Outbox.SwingLanded neck = a.all(Outbox.SwingLanded.class).get(0);
        assertEquals(SwingTrack.NECK, neck.part());
        assertEquals(117, neck.tick());
        assertEquals(120, a.tickOf(neck)); // swept late, after the payment
        assertEquals(HitResult.DECAPITATED, neck.result());
        assertTrue(d.dead());
    }
}
