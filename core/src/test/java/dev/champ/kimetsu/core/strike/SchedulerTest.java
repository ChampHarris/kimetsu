package dev.champ.kimetsu.core.strike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.BeatLock;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.strike.Scheduler.Plan;
import dev.champ.kimetsu.core.strike.Scheduler.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Rules 7.7, 7.11 and 7.12: a beat-locked strike lands on its target's first beat that leaves room for its windup and
 * for its cue lead plus the commit margin; a string's strikes land on consecutive beats, each follow-up winding up from
 * the previous strike's last active tick with its lead capped at that windup; at most 2 strikes land on one target in
 * one beat, a string that would break that moving whole.
 */
class SchedulerTest {
    private static final StrikeRules RULES = Fixtures.strikes();
    private static final List<FrameData> STRIKES = List.of(
            Fixtures.swipe(),
            Fixtures.heavy(),
            Fixtures.leap(),
            Fixtures.lowSweep(),
            Fixtures.grab(),
            Fixtures.guardCounter());

    private static Plan plan(long commit, PulseSchedule pulse, FrameData... string) {
        return Scheduler.plan(commit, List.of(string), pulse, RULES, Scheduler.ANY_BEAT);
    }

    private static int minimum(FrameData f) {
        BeatLock lock = f.beatLock();
        return Math.max(lock == null ? 0 : lock.windupMin(), f.cue().lead() + RULES.commitMarginTicks());
    }

    @Test
    void anOpenerLandsOnTheFirstBeatItsWindupAndLeadAllow() {
        PulseSchedule water = new PulseSchedule(13); // beats at 0, 13, … 104, 117, 130
        Slot swipe = plan(100, water, Fixtures.swipe()).slots().get(0); // needs max(9, 10 + 2) = 12 ticks
        assertEquals(117, swipe.impactTick());
        assertEquals(107, swipe.cueTick());
        assertEquals(107, swipe.windupStartTick()); // its windup is its lead: the demon circles until then
        assertEquals(10, swipe.windupTicks());
        assertEquals(10, swipe.leadTicks());
        assertEquals(9, swipe.beat());
        Slot heavy = plan(100, water, Fixtures.heavy()).slots().get(0); // needs max(11, 14 + 2) = 16
        assertEquals(117, heavy.impactTick());
        assertEquals(103, heavy.cueTick());
        assertEquals(117, plan(105, water, Fixtures.swipe()).slots().get(0).impactTick()); // exactly 12 ticks
        assertEquals(130, plan(106, water, Fixtures.swipe()).slots().get(0).impactTick()); // 11: the next beat
    }

    @Test
    void randomCommitsLandOnABeatInsideTheirMarginAndLessThanABeatLate() {
        Random rnd = new Random(31);
        for (int i = 0; i < 10_000; i++) {
            int[] periods = {11, 12, 13};
            PulseSchedule pulse = new PulseSchedule(periods[rnd.nextInt(3)]);
            long commit = rnd.nextInt(5_000);
            if (rnd.nextBoolean()) { // a tempo change announced somewhere around the commit
                pulse.changeTempo(Math.max(0, commit - 40 + rnd.nextInt(60)), periods[rnd.nextInt(3)], 2);
            }
            FrameData f = STRIKES.get(rnd.nextInt(STRIKES.size()));
            Plan p = plan(commit, pulse, f);
            assertTrue(p.ok(), p.refused());
            Slot s = p.slots().get(0);
            String at = f.id() + " committed at " + commit;
            assertEquals(pulse.beatMicros(s.beat()), Micros.ofTicks(s.impactTick()), at + ": on a beat");
            assertTrue(s.impactTick() - commit >= minimum(f), at + ": inside its margin");
            assertTrue(
                    Micros.tickOf(pulse.beatMicros(s.beat() - 1)) < commit + minimum(f),
                    at + ": the beat before couldn't have held it");
            assertEquals(s.impactTick() - f.windup(), s.windupStartTick(), at);
            assertEquals(s.impactTick() - f.cue().lead(), s.cueTick(), at);
            assertTrue(s.windupStartTick() >= commit && s.windupStartTick() <= s.cueTick(), at + ": windup in range");
        }
    }

    @Test
    void aStringLandsOnConsecutiveBeatsWindingUpFromTheLastActiveTick() {
        PulseSchedule water = new PulseSchedule(13);
        Plan p = plan(100, water, Fixtures.swipe(), Fixtures.heavy(), Fixtures.grab());
        assertTrue(p.ok(), p.refused());
        List<Slot> s = p.slots();
        assertEquals(
                List.of(117L, 130L, 143L),
                List.of(s.get(0).impactTick(), s.get(1).impactTick(), s.get(2).impactTick()));
        // the heavy winds up from the swipe's last active tick (117, 118): 119 to 130, 11 ticks, lead capped at 11
        assertEquals(119, s.get(1).windupStartTick());
        assertEquals(11, s.get(1).windupTicks());
        assertEquals(11, s.get(1).leadTicks());
        assertEquals(119, s.get(1).cueTick()); // never before the swipe's active frames end
        // the grab after the heavy (3 active ticks): windup 10, its 12-tick lead capped at 10
        assertEquals(133, s.get(2).windupStartTick());
        assertEquals(10, s.get(2).leadTicks());
        assertEquals(133, s.get(2).cueTick());
        for (int i = 0; i < 3; i++) {
            assertEquals(s.get(0).beat() + i, s.get(i).beat());
        }
    }

    @Test
    void aHeavyAfterAHeavyDoesntFitAtWatersBeat() {
        Plan p = plan(100, new PulseSchedule(13), Fixtures.swipe(), Fixtures.heavy(), Fixtures.heavy());
        assertFalse(p.ok());
        assertTrue(p.refused().contains("windup_min"), p.refused());
        assertTrue(p.refused().contains("kimetsu:m1_demon/heavy"), p.refused());
    }

    @Test
    void aFollowUpWhoseCappedLeadFallsBelowTheMinimumIsRefused() {
        // lead 10 and windup_min 7, after a strike with 4 active ticks, at an 11-tick beat: windup 7, lead capped at 7
        FrameData quick = new FrameData(
                "kimetsu:test/quick",
                AttackClass.LIGHT,
                10,
                2,
                10,
                0,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.ALL,
                Armour.NONE,
                false,
                null,
                null,
                new Cue(Cue.Type.WHITE, 10, false),
                new BeatLock(BeatLock.Mode.TARGET, 7, 1, 3, false),
                List.of(new HitWindow(0, 1, 1, 0, 6, 0, false, false, false, 8, 0)),
                Fixtures.box(0, 1, 1, 1, 1, 1, Sweep.Pitch.LEVEL),
                List.of());
        FrameData long4 = new FrameData(
                "kimetsu:test/long",
                AttackClass.LIGHT,
                10,
                4,
                10,
                0,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.ALL,
                Armour.NONE,
                false,
                null,
                null,
                new Cue(Cue.Type.WHITE, 10, false),
                new BeatLock(BeatLock.Mode.TARGET, 7, 1, 3, false),
                List.of(new HitWindow(0, 1, 1, 0, 6, 0, false, false, false, 8, 0)),
                Fixtures.box(0, 1, 1, 1, 1, 1, Sweep.Pitch.LEVEL),
                List.of());
        Plan p = plan(100, new PulseSchedule(11), long4, quick);
        assertFalse(p.ok());
        assertTrue(p.refused().contains("cue lead"), p.refused());
        assertTrue(plan(100, new PulseSchedule(13), long4, quick).ok()); // 13 − 4 = 9: lead 9
    }

    @Test
    void aGuardCounterTakesItsGivenBeatOrTheOneAfter() {
        PulseSchedule water = new PulseSchedule(13);
        long given = 12; // two beats after a blocked hit pressed on beat 10 (tick 130)
        FrameData counter = Fixtures.guardCounter(); // lead 14: it needs 16 ticks
        Slot onTime = Scheduler.plan(134, List.of(counter), water, RULES, given)
                .slots()
                .get(0);
        assertEquals(156, onTime.impactTick());
        assertEquals(142, onTime.cueTick()); // 14 ticks before, just before beat 11 (143)
        Slot late = Scheduler.plan(141, List.of(counter), water, RULES, given)
                .slots()
                .get(0); // 15 ticks left
        assertEquals(169, late.impactTick());
    }

    @Test
    void aStringOverTheCapMovesWholeAndBeyondTheStretchIsRefused() {
        PulseSchedule water = new PulseSchedule(13);
        BeatCap cap = new BeatCap(RULES.strikesPerTargetPerBeat());
        long target = 7;
        cap.add(target, 9); // two strikes already land on beat 9 (tick 117)
        cap.add(target, 9);
        Plan moved = Scheduler.planUnderCap(
                100, List.of(Fixtures.swipe(), Fixtures.swipe()), water, RULES, Scheduler.ANY_BEAT, cap, target);
        assertTrue(moved.ok(), moved.refused());
        assertEquals(10, moved.slots().get(0).beat()); // the whole string a beat later
        assertEquals(11, moved.slots().get(1).beat());
        cap.add(target, 10);
        cap.add(target, 10);
        Plan refused =
                Scheduler.planUnderCap(100, List.of(Fixtures.swipe()), water, RULES, Scheduler.ANY_BEAT, cap, target);
        assertFalse(refused.ok()); // beats 9 and 10 are full, and the stretch is one beat
        assertTrue(refused.refused().contains("2 strikes"), refused.refused());
        cap.remove(target, 9);
        assertTrue(Scheduler.planUnderCap(100, List.of(Fixtures.swipe()), water, RULES, Scheduler.ANY_BEAT, cap, target)
                .ok());
        assertTrue(cap.fits(8, 9), "another target's beats are its own");
    }

    @Test
    void manyAttackersNeverLandThreeStrikesOnOneTargetInABeat() {
        Random rnd = new Random(5);
        for (int run = 0; run < 500; run++) {
            PulseSchedule water = new PulseSchedule(13);
            BeatCap cap = new BeatCap(RULES.strikesPerTargetPerBeat());
            int attackers = 3 + rnd.nextInt(4);
            List<Slot> landed = new ArrayList<>();
            for (int a = 0; a < attackers; a++) {
                List<FrameData> string = new ArrayList<>();
                int length = 1 + rnd.nextInt(3);
                for (int k = 0; k < length; k++) {
                    string.add(k == 0 ? Fixtures.swipe() : rnd.nextBoolean() ? Fixtures.swipe() : Fixtures.lowSweep());
                }
                Plan p = Scheduler.planUnderCap(100 + rnd.nextInt(4), string, water, RULES, Scheduler.ANY_BEAT, cap, 1);
                if (p.ok()) {
                    for (int k = 0; k < p.slots().size(); k++) {
                        assertEquals(
                                p.slots().get(0).beat() + k, p.slots().get(k).beat(), "a string stays whole");
                    }
                    cap.add(1, p);
                    landed.addAll(p.slots());
                }
            }
            for (Slot s : landed) {
                long same = landed.stream().filter(o -> o.beat() == s.beat()).count();
                assertTrue(same <= 2, "beat " + s.beat() + " holds " + same);
                assertTrue(cap.count(1, s.beat()) <= 2);
            }
        }
    }
}
