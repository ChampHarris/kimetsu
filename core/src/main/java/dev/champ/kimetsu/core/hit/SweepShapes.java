package dev.champ.kimetsu.core.hit;

import dev.champ.kimetsu.core.data.Arc;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Displacement;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.Phase;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Compiles an attack's sweep once, when its data loads, and checks what one file can't (Rules 7.1–7.2, R6-08): that
 * an arc has a sample per sub-step and stays inside its envelope box, and that a box can't leave gaps between
 * consecutive sub-steps for a target to slip through. The gap check covers the attack's own turn and displacement;
 * the attacker's walking and the target's own motion come on top, which a body's thickness absorbs but a thin neck
 * box under a fast vertical relative motion (over about 2.8 blocks a tick) might not.
 */
public final class SweepShapes {
    private SweepShapes() {}

    /**
     * {@code f}'s sweep for a body whose standing eyes are {@code maxEye} above its feet (a crouch or a swim lowers
     * them, never raises them). {@code arc} is the baked arc its sweep names, if it's an arc.
     */
    public static SweepShape compile(FrameData f, @Nullable Arc arc, double maxEye, HitRules rules) {
        Sweep s = f.sweep();
        String at = f.id() + ": ";
        if (s == null) {
            throw new IllegalArgumentException(at + "sweep is missing");
        }
        if (!(maxEye >= 0) || !Double.isFinite(maxEye)) {
            throw new IllegalArgumentException(at + "the body's eye height must be finite and not negative");
        }
        boolean pitched = s.pitch() != Sweep.Pitch.LEVEL;
        int subSteps = rules.subSteps();
        if (s.shape() == Sweep.Shape.ARC) {
            return arc(f, s, arc, maxEye, pitched, subSteps, rules, at);
        }
        Sweep.Box b = s.box();
        double reach = 0;
        double turning = 0; // the furthest a corner lies from what it turns about
        for (int corner = 0; corner < 8; corner++) {
            double x = b.cx() + ((corner & 1) == 0 ? -b.hx() : b.hx());
            double y = b.cy() + ((corner & 2) == 0 ? -b.hy() : b.hy());
            double z = b.cz() + ((corner & 4) == 0 ? -b.hz() : b.hz());
            reach = Math.max(reach, reach(x, y, z, maxEye, pitched));
            turning = Math.max(turning, turning(x, y, z, maxEye, pitched));
        }
        double perSubStep = Math.toRadians(rules.maxTurnDegPerTick()) / subSteps * turning
                + fastestActiveMove(f.displacement()) / subSteps;
        double thinnest = 2 * Math.min(b.hx(), Math.min(b.hy(), b.hz()));
        if (perSubStep > thinnest) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "%ssweep.half: a sub-step can move the box %.3f blocks, more than its %.3f-block thickness, so a"
                            + " target could slip between two sub-steps (Rule 7.2)",
                    at,
                    perSubStep,
                    thinnest));
        }
        return new SweepShape(f, s, maxEye, reach, subSteps, new double[0]);
    }

    private static SweepShape arc(
            FrameData f,
            Sweep s,
            @Nullable Arc arc,
            double maxEye,
            boolean pitched,
            int subSteps,
            HitRules rules,
            String at) {
        if (arc == null || !arc.id().equals(s.arc())) {
            throw new IllegalArgumentException(at + "sweep.arc names " + s.arc() + ", which isn't loaded");
        }
        if (arc.samplesPerTick() != subSteps) {
            throw new IllegalArgumentException(at + "arc " + arc.id() + " samples " + arc.samplesPerTick()
                    + " times a tick, the sweep " + subSteps);
        }
        int expected = f.active() * subSteps + 1;
        if (arc.samples().size() != expected) {
            throw new IllegalArgumentException(at + "arc " + arc.id() + " holds "
                    + arc.samples().size() + " samples, its " + f.active() + " active ticks need " + expected);
        }
        List<Arc.Sample> list = arc.samples();
        double[] flat = new double[list.size() * 6];
        double reach = 0;
        for (int i = 0; i < list.size(); i++) {
            Arc.Sample p = list.get(i);
            flat[6 * i] = p.baseX();
            flat[6 * i + 1] = p.baseY();
            flat[6 * i + 2] = p.baseZ();
            flat[6 * i + 3] = p.tipX();
            flat[6 * i + 4] = p.tipY();
            flat[6 * i + 5] = p.tipZ();
            reach = Math.max(reach, reach(p.baseX(), p.baseY(), p.baseZ(), maxEye, pitched));
            reach = Math.max(reach, reach(p.tipX(), p.tipY(), p.tipZ(), maxEye, pitched));
        }
        double inside = envelopeShare(flat, s.box());
        if (inside < rules.arcEnvelopeShare()) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "%sarc %s: %.0f%% of the blade's swept points lie inside its box, under the %.0f%% its envelope"
                            + " needs (R6-08)",
                    at,
                    arc.id(),
                    inside * 100,
                    rules.arcEnvelopeShare() * 100));
        }
        return new SweepShape(f, s, maxEye, reach + s.radius(), subSteps, flat);
    }

    /**
     * The share of the blade's swept points inside the box (R6-08's ≥ 70%): each quad between two samples, at a 4 × 4
     * grid from base to tip and from one sample to the next, in the attacker's frame.
     */
    private static double envelopeShare(double[] p, Sweep.Box b) {
        int in = 0;
        int all = 0;
        for (int i = 0; i + 1 < p.length / 6; i++) {
            int a = 6 * i;
            int c = 6 * (i + 1);
            for (int u = 0; u <= 3; u++) {
                for (int v = 0; v <= 3; v++) {
                    double su = u / 3.0;
                    double sv = v / 3.0;
                    double x = mix(mix(p[a], p[a + 3], su), mix(p[c], p[c + 3], su), sv);
                    double y = mix(mix(p[a + 1], p[a + 4], su), mix(p[c + 1], p[c + 4], su), sv);
                    double z = mix(mix(p[a + 2], p[a + 5], su), mix(p[c + 2], p[c + 5], su), sv);
                    if (Math.abs(x - b.cx()) <= b.hx()
                            && Math.abs(y - b.cy()) <= b.hy()
                            && Math.abs(z - b.cz()) <= b.hz()) {
                        in++;
                    }
                    all++;
                }
            }
        }
        return all == 0 ? 1 : (double) in / all;
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * How far from the feet a point can be at any aim and any eye height up to the standing one: pitch turns it about
     * the eyes, so its distance from them plus their height. That's convex in the height, so its larger end is the
     * bound: at the feet (a swim's eyes, near 0) or at the standing eyes. A level sweep only turns about the vertical.
     */
    private static double reach(double x, double y, double z, double maxEye, boolean pitched) {
        double fromFeet = Math.sqrt(x * x + y * y + z * z);
        if (!pitched) {
            return fromFeet;
        }
        return Math.max(fromFeet, Math.sqrt(x * x + (y - maxEye) * (y - maxEye) + z * z) + maxEye);
    }

    /** How far a point lies from what it turns about: the eyes for a pitched sweep (at any height), else the vertical. */
    private static double turning(double x, double y, double z, double maxEye, boolean pitched) {
        if (!pitched) {
            return Math.sqrt(x * x + z * z);
        }
        return Math.max(Math.sqrt(x * x + y * y + z * z), Math.sqrt(x * x + (y - maxEye) * (y - maxEye) + z * z));
    }

    private static double fastestActiveMove(List<Displacement> moves) {
        double fastest = 0;
        for (Displacement d : moves) {
            if (d.phase() == Phase.ACTIVE) {
                fastest = Math.max(
                        fastest, Math.sqrt(d.forward() * d.forward() + d.right() * d.right() + d.up() * d.up()));
            }
        }
        return fastest;
    }
}
