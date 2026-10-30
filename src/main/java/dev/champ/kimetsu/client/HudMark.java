package dev.champ.kimetsu.client;

import dev.champ.kimetsu.client.present.Meters;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The combat HUD as it's drawn now (spec:582; D-141): the Pulse ring and its notch, the Rhythm pips, Breath with its
 * grey segment and Water's crest, your Posture, the locked target's panel, the winded tint and the contextual form's
 * preview. Meters are shares of their bars. One reused instance, filled each frame by {@link ClientCombat#hud}; the
 * layers only draw it.
 */
public final class HudMark {
    /** The fade on the ring, the pips, Breath, your Posture and the preview (0: hidden; D-123's fade). */
    public float alpha;

    /** The closing ring's radius now, in GUI px at HUD scale 1 ({@code PulseRing}). */
    public float ring;

    /** Whether the impact notch shows in the closing ring (assist level 1 or higher). */
    public boolean notch;

    /** Rhythm's tier, 0 to 5: that many pips lit; at 5, Mushin. */
    public int tier;

    /** Breath and its grey Recovery segment. */
    public final Meters.Bar breath = new Meters.Bar();

    /** The style's crest at the Breath arc's centre, or null when the style has none drawn. */
    public @Nullable Identifier crest;

    /** Whether your Posture shows: below its max (spec:582's "when damaged"). */
    public boolean posture;

    public final Meters.Bar ownPosture = new Meters.Bar();

    /** Whether the locked target's panel shows (never faded). */
    public boolean target;

    public String targetName = "";
    public final Meters.Bar targetHp = new Meters.Bar();

    /** The target's Posture, with the guard counter's stake as its grey segment (Rule 11.4). */
    public final Meters.Bar targetPosture = new Meters.Bar();

    /** The winded tint's share of its full strength (never faded by the HUD's fade). */
    public float tint;

    /** The contextual form's icon while it would fire, or null. */
    public @Nullable Identifier preview;

    // the frame it was filled in, and what the fill answered: the layers share one, filled by the first to draw
    long frame = Long.MIN_VALUE;
    boolean shown;
}
