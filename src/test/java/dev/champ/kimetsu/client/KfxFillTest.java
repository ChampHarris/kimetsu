package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.client.kfx.CueSheet;
import dev.champ.kimetsu.client.present.DrawnFrame;
import dev.champ.kimetsu.client.present.Feel;
import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.net.NetMessage;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A frame's world cues from what the server routed (slice 5d-4a's build step 6; D-144's craft call 4, D-145's A1, A5 to
 * A8, A10 and A11), on the frame's scheduled time:
 *
 * <ul>
 *   <li>a white or red strike's sprite stands at its arc's start in its own box (the attacker's right), on its
 *       attacker's drawn body; a grab's ring stands on its attacker's axis at its box's centre height, its box and
 *       progress handed on; the white glint flares for one step from half the target's beat before the impact, and a
 *       feint's shatters from the later of its cancel tick and the first frame that knew of the cancel (A6); any other
 *       cancel ends the cue;
 *   <li>a demon's Thread lies on its neck box, the kneeling one while COLLAPSED, from its opening tick, its shimmer
 *       stepping; never dimmed within the engage range (any Slayer there may take it), and not drawn beyond it;
 *   <li>a micro-clash's flash is drawn once, midway between its two fighters at chest height, and keeps the first frame
 *       that showed it;
 *   <li>a cue on you or one you're involved in draws at full strength; another only within the engage range, at
 *       {@code bystander_alpha} and {@code bystander_scale}; the 16 slots fill with yours first, then the nearest;
 *   <li>nothing is drawn for an attacker the client lacks, and nothing is allocated once warm.
 * </ul>
 */
class KfxFillTest {
    private static final int SELF = 1;
    private static final int ALLY = 2;
    private static final int DEMON = 10;
    private static final int FAR_DEMON = 11;
    private static final Feel.Kfx K = new Feel.Kfx(0.5f, 24, 0.35f, 100, 12, 0.45f, 0.6f, 0.75f, false);
    private static final long PERIOD = Micros.ofTicks(13); // Water's beat, 650 ms
    private static final long STEP = 1_000_000 / 12; // a 12 fps cue step, in µs
    private static final double ENGAGE = 32; // combat_rules' engage_blocks
    private static final double EPS = 1e-9;
    // a demon turned 37° at (10.5, 64, -3.25); you at (12, 64, 0)
    private static final double DX = 10.5;
    private static final double DY = 64;
    private static final double DZ = -3.25;
    private static final float DYAW = 37f;

    // ------------------------------------------------------------------------------------------------ strikes

    @Test
    void aWhiteStrikesGlint_standsAtItsArcsStart_theAttackersRight_onItsDrawnBody() {
        ClientView v = new ClientView();
        feed(v, 100, strike(1, DEMON, SELF, Fixtures.SWIPE_ID, 100, 110));
        DrawnFrame drawn = new DrawnFrame().entity(DX, DY, DZ, DYAW, 0).place(0.5f, DX + 2, DY, DZ + 2, DYAW + 60);
        World w =
                new World().at(DEMON, drawn.x, drawn.y, drawn.z, drawn.bodyYaw).at(SELF, 12, 64, 0, 200);
        KfxMark m = new KfxMark();
        KfxFill f = fill();

        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(100) - 1, K, m), "nothing before its cue");
        assertTrue(f.fill(v, w, SELF, Micros.ofTicks(103), K, m), "it holds a cue");
        assertEquals(1, m.cueCount());
        KfxMark.Cue c = m.cue(0);
        assertEquals(CueSheet.GLINT, c.cell);
        Sweep.Box box = box(Fixtures.SWIPE_ID); // the near face's right edge at the box's centre height
        assertAt(world(drawn, box.cx() + box.hx(), box.cy(), box.cz() - box.hz()), c);
        assertEquals(1f, c.alpha, "it's on you");
        assertEquals(1f, c.scale);
        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(110), K, m), "the window is [cue, impact)");

        World ahead = new World().at(DEMON, 0, 64, 0, 0).at(SELF, 0, 64, 3, 180); // facing +z, you in front
        f.fill(v, ahead, SELF, Micros.ofTicks(103), K, m);
        assertEquals(-box.hx(), m.cue(0).x, EPS, "Minecraft's right of a demon facing +z is -x: your left");
        assertEquals(box.cz() - box.hz(), m.cue(0).z, EPS);
    }

    @Test
    void theGlintFlares_forOneStepFromHalfTheTargetsBeatBeforeItsImpact() {
        ClientView v = new ClientView();
        feed(v, 100, strike(1, DEMON, SELF, Fixtures.HEAVY_ID, 100, 114));
        World w = world();
        KfxMark m = new KfxMark();
        KfxFill f = fill();
        long flare = Micros.ofTicks(114) - PERIOD / 2;
        assertEquals(Micros.ofTicks(100) + Micros.PER_TICK * 15 / 2, flare, "the heavy's flare: 7.5 ticks in (A1)");

        f.fill(v, w, SELF, flare - 1, K, m);
        assertEquals(CueSheet.GLINT, m.cue(0).cell);
        f.fill(v, w, SELF, flare, K, m);
        assertEquals(CueSheet.GLINT_FLARE, m.cue(0).cell);
        f.fill(v, w, SELF, flare + STEP - 1, K, m);
        assertEquals(CueSheet.GLINT_FLARE, m.cue(0).cell, "one cue step");
        f.fill(v, w, SELF, flare + STEP, K, m);
        assertEquals(CueSheet.GLINT, m.cue(0).cell);
    }

    @Test
    void aFeintsGlintShatters_fromTheFirstFrameThatKnew_whenTheCancelArrivedAfterItsTick() {
        ClientView v = new ClientView();
        feed(v, 100, strike(1, DEMON, SELF, Fixtures.SWIPE_ID, 100, 110));
        feed(v, 108, new Outbox.AttackCancelled(1, 105, Outbox.AttackCancelled.FEINT)); // 3 ticks late (A6)
        World w = world();
        KfxMark m = new KfxMark();
        KfxFill f = fill();
        long known = Micros.ofTicks(108) + 7_000; // the first frame to know of it

        f.fill(v, w, SELF, known, K, m);
        assertEquals(CueSheet.SHATTER, m.cue(0).cell);
        assertEquals(0, m.cue(0).frame);
        f.fill(v, w, SELF, known + STEP, K, m);
        assertEquals(1, m.cue(0).frame);
        f.fill(v, w, SELF, known + 4 * STEP - 1, K, m);
        assertEquals(3, m.cue(0).frame, "the sheet's four frames");
        assertFalse(f.fill(v, w, SELF, known + 4 * STEP, K, m), "played out");
        assertEquals(known, v.cancelKnown(1, known + 5 * STEP), "the first frame's stamp, kept (D-134 (1))");
    }

    @Test
    void aFeintKnownBeforeItsTick_shattersFromItsTick() {
        ClientView v = new ClientView();
        feed(v, 100, strike(1, DEMON, SELF, Fixtures.SWIPE_ID, 100, 110));
        feed(v, 103, new Outbox.AttackCancelled(1, 105, Outbox.AttackCancelled.FEINT)); // its cue breaks at 105
        World w = world();
        KfxMark m = new KfxMark();
        KfxFill f = fill();

        f.fill(v, w, SELF, Micros.ofTicks(103), K, m);
        assertEquals(CueSheet.GLINT, m.cue(0).cell, "whole until the break");
        f.fill(v, w, SELF, Micros.ofTicks(104), K, m);
        assertEquals(CueSheet.GLINT_FLARE, m.cue(0).cell, "its flare, 3.5 ticks in (A1)");
        f.fill(v, w, SELF, Micros.ofTicks(105), K, m);
        assertEquals(CueSheet.SHATTER, m.cue(0).cell, "the shatter wins over the flare it overlaps");
        assertEquals(0, m.cue(0).frame);
    }

    @Test
    void anyOtherCancel_endsTheCueAtItsTick_withNoShards() {
        ClientView v = new ClientView();
        feed(v, 100, strike(1, DEMON, SELF, Fixtures.SWIPE_ID, 100, 110));
        feed(v, 103, new Outbox.AttackCancelled(1, 105, "broken"));
        World w = world();
        KfxMark m = new KfxMark();
        KfxFill f = fill();

        assertTrue(f.fill(v, w, SELF, Micros.ofTicks(103), K, m));
        assertEquals(CueSheet.GLINT, m.cue(0).cell);
        assertTrue(f.fill(v, w, SELF, Micros.ofTicks(105) - 1, K, m), "until its tick");
        assertEquals(CueSheet.GLINT_FLARE, m.cue(0).cell, "mid-flare (A1: 3.5 to 5.17 ticks in)");
        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(105), K, m), "gone at its tick, the flare cut off");
        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(105) + STEP, K, m), "no shards");
    }

    @Test
    void aRedStrikesBurst_standsAtItsArcsStart() {
        ClientView v = new ClientView();
        feed(v, 100, strike(1, DEMON, SELF, Fixtures.SWEEP_ID, 100, 110));
        KfxMark m = new KfxMark();
        fill().fill(v, world(), SELF, Micros.ofTicks(104), K, m);
        KfxMark.Cue c = m.cue(0);
        assertEquals(CueSheet.BURST, c.cell);
        Sweep.Box box = box(Fixtures.SWEEP_ID); // the low sweep: wide and flat, so a horizontal arc
        assertAt(world(DX, DY, DZ, DYAW, box.cx() + box.hx(), box.cy(), box.cz() - box.hz()), c);
    }

    @Test
    void aGrabsRing_standsOnItsAttackersAxisAtItsBoxsCentreHeight_withItsBoxAndProgress() {
        ClientView v = new ClientView();
        feed(v, 100, strike(1, DEMON, SELF, Fixtures.GRAB_ID, 100, 112));
        KfxMark m = new KfxMark();
        fill().fill(v, world(), SELF, Micros.ofTicks(106), K, m);
        KfxMark.Cue c = m.cue(0);
        assertEquals(CueSheet.RING, c.cell);
        Sweep.Box box = box(Fixtures.GRAB_ID);
        assertAt(new double[] {DX, DY + box.cy(), DZ}, c);
        assertEquals(DYAW, c.yaw);
        assertEquals(box.cx(), c.boxX);
        assertEquals(box.cz(), c.boxZ);
        assertEquals(box.hx(), c.boxHx);
        assertEquals(box.hz(), c.boxHz);
        assertEquals(Fixtures.DEMON_HALF_WIDTH, c.body, "it grows from the body");
        assertEquals(0.5f, c.progress, 1e-6, "half way from its cue to its impact");
    }

    // ------------------------------------------------------------------------------------------------ the Thread

    @Test
    void theThread_liesOnTheNeckBoxFromItsOpeningTick_theKneelingOneWhileCollapsed() {
        ClientView v = new ClientView();
        feed(v, 120, state(DEMON, 120, State.STAGGER, Long.MAX_VALUE));
        World w = world();
        KfxMark m = new KfxMark();
        KfxFill f = fill();

        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(120) - 1, K, m), "not before its opening tick (A8)");
        assertTrue(f.fill(v, w, SELF, Micros.ofTicks(120), K, m));
        KfxMark.Cue c = m.cue(0);
        assertEquals(CueSheet.NECK, c.cell);
        Sweep.Box standing = Fixtures.demon().neck().standing();
        assertAt(world(DX, DY, DZ, DYAW, standing.cx(), standing.cy(), standing.cz()), c);
        assertEquals(DYAW, c.yaw, "its axis at the drawn yaw");
        assertEquals(standing.hx(), c.boxHx);
        assertEquals(0, c.frame);
        assertEquals(1f, c.alpha);
        f.fill(v, w, SELF, Micros.ofTicks(120) + 5 * STEP, K, m);
        assertEquals(1, m.cue(0).frame, "the shimmer steps round its four frames");

        feed(v, 130, state(DEMON, 130, State.COLLAPSED, Long.MAX_VALUE));
        f.fill(v, w, SELF, Micros.ofTicks(131), K, m);
        Sweep.Box kneeling = Fixtures.demon().neck().collapsed();
        assertAt(world(DX, DY, DZ, DYAW, kneeling.cx(), kneeling.cy(), kneeling.cz()), m.cue(0));

        feed(v, 140, state(DEMON, 140, State.NEUTRAL, Long.MIN_VALUE));
        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(141), K, m), "closed");
    }

    @Test
    void theThread_isNeverDimmedWithinTheEngageRange_andNotDrawnBeyondIt() {
        ClientView v = new ClientView();
        feed(v, 120, state(DEMON, 120, State.STAGGER, Long.MAX_VALUE), state(FAR_DEMON, 120, State.STAGGER, 200));
        World w = new World()
                .at(SELF, 12, 64, 0, 200)
                .at(DEMON, 12, 64, 25, 0) // 25 blocks: an opening any Slayer there may take
                .at(FAR_DEMON, 12, 64, -40, 0);
        KfxMark m = new KfxMark();
        fill().fill(v, w, SELF, Micros.ofTicks(121), K, m);
        assertEquals(1, m.cueCount());
        assertEquals(25, m.cue(0).z, EPS);
        assertEquals(1f, m.cue(0).alpha);
        assertEquals(1f, m.cue(0).scale);
    }

    // ------------------------------------------------------------------------------------------------ the clash

    @Test
    void aClashsFlash_isDrawnOnceMidwayBetweenItsFightersAtChestHeight_andKeepsItsFirstFrame() {
        ClientView v = new ClientView();
        feed(v, 120, state(DEMON, 120, State.CLASH, Long.MIN_VALUE));
        feed(v, 120, new Outbox.ClashStarted(5, DEMON, SELF, 120, Micros.ofTicks(121)));
        World w = world();
        KfxMark m = new KfxMark();
        KfxFill f = fill();
        long t = Micros.ofTicks(121) + 10_000;

        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(121) - 1, K, m), "not before its flash");
        f.fill(v, w, SELF, t, K, m);
        assertEquals(1, m.cueCount(), "both tracks hold it; it's drawn once (A11)");
        KfxMark.Cue c = m.cue(0);
        assertEquals(CueSheet.CLASH, c.cell);
        // D-146 (p)'s 0.75 as a literal, so a changed constant fails here
        double chest = 0.75 * (Fixtures.DEMON_HEIGHT + Fixtures.PLAYER_HEIGHT) / 2;
        assertAt(new double[] {(DX + 12) / 2, 64 + chest, (DZ + 0) / 2}, c);
        assertEquals(1f, c.alpha, "your clash");
        assertEquals(t, v.clash(0).shown(), "the first frame that showed it (flaw 13)");

        f.fill(v, w, SELF, t + 50_000, K, m);
        assertEquals(1, m.cueCount());
        assertEquals(t, v.clash(0).shown(), "kept");
        assertFalse(f.fill(v, w, SELF, Micros.ofTicks(121) + 100_000, K, m), "flash_ms 100");
    }

    @Test
    void aBystandersClash_isDimmed() {
        ClientView v = new ClientView();
        feed(v, 120, new Outbox.ClashStarted(6, DEMON, ALLY, 120, Micros.ofTicks(121)));
        KfxMark m = new KfxMark();
        fill().fill(v, world().at(ALLY, 9, 64, -6, 30), SELF, Micros.ofTicks(121), K, m);
        assertEquals(1, m.cueCount());
        assertEquals(0.6f, m.cue(0).alpha);
        assertEquals(0.75f, m.cue(0).scale);
    }

    // ------------------------------------------------------------------------------------------------ whose, which

    @Test
    void aStrikeOnSomeoneElse_drawsDimmedWithinTheEngageRange_andNotBeyondIt() {
        ClientView v = new ClientView();
        feed(
                v,
                100,
                strike(1, DEMON, ALLY, Fixtures.SWIPE_ID, 100, 110),
                strike(2, FAR_DEMON, ALLY, Fixtures.SWIPE_ID, 100, 110));
        World w = new World().at(SELF, 12, 64, 0, 200).at(DEMON, 12, 64, 10, 0).at(FAR_DEMON, 12, 64, -40, 0);
        KfxMark m = new KfxMark();
        KfxFill f = fill();

        f.fill(v, w, SELF, Micros.ofTicks(103), K, m);
        assertEquals(1, m.cueCount(), "every cue the client knows is drawn (spec:557), within the range");
        assertEquals(0.6f, m.cue(0).alpha, "bystander_alpha");
        assertEquals(0.75f, m.cue(0).scale, "bystander_scale");

        feed(v, 101, new Outbox.Involved(1, SELF, 101)); // Rule 2.9
        f.fill(v, w, SELF, Micros.ofTicks(103), K, m);
        assertEquals(1f, m.cue(0).alpha, "involving you: full strength");
        assertEquals(1f, m.cue(0).scale);
    }

    @Test
    void sixteenSlots_fillWithYoursFirst_thenTheNearest() {
        ClientView v = new ClientView();
        World w = new World().at(SELF, 12, 64, 0, 0);
        for (int i = 0; i < 3; i++) { // on you, but far
            int id = 100 + i;
            w.at(id, 12 - 28 - i, 64, 0, 0);
            feed(v, 100, strike(id, id, SELF, Fixtures.SWIPE_ID, 100, 110));
        }
        for (int j = 0; j < 17; j++) { // on your ally, 2 to 18 blocks off, far to near in the view's own order
            int id = 216 - j;
            w.at(id, 12 + 2 + (16 - j), 64, 0, 0);
            feed(v, 100, strike(id, id, ALLY, Fixtures.SWIPE_ID, 100, 110));
        }
        KfxMark m = new KfxMark();
        fill().fill(v, w, SELF, Micros.ofTicks(103), K, m);

        assertEquals(KfxMark.CUES, m.cueCount());
        int yours = 0;
        double farthest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < m.cueCount(); i++) {
            KfxMark.Cue c = m.cue(i);
            if (c.alpha == 1f) {
                yours++;
            } else {
                farthest = Math.max(farthest, c.x);
            }
        }
        assertEquals(3, yours, "yours, however far");
        double hx = box(Fixtures.SWIPE_ID).hx(); // each glint sits hx toward -x of its attacker (yaw 0)
        assertEquals(12 + 2 + 12 - hx, farthest, EPS, "the 13 nearest of the 17 others");
    }

    @Test
    void nothingIsDrawnForAnAttackerTheClientLacks_andAnEmptyFrameHoldsNone() {
        ClientView v = new ClientView();
        KfxMark m = new KfxMark();
        KfxFill f = fill();
        assertFalse(f.fill(v, world(), SELF, Micros.ofTicks(103), K, m));
        assertEquals(0, m.cueCount());
        feed(v, 100, strike(1, 99, SELF, Fixtures.SWIPE_ID, 100, 110));
        assertFalse(f.fill(v, world(), SELF, Micros.ofTicks(103), K, m), "no entity 99 here");
        assertEquals(0, m.cueCount());
    }

    @Test
    void warm_itAllocatesNothingAcrossAThousandFrames() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assertTrue(
                bean instanceof com.sun.management.ThreadMXBean t && t.isThreadAllocatedMemorySupported(),
                "this JVM can't count a thread's allocations: the pinned JDK 25 (HotSpot) can, so this check never skips");
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) bean;
        ClientView v = new ClientView();
        World w = world().at(ALLY, 9, 64, -6, 30).at(FAR_DEMON, 14, 64, 4, 90);
        feed(
                v,
                100,
                strike(1, DEMON, SELF, Fixtures.HEAVY_ID, 100, 114),
                strike(2, DEMON, SELF, Fixtures.SWIPE_ID, 100, 110),
                strike(3, FAR_DEMON, ALLY, Fixtures.SWEEP_ID, 100, 110),
                strike(4, FAR_DEMON, SELF, Fixtures.GRAB_ID, 100, 112),
                state(DEMON, 100, State.STAGGER, Long.MAX_VALUE),
                new Outbox.ClashStarted(5, DEMON, SELF, 100, Micros.ofTicks(102)));
        feed(v, 103, new Outbox.AttackCancelled(2, 104, Outbox.AttackCancelled.FEINT));
        KfxFill f = fill();
        KfxMark m = new KfxMark();
        long from = Micros.ofTicks(102);
        for (int i = 0; i < 1000; i++) {
            f.fill(v, w, SELF, from + i * 100, K, m); // warm: the JIT done
        }
        long id = Thread.currentThread().threadId();
        long before = t.getThreadAllocatedBytes(id);
        for (int i = 0; i < 1000; i++) {
            f.fill(v, w, SELF, from + i * 100, K, m); // 100 µs apart: 0.1 s of glints, a ring, a Thread, the flash
        }
        long after = t.getThreadAllocatedBytes(id);
        assertTrue(m.cueCount() >= 5, "it drew the frame: " + m.cueCount());
        assertTrue(after - before < 1024, "allocated " + (after - before) + " bytes over 1,000 frames");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static KfxFill fill() {
        KfxFill f = new KfxFill();
        f.data(Fixtures.demonAttacks(), Fixtures.demon(), Fixtures.PLAYER_HEIGHT, ENGAGE, PERIOD);
        return f;
    }

    /** The demon turned 37° and you, 3.5 blocks off. */
    private static World world() {
        return new World().at(DEMON, DX, DY, DZ, DYAW).at(SELF, 12, 64, 0, 200);
    }

    private static Sweep.Box box(String attack) {
        Sweep s = Fixtures.demonAttacks().get(attack).sweep();
        if (s == null) {
            throw new AssertionError(attack + " has no sweep");
        }
        return s.box();
    }

    /** A strike by {@code attacker} on {@code target}: its windup and cue from {@code cue}, its impact at {@code impact}. */
    private static Outbox.AttackStarted strike(
            long id, int attacker, int target, String attack, long cue, long impact) {
        return new Outbox.AttackStarted(id, attacker, target, attack, cue - 1, cue, cue, impact, id, true);
    }

    private static Outbox.MobState state(int mob, long tick, State s, long threadUntil) {
        return new Outbox.MobState(mob, tick, s, threadUntil, 100, 0, tick, 0);
    }

    private static void feed(ClientView v, long tick, Outbox.Event... events) {
        v.accept(new NetMessage.Tick(tick, List.of(events), null), SELF, id -> true);
    }

    /** Core's {@code Frame} by hand: the point ({@code lx, ly, lz}) of a frame at the feet facing {@code yaw}. */
    private static double[] world(double x, double y, double z, double yawDeg, double lx, double ly, double lz) {
        double s = Math.sin(Math.toRadians(yawDeg));
        double c = Math.cos(Math.toRadians(yawDeg));
        return new double[] {x - lx * c - lz * s, y + ly, z - lx * s + lz * c
        }; // right (−cos, −sin), forward (−sin, cos)
    }

    private static double[] world(DrawnFrame f, double lx, double ly, double lz) {
        return world(f.x, f.y, f.z, f.bodyYaw, lx, ly, lz);
    }

    private static void assertAt(double[] at, KfxMark.Cue c) {
        assertEquals(at[0], c.x, EPS, "x");
        assertEquals(at[1], c.y, EPS, "y");
        assertEquals(at[2], c.z, EPS, "z");
    }

    /** The client's bodies: each at its drawn frame; ids from {@link #DEMON} up are its combatants. */
    private static final class World implements KfxFill.Bodies {
        private final int[] ids = new int[64];
        private final double[] xs = new double[64];
        private final double[] ys = new double[64];
        private final double[] zs = new double[64];
        private final float[] yaws = new float[64];
        private int n;
        private final int[] demons = new int[64];
        private int demonCount;

        World at(int id, double x, double y, double z, float yaw) {
            ids[n] = id;
            xs[n] = x;
            ys[n] = y;
            zs[n] = z;
            yaws[n] = yaw;
            n++;
            if (id >= DEMON) {
                demons[demonCount++] = id;
            }
            return this;
        }

        @Override
        public int combatants() {
            return demonCount;
        }

        @Override
        public int combatant(int i) {
            return demons[i];
        }

        @Override
        public boolean drawn(int id, DrawnFrame out) {
            for (int i = 0; i < n; i++) {
                if (ids[i] == id) {
                    out.entity(xs[i], ys[i], zs[i], yaws[i], 0);
                    return true;
                }
            }
            return false;
        }
    }
}
