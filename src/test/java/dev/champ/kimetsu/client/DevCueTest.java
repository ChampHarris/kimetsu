package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.State;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code /kimetsu_dev cue <kind>}'s staging (5d-4a's sheets, D-145's A12): the events the server would route for one
 * cue on you, from the frame's scheduled tick, with a negative strike id so it never meets a real one. A strike is the
 * demon's most frequent attack with that cue, cued at its frame data's lead and locked where the demon stands; a feint
 * breaks {@code feint_break_ticks} before its impact; the Thread opens for the profile's {@code thread_ticks}, the
 * demon's own state kept; a clash flashes from the tick.
 */
class DevCueTest {
    private static final int DEMON = 10;
    private static final int SELF = 1;
    private static final DemonProfile PROFILE = Fixtures.demon();
    private static final Map<String, FrameData> FRAMES = Fixtures.demonAttacks();

    private static List<Outbox.Event> stage(DevCue.Kind kind, Outbox.MobState state) {
        return DevCue.events(kind, -7, DEMON, SELF, 500, 1.5, 64, -2.5, 37f, PROFILE, FRAMES, state);
    }

    @Test
    void aStrikeKind_isTheDemonsMostFrequentAttackWithThatCue() {
        assertEquals(
                Fixtures.SWIPE_ID,
                DevCue.attack(PROFILE, FRAMES, FrameData.Cue.Type.WHITE).id(),
                "52 of 100");
        assertEquals(
                Fixtures.LEAP_ID,
                DevCue.attack(PROFILE, FRAMES, FrameData.Cue.Type.RED).id(),
                "11 over 9");
        assertEquals(
                Fixtures.GRAB_ID,
                DevCue.attack(PROFILE, FRAMES, FrameData.Cue.Type.PURPLE).id());
    }

    @Test
    void aWhiteCue_isAStrikeOnYou_cuedAtItsLead_lockedWhereTheDemonStands() {
        List<Outbox.Event> e = stage(DevCue.Kind.WHITE, null);
        assertEquals(2, e.size());
        Outbox.AttackStarted s = assertInstanceOf(Outbox.AttackStarted.class, e.get(0));
        FrameData swipe = FRAMES.get(Fixtures.SWIPE_ID);
        assertEquals(-7, s.strike(), "negative: never a real strike's id");
        assertEquals(DEMON, s.attacker());
        assertEquals(SELF, s.target());
        assertEquals(Fixtures.SWIPE_ID, s.attack());
        assertEquals(500, s.windupStartTick());
        assertEquals(500 + swipe.windup(), s.impactTick());
        assertEquals(s.impactTick() - swipe.cue().lead(), s.cueTick());
        Outbox.StrikeLocked l = assertInstanceOf(Outbox.StrikeLocked.class, e.get(1));
        assertEquals(-7, l.strike());
        assertEquals(37, l.yaw(), 1e-6);
        assertEquals(1.5, l.x());
        assertEquals(-2.5, l.z());
        assertEquals(0, l.lunge(), "it stays put");
    }

    @Test
    void aFeint_breaksItsWhiteCueFeintBreakTicksBeforeItsImpact() {
        List<Outbox.Event> e = stage(DevCue.Kind.FEINT, null);
        Outbox.AttackStarted s = assertInstanceOf(Outbox.AttackStarted.class, e.get(0));
        Outbox.AttackCancelled c = assertInstanceOf(Outbox.AttackCancelled.class, e.get(e.size() - 1));
        assertEquals(s.strike(), c.strike());
        assertEquals(s.impactTick() - PROFILE.strings().feintBreakTicks(), c.tick());
        assertEquals(Outbox.AttackCancelled.FEINT, c.why());
        assertTrue(c.tick() > s.cueTick(), "its glint shows before it shatters");
    }

    @Test
    void theThread_opensForItsTicks_theDemonsStateKept() {
        Outbox.MobState was = new Outbox.MobState(DEMON, 480, State.GUARD, Long.MIN_VALUE, 70_000, 12, 470, 3);
        Outbox.MobState m = assertInstanceOf(
                Outbox.MobState.class, stage(DevCue.Kind.THREAD, was).get(0));
        assertEquals(new Outbox.MobState(DEMON, 500, State.GUARD, 500 + PROFILE.threadTicks(), 70_000, 12, 470, 3), m);
        Outbox.MobState fresh = assertInstanceOf(
                Outbox.MobState.class, stage(DevCue.Kind.THREAD, null).get(0));
        assertEquals(State.NEUTRAL, fresh.state(), "no state known: standing");
    }

    @Test
    void aFlash_isAClashOnYouFromTheTick() {
        List<Outbox.Event> e = stage(DevCue.Kind.FLASH, null);
        Outbox.ClashStarted c = assertInstanceOf(Outbox.ClashStarted.class, e.get(0));
        assertEquals(new Outbox.ClashStarted(-7, DEMON, SELF, 500, Micros.ofTicks(500)), c);
        Outbox.ClashEnded end = assertInstanceOf(Outbox.ClashEnded.class, e.get(1));
        assertTrue(end.tick() > 500, "its pose ends, as /kimetsu_dev pose clash does");
    }
}
