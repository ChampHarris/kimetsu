package dev.champ.kimetsu.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict;
import org.junit.jupiter.api.Test;

/**
 * Need-to-know through the router's own decision (spec Netcode): demon 10 strikes player 1, whom player 2 doesn't
 * track; player 3 tracks the demon; player 4 tracks player 1 alone.
 */
class RoutingTest {
    private static final int DEMON = 10;
    private static final long STRIKE = 7;

    private static Routing routing() {
        Trackers t = new Trackers();
        t.start(1, DEMON);
        t.start(3, DEMON);
        t.start(4, 1);
        return new Routing(t);
    }

    private static Outbox.AttackStarted started() {
        return new Outbox.AttackStarted(STRIKE, DEMON, 1, "kimetsu:m1_demon/swipe", 100, 107, 107, 117, 1, true);
    }

    @Test
    void aStrikeGoesToItsTargetAndTheDemonsTrackers_itsLockOnlyToWhoeverGotItsStart() {
        Routing r = routing();
        Outbox.StrikeLocked lock = new Outbox.StrikeLocked(STRIKE, 180, 0, 64, 0, 1);
        assertFalse(r.reaches(lock, 1), "a lock before its start reaches nobody");

        assertTrue(r.reaches(started(), 1)); // the target, who also tracks the demon
        assertFalse(r.reaches(started(), 2));
        assertTrue(r.reaches(started(), 3)); // tracks the demon
        assertFalse(r.reaches(started(), 4)); // tracks only the target

        assertTrue(r.reaches(lock, 1));
        assertFalse(r.reaches(lock, 2));
        assertTrue(r.reaches(lock, 3));
        assertFalse(r.reaches(lock, 4));
    }

    @Test
    void aResultAlsoReachesTheStruckPlayersTrackers() {
        Routing r = routing();
        Outbox.StrikeResult hit = new Outbox.StrikeResult(STRIKE, DEMON, 1, Verdict.Kind.HIT, 1.25);
        assertTrue(r.reaches(hit, 1));
        assertTrue(r.reaches(hit, 3));
        assertTrue(r.reaches(hit, 4)); // "trackers of either side"
        assertFalse(r.reaches(hit, 2));
    }

    @Test
    void aPlayersOwnEffectGoesToItAlone_andAMobsStateToItsTrackers() {
        Routing r = routing();
        Outbox.PlayerEffect own = new Outbox.PlayerEffect(1, 120, new Effect.InCombat(true));
        assertTrue(r.reaches(own, 1));
        assertFalse(r.reaches(own, 3));
        assertFalse(r.reaches(own, 4));

        Outbox.MobState state = new Outbox.MobState(DEMON, 120, State.NEUTRAL, Long.MIN_VALUE, 100, 0, 120, 0);
        assertTrue(r.reaches(state, 1));
        assertTrue(r.reaches(state, 3));
        assertFalse(r.reaches(state, 2));
        assertFalse(r.reaches(state, 4));
    }

    @Test
    void aStrikeNoLongerPendingSendsNothingMore() {
        Routing r = routing();
        assertTrue(r.reaches(started(), 1));
        Outbox.AttackCancelled cancel = new Outbox.AttackCancelled(STRIKE, 118, "feint");
        r.prune(strike -> strike == STRIKE);
        assertTrue(r.reaches(cancel, 1), "still pending: its cancel goes");
        r.prune(strike -> false);
        assertFalse(r.reaches(cancel, 1));
    }
}
