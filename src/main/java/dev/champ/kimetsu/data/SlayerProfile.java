package dev.champ.kimetsu.data;

import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.PostureRules;

/**
 * A Slayer's combatant profile ({@code kimetsu:slayer}; the GameTest dummy runs one too, {@code kimetsu:test_dummy}):
 * its HP, its body's box and eyes, the style it fights in, and its Posture and Breath.
 */
public record SlayerProfile(String id, double hp, Body body, String style, PostureRules posture, BreathRules breath)
        implements Profile {

    /** Its box (half width and height) and its eyes, in blocks from the feet: vanilla's player sizes. */
    public record Body(double halfWidth, double height, double eye) {}

    @Override
    public String kind() {
        return SLAYER;
    }
}
