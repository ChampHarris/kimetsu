package dev.champ.kimetsu.core.data;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Breathing Style (Data & State, {@code style/}): its beat, its Step, its Light Chain, the forms on its radial and the
 * one that fires while falling, and whether its swings cancel into guard. Ids point at frame data; {@link Moveset}
 * checks they exist.
 *
 * @param beatTicks the Pulse's beat in whole ticks (Water: 13, R9-01)
 * @param radial the radial's slots in order (M1: up, right, down, left)
 * @param fallingForm the form an attack press fires while falling, if any (Water: Waterfall Basin)
 * @param guardCancel Water's guard-cancel and its Breath costs (R9-09), or none for a style without it
 */
public record Style(
        String id,
        int beatTicks,
        String step,
        List<String> lightChain,
        List<String> radial,
        @Nullable String fallingForm,
        @Nullable GuardCancel guardCancel) {

    /** Any swing's recovery cancels into guard from its first recovery tick: free after a miss (Rule 3.4). */
    public record GuardCancel(double afterMiss, double afterHit) {
        public GuardCancel {
            Check.atLeast("guard_cancel.after_miss", afterMiss, 0.0);
            Check.atLeast("guard_cancel.after_hit", afterHit, 0.0);
        }
    }

    public Style {
        Check.notBlank("style id", id);
        String at = id + ": ";
        Check.positive(at + "beat_ticks", beatTicks);
        Check.notBlank(at + "step", step);
        lightChain = List.copyOf(lightChain);
        radial = List.copyOf(radial);
        Check.that(!lightChain.isEmpty(), at + "light_chain must name at least one hit");
        Check.that(!radial.isEmpty() && radial.size() <= 8, at + "radial must have 1 to 8 slots, had " + radial.size());
    }
}
