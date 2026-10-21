package dev.champ.kimetsu.core.hit;

import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Sweep;

/**
 * An attack's sweep compiled for one body ({@link SweepShapes#compile}): its box, or its arc's samples as one flat
 * array, the sub-steps it was compiled for, and how far from the feet any of it can reach whatever the aim and for any
 * eye height up to the body's standing one, for culling distant targets.
 */
public final class SweepShape {
    final FrameData frames;
    final Sweep sweep;
    final boolean arc;
    final double radius;
    final double maxEye;
    final double reach;
    final int subSteps;
    final double[] samples; // per sample: base x, y, z, tip x, y, z; empty for a box

    SweepShape(FrameData frames, Sweep sweep, double maxEye, double reach, int subSteps, double[] samples) {
        this.frames = frames;
        this.sweep = sweep;
        this.arc = sweep.shape() == Sweep.Shape.ARC;
        this.radius = sweep.radius();
        this.maxEye = maxEye;
        this.reach = reach;
        this.subSteps = subSteps;
        this.samples = samples;
    }

    public FrameData frames() {
        return frames;
    }

    /** The furthest any part of it lies from the attacker's feet, at any aim and eye height. */
    public double reach() {
        return reach;
    }

    /** The body's standing eye height: a pose's lower eyes (crouching, swimming) are clamped to it. */
    public double maxEye() {
        return maxEye;
    }
}
