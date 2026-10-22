package dev.champ.kimetsu.core.fight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Need-to-know (spec Netcode and its payload table): a player's own effects and its swings' damage are its alone; the
 * rest go to whoever sees the event's subject and to the player it names, and a strike's result, a hold and a clash
 * also to that player's trackers. Mob 10 strikes player 1 as strike 7; player 2 tracks the demon, player 3 tracks
 * player 1 only, player 4 is out of range of both.
 */
class AudienceTest {
    private static final int MOB = 10;
    private static final int PLAYER = 1;
    private static final int DEMON_TRACKER = 2;
    private static final int PLAYER_TRACKER = 3;
    private static final int AWAY = 4;
    private static final long STRIKE = 7;

    /** One of every event the world sends. */
    private static final List<Outbox.Event> EVENTS = List.of(
            new Outbox.AttackStarted(STRIKE, MOB, PLAYER, "kimetsu:m1_demon/swipe", 90, 100, 100, 110, 1, true),
            new Outbox.StrikeLocked(STRIKE, 0, 0, 64, 0, 0),
            new Outbox.Involved(STRIKE, PLAYER, 100),
            new Outbox.AttackCancelled(STRIKE, 105, "feint"),
            new Outbox.StrikeResult(STRIKE, MOB, PLAYER, Verdict.Kind.HIT, 1.25),
            new Outbox.PlayerEffect(PLAYER, 110, new Effect.Blocked()),
            new Outbox.SwingLanded(PLAYER, MOB, "kimetsu:light_chain/1", 0, 1, 112, HitResult.LANDED),
            new Outbox.SwingDealt(PLAYER, MOB, 0, 112, 2, 3),
            new Outbox.HoldStarted(STRIKE, MOB, PLAYER, 110, 180),
            new Outbox.HoldEnded(STRIKE, MOB, PLAYER, 150, Release.ESCAPED, 0),
            new Outbox.ClashStarted(STRIKE, MOB, PLAYER, 110, 6_000_000),
            new Outbox.ClashEnded(STRIKE, MOB, PLAYER, 125, Outbox.ClashOutcome.PLAYER),
            new Outbox.MobDied(MOB, 200, PLAYER),
            new Outbox.MobState(MOB, 120, State.STAGGER, 150, 0, 0, 120, 0),
            new Outbox.GuardYaw(MOB, 121, 64),
            new Outbox.Targeting(MOB, 122, PLAYER),
            new Outbox.Clang(MOB, 123, PLAYER, true),
            new Outbox.Pushed(MOB, 124, 1, 0, 2, 4, false));

    private static Outbox.Event of(Class<? extends Outbox.Event> type) {
        for (Outbox.Event e : EVENTS) {
            if (type.isInstance(e)) {
                return e;
            }
        }
        throw new IllegalArgumentException("no " + type.getSimpleName());
    }

    @Test
    void aPlayersOwnEffectsAndItsSwingsDamageAreItsAlone() {
        for (Outbox.Event e : EVENTS) {
            boolean own = e instanceof Outbox.PlayerEffect || e instanceof Outbox.SwingDealt;
            assertEquals(own ? PLAYER : Audience.NOBODY, Audience.privateTo(e), e.toString());
            if (own) {
                assertTrue(Audience.reaches(e, PLAYER, false, false), e.toString());
                assertFalse(Audience.reaches(e, DEMON_TRACKER, true, true), "a tracker never sees " + e);
            }
        }
    }

    @Test
    void everythingElseIsPublic_toWhoeverSeesItsSubjectAndThePlayerItNames() {
        for (Outbox.Event e : EVENTS) {
            if (Audience.privateTo(e) != Audience.NOBODY) {
                continue;
            }
            boolean concernsTheMob = Audience.subject(e) == MOB || Audience.strike(e) == STRIKE;
            assertTrue(concernsTheMob, "every public event names its mob or its strike: " + e);
            assertTrue(Audience.reaches(e, DEMON_TRACKER, true, false), "the demon's tracker sees " + e);
            assertFalse(Audience.reaches(e, AWAY, false, false), "a player out of range doesn't see " + e);
            if (Audience.party(e) == PLAYER) {
                assertTrue(Audience.reaches(e, PLAYER, false, false), "the player it names gets " + e);
            }
        }
        assertEquals(PLAYER, Audience.party(of(Outbox.AttackStarted.class)), "the strike's target");
        assertEquals(MOB, Audience.subject(of(Outbox.StrikeResult.class)), "a result names its attacker");
        assertEquals(MOB, Audience.subject(of(Outbox.HoldEnded.class)), "a hold's end names its mob");
    }

    @Test
    void aResultAHoldAndAClashReachThePlayersTrackersToo_nothingElseDoes() {
        Set<Class<?>> eitherSide = Set.of(
                Outbox.StrikeResult.class,
                Outbox.HoldStarted.class,
                Outbox.HoldEnded.class,
                Outbox.ClashStarted.class,
                Outbox.ClashEnded.class);
        for (Outbox.Event e : EVENTS) {
            boolean reaches = Audience.reaches(e, PLAYER_TRACKER, false, true);
            assertEquals(eitherSide.contains(e.getClass()), reaches, "player 1's tracker and " + e);
        }
    }

    @Test
    void aStrikeEventNamingOnlyItsStrikeGoesToWhoeverGotItsAttackStarted() {
        for (Class<? extends Outbox.Event> c :
                List.of(Outbox.StrikeLocked.class, Outbox.Involved.class, Outbox.AttackCancelled.class)) {
            Outbox.Event e = of(c);
            assertEquals(Audience.NOBODY, Audience.subject(e), c.getSimpleName());
            assertEquals(STRIKE, Audience.strike(e), c.getSimpleName());
            assertTrue(Audience.reaches(e, PLAYER_TRACKER, true, false), "it got the strike's schedule: " + e);
        }
        assertFalse(
                Audience.reaches(of(Outbox.StrikeLocked.class), PLAYER_TRACKER, false, true), "a lock isn't a side");
    }

    @Test
    void anUnclaimedDeathNamesNobody() {
        Outbox.Event e = new Outbox.MobDied(MOB, 200, -1);
        assertEquals(Audience.NOBODY, Audience.party(e));
        assertFalse(Audience.reaches(e, PLAYER, false, true));
    }

    @Test
    void everyEventTheWorldCanSendIsListedHere() {
        Set<Class<?>> listed = new HashSet<>();
        for (Outbox.Event e : EVENTS) {
            listed.add(e.getClass());
        }
        assertEquals(eventTypes(), listed, "a new outbox event needs its audience here and in Audience");
    }

    /** Every record that implements {@link Outbox.Event}, through its sealed sub-interfaces. */
    private static Set<Class<?>> eventTypes() {
        Set<Class<?>> out = new HashSet<>();
        ArrayDeque<Class<?>> todo = new ArrayDeque<>(List.of(Outbox.Event.class));
        while (!todo.isEmpty()) {
            Class<?> c = todo.pop();
            if (c.isRecord()) {
                out.add(c);
            }
            Class<?>[] subs = c.getPermittedSubclasses();
            if (subs != null) {
                todo.addAll(List.of(subs));
            }
        }
        return out;
    }
}
