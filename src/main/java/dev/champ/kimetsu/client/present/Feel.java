package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.Micros;

/**
 * The presentation's timings, from {@code assets/kimetsu/feel/default.json} (spec:438: a client resource, reloaded by
 * F3+T; {@code FeelLoader} reads it). 5d-3a's keys; 5d-3b adds the HUD's, 5d-4 and 5d-5 the hit-stop, camera and trail
 * timings. PROPOSED until a playtest.
 *
 * @param blendBackTicks how long the body takes from its strike path's last place back to its entity's own (spec:534)
 * @param blendInTicks how long the body takes from its entity's own position onto its strike path at the path's lock,
 *     since the client's entity trails the server's by its interpolation (about 2 ticks; D-139)
 * @param poseBlendTicks how long one stand-in pose takes to blend into the next
 */
public record Feel(int blendBackTicks, int blendInTicks, int poseBlendTicks) {
    /** The longest blend: {@code ClientView} keeps a strike long enough to blend back from it. */
    public static final int MAX_BLEND_TICKS = 10;

    /** The values the file ships with, used until it loads and when it never does. */
    public static final Feel DEFAULT = new Feel(3, 3, 3);

    public Feel {
        if (blendBackTicks < 0
                || blendBackTicks > MAX_BLEND_TICKS
                || blendInTicks < 0
                || blendInTicks > MAX_BLEND_TICKS
                || poseBlendTicks < 0
                || poseBlendTicks > MAX_BLEND_TICKS) {
            throw new IllegalArgumentException("feel: blends run 0 to " + MAX_BLEND_TICKS + " ticks");
        }
    }

    long blendBackMicros() {
        return Micros.ofTicks(blendBackTicks);
    }

    long blendInMicros() {
        return Micros.ofTicks(blendInTicks);
    }

    long poseBlendMicros() {
        return Micros.ofTicks(poseBlendTicks);
    }
}
