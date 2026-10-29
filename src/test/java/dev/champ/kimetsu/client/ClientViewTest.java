package dev.champ.kimetsu.client;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.client.present.Layer;
import dev.champ.kimetsu.client.present.Poser;
import dev.champ.kimetsu.client.present.Stance;
import dev.champ.kimetsu.client.present.StrikeSource;
import dev.champ.kimetsu.client.present.StrikeSpan;
import dev.champ.kimetsu.client.present.Track;
import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.fight.HitResult;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Segment;
import dev.champ.kimetsu.core.strike.Verdict;
import dev.champ.kimetsu.net.NetMessage;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What the client keeps of the routed state: a mob's state goes when the mob does, however it goes (slice 5c-1), and
 * its player's combat flag runs out on the client's ticks and goes with the level (5c-2); its player's own push is
 * handed on for the client to play (5c-3b). Every routed event is handled, by a switch with no default (5d-3a Step 3):
 * shown on a combatant's track or kept for a named later slice, or ignored for a stated reason; orphans are dropped, and
 * a combatant's track and strikes go with it.
 */
class ClientViewTest {
    private static final int SELF = 1;
    private static final int ALLY = 2;
    private static final int DEMON = 10;
    private static final FrameData SWIPE = Fixtures.swipe();
    private static final Map<String, FrameData> FRAMES = Map.of(SWIPE.id(), SWIPE);

    /**
     * How each kind is handled: the plan's table (5d-3a Step 3). A new kind fails {@link #everyEventKindIsHandled}
     * until its row is added here and its case in {@code ClientView.accept}.
     */
    private static final Map<Class<?>, String> HANDLED = Map.ofEntries(
            entry(Outbox.AttackStarted.class, "a strike in flight; incoming when aimed at this player"),
            entry(Outbox.StrikeLocked.class, "its strike's lock; dropped for a strike the client doesn't know (D-093)"),
            entry(Outbox.Involved.class, "this player's: the strike is incoming (the cue marker, 5d-3b's notch)"),
            entry(
                    Outbox.AttackCancelled.class,
                    "the strike leaves the flight and ends at its tick; its pose blends out"),
            entry(Outbox.StrikeResult.class, "this player's: a verdict; others': kept on the fighter's track for 5d-5"),
            entry(Outbox.PlayerEffect.class, "this player's Push and AssistChanged; the rest: the snapshot's"),
            entry(Outbox.SwingLanded.class, "kept on the mob's track for 5d-5's flinch and spark"),
            entry(Outbox.SwingDealt.class, "ignored: the snapshot carries the meters; no damage numbers"),
            entry(Outbox.HoldStarted.class, "the hold pose on the mob's track"),
            entry(Outbox.HoldEnded.class, "ends the hold pose"),
            entry(Outbox.ClashStarted.class, "the clash pose on both tracks, the flash kept for 5d-4/5d-5"),
            entry(Outbox.ClashEnded.class, "ends the clash pose"),
            entry(Outbox.MobDied.class, "the track ends and its strikes are forgotten"),
            entry(Outbox.MobLeft.class, "the track and its strikes are dropped"),
            entry(Outbox.MobState.class, "the state pose, Posture, stake and Thread; ignored with no such entity"),
            entry(Outbox.GuardYaw.class, "turns the guard pose (Rule 11.4)"),
            entry(Outbox.Targeting.class, "the head's target, kept on the track (Rule 11.2)"),
            entry(Outbox.Clang.class, "a head recoil for the data's recoil length; the sound is 5d-5's"),
            entry(Outbox.Pushed.class, "ignored: the server moves the mob and tracking shows it (Rule 7.10)"),
            entry(Outbox.WallSplat.class, "ignored: the snapshot carries the knockdown; the Tech feedback is 5d-5's"),
            entry(Outbox.PoseClamped.class, "routed to nobody (Audience): the fight log's"),
            entry(Outbox.GuardJudged.class, "routed to nobody (Audience): the fight log's"));

    /** The client has every entity. */
    private static boolean known(int id) {
        return true;
    }

    private static Set<Class<?>> kinds(Class<?> c) {
        Set<Class<?>> out = new HashSet<>();
        if (c.isSealed()) {
            for (Class<?> sub : c.getPermittedSubclasses()) {
                out.addAll(kinds(sub));
            }
        } else {
            out.add(c);
        }
        return out;
    }

    /** Any value of {@code type}: zeros, the first enum constant, the first record a sealed type permits that builds. */
    private static Object sample(Class<?> type) throws ReflectiveOperationException {
        if (type == long.class) {
            return 0L;
        } else if (type == int.class) {
            return 0;
        } else if (type == double.class) {
            return 0.0;
        } else if (type == boolean.class) {
            return false;
        } else if (type == String.class) {
            return SWIPE.id();
        } else if (type.isEnum()) {
            return type.getEnumConstants()[0];
        } else if (type.isSealed()) {
            ReflectiveOperationException last = null;
            for (Class<?> sub : type.getPermittedSubclasses()) {
                try {
                    return sample(sub);
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    last = e instanceof ReflectiveOperationException r ? r : new ReflectiveOperationException(e);
                }
            }
            throw last != null ? last : new ReflectiveOperationException("nothing permitted in " + type);
        } else if (type.isRecord()) {
            RecordComponent[] parts = type.getRecordComponents();
            Class<?>[] types = new Class<?>[parts.length];
            Object[] args = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                args[i] = sample(types[i]);
            }
            Constructor<?> make = type.getDeclaredConstructor(types);
            make.setAccessible(true);
            return make.newInstance(args);
        }
        throw new ReflectiveOperationException("no sample for " + type);
    }

    @Test
    void everyEventKindIsHandled() throws ReflectiveOperationException {
        Set<Class<?>> kinds = kinds(Outbox.Event.class);
        assertEquals(22, kinds.size(), "Outbox's 22 kinds (EventCodec's header says 21: a cleanup item)");
        assertEquals(HANDLED.keySet(), kinds, "a kind with no row in the handling table, or a row with no kind");
        List<Outbox.Event> all = new ArrayList<>();
        for (Class<?> k : kinds) {
            all.add((Outbox.Event) sample(k));
        }
        ClientView v = new ClientView();
        v.accept(new NetMessage.Tick(120, all, null), SELF, ClientViewTest::known); // one of each, none throws
        v.accept(new NetMessage.Tick(121, all, null), SELF, id -> false);
    }

    private static Outbox.MobState state(State s, long tick) {
        return new Outbox.MobState(DEMON, tick, s, Long.MIN_VALUE, 100, 0, tick, 0);
    }

    private static ClientView seeing() {
        ClientView v = new ClientView();
        v.accept(new NetMessage.Tick(120, List.of(state(State.NEUTRAL, 120)), null), SELF, ClientViewTest::known);
        assertNotNull(v.mob(DEMON));
        return v;
    }

    /** A swipe by the demon on {@code target}: windup from 100, its cue at 100, its impact at 110. */
    private static Outbox.AttackStarted swipe(long strike, int target) {
        return new Outbox.AttackStarted(strike, DEMON, target, SWIPE.id(), 99, 100, 100, 110, strike, true);
    }

    private static Outbox.StrikeLocked lockOf(long strike) {
        return new Outbox.StrikeLocked(strike, 0, 0, 64, 0, 1);
    }

    private static void feed(ClientView v, long tick, Outbox.Event... events) {
        v.accept(new NetMessage.Tick(tick, List.of(events), null), SELF, ClientViewTest::known);
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
        assertEquals(mine, v.accept(new NetMessage.Tick(122, events, null), SELF, ClientViewTest::known));
        assertNull(v.accept(
                new NetMessage.Tick(123, List.of(new Outbox.PlayerEffect(2, 123, theirs)), null),
                SELF,
                ClientViewTest::known));
    }

    /**
     * The assist level the server gives its player (5d-2): its own {@code AssistChanged}, not another player's; 0 until
     * the first, which every join sends.
     */
    @Test
    void itKeepsItsPlayersAssistLevel_notAnotherPlayers() {
        ClientView v = new ClientView();
        assertEquals(0, v.assist());
        feed(v, 122, new Outbox.PlayerEffect(SELF, 122, new Effect.AssistChanged(2)));
        assertEquals(2, v.assist());
        feed(v, 123, new Outbox.PlayerEffect(2, 123, new Effect.AssistChanged(3)));
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
        feed(v, 120, state(State.NEUTRAL, 120));
        assertTrue(v.fights(DEMON));
        assertFalse(v.fights(DEMON + 1), "another demon");
        feed(v, 121, new Outbox.MobDied(DEMON, 121, SELF));
        assertFalse(v.fights(DEMON), "a dead demon");
    }

    @Test
    void aMobLeftThatReachesTheClientForgetsTheMobToo() {
        ClientView v = seeing();
        feed(v, 121, new Outbox.MobLeft(DEMON, 121));
        assertNull(v.mob(DEMON));
    }

    /** D-093: a {@code MobState} for an entity the client lacks would make {@code fights} true for nothing. */
    @Test
    void aMobStateForAnEntityTheClientLacksIsIgnored() {
        ClientView v = new ClientView();
        v.accept(new NetMessage.Tick(120, List.of(state(State.GUARD, 120)), null), SELF, id -> false);
        assertFalse(v.fights(DEMON));
        assertNull(v.mob(DEMON));
        assertNull(v.track(DEMON), "no track for it either");
    }

    /** The {@code onLeave} race (D-093): one in flight as its entity leaves doesn't bring the mob back. */
    @Test
    void aMobStateInFlightAsItsEntityLeftDoesntBringItBack() {
        ClientView v = seeing();
        v.mobGone(DEMON);
        v.accept(new NetMessage.Tick(121, List.of(state(State.GUARD, 121)), null), SELF, id -> id != DEMON);
        assertFalse(v.fights(DEMON));
    }

    /** D-093: a lock stored for a strike the client never had would never be pruned. */
    @Test
    void aLockForAStrikeTheClientDoesntKnowIsDropped() {
        ClientView v = new ClientView();
        feed(v, 100, lockOf(7));
        assertNull(v.lock(7));
        feed(v, 101, swipe(7, SELF), lockOf(7));
        assertNotNull(v.lock(7));
    }

    /** Like an orphan lock (D-093): kept, it would never be pruned, and a strike of that id later would read as on you. */
    @Test
    void anInvolvedForAStrikeTheClientDoesntKnowIsDropped() {
        ClientView v = seeing();
        feed(v, 100, new Outbox.Involved(7, SELF, 100));
        feed(v, 101, swipe(7, ALLY));
        assertFalse(v.incoming(v.strikes().get(7), SELF));
        assertNull(v.cueAt(SELF, Micros.ofTicks(105)));
    }

    @Test
    void aMobsDeathForgetsItsStrikesAndItsTrack() {
        ClientView v = seeing();
        feed(v, 101, swipe(7, SELF), lockOf(7), swipe(6, SELF), new Outbox.AttackCancelled(6, 101, "feint"));
        assertNotNull(v.track(DEMON));
        assertTrue(v.cancelled(6));
        feed(v, 102, new Outbox.MobDied(DEMON, 102, SELF));
        assertTrue(v.strikes().isEmpty(), "its strike");
        assertFalse(v.cancelled(6), "its cancelled strike");
        assertNull(v.lock(7));
        assertNull(v.cueAt(SELF, Micros.ofTicks(105)), "its cue");
        assertNull(v.track(DEMON));
    }

    @Test
    void aMobsDeathKeepsAnotherMobsStrikes() {
        ClientView v = seeing();
        int other = DEMON + 1;
        feed(
                v,
                101,
                swipe(7, SELF),
                swipe(6, SELF),
                new Outbox.AttackCancelled(6, 101, "feint"),
                new Outbox.AttackStarted(8, other, SELF, SWIPE.id(), 99, 100, 100, 110, 8, true),
                new Outbox.AttackStarted(9, other, SELF, SWIPE.id(), 99, 100, 100, 110, 9, true),
                new Outbox.AttackCancelled(9, 101, "feint"));
        feed(v, 102, new Outbox.MobDied(DEMON, 102, SELF));
        assertNotNull(v.strikes().get(8), "the other demon's strike in flight");
        assertTrue(v.cancelled(9), "and its cancelled one");
        assertFalse(v.cancelled(6));
    }

    /** The beat lock stretched its windup to start at 97; its cue, which locks its path, stays at 100. */
    @Test
    void aStretchedWindupsSpanLocksAtItsCue() {
        ClientView v = seeing();
        feed(v, 101, new Outbox.AttackStarted(7, DEMON, SELF, SWIPE.id(), 96, 97, 100, 110, 7, true));
        StrikeSpan out = new StrikeSpan();
        assertTrue(v.strikesBy(DEMON, Map.of(SWIPE.id(), SWIPE), 1.74).newest(Micros.ofTicks(105), out));
        assertEquals(Micros.ofTicks(97), out.start());
        assertEquals(Micros.ofTicks(100), out.lock());
    }

    @Test
    void ofTwoStrikesBegunOnOneTickTheStandInDrawsTheNewer() {
        ClientView v = seeing();
        feed(v, 101, swipe(8, SELF), swipe(7, SELF));
        StrikeSpan out = new StrikeSpan();
        assertTrue(v.strikesBy(DEMON, Map.of(SWIPE.id(), SWIPE), 1.74).newest(Micros.ofTicks(105), out));
        assertEquals(8, out.id());
    }

    @Test
    void aMobLeftDropsItsStrikesAndItsTrack() {
        ClientView v = seeing();
        feed(v, 101, swipe(7, SELF));
        feed(v, 102, new Outbox.MobLeft(DEMON, 102));
        assertTrue(v.strikes().isEmpty());
        assertNull(v.track(DEMON));
    }

    @Test
    void whenItsEntityGoesItsTrackAndStrikesGo() {
        ClientView v = seeing();
        feed(v, 101, swipe(7, SELF), lockOf(7), new Outbox.AttackCancelled(8, 101, "feint"));
        v.mobGone(DEMON);
        assertTrue(v.strikes().isEmpty());
        assertNull(v.track(DEMON));
    }

    @Test
    void leavingTheLevelDropsEveryTrackAndStrike() {
        ClientView v = seeing();
        feed(v, 101, swipe(7, SELF), swipe(8, SELF), lockOf(7), new Outbox.AttackCancelled(8, 101, "feint"));
        v.leftLevel();
        assertTrue(v.strikes().isEmpty());
        assertFalse(v.cancelled(8));
        assertNull(v.lock(7));
        assertNull(v.track(DEMON));
    }

    /** Rule 2.9: a strike that isn't aimed at this player but involves them is theirs to answer, on the HUD too. */
    @Test
    void aStrikeIsIncomingWhenAimedAtItsPlayer_orWhenItsPlayerIsInvolved() {
        ClientView v = new ClientView();
        Outbox.AttackStarted atAlly = swipe(7, ALLY);
        feed(v, 100, atAlly, new Outbox.Involved(7, ALLY, 100));
        assertFalse(v.incoming(atAlly, SELF), "another player's involvement reaches every viewer of the strike");
        assertNull(v.cueAt(SELF, Micros.ofTicks(105)));
        feed(v, 101, new Outbox.Involved(7, SELF, 101));
        assertTrue(v.incoming(atAlly, SELF));
        assertSame(atAlly, v.cueAt(SELF, Micros.ofTicks(105)));
        Outbox.AttackStarted atSelf = swipe(8, SELF);
        feed(v, 102, atSelf);
        assertTrue(v.incoming(atSelf, SELF));
    }

    @Test
    void theCueShowingIsTheIncomingStrikeWithTheEarliestImpact_fromItsCueToItsImpact() {
        ClientView v = new ClientView();
        Outbox.AttackStarted later = new Outbox.AttackStarted(8, DEMON, SELF, SWIPE.id(), 99, 101, 103, 113, 8, true);
        feed(v, 100, later, swipe(7, SELF));
        assertNull(v.cueAt(SELF, Micros.ofTicks(100) - 1), "before its cue");
        assertEquals(7, v.cueAt(SELF, Micros.ofTicks(105)).strike());
        assertEquals(8, v.cueAt(SELF, Micros.ofTicks(111)).strike(), "the first one's impact is past");
        assertNull(v.cueAt(SELF, Micros.ofTicks(113) + 1));
    }

    /**
     * A cancel takes the strike out of the flight (the cue goes; the dev hook arms the next, D-091) and ends its pose at
     * the cancel's tick; its lock stays for the blend back until the strike is pruned.
     */
    @Test
    void aCancelledStrikeLeavesTheFlight_endingAtItsTick() {
        ClientView v = seeing();
        feed(v, 103, swipe(7, SELF), lockOf(7), new Outbox.AttackCancelled(7, 105, "feint"));
        assertFalse(v.strikes().containsKey(7));
        assertTrue(v.cancelled(7));
        assertNull(v.cueAt(SELF, Micros.ofTicks(104)), "its cue goes with it");
        assertNotNull(v.lock(7), "kept for the blend back");
        StrikeSpan span = new StrikeSpan();
        assertTrue(v.strikesBy(DEMON, FRAMES, 1.74).newest(Micros.ofTicks(104), span));
        assertEquals(Micros.ofTicks(105), span.end());
        assertNotNull(span.path(), "placed: it locked");
    }

    @Test
    void theStrikeSourceGivesTheNewestStrikeBegun_endingAtItsRecoverysEnd() {
        ClientView v = seeing();
        Outbox.AttackStarted second =
                new Outbox.AttackStarted(8, DEMON, SELF, SWIPE.id(), 99, 112, 113, 122, 7, true); // its string's next
        feed(v, 100, swipe(7, SELF), second);
        StrikeSource s = v.strikesBy(DEMON, FRAMES, 1.74);
        StrikeSpan span = new StrikeSpan();
        assertFalse(s.newest(Micros.ofTicks(100) - 1, span), "none begun");
        assertTrue(s.newest(Micros.ofTicks(105), span));
        assertEquals(7, span.id());
        assertEquals(Micros.ofTicks(110 + SWIPE.active() + SWIPE.recovery()), span.end());
        assertEquals(Micros.ofTicks(SWIPE.active() + SWIPE.recovery()), span.follow());
        assertNull(span.path(), "not locked");
        assertTrue(s.newest(Micros.ofTicks(112), span));
        assertEquals(8, span.id(), "the next strike of its string, from its own windup");
        assertFalse(v.strikesBy(DEMON + 1, FRAMES, 1.74).newest(Micros.ofTicks(105), span), "another's strikes");
    }

    @Test
    void othersResultsGoToTheFightersTrack_yoursToTheVerdicts() {
        ClientView v = new ClientView();
        Outbox.StrikeResult mine = new Outbox.StrikeResult(7, DEMON, SELF, Verdict.Kind.HIT, 1.25);
        Outbox.StrikeResult ally = new Outbox.StrikeResult(7, DEMON, ALLY, Verdict.Kind.DEFLECT, 0);
        feed(v, 110, swipe(7, SELF), mine, ally);
        assertEquals(mine, v.verdicts().iterator().next().result());
        assertNull(v.track(SELF), "yours isn't kept on a track");
        Track k = v.track(ALLY);
        assertNotNull(k);
        assertEquals(ally, k.result());
        assertEquals(110, k.resultTick());
        v.accept(
                new NetMessage.Tick(111, List.of(new Outbox.StrikeResult(9, DEMON, 3, Verdict.Kind.HIT, 1)), null),
                SELF,
                id -> id != 3);
        assertNull(v.track(3), "a fighter the client lacks gets no track");
    }

    @Test
    void eachMobEventLandsOnItsTrack() {
        ClientView v = seeing();
        Outbox.SwingLanded landed =
                new Outbox.SwingLanded(SELF, DEMON, "kimetsu:light_chain/1", 0, 0, 121, HitResult.LANDED);
        feed(
                v,
                121,
                landed,
                new Outbox.HoldStarted(7, DEMON, SELF, 121, 141),
                new Outbox.GuardYaw(DEMON, 121, 64),
                new Outbox.Targeting(DEMON, 121, SELF),
                new Outbox.Clang(DEMON, 121, SELF, true));
        Track k = v.track(DEMON);
        assertNotNull(k);
        assertEquals(landed, k.landed());
        assertEquals(64, k.guardStep());
        assertEquals(SELF, k.target());
        assertEquals(Micros.ofTicks(121), k.recoilFrom());
        Poser poser = new Poser();
        StrikeSource none = v.strikesBy(DEMON, FRAMES, 1.74);
        assertEquals(Stance.HOLD, poser.layerAt(k, none, Micros.ofTicks(125)).stance());
        feed(v, 130, new Outbox.HoldEnded(7, DEMON, SELF, 130, dev.champ.kimetsu.core.fight.Release.ESCAPED, 0));
        assertEquals(Stance.NEUTRAL, poser.layerAt(k, none, Micros.ofTicks(131)).stance());
        feed(v, 132, new Outbox.Clang(DEMON, 132, SELF, false));
        assertEquals(Micros.ofTicks(121), k.recoilFrom(), "a clang with no recoil keeps the last");
    }

    @Test
    void aClashShowsOnBothTracks() {
        ClientView v = seeing();
        feed(v, 140, new Outbox.ClashStarted(7, DEMON, SELF, 140, 7_000_000));
        Poser poser = new Poser();
        StrikeSource none = v.strikesBy(DEMON, FRAMES, 1.74);
        for (int id : new int[] {DEMON, SELF}) {
            Track k = v.track(id);
            assertNotNull(k, "track " + id);
            Layer l = poser.layerAt(k, none, Micros.ofTicks(141));
            assertEquals(Stance.CLASH, l.stance(), "track " + id);
            assertEquals(7_000_000, k.flashMicros());
        }
        feed(v, 150, new Outbox.ClashEnded(7, DEMON, SELF, 150, Outbox.ClashOutcome.TIE));
        assertEquals(
                Stance.NEUTRAL,
                poser.layerAt(v.trackFor(DEMON), none, Micros.ofTicks(151)).stance());
    }

    @Test
    void aMobStatesPoseShowsOnItsTrackFromItsTick() {
        ClientView v = seeing();
        feed(v, 130, state(State.STAGGER, 130));
        Track k = v.track(DEMON);
        assertNotNull(k);
        StrikeSource none = v.strikesBy(DEMON, FRAMES, 1.74);
        assertEquals(
                Stance.STAGGER,
                new Poser().layerAt(k, none, Micros.ofTicks(130)).stance());
        assertEquals(
                Stance.NEUTRAL,
                new Poser().layerAt(k, none, Micros.ofTicks(129)).stance());
    }

    /** A strike and everything kept with it go together, a while after its impact (past its blend back). */
    @Test
    void aPrunedStrikeTakesItsLockCancelAndInvolvementWithIt() {
        ClientView v = seeing();
        feed(v, 100, swipe(7, ALLY), lockOf(7), new Outbox.Involved(7, SELF, 100), swipe(8, SELF));
        feed(v, 101, new Outbox.AttackCancelled(8, 101, "feint"));
        feed(v, 110 + ClientView.KEEP_TICKS, new Outbox.Targeting(DEMON, 170, SELF));
        assertTrue(v.strikes().containsKey(7), "kept until KEEP_TICKS past its impact");
        feed(v, 111 + ClientView.KEEP_TICKS, new Outbox.Targeting(DEMON, 171, SELF));
        assertTrue(v.strikes().isEmpty());
        assertFalse(v.cancelled(8));
        assertNull(v.lock(7));
        assertFalse(v.incoming(swipe(7, ALLY), SELF), "its involvement went with it");
    }

    /** KEEP_TICKS outlasts the longest drawing of a strike: its recovery, the blend back, a hold's debt, a late offset. */
    @Test
    void aStrikeIsKeptPastItsLongestDrawing() {
        int longest = 0;
        for (FrameData f : Fixtures.demonAttacks().values()) {
            longest = Math.max(longest, f.active() + f.recovery());
        }
        long offsetTicks = Micros.ofMillis(dev.champ.kimetsu.net.NetCodec.MAX_OFFSET_MS) / Micros.PER_TICK;
        int holdTicks = 5; // 5d-5's limit: 0.25 s
        assertTrue(
                ClientView.KEEP_TICKS
                        >= longest + dev.champ.kimetsu.client.present.Feel.MAX_BLEND_TICKS + holdTicks + offsetTicks,
                "KEEP_TICKS " + ClientView.KEEP_TICKS);
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
        v.accept(new NetMessage.Tick(120, List.of(), inCombatUntil(118, 420)), SELF, ClientViewTest::known);
        assertTrue(v.inCombat(120));
        assertTrue(v.inCombat(419), "out of combat a tick early");
        assertFalse(v.inCombat(420), "still in combat at its end");
        v.accept(new NetMessage.Tick(130, List.of(), inCombatUntil(128, Long.MIN_VALUE)), SELF, ClientViewTest::known);
        assertFalse(v.inCombat(130), "a snapshot out of combat left the flag up");
    }

    /**
     * A dimension change: the server's new fighter there starts out of combat, and its first snapshot waits for a
     * settled frame, so the old flag goes with the level (code review: it refused mining for up to 15 s).
     */
    @Test
    void leavingTheLevelDropsTheFlag() {
        ClientView v = new ClientView();
        v.accept(new NetMessage.Tick(120, List.of(), inCombatUntil(118, 420)), SELF, ClientViewTest::known);
        v.leftLevel();
        assertFalse(v.inCombat(121));
    }
}
