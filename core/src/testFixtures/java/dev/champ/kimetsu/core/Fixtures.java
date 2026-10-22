package dev.champ.kimetsu.core;

import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.ClashRules;
import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.BeatLock;
import dev.champ.kimetsu.core.data.FrameData.Cancel;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.Displacement;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Into;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.data.Style;
import dev.champ.kimetsu.core.data.TimingRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    // The M1 demon's combatant profile (Rules 8, 11; Numbers "Demon", "Guard stance", "Guard counter", "Strings",
    // "Demon feints"; slice 4's PROPOSED sizes and gap calls, D-044 and D-045).
    public static final String SWIPE_ID = "kimetsu:m1_demon/swipe";
    public static final String HEAVY_ID = "kimetsu:m1_demon/heavy";
    public static final String LEAP_ID = "kimetsu:m1_demon/leap";
    public static final String SWEEP_ID = "kimetsu:m1_demon/sweep";
    public static final String GRAB_ID = "kimetsu:m1_demon/grab";
    public static final String COUNTER_ID = "kimetsu:m1_demon/guard_counter";

    public static DemonProfile demon() {
        List<String> all = List.of(SWIPE_ID, HEAVY_ID, LEAP_ID, SWEEP_ID, GRAB_ID);
        List<String> notHeavy = List.of(SWIPE_ID, LEAP_ID, SWEEP_ID, GRAB_ID);
        return new DemonProfile(
                "kimetsu:m1_demon",
                new DemonProfile.Body(DEMON_HALF_WIDTH, DEMON_HEIGHT, DEMON_EYE),
                100, // HP 100
                demonPosture(),
                hunger(),
                new DemonProfile.Regen(1.0, 0, DemonProfile.PauseOn.NICHIRIN), // 1 HP/s, no pause (R9-03)
                new DemonProfile.Neck(
                        new Sweep.Box(0, 1.5, 0, 0.18, 0.18, 0.18), // a 0.36-block box at 1.5 up (slice 3)
                        new Sweep.Box(0, 0.95, 0.15, 0.18, 0.18, 0.18)), // kneeling (PROPOSED)
                new DemonProfile.Clang(1.0, 6, 40), // ×1.0, a 6-tick recoil, at most once per 2 s
                30, // Thread: 1.5 s
                new DemonProfile.Collapse(100, 0.5, 0.25), // 5 s at 0.5 HP, rises at 25%
                0.25, // outside damage never below 25% HP
                new DemonProfile.Knockdown(20, 8, 2.0, 10), // a wall splat: 2.0 and a knockdown, within 10 ticks
                4, // a push runs over 4 ticks (PROPOSED)
                new DemonProfile.Guard(
                        120, // arc 120°
                        15, // turns 15° a tick
                        8, // a blocked Chain hit: 8 Posture
                        20, // a blocked form: 20
                        false, // blocks score nothing
                        false, // ... and don't restart the recovery delay
                        new DemonProfile.Counter(1, COUNTER_ID, true, DemonProfile.Opens.ANSWERED)),
                new DemonProfile.Strings(
                        List.of(45, 35, 20), // 1, 2, 3 strikes
                        4, // a new string 4 ± 1 beats after the last ends
                        1,
                        2,
                        Map.of(SWIPE_ID, 52, HEAVY_ID, 18, LEAP_ID, 11, SWEEP_ID, 9, GRAB_ID, 10),
                        Map.of(SWIPE_ID, all, HEAVY_ID, notHeavy, LEAP_ID, all, SWEEP_ID, all, GRAB_ID, all),
                        List.of(LEAP_ID, GRAB_ID), // they end a string
                        LEAP_ID,
                        0.08, // 8% of strings open with a feint
                        5, // ... its cue breaking 250 ms before its impact
                        1.1, // lunges stop 1.1 blocks from the target (PROPOSED)
                        3.5), // it commits with its target within 3.5 blocks
                new DemonProfile.Senses(24, 200, 20), // 24 blocks; idle after 10 s; targeted every 1 s
                12, // reels 12 ticks after a grab escape (the sim's half recovery)
                new DemonProfile.Clash(380, 60)); // ruling Q12
    }

    /** The M1 demon's attacks by id. */
    public static Map<String, FrameData> demonAttacks() {
        return Map.of(
                SWIPE_ID,
                swipe(),
                HEAVY_ID,
                heavy(),
                LEAP_ID,
                leap(),
                SWEEP_ID,
                lowSweep(),
                GRAB_ID,
                grab(),
                COUNTER_ID,
                guardCounter());
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
                40, // ... and with no landing reported, it lands 2 s after the knockdown (Champ, D-051)
                10, // Juggle cut: 10 Breath
                3, // Grab: 3 on-beat inputs to escape
                16); // State history: 16 ticks
    }

    public static HitRules hits() {
        return new HitRules(
                3, // Sub-steps: 3 per tick
                0.15, 0.25, // Combo scaling: hitstun × max(0.25, 1 − 0.15 (n − 1))
                30, // Combo scaling: chain breaks after 30 ticks unhit
                30, // Horizontal pitch clamp: ±30°
                20, // Swing turn: at most 20° a tick (slice 3)
                4, // Teleport: a move past 4 blocks a tick sweeps only its end
                1, // Involved: within reach + 1 block (Rule 2.9)
                2, // Line of sight: 2 rays per target per tick
                2.0, // Mushin stand-in: ×2.0
                0.75, // Assist level 3 damage: ×0.75
                0.70); // Arc envelope overlap: ≥ 70%
    }

    public static StrikeRules strikes() {
        return new StrikeRules(
                2, // Strikes per target per beat: 2
                8, // Min cue lead, mobs: 8 ticks
                2, // Commit margin: the strike commits at least cue lead + 2 ticks before impact
                120, // Guard arc: 120°
                4, 8, // Deflect Posture: −4 defender, −8 attacker
                0.07, // Perfect Posture damage: 7% of attacker's max × Rhythm multiplier
                8, // Perfect recovery penalty: +8 ticks
                10, // Pommel Strike: stun 10 ticks
                6, // Grab hold: thrown after 6 beats
                1.0, // ... for 1.0 damage
                clash());
    }

    public static ClashRules clash() {
        return new ClashRules(
                3, // the flash on one of the next 3 half-beats
                50, // ... at least RTT/2 + tolerance + 50 ms after ClashStart
                700, // no press 700 ms after the flash: a tie
                100, // a press within 100 ms of the flash is a false start
                15, // reactions within 15 ms tie
                300, // a player clashes at most once in 15 s
                0.5); // a win takes half the mob's max Posture (Champ, D-050)
    }

    // Bodies (blocks): vanilla's player, and the M1 demon at a zombie's size (slice 3, PROPOSED).
    public static final double PLAYER_HALF_WIDTH = 0.3;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double PLAYER_EYE = 1.62;
    public static final double DEMON_HALF_WIDTH = 0.3;
    public static final double DEMON_HEIGHT = 1.95;
    public static final double DEMON_EYE = 1.74;

    // Frame data: the Numbers rows "Light Chain frames" (3 / 2 / 6), "Pommel Strike" (2 / 2 / 8), "Step" (3 blocks over
    // 6 ticks), "Water's Step cost" (7.5), "Form frames", "Form costs", "Form damage", "Hitstun dealt", "Light Chain"
    // and "Demon attacks". A Feint fits the first half of each windup. The reach boxes and blade radius are slice 3's
    // PROPOSED sizes (x right, y up, z forward from the feet), to be measured again in the grey box.
    private static final List<Cancel> STEP_CANCEL = List.of(new Cancel(Into.STEP, Phase.RECOVERY, 0, -1));

    /** A hit window that stuns. */
    public static HitWindow hit(int from, int to, double damage, double posture, int hitstun, double knockback) {
        return new HitWindow(from, to, damage, posture, hitstun, knockback, false, false, false, 0, 0);
    }

    /** A horizontal one (Water Surface Slash, Striking Tide): it can take a head (Rule 8.1). */
    public static HitWindow cut(int from, int to, double damage, double posture, int hitstun, double knockback) {
        return new HitWindow(from, to, damage, posture, hitstun, knockback, false, false, true, 0, 0);
    }

    public static Sweep box(double cx, double cy, double cz, double hx, double hy, double hz, Sweep.Pitch pitch) {
        return new Sweep(Sweep.Shape.OBB, new Sweep.Box(cx, cy, cz, hx, hy, hz), pitch, 0, null);
    }

    public static List<Displacement> forward(Phase phase, int from, int to, double perTick) {
        return List.of(new Displacement(phase, from, to, perTick, 0, 0));
    }

    private static final double[] CHAIN_DAMAGE = {2, 2, 3};
    private static final double[] CHAIN_POSTURE = {3, 3, 10};
    private static final int[] CHAIN_HITSTUN = {6, 6, 8};

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
                null,
                Cue.NONE,
                null,
                List.of(hit(0, 1, CHAIN_DAMAGE[n - 1], CHAIN_POSTURE[n - 1], CHAIN_HITSTUN[n - 1], 0)),
                box(0, 1.2, 1.5, 0.9, 0.6, 1.1, Sweep.Pitch.LOOK), // reach 2.6
                forward(Phase.ACTIVE, 0, 1, 0.15));
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
                null,
                Cue.NONE,
                null,
                List.of(hit(0, 1, 0, 0, 0, 0)), // its only effect is the interrupt (Rule 5.2)
                box(0, 1.3, 1.15, 0.45, 0.45, 0.85, Sweep.Pitch.LOOK), // reach 2.0
                List.of());
    }

    public static FrameData step() {
        double[] dash = {0.7, 0.6, 0.5, 0.5, 0.4, 0.3}; // 3 blocks over 6 ticks, fastest first
        List<Displacement> moves = new ArrayList<>();
        for (int i = 0; i < dash.length; i++) {
            moves.add(new Displacement(Phase.ACTIVE, i, i, dash[i], 0, 0));
        }
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
                null,
                Cue.NONE,
                null,
                List.of(),
                null,
                moves);
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
            @Nullable String mushin,
            List<HitWindow> hits,
            Sweep sweep,
            List<Displacement> moves) {
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
                mushin,
                Cue.NONE,
                null,
                hits,
                sweep,
                moves);
    }

    /** Striking Tide's windows: one hit every three active ticks, the last of Turbulent's wall-splatting. */
    private static List<HitWindow> tide(int count, boolean turbulent) {
        List<HitWindow> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean last = turbulent && i == count - 1;
            out.add(new HitWindow(3 * i, 3 * i + 2, 2, 6, 4, last ? 2.5 : 0, false, last, true, 0, 0));
        }
        return out;
    }

    /**
     * Water in M1: the four radial forms (1st up, 2nd right, 4th down, 8th left), Waterfall Basin (the 8th) while
     * falling 2 blocks, and the guard-cancel at 0 / 5 Breath. The spec has no frame timings for the Mushin finishers
     * yet (an open number for slice 5's data): the 1st's reuses its form's timing, and Turbulent runs 18 active ticks
     * for its 6 hits (slice 3, PROPOSED).
     */
    public static Moveset water() {
        Sweep first = box(0, 1.35, 1.5, 1.6, 0.35, 1.3, Sweep.Pitch.LOOK_CLAMPED); // reach 2.8, at neck height
        Sweep tide = box(0, 1.3, 1.4, 1.3, 0.4, 1.1, Sweep.Pitch.LOOK_CLAMPED); // reach 2.5, at neck height
        List<FrameData> frames = List.of(
                chainHit(1, true),
                chainHit(2, true),
                chainHit(3, false),
                pommel(),
                step(),
                form(
                        "first_form",
                        6,
                        3,
                        17,
                        20,
                        3,
                        null,
                        "kimetsu:water/first_form_mushin",
                        List.of(cut(0, 2, 6, 15, 8, 0.35)),
                        first,
                        forward(Phase.ACTIVE, 0, 2, 0.25)),
                form(
                        "first_form_mushin",
                        6,
                        3,
                        17,
                        20,
                        3,
                        null,
                        null,
                        List.of(cut(0, 2, 6, 22.5, 8, 0.35)), // ×1.5 Posture
                        box(0, 1.35, 2.25, 1.6, 0.35, 2.05, Sweep.Pitch.LOOK_CLAMPED), // +1.5 on the far face: 4.3
                        forward(Phase.ACTIVE, 0, 2, 0.25)),
                form(
                        "second_form",
                        7,
                        4,
                        15,
                        25,
                        3,
                        null,
                        null,
                        List.of(hit(0, 3, 7, 18, 8, 0.35)),
                        box(0, 1.2, 1.5, 0.5, 1.0, 1.1, Sweep.Pitch.LOOK), // a narrow vertical cut, reach 2.6
                        forward(Phase.ACTIVE, 0, 3, 0.3)),
                form(
                        "fourth_form",
                        6,
                        12,
                        21,
                        30,
                        3,
                        null,
                        "kimetsu:water/fourth_form_mushin",
                        tide(4, false),
                        tide,
                        forward(Phase.ACTIVE, 0, 11, 0.2)),
                form(
                        "fourth_form_mushin",
                        6,
                        18,
                        21,
                        30,
                        3,
                        null,
                        null, // Turbulent: +2 hits
                        tide(6, true),
                        tide,
                        forward(Phase.ACTIVE, 0, 17, 0.2)),
                form(
                        "eighth_form",
                        8,
                        3,
                        15,
                        25,
                        4,
                        new FrameData.Contextual(2.0),
                        null,
                        List.of(hit(0, 2, 6, 15, 8, 0)),
                        box(0, 0.4, 1.0, 0.6, 1.4, 1.0, Sweep.Pitch.LEVEL), // reaches a block below the feet
                        List.of()));
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

    /**
     * The M1 demon's strikes (Rule 11.3; Numbers "Demon attacks", "Demon recoveries", "Demon hitstun on you"; block
     * values v0). Each is beat-locked to its target, winds up for its cue lead when it opens a string (Rule 7.11), and
     * shrugs off every hit until its impact (Rule 3.9). Its lunge comes in the windup's last ticks, after the hold.
     */
    public static FrameData swipe() {
        return strike(
                "swipe",
                AttackClass.LIGHT,
                10,
                2,
                13,
                new Cue(Cue.Type.WHITE, 10, false),
                9,
                false,
                new HitWindow(0, 1, 1.25, 0, 6, 0, false, false, false, 8, 0),
                box(0, 1.1, 1.3, 1.1, 0.6, 1.2, Sweep.Pitch.LEVEL),
                6,
                9,
                0.25); // reach 2.5 after a 1-block lunge
    }

    public static FrameData heavy() {
        return strike(
                "heavy",
                AttackClass.HEAVY,
                14,
                3,
                18,
                new Cue(Cue.Type.WHITE, 14, false),
                11,
                false,
                new HitWindow(0, 2, 2.5, 0, 10, 2.0, false, true, false, 20, 0),
                box(0, 1.0, 1.5, 1.0, 0.8, 1.4, Sweep.Pitch.LEVEL),
                10,
                13,
                0.25); // reach 2.9, pushes 2 blocks
    }

    public static FrameData leap() {
        return strike(
                "leap",
                AttackClass.HEAVY,
                12,
                3,
                22,
                new Cue(Cue.Type.RED, 12, true),
                10,
                false,
                new HitWindow(0, 2, 3.0, 0, 0, 0, true, false, false, 0, 0),
                box(0, 0.8, 0.9, 1.25, 0.8, 1.2, Sweep.Pitch.LEVEL),
                8,
                11,
                0.5); // 2.5 wide after a 2-block lunge
    }

    public static FrameData lowSweep() {
        return strike(
                "sweep",
                AttackClass.LIGHT,
                10,
                2,
                14,
                new Cue(Cue.Type.RED, 10, false),
                9,
                false,
                new HitWindow(0, 1, 1.5, 0, 8, 0, false, false, false, 0, 0),
                box(0, 0.25, 1.2, 1.6, 0.25, 1.5, Sweep.Pitch.LEVEL),
                7,
                9,
                0.25); // 3.2 wide, 0.5 high
    }

    public static FrameData grab() {
        return strike(
                "grab",
                AttackClass.GRAB,
                12,
                2,
                24,
                new Cue(Cue.Type.PURPLE, 12, false),
                10,
                false,
                new HitWindow(0, 1, 1.0, 0, 0, 0, false, false, false, 0, 15),
                box(0, 1.0, 1.05, 0.6, 0.7, 0.95, Sweep.Pitch.LEVEL),
                9,
                11,
                0.5); // narrow, reach 2.0
    }

    /** Its guard counter (Rule 11.4): the swipe's numbers with a 14-tick lead, its guard held until the impact. */
    public static FrameData guardCounter() {
        return strike(
                "guard_counter",
                AttackClass.LIGHT,
                14,
                2,
                13,
                new Cue(Cue.Type.WHITE, 14, false),
                9,
                true,
                new HitWindow(0, 1, 1.25, 0, 6, 0, false, false, false, 8, 0),
                box(0, 1.1, 1.3, 1.1, 0.6, 1.2, Sweep.Pitch.LEVEL),
                10,
                13,
                0.25);
    }

    private static FrameData strike(
            String name,
            AttackClass attackClass,
            int windup,
            int active,
            int recovery,
            Cue cue,
            int windupMin,
            boolean holdGuards,
            HitWindow hit,
            Sweep sweep,
            int lungeFrom,
            int lungeTo,
            double lungePerTick) {
        return new FrameData(
                "kimetsu:m1_demon/" + name,
                attackClass,
                windup,
                active,
                recovery,
                0,
                FrameData.Judge.PRESS,
                List.of(),
                Armour.ALL,
                Armour.NONE,
                false,
                null,
                null,
                cue,
                new BeatLock(BeatLock.Mode.TARGET, windupMin, 1, 3, holdGuards), // Max windup stretch: 1 beat
                List.of(hit),
                sweep,
                forward(Phase.WINDUP, lungeFrom, lungeTo, lungePerTick));
    }
}
