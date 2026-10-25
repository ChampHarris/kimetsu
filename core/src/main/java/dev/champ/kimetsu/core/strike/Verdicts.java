package dev.champ.kimetsu.core.strike;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.hit.Combo;
import dev.champ.kimetsu.core.hit.Facing;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.hit.SweepBounds;
import dev.champ.kimetsu.core.judge.DeflectJudge;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.machine.TimedAnswers;
import dev.champ.kimetsu.core.meter.Fixed;
import java.util.List;

/**
 * One NPC strike's verdict on one defender, as of its impact (Rules 2.6, 2.10, 6.1–6.3; D-039): the one function the
 * server's resolver and the predicting client both run, on the defender's state at the impact and its pose history.
 *
 * <ol>
 *   <li>Is the defender in the sweep at the impact: its box (pose interpolated at I) inside the strike's box placed
 *       where the strike has carried its attacker by then, in the locked direction (Rule 7.13), in sight of its eyes
 *       (Rule 7.3)? Every answer needs this
 *       (D-039).
 *   <li>Not in it: a miss, or for a red or purple strike a cleared dodge if a Step started between the cue and the
 *       impact from inside the sweep (a counterfactual: it would have been hit), or a Jump did and the body, dropped
 *       back to where its feet were then, would still be inside (its height cleared it: Rule 6.2's Jump over a low
 *       sweep).
 *   <li>In it, against a white strike, with the attacker inside the defender's front arc (its look yaw at I): a claimed
 *       press is a Perfect or a deflect ({@link DeflectJudge#answer}) if the defender can still guard at I (standing,
 *       guarding or in the radial: an attack, a Step or a stun begun after the claim gives it up; Champ, D-043);
 *       otherwise held guard blocks.
 *   <li>Anything else lands: a grab for a grab, a launch for a launching window, else a hit.
 * </ol>
 *
 * <p>Against a white strike the verdict also carries the guard's record for the fight log (D-089): each gate above and
 * the defender's {@link DeflectJudge.Attempt}. Also the Pommel Strike's timed answer (Rule 5.2), {@link
 * #pommelInterrupt}, and the effects a verdict logs, {@link #effects}. Judging allocates nothing; the effects are a
 * decision's records.
 */
public final class Verdicts {
    /** {@link #pommelInterrupt}'s answer when no Pommel Strike interrupts. */
    public static final long NONE = Long.MIN_VALUE;

    private final HitRules hits;
    private final StrikeRules strikes;
    private final TimingRules timing;
    private final RhythmRules rhythm;
    private final SweepBounds bounds = new SweepBounds();
    private final Pose pose = new Pose();
    private final Pose other = new Pose();

    public Verdicts(HitRules hits, StrikeRules strikes, TimingRules timing, RhythmRules rhythm) {
        this.hits = hits;
        this.strikes = strikes;
        this.timing = timing;
        this.rhythm = rhythm;
    }

    /**
     * The verdict of strike {@code s} on a defender whose state at the impact is {@code d} ({@code Timeline.stateAt}),
     * whose poses are {@code poses} and whose box is {@code halfWidth} wide each way and {@code height} tall from its
     * feet, against an attacker of {@code attackerMaxPosture}: into {@code out}, and returned.
     */
    public Verdict.Kind judge(
            StrikeView s,
            Combatant d,
            PoseRing poses,
            double halfWidth,
            double height,
            Sight sight,
            double attackerMaxPosture,
            Verdict out) {
        out.reset();
        FrameData f = s.attack();
        Sweep sweep = f.sweep();
        if (d.state() == State.DEAD || sweep == null || f.hits().isEmpty()) {
            return out.kind;
        }
        bounds.place(sweep, s.x(), s.y(), s.z(), s.eye(), s.yaw(), 0); // a beat-locked strike's sweep is level
        poses.at(s.impactMicros(), pose);
        double lookYaw = pose.yaw();
        double px = pose.x();
        double pz = pose.z();
        Cue.Type cue = f.cue().type();
        boolean in = inside(pose, halfWidth, height, sight);
        if (cue == Cue.Type.WHITE) {
            guardRecord(s, d, lookYaw, px, pz, in, out);
        }
        if (!in) {
            if ((cue == Cue.Type.RED || cue == Cue.Type.PURPLE)
                    && dodged(s, d, pose, poses, halfWidth, height, sight)) {
                out.kind = Verdict.Kind.CLEARED;
                out.rhythm = rhythm.dodge();
            }
            return out.kind;
        }
        HitWindow w = f.hits().get(0);
        if (cue == Cue.Type.WHITE && out.inArc) {
            int assist = d.assistLevel();
            double scale = assist >= 3 ? hits.autoRhythmScale() : 1;
            long claim = out.stateOk ? d.deflects().claimOf(s.id()) : DeflectJudge.UNCLAIMED;
            switch (DeflectJudge.answer(claim, s.impactMicros(), timing, assist)) {
                case PERFECT -> {
                    out.kind = Verdict.Kind.PERFECT;
                    out.rhythm = rhythm.perfect();
                    out.attackerPosture = strikes.perfectAttackerShare()
                            * attackerMaxPosture
                            * d.rhythm().multiplier()
                            * scale;
                    out.attackerRecoveryTicks = s.last() ? strikes.perfectRecoveryTicks() : 0;
                    return out.kind;
                }
                case DEFLECT -> {
                    out.kind = Verdict.Kind.DEFLECT;
                    out.rhythm = rhythm.deflect();
                    out.defenderPosture = strikes.deflectDefenderPosture();
                    out.attackerPosture = strikes.deflectAttackerPosture() * scale;
                    return out.kind;
                }
                case NONE -> {
                    if (d.state() == State.GUARD) {
                        out.kind = Verdict.Kind.BLOCK;
                        out.defenderPosture = w.blockPosture();
                        return out.kind;
                    }
                }
            }
        }
        out.kind = f.attackClass() == AttackClass.GRAB
                ? Verdict.Kind.GRAB
                : w.launch() ? Verdict.Kind.LAUNCH : Verdict.Kind.HIT;
        return out.kind;
    }

    /**
     * Rule 5.2: the moment a Pommel Strike of the player whose state at the impact is {@code d} interrupts strike
     * {@code s} (a grab or a charged red), or {@link #NONE}: its start plus its windup falls after the cue and before
     * the impact, and its sweep then, from the player's pose and look, holds the attacker's box then (where the strike
     * has carried it, {@code attackerHalfWidth} wide each way and {@code attackerHeight} tall), in sight. The earliest
     * such moment.
     */
    public long pommelInterrupt(
            StrikeView s,
            Combatant d,
            PoseRing poses,
            double standingEye,
            FrameData pommel,
            double attackerHalfWidth,
            double attackerHeight,
            Sight sight) {
        FrameData f = s.attack();
        Sweep reach = pommel.sweep();
        boolean breakable = f.attackClass() == AttackClass.GRAB
                || (f.cue().type() == Cue.Type.RED && f.cue().charged());
        if (!breakable || reach == null || d.state() == State.DEAD) {
            return NONE;
        }
        long windup = Micros.ofTicks(pommel.windup());
        long best = NONE;
        TimedAnswers a = d.answers();
        for (int i = 0; i < a.count(TimedAnswers.Kind.POMMEL); i++) {
            long t = a.at(TimedAnswers.Kind.POMMEL, i) + windup;
            if (t < s.cueMicros() || t >= s.impactMicros()) {
                continue;
            }
            poses.at(t, pose);
            bounds.place(
                    reach,
                    pose.x(),
                    pose.y(),
                    pose.z(),
                    pose.eyeOr(standingEye),
                    pose.yaw(),
                    SweepBounds.pitchFor(reach, pose.pitch(), hits));
            s.attackerAtMicros(t, other);
            if (bounds.holds(
                            other.x() - attackerHalfWidth,
                            other.y(),
                            other.z() - attackerHalfWidth,
                            other.x() + attackerHalfWidth,
                            other.y() + attackerHeight,
                            other.z() + attackerHalfWidth)
                    && bounds.seen(sight, hits)
                    && (best == NONE || t < best)) {
                best = t;
            }
        }
        return best;
    }

    /**
     * The effects verdict {@code v} of strike {@code attack} logs on its defender at the impact, in order, into {@code
     * out} (cleared first): its Rhythm, then the Posture an answer or a block costs the defender, the block, and for a
     * strike that lands the hit, its Posture, its push, then the launch or the grab. {@code comboCount} is the landed
     * hit's place in the defender's combo (Rule 7.5, {@code Combo.land}), which scales its hitstun. A hit or a launch
     * whose window pushes (knockback, or a launch's lift) pushes along the unit direction {@code (dirX, dirZ)} from tick
     * {@code fromTick} (Rule 7.10), splatting on a wall for {@code splat_window_ticks} when its window wall-splats (Rule
     * 9.4), its Tech taking Jumps stamped after {@code pressesFromMicros} (D-081); a grab never pushes. The server logs
     * these, and sends each to the player's client
     * ({@code Outbox.PlayerEffect}). A client that logs them ahead, to predict its own state, must take them back when
     * the server's arrive (slice 5's provisional effects), or they apply twice.
     */
    public void effects(
            Verdict v,
            FrameData attack,
            int comboCount,
            double dirX,
            double dirZ,
            long fromTick,
            long pressesFromMicros,
            List<Effect> out) {
        out.clear();
        HitWindow w = attack.hits().get(0);
        switch (v.kind()) {
            case PERFECT, DEFLECT -> {
                out.add(new Effect.RhythmDelta(v.rhythm()));
                if (v.defenderPosture() > 0) {
                    out.add(new Effect.PostureDamage(Fixed.of(v.defenderPosture()), true));
                }
            }
            case CLEARED -> out.add(new Effect.RhythmDelta(v.rhythm()));
            case BLOCK -> {
                out.add(new Effect.Blocked());
                out.add(new Effect.PostureDamage(Fixed.of(v.defenderPosture()), true));
            }
            case HIT, LAUNCH, GRAB -> {
                out.add(new Effect.Hit(attack.attackClass(), Combo.hitstun(w.hitstun(), comboCount, hits)));
                if (w.posture() > 0) {
                    out.add(new Effect.PostureDamage(Fixed.of(w.posture()), true));
                }
                if (v.kind() != Verdict.Kind.GRAB && (w.knockback() > 0 || w.launch())) {
                    out.add(new Effect.Push(
                            dirX,
                            dirZ,
                            w.knockback(),
                            strikes.knockbackTicks(),
                            w.launch() ? strikes.launchUp() : 0,
                            fromTick,
                            w.wallSplat() ? fromTick + strikes.splatWindowTicks() : Effect.Push.NO_SPLAT,
                            pressesFromMicros));
                }
                if (v.kind() == Verdict.Kind.LAUNCH) {
                    out.add(new Effect.Knockdown());
                }
                if (v.kind() == Verdict.Kind.GRAB) {
                    out.add(new Effect.Grabbed(w.grabDrain()));
                }
            }
            case MISS -> {}
        }
    }

    /**
     * White strike {@code s}'s guard record on {@code d} into {@code out}: in the sweep ({@code in}), the attacker inside
     * the front arc of a defender at {@code (px, pz)} looking along {@code lookYaw}, able to guard, and its attempt.
     */
    private void guardRecord(StrikeView s, Combatant d, double lookYaw, double px, double pz, boolean in, Verdict out) {
        DeflectJudge j = d.deflects();
        out.guardJudged = true;
        out.inSweep = in;
        out.inArc = Facing.inArc(lookYaw, strikes.guardArcDeg() / 2, s.x() - px, s.z() - pz);
        out.stateOk = canGuard(d);
        out.attempt = j.attemptOf(s.id());
        out.pressMicros = j.attemptAt(s.id());
        out.assist = d.assistLevel();
    }

    /** Whether the body at {@code p} lies inside the placed sweep, in sight. */
    private boolean inside(Pose p, double halfWidth, double height, Sight sight) {
        return bounds.holds(
                        p.x() - halfWidth,
                        p.y(),
                        p.z() - halfWidth,
                        p.x() + halfWidth,
                        p.y() + height,
                        p.z() + halfWidth)
                && bounds.seen(sight, hits);
    }

    /**
     * Rule 2.5's clearing dodge, the defender at the impact being {@code atImpact}: a Step that started between the cue
     * and the impact from inside the sweep, or a Jump that did and whose height cleared it.
     */
    private boolean dodged(
            StrikeView s, Combatant d, Pose atImpact, PoseRing poses, double halfWidth, double height, Sight sight) {
        TimedAnswers a = d.answers();
        for (int i = 0; i < a.count(TimedAnswers.Kind.STEP); i++) {
            long at = a.at(TimedAnswers.Kind.STEP, i);
            if (at >= s.cueMicros() && at <= s.impactMicros()) {
                poses.at(at, other);
                if (inside(other, halfWidth, height, sight)) {
                    return true;
                }
            }
        }
        for (int i = 0; i < a.count(TimedAnswers.Kind.JUMP); i++) {
            long at = a.at(TimedAnswers.Kind.JUMP, i);
            if (at >= s.cueMicros() && at <= s.impactMicros()) {
                poses.at(at, other);
                double groundY = other.y();
                if (inside(other, halfWidth, height, sight)) {
                    other.set(at, atImpact.x(), groundY, atImpact.z(), atImpact.yaw(), atImpact.pitch());
                    if (inside(other, halfWidth, height, sight)) {
                        return true; // where it stood at the impact, but on the ground: it would have been hit
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether the defender can still guard at the impact (Champ, D-043): standing, guarding or in the radial (the
     * guard cancels it). An attack, a Step or a stun begun after its press gives the claim up (Rule 2.10: the guard and
     * the attack phase as of I).
     */
    private static boolean canGuard(Combatant d) {
        State st = d.state();
        return st == State.NEUTRAL || st == State.GUARD || st == State.RADIAL;
    }
}
