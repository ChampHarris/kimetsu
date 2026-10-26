package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Segment;
import dev.champ.kimetsu.net.NetMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the client keeps of the routed state: a mob's state goes when the mob does, however it goes (slice 5c-1), and
 * its player's combat flag runs out on the client's ticks and goes with the level (5c-2); its player's own push is
 * handed on for the client to play (5c-3b).
 */
class ClientViewTest {
    private static final int SELF = 1;
    private static final int DEMON = 10;

    private static ClientView seeing() {
        ClientView v = new ClientView();
        Outbox.MobState state = new Outbox.MobState(DEMON, 120, State.NEUTRAL, Long.MIN_VALUE, 100, 0, 120, 0);
        v.accept(new NetMessage.Tick(120, List.of(state), null), SELF);
        assertNotNull(v.mob(DEMON));
        return v;
    }

    @Test
    void aMobWhoseEntityLeavesTheClientsLevelIsForgotten_thoughNoMobLeftReachedIt() {
        // the server ends the tracking before an unloading mob leaves, so its MobLeft reaches nobody (both reviews)
        ClientView v = seeing();
        v.mobGone(DEMON);
        assertNull(v.mob(DEMON));
    }

    @Test
    void itsPlayersOwnPushIsHandedOn_notAnotherPlayersNorAnyOtherEffect() {
        ClientView v = new ClientView();
        Effect.Push mine = new Effect.Push(0, 1, 2.0, 4, 0, 123, 133, 6_130_000);
        Effect.Push theirs = new Effect.Push(1, 0, 2.0, 4, 0, 123, 133, 6_130_000);
        List<Outbox.Event> events = List.of(
                new Outbox.PlayerEffect(SELF, 122, new Effect.Blocked()),
                new Outbox.PlayerEffect(SELF, 122, mine),
                new Outbox.PlayerEffect(2, 122, theirs));
        assertEquals(mine, v.accept(new NetMessage.Tick(122, events, null), SELF));
        assertNull(v.accept(new NetMessage.Tick(123, List.of(new Outbox.PlayerEffect(2, 123, theirs)), null), SELF));
    }

    /**
     * The assist level the server gives its player (5d-2): its own {@code AssistChanged}, not another player's; 0 until
     * the first, which every join sends.
     */
    @Test
    void itKeepsItsPlayersAssistLevel_notAnotherPlayers() {
        ClientView v = new ClientView();
        assertEquals(0, v.assist());
        v.accept(
                new NetMessage.Tick(
                        122, List.of(new Outbox.PlayerEffect(SELF, 122, new Effect.AssistChanged(2))), null),
                SELF);
        assertEquals(2, v.assist());
        v.accept(
                new NetMessage.Tick(123, List.of(new Outbox.PlayerEffect(2, 123, new Effect.AssistChanged(3))), null),
                SELF);
        assertEquals(2, v.assist(), "another player's level");
    }

    /**
     * D-055 on the client, as the server counts it (`Arena.hostileWithin`, a review nit): a demon makes the blade only
     * the Attack while the server has it in a fight, which its {@code MobState} says, and no longer once it dies; a
     * demon the server never routed (one in no arena, a GameTest's) counts for nothing.
     */
    @Test
    void aDemonFightsOnlyFromItsFirstMobStateToItsDeath() {
        ClientView v = new ClientView();
        assertFalse(v.fights(DEMON), "a demon the server never said was in a fight");
        v.accept(
                new NetMessage.Tick(
                        120,
                        List.of(new Outbox.MobState(DEMON, 120, State.NEUTRAL, Long.MIN_VALUE, 100, 0, 120, 0)),
                        null),
                SELF);
        assertTrue(v.fights(DEMON));
        assertFalse(v.fights(DEMON + 1), "another demon");
        v.accept(new NetMessage.Tick(121, List.of(new Outbox.MobDied(DEMON, 121, SELF)), null), SELF);
        assertFalse(v.fights(DEMON), "a dead demon");
    }

    @Test
    void aMobLeftThatReachesTheClientForgetsTheMobToo() {
        ClientView v = seeing();
        v.accept(new NetMessage.Tick(121, List.of(new Outbox.MobLeft(DEMON, 121)), null), SELF);
        assertNull(v.mob(DEMON));
    }

    private static NetMessage.Snapshot inCombatUntil(long settled, long until) {
        return new NetMessage.Snapshot(
                settled,
                1,
                State.NEUTRAL,
                "",
                new Segment(50_000, 200, settled, 100_000),
                0,
                false,
                new Segment(60_000, 0, settled, 60_000),
                0,
                0,
                until);
    }

    /** Rule 13.1 on the client: the last snapshot's flag runs out on the client's own ticks, at its end tick exactly. */
    @Test
    void theFlagRunsOutOnTheClientsOwnTicks() {
        ClientView v = new ClientView();
        assertFalse(v.inCombat(0), "in combat before any snapshot");
        v.accept(new NetMessage.Tick(120, List.of(), inCombatUntil(118, 420)), SELF);
        assertTrue(v.inCombat(120));
        assertTrue(v.inCombat(419), "out of combat a tick early");
        assertFalse(v.inCombat(420), "still in combat at its end");
        v.accept(new NetMessage.Tick(130, List.of(), inCombatUntil(128, Long.MIN_VALUE)), SELF);
        assertFalse(v.inCombat(130), "a snapshot out of combat left the flag up");
    }

    /**
     * A dimension change: the server's new fighter there starts out of combat, and its first snapshot waits for a
     * settled frame, so the old flag goes with the level (code review: it refused mining for up to 15 s).
     */
    @Test
    void leavingTheLevelDropsTheFlag() {
        ClientView v = new ClientView();
        v.accept(new NetMessage.Tick(120, List.of(), inCombatUntil(118, 420)), SELF);
        v.leftLevel();
        assertFalse(v.inCombat(121));
    }
}
