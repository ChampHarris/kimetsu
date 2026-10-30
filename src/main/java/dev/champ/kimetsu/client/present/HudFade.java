package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.fight.PlayerIntake;

/**
 * The combat HUD's fade (Champ's call in D-123, D-141's call 8): it shows while you're fighting (in combat by Rule
 * 13.1, or a hostile demon within the engagement range) or within {@code fade_after_ms} of a combat press; then it
 * fades out over {@code fade_out_ms}, from the later of the last tick you were fighting and that press's
 * {@code fade_after_ms}; it comes back over {@code fade_in_ms} (0: at once). It fades the ring, the pips, Breath, your
 * Posture and the preview; the radial, the winded tint and the target panel keep their own rules. Times are the combat
 * clock's µs. Main thread only.
 */
public final class HudFade {
    /** No press yet, or never fighting. */
    public static final long NONE = Long.MIN_VALUE;

    private final Ramp in = new Ramp();

    /** Whether a press of {@code k} counts as a combat press: every combat key but Jump and Sneak, which are vanilla's. */
    public static boolean counts(PlayerIntake.Key k) {
        return k != PlayerIntake.Key.JUMP && k != PlayerIntake.Key.SNEAK;
    }

    /**
     * How shown the HUD is at {@code now}: 1 while {@code fighting} or within {@code fade_after_ms} of
     * {@code lastPress}, then falling to 0 over {@code fade_out_ms} from the later of {@code lastFighting} (the last tick
     * you were) and the press's end.
     */
    public static float visibility(long now, boolean fighting, long lastFighting, long lastPress, Feel.Hud h) {
        if (fighting) {
            return 1f;
        }
        long pressEnd = lastPress == NONE ? NONE : lastPress + Micros.ofMillis(h.fadeAfterMs());
        long from = Math.max(lastFighting, pressEnd);
        if (from == NONE) {
            return 0f;
        }
        if (now <= from) {
            return 1f;
        }
        long out = Micros.ofMillis(h.fadeOutMs());
        return out <= 0 || now - from >= out ? 0f : 1f - (now - from) / (float) out;
    }

    /** What it's drawn at, at {@code now}: its {@link #visibility}, risen no faster than {@code fade_in_ms} allows. */
    public float alpha(long now, boolean fighting, long lastFighting, long lastPress, Feel.Hud h) {
        return in.toward(visibility(now, fighting, lastFighting, lastPress, h), now, Micros.ofMillis(h.fadeInMs()), 0);
    }
}
