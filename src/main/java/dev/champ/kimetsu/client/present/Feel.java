package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.Micros;

/**
 * The presentation's timings, from {@code assets/kimetsu/feel/default.json} (spec:438: a client resource, reloaded by
 * F3+T; {@code FeelLoader} reads it). 5d-3a's blends and 5d-3b's {@code hud}; 5d-4 and 5d-5 add the hit-stop, camera
 * and trail timings. PROPOSED until a playtest.
 *
 * @param blendBackTicks how long the body takes from its strike path's last place back to its entity's own (spec:534)
 * @param blendInTicks how long the body takes from its entity's own position onto its strike path at the path's lock,
 *     since the client's entity trails the server's by its interpolation (about 2 ticks; D-139)
 * @param poseBlendTicks how long one stand-in pose takes to blend into the next
 * @param hud the combat HUD's fade and the winded tint's ease (D-141)
 */
public record Feel(int blendBackTicks, int blendInTicks, int poseBlendTicks, Hud hud) {
    /** The longest blend: {@code ClientView} keeps a strike long enough to blend back from it. */
    public static final int MAX_BLEND_TICKS = 10;

    /** The values the file ships with, used until it loads and when it never does. */
    public static final Feel DEFAULT = new Feel(3, 3, 3, Hud.DEFAULT);

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

    /**
     * The combat HUD's timings (Champ's fade in D-123; D-141's call 12), each 0 to {@link #MAX_MS}.
     *
     * @param fadeAfterMs how long after a combat press it stays shown, out of a fight
     * @param fadeInMs how long it takes to come back (0: at once)
     * @param fadeOutMs how long it takes to fade out
     * @param tintFadeMs how long the winded tint takes to ease in or out
     */
    public record Hud(int fadeAfterMs, int fadeInMs, int fadeOutMs, int tintFadeMs) {
        public static final int MAX_MS = 10_000;

        public static final Hud DEFAULT = new Hud(3000, 0, 500, 200);

        public Hud {
            if (fadeAfterMs < 0
                    || fadeAfterMs > MAX_MS
                    || fadeInMs < 0
                    || fadeInMs > MAX_MS
                    || fadeOutMs < 0
                    || fadeOutMs > MAX_MS
                    || tintFadeMs < 0
                    || tintFadeMs > MAX_MS) {
                throw new IllegalArgumentException("feel: the hud's timings run 0 to " + MAX_MS + " ms");
            }
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
