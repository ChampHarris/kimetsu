package dev.champ.kimetsu.core.demon;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.fight.Brain;
import dev.champ.kimetsu.core.fight.CombatWorld;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.StrikeMob;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.PendingStrike;
import dev.champ.kimetsu.core.strike.Scheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The M1 demon's mind (Rules 11.2–11.5): when it strikes, at whom, and with what. Movement is the mod's (SmartBrainLib
 * closes it to 2.5–3.5 blocks and circles); this decides in step 2 of the tick order and hands every strike to the
 * scheduler, so no second clock times a hit.
 *
 * <ul>
 *   <li><b>Its guard counter</b> first: one its guard asked for commits the tick after the block (Rule 11.4); one that
 *       can't be scheduled, or that a sprint overtakes, refunds its stake.
 *   <li><b>Its target:</b> the nearest player within range it can see, kept through a string; once, a beat before each
 *       string commits, it turns to whoever is nearest then and keeps them (the mod turns its head, so everyone has a
 *       beat's warning of whom it's about to hit); told every so often it's targeted (Rhythm's idle clock, Rule 4.3);
 *       with nobody in sight for the profile's idle time, it drops its target, its meters as they were.
 *   <li><b>Its strings</b> ({@link StringDraw}): the next one's first impact falls on the beat {@code gap ± spread}
 *       after the beat it became free on with a target. The gap runs on through hitstun, a clang's recoil and its guard
 *       counter (Rule 11.4), and starts again after a Posture break, a collapse, a knockdown or a clash. A string commits
 *       exactly its margin before its beat (Rule 7.11), and only with its target in reach: out of reach, it commits
 *       exactly its margin before the next beat it can make; busy over its commit tick, it commits as it's free again,
 *       on the first beat its margin allows (Rule 11.4), so chipping into counters can't hold a string off. A string with
 *       a leap that the Hunger regeneration burns by then could leave unpaid is drawn again; one the scheduler refuses
 *       waits a fresh gap.
 *   <li>{@code /tick sprint} ({@link #pause}): it commits nothing.
 * </ul>
 *
 * <p>Its draws come from its own seeded {@link Rng}: the same seed, the same fight. Its sight tests run on the ticks it's
 * free, for players in range only, and ask the level about each player at most once a step: the step's answers are kept
 * by player, as vanilla's {@code Sensing} keeps a mob's for an AI step (the mod's demons don't run vanilla's AI).
 */
public final class DemonBrain implements Brain {
    private static final long NONE = Long.MIN_VALUE;

    private final Demon d;
    private final DemonProfile p;
    private final StrikeRules strikes;
    private final FrameData counter;
    private final StringDraw draw;
    private final Rng rng;
    private final double drainPerTick; // the most Hunger regeneration burns in a tick
    private final Pose pose = new Pose();
    private final List<FrameData> string = new ArrayList<>();
    private boolean paused;
    private long freeSince = NONE;
    private long noTargetSince = NONE;
    private long lastTargeted = NONE;
    private int told = -1; // whom it last told
    private long impactMicros = NONE; // its next string's first impact, while one is planned
    private boolean feint;
    private boolean turned; // it has turned to its planned string's target
    // this step's sight answers, by player id: a line can't change within a step, so the level is asked once
    private int[] askedIds = new int[8];
    private boolean[] askedClear = new boolean[8];
    private int asked;

    /**
     * The brain of {@code demon}: its profile's attacks looked up in {@code attacks} (frame data by id), drawing from a
     * generator seeded with {@code seed}.
     */
    public DemonBrain(Demon demon, StrikeRules strikes, Map<String, FrameData> attacks, long seed) {
        this.d = demon;
        this.p = demon.profile();
        this.strikes = strikes;
        Map<String, FrameData> loaded = Map.copyOf(attacks);
        p.checkAttacks(loaded);
        FrameData c = loaded.get(p.guard().counter().attack());
        if (c == null) {
            throw new IllegalArgumentException(p.id() + " has no guard counter loaded");
        }
        this.counter = c;
        this.draw = new StringDraw(p, loaded);
        this.rng = new Rng(seed);
        this.drainPerTick = p.regen().hpPerS() * p.hunger().perHp() * Micros.PER_TICK / 1_000_000.0;
    }

    /** {@code /tick sprint}, or a world otherwise hurrying: it commits nothing while paused (Rule 11.2). */
    public void pause(boolean on) {
        paused = on;
    }

    @Override
    public void decide(long n, StrikeMob self, CombatWorld w) {
        asked = 0; // a new step: the last one's answers are stale
        if (d.dead()) {
            return;
        }
        if (paused) {
            if (d.counterAsked()) {
                d.counterRefused(n); // its stake back, rather than a counter on a beat the sprint left behind
            }
            return;
        }
        if (d.counterAsked()) {
            Scheduler.Plan plan = w.commit(d.id(), d.counterFighter(), List.of(counter), d.counterBeat());
            if (!plan.ok()) {
                d.counterRefused(n);
            }
        }
        State state = d.state(n);
        if (state == State.STAGGER || state == State.COLLAPSED || state == State.KNOCKDOWN || state == State.CLASH) {
            impactMicros = NONE; // its next string waits a fresh gap
        }
        // free on the last tick of its recovery or stun: an overdue string commits as that ends (Rule 11.4).
        // A tick later misses the beat its margin just reaches whenever a recovery and the margin make whole
        // beats (the guard counter's do), and a chip in that beat asks for another counter. A hit on that last
        // tick meets the committed string's super armour, so it doesn't stretch the stun (D-049)
        boolean busy = striking(w) || !d.canMove(n + 1);
        if (!busy) {
            if (sees(n, w, w.fighter(d.target()))) {
                noTargetSince = NONE;
            } else {
                choose(n, w);
            }
        }
        tellTargeted(n, w);
        Fighter t = d.target() < 0 ? null : w.fighter(d.target());
        if (busy || t == null) {
            freeSince = NONE;
            return;
        }
        if (freeSince == NONE) {
            freeSince = n;
        }
        if (impactMicros == NONE) {
            int gap = Math.max(
                    p.strings().gapMinBeats(),
                    p.strings().gapBeats()
                            + rng.nextInt(2 * p.strings().gapSpread() + 1)
                            - p.strings().gapSpread());
            impactMicros = t.pulse().beatMicros(beatAtOrAfter(t.pulse(), Micros.ofTicks(freeSince)) + gap);
            feint = draw.draw(rng, d.hunger(), string);
            turned = false;
        }
        int margin = margin(string.get(0));
        long commitTick = Micros.tickOf(impactMicros) - margin;
        if (n < commitTick - beatTicks(t)) {
            return;
        }
        if (!turned) {
            turned = true; // a beat before it commits, once (Rule 11.2)
            Fighter next = nearest(n, w);
            if (next != null && next != t) {
                d.target(next.id());
                tellTargeted(n, w);
                // on its grid, and a full beat's warning before it commits even if it turns late (busy until now)
                long earliest = Micros.ofTicks(n + beatTicks(next) + margin);
                impactMicros = next.pulse().beatMicros(beatAtOrAfter(next.pulse(), Math.max(impactMicros, earliest)));
                return;
            }
        }
        if (n > commitTick) {
            // overdue, busy over its commit tick (its guard counter, hitstun, a clang's recoil): it commits
            // now, as that ends (Rule 11.4: "that string commits as the recovery ends"), on the first beat its
            // margin allows, so a chip meanwhile can't ask for another counter and hold the string off for good
            impactMicros = t.pulse().beatMicros(beatAtOrAfter(t.pulse(), Micros.ofTicks(n + margin)));
            commitTick = n;
        }
        if (n < commitTick) {
            return;
        }
        if (!inReach(t)) { // the mod closes the distance; it waits for the next beat it can make exactly
            impactMicros = committable(t.pulse(), n + 1, margin);
            return;
        }
        if (unpaidLeap(n, t)) {
            feint = draw.draw(rng, d.hunger() - drain(n, t), string);
            return; // a new first strike may need another margin: the next tick works it out
        }
        Scheduler.Plan plan = w.commit(
                d.id(),
                t.id(),
                string,
                beatAtOrAfter(t.pulse(), impactMicros),
                feint ? p.strings().feintBreakTicks() : 0);
        impactMicros = NONE;
        freeSince = plan.ok() ? NONE : n; // refused (its target's beats are taken): a fresh gap, not a retry each tick
    }

    /** Whether any strike of its is committed and not yet over. */
    private boolean striking(CombatWorld w) {
        List<PendingStrike> book = w.pending();
        for (int i = 0; i < book.size(); i++) {
            if (book.get(i).attacker() == d.id()) {
                return true;
            }
        }
        return false;
    }

    /** Between strings, with no target it can see: the nearest player it can, or none; idle after a while with none. */
    private void choose(long n, CombatWorld w) {
        Fighter t = nearest(n, w);
        if (t != null) {
            noTargetSince = NONE;
            d.target(t.id());
            return;
        }
        if (noTargetSince == NONE) {
            noTargetSince = n;
        }
        if (n - noTargetSince >= p.senses().idleTicks() && d.target() >= 0) {
            d.target(-1); // idle, its meters as they were (Rule 11.2)
            impactMicros = NONE;
        }
    }

    private @Nullable Fighter nearest(long n, CombatWorld w) {
        Fighter best = null;
        double bestD = Double.MAX_VALUE;
        List<Fighter> players = w.players();
        for (int i = 0; i < players.size(); i++) {
            Fighter f = players.get(i);
            if (sees(n, w, f)) {
                double dx = pose.x() - d.x();
                double dz = pose.z() - d.z();
                double dist = dx * dx + dz * dz;
                if (dist < bestD) {
                    best = f;
                    bestD = dist;
                }
            }
        }
        return best;
    }

    /**
     * Whether {@code f} is in the fight, within its range and in its sight, its newest pose left in {@code pose}. The
     * level is asked once a step per player; later asks that step get the same answer.
     */
    private boolean sees(long n, CombatWorld w, @Nullable Fighter f) {
        if (f == null || f.leaving() || f.poses().size() == 0) {
            return false;
        }
        f.poses().at(Micros.ofTicks(n), pose);
        double dx = pose.x() - d.x();
        double dz = pose.z() - d.z();
        double range = p.senses().targetRange();
        if (dx * dx + dz * dz > range * range) {
            return false;
        }
        int id = f.id();
        for (int i = 0; i < asked; i++) {
            if (askedIds[i] == id) {
                return askedClear[i];
            }
        }
        boolean clear = w.sight().clear(d.x(), d.y() + d.eyeHeight(), d.z(), pose.x(), pose.y() + f.eye(), pose.z());
        if (asked == askedIds.length) { // more players in range than it has ever had: grows, never shrinks
            askedIds = Arrays.copyOf(askedIds, 2 * asked);
            askedClear = Arrays.copyOf(askedClear, 2 * asked);
        }
        askedIds[asked] = id;
        askedClear[asked] = clear;
        asked++;
        return clear;
    }

    private boolean inReach(Fighter t) {
        if (t.poses().size() == 0) {
            return false;
        }
        t.poses().at(t.poses().newestTime(), pose);
        double dx = pose.x() - d.x();
        double dz = pose.z() - d.z();
        double reach = p.strings().commitRange();
        return dx * dx + dz * dz <= reach * reach;
    }

    /**
     * Whether the planned string holds a leap that the Hunger regeneration may burn before its windup would leave
     * unpaid (Rule 4.4: no leap below its cost). The leap ends a string, so its windup starts before the last impact.
     */
    private boolean unpaidLeap(long n, Fighter t) {
        for (int i = 0; i < string.size(); i++) {
            if (string.get(i).id().equals(p.strings().leap())) {
                return d.hunger() - drain(n, t) < p.hunger().leapCost();
            }
        }
        return false;
    }

    /** The most Hunger regeneration can burn from tick {@code n} to the planned string's last impact. */
    private double drain(long n, Fighter t) {
        long last = Micros.tickOf(impactMicros) + (long) (string.size() - 1) * beatTicks(t);
        return drainPerTick * Math.max(0, last - n);
    }

    /** Tells its target it's targeted now and then, and a new target at once (Rhythm's idle clock, Rule 4.3). */
    private void tellTargeted(long n, CombatWorld w) {
        int t = d.target();
        if (t < 0) {
            lastTargeted = NONE;
            told = -1;
            return;
        }
        if (t != told || lastTargeted == NONE || n - lastTargeted >= p.senses().targetedEveryTicks()) {
            w.targeted(t);
            lastTargeted = n;
            told = t;
        }
    }

    /** How long before its impact a string's first strike commits: max(windup_min, cue lead + margin) (Rule 7.11). */
    private int margin(FrameData f) {
        FrameData.BeatLock lock = f.beatLock();
        int windupMin = lock == null ? f.windup() : lock.windupMin();
        return Math.max(windupMin, f.cue().lead() + strikes.commitMarginTicks());
    }

    /** Its target's beat, in ticks, around its next string's impact. */
    private int beatTicks(Fighter t) {
        PulseSchedule pulse = t.pulse();
        long k = beatAtOrAfter(pulse, impactMicros);
        return (int) Micros.tickOf(pulse.beatMicros(k) - pulse.beatMicros(k - 1));
    }

    /** The first beat of {@code pulse} it can commit for exactly {@code margin} ticks ahead, at tick {@code from} or later. */
    private static long committable(PulseSchedule pulse, long from, int margin) {
        long k = beatAtOrAfter(pulse, Micros.ofTicks(from + margin));
        while (Micros.tickOf(pulse.beatMicros(k)) - margin < from) {
            k++;
        }
        return pulse.beatMicros(k);
    }

    /** The first beat of {@code pulse} at or after {@code micros}. */
    private static long beatAtOrAfter(PulseSchedule pulse, long micros) {
        long k = pulse.nearestBeat(micros);
        return pulse.beatMicros(k) < micros ? k + 1 : k;
    }
}
