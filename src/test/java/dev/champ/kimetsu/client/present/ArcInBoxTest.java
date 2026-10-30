package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.hit.Frame;
import dev.champ.kimetsu.core.hit.Obb;
import dev.champ.kimetsu.core.hit.V3;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * A strike's blade path inside its own hitbox (D-144's craft call 2, D-145's A4): a half-ellipse in the plane rolled
 * about the box's forward axis, from one side edge through the far face to the other, its base a share of the way out
 * from the near face's centre. Every base and tip lies in the box, in the attacker's frame and in the world as the
 * server places the box (a level sweep's and a look sweep's pitch), so no cue or trail claims more reach than the real
 * sweep; the cue's anchor is the arc's start. The ends lie on the box's faces, hence the 1e-9 tolerance.
 */
class ArcInBoxTest {
    private static final double EPS = 1e-9;
    private static final Sweep.Box SWIPE = new Sweep.Box(0.0, 1.1, 1.3, 1.1, 0.6, 1.2); // m1_demon/swipe.json
    private static final Sweep.Box GRAB = new Sweep.Box(0.0, 1.0, 1.05, 0.6, 0.7, 0.95); // m1_demon/grab.json

    @Test
    void everyBaseAndTipLiesInTheBox_overRandomBoxesRollsSpansAndProgress() {
        SplittableRandom r = new SplittableRandom(144);
        ArcInBox arc = new ArcInBox();
        V3 base = new V3();
        V3 tip = new V3();
        for (int i = 0; i < 10_000; i++) {
            Sweep.Box b = new Sweep.Box(
                    r.nextDouble(-1, 1),
                    r.nextDouble(0, 2),
                    r.nextDouble(0, 2),
                    r.nextDouble(0.05, 2),
                    r.nextDouble(0.05, 2),
                    r.nextDouble(0.05, 2));
            double roll = i % 7 == 0 ? 90 * r.nextInt(4) : r.nextDouble(-360, 360); // the axes' edge cases too
            double from = r.nextDouble(0, 180);
            double to = r.nextDouble(0, 180);
            arc.set(b, roll, from, to, r.nextDouble(0, 1));
            for (double p : new double[] {0, 1, r.nextDouble()}) {
                arc.at(p, base, tip);
                inBox(b, base, "base", i, p);
                inBox(b, tip, "tip", i, p);
            }
        }
    }

    @Test
    void theArcStaysInTheWorldBox_atALevelAndALookPitch() {
        SplittableRandom r = new SplittableRandom(145);
        ArcInBox arc = new ArcInBox();
        Frame frame = new Frame();
        Obb box = new Obb();
        V3 base = new V3();
        V3 tip = new V3();
        V3 world = new V3();
        V3 near = new V3();
        for (int i = 0; i < 2_000; i++) {
            Sweep.Box b = new Sweep.Box(
                    r.nextDouble(-1, 1),
                    r.nextDouble(0, 2),
                    r.nextDouble(0, 2),
                    r.nextDouble(0.05, 2),
                    r.nextDouble(0.05, 2),
                    r.nextDouble(0.05, 2));
            arc.set(b, r.nextDouble(-360, 360), r.nextDouble(0, 180), r.nextDouble(0, 180), 0.45);
            double pitch = i % 2 == 0 ? 0 : r.nextDouble(-90, 90); // a level sweep's, then a look sweep's
            frame.set(
                    r.nextDouble(-30_000_000, 30_000_000),
                    r.nextDouble(-64, 320),
                    r.nextDouble(-30_000_000, 30_000_000),
                    r.nextDouble(0.5, 2.5),
                    r.nextDouble(-180, 180),
                    pitch);
            frame.box(b, box);
            arc.at(r.nextDouble(), base, tip);
            for (V3 p : new V3[] {base, tip}) {
                frame.toWorld(p.x(), p.y(), p.z(), world);
                box.nearest(world.x(), world.y(), world.z(), near);
                double off = Math.max(
                        Math.abs(near.x() - world.x()),
                        Math.max(Math.abs(near.y() - world.y()), Math.abs(near.z() - world.z())));
                // far from the origin a world coordinate keeps ~1e-9 of a block per 1e7: relative, as the box's own
                assertTrue(off <= 1e-7, "outside the world box by " + off + " at case " + i);
            }
        }
    }

    @Test
    void theDefaultArcRunsFromTheAttackersRightThroughTheFarFaceToItsLeft() {
        ArcInBox arc = new ArcInBox().set(SWIPE, ArcInBox.defaultRoll(SWIPE), 0, 180, 0.45);
        V3 base = new V3();
        V3 tip = new V3();
        arc.at(0, base, tip);
        assertPoint(1.1, 1.1, 0.1, tip, "the start: the right side edge at the near face, centre height");
        arc.at(0.5, base, tip);
        assertPoint(0.0, 1.1, 2.5, tip, "the middle: the far face's centre");
        assertPoint(0.0, 1.1, 0.1 + 0.45 * 2.4, base, "the base: arc_base of the way out from the near face");
        arc.at(1, base, tip);
        assertPoint(-1.1, 1.1, 0.1, tip, "the end: the left side edge");
    }

    @Test
    void aTallBoxsDefaultArcIsAnOverheadChop() {
        assertEquals(0, ArcInBox.defaultRoll(SWIPE), "wider than tall: horizontal");
        assertEquals(90, ArcInBox.defaultRoll(GRAB), "taller than wide: vertical");
        ArcInBox arc = new ArcInBox().set(GRAB, ArcInBox.defaultRoll(GRAB), 0, 180, 0.45);
        V3 base = new V3();
        V3 tip = new V3();
        arc.at(0, base, tip);
        assertPoint(0.0, 1.7, 0.1, tip, "it starts at the top");
        arc.at(1, base, tip);
        assertPoint(0.0, 0.3, 0.1, tip, "and ends at the bottom");
    }

    @Test
    void theCueAnchorIsTheArcsStart() {
        ArcInBox arc = new ArcInBox().set(SWIPE, 30, 20, 160, 0.45);
        V3 anchor = new V3();
        V3 base = new V3();
        V3 tip = new V3();
        arc.anchor(anchor);
        arc.at(0, base, tip);
        assertPoint(tip.x(), tip.y(), tip.z(), anchor, "the glint and the burst sit where the blow comes from");
    }

    private static void inBox(Sweep.Box b, V3 p, String what, int i, double progress) {
        String where = what + " at case " + i + ", progress " + progress;
        assertTrue(Math.abs(p.x() - b.cx()) <= b.hx() + EPS, where + ": x " + p.x() + " outside " + b);
        assertTrue(Math.abs(p.y() - b.cy()) <= b.hy() + EPS, where + ": y " + p.y() + " outside " + b);
        assertTrue(Math.abs(p.z() - b.cz()) <= b.hz() + EPS, where + ": z " + p.z() + " outside " + b);
    }

    private static void assertPoint(double x, double y, double z, V3 p, String what) {
        assertEquals(x, p.x(), EPS, what + " (x)");
        assertEquals(y, p.y(), EPS, what + " (y)");
        assertEquals(z, p.z(), EPS, what + " (z)");
    }
}
