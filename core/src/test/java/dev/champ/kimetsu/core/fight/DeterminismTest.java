package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.Outbox.AttackCancelled;
import dev.champ.kimetsu.core.fight.Outbox.StrikeResult;
import dev.champ.kimetsu.core.fight.Outbox.SwingLanded;
import dev.champ.kimetsu.core.machine.Input;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The fight loop is a function of its inputs (D-037): the same fight run twice records the same events and ends in the
 * same states, and when each player's input arrives (late, jittering, in order) changes when a strike is decided, never
 * what it decides.
 */
class DeterminismTest {
    /**
     * Two players a block apart, each the target of its own demon, the second's strikes two beats after the first's;
     * both are in both demons' reach. Between strikes they swing their Light Chain at their demon, breaking it now and
     * then. 2,400 ticks: two minutes.
     */
    private static Arena coop(long seed) {
        Random rnd = new Random(seed);
        Arena a = new Arena();
        Arena.Player one = a.player(1, 1);
        Arena.Player two = a.player(2, 2);
        Arena.Script first = new Arena.Script();
        Arena.Script second = new Arena.Script();
        a.demon(10, first).at(-0.8, 0, 0).posture(30).recovering(40); // the swings break them now and then
        a.demon(11, second).at(0.8, 0, 0).posture(30).recovering(40);
        RandomFight.script(rnd, a, first, one, 35, 0, true, -0.5, 2);
        RandomFight.script(rnd, a, second, two, 35, 26, true, 0.5, 2);
        for (Arena.Player p : List.of(one, two)) {
            for (int k = 0; k < 120; k++) {
                p.press(Input.Kind.ATTACK, Micros.ofTicks(100 + rnd.nextInt(2300)) + rnd.nextInt(50_000));
            }
        }
        a.through(2400);
        return a;
    }

    @Test
    void theSameFightRunTwiceRecordsTheSameEventsAndEndsTheSame() {
        Arena once = coop(7);
        Arena again = coop(7);
        assertEquals(once.seen, again.seen);
        assertTrue(once.all(StrikeResult.class).size() > 100);
        assertTrue(once.all(SwingLanded.class).size() > 20);
        System.out.println("two-minute fight: " + once.all(StrikeResult.class).size() + " results, "
                + once.all(SwingLanded.class).size() + " swings landed, cancels " + once.all(AttackCancelled.class));
        List<String> why = once.all(AttackCancelled.class).stream()
                .map(AttackCancelled::why)
                .toList();
        assertTrue(why.contains("posture break") && why.contains("pommel"), why.toString());
        for (int i = 0; i < once.players.size(); i++) {
            assertTrue(once.players.get(i).now().sameAs(again.players.get(i).now()));
        }
        for (int i = 0; i < once.demons.size(); i++) {
            assertEquals(once.demons.get(i).log, again.demons.get(i).log);
        }
    }

    /** What each strike decided, and the ticks from the impacts to the decisions, summed. */
    private record Decided(Map<String, String> verdicts, long waited) {}

    /**
     * Each player alone with its demon, 20 blocks apart, plus a third who stands beside the first and never moves:
     * involved in the first demon's strikes, its input jittering, so they wait for it.
     */
    private static Decided verdicts(long seed, int maxJitterTicks) {
        Random script = new Random(seed);
        Random jitter = new Random(seed * 31 + maxJitterTicks);
        Arena a = new Arena();
        Arena.Player one = a.player(1, 1).jitter(jitter, maxJitterTicks);
        Arena.Player two = a.player(2, 2).jitter(jitter, maxJitterTicks);
        a.player(3, 1).jitter(jitter, maxJitterTicks).stand(Long.MIN_VALUE, 1.0, 64, 2, 180);
        Arena.Script first = new Arena.Script();
        Arena.Script second = new Arena.Script();
        a.demon(10, first).posture(1e9);
        a.demon(11, second).posture(1e9).at(20, 0, 0);
        RandomFight.script(script, a, first, one, 120, 0, true, 0, 2);
        RandomFight.script(script, a, second, two, 120, 26, true, 20, 2);
        a.through(RandomFight.end(120, 26) + 10);
        Map<Long, Long> impacts = new TreeMap<>();
        for (Outbox.AttackStarted s : a.all(Outbox.AttackStarted.class)) {
            impacts.put(s.strike(), s.impactTick());
        }
        Map<String, String> out = new TreeMap<>();
        long waited = 0;
        for (StrikeResult r : a.all(StrikeResult.class)) {
            out.put(r.strike() + "/" + r.fighter(), r.kind().name());
            waited += a.tickOf(r) - impacts.get(r.strike());
        }
        for (AttackCancelled c : a.all(AttackCancelled.class)) {
            out.put(c.strike() + "/cancelled", c.why());
        }
        return new Decided(out, waited);
    }

    @Test
    void howLateEachPlayersInputArrivesNeverChangesAVerdict() {
        Decided onTime = verdicts(11, 0);
        assertTrue(onTime.verdicts().size() > 300);
        assertTrue(onTime.verdicts().containsValue("pommel"));
        for (int jitter : new int[] {1, 2, 3}) {
            Decided late = verdicts(11, jitter);
            assertEquals(onTime.verdicts(), late.verdicts(), "input up to " + jitter + " ticks later still");
            assertTrue(late.waited() > onTime.waited()); // it did wait longer for them
        }
    }
}
