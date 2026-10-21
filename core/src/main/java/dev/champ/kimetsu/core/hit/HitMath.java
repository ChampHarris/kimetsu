package dev.champ.kimetsu.core.hit;

import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.Moveset;

/**
 * What a landed hit deals, as the duel sim reckons it: the window's damage and Posture × the attacker's Rhythm
 * multiplier at the hit (Rule 4.3), × the ×2.0 stand-in for a form fired at Mushin without a finisher (Rule 5.3:
 * damage only), × 0.75 at assist level 3 (Rule 2.12: damage and Posture damage dealt). A Mushin finisher pays its
 * Mushin at its first active frame (D-038), so its hits already read tier 3's multiplier.
 */
public final class HitMath {
    private HitMath() {}

    /** HP dealt. */
    public static double damage(
            HitWindow w, double rhythmMultiplier, boolean standIn, int assistLevel, HitRules rules) {
        double d = w.damage() * rhythmMultiplier;
        if (standIn) {
            d *= rules.mushinStandIn();
        }
        return assistLevel >= 3 ? d * rules.autoRhythmScale() : d;
    }

    /** Posture damage dealt. */
    public static double posture(HitWindow w, double rhythmMultiplier, int assistLevel, HitRules rules) {
        double p = w.posture() * rhythmMultiplier;
        return assistLevel >= 3 ? p * rules.autoRhythmScale() : p;
    }

    /** Whether attack {@code f}, fired at Mushin or not, deals the stand-in's damage: a form that fired itself. */
    public static boolean standIn(FrameData f, boolean firedAtMushin, Moveset moves) {
        return firedAtMushin && !moves.isFinisher(f);
    }
}
