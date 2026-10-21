package dev.champ.kimetsu.core.strike;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.StrikeRules;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * When a beat-locked string lands (Rules 7.11 and 7.12). Its first strike lands on the first beat of the target's
 * Pulse that leaves room for both its {@code windup_min} and its cue lead plus the commit margin, so the schedule
 * reaches clients before the cue is due. Its windup is fixed (the larger of the two, D-041): when the margin is
 * longer, the attacker keeps circling until the windup starts, and the commit stays invisible. Each later strike lands
 * on the next beat, winding up from the previous strike's last active tick, so its windup is the beat minus that
 * strike's active ticks; it must still be at least its {@code windup_min}, and its cue lead is capped at that windup, so
 * its glint never shows before the previous strike's active frames end. Every impact is fixed at the commit: a later
 * tempo change or result moves none of them.
 *
 * <p>Runs once per commit, so its small result is allocated; nothing here runs per tick.
 */
public final class Scheduler {
    /** No given beat: the first one the margin allows. */
    public static final long ANY_BEAT = Long.MIN_VALUE;

    private Scheduler() {}

    /**
     * One strike of a scheduled string, in ticks: its impact on {@code beat} (the Pulse's global index), its cue, its
     * windup's start and length, and its cue lead (capped at its windup inside a string).
     */
    public record Slot(
            FrameData attack,
            long beat,
            long impactTick,
            long cueTick,
            long windupStartTick,
            int windupTicks,
            int leadTicks) {

        public long impactMicros() {
            return Micros.ofTicks(impactTick);
        }

        public long cueMicros() {
            return Micros.ofTicks(cueTick);
        }
    }

    /** A string's schedule, or why it couldn't be scheduled ({@code refused}). */
    public record Plan(List<Slot> slots, @Nullable String refused) {
        public Plan {
            slots = List.copyOf(slots);
        }

        public static Plan refuse(String why) {
            return new Plan(List.of(), why);
        }

        public boolean ok() {
            return refused == null;
        }
    }

    /**
     * Schedules {@code string} (its strikes in order, each beat-locked) committed at {@code commitTick} on a target whose
     * Pulse is {@code pulse}. Its first strike lands no earlier than beat {@code beatAtLeast} (a guard counter's given
     * beat), or {@link #ANY_BEAT}.
     */
    public static Plan plan(
            long commitTick, List<FrameData> string, PulseSchedule pulse, StrikeRules rules, long beatAtLeast) {
        if (string.isEmpty()) {
            return Plan.refuse("a string needs a strike");
        }
        List<Slot> slots = new ArrayList<>(string.size());
        FrameData first = string.get(0);
        FrameData.BeatLock lock = first.beatLock();
        if (lock == null) {
            return Plan.refuse(first.id() + " isn't beat-locked");
        }
        long earliest = commitTick + Math.max(lock.windupMin(), first.cue().lead() + rules.commitMarginTicks());
        long beat = firstBeatAtOrAfter(pulse, earliest);
        if (beatAtLeast != ANY_BEAT && beat < beatAtLeast) {
            beat = beatAtLeast;
        }
        long impact = tickOf(pulse, beat);
        slots.add(new Slot(
                first,
                beat,
                impact,
                impact - first.cue().lead(),
                impact - first.windup(),
                first.windup(),
                first.cue().lead()));
        for (int k = 1; k < string.size(); k++) {
            FrameData f = string.get(k);
            FrameData.BeatLock fl = f.beatLock();
            if (fl == null) {
                return Plan.refuse(f.id() + " isn't beat-locked");
            }
            Slot previous = slots.get(k - 1);
            long next = previous.beat() + 1;
            long at = tickOf(pulse, next);
            long windupStart = previous.impactTick() + previous.attack().active();
            int windup = (int) (at - windupStart);
            if (windup < fl.windupMin()) {
                return Plan.refuse(f.id() + " after " + previous.attack().id() + " winds up " + windup
                        + " ticks, under its windup_min of " + fl.windupMin());
            }
            int lead = Math.min(f.cue().lead(), windup);
            if (lead < rules.minCueLeadTicks()) {
                return Plan.refuse(f.id() + " after " + previous.attack().id() + " has a cue lead of " + lead
                        + " ticks, under the minimum of " + rules.minCueLeadTicks());
            }
            slots.add(new Slot(f, next, at, at - lead, windupStart, windup, lead));
        }
        return new Plan(slots, null);
    }

    /**
     * {@link #plan}, moved whole a beat at a time while any of its beats already holds the cap's strikes on
     * {@code target}, up to the first strike's {@code max_stretch_beats}; beyond that, refused (the brain tries again).
     */
    public static Plan planUnderCap(
            long commitTick,
            List<FrameData> string,
            PulseSchedule pulse,
            StrikeRules rules,
            long beatAtLeast,
            BeatCap cap,
            long target) {
        Plan p = plan(commitTick, string, pulse, rules, beatAtLeast);
        if (!p.ok()) {
            return p;
        }
        FrameData.BeatLock lock = string.get(0).beatLock();
        int stretch = lock == null ? 0 : lock.maxStretchBeats();
        long firstBeat = p.slots().get(0).beat();
        for (int s = 0; s <= stretch; s++) {
            Plan shifted = s == 0 ? p : plan(commitTick, string, pulse, rules, firstBeat + s);
            if (!shifted.ok()) {
                return shifted;
            }
            if (cap.fits(target, shifted)) {
                return shifted;
            }
        }
        return Plan.refuse("every beat within " + stretch + " of beat " + firstBeat + " already holds " + cap.perBeat()
                + " strikes on target " + target);
    }

    /** The global index of the first beat at or after {@code tick}. */
    private static long firstBeatAtOrAfter(PulseSchedule pulse, long tick) {
        long at = Micros.ofTicks(tick);
        long beat = pulse.nearestBeat(at);
        while (pulse.beatMicros(beat) < at) {
            beat++;
        }
        while (pulse.beatMicros(beat - 1) >= at) {
            beat--;
        }
        return beat;
    }

    private static long tickOf(PulseSchedule pulse, long beat) {
        return Micros.tickOf(pulse.beatMicros(beat));
    }
}
