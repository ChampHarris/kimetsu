package dev.champ.kimetsu.core.data;

import java.util.List;

/**
 * A baked blade arc (R6-08; {@code data/<ns>/kimetsu/arcs/}, written by {@code tools/bake_arcs.py}): the blade's base
 * and tip in the attacker's frame (x right, y up, z forward from the feet), {@code samplesPerTick} times per active
 * tick plus one, so each sub-step sweeps the quad between two samples (Rule 7.2).
 */
public record Arc(String id, int samplesPerTick, List<Sample> samples) {

    /** The blade at one moment: its base and its tip. */
    public record Sample(double baseX, double baseY, double baseZ, double tipX, double tipY, double tipZ) {}

    public Arc {
        Check.notBlank("arc id", id);
        String at = id + ": ";
        Check.positive(at + "samples_per_tick", samplesPerTick);
        samples = List.copyOf(samples);
        Check.that(samples.size() >= 2, at + "samples must hold at least 2, held " + samples.size());
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            Check.that(
                    Double.isFinite(s.baseX())
                            && Double.isFinite(s.baseY())
                            && Double.isFinite(s.baseZ())
                            && Double.isFinite(s.tipX())
                            && Double.isFinite(s.tipY())
                            && Double.isFinite(s.tipZ()),
                    at + "samples[" + i + "] must be finite");
        }
    }
}
