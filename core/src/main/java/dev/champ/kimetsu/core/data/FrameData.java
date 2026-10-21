package dev.champ.kimetsu.core.data;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One attack's (or Step's) timing file, the part the state machine reads (Data & State, frame data schema 1). The hit
 * windows, the sweep and the displacement are the hit system's (slice 3) and join this record then.
 *
 * <p>Ticks are frames (1 tick = 50 ms). Breath costs are in points; the meter turns them into fixed point. Validation
 * names the file and the field, so a bad datapack entry fails where the mod decodes it.
 *
 * @param id the frame data's id, also the start of every validation message
 * @param attackClass what it is: which hits it absorbs and whether it can clash
 * @param windup ticks before the first active tick
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
        @Nullable String mushin) {

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
                    contextual.minFallBlocks() > 0,
                    at + "contextual.min_fall_blocks must be positive, was " + contextual.minFallBlocks());
        }
        if (mushin != null) {
            Check.notBlank(at + "mushin", mushin);
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

    private static int phaseLength(Phase phase, int windup, int active, int recovery) {
        return switch (phase) {
            case WINDUP -> windup;
            case ACTIVE -> active;
            case RECOVERY -> recovery;
        };
    }
}
