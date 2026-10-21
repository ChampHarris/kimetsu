package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.fight.Outbox.AttackStarted;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The plan's client agreement check (Netcode, "Prediction and reconciliation"): a client predicting every strike on
 * its player with the server's own verdict code, from its own state and the effects the server logged at the server's
 * ticks, hears the server's bell. At 20, 150 and 250 ms RTT (its input arriving 0, 2 and 3 ticks late), 800 random
 * strikes each, all of them predicted as the server decides them. Without Pommels its state also equals every
 * snapshot the server sends, before the snapshot corrects anything. With them it needn't: the server holds an attack's
 * windup until its press arrives (Rule 2.2's back-dating limit), so at 250 ms its snapshot moves the client's Pommel a
 * tick later, a correction no verdict feels (a Pommel's timed answer is judged by its stamp).
 */
class ClientAgreementTest {
    private static final int STRIKES = 400;

    private record Run(int answers, Map<String, Integer> outcomes, List<String> disagreements, ClientMirror client) {}

    private static Run run(long seed, int latency, boolean pommels) {
        Arena a = new Arena();
        Arena.Player p = a.player(1, latency);
        p.snapshotting = true;
        Arena.Script brain = new Arena.Script();
        BoxDummy d = a.demon(10, brain).posture(1e9); // it never breaks: every strike is decided
        int answers = RandomFight.script(new Random(seed), a, brain, p, STRIKES, 0, pommels, 0, 2);
        ClientMirror client = new ClientMirror(p, d, latency, 0, a);
        long end = RandomFight.end(STRIKES, 0) + 2L * latency;
        for (long n = 1; n <= end; n++) {
            a.through(n);
            client.tick(n, a);
        }
        Map<String, Integer> outcomes = new TreeMap<>();
        List<String> disagreements = new ArrayList<>();
        for (AttackStarted s : a.all(AttackStarted.class)) {
            String server = ClientMirror.serverOutcome(a, s.strike(), 1);
            String predicted = client.predicted.get(s.strike());
            outcomes.merge(server, 1, Integer::sum);
            if (!server.equals(predicted)) {
                disagreements.add(String.format(
                        Locale.ROOT,
                        "strike %d (%s, impact %d): server %s, client %s",
                        s.strike(),
                        s.attack(),
                        s.impactTick(),
                        server,
                        predicted));
            }
        }
        return new Run(answers, outcomes, disagreements, client);
    }

    @ParameterizedTest(name = "{0} ms RTT")
    @CsvSource({"20, 0", "150, 2", "250, 3"})
    void theClientsPredictedVerdictsAreTheServers(int rttMs, int latency) {
        Run withPommels = run(1000L + rttMs, latency, true);
        Run without = run(2000L + rttMs, latency, false);
        System.out.printf(
                Locale.ROOT,
                "RTT %d ms: %d + %d answering presses; with Pommels %s (snapshots corrected: %d of %d);"
                        + " without %s (corrected: %d of %d)%n",
                rttMs,
                withPommels.answers(),
                without.answers(),
                withPommels.outcomes(),
                withPommels.client().corrected,
                withPommels.client().snapshots,
                without.outcomes(),
                without.client().corrected,
                without.client().snapshots);

        assertEquals(List.of(), withPommels.disagreements());
        assertEquals(List.of(), without.disagreements());
        assertEquals(STRIKES, withPommels.client().predicted.size());
        assertEquals(STRIKES, without.client().predicted.size());
        assertEquals(List.of(), without.client().corrections); // its own state was the server's at every snapshot
        assertTrue(without.client().snapshots > STRIKES * 50);
        for (String kind : List.of("PERFECT", "DEFLECT", "BLOCK", "HIT", "CLEARED", "MISS", "LAUNCH", "GRAB")) {
            assertTrue(
                    withPommels.outcomes().getOrDefault(kind, 0)
                                    + without.outcomes().getOrDefault(kind, 0)
                            >= 5,
                    kind + " too rare to tell: " + withPommels.outcomes() + " " + without.outcomes());
        }
        assertTrue(withPommels.outcomes().getOrDefault(ClientMirror.INTERRUPTED, 0) >= 5, "Pommel interrupts");
    }
}
