package dev.champ.kimetsu.core;

import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.core.data.HungerRules;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.TimingRules;
import java.util.List;

/**
 * The spec's PROPOSED values (docs/design/specs/m1-combat-core.md, Numbers), each named as its Numbers row. This is
 * the only place they live in Java: the mod loads them from datapack JSON, and the {@code m1_data_loaded} GameTest
 * compares what it loads against these.
 */
public final class Fixtures {
    private Fixtures() {
    }

    public static TimingRules timing() {
        return new TimingRules(
                120,      // On-beat window
                180,      // On-beat window, assist
                150,      // Deflect window
                60,       // Perfect window
                1.5,      // Assist window scale
                1,        // Credits per beat
                5,        // Whiff lockout (ticks)
                3.0,      // Stamp tolerance: 3 × jitter
                20, 80,   // Stamp tolerance: clamped to 20–80 ms
                40,       // Stamp tolerance: jitter over 40 frames
                300,      // Stall bracket
                300,      // Hold cap: RTT/2 + tolerance + 300
                100, 450, // Hold cap: clamped to 100–450 ms
                true,     // Stamp correction: half a frame
                100,      // Stamp correction: frames longer than 100 ms count as 100
                10_000);  // RTT: minimum over 10 s
    }

    public static ClockRules clock() {
        return new ClockRules(
                16,       // Clock sync samples
                4,        // Clock sync samples: the first fix waits for 4 (the join burst)
                10,       // Clock slew limit, ms per beat
                40,       // Clock slew limit after a rate change
                4,        // Clock rate: records averaged
                0.01,     // Clock rate change: 1%
                4.0, 4,   // Clock record push: 4 a second, a burst of 4
                2,        // Tempo change: 2 beats ahead
                1000,     // Clock sync interval in combat
                5000,     // Clock sync interval otherwise
                10,       // Tick length (T̄): the last 10 ordinary intervals
                3,        // Tick length (T̄): 3 agreeing intervals outside the band are a new rate
                0.2,      // Tick length (T̄): late = more than a fifth of a tick behind vanilla's schedule
                0.5, 1.5, // Tick length (T̄): ordinary = 0.5–1.5 × T̄
                0.5);     // Tick length (T̄): steered halfway
    }

    public static BreathRules breath() {
        return new BreathRules(
                100,      // Breath
                10, 4,    // Breath regeneration: out of combat, in combat
                6,        // On-beat Inhale
                6,        // Held guard drain
                40,       // Winded (ticks)
                6,        // Winded guard: 6 Posture/s
                1.0);     // Recovery Breath refund
    }

    public static PostureRules playerPosture() {
        return new PostureRules(
                100, 12,  // Player Posture: 100, recovers 12/s
                30,       // ... after 1.5 s
                4,        // On-beat Posture bonus
                30, 0.5,  // Stagger: 30 ticks, resets to 50%
                1.0);     // no vitality coupling
    }

    public static PostureRules demonPosture() {
        return new PostureRules(
                300, 12,  // M1 demon Posture: 300, recovers 12/s
                60,       // ... after 3 s (D-028)
                0,
                30, 0.5,  // Stagger
                0.25);    // Vitality coupling floor
    }

    public static RhythmRules rhythm() {
        return new RhythmRules(
                4, 5,     // Rhythm per tier: 4 points, tiers 0–5
                List.of(1.00, 1.10, 1.20, 1.32, 1.45, 1.60),   // Rhythm multipliers
                1, 1, 2, 1, -1, -4,                            // Rhythm changes: credit, deflect, Perfect, dodge, off-beat, hit
                60,       // Rhythm decay: after 3 s idle
                3);       // Flow spend: to tier 3
    }

    public static HungerRules hunger() {
        return new HungerRules(
                100,      // Hunger
                1,        // 1 per HP
                5,        // the leap
                25,       // rising from collapse
                0.25);    // Cellular Hardening above 25%
    }
}
