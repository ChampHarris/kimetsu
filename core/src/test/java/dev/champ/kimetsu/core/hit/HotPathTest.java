package dev.champ.kimetsu.core.hit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.Arc;
import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The sweep's hot path (Performance Budget: about 24 separating-axis tests per active attack per tick, under 5 µs,
 * nothing allocated). Run by {@code :core:hotPathTest}, with escape analysis off so the JIT can't hide a stray
 * allocation that a busier call site would make. Two worst cases, each a crowd of 4 near misses with necks that pass
 * both cheap culls (the reach, then the bounds) on every sub-step, so every separating-axis test runs in full and finds
 * nothing: a turned box (24 box tests) and a blade arc (48 triangle tests). {@link #fullTests} replays the culls to
 * hold each crowd to that. The times are printed; with {@code -Pbench} they're also held to the budget (shared CI
 * runners are too noisy to hold them there).
 */
@Tag("hot-path")
class HotPathTest {
    private static final HitRules RULES = Fixtures.hits();

    private final Sweeper sweeper = new Sweeper();
    private final SweepShape box =
            SweepShapes.compile(Fixtures.water().frame("kimetsu:water/fourth_form"), null, Fixtures.PLAYER_EYE, RULES);
    private final SweepShape blade = SweepShapes.compile(arcAttack(), arc(), Fixtures.PLAYER_EYE, RULES);
    private final PoseRing poses = new PoseRing(32);
    private final Targets boxMisses = new Targets();
    private final Targets bladeMisses = new Targets();
    private final SwingTrack track = new SwingTrack();
    private final Contacts out = new Contacts();

    HotPathTest() {
        for (int n = 0; n <= 20; n++) {
            poses.put(Micros.ofTicks(n), 0, 64, 0, 45, 0); // turned 45°: the shapes' bounds outgrow them at the corners
        }
        // Striking Tide's box at yaw 45 is centred at (−0.99, 0.99) and its bounds reach 1.70 each way: a target pulled
        // 0.15 in from each corner of the bounds overlaps them but lies outside the box
        double cx = -1.4 * Math.sin(Math.toRadians(45));
        double cz = 1.4 * Math.cos(Math.toRadians(45));
        double reach = 1.3 * Math.cos(Math.toRadians(45)) + 1.1 * Math.sin(Math.toRadians(45)) - 0.15;
        int id = 100;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                demon(boxMisses, id++, cx + sx * reach, cz + sz * reach);
            }
        }
        // the blade's strips run along its facing, so their bounds outgrow them off the diagonal: a crowd packed into
        // the two corners there, found by search, meets every strip's bounds and misses every strip
        demon(bladeMisses, 200, -1.75, 0.50);
        demon(bladeMisses, 201, -1.75, 0.55);
        demon(bladeMisses, 202, -1.70, 0.50);
        demon(bladeMisses, 203, -0.40, 1.60);
    }

    private static void demon(Targets crowd, int id, double x, double z) {
        int i = crowd.add(id, x - 0.3, 64, z - 0.3, x + 0.3, 65.95, z + 0.3);
        crowd.neck(i, x, 65.4, z, 0, x, 65.4, z, 0, 0.18, 0.12, 0.18);
    }

    private static FrameData arcAttack() {
        return new FrameData(
                "kimetsu:test/hot_arc",
                AttackClass.HEAVY,
                0,
                1,
                5,
                0,
                FrameData.Judge.RELEASE,
                List.of(),
                Armour.NONE,
                Armour.NONE,
                false,
                null,
                null,
                Cue.NONE,
                null,
                List.of(new HitWindow(0, 0, 1, 1, 1, 0, false, false, true, 0, 0)), // horizontal: necks too
                new Sweep(
                        Sweep.Shape.ARC,
                        new Sweep.Box(0, 1.35, 1.5, 0.4, 0.3, 0.8),
                        Sweep.Pitch.LEVEL,
                        0.1,
                        "kimetsu:test/hot_arc"),
                List.of());
    }

    /** A blade swinging a little, left to right, straight ahead. */
    private static Arc arc() {
        List<Arc.Sample> s = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            double x = -0.3 + 0.2 * i;
            s.add(new Arc.Sample(x, 1.35, 0.8, x, 1.35, 2.2));
        }
        return new Arc("kimetsu:test/hot_arc", 3, s);
    }

    /**
     * How many separating-axis tests one sweep of {@code crowd} runs in full: the sweep's two cheap culls (the reach
     * from the feet, then the bounds) replayed for this still attacker, a test for each body and neck, sub-step and
     * shape (a box, or a strip's two triangles) that passes both.
     */
    private int fullTests(SweepShape shape, Targets crowd) {
        Frame at = new Frame().set(0, 64, 0, Fixtures.PLAYER_EYE, 45, 0);
        Obb swept = at.box(shape.sweep.box(), new Obb());
        Obb part = new Obb();
        V3 b0 = new V3();
        V3 t0 = new V3();
        V3 b1 = new V3();
        V3 t1 = new V3();
        V3 near = new V3();
        Tri first = new Tri();
        Tri second = new Tri();
        double[] p = shape.samples;
        int full = 0;
        for (int k = 1; k <= RULES.subSteps(); k++) {
            if (shape.arc) {
                int a = 6 * (k - 1);
                int b = 6 * k;
                at.toWorld(p[a], p[a + 1], p[a + 2], b0);
                at.toWorld(p[a + 3], p[a + 4], p[a + 5], t0);
                at.toWorld(p[b], p[b + 1], p[b + 2], b1);
                at.toWorld(p[b + 3], p[b + 4], p[b + 5], t1);
                first.set(b0, t0, t1);
                second.set(b0, t1, b1);
            }
            for (int j = 0; j < crowd.size(); j++) {
                for (int neck = 0; neck < 2; neck++) {
                    Obb o = neck == 0 ? crowd.boxAt(j, 1, part) : crowd.neckAt(j, 1, part);
                    o.nearest(at.feetX(), at.feetY(), at.feetZ(), near);
                    double dx = near.x - at.feetX();
                    double dy = near.y - at.feetY();
                    double dz = near.z - at.feetZ();
                    if (dx * dx + dy * dy + dz * dz > shape.reach * shape.reach) {
                        continue;
                    }
                    double minX = o.cx - o.reachX();
                    double minY = o.cy - o.reachY();
                    double minZ = o.cz - o.reachZ();
                    double maxX = o.cx + o.reachX();
                    double maxY = o.cy + o.reachY();
                    double maxZ = o.cz + o.reachZ();
                    if (!shape.arc) {
                        full += swept.boundsOverlap(minX, minY, minZ, maxX, maxY, maxZ) ? 1 : 0;
                    } else {
                        full += first.boundsOverlap(shape.radius, minX, minY, minZ, maxX, maxY, maxZ) ? 1 : 0;
                        full += second.boundsOverlap(shape.radius, minX, minY, minZ, maxX, maxY, maxZ) ? 1 : 0;
                    }
                }
            }
        }
        return full;
    }

    @Test
    void eachCrowdRunsEveryTestInFullAndIsMissed() {
        assertEquals(24, fullTests(box, boxMisses), "full box tests per sweep");
        assertEquals(48, fullTests(blade, bladeMisses), "full triangle tests per sweep");
        assertEquals(0, once(box, boxMisses, 1));
        assertEquals(0, once(blade, bladeMisses, 2));
    }

    private int once(SweepShape shape, Targets crowd, long swing) {
        out.clear();
        return sweeper.sweep(1, swing, shape, 0, 10, poses, crowd, track, Sight.OPEN, RULES, out);
    }

    /**
     * The bytes this thread allocates in each of 10 windows of 20,000 sweeps, after 50,000 to warm up. With escape
     * analysis off, a sweep that allocated would show in every window. The JIT's own tier changes allocate a few
     * hundred bytes on this thread now and then (a 2-core runner still shows one after 100,000 sweeps), so one window
     * that must read 0 would flake: most of them must.
     */
    private long[] allocated(SweepShape shape, Targets crowd) {
        long swing = 0;
        for (int i = 0; i < 50_000; i++) {
            once(shape, crowd, ++swing);
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] windows = new long[10];
        int contacts = 0;
        for (int w = 0; w < windows.length; w++) {
            long before = bean.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 20_000; i++) {
                contacts += once(shape, crowd, ++swing);
            }
            windows[w] = bean.getCurrentThreadAllocatedBytes() - before;
        }
        assertEquals(0, contacts);
        return windows;
    }

    private static void mostlyClean(long[] windows, String sweeps) {
        System.out.println(sweeps + ", bytes allocated per window of 20,000: " + Arrays.toString(windows));
        int clean = 0;
        for (long bytes : windows) {
            clean += bytes == 0 ? 1 : 0;
        }
        assertTrue(
                clean >= 6,
                sweeps + " allocated in " + (windows.length - clean) + " of " + windows.length
                        + " windows of 20,000 sweeps (bytes: " + Arrays.toString(windows) + ")");
    }

    @Test
    void aSweepAllocatesNothing() {
        mostlyClean(allocated(box, boxMisses), "box sweeps");
        mostlyClean(allocated(blade, bladeMisses), "arc sweeps");
    }

    private long median(SweepShape shape, Targets crowd) {
        long swing = 0;
        for (int i = 0; i < 50_000; i++) {
            once(shape, crowd, ++swing);
        }
        long[] batches = new long[20];
        for (int b = 0; b < batches.length; b++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                once(shape, crowd, ++swing);
            }
            batches[b] = (System.nanoTime() - start) / 100_000;
        }
        Arrays.sort(batches);
        return batches[batches.length / 2];
    }

    @Test
    void aSweepFitsTheBudget() {
        long boxNs = median(box, boxMisses);
        long arcNs = median(blade, bladeMisses);
        System.out.println("worst-case sweep, 4 near misses with necks, 3 sub-steps: box " + boxNs
                + " ns (24 tests), arc " + arcNs + " ns (48 tests); budget 5000 ns");
        if (Boolean.getBoolean("kimetsu.bench")) {
            assertTrue(boxNs < 5000 && arcNs < 5000, "box " + boxNs + " ns, arc " + arcNs + " ns per sweep");
        }
    }
}
