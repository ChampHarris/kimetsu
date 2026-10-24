package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.FightRules;
import dev.champ.kimetsu.core.fight.Fights.End;
import dev.champ.kimetsu.core.fight.Fights.Fight;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rule 13.1's fights as Champ set them (D-068): each combatant's own, opened and refreshed by every trigger between two
 * combatants, ended after {@code end_ticks} with none, at its own defeat, or once every foe it fought is defeated. Players
 * are 1–3, demons 10 and 11; a fight ends after 1200 ticks (60 s).
 */
class FightsTest {
    private static final int P = 1;
    private static final int Q = 2;
    private static final int D = 10;
    private static final int E = 11;

    private final List<String> log = new ArrayList<>();
    private final Fights fights = new Fights(new FightRules(32, 1200), new Fights.Listener() {
        @Override
        public void started(Fight f, long step) {
            log.add("start " + f.id() + " @" + step);
        }

        @Override
        public void ended(Fight f, long step, End why) {
            log.add("end " + f.id() + " @" + step + " " + why + " from " + f.from());
        }
    });

    private static Outbox.AttackStarted commit(long strike, int by, int on, long impact) {
        return new Outbox.AttackStarted(strike, by, on, "swipe", impact - 13, impact - 10, impact - 8, impact, 1, true);
    }

    private static Outbox.StrikeResult result(long strike, int by, int on) {
        return new Outbox.StrikeResult(strike, by, on, Verdict.Kind.DEFLECT, 0);
    }

    @Test
    void aTriggerOpensBothEndsOnce() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(result(1, D, P), 120);
        assertEquals(List.of("start " + D + " @100", "start " + P + " @100"), log);
        Fight p = fights.fight(P);
        assertNotNull(p);
        assertEquals(100, p.from());
        assertEquals(120, p.last());
        assertEquals(1, p.foeCount());
        assertEquals(D, p.foe(0));
    }

    @Test
    void sixtySecondsWithNoTriggerEndIt() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.endStep(1299);
        assertNotNull(fights.fight(P), "on through the 1199th step after its last trigger");
        fights.endStep(1300);
        assertNull(fights.fight(P));
        assertNull(fights.fight(D));
        assertTrue(log.contains("end " + P + " @1300 QUIET from 100"), log.toString());
    }

    @Test
    void aTriggerRefreshesTheTime() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(new Outbox.SwingLanded(P, D, "chain_1", 0, 0, 900, HitResult.LANDED), 900);
        fights.endStep(1300);
        assertNotNull(fights.fight(P), "refreshed at 900");
        fights.endStep(2099);
        assertNotNull(fights.fight(P));
        fights.endStep(2100);
        assertNull(fights.fight(P));
    }

    @Test
    void itsOwnDefeatEndsItAndAFoesEndsTheirsOnceAllAreDown() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(commit(2, E, P, 140), 110);
        fights.defeated(D, 200);
        assertTrue(log.contains("end " + D + " @200 DEFEATED from 100"), log.toString());
        Fight p = fights.fight(P);
        assertNotNull(p, "the player still fights the second demon");
        assertTrue(p.down(0));
        assertFalse(p.down(1));
        fights.defeated(E, 260);
        assertTrue(log.contains("end " + E + " @260 DEFEATED from 110"), log.toString());
        assertTrue(log.contains("end " + P + " @260 FOES_DEFEATED from 100"), log.toString());
        assertTrue(fights.open().isEmpty());
    }

    @Test
    void aPlayersDeathEndsTheDemonsFightWhenItWasItsOnlyFoe() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(commit(2, D, Q, 150), 130);
        fights.defeated(P, 160);
        assertNotNull(fights.fight(D), "Q is still up");
        fights.defeated(Q, 170);
        assertNull(fights.fight(D));
        assertTrue(log.contains("end " + D + " @170 FOES_DEFEATED from 100"), log.toString());
    }

    @Test
    void aDefeatedFoeThatFightsAgainIsUp() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(commit(2, D, Q, 150), 130);
        fights.defeated(P, 160); // P respawns with its id and comes back
        fights.accept(result(3, D, P), 400);
        fights.defeated(Q, 500);
        assertNotNull(fights.fight(D), "P was up again");
    }

    @Test
    void aStrayKilledMidBossEndsNoBossFight() {
        fights.accept(commit(1, D, P, 120), 100); // the boss
        fights.accept(commit(2, E, P, 140), 110); // a Stray
        fights.defeated(E, 200);
        Fight p = fights.fight(P);
        assertNotNull(p);
        assertEquals(100, p.from(), "the player's fight goes on from its start");
        assertNotNull(fights.fight(D));
    }

    @Test
    void everyTriggerLinks() {
        List<Outbox.Event> triggers = List.of(
                commit(1, D, P, 120),
                result(1, D, P),
                new Outbox.SwingLanded(P, D, "chain_1", 0, 0, 100, HitResult.BLOCKED),
                new Outbox.HoldStarted(1, D, P, 100, 200),
                new Outbox.HoldEnded(1, D, P, 200, Release.THROWN, 1.0),
                new Outbox.ClashStarted(1, D, P, 100, 5_000_000));
        for (Outbox.Event e : triggers) {
            Fights f = new Fights(new FightRules(32, 1200), new Quiet());
            f.accept(e, 100);
            assertNotNull(f.fight(P), e + " opened no fight for the player");
            assertNotNull(f.fight(D), e + " opened no fight for the demon");
        }
    }

    @Test
    void whatIsntATriggerDoesntLink() {
        List<Outbox.Event> quiet = List.of(
                new Outbox.Targeting(D, 100, P), // a target alone isn't one (Rule 13.1)
                new Outbox.MobState(D, 100, State.NEUTRAL, Long.MIN_VALUE, 0, 0, 100, 0),
                new Outbox.PlayerEffect(P, 100, Effect.TARGETED),
                new Outbox.SwingDealt(P, D, 0, 100, 2, 3),
                new Outbox.HoldEnded(1, D, P, 200, Release.ESCAPED, 0), // no throw's damage
                new Outbox.MobLeft(D, 100),
                new Outbox.AttackCancelled(1, 100, "feint"));
        for (Outbox.Event e : quiet) {
            Fights f = new Fights(new FightRules(32, 1200), new Quiet());
            f.accept(e, 100);
            assertTrue(f.open().isEmpty(), e + " opened a fight");
        }
    }

    @Test
    void anInvolvedBystanderFightsTheAttacker() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(new Outbox.Involved(1, Q, 112), 112);
        Fight q = fights.fight(Q);
        assertNotNull(q, "a sweep that can reach a bystander puts it in a fight (D-068)");
        assertEquals(D, q.foe(0));
    }

    @Test
    void anInvolvedAfterItsStrikeIsGoneLinksNothing() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(commit(2, D, P, 133), 101);
        fights.accept(new Outbox.AttackCancelled(1, 105, "feint"), 105);
        fights.accept(new Outbox.Involved(1, Q, 106), 106);
        assertNull(fights.fight(Q), "a cancelled strike names no attacker");
        fights.endStep(133); // strike 2's impact: nothing joins it after this step
        fights.accept(new Outbox.Involved(2, Q, 134), 134);
        assertNull(fights.fight(Q), "a strike past its impact names no attacker");
    }

    @Test
    void anInvolvedInItsImpactsStepStillLinks() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.endStep(119); // the step before the impact: the strike is still pending
        fights.accept(new Outbox.Involved(1, Q, 120), 120); // CombatWorld involves bystanders up to the impact's step
        Fight q = fights.fight(Q);
        assertNotNull(q, "a sweep in its impact's step still names its attacker");
        assertEquals(D, q.foe(0));
    }

    @Test
    void aMobsDeathIsItsDefeat() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.accept(new Outbox.MobDied(D, 150, P), 150);
        assertTrue(log.contains("end " + D + " @150 DEFEATED from 100"), log.toString());
        assertTrue(log.contains("end " + P + " @150 FOES_DEFEATED from 100"), log.toString());
    }

    @Test
    void aReanchorRebasesTheOpenFights() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.rebase(10_000); // /time set moved game time on
        fights.endStep(10_110);
        fights.accept(new Outbox.Involved(1, Q, 10_111), 10_111);
        assertNotNull(fights.fight(Q), "the strike's impact moved with it");
        fights.endStep(11_299);
        assertNotNull(fights.fight(P), "its time runs on the new numbering");
        Fight p = fights.fight(P);
        assertNotNull(p);
        assertEquals(10_100, p.from());
        fights.endStep(11_300);
        assertNull(fights.fight(P));
    }

    @Test
    void newRulesSetTheEnd() {
        fights.accept(commit(1, D, P, 120), 100);
        fights.rules(new FightRules(32, 200));
        fights.endStep(299);
        assertNotNull(fights.fight(P));
        fights.endStep(300);
        assertNull(fights.fight(P));
    }

    private static final class Quiet implements Fights.Listener {
        @Override
        public void started(Fight f, long step) {}

        @Override
        public void ended(Fight f, long step, End why) {}
    }
}
