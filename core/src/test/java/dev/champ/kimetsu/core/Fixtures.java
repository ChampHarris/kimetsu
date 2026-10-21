package dev.champ.kimetsu.core;

import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cancel;
import dev.champ.kimetsu.core.data.FrameData.Into;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.Style;
import dev.champ.kimetsu.core.data.TimingRules;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The spec's PROPOSED values (docs/design/specs/m1-combat-core.md, Numbers), each named as its Numbers row. This is
 * the only place they live in Java: the mod loads them from datapack JSON, and the {@code m1_data_loaded} GameTest
 * compares what it loads against these.
 */
public final class Fixtures {
    private Fixtures() {}

    public static TimingRules timing() {
        return new TimingRules(
                120, // On-beat window
                180, // On-beat window, assist
                150, // Deflect window
                60, // Perfect window
                1.5, // Assist window scale
                1, // Credits per beat
                5, // Whiff lockout (ticks)
                3.0, // Stamp tolerance: 3 × jitter
                20, 80, // Stamp tolerance: clamped to 20–80 ms
                40, // Stamp tolerance: jitter over 40 frames
                300, // Stall bracket
                300, // Hold cap: RTT/2 + tolerance + 300
                100, 450, // Hold cap: clamped to 100–450 ms
                true, // Stamp correction: half a frame
                100, // Stamp correction: frames longer than 100 ms count as 100
                10_000); // RTT: minimum over 10 s
    }

    public static ClockRules clock() {
        return new ClockRules(
                16, // Clock sync samples
                4, // Clock sync samples: the first fix waits for 4 (the join burst)
                10, // Clock slew limit, ms per beat
                40, // Clock slew limit after a rate change
                4, // Clock rate: records averaged
                0.01, // Clock rate change: 1%
                4.0, 4, // Clock record push: 4 a second, a burst of 4
                2, // Tempo change: 2 beats ahead
                1000, // Clock sync interval in combat
                5000, // Clock sync interval otherwise
                10, // Tick length (T̄): the last 10 ordinary intervals
                3, // Tick length (T̄): 3 agreeing intervals outside the band are a new rate
                0.2, // Tick length (T̄): late = more than a fifth of a tick behind vanilla's schedule
                0.5, 1.5, // Tick length (T̄): ordinary = 0.5–1.5 × T̄
                0.5); // Tick length (T̄): steered halfway
    }

    public static BreathRules breath() {
        return new BreathRules(
                100, // Breath
                10, 4, // Breath regeneration: out of combat, in combat
                6, // On-beat Inhale
                6, // Held guard drain
                40, // Winded (ticks)
                6, // Winded guard: 6 Posture/s
                1.0); // Recovery Breath refund
    }

    public static PostureRules playerPosture() {
        return new PostureRules(
                100, 12, // Player Posture: 100, recovers 12/s
                30, // ... after 1.5 s
                4, // On-beat Posture bonus
                30, 0.5, // Stagger: 30 ticks, resets to 50%
                1.0); // no vitality coupling
    }

    public static PostureRules demonPosture() {
        return new PostureRules(
                300, 12, // M1 demon Posture: 300, recovers 12/s
                60, // ... after 3 s (D-028)
                0, 30, 0.5, // Stagger
                0.25); // Vitality coupling floor
    }

    public static RhythmRules rhythm() {
        return new RhythmRules(
                4,
                5, // Rhythm per tier: 4 points, tiers 0–5
                List.of(1.00, 1.10, 1.20, 1.32, 1.45, 1.60), // Rhythm multipliers
                1,
                1,
                2,
                1,
                -1,
                -4, // Rhythm changes: credit, deflect, Perfect, dodge, off-beat, hit
                60, // Rhythm decay: after 3 s idle
                3); // Mushin spend: to tier 3
    }

    public static HungerRules hunger() {
        return new HungerRules(
                100, // Hunger
                1, // 1 per HP
                5, // the leap
                25, // rising from collapse
                0.25); // Cellular Hardening above 25%
    }

    public static MachineRules machine() {
        return new MachineRules(
                3, // Input buffer: 3 ticks
                150, // Radial hold threshold: 150 ms
                2, // Radial limit: 2 beats
                1, // GUARD: guard held 1 tick
                10, // Feint: 10 Breath
                60, // Feint: doubling within 3 s
                6, // Chain stumble: +6 recovery ticks
                4, // Tech window: 4 ticks before contact
                1, // ... to 1 tick after
                13, // Tech lockout: 13 ticks after a missed Jump
                20, // Knockdown: 20 ticks down
                8, // ... and 8 to rise
                10, // Juggle cut: 10 Breath
                3, // Grab: 3 on-beat inputs to escape
                16); // State history: 16 ticks
    }

    // Frame data: the Numbers rows "Light Chain frames" (3 / 2 / 6), "Pommel Strike" (2 / 2 / 8), "Step" (3 blocks over
    // 6 ticks), "Water's Step cost" (7.5), "Form frames" and "Form costs". A Feint fits the first half of each windup.
    private static final List<Cancel> STEP_CANCEL = List.of(new Cancel(Into.STEP, Phase.RECOVERY, 0, -1));

    public static FrameData chainHit(int n, boolean continues) {
        List<Cancel> cancels = continues
                ? List.of(new Cancel(Into.LIGHT_CHAIN, Phase.ACTIVE, 1, -1), STEP_CANCEL.get(0))
                : STEP_CANCEL;
        return new FrameData(
                "kimetsu:light_chain/" + n,
                AttackClass.LIGHT,
                3,
                2,
                6,
                0,
                FrameData.Judge.PRESS,
                cancels,
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null);
    }

    public static FrameData pommel() {
        return new FrameData(
                "kimetsu:pommel",
                AttackClass.POMMEL,
                2,
                2,
                8,
                0,
                FrameData.Judge.PRESS,
                STEP_CANCEL,
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null);
    }

    public static FrameData step() {
        return new FrameData(
                "kimetsu:water/step",
                AttackClass.STEP,
                0,
                6,
                0,
                7.5,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null);
    }

    /** A Water form: heavy, judged at the release, light super armour in its active frames, a Recovery Breath. */
    public static FrameData form(
            String name,
            int windup,
            int active,
            int recovery,
            double cost,
            int feintUntil,
            FrameData.@Nullable Contextual contextual,
            @Nullable String mushin) {
        return new FrameData(
                "kimetsu:water/" + name,
                AttackClass.HEAVY,
                windup,
                active,
                recovery,
                cost,
                FrameData.Judge.RELEASE,
                List.of(new Cancel(Into.FEINT, Phase.WINDUP, 0, feintUntil), STEP_CANCEL.get(0)),
                Armour.NONE,
                Armour.LIGHT,
                true,
                contextual,
                mushin);
    }

    /**
     * Water in M1: the four radial forms (1st up, 2nd right, 4th down, 8th left), Waterfall Basin (the 8th) while
     * falling 2 blocks, and the guard-cancel at 0 / 5 Breath. The spec has no frame timings for the Mushin finishers
     * yet (an open number for slice 5's data); until then these test finishers reuse their base form's timing.
     */
    public static Moveset water() {
        List<FrameData> frames = List.of(
                chainHit(1, true),
                chainHit(2, true),
                chainHit(3, false),
                pommel(),
                step(),
                form("first_form", 6, 3, 17, 20, 3, null, "kimetsu:water/first_form_mushin"),
                form("first_form_mushin", 6, 3, 17, 20, 3, null, null),
                form("second_form", 7, 4, 15, 25, 3, null, null),
                form("fourth_form", 6, 12, 21, 30, 3, null, "kimetsu:water/fourth_form_mushin"),
                form("fourth_form_mushin", 6, 12, 21, 30, 3, null, null),
                form("eighth_form", 8, 3, 15, 25, 4, new FrameData.Contextual(2.0), null));
        Style style = new Style(
                "kimetsu:water",
                13, // Water beat: 13 ticks
                "kimetsu:water/step",
                List.of("kimetsu:light_chain/1", "kimetsu:light_chain/2", "kimetsu:light_chain/3"),
                List.of(
                        "kimetsu:water/first_form",
                        "kimetsu:water/second_form",
                        "kimetsu:water/fourth_form",
                        "kimetsu:water/eighth_form"),
                "kimetsu:water/eighth_form",
                new Style.GuardCancel(0, 5)); // Water guard-cancel: free after a miss, 5 Breath after a hit
        return new Moveset(style, frames, "kimetsu:pommel");
    }
}
