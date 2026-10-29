package dev.champ.kimetsu.client.present;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.strike.StrikeView;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Where the stand-in's body is drawn (spec:534, 5d-3a Step 3): on the path of the newest strike to have locked, as the
 * server's mob rides it (D-140): blended onto it over {@code blend_in_ticks} from its lock, from where the strike before
 * left the body or else from the entity's own position (the client's entity trails the server's, D-139), on it through
 * its strike, then blended back from its last place on the path to the entity's own position over
 * {@code blend_back_ticks} of model time, from its recovery's end or its cancel. No snap. The M1 leap's real path: a
 * 12-tick windup that lunges 2 blocks, its cue at the windup's start.
 */
class StandInPoseTest {
    private static final Feel FEEL = new Feel(3, 3, 3);
    private static final FrameData LEAP = Fixtures.leap();
    private static final double EYE = 1.74;

    private static long t(double ticks) {
        return Math.round(ticks * Micros.PER_TICK);
    }

    private static long t(long ticks) {
        return Micros.ofTicks(ticks);
    }

    /** The leap from tick {@code start}, locked at its cue (its windup's start) at the origin facing +Z. */
    private static StrikeView leapPath(long id, long start) {
        long impact = start + LEAP.windup();
        return new StrikeView(LEAP)
                .set(id, LEAP, start, LEAP.windup(), impact - LEAP.cue().lead(), impact, true, 0, 64, 0, EYE, 0, 1);
    }

    private static final class Strikes implements StrikeSource {
        private record Span(
                long id,
                long start,
                long lock,
                long impact,
                long end,
                long follow,
                @Nullable StrikeView path) {}

        private final List<Span> spans = new ArrayList<>();

        Strikes add(long id, long start, long end, @Nullable StrikeView path) {
            return add(id, start, start, end, path);
        }

        /** A strike locked at {@code lock}: its windup's start, or later when the beat lock stretched its windup. */
        Strikes add(long id, long start, long lock, long end, @Nullable StrikeView path) {
            long impact = start + LEAP.windup();
            spans.add(new Span(
                    id, t(start), t(lock), t(impact), t(end), Micros.ofTicks(LEAP.active() + LEAP.recovery()), path));
            return this;
        }

        @Override
        public boolean newest(long at, StrikeSpan out) {
            Span best = null;
            for (Span s : spans) {
                if (s.start() <= at && (best == null || s.start() > best.start())) {
                    best = s;
                }
            }
            if (best == null) {
                return false;
            }
            out.set(best.id(), best.start(), best.lock(), best.impact(), best.end(), best.follow(), best.path());
            return true;
        }
    }

    private static Placement place(StrikeSource s, long at) {
        Placement out = new Placement();
        new Poser().place(s, at, FEEL, out);
        return out;
    }

    private static Pose on(StrikeView path, long at) {
        Pose p = new Pose();
        path.attackerAtMicros(at, p);
        return p;
    }

    private static final long END = 100L + LEAP.windup() + LEAP.active() + LEAP.recovery(); // its recovery's end

    @Test
    void throughItsStrikeTheBodyIsOnThePath() {
        StrikeView path = leapPath(1, 100);
        Placement p = place(new Strikes().add(1, 100, END, path), t(110));
        assertEquals(1f, p.weight, 1e-6f);
        Pose want = on(path, t(110));
        assertEquals(want.z(), p.z, 1e-9);
        assertTrue(p.z > 0.5, "partway into its 2-block lunge: " + p.z);
    }

    @Test
    void blendBackReachesTheEntityAtExactlyBlendBackTicks_withNoJumpAtTheEnd() {
        StrikeView path = leapPath(1, 100);
        Strikes s = new Strikes().add(1, 100, END, path);
        Pose last = on(path, t(END));
        Placement before = place(s, t(END) - 1);
        Placement atEnd = place(s, t(END));
        assertEquals(1f, before.weight, 1e-6f);
        assertEquals(1f, atEnd.weight, 1e-6f, "its last place on the path, at its end");
        assertEquals(before.z, atEnd.z, 1e-6, "no jump at the strike's end");
        assertEquals(last.z(), atEnd.z, 1e-9);
        Placement half = place(s, t(END + 1.5));
        assertEquals(0.5f, half.weight, 1e-6f);
        assertEquals(last.z(), half.z, 1e-9, "from where the path left it");
        assertTrue(place(s, t(END + 3) - 1).weight > 0, "still blending a µs before blend_back_ticks");
        assertEquals(0f, place(s, t(END + 3)).weight, 1e-6f, "on the entity at blend_back_ticks exactly");
        assertEquals(0f, place(s, t(END + 40)).weight, 1e-6f);
    }

    /** What the eye sees, with the entity standing a block past the lunge's end: no jump anywhere around the end. */
    @Test
    void theDrawnPositionNeverJumps() {
        StrikeView path = leapPath(1, 100);
        Strikes s = new Strikes().add(1, 100, END, path);
        double entityZ = on(path, t(END)).z() + 1;
        double prev = Double.NaN;
        for (long u = t(END - 2); u <= t(END + 5); u += 1000) {
            Placement p = place(s, u);
            double drawn = entityZ + (p.z - entityZ) * p.weight;
            if (!Double.isNaN(prev)) {
                assertTrue(Math.abs(drawn - prev) < 0.01, "jumped " + (drawn - prev) + " at " + u);
            }
            prev = drawn;
        }
    }

    @Test
    void aCancelledStrikeBlendsBackFromWhereItsCancelLeftIt() {
        StrikeView path = leapPath(1, 100);
        Strikes s = new Strikes().add(1, 100, 108, path); // a cancel at 108, mid-lunge
        Placement p = place(s, t(109.5));
        assertEquals(0.5f, p.weight, 1e-6f);
        assertEquals(on(path, t(108)).z(), p.z, 1e-9);
        assertEquals(0f, place(s, t(111)).weight, 1e-6f);
    }

    /**
     * A string's next strike starts as the last one's active frames end; until it locks, the server's mob still rides the
     * last one's path (Rule 7.13), so the body does: here through the last one's recovery and its blend back, the next
     * one broken before its cue (a Feint).
     */
    @Test
    void untilTheNextStrikeOfAStringLocks_theLastOneCarriesTheBody() {
        StrikeView first = leapPath(1, 100);
        long handover = 100L + LEAP.windup() + LEAP.active();
        Strikes s = new Strikes().add(1, 100, END, first).add(2, handover, handover + 5, null);
        Placement at = place(s, t(handover));
        assertEquals(1f, at.weight, 1e-6f);
        assertEquals(on(first, t(handover)).z(), at.z, 1e-9);
        assertEquals(1f, place(s, t(handover + 1.5)).weight, 1e-6f);
        assertEquals(1f, place(s, t(handover + 10)).weight, 1e-6f, "after the next one's cancel too");
        assertEquals(1f, place(s, t(END)).weight, 1e-6f, "at the last one's recovery's end");
        assertEquals(0.5f, place(s, t(END + 1.5)).weight, 1e-6f);
        assertEquals(0f, place(s, t(END + 3)).weight, 1e-6f);
    }

    /**
     * The client's entity trails the server's by its interpolation, so a strike from rest locked where the server had
     * it, 0.35 blocks ahead at a walk (D-139's trace): the body goes there over {@code blend_in_ticks}, not at once.
     */
    @Test
    void theBodyBlendsOntoThePathFromItsEntity_overBlendInTicksFromItsLock() {
        StrikeView path = leapPath(1, 100);
        Strikes s = new Strikes().add(1, 100, END, path);
        assertEquals(0f, place(s, t(100)).weight, 1e-6f, "on its entity at its lock");
        Placement half = place(s, t(101.5));
        assertEquals(0.5f, half.weight, 1e-6f);
        assertEquals(on(path, t(101.5)).z(), half.z, 1e-9, "toward the path's place now");
        assertTrue(place(s, t(103) - 1).weight < 1, "still blending a µs before blend_in_ticks");
        assertEquals(1f, place(s, t(103)).weight, 1e-6f, "on the path at blend_in_ticks exactly");
        double entityZ = -0.35; // where the client's entity trails
        double prev = Double.NaN;
        for (long u = t(99); u <= t(105); u += 1000) {
            Placement p = place(s, u);
            double drawn = entityZ + (p.z - entityZ) * p.weight;
            if (!Double.isNaN(prev)) {
                assertTrue(Math.abs(drawn - prev) < 0.01, "jumped " + (drawn - prev) + " at " + u);
            }
            prev = drawn;
        }
    }

    /** The beat lock stretched its windup, so its cue, and its lock, come after its windup's start. */
    @Test
    void aStretchedWindupBlendsOntoThePathFromItsLock_notItsStart() {
        StrikeView path = leapPath(1, 100);
        Strikes s = new Strikes().add(1, 97, 100, END, path);
        assertEquals(0f, place(s, t(99)).weight, 1e-6f, "before its lock");
        assertEquals(0f, place(s, t(100)).weight, 1e-6f);
        assertEquals(0.5f, place(s, t(101.5)).weight, 1e-6f);
        assertEquals(1f, place(s, t(103)).weight, 1e-6f);
    }

    /** A string's next strike that locks as it begins takes the body from the last one's path at once (D-139: 0.000). */
    @Test
    void aStringsNextStrikeLockedAtItsStartTakesOverWithoutABlendIn() {
        StrikeView first = leapPath(1, 100);
        long handover = 100L + LEAP.windup() + LEAP.active();
        StrikeView next = leapPath(2, handover);
        Strikes s = new Strikes().add(1, 100, END, first).add(2, handover, handover + 40, next);
        assertEquals(1f, place(s, t(handover)).weight, 1e-6f);
        assertEquals(1f, place(s, t(handover + 1)).weight, 1e-6f);
    }

    /**
     * The leap from tick {@code start}, its windup stretched {@code stretch} ticks past its cue lead (the beat lock, inside
     * a string), locked at its cue where the server held its attacker: at {@code where}, facing +Z.
     */
    private static StrikeView nextPath(long id, long start, int stretch, Pose where) {
        int windup = LEAP.windup() + stretch;
        long impact = start + windup;
        return new StrikeView(LEAP)
                .set(
                        id,
                        LEAP,
                        start,
                        windup,
                        impact - LEAP.cue().lead(),
                        impact,
                        true,
                        where.x(),
                        where.y(),
                        where.z(),
                        EYE,
                        0,
                        1);
    }

    /**
     * The server carries its mob along a string's strike until the next one locks and locks that one where the first
     * left it (Rule 7.13, {@code CombatWorld.strikePose}); below about 100 BPM a string's next swipe winds up past its cue
     * lead, so it locks ticks after it begins. The body rides the first's path until the lock and the next one's from
     * there, the same whether the lock's message came early or only at the lock (one that comes after it is D-140's known
     * limit, for build step 5's two-client check).
     */
    @Test
    void aStringsNextStrikeLockedLateTakesOverAtItsLock_itsMessageEarlyOrAtTheLock() {
        StrikeView first = leapPath(1, 100);
        long handover = 100L + LEAP.windup() + LEAP.active();
        long lock = handover + 2;
        StrikeView next = nextPath(2, handover, 2, on(first, t(lock)));
        Strikes early = new Strikes().add(1, 100, END, first).add(2, handover, lock, handover + 40, next);
        Strikes late = new Strikes().add(1, 100, END, first).add(2, handover, lock, handover + 40, null);
        for (long u = t(handover); u < t(lock); u += 1000) {
            Placement p = place(early, u);
            Placement q = place(late, u);
            assertEquals(1f, p.weight, 1e-6f, "on the first's path until the lock, at " + u);
            assertEquals(on(first, u).z(), p.z, 1e-9);
            assertEquals(p.weight, q.weight, 1e-6f, "the same before its message came, at " + u);
            assertEquals(p.z, q.z, 1e-9);
        }
        assertEquals(1f, place(early, t(lock)).weight, 1e-6f, "the next takes over at its lock");
        assertEquals(on(next, t(lock + 1)).z(), place(early, t(lock + 1)).z, 1e-9);
        double entityZ = -0.35; // where the client's entity trails
        double prev = Double.NaN;
        for (long u = t(handover - 2); u <= t(lock + 4); u += 1000) {
            Placement p = place(u < t(lock) ? late : early, u);
            double drawn = entityZ + (p.z - entityZ) * p.weight;
            if (!Double.isNaN(prev)) {
                assertTrue(Math.abs(drawn - prev) < 0.01, "jumped " + (drawn - prev) + " at " + u);
            }
            prev = drawn;
        }
    }

    /**
     * A strike begun while the last one blends back from its cancel (a Feint, the rest of its string standing) leaves
     * that blend running until it locks, then blends onto its path from that blend's weight: the server held its mob at
     * the cancel and locks the next strike there. One begun after the blend leaves the body to its entity.
     */
    @Test
    void aStrikeBegunInTheLastOnesBlendBackGoesOnFromIt() {
        StrikeView first = leapPath(1, 100);
        Pose held = on(first, t(108)); // a cancel mid-lunge
        StrikeView next = nextPath(2, 109, 0, held);
        Strikes early = new Strikes().add(1, 100, 108, first).add(2, 109, 109 + 40, next);
        Strikes late = new Strikes().add(1, 100, 108, first).add(2, 109, 109 + 40, null);
        Placement before = place(late, t(109.5));
        assertEquals(0.5f, before.weight, 1e-6f, "the last one's blend back, before the next one's message");
        assertEquals(held.z(), before.z, 1e-9);
        assertEquals(2 / 3f, place(early, t(109)).weight, 1e-6f, "from the blend back's weight at the lock");
        assertEquals(2 / 3f + 1 / 3f * 0.5f, place(early, t(110.5)).weight, 1e-6f);
        assertEquals(1f, place(early, t(112)).weight, 1e-6f, "on its path at blend_in_ticks");
        double entityZ = held.z() + 1;
        double prev = Double.NaN;
        for (long u = t(107); u <= t(113); u += 1000) {
            Placement p = place(early, u);
            double drawn = entityZ + (p.z - entityZ) * p.weight;
            if (!Double.isNaN(prev)) {
                assertTrue(Math.abs(drawn - prev) < 0.02, "jumped " + (drawn - prev) + " at " + u);
            }
            prev = drawn;
        }
        StrikeView stretched = nextPath(2, 109, 2, held); // locks at 111, its message here early
        Strikes waiting = new Strikes().add(1, 100, 108, first).add(2, 109, 111, 109 + 40, stretched);
        assertEquals(0.5f, place(waiting, t(109.5)).weight, 1e-6f, "the blend back now, not as it will be at the lock");
        Strikes after = new Strikes().add(1, 100, 108, first).add(2, 112, 112 + 40, null);
        assertEquals(0f, place(after, t(112)).weight, 1e-6f, "begun after the blend back: the entity's own");
    }

    /** A cancel inside the blend in blends back from the weight the body had reached: no jump forward (5d-3a's review). */
    @Test
    void aStrikeCancelledWhileBlendingOntoItsPathBlendsBackFromWhereItGot() {
        StrikeView path = leapPath(1, 100);
        Strikes s = new Strikes().add(1, 100, 101, path); // a Posture break a tick into its windup
        assertEquals(1 / 3f, place(s, t(101)).weight, 1e-6f, "the third of the way it had come");
        assertEquals(1 / 6f, place(s, t(102.5)).weight, 1e-6f);
        assertEquals(0f, place(s, t(104)).weight, 1e-6f);
        double entityZ = -0.35;
        double prev = Double.NaN;
        for (long u = t(99); u <= t(105); u += 1000) {
            Placement p = place(s, u);
            double drawn = entityZ + (p.z - entityZ) * p.weight;
            if (!Double.isNaN(prev)) {
                assertTrue(Math.abs(drawn - prev) < 0.01, "jumped " + (drawn - prev) + " at " + u);
            }
            prev = drawn;
        }
    }

    /**
     * The server's mob rides the newest strike that locked, so a string's next strike broken before its cue leaves the
     * body on the one before it, and the strike after takes over from that one (5d-3a's review).
     */
    @Test
    void aStrikeThatNeverLockedIsPassedOverForTheOneBeforeIt() {
        StrikeView first = leapPath(1, 100);
        long handover = 100L + LEAP.windup() + LEAP.active();
        long third = handover + 10;
        StrikeView next = nextPath(3, third, 0, on(first, t(third)));
        Strikes s = new Strikes()
                .add(1, 100, END, first)
                .add(2, handover, handover + 5, null) // a Feint broken before its cue
                .add(3, third, third + 40, next);
        Placement still = place(s, t(handover + 7));
        assertEquals(1f, still.weight, 1e-6f, "still on the first's path");
        assertEquals(on(first, t(handover + 7)).z(), still.z, 1e-9);
        Placement over = place(s, t(third));
        assertEquals(1f, over.weight, 1e-6f, "the third takes over from the first at once");
        assertEquals(on(next, t(third)).z(), over.z, 1e-9);
        StrikeView lateNext = nextPath(3, third, 3, on(first, t(third + 3)));
        Strikes lateLock = new Strikes()
                .add(1, 100, END, first)
                .add(2, handover, handover + 5, null)
                .add(3, third, third + 3, third + 40, lateNext); // its message early, its lock 3 ticks in
        Placement waiting = place(lateLock, t(third + 1));
        assertEquals(1f, waiting.weight, 1e-6f, "past two strikes not locked yet, to the first's path");
        assertEquals(on(first, t(third + 1)).z(), waiting.z, 1e-9);
    }

    /** Where the strike before left the body at a lock sets the blend in's start. */
    @Test
    void theBodyBlendsOntoAPathFromTheWeightTheStrikeBeforeLeftItAt() {
        StrikeView first = leapPath(1, 100);
        long handover = 100L + LEAP.windup() + LEAP.active();
        StrikeView next = nextPath(2, handover, 0, on(first, t(handover)));
        Strikes justEnded = new Strikes().add(1, 100, handover, first).add(2, handover, handover + 40, next);
        assertEquals(1f, place(justEnded, t(handover)).weight, 1e-6f, "the last one ended as this began: all of it");
        Strikes longEnded = new Strikes().add(1, 100, handover - 3, first).add(2, handover, handover + 40, next);
        assertEquals(0f, place(longEnded, t(handover)).weight, 1e-6f, "its blend back over: from the entity");
        Strikes ended = new Strikes().add(1, 100, handover - 6, first).add(2, handover, handover + 40, next);
        assertEquals(0f, place(ended, t(handover)).weight, 1e-6f, "long over: from the entity");
        Strikes unplaced = new Strikes().add(1, 100, END, null).add(2, handover, handover + 40, next);
        assertEquals(0f, place(unplaced, t(handover)).weight, 1e-6f, "one that never locked held nothing");
        assertEquals(0.5f, place(unplaced, t(handover + 1.5)).weight, 1e-6f);
    }

    /** Where the top of the body, (0, 1, 0), goes under a right-handed turn about +X (JOML's, so {@code Axis.XP}'s). */
    private static double topZ(StandInPose p) {
        return Math.sin(Math.toRadians(p.pitchDegrees()));
    }

    private static StandInPose posed(Stance s) {
        StandInPose p = new StandInPose();
        p.blend(StandInPoses.of(s), StandInPoses.of(s), 1);
        return p;
    }

    /**
     * Vanilla's {@code setupRotations} frame faces −Z, so a forward lean takes the top toward −Z. D-139's sheet showed
     * every pose leaned the wrong way (the knockdown on its face) while the renderer turned by the lean itself.
     */
    @Test
    void eachPoseLeansTheWayItsDrawingSays() {
        assertTrue(topZ(posed(Stance.KNOCKDOWN)) > 0.99, "flat on its back: its top behind its feet");
        assertTrue(topZ(posed(Stance.STAGGER)) > 0.2, "the torso thrown back");
        assertTrue(topZ(posed(Stance.COLLAPSED)) < -0.2, "slumped forward on its knees");
        assertTrue(topZ(posed(Stance.CLASH)) < -0.1, "leaning into the clash");
        assertTrue(topZ(posed(Stance.HOLD)) < -0.05, "leaning into the hold");
        assertEquals(0, topZ(posed(Stance.GUARD)), 1e-9);
        assertEquals(0, topZ(posed(Stance.NEUTRAL)), 1e-9);
    }

    @Test
    void aStrikeThatNeverLockedLeavesTheBodyToTheEntity() {
        Strikes s = new Strikes().add(1, 100, 105, null); // a Feint broken before its cue
        assertEquals(0f, place(s, t(102)).weight, 1e-6f);
        assertEquals(0f, place(s, t(106)).weight, 1e-6f);
        assertEquals(0f, place(new Strikes(), t(106)).weight, 1e-6f);
    }
}
