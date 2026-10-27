package dev.champ.kimetsu.core.machine;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.Armour;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Into;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.data.PostureRules;
import dev.champ.kimetsu.core.data.RhythmRules;
import dev.champ.kimetsu.core.data.Style;
import dev.champ.kimetsu.core.data.TimingRules;
import dev.champ.kimetsu.core.judge.CreditLedger;
import dev.champ.kimetsu.core.judge.DeflectJudge;
import dev.champ.kimetsu.core.judge.OnBeat;
import dev.champ.kimetsu.core.machine.InputBuffer.Slot;
import dev.champ.kimetsu.core.meter.Breath;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Posture;
import dev.champ.kimetsu.core.meter.Rhythm;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One player's state machine (Rule 3) with the meters it spends and earns (Rule 4), one tick at a time, in the spec's
 * order: {@link #input} for each press of the tick in stamp order (step 1), then {@link #advance} (step 3: timers run
 * out, the buffer polls), then {@link #effect} for what the world did (step 6), then {@link #meters} (step 7).
 *
 * <p><b>When things happen.</b> A press stamped inside tick t−1's 50 ms arrives in step t and is judged against the
 * state tick t−1 left (so an Inhale on a form's last recovery tick is still a Recovery Breath); what it starts begins
 * at tick t. A press the buffer held runs in step t's poll, judged against tick t, and also begins at t. An attack's
 * frame at tick t is {@code t − start}, its windup's last frame repeated while it's held.
 *
 * <p><b>Rule 2.2's limit.</b> An attack never becomes active in a tick the world has already swept: when its press
 * arrived late, its last windup frame is held until its first active tick is the step it arrived in. A Step, a guard
 * press and a Jump are never held back.
 *
 * <p><b>Calls the spec leaves open</b> (D-035, D-036): the chain ends when a Step, GUARD, a guard-cancel or any other
 * attack comes between its hits; from NEUTRAL a contextual form takes an attack press before the chain does; a form's
 * recovery holds one Recovery Breath (the tap that takes or loses the grey bar); Mushin is paid when the finisher's
 * active frames start; a hit stuns no one GRABBED, in a CLASH, COLLAPSED or DEAD, and a Posture break waits for a
 * knockdown, a grab or a clash to end; nothing moves a COLLAPSED or DEAD combatant but death.
 *
 * <p><b>Strikes on it</b> (Rules 2.6–2.8). Its {@link DeflectJudge} holds the NPC strikes it's involved in, logged by the
 * world as {@link Effect.StrikeCommitted}; a guard press claims from them only where the guard can come up at that
 * moment (standing, guarding, from the radial, a Feint in a form's windup, Water's guard-cancel in a recovery), and
 * elsewhere claims nothing and starts no lockout. A strike leaves with its verdict ({@link Effect.StrikeDecided}), a
 * cancel, or once its claim can no longer be decided. {@link TimedAnswers} keeps when its Steps, Jumps and Pommel
 * Strikes started, for the verdicts that read them.
 *
 * <p>The server and the predicting client run the same machine; a {@link Timeline} snapshots it every tick and replays
 * late presses at their stamps. Everything it mutates is copied by {@link #copyFrom}, and nothing it reads lies outside
 * it except the rules, the moveset and the Pulse, whose past never changes.
 */
public final class Combatant {
    private static final int JUMP_REPEAT_TICKS = 2;
    private static final long NONE = Long.MIN_VALUE;
    private static final int JUMPS = 16;
    private static final int WALLS = 4; // wall results kept for settling: a splat settles within a hold cap of its wall
    private static final Slot[] SLOTS = Slot.values();

    private final MachineRules rules;
    private final TimingRules timing;
    private final BreathRules breathRules;
    private final PostureRules postureRules;
    private final RhythmRules rhythmRules;
    private final Moveset moves;
    private final PulseSchedule pulse;

    private final Breath breath;
    private final Posture posture;
    private final Rhythm rhythm;
    private final CreditLedger ledger;
    private final InputBuffer buffer;
    private final DeflectJudge deflects;
    private final TimedAnswers answers = new TimedAnswers();

    private int assistLevel;
    private State state = State.NEUTRAL;
    private long stateUntil = NONE; // HITSTUN, STEP, a knockdown's ticks down and rising, COLLAPSED
    private long stunAfter = NONE; // a hit's stun that outlasts a stagger or a knockdown

    private @Nullable FrameData attack;
    private long attackStart;
    private long attackHold;
    private long attackEnd;
    private long attackId;
    private int attackChain = -1; // the attack's index in the Light Chain, or −1
    private long attackBeat = NONE;
    private boolean attackOnBeat;
    private boolean attackMushin;
    private boolean attackConnected;
    private boolean mushinSpent; // a Mushin firing pays at its first active tick, so a Feint or an interrupt keeps it
    private long nextAttackId = 1;

    private int chainNext; // the Chain hit a continuation fires (0: no chain open)
    private long chainBeat = NONE; // the beat of the last Chain hit's press
    private long chainFrom = NONE; // the first step a continuation can act in: the one after the hit's last active tick
    private long chainUntil = NONE; // µs: the end of the next beat's on-beat window

    private boolean guardDown;
    private long guardSince = NONE;
    private boolean breatheDown;
    private long breatheDownAt = NONE;
    private boolean radialSpent;
    private long radialUntil = NONE;

    private long lastFeintTick = NONE;
    private double lastFeintCost;
    private double pendingFeint;
    private long pendingFeintTick = NONE;
    private long pendingFeintPress = NONE;
    private boolean feintAnswered; // a strike its press claimed was deflected: the Feint is free

    private long knockdownAt = NONE;
    private boolean airborne;
    private long lastContact = NONE;
    private long techLockoutUntil = NONE;
    private final long[] jumps = new long[JUMPS];
    private int jumpCount;
    private boolean teched;
    private boolean juggleCut;
    // a push's splat window (Rule 9.4): a wall met from step splatFrom until before splatUntil splats it, and the Jumps
    // pressed after splatOpened (µs: the push's tick, or the earliest its client can have shown it, D-081) count for it
    private long splatFrom = NONE;
    private long splatUntil = NONE;
    private long splatOpened = NONE;
    // its last WALLS wall contacts inside a splat window, newest first, for each splat's log (D-078): µs, whether a
    // Jump Teched it, the Jumps pressed in its Tech window, and the Teching press's offset from it. More than the
    // newest, so a second splat before the first is settled doesn't take the first's result (D-079's review)
    private final long[] wallAt = new long[WALLS];
    private final boolean[] wallTeched = new boolean[WALLS];
    private final int[] wallJumps = new int[WALLS];
    private final long[] wallTechOffset = new long[WALLS];

    private int grabEscapes;
    private long lastEscapeBeat = NONE;
    private boolean escaped;
    private long escapedAt = NONE;
    private long clashPress = NONE;

    private long combatUntil = NONE; // the tick Rule 13.1's flag drops at: 15 s after the last combat effect
    private long clearedAt = NONE; // µs of the last effect that emptied the buffer: offence pressed before it is void
    private long enteredAt = NONE; // µs an effect put it in GRABBED, CLASH or KNOCKDOWN: presses before it aren't its

    public Combatant(
            MachineRules rules,
            TimingRules timing,
            BreathRules breathRules,
            PostureRules postureRules,
            RhythmRules rhythmRules,
            Moveset moves,
            PulseSchedule pulse,
            long tick) {
        this.rules = rules;
        this.timing = timing;
        this.breathRules = breathRules;
        this.postureRules = postureRules;
        this.rhythmRules = rhythmRules;
        this.moves = moves;
        this.pulse = pulse;
        this.breath = new Breath(breathRules, tick);
        this.posture = new Posture(postureRules, tick);
        this.rhythm = new Rhythm(rhythmRules);
        this.ledger = new CreditLedger(timing.creditsPerBeat());
        this.buffer = new InputBuffer(rules.bufferTicks());
        this.deflects = new DeflectJudge(timing);
        Arrays.fill(wallAt, NONE);
        Arrays.fill(wallTechOffset, NONE);
    }

    /**
     * Before its first tick, in a rebuilt world (a data reload, or game time re-anchoring; slice 5c-1): it takes over
     * {@code old}'s meters as they stood at {@code oldTick}, as of {@code newTick}, the same moment on this world's
     * ticks: Breath, Winded, Posture and Rhythm, each held to its own maximum, so new rules never heal, their timers
     * running on, and the combat flag with the time it had left (a reload mid-fight doesn't let a player heal or mine).
     * A stagger still running goes on in STAGGER, and its assist level stays (5d-2: the mod tells the engine a level
     * only when it changes). Nothing else: its attack in progress went with the old world, and the new world sets its
     * guard and its drains again.
     */
    public void carry(Combatant old, long oldTick, long newTick) {
        assistLevel = old.assistLevel;
        breath.carry(old.breath, oldTick, newTick);
        posture.carry(old.posture, oldTick, newTick);
        rhythm.carry(old.rhythm, oldTick, newTick);
        if (old.inCombat(oldTick)) {
            combatUntil = old.combatUntil + (newTick - oldTick);
            breath.setInCombat(newTick, true);
        }
        if (posture.staggered(newTick)) {
            stagger(newTick);
        }
    }

    /** A fresh combatant sharing this one's rules, moveset and Pulse (a snapshot slot). */
    public Combatant blank() {
        return new Combatant(rules, timing, breathRules, postureRules, rhythmRules, moves, pulse, 0);
    }

    public MachineRules rules() {
        return rules;
    }

    // ------------------------------------------------------------------------------------------------------ step 1

    /**
     * A press or release acting in step {@code tick}. {@code minActiveTick} is the step it arrived in: an attack it
     * starts becomes active no earlier (Rule 2.2). The client, which applies its own presses at once, passes the press's
     * own step. A press stamped past the radial's hold, Breathe still down, the guard up, meets the radial open, as
     * {@link #advance} would open it at this step's end.
     */
    public void input(Input in, long tick, long minActiveTick) {
        if (state == State.DEAD) {
            return;
        }
        if (in.combat()) {
            rhythm.active(tick);
        }
        if (state == State.NEUTRAL && !guardDown && breatheDown && !radialSpent && radialHeldAt(in.stampMicros())) {
            openRadial(tick); // its edge came first: in time it's open, as the client aims it (Rule 5.4)
        }
        // A press the world decided without (it arrived after a strike's verdict) is applied after the effect it
        // predates. On time it would have come first, so it's treated that way: offence pressed before a hit that
        // emptied the buffer is gone, as the buffer's presses are, and a grab, a clash or a launch counts only the
        // presses made after it. Held keys (guard, Breathe) still apply.
        long stamp = in.stampMicros();
        boolean voided = clearedAt != NONE && stamp <= clearedAt;
        boolean before = enteredAt != NONE && stamp <= enteredAt;
        switch (in.kind()) {
            case GUARD_DOWN -> guardDown(in, tick);
            case GUARD_UP -> guardUp(tick);
            case STEP -> {
                if (!voided) {
                    buffer.dropOffence();
                    queue(Slot.STEP, in, tick, tick); // no active frames to hold back: when it arrived doesn't matter
                }
            }
            case ATTACK -> {
                if (state == State.CLASH) {
                    if (!before && clashPress == NONE) {
                        clashPress = stamp;
                    }
                } else if (state == State.GRABBED) {
                    if (!before) {
                        escape(in, tick);
                    }
                } else if (!voided) {
                    queue(Slot.ATTACK, in, tick, minActiveTick);
                }
            }
            case POMMEL -> {
                if (state == State.GRABBED) {
                    if (!before) {
                        escape(in, tick);
                    }
                } else if (!voided) {
                    queue(Slot.POMMEL, in, tick, minActiveTick);
                }
            }
            case INHALE -> {
                if (state == State.GRABBED) {
                    if (!before) {
                        escape(in, tick);
                    }
                } else {
                    inhale(in, tick);
                }
            }
            case BREATHE_DOWN -> {
                breatheDown = true;
                breatheDownAt = stamp;
            }
            case BREATHE_UP -> breatheUp(in, tick, minActiveTick, voided);
            case JUMP -> {
                if (!before) {
                    jump(in, tick);
                }
            }
            case SNEAK -> {
                if (!before) {
                    juggleCut(tick);
                }
            }
        }
    }

    private void queue(Slot slot, Input in, long tick, long minActiveTick) {
        buffer.offer(slot, in, tick, minActiveTick);
        if (run(slot, in, tick, tick - 1, minActiveTick)) {
            buffer.take(slot);
        }
    }

    /**
     * Rule 3.3: a guard press in the first part of a form's windup feints it; any other just raises the guard. Where the
     * guard can come up at the press's moment it's also a deflect attempt (Rules 2.6–2.8). It cancels the radial.
     */
    private void guardDown(Input in, long tick) {
        guardDown = true;
        guardSince = tick;
        buffer.dropOffence();
        FrameData f = attack;
        Style.GuardCancel gc = moves.style().guardCancel();
        boolean feints = state == State.ATTACK && f != null && cancels(f, Into.FEINT, tick - 1);
        boolean cancelsRecovery = !feints
                && state == State.ATTACK
                && f != null
                && gc != null
                && phaseAt(f, Math.max(attackStart, tick - 1)) == Phase.RECOVERY;
        boolean claimed = deflects.press(in.stampMicros(), assistLevel, guardCanRise(f, tick - 1, feints));
        if (feints) {
            feint(in, tick, claimed);
        } else if (cancelsRecovery && gc != null) {
            guardCancel(gc, tick); // the press itself cancels, so a tap that claims pays as a held guard would
        } else if (state == State.RADIAL) {
            setState(State.NEUTRAL, tick); // the guard cancels it; GUARD follows by the one rule
        }
    }

    /**
     * Whether the guard can come up at a press judged against tick {@code lived}: standing, guarding, from the radial, a
     * Feint, or Water's guard-cancel in a recovery. Mid-swing, mid-Step, stunned or held, it can't: the press claims no
     * strike and starts no lockout (its judge keeps it only as the strike's attempt), though a held guard still rises
     * when it can.
     */
    private boolean guardCanRise(@Nullable FrameData f, long lived, boolean feints) {
        return switch (state) {
            case NEUTRAL, GUARD, RADIAL -> true;
            case ATTACK ->
                feints
                        || (f != null
                                && moves.style().guardCancel() != null
                                && phaseAt(f, Math.max(attackStart, lived)) == Phase.RECOVERY);
            default -> false;
        };
    }

    private void guardUp(long tick) {
        guardDown = false;
        if (state == State.GUARD) {
            setState(State.NEUTRAL, tick);
        }
    }

    /**
     * Rule 3.3 and ruling Q8. The form never fired, so its cost comes back and the Feint pays its own (D-035). A press
     * that claimed a strike pays only once that strike is decided unanswered; one Feint waits at a time, and a second
     * pays the first's cost at once.
     */
    private void feint(Input in, long tick, boolean claimed) {
        breath.refundForm(tick);
        if (pendingFeint > 0) {
            payFeint(tick, pendingFeintTick, pendingFeint);
            clearPendingFeint();
        }
        double cost = lastFeintTick != NONE && tick - lastFeintTick <= rules.feintDoublingTicks()
                ? lastFeintCost * 2
                : rules.feintBreath();
        if (claimed) {
            pendingFeint = cost;
            pendingFeintTick = tick;
            pendingFeintPress = in.stampMicros();
            feintAnswered = false;
        } else {
            payFeint(tick, tick, cost);
        }
        endAttack();
        setState(State.NEUTRAL, tick);
    }

    private void payFeint(long tick, long feintTick, double cost) {
        breath.spend(tick, Fixed.of(cost));
        lastFeintTick = feintTick;
        lastFeintCost = cost;
    }

    private void clearPendingFeint() {
        pendingFeint = 0;
        pendingFeintTick = NONE;
        pendingFeintPress = NONE;
        feintAnswered = false;
    }

    /**
     * Ruling Q8: a strike that the press claimed with {@code claimedAt} left, {@code answered} or not. A Feint that press
     * made is free if any strike it claimed was answered, and pays once every one of them has left otherwise.
     */
    private void settle(long claimedAt, boolean answered, long tick) {
        if (pendingFeint <= 0 || claimedAt == DeflectJudge.UNCLAIMED || claimedAt != pendingFeintPress) {
            return;
        }
        feintAnswered |= answered;
        if (deflects.claimedBy(pendingFeintPress)) {
            return; // another strike it claimed is still to be decided
        }
        if (!feintAnswered) {
            payFeint(tick, pendingFeintTick, pendingFeint);
        }
        clearPendingFeint();
    }

    private void inhale(Input in, long tick) {
        FrameData f = attack;
        if (state == State.NEUTRAL) {
            plainInhale(in, tick);
        } else if (state == State.ATTACK
                && f != null
                && f.recoveryBreath()
                && breath.grey() > 0
                && phaseAt(f, Math.max(attackStart, tick - 1)) == Phase.RECOVERY) {
            recoveryBreath(in, tick);
        } // anywhere else an Inhale tap does nothing and isn't judged
    }

    /** An Inhale outside a form's recovery: +6 Breath once per beat on the beat, no credit; off it, −1 (Rule 2.5). */
    private void plainInhale(Input in, long tick) {
        long beat = beatOf(in.stampMicros());
        if (beat != OnBeat.OFF_BEAT) {
            breath.inhale(tick, beat);
        } else {
            rhythm.add(rhythmRules.offBeat());
        }
    }

    /** Rule 3.5: on the beat it restores the Inhale, refunds the grey bar and takes the beat's credit; off it, winds. */
    private void recoveryBreath(Input in, long tick) {
        long beat = beatOf(in.stampMicros());
        if (beat != OnBeat.OFF_BEAT) {
            breath.inhale(tick, beat);
            breath.recoveryBreath(tick, true);
            credit(beat, tick);
        } else {
            breath.recoveryBreath(tick, false);
            rhythm.add(rhythmRules.offBeat());
        }
    }

    /**
     * Rule 3.7. A release held past the radial's threshold while NEUTRAL, the guard up, opens it on this step and fires
     * at once. One toward a slot in a state that can't fire yet (a recovery, a Step) waits in the buffer for NEUTRAL,
     * judged at its stamp (Rule 3's buffer). A tap reaches the machine as an Inhale (the Timeline adds it).
     */
    private void breatheUp(Input in, long tick, long minActiveTick, boolean voided) {
        breatheDown = false;
        boolean spent = radialSpent;
        radialSpent = false;
        boolean held = radialHeldAt(in.stampMicros());
        if (state == State.NEUTRAL && !guardDown && !spent && held) {
            openRadial(tick);
        }
        if (state == State.RADIAL) {
            FrameData form = moves.radial(in.slot());
            if (form == null || !tryForm(form, in.stampMicros(), tick, minActiveTick)) {
                setState(State.NEUTRAL, tick); // nothing fired, nothing paid, not judged
            }
        } else if (held && !spent && !voided && state != State.NEUTRAL && moves.radial(in.slot()) != null) {
            queue(Slot.FORM, in, tick, minActiveTick);
        }
    }

    /**
     * Rule 9.2: a Jump during a knockdown is a Tech attempt, judged against the next contact; so is one while a push's
     * splat window is open, against the wall it may meet (D-078: as written, so mashing Techs most close-wall splats),
     * unless it was pressed before its client could show the push (D-081).
     */
    private void jump(Input in, long tick) {
        if (state != State.KNOCKDOWN) {
            long j = in.stampMicros();
            // a dodge, if it clears a strike (Rule 6.2); a press within 2 ticks of the last is mashing mid-air, not a
            // jump, and mustn't push the one that left the ground out of the answers
            if (answers.count(TimedAnswers.Kind.JUMP) == 0
                    || j - answers.at(TimedAnswers.Kind.JUMP, 0) >= Micros.ofTicks(JUMP_REPEAT_TICKS)) {
                answers.add(TimedAnswers.Kind.JUMP, j);
            }
            if (splatUntil != NONE && j > splatOpened && j < Micros.ofTicks(splatUntil)) {
                storeJump(j); // judged if the push meets a wall
            } else if (wallTeched[0]
                    && j >= wallAt[0] - Micros.ofTicks(rules.techBeforeTicks())
                    && j <= wallAt[0] + Micros.ofTicks(rules.techAfterTicks())) {
                wallJumps[0]++; // a press after a Teched wall, still in its window: mashing counts it (D-078)
            }
            return; // the breakfall on an ordinary landing is travel (build step 6)
        }
        long j = in.stampMicros();
        if (lastContact != NONE && j <= lastContact + Micros.ofTicks(rules.techAfterTicks())) {
            boolean wall = lastContact == wallAt[0]; // still in the air from that splat
            if (wall && j >= lastContact - Micros.ofTicks(rules.techBeforeTicks())) {
                wallJumps[0]++;
            }
            if (techAgainst(lastContact, j)) {
                if (wall) {
                    wallTeched[0] = true;
                    wallTechOffset[0] = j - lastContact;
                }
                tech(tick);
            }
        } else if (airborne) {
            storeJump(j); // judged when the body meets the floor or a wall
        } else {
            lockOut(j);
        }
    }

    /**
     * Keeps a Jump for the next contact. When the list is full the oldest counts as a miss: sixteen presses can't all
     * fall inside the five-tick window, so the oldest of them is outside it.
     */
    private void storeJump(long j) {
        if (jumpCount == JUMPS) {
            lockOut(jumps[0]);
            System.arraycopy(jumps, 1, jumps, 0, JUMPS - 1);
            jumpCount--;
        }
        jumps[jumpCount++] = j;
    }

    private void lockOut(long jump) {
        long until = jump + Micros.ofTicks(rules.techLockoutTicks());
        techLockoutUntil = techLockoutUntil == NONE ? until : Math.max(techLockoutUntil, until);
    }

    /** Sneak in mid-air cuts a juggle short for its Breath; the mod drops the body to the ground (D-035). */
    private void juggleCut(long tick) {
        long cost = Fixed.of(rules.juggleCutBreath());
        if (state == State.KNOCKDOWN && airborne && !juggleCut && breath.canAfford(tick, cost)) {
            breath.spend(tick, cost);
            juggleCut = true;
        }
    }

    /** A rhythm-scored press while grabbed: one escape per beat on the beat; off the beat, −1 (Rule 6.3, D-035). */
    private void escape(Input in, long tick) {
        long beat = beatOf(in.stampMicros());
        if (beat == OnBeat.OFF_BEAT) {
            rhythm.add(rhythmRules.offBeat());
            return;
        }
        if (lastEscapeBeat != NONE && beat <= lastEscapeBeat) {
            return;
        }
        lastEscapeBeat = beat;
        credit(beat, tick);
        if (++grabEscapes >= rules.grabEscapeInputs()) {
            escaped = true;
            escapedAt = tick;
            recover(tick);
        }
    }

    // ------------------------------------------------------------------------------------------------------ step 3

    /** Step 3: timers that run out at {@code tick}, the guard and the radial, then the buffer's presses that can run. */
    public void advance(long tick) {
        switch (state) {
            case ATTACK -> advanceAttack(tick);
            case STEP, HITSTUN, COLLAPSED -> {
                if (tick >= stateUntil) {
                    setState(State.NEUTRAL, tick);
                }
            }
            case STAGGER -> {
                if (!posture.staggered(tick)) {
                    recover(tick);
                }
            }
            case KNOCKDOWN -> {
                if (airborne && tick >= knockdownAt + rules.knockdownMaxAirTicks()) {
                    // no landing reported in time (one the level missed, or withheld): it meets the floor now
                    contact(Effect.Surface.FLOOR, Micros.ofTicks(tick), tick); // allocating nothing
                }
                if (!airborne && stateUntil != NONE && tick >= stateUntil) {
                    recover(tick); // down 20 ticks, then rising 8: one span
                }
            }
            case RADIAL -> {
                if (radialUntil != NONE && tick >= radialUntil) {
                    setState(State.NEUTRAL, tick); // it closed with nothing fired
                }
            }
            default -> {}
        }
        long now = Micros.ofTicks(tick);
        for (int i = deflects.count() - 1; i >= 0; i--) {
            if (now > deflects.impactAt(i) + claimMicros()) {
                settle(deflects.removeAt(i), false, tick); // no verdict came: nothing answered it
            }
        }
        if (pendingFeint > 0 && now > pendingFeintPress + claimMicros()) {
            payFeint(tick, pendingFeintTick, pendingFeint); // no verdict came: a claim nothing answered
            clearPendingFeint();
        }
        if (state == State.NEUTRAL && guardDown && guardSince != NONE && tick - guardSince >= rules.guardHoldTicks()) {
            setState(State.GUARD, tick);
        }
        if (state == State.NEUTRAL && !guardDown && breatheDown && !radialSpent && radialHeldAt(Micros.ofTicks(tick))) {
            openRadial(tick); // not over a guard still rising (guard_hold_ticks): the guard cancels the radial
        }
        poll(tick);
        if (chainNext > 0 && Micros.ofTicks(tick) >= chainUntil) {
            resetChain(); // no press by the end of the next beat's window: the chain starts over
        }
    }

    /** The attack's end, or Water's guard-cancel: a guard held at a recovery tick ends it (Rule 3.4, R9-09). */
    private void advanceAttack(long tick) {
        FrameData f = attack;
        if (f == null || tick >= attackEnd) {
            endAttack();
            setState(State.NEUTRAL, tick);
            return;
        }
        if (attackMushin && !mushinSpent && phaseAt(f, tick) != Phase.WINDUP) {
            rhythm.spendMushin(); // the finisher fires: Rhythm drops to the spend's tier
            mushinSpent = true;
        }
        Style.GuardCancel gc = moves.style().guardCancel();
        if (gc != null && guardDown && phaseAt(f, tick) == Phase.RECOVERY) {
            guardCancel(gc, tick);
        }
    }

    /** Water's guard-cancel (Rule 3.4, R9-09): the recovery ends, free after a miss, costing Breath after a hit. */
    private void guardCancel(Style.GuardCancel gc, long tick) {
        double cost = attackConnected ? gc.afterHit() : gc.afterMiss();
        endAttack();
        resetChain();
        if (cost > 0) {
            breath.spend(tick, Fixed.of(cost)); // legal whatever the Breath: the client can't know the cost
        }
        setState(State.NEUTRAL, tick);
    }

    /**
     * An incapacity ended: a Posture break that came during it staggers now if it's still running, a hit's longer stun
     * runs on (Rule 7.5), and otherwise it's NEUTRAL.
     */
    private void recover(long tick) {
        if (posture.staggered(tick)) {
            stagger(tick);
            return;
        }
        if (stunAfter != NONE && stunAfter > tick) {
            setState(State.HITSTUN, tick);
            stateUntil = stunAfter;
        } else {
            setState(State.NEUTRAL, tick);
        }
        stunAfter = NONE;
    }

    /** How long a guard press's claim can wait for its strike's verdict: the longest deflect window and hold cap. */
    private long claimMicros() {
        return Micros.scaled(timing.deflectMs(), timing.assistWindowScale()) + Micros.ofMillis(timing.holdCapMaxMs());
    }

    /** Whether Breathe, pressed at {@code breatheDownAt}, has been held to the radial's threshold at {@code micros}. */
    private boolean radialHeldAt(long micros) {
        return breatheDownAt != NONE && micros - breatheDownAt >= Micros.ofMillis(rules.radialHoldMs());
    }

    private void openRadial(long tick) {
        setState(State.RADIAL, tick);
        radialUntil = assistLevel >= 1
                ? NONE
                : tick
                        + (long) rules.radialLimitBeats()
                                * pulse.gridAt(Micros.ofTicks(tick)).periodTicks();
    }

    private void poll(long tick) {
        buffer.expire(tick);
        for (Slot slot : SLOTS) {
            Input in = buffer.get(slot);
            if (in != null && run(slot, in, tick, tick, buffer.minActive(slot))) {
                buffer.take(slot);
            }
        }
    }

    /**
     * Runs a queued press if its state allows it now. {@code lived} is the tick whose state judges it: the previous one
     * for a press arriving in this step, this one for a press the buffer held. Returns whether the press is used up.
     */
    private boolean run(Slot slot, Input in, long tick, long lived, long minActiveTick) {
        return switch (slot) {
            case STEP -> runStep(in, tick, lived);
            case POMMEL -> {
                if (state != State.NEUTRAL) {
                    yield false;
                }
                judgePress(in.stampMicros());
                startAttack(moves.pommel(), tick, minActiveTick, in.stampMicros(), false);
                resetChain();
                answers.add(TimedAnswers.Kind.POMMEL, startedAt(in, tick));
                yield true;
            }
            case ATTACK -> runAttack(in, tick, minActiveTick);
            case FORM -> {
                if (state != State.NEUTRAL) {
                    yield false;
                }
                FrameData form = moves.radial(in.slot());
                if (form != null) {
                    tryForm(form, in.stampMicros(), tick, minActiveTick); // short of Breath or Winded: nothing
                }
                yield true;
            }
        };
    }

    /** Rule 3.6 and ruling Q3: from NEUTRAL or GUARD, or cancelling an attack's recovery; short of Breath, nothing. */
    private boolean runStep(Input in, long tick, long lived) {
        FrameData f = attack;
        boolean cancel = state == State.ATTACK && f != null && cancels(f, Into.STEP, lived);
        if (state != State.NEUTRAL && state != State.GUARD && !cancel) {
            return false;
        }
        FrameData step = moves.step();
        long cost = Fixed.of(step.breathCost());
        if (breath.canAfford(tick, cost)) {
            if (cancel) {
                endAttack();
            }
            breath.spend(tick, cost);
            setState(State.STEP, tick);
            stateUntil = tick + step.total();
            answers.add(TimedAnswers.Kind.STEP, startedAt(in, tick));
        }
        return true;
    }

    /**
     * When what {@code in} started at step {@code tick} began: at its stamp if it ran in the step it arrived in, else at
     * the start of the tick the buffer let it run, whose pose is where it began.
     */
    private static long startedAt(Input in, long tick) {
        return Math.max(in.stampMicros(), Micros.ofTicks(tick - 1));
    }

    /** Rules 3.1 and 3.2: a contextual form, the chain's next hit or a stumble, or Chain 1. */
    private boolean runAttack(Input in, long tick, long minActiveTick) {
        long stamp = in.stampMicros();
        if (state == State.NEUTRAL && contextual(in, tick, minActiveTick)) {
            return true;
        }
        if (chainNext > 0 && stamp > chainUntil) {
            resetChain(); // pressed after the next beat's window: this press starts over
        }
        boolean inChainHit = state == State.ATTACK && attackChain >= 0;
        if (chainNext > 0 && tick >= chainFrom && (state == State.NEUTRAL || inChainHit)) {
            chainPress(stamp, tick, minActiveTick);
            return true;
        }
        if (state != State.NEUTRAL) {
            return false;
        }
        judgePress(stamp);
        startChainHit(0, stamp, tick, minActiveTick);
        return true;
    }

    /** Rule 5.5: Waterfall Basin from an attack press while falling far enough; if it can't fire, the chain gets it. */
    private boolean contextual(Input in, long tick, long minActiveTick) {
        FrameData falling = moves.falling();
        FrameData.Contextual when = falling == null ? null : falling.contextual();
        return falling != null
                && when != null
                && in.fallBlocks() >= when.minFallBlocks()
                && tryForm(falling, in.stampMicros(), tick, minActiveTick);
    }

    /** Rule 3.2: on the beat after the last hit's, the chain continues; anything else stumbles. */
    private void chainPress(long stamp, long tick, long minActiveTick) {
        long beat = beatOf(stamp);
        if (beat != OnBeat.OFF_BEAT && beat == chainBeat + 1) {
            if (state == State.ATTACK) {
                endAttack();
            }
            startChainHit(chainNext, stamp, tick, minActiveTick);
            return;
        }
        if (beat == OnBeat.OFF_BEAT) {
            rhythm.add(rhythmRules.offBeat());
        }
        if (state == State.ATTACK) {
            attackEnd += rules.stumbleTicks();
        } else {
            FrameData hit = moves.chain(chainNext - 1);
            attack = hit; // back into the last hit's recovery for the stumble's ticks
            attackStart = tick - hit.windup() - hit.active();
            attackHold = 0;
            attackEnd = tick + rules.stumbleTicks();
            attackId = nextAttackId++;
            attackBeat = NONE;
            attackOnBeat = false;
            attackMushin = false;
            mushinSpent = false;
            attackConnected = false;
            setState(State.ATTACK, tick);
        }
        attackChain = -1;
        resetChain();
    }

    private void startChainHit(int index, long pressStamp, long tick, long minActiveTick) {
        FrameData hit = moves.chain(index);
        startAttack(hit, tick, minActiveTick, pressStamp, false);
        attackChain = index;
        int opens = hit.opens(Into.LIGHT_CHAIN);
        if (opens >= 0 && index + 1 < moves.chainLength()) {
            chainNext = index + 1;
            chainBeat = pulse.nearestBeat(pressStamp);
            chainFrom = attackStart + attackHold + opens + 1;
            chainUntil = pulse.beatMicros(chainBeat + 1) + OnBeat.windowMicros(timing, Math.min(assistLevel, 2));
        } else {
            resetChain(); // the last hit completes the chain (D-035)
        }
    }

    /**
     * Fires a form judged at {@code judgedAt}; returns false if it can't (Rule 3.7). The release is judged first, as the
     * duel sim does: Mushin is every point of Rhythm, so an off-beat release drops below it and fires the plain form.
     * Mushin is paid when the finisher's active frames start, so a Feint or an interrupted windup keeps it.
     */
    private boolean tryForm(FrameData form, long judgedAt, long tick, long minActiveTick) {
        boolean offBeat = beatOf(judgedAt) == OnBeat.OFF_BEAT;
        boolean mushin = rhythm.mushinAfter(offBeat ? rhythmRules.offBeat() : 0);
        FrameData fired = mushin ? moves.atMushin(form) : form;
        if (!breath.canAfford(tick, Fixed.of(fired.breathCost())) || breath.winded(tick)) {
            return false;
        }
        if (offBeat) {
            rhythm.add(rhythmRules.offBeat());
        }
        startAttack(fired, tick, minActiveTick, judgedAt, mushin);
        resetChain();
        return true;
    }

    private void startAttack(FrameData f, long tick, long minActiveTick, long pressStamp, boolean mushin) {
        endAttack();
        attack = f;
        attackStart = tick;
        attackHold = Math.max(0, minActiveTick - (tick + f.windup()));
        attackEnd = tick + attackHold + f.total();
        attackId = nextAttackId++;
        attackChain = -1;
        attackBeat = pulse.nearestBeat(pressStamp);
        attackOnBeat = beatOf(pressStamp) != OnBeat.OFF_BEAT;
        attackMushin = mushin;
        mushinSpent = false;
        attackConnected = false;
        setState(State.ATTACK, tick);
        if (f.breathCost() > 0) {
            breath.spendForm(tick, Fixed.of(f.breathCost()));
        }
    }

    /** The attack ends or is cancelled; a form's grey bar goes with its recovery (Rule 3.5). */
    private void endAttack() {
        if (attack != null) {
            breath.endRecovery();
        }
        attack = null;
        attackChain = -1;
    }

    private void resetChain() {
        chainNext = 0;
        chainBeat = NONE;
        chainFrom = NONE;
        chainUntil = NONE;
    }

    // ------------------------------------------------------------------------------------------------------ step 6

    /** Step 6: what the world did to it at {@code tick}. */
    public void effect(Effect e, long tick) {
        if (state == State.DEAD) {
            return;
        }
        boolean movable = state != State.COLLAPSED;
        switch (e) {
            case Effect.Hit h -> {
                fought(tick);
                hit(h, tick);
            }
            case Effect.Blocked b -> {
                fought(tick);
                clearBuffer(tick);
            }
            case Effect.PostureDamage d -> {
                if (posture.damage(tick, d.amount(), d.restartDelay())) {
                    postureBroke(tick);
                }
            }
            case Effect.Knockdown k -> {
                if (movable) {
                    knockdown(tick);
                }
            }
            case Effect.Push p -> {
                if (movable) {
                    splatFrom = p.fromTick();
                    splatUntil = p.splats() ? p.splatUntil() : NONE; // the latest push decides
                    splatOpened = Math.max(Micros.ofTicks(tick), p.pressesFromMicros());
                    if (state != State.KNOCKDOWN) {
                        jumpCount = 0; // Jumps kept for an earlier push's wall aren't this one's
                    }
                }
            }
            case Effect.Contact c -> contact(c.surface(), c.micros(), tick);
            case Effect.Grabbed g -> {
                if (movable) {
                    splatUntil = NONE; // held: the push is over
                    incapacitate(tick);
                    setState(State.GRABBED, tick);
                    enteredAt = Micros.ofTicks(tick);
                    breath.setExtraDrain(tick, g.drainPerS());
                    grabEscapes = 0;
                    lastEscapeBeat = NONE;
                    escaped = false;
                    escapedAt = NONE;
                }
            }
            case Effect.Released r -> {
                if (state == State.GRABBED) {
                    recover(tick);
                }
            }
            case Effect.ClashStart c -> {
                if (movable) {
                    splatUntil = NONE;
                    incapacitate(tick);
                    setState(State.CLASH, tick);
                    enteredAt = Math.max(Micros.ofTicks(tick), c.pressesFromMicros());
                    clashPress = NONE;
                }
            }
            case Effect.ClashEnd c -> {
                if (state == State.CLASH) {
                    recover(tick);
                }
            }
            case Effect.Credit c -> credit(c.beat(), tick);
            case Effect.RhythmDelta d -> rhythm.add(d.delta());
            case Effect.Connected c -> {
                fought(tick);
                if (attack != null) {
                    attackConnected = true;
                }
            }
            case Effect.Targeted t -> rhythm.active(tick);
            case Effect.StrikeCommitted s -> {
                fought(tick);
                deflects.commit(s.id(), s.impactMicros(), s.white());
            }
            case Effect.StrikeCancelled s -> settle(deflects.remove(s.id()), false, tick);
            case Effect.StrikeDecided s -> {
                if (s.answered()) {
                    fought(tick);
                }
                settle(deflects.remove(s.id()), s.answered(), tick);
            }
            case Effect.AssistChanged a -> assistLevel = a.level();
            case Effect.Collapse c -> {
                if (movable) {
                    splatUntil = NONE;
                    incapacitate(tick);
                    setState(State.COLLAPSED, tick);
                    stateUntil = tick + c.ticks();
                }
            }
            case Effect.Die d -> {
                incapacitate(tick);
                setState(State.DEAD, tick);
            }
        }
    }

    /**
     * Rule 13.1: it dealt or took mod damage, blocked or deflected a strike, or a strike was committed on it (being
     * targeted alone doesn't count) at {@code tick}: in combat for the next {@code in_combat_ticks}, at Breath's
     * in-combat rate. The world logs these at their own ticks, so a verdict decided late raises it from its impact.
     */
    private void fought(long tick) {
        combatUntil = Math.max(combatUntil, tick + rules.inCombatTicks());
        breath.setInCombat(tick, true);
    }

    /** Rules 3.9, 7.5 and 7.6: armour absorbs a hit's class; otherwise it stuns, for whichever lasts longer. */
    private void hit(Effect.Hit h, long tick) {
        rhythm.add(rhythmRules.hitTaken());
        clearBuffer(tick);
        long until = tick + h.hitstunTicks();
        FrameData f = attack;
        switch (state) {
            case GRABBED, CLASH, COLLAPSED, DEAD -> {}
            case STAGGER, KNOCKDOWN -> stunAfter = stunAfter == NONE ? until : Math.max(stunAfter, until);
            case HITSTUN -> stateUntil = Math.max(stateUntil, until);
            case ATTACK -> {
                if (f == null) {
                    stun(tick, until);
                    break;
                }
                Phase p = phaseAt(f, tick);
                Armour a = switch (p) {
                    case WINDUP -> f.windupArmour();
                    case ACTIVE -> f.activeArmour();
                    case RECOVERY -> Armour.NONE;
                };
                if (!a.absorbs(h.hitClass())) { // absorbed: damage and a flinch, no stun
                    stun(tick, p == Phase.RECOVERY ? Math.max(attackEnd, until) : until);
                }
            }
            default -> stun(tick, until);
        }
    }

    private void stun(long tick, long until) {
        retractUnstruckPommel(tick);
        endAttack();
        resetChain();
        setState(State.HITSTUN, tick);
        stateUntil = until;
    }

    /**
     * Posture broke (Rule 4.2): STAGGER. A knockdown, a grab or a clash holds the body, so it staggers when that ends,
     * if the stagger is still running; a hitstun it cuts short runs on after it.
     */
    private void postureBroke(long tick) {
        switch (state) {
            case KNOCKDOWN, GRABBED, CLASH, STAGGER, COLLAPSED, DEAD -> {}
            case HITSTUN -> {
                stunAfter = stunAfter == NONE ? stateUntil : Math.max(stunAfter, stateUntil);
                stagger(tick);
            }
            default -> stagger(tick);
        }
    }

    private void stagger(long tick) {
        incapacitate(tick);
        setState(State.STAGGER, tick);
    }

    private void knockdown(long tick) {
        incapacitate(tick);
        setState(State.KNOCKDOWN, tick);
        enteredAt = Micros.ofTicks(tick);
        knockdownAt = tick;
        airborne = true;
        lastContact = NONE;
        techLockoutUntil = NONE;
        jumpCount = 0;
        teched = false;
        juggleCut = false;
        stateUntil = NONE;
    }

    /** A stagger, knockdown, grab, clash, collapse or death: the attack, the chain and the queued presses go. */
    private void incapacitate(long tick) {
        clearBuffer(tick);
        retractUnstruckPommel(tick);
        endAttack();
        resetChain();
    }

    /**
     * A Pommel Strike cut off in its windup (a hit, a grab, a knockdown at tick {@code tick}) never reached its active
     * frames, so it answers nothing: its timed answer goes (Rule 5.2), as a claim goes when the guard can't rise.
     */
    private void retractUnstruckPommel(long tick) {
        FrameData f = attack;
        if (state == State.ATTACK
                && f != null
                && f.attackClass() == AttackClass.POMMEL
                && phaseAt(f, tick) == Phase.WINDUP) {
            answers.dropNewest(TimedAnswers.Kind.POMMEL);
        }
    }

    /** Rule 3's buffer empties when a hit reaches the combatant; a press stamped before then comes to nothing. */
    private void clearBuffer(long tick) {
        buffer.clear();
        clearedAt = Micros.ofTicks(tick);
    }

    /**
     * Rule 9.2 (R9-05): a Jump from 4 ticks before to 1 tick after the contact Techs; any other locks Tech out. A floor
     * contact without one starts the ticks down, counted from the contact. A wall met inside a push's splat window is a
     * wall splat ({@link #splat}), unless the body is already down on the floor (Champ, 5c-3b review): a hit can push a
     * downed player, but not splat it again, so a demon can't keep it down at a wall; one still in the air can splat.
     */
    private void contact(Effect.Surface surface, long micros, long tick) {
        if (surface == Effect.Surface.WALL
                && (state != State.KNOCKDOWN || airborne)
                && splatUntil != NONE
                && micros >= Micros.ofTicks(splatFrom)
                && micros < Micros.ofTicks(splatUntil)) {
            splat(micros, tick);
            return;
        }
        if (state != State.KNOCKDOWN || !airborne || micros < Micros.ofTicks(knockdownAt)) {
            return; // once down, a second report (late, or after the air-time limit) changes nothing
        }
        lastContact = micros;
        for (int i = 0; i < jumpCount; i++) {
            if (techAgainst(micros, jumps[i])) {
                jumpCount = 0;
                tech(tick);
                return;
            }
        }
        jumpCount = 0;
        if (surface == Effect.Surface.FLOOR) {
            airborne = false;
            stateUntil = Micros.stepOf(micros) + rules.knockdownDownTicks() + rules.knockdownRiseTicks();
        } // a wall splat without a Tech: still in the air, the floor comes next
    }

    /**
     * Rule 9.4 with Rule 9.2 as written (D-078): the push met a wall at {@code micros}. The Jumps kept since the push
     * could show (D-081: one still kept from before, for a knockdown's floor, is none of the wall's) are judged against
     * it, lockout and all. A Tech cancels the splat: the window closes and a hitstun runs on (PROPOSED), and a
     * knockdown is Teched as at any contact. Without one it's a KNOCKDOWN from here, airborne, the lockout kept, so a
     * Jump up to a tick after the contact still Techs it, and a longer stun runs on after it. The contact, its Tech and
     * its Jumps are kept for the splat's log, the newest of the wall results it keeps.
     */
    private void splat(long micros, long tick) {
        splatUntil = NONE;
        System.arraycopy(wallAt, 0, wallAt, 1, WALLS - 1);
        System.arraycopy(wallTeched, 0, wallTeched, 1, WALLS - 1);
        System.arraycopy(wallJumps, 0, wallJumps, 1, WALLS - 1);
        System.arraycopy(wallTechOffset, 0, wallTechOffset, 1, WALLS - 1);
        wallAt[0] = micros;
        wallTeched[0] = false;
        wallTechOffset[0] = NONE;
        wallJumps[0] = 0;
        long techFrom = micros - Micros.ofTicks(rules.techBeforeTicks());
        long techTo = micros + Micros.ofTicks(rules.techAfterTicks());
        for (int i = 0; i < jumpCount; i++) {
            if (jumps[i] > splatOpened && jumps[i] >= techFrom && jumps[i] <= techTo) {
                wallJumps[0]++;
            }
        }
        for (int i = 0; i < jumpCount; i++) {
            if (jumps[i] > splatOpened && techAgainst(micros, jumps[i])) {
                wallTeched[0] = true;
                wallTechOffset[0] = jumps[i] - micros;
                break;
            }
        }
        jumpCount = 0;
        if (wallTeched[0]) {
            if (state == State.KNOCKDOWN) {
                tech(tick);
            }
            return;
        }
        if (state == State.HITSTUN) {
            stunAfter = stunAfter == NONE ? stateUntil : Math.max(stunAfter, stateUntil);
        }
        long lockout = techLockoutUntil;
        knockdown(tick);
        techLockoutUntil = lockout;
        lastContact = micros;
    }

    private boolean techAgainst(long contact, long jump) {
        if (techLockoutUntil != NONE && jump < techLockoutUntil) {
            lockOut(jump);
            return false;
        }
        if (jump >= contact - Micros.ofTicks(rules.techBeforeTicks())
                && jump <= contact + Micros.ofTicks(rules.techAfterTicks())) {
            return true;
        }
        lockOut(jump);
        return false;
    }

    private void tech(long tick) {
        teched = true;
        airborne = false;
        stateUntil = NONE;
        recover(tick);
    }

    // ------------------------------------------------------------------------------------------------------ step 7

    /** Step 7: the meters' own tick; a guard held while Winded drains Posture, and a drain that breaks it staggers. */
    public void meters(long tick) {
        if (combatUntil != NONE && tick >= combatUntil) {
            combatUntil = NONE; // 15 s with nothing: out of combat (Rule 13.1)
            breath.setInCombat(tick, false);
        }
        boolean drain = state == State.GUARD && breath.winded(tick);
        posture.setDrain(tick, drain ? Fixed.perTick(breathRules.windedGuardPosturePerS()) : 0);
        boolean broken = posture.staggered(tick);
        breath.tick(tick);
        posture.tick(tick);
        rhythm.tick(tick);
        if (!broken && posture.staggered(tick)) {
            postureBroke(tick);
        }
    }

    // -------------------------------------------------------------------------------------------------- helpers

    private void credit(long beat, long tick) {
        if (ledger.take(beat)) {
            rhythm.add(rhythmRules.onBeat());
            posture.restore(tick, Fixed.of(postureRules.creditRestore()));
        }
    }

    /** −1 Rhythm for an off-beat attack press or form release (Rule 2.5). */
    private void judgePress(long stamp) {
        if (beatOf(stamp) == OnBeat.OFF_BEAT) {
            rhythm.add(rhythmRules.offBeat());
        }
    }

    /** The beat a judged input is on, or {@link OnBeat#OFF_BEAT} (Auto-Rhythm counts every input on its beat). */
    private long beatOf(long stamp) {
        return OnBeat.beat(pulse, stamp, OnBeat.windowMicros(timing, assistLevel));
    }

    private boolean cancels(FrameData f, Into into, long lived) {
        long at = Math.max(attackStart, lived);
        Phase p = phaseAt(f, at);
        return f.cancels(into, p, tickInPhase(f, p, at));
    }

    private Phase phaseAt(FrameData f, long tick) {
        long frame = tick - attackStart;
        if (frame < f.windup() + attackHold) {
            return Phase.WINDUP;
        }
        if (frame < f.windup() + attackHold + f.active()) {
            return Phase.ACTIVE;
        }
        return Phase.RECOVERY;
    }

    private int tickInPhase(FrameData f, Phase p, long tick) {
        long frame = Math.max(0, tick - attackStart);
        return (int)
                switch (p) {
                    case WINDUP -> Math.min(frame, Math.max(0, f.windup() - 1)); // a hold repeats the last frame
                    case ACTIVE -> frame - f.windup() - attackHold;
                    case RECOVERY -> frame - f.windup() - attackHold - f.active();
                };
    }

    private void setState(State next, long tick) {
        if (state == State.GUARD && next != State.GUARD) {
            breath.setGuard(tick, false);
        }
        if (next == State.GUARD && state != State.GUARD) {
            breath.setGuard(tick, true);
        }
        if (state == State.RADIAL && next != State.RADIAL) {
            radialUntil = NONE;
            radialSpent = breatheDown; // closed with Breathe still held: it opens again only after a release
        }
        if (state == State.GRABBED && next != State.GRABBED) {
            breath.setExtraDrain(tick, 0);
        }
        if (next == State.GUARD || next == State.STEP) {
            resetChain();
        }
        state = next;
    }

    // ---------------------------------------------------------------------------------------------------- views

    public State state() {
        return state;
    }

    /** The attack in progress, or none. */
    public @Nullable FrameData attack() {
        return attack;
    }

    /** The attack's phase at {@code tick}, or none outside ATTACK. */
    public @Nullable Phase phase(long tick) {
        FrameData f = attack;
        return state == State.ATTACK && f != null ? phaseAt(f, tick) : null;
    }

    /**
     * The attack's frame-data frame at {@code tick}: {@code tick − start}, with the windup's last frame repeated while
     * it's held and the last recovery frame through a stumble's extra ticks.
     */
    public long frameAt(long tick) {
        FrameData f = attack;
        long frame = tick - attackStart;
        if (f == null || frame < f.windup()) {
            return frame;
        }
        return frame < f.windup() + attackHold ? f.windup() - 1 : Math.min(frame - attackHold, f.total() - 1);
    }

    public long attackStart() {
        return attackStart;
    }

    /** Ticks the windup's last frame is held because the press arrived late (Rule 2.2). */
    public long attackHold() {
        return attackHold;
    }

    /** The tick the attack's recovery ends (exclusive). */
    public long attackEnd() {
        return attackEnd;
    }

    /** The attack's instance, the key of the hit system's once-per-swing list. */
    public long attackId() {
        return attackId;
    }

    /** The beat its press was nearest, which its hits' credit goes to if the press was on the beat. */
    public long attackBeat() {
        return attackBeat;
    }

    public boolean attackOnBeat() {
        return attackOnBeat;
    }

    /** Fired at Mushin: its finisher, or the stand-in's damage for a form without one. */
    public boolean attackMushin() {
        return attackMushin;
    }

    public boolean attackConnected() {
        return attackConnected;
    }

    /** The Chain hit a continuation would fire (1 for Chain 2), or 0 with no chain open. */
    public int chainNext() {
        return chainNext;
    }

    /** The end of the window for the chain's next press, in µs. */
    public long chainUntil() {
        return chainUntil;
    }

    /** The end of the current HITSTUN, STEP, COLLAPSED or knockdown (exclusive), when one is running. */
    public long stateUntil() {
        return stateUntil;
    }

    public boolean guardDown() {
        return guardDown;
    }

    public boolean teched() {
        return teched;
    }

    public boolean juggleCut() {
        return juggleCut;
    }

    public boolean airborne() {
        return airborne;
    }

    /** The tick its knockdown began, or {@code Long.MIN_VALUE} before its first. */
    public long knockdownTick() {
        return knockdownAt;
    }

    /** How long after a contact a Jump still Techs it (Rule 9.2: 1 tick), in µs. */
    public long techAfterMicros() {
        return Micros.ofTicks(rules.techAfterTicks());
    }

    /** The end of the push's splat window (exclusive), or {@code Long.MIN_VALUE} when none is open. */
    public long splatUntil() {
        return splatUntil;
    }

    /** Its last wall contact inside a splat window (µs), or {@code Long.MIN_VALUE}. */
    public long wallContactMicros() {
        return wallAt[0];
    }

    /** Whether a Jump Teched that contact, cancelling its splat. */
    public boolean wallTeched() {
        return wallTeched[0];
    }

    /** The Jumps pressed in that contact's Tech window: more than one is mashing (D-078). */
    public int wallJumps() {
        return wallJumps[0];
    }

    /** The Teching Jump's stamp less the contact's, or {@code Long.MIN_VALUE} if it wasn't Teched. */
    public long wallTechOffsetMicros() {
        return wallTechOffset[0];
    }

    /**
     * Which of its last four wall contacts inside a splat window came at {@code micros} (0 the newest), or −1: none did,
     * or it's too old to be kept. A splat is settled by its own contact's result, whatever came since.
     */
    public int wallIndex(long micros) {
        for (int i = 0; i < WALLS; i++) {
            if (wallAt[i] == micros && micros != NONE) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a Jump Teched its {@code i}th last wall contact ({@link #wallIndex}). */
    public boolean wallTeched(int i) {
        return wallTeched[i];
    }

    /** The Jumps pressed in its {@code i}th last wall contact's Tech window. */
    public int wallJumps(int i) {
        return wallJumps[i];
    }

    /** Its {@code i}th last wall contact's Teching Jump less the contact, or {@code Long.MIN_VALUE}. */
    public long wallTechOffsetMicros(int i) {
        return wallTechOffset[i];
    }

    public int grabEscapes() {
        return grabEscapes;
    }

    /** It broke free of the grab with on-beat presses and left GRABBED; the grab system ends the grabber's hold. */
    public boolean escaped() {
        return escaped;
    }

    /** The tick it broke free of its last grab, or {@code Long.MIN_VALUE}: the grabber's hold ends there. */
    public long escapedAt() {
        return escapedAt;
    }

    /** The first attack press during a clash, or {@code Long.MIN_VALUE}: the clash judge's input. */
    public long clashPress() {
        return clashPress;
    }

    /** A claimed Feint's cost still waiting on its strike's verdict, or 0. */
    public double pendingFeint() {
        return pendingFeint;
    }

    public int assistLevel() {
        return assistLevel;
    }

    /** Whether it's in combat at {@code tick} (Rule 13.1), asked at or after the last tick it ran. */
    public boolean inCombat(long tick) {
        return tick < combatUntil;
    }

    /** The tick its combat flag drops at, if nothing more happens; {@code Long.MIN_VALUE} out of combat. */
    public long combatUntil() {
        return combatUntil;
    }

    public Breath breath() {
        return breath;
    }

    public Posture posture() {
        return posture;
    }

    public Rhythm rhythm() {
        return rhythm;
    }

    public CreditLedger ledger() {
        return ledger;
    }

    public InputBuffer buffer() {
        return buffer;
    }

    /** The strikes it's involved in and its guard presses' claims on them. Read it; don't change it. */
    public DeflectJudge deflects() {
        return deflects;
    }

    /** When its recent Steps, Jumps and Pommel Strikes started. */
    public TimedAnswers answers() {
        return answers;
    }

    // ------------------------------------------------------------------------------------------------ snapshots

    /** Becomes an exact copy of {@code o}, which must share its rules, moveset and Pulse. */
    public void copyFrom(Combatant o) {
        if (o.moves != moves || o.pulse != pulse || !o.rules.equals(rules)) {
            throw new IllegalArgumentException("a combatant copies only one with the same rules, moveset and Pulse");
        }
        breath.copyFrom(o.breath);
        posture.copyFrom(o.posture);
        rhythm.copyFrom(o.rhythm);
        ledger.copyFrom(o.ledger);
        buffer.copyFrom(o.buffer);
        deflects.copyFrom(o.deflects);
        answers.copyFrom(o.answers);
        assistLevel = o.assistLevel;
        state = o.state;
        stateUntil = o.stateUntil;
        stunAfter = o.stunAfter;
        attack = o.attack;
        attackStart = o.attackStart;
        attackHold = o.attackHold;
        attackEnd = o.attackEnd;
        attackId = o.attackId;
        attackChain = o.attackChain;
        attackBeat = o.attackBeat;
        attackOnBeat = o.attackOnBeat;
        attackMushin = o.attackMushin;
        attackConnected = o.attackConnected;
        nextAttackId = o.nextAttackId;
        chainNext = o.chainNext;
        chainBeat = o.chainBeat;
        chainFrom = o.chainFrom;
        chainUntil = o.chainUntil;
        guardDown = o.guardDown;
        guardSince = o.guardSince;
        breatheDown = o.breatheDown;
        breatheDownAt = o.breatheDownAt;
        radialSpent = o.radialSpent;
        radialUntil = o.radialUntil;
        lastFeintTick = o.lastFeintTick;
        lastFeintCost = o.lastFeintCost;
        pendingFeint = o.pendingFeint;
        pendingFeintTick = o.pendingFeintTick;
        pendingFeintPress = o.pendingFeintPress;
        feintAnswered = o.feintAnswered;
        knockdownAt = o.knockdownAt;
        airborne = o.airborne;
        lastContact = o.lastContact;
        techLockoutUntil = o.techLockoutUntil;
        System.arraycopy(o.jumps, 0, jumps, 0, JUMPS);
        jumpCount = o.jumpCount;
        teched = o.teched;
        juggleCut = o.juggleCut;
        splatFrom = o.splatFrom;
        splatUntil = o.splatUntil;
        splatOpened = o.splatOpened;
        System.arraycopy(o.wallAt, 0, wallAt, 0, WALLS);
        System.arraycopy(o.wallTeched, 0, wallTeched, 0, WALLS);
        System.arraycopy(o.wallJumps, 0, wallJumps, 0, WALLS);
        System.arraycopy(o.wallTechOffset, 0, wallTechOffset, 0, WALLS);
        grabEscapes = o.grabEscapes;
        lastEscapeBeat = o.lastEscapeBeat;
        escaped = o.escaped;
        escapedAt = o.escapedAt;
        clashPress = o.clashPress;
        mushinSpent = o.mushinSpent;
        clearedAt = o.clearedAt;
        enteredAt = o.enteredAt;
        combatUntil = o.combatUntil;
    }

    /** The same state as {@code o} in every field the machine reads (the meters' versions aside). */
    public boolean sameAs(Combatant o) {
        boolean jumpsSame = jumpCount == o.jumpCount;
        for (int i = 0; jumpsSame && i < jumpCount; i++) {
            jumpsSame = jumps[i] == o.jumps[i];
        }
        return jumpsSame
                && breath.sameAs(o.breath)
                && posture.sameAs(o.posture)
                && rhythm.sameAs(o.rhythm)
                && ledger.sameAs(o.ledger)
                && buffer.sameAs(o.buffer)
                && deflects.sameAs(o.deflects)
                && answers.sameAs(o.answers)
                && assistLevel == o.assistLevel
                && state == o.state
                && stateUntil == o.stateUntil
                && stunAfter == o.stunAfter
                && Objects.equals(attack, o.attack)
                && attackStart == o.attackStart
                && attackHold == o.attackHold
                && attackEnd == o.attackEnd
                && attackId == o.attackId
                && attackChain == o.attackChain
                && attackBeat == o.attackBeat
                && attackOnBeat == o.attackOnBeat
                && attackMushin == o.attackMushin
                && attackConnected == o.attackConnected
                && nextAttackId == o.nextAttackId
                && chainNext == o.chainNext
                && chainBeat == o.chainBeat
                && chainFrom == o.chainFrom
                && chainUntil == o.chainUntil
                && guardDown == o.guardDown
                && guardSince == o.guardSince
                && breatheDown == o.breatheDown
                && breatheDownAt == o.breatheDownAt
                && radialSpent == o.radialSpent
                && radialUntil == o.radialUntil
                && lastFeintTick == o.lastFeintTick
                && Double.compare(lastFeintCost, o.lastFeintCost) == 0
                && Double.compare(pendingFeint, o.pendingFeint) == 0
                && pendingFeintTick == o.pendingFeintTick
                && pendingFeintPress == o.pendingFeintPress
                && feintAnswered == o.feintAnswered
                && knockdownAt == o.knockdownAt
                && airborne == o.airborne
                && lastContact == o.lastContact
                && techLockoutUntil == o.techLockoutUntil
                && teched == o.teched
                && juggleCut == o.juggleCut
                && splatFrom == o.splatFrom
                && splatUntil == o.splatUntil
                && splatOpened == o.splatOpened
                && Arrays.equals(wallAt, o.wallAt)
                && Arrays.equals(wallTeched, o.wallTeched)
                && Arrays.equals(wallJumps, o.wallJumps)
                && Arrays.equals(wallTechOffset, o.wallTechOffset)
                && grabEscapes == o.grabEscapes
                && lastEscapeBeat == o.lastEscapeBeat
                && escaped == o.escaped
                && escapedAt == o.escapedAt
                && clashPress == o.clashPress
                && mushinSpent == o.mushinSpent
                && clearedAt == o.clearedAt
                && enteredAt == o.enteredAt
                && combatUntil == o.combatUntil;
    }
}
