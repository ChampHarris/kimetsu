package dev.champ.kimetsu.data;

import dev.champ.kimetsu.core.data.DemonProfile;

/** A combatant profile file ({@code data/<ns>/kimetsu/combatant/}), by its {@code kind}. */
public sealed interface Profile permits SlayerProfile, Profile.Demon {
    String SLAYER = "slayer";
    String DEMON = "demon";

    String id();

    String kind();

    /**
     * A demon's profile: {@code core/}'s {@link DemonProfile}, and how its entity walks between strings, which the
     * engine never reads ({@code movement}).
     */
    record Demon(DemonProfile profile, Movement movement) implements Profile {
        @Override
        public String id() {
            return profile.id();
        }

        @Override
        public String kind() {
            return DEMON;
        }
    }

    /**
     * How a demon's entity moves while no strike carries it (Rule 11.2's senses and movement): it walks at
     * {@code walkSpeed} (its movement speed attribute) until its target is between {@code closeMin} and
     * {@code closeMax} blocks away, and circles there at {@code circleShare} of its walking speed.
     */
    record Movement(double walkSpeed, double circleShare, double closeMin, double closeMax) {
        public Movement {
            if (!(walkSpeed > 0)) {
                throw new IllegalArgumentException("movement.walk_speed must be positive, was " + walkSpeed);
            }
            if (!(circleShare > 0 && circleShare <= 1)) {
                throw new IllegalArgumentException("movement.circle_share must be in (0, 1], was " + circleShare);
            }
            if (!(closeMin >= 0 && closeMax >= closeMin)) {
                throw new IllegalArgumentException("movement.close_min must be at least 0 and close_max at least"
                        + " close_min, were " + closeMin + " and " + closeMax);
            }
        }
    }
}
