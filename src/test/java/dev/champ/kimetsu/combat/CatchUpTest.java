package dev.champ.kimetsu.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.fight.Audience;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;

/**
 * Late tracking's cache (slice 5c-4b, Step 5): what the router keeps so a player who starts seeing a demon late, or
 * whose session turns ready late, is shown it as its live trackers are. Demon 10 strikes player 1; demon 20 is another.
 */
class CatchUpTest {
    private static final int DEMON = 10;
    private static final int OTHER = 20;

    private static Outbox.AttackStarted started(long strike, int attacker) {
        return new Outbox.AttackStarted(strike, attacker, 1, "kimetsu:m1_demon/swipe", 100, 107, 107, 117, 1, true);
    }

    private static Outbox.MobState state(int mob, long tick) {
        return new Outbox.MobState(mob, tick, State.NEUTRAL, Long.MAX_VALUE, 100_000, 0, tick, 0);
    }

    private static List<Outbox.Event> replay(CatchUp c, int mob) {
        List<Outbox.Event> got = new ArrayList<>();
        c.replay(mob, got::add);
        return got;
    }

    @Test
    void aLateViewerGetsTheMobsLatestViewAndEachPendingStrikeInOrder() {
        CatchUp c = new CatchUp();
        Outbox.MobState old = state(DEMON, 100);
        Outbox.MobState now = state(DEMON, 110);
        Outbox.GuardYaw oldYaw = new Outbox.GuardYaw(DEMON, 100, 12);
        Outbox.GuardYaw yaw = new Outbox.GuardYaw(DEMON, 108, 16);
        Outbox.Targeting targeting = new Outbox.Targeting(DEMON, 101, 1);
        Outbox.AttackStarted first = started(7, DEMON);
        Outbox.AttackStarted second = started(8, DEMON);
        Outbox.Involved involved = new Outbox.Involved(7, 2, 107);
        Outbox.StrikeLocked lock = new Outbox.StrikeLocked(7, 180, 0, 64, 0, 1);
        for (Outbox.Event e : List.of(old, oldYaw, targeting, first, second, now, yaw, involved, lock)) {
            c.record(e);
        }
        assertEquals(List.of(now, yaw, targeting, first, lock, involved, second), replay(c, DEMON));
    }

    @Test
    void itReplaysOnlyTheMobAsked_orEveryMob() {
        CatchUp c = new CatchUp();
        Outbox.MobState mine = state(DEMON, 100);
        Outbox.MobState its = state(OTHER, 100);
        Outbox.AttackStarted myStrike = started(7, DEMON);
        Outbox.AttackStarted itsStrike = started(8, OTHER);
        Outbox.StrikeLocked itsLock = new Outbox.StrikeLocked(8, 0, 0, 64, 0, 1);
        for (Outbox.Event e : List.of(mine, its, myStrike, itsStrike, itsLock)) {
            c.record(e);
        }
        assertEquals(List.of(mine, myStrike), replay(c, DEMON));
        assertEquals(List.of(its, itsStrike, itsLock), replay(c, OTHER));
        List<Outbox.Event> all = replay(c, Audience.NOBODY);
        assertEquals(5, all.size(), "every mob's: " + all);
        assertTrue(
                all.containsAll(List.<Outbox.Event>of(mine, its, myStrike, itsStrike, itsLock)), "every mob's: " + all);
        assertTrue(all.indexOf(itsStrike) < all.indexOf(itsLock), "a strike's start goes before its lock: " + all);
        assertEquals(List.of(), replay(c, 30), "a mob it never heard of");
    }

    @Test
    void aCancelledStrikeIsForgottenAtOnce_andOneNoLongerPendingAtTheStepsEnd() {
        CatchUp c = new CatchUp();
        Outbox.AttackStarted first = started(7, DEMON);
        Outbox.AttackStarted second = started(8, DEMON);
        c.record(first);
        c.record(second);
        // the book may still hold a cancelled strike until it purges: a replay must never send a start without its end
        c.record(new Outbox.AttackCancelled(7, 110, "feint"));
        assertEquals(List.of(second), replay(c, DEMON));
        c.prune(strike -> strike == 8);
        assertEquals(List.of(second), replay(c, DEMON), "still pending");
        c.prune(strike -> false); // decided for everyone, and out of the book
        assertEquals(List.of(), replay(c, DEMON));
        c.record(new Outbox.StrikeLocked(8, 0, 0, 64, 0, 1));
        c.record(new Outbox.Involved(8, 2, 112));
        assertEquals(List.of(), replay(c, DEMON), "a strike it no longer keeps gains nothing");
    }

    @Test
    void aMobThatDiesOrLeavesIsForgotten() {
        CatchUp c = new CatchUp();
        c.record(state(DEMON, 100));
        c.record(new Outbox.GuardYaw(DEMON, 100, 12));
        c.record(new Outbox.Targeting(DEMON, 101, 1));
        c.record(state(OTHER, 100));
        c.record(new Outbox.MobDied(DEMON, 120, 1));
        c.record(new Outbox.MobLeft(OTHER, 121));
        assertEquals(List.of(), replay(c, Audience.NOBODY));
        Outbox.MobState back = state(OTHER, 400); // back in tracking, a new combatant: what it sends now counts
        c.record(back);
        assertEquals(List.of(back), replay(c, OTHER));
    }

    @Test
    void whatOnlyHappensOnceIsNotKept() {
        // a result, a hit, a hold, a clash, a clang or a push isn't a view to restore (holds and clashes: 5d's HUD)
        CatchUp c = new CatchUp();
        c.record(started(7, DEMON));
        c.record(new Outbox.StrikeResult(7, DEMON, 1, Verdict.Kind.HIT, 1.25));
        c.record(new Outbox.Clang(DEMON, 110, 1, false));
        c.record(new Outbox.Pushed(DEMON, 110, 1, 0, 2, 6, true));
        c.record(new Outbox.HoldStarted(7, DEMON, 1, 117, 140));
        c.record(new Outbox.ClashStarted(7, DEMON, 1, 117, 5_850_000));
        assertEquals(List.of(started(7, DEMON)), replay(c, DEMON));
    }

    @Test
    void aReplayedStartLetsTheViewerSeeTheStrikesRest_andANonTrackerGetsNone() {
        Trackers t = new Trackers();
        t.start(3, DEMON); // a late tracker; player 4 doesn't track the demon
        Routing r = new Routing(t);
        CatchUp c = new CatchUp();
        Outbox.MobState s = state(DEMON, 100);
        Outbox.AttackStarted a = started(7, DEMON);
        c.record(s);
        c.record(a);
        assertEquals(List.of(s, a), seen(c, r, 3));
        assertEquals(List.of(), seen(c, r, 4));
        Outbox.StrikeLocked lock = new Outbox.StrikeLocked(7, 180, 0, 64, 0, 1); // live, after the catch-up
        assertTrue(r.reaches(lock, 3), "the replayed start let the late tracker see the strike's lock");
        assertFalse(r.reaches(lock, 4));
    }

    private static List<Outbox.Event> seen(CatchUp c, Routing r, int viewer) {
        List<Outbox.Event> got = new ArrayList<>();
        c.replay(DEMON, e -> {
            if (r.reaches(e, viewer)) {
                got.add(e);
            }
        });
        return got;
    }

    @Test
    void theStepsPruneAllocatesNothing() {
        CatchUp c = new CatchUp();
        c.record(started(7, DEMON));
        c.record(started(8, OTHER));
        LongPredicate pending = strike -> true;
        for (int i = 0; i < 50_000; i++) {
            c.prune(pending);
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] windows = new long[10];
        for (int win = 0; win < windows.length; win++) {
            long before = bean.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 2_000; i++) {
                c.prune(pending);
            }
            windows[win] = bean.getCurrentThreadAllocatedBytes() - before;
        }
        int clean = 0;
        for (long bytes : windows) {
            clean += bytes == 0 ? 1 : 0;
        }
        assertTrue(
                clean >= 6, "the prune allocated in " + (10 - clean) + " of 10 windows: " + Arrays.toString(windows));
    }
}
