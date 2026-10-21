package dev.champ.kimetsu.core.sim;

/**
 * A bot player's skill, copied from the duel sim (`tools/sims/duel_sim.py`'s `Skill` and `m1_rules.profiles`) with the
 * matched configuration's dodge aims (D-044: the centres of `dodge_windows.py` case B's Step [-600, -125] and Jump
 * [-450, -100] ms, a casual player 20 ms later), and the clash sim's reaction to the flash (`clash_sim.py`). Times are
 * in milliseconds; aims are signed against the impact (negative: before it).
 *
 * @param sigmaMs timing error SD against a predictable beat or impact
 * @param reactMuMs reaction to a new visual cue (the Thread's shimmer)
 * @param blockFallback an early, mistimed guard tap is still held at the impact, a block
 * @param misread answers a red or purple cue as if it were white
 * @param dodgeMult a dodge's timing SD is {@code sigmaMs} times this
 * @param formRate spends an opening long enough for one on a form rather than Chain hits
 * @param rbRate tries a Recovery Breath after a form
 * @param recognize notices an open Thread and goes for the neck
 * @param aim where the guard tap aims, as a share of the deflect window before the impact
 * @param assist the assist level (2: every timing window x1.5)
 * @param feintTap still presses after a feint's cue visibly breaks
 * @param chip chips its guard through a gap (Rule 11.4), decided per gap
 * @param gapForm a chip is a form, when its Breath allows one and 20 more
 * @param inhale Inhales on a gap's beat when not chipping and short of Breath
 * @param counterRead reads a guard counter coming and answers it
 * @param clashAnticipate the share of clashes it presses before the flash
 */
record Skill(
        String name,
        double sigmaMs,
        double reactMuMs,
        double reactSdMs,
        double blockFallback,
        double misread,
        double dodgeMult,
        double formRate,
        double rbRate,
        double recognize,
        double aim,
        int assist,
        double feintTap,
        double chip,
        double gapForm,
        double inhale,
        double stepAimMs,
        double jumpAimMs,
        double counterRead,
        double clashMuMs,
        double clashSdMs,
        double clashAnticipate) {

    /** A Tech's aim against the landing (duel sim `tech_aim_ms`). */
    static final double TECH_AIM_MS = -75;

    static final Skill EXPERT = new Skill(
            "expert", 20, 260, 40, 0.20, 0.02, 2.0, 0.60, 0.90, 0.98, 0.20, 0, 0.10, 0.80, 0.25, 0.90, -362.5, -275,
            0.95, 220, 30, 0.05);
    static final Skill AVERAGE = new Skill(
            "average", 35, 340, 70, 0.50, 0.08, 2.5, 0.40, 0.60, 0.85, 0.30, 0, 0.30, 0.50, 0.15, 0.60, -362.5, -275,
            0.85, 270, 40, 0.10);
    static final Skill CASUAL = new Skill(
            "casual", 60, 480, 110, 0.75, 0.12, 2.5, 0.20, 0.25, 0.65, 0.45, 0, 0.50, 0.25, 0.05, 0.30, -342.5, -255,
            0.70, 330, 60, 0.15);
    static final Skill CASUAL_ASSIST = CASUAL.on("casual+assist", 2);

    /** The same skill named {@code name}, on assist level {@code level}. */
    Skill on(String name, int level) {
        return new Skill(
                name,
                sigmaMs,
                reactMuMs,
                reactSdMs,
                blockFallback,
                misread,
                dodgeMult,
                formRate,
                rbRate,
                recognize,
                aim,
                level,
                feintTap,
                chip,
                gapForm,
                inhale,
                stepAimMs,
                jumpAimMs,
                counterRead,
                clashMuMs,
                clashSdMs,
                clashAnticipate);
    }
}
