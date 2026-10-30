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
 * @param kfx the world cues' sizes, steps and strengths (5d-4a)
 */
public record Feel(int blendBackTicks, int blendInTicks, int poseBlendTicks, Hud hud, Kfx kfx) {
    /** The longest blend: {@code ClientView} keeps a strike long enough to blend back from it. */
    public static final int MAX_BLEND_TICKS = 10;

    /** The values the file ships with, used until it loads and when it never does. */
    public static final Feel DEFAULT = new Feel(3, 3, 3, Hud.DEFAULT, Kfx.DEFAULT);

    /**
     * The world cues' knobs (slice 5d-4a; D-144's craft call 12, D-145's A7 and A12), each bounded.
     *
     * @param cueSize how many blocks a 16-texel cue cell spans (0.05 to 4)
     * @param cueMinPx the smallest a cue is drawn, in px of a 1080-line screen (0 to 256), so it reads at 16 blocks
     * @param ringFadedAlpha a grab's ring's strength outside its box (0 to 1)
     * @param flashMs how long a micro-clash's white flash shows (0 to 1000)
     * @param cueStepFps the cues' stepped cadence: the flare's one step, the shatter's and the shimmer's (1 to 60)
     * @param arcBase how far out from the near face a strike's arc puts its blade's base, of the tip's way (0 to 1);
     *     read by 5d-4b's trails, nothing in 5d-4a (the glint stands at the tip's start)
     * @param bystanderAlpha a cue neither on you nor yours: its strength (0 to 1)
     * @param bystanderScale ... and its sprite's size (0.1 to 1), never the reach or neck it marks
     * @param crosshairCue whether the 2D crosshair diamond shows too (it always does if the cue pipeline failed)
     */
    public record Kfx(
            float cueSize,
            int cueMinPx,
            float ringFadedAlpha,
            int flashMs,
            int cueStepFps,
            float arcBase,
            float bystanderAlpha,
            float bystanderScale,
            boolean crosshairCue) {
        public static final float MIN_CUE_SIZE = 0.05f;
        public static final float MAX_CUE_SIZE = 4;
        public static final int MAX_CUE_MIN_PX = 256;
        public static final int MAX_FLASH_MS = 1000;
        public static final int MAX_CUE_STEP_FPS = 60;
        public static final float MIN_BYSTANDER_SCALE = 0.1f;

        public static final Kfx DEFAULT = new Kfx(0.5f, 24, 0.35f, 100, 12, 0.45f, 0.6f, 0.75f, false);

        public Kfx {
            if (!(cueSize >= MIN_CUE_SIZE && cueSize <= MAX_CUE_SIZE)
                    || cueMinPx < 0
                    || cueMinPx > MAX_CUE_MIN_PX
                    || !unit(ringFadedAlpha)
                    || flashMs < 0
                    || flashMs > MAX_FLASH_MS
                    || cueStepFps < 1
                    || cueStepFps > MAX_CUE_STEP_FPS
                    || !unit(arcBase)
                    || !unit(bystanderAlpha)
                    || !(bystanderScale >= MIN_BYSTANDER_SCALE && bystanderScale <= 1)) {
                throw new IllegalArgumentException("feel: a kfx knob is out of its bounds");
            }
        }

        private static boolean unit(float f) {
            return f >= 0 && f <= 1; // false for NaN
        }

        /** One cue step, in µs. */
        public long cueStepMicros() {
            return Micros.ofMillis(1000) / cueStepFps;
        }

        /** The clash flash's length, in µs. */
        public long flashMicros() {
            return Micros.ofMillis(flashMs);
        }
    }

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
