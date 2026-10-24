package dev.champ.kimetsu.data;

import dev.champ.kimetsu.core.data.ClockRules;
import dev.champ.kimetsu.core.data.FightRules;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.data.TimingRules;

/**
 * A combat rules file ({@code data/<ns>/kimetsu/combat_rules/}; M1's {@code kimetsu:default}): the judge's windows and
 * stamp checks, the clocks, the state machine, hits, strikes and the clash, Rhythm, and fights.
 */
public record CombatRules(
        TimingRules timing,
        ClockRules clock,
        MachineRules machine,
        HitRules hits,
        StrikeRules strikes,
        RhythmRules rhythm,
        FightRules fights) {}
