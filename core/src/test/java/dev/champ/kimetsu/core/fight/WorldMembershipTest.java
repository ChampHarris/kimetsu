package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.meter.Fixed;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Who's in the world and what the mod tells it between ticks (slice 5): players are kept in id order however they
 * join, since the loop's order (and so a fight's outcome) follows it and a respawn rejoins with its old id after newer
 * ones; an id still in the fight can't join twice; and the mod's own effects on a player (in combat, an assist change)
 * are logged at the next tick's start and sent like the world's.
 */
class WorldMembershipTest {
    private static List<Integer> ids(Arena a) {
        List<Integer> out = new ArrayList<>();
        for (Fighter f : a.world.players()) {
            out.add(f.id());
        }
        return out;
    }

    @Test
    void playersAreKeptInIdOrderHoweverTheyJoin() {
        Arena a = new Arena();
        a.player(5);
        a.player(2);
        a.player(9);
        assertEquals(List.of(2, 5, 9), ids(a));
    }

    @Test
    void aRespawnRejoinsWithItsOldIdBeforeNewerOnes_onceItsLeft() {
        Arena a = new Arena();
        a.player(3);
        a.player(7);
        a.through(10);
        a.world.remove(3);
        assertThrows(IllegalStateException.class, () -> a.player(3), "it's still leaving");
        a.through(11); // it leaves in the next tick
        assertEquals(List.of(7), ids(a));
        a.player(3);
        assertEquals(List.of(3, 7), ids(a));
    }

    @Test
    void anIdAlreadyInTheFightCantJoinAgain() {
        Arena a = new Arena();
        a.player(4);
        assertThrows(IllegalStateException.class, () -> a.player(4));
    }

    @Test
    void theModsOwnEffectIsLoggedAtTheNextTicksStart_andSentLikeTheWorlds() {
        Arena a = new Arena();
        Arena.Player p = a.player(1, 0, 50); // half its Breath spent
        a.through(10);
        a.world.apply(1, new Effect.AssistChanged(2));
        a.through(11);
        boolean sent = false;
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            sent |= e.fighter() == 1 && e.tick() == 11 && e.effect().equals(new Effect.AssistChanged(2));
        }
        assertTrue(sent, "PlayerEffect(1, 11, AssistChanged) went out");
        assertEquals(2, p.now().assistLevel(), "its machine took the mod's effect");
        long before = p.now().breath().at(11);
        a.through(31); // nothing put it in combat: Breath regenerates at its idle rate (Rule 13.1 is the machine's)
        double gained = (p.now().breath().at(31) - before) / (double) Fixed.ONE;
        assertEquals(Fixtures.breath().regenIdlePerS(), gained, 0.01);
    }

    @Test
    void aWorldReplacingAnotherCarriesOnItsStrikeAndStringIds_neverBack() {
        Arena a = new Arena();
        a.player(1);
        a.world.idsFrom(40, 7); // the old world's next ids: a data reload's rebuild
        a.demon(10, new Arena.Script().at(20, 1, Fixtures.swipe(), Fixtures.swipe()));
        a.through(60);
        List<Outbox.AttackStarted> started = a.all(Outbox.AttackStarted.class);
        assertEquals(2, started.size());
        assertEquals(40, started.get(0).strike());
        assertEquals(41, started.get(1).strike());
        assertEquals(7, started.get(0).string());
        assertEquals(42, a.world.nextStrikeId());
        assertEquals(8, a.world.nextStringId());
        assertThrows(IllegalArgumentException.class, () -> a.world.idsFrom(41, 8), "ids only move on");
    }

    @Test
    void aStrikesScheduleGoesOutBeforeTheNoticeOnItsTargetsMachine() {
        Arena a = new Arena();
        a.player(1);
        a.demon(10, new Arena.Script().at(20, 1, Fixtures.swipe()));
        a.through(30);
        Outbox.AttackStarted started = a.all(Outbox.AttackStarted.class).get(0);
        Outbox.PlayerEffect notice = null;
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            if (e.effect() instanceof Effect.StrikeCommitted c && c.id() == started.strike()) {
                notice = e;
            }
        }
        assertTrue(notice != null, "the target's machine heard of the strike");
        assertTrue(a.indexOf(started) < a.indexOf(notice), "no client hears of a strike before it's described");
    }

    @Test
    void mobsAreKeptInIdOrderHoweverTheyJoin_andOneCantJoinTwice() {
        Arena a = new Arena();
        a.player(1);
        a.demon(20, new Arena.Script().at(20, 1, Fixtures.swipe()));
        a.demon(10, new Arena.Script().at(20, 1, Fixtures.swipe())); // a rebuilt world can take them in any order
        a.through(30);
        List<Outbox.AttackStarted> started = a.all(Outbox.AttackStarted.class);
        assertEquals(
                List.of(10, 20),
                List.of(started.get(0).attacker(), started.get(1).attacker()),
                "decided in id order");
        assertThrows(IllegalStateException.class, () -> a.demon(10, new Arena.Script()));
    }

    @Test
    void aMobNoLongerInTheWorldHasNoStrikePose() {
        Arena a = new Arena();
        assertFalse(a.world.strikePose(99, 1, new Pose()), "the mod asks after a death buries it");
    }

    @Test
    void anEffectForAPlayerStillLeavingIsDroppedToo() {
        Arena a = new Arena();
        a.player(1);
        a.player(2);
        a.through(10);
        a.world.remove(2);
        a.world.apply(2, new Effect.AssistChanged(1)); // between the same two ticks: it leaves in the next
        a.through(12);
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            assertTrue(
                    !(e.fighter() == 2 && e.effect() instanceof Effect.AssistChanged),
                    "nothing for a player leaving: " + e);
        }
    }

    @Test
    void anEffectForAPlayerWhoLeftIsDropped() {
        Arena a = new Arena();
        a.player(1);
        a.player(2);
        a.through(10);
        a.world.remove(2);
        a.through(11);
        a.world.apply(2, new Effect.AssistChanged(1));
        a.through(12);
        for (Outbox.PlayerEffect e : a.all(Outbox.PlayerEffect.class)) {
            assertTrue(
                    !(e.fighter() == 2 && e.effect() instanceof Effect.AssistChanged),
                    "nothing for a player gone: " + e);
        }
    }
}
