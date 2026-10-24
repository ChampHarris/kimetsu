package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Outbox.AttackCancelled;
import dev.champ.kimetsu.core.fight.Outbox.HoldEnded;
import dev.champ.kimetsu.core.fight.Outbox.MobDied;
import dev.champ.kimetsu.core.fight.Outbox.MobLeft;
import dev.champ.kimetsu.core.fight.Outbox.SwingLanded;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A mob leaving the fight alive (slice 5c-1, D-061: its chunk unloaded, or it went out of tracking), between ticks, in
 * the next tick: a strike that landed before it went is decided on what the server knows, as a leaving player's is; the
 * strikes still to land are cancelled for everyone; its hold lets go and its clash ends in a tie; it takes no hits and
 * its brain commits nothing; and once none of its strikes waits, it goes with {@code MobLeft}, never {@code MobDied}.
 * The box-dummy demon at the origin facing +z strikes on Water's 13-tick beat: committed at 100, a string lands at 117
 * and 130.
 */
class MobDepartureTest {
    private static final long COMMIT = 100;
    private static final long MIN = Long.MIN_VALUE;
    private static final FrameData SWIPE = Fixtures.swipe();

    private static Arena.Script commits(long tick, int target, FrameData... string) {
        return new Arena.Script().at(tick, target, string);
    }

    @Test
    void aStrikeThatLandedBeforeItLeftIsDecided_theStringsRestIsCancelled_andItLeavesAlive() {
        Arena a = new Arena();
        a.player(1, 3); // 3 ticks late, unguarded
        BoxDummy d = a.demon(10, commits(COMMIT, 1, SWIPE, SWIPE)); // impacts at 117 and 130
        a.through(118); // the first swipe landed at 117; its verdict waits for input due in 120
        assertTrue(a.results(1).isEmpty());
        a.world.leave(10); // its chunk unloads
        a.through(125);

        assertEquals(Kind.HIT, a.result(1, 1).kind()); // decided on what was known, as its hold cap would
        assertEquals(119, a.tickOf(a.result(1, 1)));
        assertEquals(List.of(new AttackCancelled(2, 119, "attacker gone")), a.all(AttackCancelled.class));
        assertTrue(d.log.contains("cancelled 2"), "" + d.log);
        assertFalse(a.get(1).now().deflects().holds(2)); // the player's machine let the cancelled one go
        assertEquals(List.of(new MobLeft(10, 119)), a.all(MobLeft.class));
        assertTrue(a.all(MobDied.class).isEmpty(), "it left alive");
        assertFalse(a.world.hasMob(10));
        assertTrue(a.world.pending().isEmpty());
        // MobLeft is the last word on it
        MobLeft left = a.all(MobLeft.class).get(0);
        assertTrue(a.indexOf(a.result(1, 1)) < a.indexOf(left));
    }

    @Test
    void itLingersWhileItsLandedStrikeWaitsBehindAnothers_takingNoHitsAndCommittingNothing() {
        Arena a = new Arena();
        a.player(1, 3); // both demons' swipes land on it at 117; its input for 117 is due in 120
        Arena.Player behind =
                a.player(2).stand(MIN, 0, 64, -0.5, 0); // behind both once they lunge a block, facing them
        behind.press(Input.of(Input.Kind.ATTACK, Micros.ofTicks(116))); // Chain 1 touches at 119
        a.demon(10, commits(COMMIT, 1, SWIPE)); // strike 1
        Arena.Script leaverBrain = commits(COMMIT, 1, SWIPE).at(119, 1, SWIPE);
        a.demon(11, leaverBrain); // strike 2, after strike 1 in the book
        a.through(118);
        a.world.leave(11);
        a.through(119);

        // strike 2 landed before demon 11 left, but player 1 resolves in impact order: strike 1 waits for its input
        assertTrue(a.world.hasMob(11), "it waits for its landed strike");
        assertTrue(a.results(2).isEmpty());
        assertEquals(1, leaverBrain.plans.size(), "a leaving mob's brain commits nothing");
        boolean hitTen = false;
        for (SwingLanded l : a.all(SwingLanded.class)) {
            assertNotEquals(11, l.mob(), "a leaving mob takes no hits: " + l);
            hitTen |= l.mob() == 10 && l.tick() == 119;
        }
        assertTrue(
                hitTen,
                "the swing that would have touched it touched the demon that stayed: " + a.all(SwingLanded.class));

        a.through(125);
        assertEquals(120, a.tickOf(a.result(1, 1)));
        assertEquals(120, a.tickOf(a.result(2, 1))); // decided at once once nothing earlier waits
        assertEquals(List.of(new MobLeft(11, 120)), a.all(MobLeft.class));
        assertTrue(a.world.hasMob(10));
        assertFalse(a.world.hasMob(11));
    }

    @Test
    void itsHoldLetsGo_andThePlayerIsReleased() {
        Arena a = new Arena();
        Arena.Player held = a.player(1).stand(MIN, 0, 64, 1.5, 180);
        a.demon(10, commits(COMMIT, 1, Fixtures.grab())); // connects at 117
        a.through(120);
        assertEquals(State.GRABBED, held.now().state());
        a.world.leave(10);
        a.through(125);

        HoldEnded end = a.all(HoldEnded.class).get(0);
        assertEquals(new HoldEnded(1, 10, 1, 121, Release.DROPPED, 0), end);
        assertNotEquals(State.GRABBED, held.now().state());
        assertEquals(List.of(new MobLeft(10, 121)), a.all(MobLeft.class));
        assertTrue(a.indexOf(end) < a.indexOf(a.all(MobLeft.class).get(0)));
    }

    @Test
    void aMobAlreadyDeadOrUnknownDoesntLeave() {
        Arena a = new Arena();
        a.player(1);
        Demon d = a.demon(10, (Brain) new Arena.Script()); // a real demon: it can die from outside
        a.world.leave(99); // no such mob
        a.through(101);
        d.outsideDamage(102, 1_000, true); // /kill
        a.through(102);
        a.world.leave(10);
        a.through(110);
        assertEquals(1, a.all(MobDied.class).size());
        assertTrue(a.all(MobLeft.class).isEmpty());
        assertFalse(a.world.hasMob(10));
    }
}
