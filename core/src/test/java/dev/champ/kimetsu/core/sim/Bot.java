package dev.champ.kimetsu.core.sim;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A bot player: the duel sim's player model (`tools/sims/duel_sim.py`) playing the real engine through real inputs, from
 * what its client sees (the outbox's events, after its latency) and its own machine.
 *
 * <ul>
 *   <li><b>Answers</b> each strike on it once its schedule arrives: a white one with a guard tap aimed {@code aim} of the
 *       deflect window before the impact (held through the impact with {@code blockFallback}), a red or purple one with
 *       a Step (the low sweep with a Jump) aimed at the dodge window's centre, unless it misreads the cue as white; a
 *       guard counter only if it reads it coming. A feint's break takes its press back unless it bites anyway.
 *   <li><b>Openings</b> (the last strike's recovery, a stagger, a collapse): a Chain hit on every beat whose first active
 *       tick falls inside, or a form that fits ({@code formRate}), then a Recovery Breath on its last beat
 *       ({@code rbRate}); an off-beat press stumbles and ends its offence there.
 *   <li><b>Gaps</b> (its guard up): chips on every beat ({@code chip}, decided per gap; a form with {@code gapForm})
 *       until a press stumbles, or else Inhales when short of Breath ({@code inhale}).
 *   <li><b>The Thread</b>: seen open, with {@code recognize}, a Water Surface Slash after its reaction, aimed at the
 *       neck. <b>Grabbed:</b> an attack press on every beat. <b>Knocked down:</b> a Jump aimed at the landing (Tech).
 *       <b>A clash:</b> an attack press its reaction after the flash, or now and then one before it.
 * </ul>
 *
 * <p>Its exploit kinds play one degenerate strategy each instead (spec Tests, "Exploit bots").
 */
final class Bot {
    enum Kind {
        HONEST,
        /**
         * Every press it can make: an attack and a guard tap on every tick, and no timed answer to anything (the duel
         * sim's masher); it still goes for the Thread when it sees it open.
         */
        MASHER,
        /** Two guard taps on every white strike, 100 ms apart. */
        DOUBLE_TAPPER,
        /** Chips every gap and holds guard through each guard counter, never timing it. */
        GUARD_HOLDER,
        /** Taps Inhale on every beat between strings. */
        INHALE_FARMER,
        /** Holds guard all fight. */
        TURTLE,
        /** Pommels every visible windup it can, forms into every heavy, and otherwise plays honestly. */
        INTERRUPTER,
        /** The interrupter's Pommels alone: every grab and charged red, Pommelled (D-043). */
        POMMELER,
        /** The interrupter's forms alone: into every heavy, to clash (D-044). */
        CLASHER,
        /** One of two players taking turns to form on the demon every other two beats, whatever it's doing. */
        ROTATION
    }

    static final int RADIAL_HOLD_MS = Fixtures.machine().radialHoldMs(); // Breathe held this long opens the radial
    static final int LAUNCH_AIR_TICKS = 6; // a launched body lands this long after the hit (duel sim)
    private static final int CHAIN_FIRST_ACTIVE = 3;
    private static final int CHAIN_COMMIT = 5; // its windup and active ticks: a guard cancels the rest
    private static final int SURFACE_SLASH = 0; // radial slots: the 1st form, the 2nd, the 4th
    private static final int[] FORM_SLOTS = {0, 1, 2};
    private static final long NONE = Long.MIN_VALUE;
    private static final int PLAN_AHEAD = 5;

    private record Strike(
            long id, FrameData f, int target, long cueTick, long impactTick, long recoveryEnd, boolean last) {}

    private record Press(long stamp, Input in, long strike) {}

    final int id;
    final Skill skill;
    final Kind kind;
    private final int demon;
    private final SplittableRandom rng;
    private final PulseSchedule pulse;
    private final TimingRules timing;
    private final Map<String, FrameData> attacks;
    private final List<FrameData> forms = new ArrayList<>();
    private final Map<Long, Strike> strikes = new HashMap<>();
    private final List<Press> planned = new ArrayList<>();
    private final Map<Long, Long> released = new HashMap<>(); // a strike's guard release, for the riposte after it
    private final int beat;
    private final long wDef;
    private final long wPerfect;
    private final long wOnBeat;
    private State demonState = State.GUARD;
    private long threadUntil = NONE;
    private long threadTried = NONE; // the Thread opening it last went for
    private long openingStart = NONE;
    private long openingEnd = NONE;
    private long lastPlannedBeat = NONE;
    private long busyUntil = NONE; // its own action runs to this tick
    private long offenceStoppedFor = NONE; // the opening (or gap) a stumble ended its offence in
    private int gap; // gaps seen; a chip decision per gap
    private int chipGap = -1;
    private boolean chipping;
    private boolean grabbed;
    private long aimNeckUntil = NONE;
    private boolean collapsed;
    private long landingTick = NONE;
    private long hitAt = NONE; // the impact it felt itself miss: no riposte on it
    private double breathNow = 100; // its Breath as of its last tick, for the Thread's check
    private boolean windedNow;
    private long myAttackEnd = NONE; // its own swing's end (exclusive) and its active frames' end, while it swings
    private long myActiveEnd = NONE;

    Bot(int id, int demon, Skill skill, Kind kind, long seed, PulseSchedule pulse) {
        this.id = id;
        this.demon = demon;
        this.skill = skill;
        this.kind = kind;
        this.rng = new SplittableRandom(seed);
        this.pulse = pulse;
        this.timing = Fixtures.timing();
        this.attacks = Fixtures.demonAttacks();
        for (int slot : FORM_SLOTS) {
            FrameData f = Fixtures.water().radial(slot);
            if (f == null) {
                throw new IllegalStateException("no form in radial slot " + slot);
            }
            forms.add(f);
        }
        this.beat = (int) Micros.tickOf(pulse.beatMicros(1) - pulse.beatMicros(0));
        double scale = skill.assist() >= 2 ? timing.assistWindowScale() : 1.0;
        this.wDef = Math.round(timing.deflectMs() * scale * 1000);
        this.wPerfect = Math.round(timing.perfectMs() * scale * 1000);
        this.wOnBeat = Micros.ofMillis(skill.assist() >= 2 ? timing.assistOnBeatMs() : timing.onBeatMs());
    }

    // ------------------------------------------------------------------------------------------------ what it sees

    /** An event its client received at tick {@code now} (its own effects and everything public), in order. */
    void see(Outbox.Event e, long now) {
        switch (e) {
            case Outbox.AttackStarted a -> started(a);
            case Outbox.Involved v -> {}
            case Outbox.AttackCancelled c -> cancelled(c);
            case Outbox.StrikeResult r -> result(r);
            case Outbox.MobState m -> {
                if (m.mob() == demon) {
                    mobState(m, now);
                }
            }
            case Outbox.PlayerEffect p -> {
                if (p.fighter() == id) {
                    effect(p.effect(), p.tick());
                }
            }
            case Outbox.HoldStarted h -> grabbed |= h.fighter() == id;
            case Outbox.HoldEnded h -> grabbed &= h.fighter() != id;
            case Outbox.ClashStarted c -> {
                if (c.fighter() == id) {
                    clash(c, now);
                }
            }
            default -> {}
        }
    }

    private void started(Outbox.AttackStarted a) {
        FrameData f = attacks.get(a.attack());
        if (f == null || a.attacker() != demon) {
            return;
        }
        long recoveryEnd = a.impactTick() + f.active() + f.recovery();
        Strike s = new Strike(a.strike(), f, a.target(), a.cueTick(), a.impactTick(), recoveryEnd, a.last());
        strikes.put(s.id(), s);
        if (a.target() != id) {
            return;
        }
        if (a.last()) {
            openingStart = a.impactTick();
            openingEnd = recoveryEnd;
        }
        answer(s);
    }

    private void cancelled(Outbox.AttackCancelled c) {
        Strike s = strikes.get(c.strike());
        boolean feint = c.why().equals("feint");
        if (feint && rng.nextDouble() < skill.feintTap()) {
            return; // it bites anyway: its press stands
        }
        planned.removeIf(p -> p.strike() == c.strike() && p.stamp() >= Micros.ofTicks(c.tick()));
        if (s != null && s.last() && !feint) {
            openingEnd = NONE; // a cut string: the stagger or the interrupt sets the opening
        }
    }

    private void result(Outbox.StrikeResult r) {
        Strike s = strikes.get(r.strike());
        if (r.fighter() == id && s != null && s.last() && r.kind() == Verdict.Kind.PERFECT) {
            openingEnd = s.recoveryEnd() + Fixtures.strikes().perfectRecoveryTicks();
        }
    }

    private void mobState(Outbox.MobState m, long now) {
        State before = demonState;
        demonState = m.state();
        threadUntil = m.threadUntil();
        collapsed = m.state() == State.COLLAPSED;
        switch (m.state()) {
            case STAGGER, KNOCKDOWN -> {
                openingStart = m.tick();
                openingEnd = Math.max(openingEnd, m.tick() + (m.state() == State.STAGGER ? 30 : 28));
            }
            case COLLAPSED -> {
                openingStart = m.tick();
                openingEnd = threadUntil == Long.MAX_VALUE ? m.tick() + 100 : threadUntil;
            }
            case GUARD -> {
                if (before != State.GUARD) {
                    gap++;
                }
            }
            default -> {}
        }
        if (threadUntil != NONE && threadUntil > m.tick() && threadTried != threadUntil) {
            thread(now); // its reaction runs from when its client learns of it
        }
    }

    private void effect(Effect e, long tick) {
        if (e instanceof Effect.Knockdown) {
            landingTick = tick + LAUNCH_AIR_TICKS;
            long jump = Math.round(Micros.ofTicks(landingTick)
                    + 1000 * (Skill.TECH_AIM_MS + gauss(skill.sigmaMs() * skill.dodgeMult())));
            plan(jump, Input.Kind.JUMP, -1);
        }
    }

    private void clash(Outbox.ClashStarted c, long now) {
        planned.clear(); // nothing else counts while frozen
        if (rng.nextDouble() < skill.clashAnticipate()) {
            long early = Micros.ofTicks(now)
                    + (long) (rng.nextDouble() * Math.max(0, c.flashMicros() - Micros.ofTicks(now)));
            plan(early, Input.Kind.ATTACK, -1);
        }
        double rt = Math.max(100, skill.clashMuMs() + gauss(skill.clashSdMs()));
        plan(c.flashMicros() + Math.round(rt * 1000), Input.Kind.ATTACK, -1);
    }

    // ----------------------------------------------------------------------------------------------- its answers

    private void answer(Strike s) {
        long impact = Micros.ofTicks(s.impactTick());
        FrameData.Cue cue = s.f().cue();
        // what it had planned into the strike's beat goes: it can't be mid-swing when it must answer
        long from = impact - Micros.ofTicks(beat);
        planned.removeIf(p -> p.strike() == -1
                && p.in().kind() != Input.Kind.INHALE
                && p.stamp() >= from
                && p.stamp() <= impact + Micros.PER_TICK);
        if (kind == Kind.TURTLE || kind == Kind.MASHER) {
            return; // its guard is always up, or its mashing is all it does
        }
        if (cue.type() == FrameData.Cue.Type.WHITE) {
            if (s.f().id().equals(Fixtures.COUNTER_ID)) {
                if (kind == Kind.GUARD_HOLDER) {
                    // up long before the impact, so it claims nothing: a held-guard block, no read and no timing
                    hold(impact - Micros.ofTicks(10), impact + Micros.ofTicks(2), s.id());
                    return;
                }
                if (rng.nextDouble() >= skill.counterRead()) {
                    return; // unread: its next chip goes into it
                }
            }
            if ((kind == Kind.INTERRUPTER || kind == Kind.CLASHER) && s.f().attackClass() == AttackClass.HEAVY) {
                formInto(s);
                return;
            }
            guardTap(s, impact);
            return;
        }
        if ((kind == Kind.INTERRUPTER || kind == Kind.POMMELER)
                && (cue.type() == FrameData.Cue.Type.PURPLE || cue.charged())) {
            long seen = Micros.ofTicks(s.cueTick())
                    + Math.round(1000 * Math.max(100, skill.reactMuMs() + gauss(skill.reactSdMs())));
            plan(Math.min(seen, impact - Micros.ofTicks(3)), Input.Kind.POMMEL, s.id());
            return;
        }
        if (rng.nextDouble() < skill.misread()) {
            guardTap(s, impact); // read as white: it's hit
            return;
        }
        boolean jump = s.f().id().equals(Fixtures.SWEEP_ID);
        double aim = jump ? skill.jumpAimMs() : skill.stepAimMs();
        long at = Math.round(impact + 1000 * (aim + gauss(skill.sigmaMs() * skill.dodgeMult())));
        plan(at, jump ? Input.Kind.JUMP : Input.Kind.STEP, s.id());
    }

    private void guardTap(Strike s, long impact) {
        if (kind == Kind.DOUBLE_TAPPER) {
            double rho = 0.8;
            double e1 = gauss(skill.sigmaMs());
            double e2 = rho * e1 + Math.sqrt(1 - rho * rho) * gauss(skill.sigmaMs());
            tap(impact + Math.round(1000 * (-100 + e1)), impact, s.id());
            tap(impact + Math.round(1000 * e2), impact, s.id());
            return;
        }
        long press = impact - Math.round(skill.aim() * wDef) + Math.round(1000 * gauss(skill.sigmaMs()));
        boolean held = tap(press, impact, s.id());
        if (s.last() && !held && (press < impact - wDef || press > impact)) {
            // it feels the miss as it presses: no riposte; it swings as its hitstun ends, off the beat (the duel
            // sim's hit opening, with the beat a real press can't have)
            hitAt = s.impactTick();
            long swing = Micros.ofTicks(s.impactTick() + 1 + hitstun(s.f()))
                    + Math.round(1000 * Math.abs(gauss(skill.sigmaMs())));
            plan(swing, Input.Kind.ATTACK, -1);
        }
        if (s.last() && press >= impact - wPerfect && press <= impact) {
            // it feels a Perfect as it presses (its client predicts the verdict): its opening runs 8 ticks longer
            openingEnd =
                    Math.max(openingEnd, s.recoveryEnd() + Fixtures.strikes().perfectRecoveryTicks());
        }
    }

    /**
     * A guard tap at {@code press}; one too early to deflect is still held at {@code impact} with the skill's block
     * fallback (the duel sim's {@code white_grade}).
     */
    private boolean tap(long press, long impact, long strike) {
        boolean held = press < impact - wDef && rng.nextDouble() < skill.blockFallback();
        long up = held ? Math.max(press, impact) + Micros.ofTicks(2) : press + Micros.PER_TICK;
        hold(press, up, strike);
        released.merge(strike, up, Math::max);
        return held;
    }

    private void hold(long down, long up, long strike) {
        plan(down, Input.Kind.GUARD_DOWN, strike);
        plan(Math.max(up, down + 1), Input.Kind.GUARD_UP, strike);
    }

    /** The interrupter's form into a heavy, to clash: released so its active frames hold the heavy's impact. */
    private void formInto(Strike s) {
        FrameData first = forms.get(SURFACE_SLASH);
        long release = Micros.ofTicks(s.impactTick() - first.windup() - 1) + Math.round(1000 * gauss(skill.sigmaMs()));
        plan(release - Micros.ofMillis(RADIAL_HOLD_MS), Input.Kind.BREATHE_DOWN, s.id());
        planRelease(release, SURFACE_SLASH, s.id());
    }

    // ------------------------------------------------------------------------------------------------- the Thread

    private void thread(long seenTick) {
        threadTried = threadUntil;
        if (kind == Kind.TURTLE || rng.nextDouble() >= skill.recognize()) {
            return;
        }
        FrameData slash = forms.get(SURFACE_SLASH);
        if (breathNow < slash.breathCost() || windedNow) {
            return; // as the duel sim's try_decap: no Breath for the cut
        }
        long react = Micros.ofTicks(seenTick)
                + Math.round(1000 * Math.max(100, skill.reactMuMs() + gauss(skill.reactSdMs())));
        planned.removeIf(p -> p.strike() == -1 && p.stamp() >= react - Micros.ofMillis(RADIAL_HOLD_MS));
        plan(react, Input.Kind.BREATHE_DOWN, -2);
        long release = react + Micros.ofMillis(RADIAL_HOLD_MS);
        if (myAttackEnd != NONE && Micros.tickOf(release) < myAttackEnd) {
            // still in its own swing (the form that broke it): Water's guard-cancel ends the recovery, then the radial
            long cancel = Math.max(react, Micros.ofTicks(myActiveEnd));
            if (Micros.tickOf(cancel) < myAttackEnd) {
                plan(cancel, Input.Kind.GUARD_DOWN, -2);
                plan(cancel + Micros.PER_TICK, Input.Kind.GUARD_UP, -2);
                release = Math.max(release, cancel + 2 * Micros.PER_TICK);
            } else {
                release = Math.max(release, Micros.ofTicks(myAttackEnd));
            }
        }
        planRelease(release, SURFACE_SLASH, -2);
        FrameData f = forms.get(SURFACE_SLASH);
        aimNeckUntil = Micros.tickOf(release) + f.windup() + f.active() + 1;
        busyUntil = Math.max(busyUntil, aimNeckUntil);
    }

    // -------------------------------------------------------------------------------------------------- each tick

    /**
     * Its client's tick {@code t}: plans the next beat's play from {@code me} (its own machine as its client predicts
     * it), and returns the presses stamped in this tick, in order.
     */
    List<Input> tick(long t, Combatant me) {
        FrameData swing = me.attack();
        if (me.state() == State.ATTACK && swing != null) {
            myAttackEnd = me.attackEnd();
            myActiveEnd = me.attackStart() + me.attackHold() + swing.windup() + swing.active();
        } else {
            myAttackEnd = NONE;
            myActiveEnd = NONE;
        }
        breathNow = units(me.breath().at(t));
        windedNow = me.breath().winded(t);
        long k = pulse.nearestBeat(Micros.ofTicks(t + PLAN_AHEAD)); // planned 5 ticks ahead: 3 SDs of a casual press
        if (pulse.beatMicros(k) < Micros.ofTicks(t + PLAN_AHEAD)) {
            k++;
        }
        if (k > lastPlannedBeat) {
            lastPlannedBeat = k;
            onBeat(k, Micros.tickOf(pulse.beatMicros(k)), me, t);
        }
        if (kind == Kind.TURTLE && me.state() != State.GUARD && !me.guardDown()) {
            plan(Micros.ofTicks(t), Input.Kind.GUARD_DOWN, -1);
        }
        if (kind == Kind.MASHER && t >= busyUntil) { // an attack and a guard tap every tick (not over a Thread cut)
            long at = Micros.ofTicks(t);
            plan(at + 5_000, Input.Kind.ATTACK, -1);
            plan(at + 20_000, Input.Kind.GUARD_DOWN, -1);
            plan(at + 40_000, Input.Kind.GUARD_UP, -1);
        }
        List<Input> out = new ArrayList<>();
        long from = Micros.ofTicks(t);
        long to = Micros.ofTicks(t + 1);
        planned.sort((a, b) -> Long.compare(a.stamp(), b.stamp()));
        while (!planned.isEmpty() && planned.get(0).stamp() < to) {
            Press p = planned.remove(0);
            Input in = p.in();
            long stamp = Math.max(from, p.stamp());
            out.add(new Input(in.kind(), stamp, in.slot(), in.fallBlocks()));
        }
        return out;
    }

    private void onBeat(long k, long beatTick, Combatant me, long t) {
        long at = pulse.beatMicros(k);
        if (grabbed) {
            plan(at + Math.round(1000 * gauss(skill.sigmaMs())), Input.Kind.ATTACK, -1); // an escape input
            return;
        }
        State st = me.state();
        if (st == State.CLASH
                || st == State.KNOCKDOWN
                || st == State.GRABBED
                || st == State.DEAD
                || st == State.COLLAPSED) {
            return;
        }
        if (kind == Kind.TURTLE || kind == Kind.MASHER) {
            return; // the masher mashes every tick instead
        }
        // the riposte (the duel sim's opening starts on the impact beat): a deflect, then its offence within the beat
        boolean riposte = openingStart != NONE
                && beatTick == openingStart
                && beatTick != hitAt
                && beatTick + CHAIN_FIRST_ACTIVE < openingEnd;
        if (riposte && beatTick >= busyUntil && offenceStoppedFor != openingEnd) {
            offence(k, me, t, true);
            return;
        }
        if (beatTick < busyUntil || answeringNear(beatTick, CHAIN_COMMIT + 4)) {
            return;
        }
        if (kind == Kind.ROTATION && (k / 2) % 2 == id % 2 && rotate(k, me, t)) {
            return; // its turn: a form, wherever the demon is
        }
        boolean opening =
                openingStart != NONE && beatTick >= openingStart && beatTick + CHAIN_FIRST_ACTIVE < openingEnd;
        if (opening) {
            if (offenceStoppedFor != openingEnd) {
                offence(k, me, t, false);
            }
            return;
        }
        if (demonState == State.GUARD && !stringComing(beatTick)) {
            gapBeat(
                    k, me,
                    t); // the duel sim chips into a guard counter's lead too (its guard holds: staked, no counter)
        }
    }

    /** When its guard tap on the strike landing at {@code impactTick} lets go (the riposte comes after it). */
    private long releasedAt(long impactTick) {
        for (Strike s : strikes.values()) {
            if (s.target() == id && s.impactTick() == impactTick) {
                Long up = released.get(s.id());
                return up == null ? Long.MIN_VALUE + 1 : up;
            }
        }
        return Long.MIN_VALUE + 1;
    }

    /** Whether one of its answers falls within {@code ticks} of beat tick {@code b}, or a string's strike lands next. */
    private boolean answeringNear(long b, int ticks) {
        for (int i = 0; i < planned.size(); i++) {
            Press p = planned.get(i);
            if (p.strike() >= 0) {
                long pt = Micros.tickOf(p.stamp());
                if (pt >= b - 1 && pt <= b + ticks) {
                    return true;
                }
            }
        }
        return stringComing(b);
    }

    /** A strike of a string (not a guard counter) on it lands from tick {@code b} to a beat later: its cue beat. */
    private boolean stringComing(long b) {
        for (Strike s : strikes.values()) {
            if (s.target() == id
                    && !s.f().id().equals(Fixtures.COUNTER_ID)
                    && s.impactTick() >= b
                    && s.impactTick() <= b + beat) {
                return true;
            }
        }
        return false;
    }

    /** Its offence on beat {@code k} of an opening; {@code riposte}: on the impact beat, after its answer. */
    private void offence(long k, Combatant me, long t, boolean riposte) {
        long at = pulse.beatMicros(k);
        long after = riposte ? releasedAt(Micros.tickOf(at)) + 1 : Long.MIN_VALUE;
        int left = 0;
        for (long j = k; Micros.tickOf(pulse.beatMicros(j)) + CHAIN_FIRST_ACTIVE < openingEnd; j++) {
            left++;
        }
        double breath = units(me.breath().at(t));
        boolean winded = me.breath().winded(t);
        if (!winded && rng.nextDouble() < skill.formRate()) {
            List<Integer> fit = new ArrayList<>();
            for (int slot : FORM_SLOTS) {
                FrameData f = forms.get(slot);
                if (beats(f) <= left && breath >= f.breathCost()) {
                    fit.add(slot);
                }
            }
            if (!fit.isEmpty()) {
                form(k, fit.get(rng.nextInt(fit.size())), true, after);
                return;
            }
        }
        double err = riposte ? Math.abs(gauss(skill.sigmaMs())) : gauss(skill.sigmaMs());
        long press = Math.max(at + Math.round(1000 * err), after);
        plan(press, Input.Kind.ATTACK, -1);
        err = (press - at) / 1000.0;
        if (Math.abs(err) * 1000 > wOnBeat) {
            offenceStoppedFor = openingEnd; // a stumble: its chain breaks, and so does its offence this opening
        }
    }

    /** Fires the form in radial slot {@code slot} on beat {@code k}, then maybe a Recovery Breath on its last beat. */
    private void form(long k, int slot, boolean recoveryBreath, long after) {
        FrameData f = forms.get(slot);
        double err = after == Long.MIN_VALUE ? gauss(skill.sigmaMs()) : Math.abs(gauss(skill.sigmaMs()));
        long release = Math.max(pulse.beatMicros(k) + Math.round(1000 * err), after);
        plan(release - Micros.ofMillis(RADIAL_HOLD_MS), Input.Kind.BREATHE_DOWN, -1);
        planRelease(release, slot, -1);
        int beats = beats(f);
        busyUntil = Micros.tickOf(pulse.beatMicros(k + beats)) - 2;
        if (recoveryBreath && rng.nextDouble() < skill.rbRate()) {
            plan(pulse.beatMicros(k + beats - 1) + Math.round(1000 * gauss(skill.sigmaMs())), Input.Kind.INHALE, -1);
        }
        lastPlannedBeat = k + beats - 1;
    }

    /** The rotation's form on its turn, if its Breath allows one: the 1st form, else the 2nd. */
    private boolean rotate(long k, Combatant me, long t) {
        if (me.breath().winded(t)) {
            return false;
        }
        double breath = units(me.breath().at(t));
        for (int slot : new int[] {0, 1}) {
            if (breath >= forms.get(slot).breathCost()) {
                form(k, slot, true, Long.MIN_VALUE);
                return true;
            }
        }
        return false;
    }

    private void gapBeat(long k, Combatant me, long t) {
        long at = pulse.beatMicros(k);
        if (chipGap != gap) {
            chipGap = gap;
            chipping = kind == Kind.GUARD_HOLDER || rng.nextDouble() < skill.chip();
        }
        double breath = units(me.breath().at(t));
        if (kind == Kind.INHALE_FARMER) {
            plan(at + Math.round(1000 * gauss(skill.sigmaMs())), Input.Kind.INHALE, -1);
            return;
        }
        if (chipping) {
            if (!me.breath().winded(t) && me.rhythm().tier() < 5 && rng.nextDouble() < skill.gapForm()) {
                for (int slot : new int[] {0, 1}) { // the two-beat forms: a gap rarely holds a third
                    FrameData f = forms.get(slot);
                    if (breath >= f.breathCost() + 20) {
                        form(k, slot, true, Long.MIN_VALUE);
                        return;
                    }
                }
            }
            double err = gauss(skill.sigmaMs());
            plan(at + Math.round(1000 * err), Input.Kind.ATTACK, -1);
            if (Math.abs(err) * 1000 > wOnBeat) {
                chipping = false; // an off-beat chip: it stumbles, and stops chipping this gap
            }
            return;
        }
        if (breath < 100 && rng.nextDouble() < skill.inhale()) {
            plan(at + Math.round(1000 * gauss(skill.sigmaMs())), Input.Kind.INHALE, -1);
        }
    }

    // ------------------------------------------------------------------------------------------------- for the runner

    /** It aims at the demon's neck now (a Thread attempt), and whether that neck is the collapsed one. */
    boolean aimsNeck(long t) {
        return t <= aimNeckUntil;
    }

    boolean neckCollapsed() {
        return collapsed;
    }

    /** The tick its launched body lands, once (the runner reports it); NONE if none is due. */
    long takeLanding(long t) {
        if (landingTick != NONE && t >= landingTick) {
            long l = landingTick;
            landingTick = NONE;
            return l;
        }
        return NONE;
    }

    // --------------------------------------------------------------------------------------------------- helpers

    private void plan(long stamp, Input.Kind k, long strike) {
        planned.add(new Press(stamp, Input.of(k, stamp), strike));
    }

    private void planRelease(long stamp, int slot, long strike) {
        planned.add(new Press(stamp, Input.release(stamp, slot), strike));
    }

    /** The hitstun a strike of the demon's deals (its first hit window's). */
    private static int hitstun(FrameData f) {
        return f.hits().isEmpty() ? 0 : f.hits().get(0).hitstun();
    }

    private int beats(FrameData f) {
        int ticks = f.windup() + f.active() + f.recovery();
        return (ticks + beat - 1) / beat;
    }

    private double gauss(double sd) {
        return sd <= 0 ? 0 : rng.nextGaussian() * sd;
    }

    private static double units(long fixed) {
        return fixed / (double) Fixed.ONE;
    }
}
