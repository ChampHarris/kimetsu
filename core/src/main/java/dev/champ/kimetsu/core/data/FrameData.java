package dev.champ.kimetsu.core.data;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One attack's (or Step's) timing file (Data & State, frame data schema 1): what the state machine reads, and what the
 * hit system and the strike scheduler read (its hit windows, its sweep, its cue, its beat lock and its displacement).
 *
 * <p>Ticks are frames (1 tick = 50 ms); distances are blocks. Breath costs and Posture are in points; the meters turn
 * them into fixed point. Validation names the file and the field, so a bad datapack entry fails where the mod decodes
 * it.
 *
 * @param id the frame data's id, also the start of every validation message
 * @param attackClass what it is: which hits it absorbs and whether it can clash
 * @param windup ticks before the first active tick (a beat-locked strike's windup when it opens a string, Rule 7.11)
 * @param active ticks whose hit windows sweep (a Step's are its dash)
 * @param recovery ticks after the active frames
 * @param breathCost Breath spent when it starts (a form's shows as the grey bar until its recovery ends)
 * @param judge whether its timing is the press's or the radial release's
 * @param cancels the windows in which other inputs cancel it (Rule 3, transitions 2, 3 and 6)
 * @param windupArmour hits its windup shrugs off
 * @param activeArmour hits its active frames shrug off (a form's: light hits, Rule 3.9)
 * @param recoveryBreath whether an on-beat Inhale in its recovery is a Recovery Breath (Rule 3.5)
 * @param contextual the state that fires it from an attack press instead of the radial, if any
 * @param mushin the frame data fired instead at Mushin, if it has a finisher (Rule 5.3); a form without one fires
 *     itself with the stand-in's damage
 * @param cue its telegraph (an NPC strike's; a player's attacks have none)
 * @param beatLock how an NPC strike locks to its target's beat (Rule 7.11), or none
 * @param hits its hit windows, relative to the first active tick (Rule 7.4); a Step has none
 * @param sweep the shape its hit windows sweep (Rule 7.1); a Step has none
 * @param displacement the attacker's own motion while it runs, per tick (R8-02)
 */
public record FrameData(
        String id,
        AttackClass attackClass,
        int windup,
        int active,
        int recovery,
        double breathCost,
        Judge judge,
        List<Cancel> cancels,
        Armour windupArmour,
        Armour activeArmour,
        boolean recoveryBreath,
        @Nullable Contextual contextual,
        @Nullable String mushin,
        Cue cue,
        @Nullable BeatLock beatLock,
        List<HitWindow> hits,
        @Nullable Sweep sweep,
        List<Displacement> displacement) {

    /** Which moment judges the input on the beat: the attack press, or the radial's release (forms). */
    public enum Judge {
        PRESS,
        RELEASE
    }

    /** What an input cancels the attack into. */
    public enum Into {
        /** A guard press: the windup is cancelled (Rule 3.3). */
        FEINT,
        /** A Step press (Rule 3.6). */
        STEP,
        /** An attack press continues the Light Chain or stumbles (Rule 3.2). */
        LIGHT_CHAIN
    }

    /**
     * A cancel window: within {@code phase}, from tick {@code from} of that phase up to tick {@code until}, exclusive.
     * {@code until} −1 runs to the phase's end.
     */
    public record Cancel(Into into, Phase phase, int from, int until) {}

    /** Fires from an attack press while falling at least {@code minFallBlocks} above the ground (Rule 5.5). */
    public record Contextual(double minFallBlocks) {}

    /**
     * The telegraph (One telegraph language): its colour, shown {@code lead} ticks before the impact. Only a red
     * strike is {@code charged} (a Pommel Strike can interrupt it, Rule 5.2), and only a grab is purple.
     */
    public record Cue(Type type, int lead, boolean charged) {
        public static final Cue NONE = new Cue(Type.NONE, 0, false);

        public enum Type {
            NONE,
            /** Deflect (or block with held guard). */
            WHITE,
            /** Dodge, or Pommel a charged windup. */
            RED,
            /** A grab: spacing, a Step, or a Pommel during its windup. */
            PURPLE
        }
    }

    /**
     * An NPC strike's beat lock (Rule 7.11; {@code timing.beat_lock}). A string's first strike winds up for the frame
     * data's {@code windup}, which must be the larger of {@code windupMin} and its cue lead: the glint shows as the
     * windup starts, never on a demon still circling, and never long after. A later strike winds up for the beat minus
     * the previous strike's active ticks, at least {@code windupMin}. The animation holds frame {@code holdFrame} to
     * fill a longer windup, by at most {@code maxStretchBeats} beats. A guard counter's {@code holdGuards} keeps the
     * guard stance up until its impact.
     */
    public record BeatLock(Mode mode, int windupMin, int maxStretchBeats, int holdFrame, boolean holdGuards) {
        public enum Mode {
            /** The target's own beat. */
            TARGET,
            /** A Domain's shared beat (M4). */
            DOMAIN
        }
    }

    /**
     * A hit window (frame data's {@code hits}): active ticks {@code from} to {@code to}, both included, counted from the
     * first active tick. Each hits each target once (Rule 7.4).
     *
     * @param damage HP, before the Rhythm multiplier and the other scales
     * @param posture Posture damage, likewise
     * @param hitstun the stun before combo scaling (Rule 7.5)
     * @param knockback the push along the attacker's facing, in blocks (Rule 7.10)
     * @param launch whether it launches into a KNOCKDOWN
     * @param wallSplat whether a push that carries the target into a wall splats it (Rule 9.4)
     * @param horizontal whether it's a level cut that can take a head (Rule 8.1; Slayer forms only)
     * @param blockPosture what a held guard pays to block it (Rule 6.1; NPC strikes)
     * @param grabDrain the Breath a connected grab drains per second (Rule 6.3; grabs only)
     */
    public record HitWindow(
            int from,
            int to,
            double damage,
            double posture,
            int hitstun,
            double knockback,
            boolean launch,
            boolean wallSplat,
            boolean horizontal,
            double blockPosture,
            double grabDrain) {}

    /**
     * The shape the hit windows sweep (Rule 7.1): an oriented box in the attacker's frame (x right, y up, z forward,
     * from its feet), or a baked blade arc thickened by {@code radius}, with the box as its envelope (R6-08). The
     * pitch turns it about the eyes.
     */
    public record Sweep(
            Shape shape,
            Box box,
            Pitch pitch,
            double radius,
            @Nullable String arc) {
        public enum Shape {
            OBB,
            ARC
        }

        /** How the attacker's look pitch tilts it: not at all, fully, or clamped (a horizontal cut stays level). */
        public enum Pitch {
            LEVEL,
            LOOK,
            LOOK_CLAMPED
        }

        /** A box by its centre and half extents, in blocks, in the attacker's frame. */
        public record Box(double cx, double cy, double cz, double hx, double hy, double hz) {}
    }

    /**
     * The attacker's own motion (R8-02): during ticks {@code from} to {@code to} (both included) of {@code phase},
     * this many blocks per tick along its facing ({@code forward}), its right and up.
     */
    public record Displacement(Phase phase, int from, int to, double forward, double right, double up) {}

    public FrameData {
        Check.notBlank("frame data id", id);
        String at = id + ": ";
        Check.atLeast(at + "timing.windup", windup, 0);
        Check.positive(at + "timing.active", active);
        Check.atLeast(at + "timing.recovery", recovery, 0);
        Check.atLeast(at + "cost.breath", breathCost, 0.0);
        cancels = List.copyOf(cancels);
        for (Cancel c : cancels) {
            int length = phaseLength(c.phase(), windup, active, recovery);
            String field = at + "cancels." + c.into().name().toLowerCase(Locale.ROOT);
            Check.atLeast(field + ".from", c.from(), 0);
            Check.that(c.from() < length, field + ".from must lie inside its " + c.phase() + " phase, was " + c.from());
            Check.that(
                    c.until() == -1 || (c.until() > c.from() && c.until() <= length),
                    field + ".until must be −1 or after from and inside its phase, was " + c.until());
            Check.that(
                    c.into() != Into.FEINT || c.phase() == Phase.WINDUP,
                    field + " can only cancel a windup (Rule 3.3)");
            Check.that(
                    c.into() != Into.LIGHT_CHAIN || c.phase() != Phase.WINDUP,
                    field + " opens after the hit's active frames start (Rule 3.2)");
        }
        if (contextual != null) {
            Check.that(
                    contextual.minFallBlocks() > 0 && Double.isFinite(contextual.minFallBlocks()),
                    at + "contextual.min_fall_blocks must be positive and finite, was " + contextual.minFallBlocks());
        }
        if (mushin != null) {
            Check.notBlank(at + "mushin", mushin);
        }
        hits = List.copyOf(hits);
        displacement = List.copyOf(displacement);
        checkCue(at, attackClass, windup, cue);
        checkBeatLock(at, windup, cue, beatLock, hits);
        checkHits(at, attackClass, active, judge, hits, sweep);
        checkSweep(at, sweep);
        checkDisplacement(at, windup, active, recovery, displacement, beatLock);
    }

    private static void checkCue(String at, AttackClass attackClass, int windup, Cue cue) {
        if (cue.type() == Cue.Type.NONE) {
            Check.that(cue.lead() == 0, at + "cue.lead must be 0 with no cue, was " + cue.lead());
        } else {
            Check.positive(at + "cue.lead", cue.lead());
            Check.atMost(at + "cue.lead", cue.lead(), windup); // the glint shows during the windup, never before it
            Check.that(
                    (cue.type() == Cue.Type.PURPLE) == (attackClass == AttackClass.GRAB),
                    at + "cue.type must be purple exactly for a grab, was " + cue.type() + " for " + attackClass);
        }
        Check.that(!cue.charged() || cue.type() == Cue.Type.RED, at + "cue.charged is only for a red cue");
    }

    private static void checkBeatLock(String at, int windup, Cue cue, @Nullable BeatLock lock, List<HitWindow> hits) {
        if (lock == null) {
            return;
        }
        String field = at + "timing.beat_lock.";
        Check.that(cue.type() != Cue.Type.NONE, at + "timing.beat_lock needs a cue: a beat-locked strike is an NPC's");
        Check.positive(field + "windup_min", lock.windupMin());
        Check.that(
                windup == Math.max(lock.windupMin(), cue.lead()),
                at + "timing.windup must be the larger of windup_min (" + lock.windupMin() + ") and cue.lead ("
                        + cue.lead() + ") on a beat-locked strike, was " + windup);
        Check.atLeast(field + "max_stretch_beats", lock.maxStretchBeats(), 0);
        Check.atLeast(field + "hold_frame", lock.holdFrame(), 0);
        Check.that(
                lock.holdFrame() < lock.windupMin(),
                field + "hold_frame must come before windup_min (" + lock.windupMin() + "), was " + lock.holdFrame());
        Check.that(
                !lock.holdGuards() || cue.type() == Cue.Type.WHITE,
                field + "hold_guards is a guard counter's, which is white");
        Check.that(
                hits.size() == 1 && hits.get(0).from() == 0,
                at + "hits must be one window from the first active tick on a beat-locked strike (Rule 2.10)");
    }

    private static void checkHits(
            String at, AttackClass attackClass, int active, Judge judge, List<HitWindow> hits, @Nullable Sweep sweep) {
        if (attackClass == AttackClass.STEP) {
            Check.that(hits.isEmpty() && sweep == null, at + "a Step has no hits and no sweep");
            return;
        }
        Check.that(!hits.isEmpty(), at + "hits must list at least one window");
        Check.that(sweep != null, at + "sweep is missing");
        int after = -1;
        for (int i = 0; i < hits.size(); i++) {
            HitWindow h = hits.get(i);
            String field = at + "hits[" + i + "].";
            Check.atLeast(field + "from", h.from(), 0);
            Check.that(h.from() > after, field + "from must come after the previous window, was " + h.from());
            Check.that(
                    h.to() >= h.from() && h.to() < active,
                    field + "to must lie in from.." + (active - 1) + ", was " + h.to());
            Check.atLeast(field + "damage", h.damage(), 0.0);
            Check.atLeast(field + "posture", h.posture(), 0.0);
            Check.atLeast(field + "hitstun", h.hitstun(), 0);
            Check.atLeast(field + "knockback", h.knockback(), 0.0);
            Check.atLeast(field + "block_posture", h.blockPosture(), 0.0);
            Check.atLeast(field + "grab_drain", h.grabDrain(), 0.0);
            Check.that(
                    !h.wallSplat() || h.launch() || h.knockback() > 0,
                    field + "wall_splat needs a launch or knockback to carry the target into the wall");
            Check.that(
                    (h.grabDrain() > 0) == (attackClass == AttackClass.GRAB),
                    field + "grab_drain is a grab's, and every grab drains");
            if (h.horizontal()) {
                Check.that(judge == Judge.RELEASE, field + "horizontal is only on Slayer forms (Rule 8.1)");
                Check.that(
                        sweep == null || sweep.pitch() != Sweep.Pitch.LOOK,
                        field + "horizontal needs a level or clamped sweep pitch (Rule 7.1)");
            }
            after = h.to();
        }
    }

    private static void checkSweep(String at, @Nullable Sweep sweep) {
        if (sweep == null) {
            return;
        }
        Sweep.Box b = sweep.box();
        Check.that(
                b.hx() > 0 && b.hy() > 0 && b.hz() > 0 && Double.isFinite(b.hx() + b.hy() + b.hz()),
                at + "sweep.half must be positive and finite on every axis");
        Check.that(
                Double.isFinite(b.cx()) && Double.isFinite(b.cy()) && Double.isFinite(b.cz()),
                at + "sweep.center must be finite");
        if (sweep.shape() == Sweep.Shape.ARC) {
            Check.that(
                    sweep.radius() > 0 && Double.isFinite(sweep.radius()),
                    at + "sweep.radius must be positive and finite for an arc, was " + sweep.radius());
            String arc = sweep.arc();
            Check.that(arc != null && !arc.isBlank(), at + "sweep.arc must name the baked arc");
        } else {
            Check.that(sweep.radius() == 0, at + "sweep.radius is an arc's, was " + sweep.radius());
            Check.that(sweep.arc() == null, at + "sweep.arc is an arc's");
        }
    }

    private static void checkDisplacement(
            String at, int windup, int active, int recovery, List<Displacement> moves, @Nullable BeatLock lock) {
        for (int i = 0; i < moves.size(); i++) {
            Displacement d = moves.get(i);
            String field = at + "displacement[" + i + "].";
            int length = phaseLength(d.phase(), windup, active, recovery);
            Check.atLeast(field + "from", d.from(), 0);
            Check.that(
                    d.to() >= d.from() && d.to() < length,
                    field + "to must lie in from.." + (length - 1) + " of its " + d.phase() + " phase, was " + d.to());
            Check.that(
                    Double.isFinite(d.forward()) && Double.isFinite(d.right()) && Double.isFinite(d.up()),
                    field + "must be finite");
            for (int j = 0; j < i; j++) {
                Displacement e = moves.get(j);
                Check.that(
                        e.phase() != d.phase() || d.from() > e.to() || d.to() < e.from(),
                        field + "overlaps displacement[" + j + "]");
            }
            if (lock != null && d.phase() == Phase.WINDUP) {
                Check.that(
                        d.from() > lock.holdFrame(),
                        field + "from must come after the hold frame (" + lock.holdFrame() + "): a beat-locked windup"
                                + " moves only once its hold is over");
            }
        }
    }

    /** Windup, active and recovery ticks together. */
    public int total() {
        return windup + active + recovery;
    }

    /** The length of {@code phase} in ticks. */
    public int length(Phase phase) {
        return phaseLength(phase, windup, active, recovery);
    }

    /** Whether an input cancels into {@code into} at tick {@code tickInPhase} of {@code phase}. */
    public boolean cancels(Into into, Phase phase, int tickInPhase) {
        for (Cancel c : cancels) {
            if (c.into() == into
                    && c.phase() == phase
                    && tickInPhase >= c.from()
                    && (c.until() == -1 || tickInPhase < c.until())) {
                return true;
            }
        }
        return false;
    }

    /** The first tick, counted from the attack's start with no windup hold, at which {@code into} opens; −1 if never. */
    public int opens(Into into) {
        int first = -1;
        for (Cancel c : cancels) {
            if (c.into() == into) {
                int at = start(c.phase()) + c.from();
                first = first == -1 ? at : Math.min(first, at);
            }
        }
        return first;
    }

    /** The tick, counted from the attack's start with no windup hold, at which {@code phase} begins. */
    public int start(Phase phase) {
        return switch (phase) {
            case WINDUP -> 0;
            case ACTIVE -> windup;
            case RECOVERY -> windup + active;
        };
    }

    /** The hit window open at active frame {@code activeFrame}, as an index into {@link #hits}, or −1. */
    public int windowAt(int activeFrame) {
        for (int i = 0; i < hits.size(); i++) {
            HitWindow h = hits.get(i);
            if (activeFrame >= h.from() && activeFrame <= h.to()) {
                return i;
            }
        }
        return -1;
    }

    private static int phaseLength(Phase phase, int windup, int active, int recovery) {
        return switch (phase) {
            case WINDUP -> windup;
            case ACTIVE -> active;
            case RECOVERY -> recovery;
        };
    }
}
