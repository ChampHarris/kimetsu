package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.BreathRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Into;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.Phase;
import dev.champ.kimetsu.core.judge.OnBeat;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Rhythm;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.net.NetMessage;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * What the client's own presses start on the server, until the client predicts its state (step 5; plan Risk 8,
 * D-107's review): its attack, its Step, its guard and its buffer, by the server's own rules ({@code Combatant}), so the
 * radial's "blocked", the Step's dash and the attack gate all read one answer. Kept free of game state, so its cases
 * are a table test.
 *
 * <p>Ticks are engine steps, run in the server's order ({@link OwnPresses}): a press acts in the step it's judged
 * into, before that step's end; its stamp, the combat clock's µs as the server judges it, judges its beat, and the
 * Breath it spends is counted at that step. Each press runs at once if the state its own moves left allows it;
 * otherwise the buffer keeps it ({@code buffer_ticks}, the newest of each kind), and {@link #tick} runs it at the first
 * step's end that does, as the server's step polls its buffer: a Step by the attack's Step cancel or once its state is
 * NEUTRAL or GUARD, a Pommel from NEUTRAL, an attack from NEUTRAL or into the Light Chain's window, which closes at its
 * deadline whether or not a press waits for it. The guard comes up
 * {@code guard_hold_ticks} after its press, so offence pressed before then runs from NEUTRAL. A guard press or a Step
 * drops a held attack and Pommel; the radial aiming (the server's RADIAL) holds them all, and its close with nothing
 * fired lets them run, unless a hit closed it. A guard press in a form's Feint window ends it (its Breath back, the Feint's paid);
 * Water's guard-cancel ends an attack at a guard pressed or held in its recovery. A form fires only on the Breath the
 * last snapshot shows, less what its own moves have spent since, and not Winded; at Mushin on the beat it's the
 * finisher. What the server alone knows (a hit, a clash, a grab) it learns from the snapshot: such a state holds every
 * Step, and the rest waits for step 5.
 */
final class OwnMoves {
    static final long NONE = Long.MIN_VALUE;

    /** The rules it reads, one set per data generation and assist level, with the style's Pulse. */
    record Rules(Moveset moves, CombatRules rules, BreathRules breath, int assist, PulseSchedule pulse) {
        static Rules of(Moveset moves, CombatRules rules, BreathRules breath, int assist) {
            return new Rules(
                    moves,
                    rules,
                    breath,
                    assist,
                    new PulseSchedule(moves.style().beatTicks()));
        }
    }

    // the attack its presses started: its frames, first tick, the tick it's over at, its Light Chain hit (−1: none)
    private @Nullable FrameData attack;
    private long attackStart = NONE;
    private long attackEnd = NONE;
    private int chainHit = -1;
    // a form's: the Breath it paid (−1: not a form, or its grey bar is gone), which a Feint gives back and a Recovery
    // Breath refunds a share of, and the engine step it paid at, for a release that never went
    private long formCost = -1;
    private long formAt = NONE;
    private boolean ownForm; // the attack running is a form its own release fired: its grey bar is its own to tell
    // the Light Chain's next hit (Rule 3.2): from tick chainFrom, for a press stamped by chainUntil
    private int chainNext;
    private long chainBeat = NONE;
    private long chainFrom = NONE;
    private long chainUntil = NONE;
    private long stepEnd = NONE; // its Step runs until this tick
    private long stepRan = NONE; // and started at this one, until the dash takes it
    private boolean guard;
    private long guardSince = NONE; // the tick its guard press acted in
    private boolean radial; // aiming: the server's RADIAL
    private boolean closing; // its aim ended at the limit: the server closes it at this tick's poll
    // the buffer (Rule 3): the newest press of each kind its state held back, by the tick it acts in
    private long bufferedStep = NONE;
    private long bufferedPommel = NONE;
    private long bufferedAttack = NONE;
    private long bufferedAttackStamp;
    // Breath spent (below 0: given back): the engine step and cost of each spend the last snapshot it ticked with can't
    // show yet, however many (D-110's review); those it can are let go at the next tick
    private long[] spentAt = new long[8];
    private long[] spent = new long[8];
    private int spends;
    private long lastFeintStep = NONE;
    private double lastFeintCost;
    // the Inhale (Combatant.inhale): a Breathe press, which its release may make a tap, judged as its press found it
    private Inhale tap = Inhale.NONE;
    private long tapAt = NONE; // its stamp
    private long tapRefund; // a Recovery Breath's: the form's grey bar
    private long inhaleBeat = NONE; // the last on-beat Inhale's beat: one a beat (Breath.inhale)
    private long windedUntil = NONE; // the engine step an off-beat Recovery Breath winds until

    /** What an Inhale does where it's pressed. */
    private enum Inhale {
        NONE, // mid-swing, mid-Step, guarding
        PLAIN, // from NEUTRAL: on the beat, Breath
        RECOVERY // in a form's recovery: on the beat, Breath and the form's back; off it, Winded
    }

    // ------------------------------------------------------------------------------------------------- presses

    /**
     * An Attack press acting in {@code tick}, stamped {@code micros}. Each press takes its kind's place in the buffer
     * ({@code InputBuffer.offer}), so one that runs at once leaves none of its kind held.
     */
    void attack(long tick, long micros, Rules r) {
        bufferedAttack = NONE;
        if (!runAttack(tick, micros, r, true)) {
            bufferedAttack = tick;
            bufferedAttackStamp = micros;
        }
    }

    /** A Pommel press acting in {@code tick}: from NEUTRAL only. */
    void pommel(long tick, Rules r) {
        bufferedPommel = NONE;
        if (state(tick, true, r) == State.NEUTRAL) {
            start(r.moves().pommel(), tick);
            resetChain();
        } else {
            bufferedPommel = tick;
        }
    }

    /** A Step press acting in {@code tick}: it drops a held attack and Pommel, then starts or waits ({@link #takeStep}). */
    void step(long tick, long micros, NetMessage.@Nullable Snapshot shown, Rules r) {
        dropOffence();
        bufferedStep = NONE;
        if (!runStep(tick, tick - 1, micros, shown, r, true)) {
            bufferedStep = tick;
        }
    }

    /**
     * A guard press acting in {@code tick} ({@code Combatant.guardDown}): it drops a held attack and Pommel, feints a
     * form in its Feint window, cancels a recovery with Water's guard-cancel, and closes the radial.
     */
    void guardDown(long tick, long micros, NetMessage.@Nullable Snapshot shown, Rules r) {
        guard = true;
        guardSince = tick;
        dropOffence();
        FrameData f = attack;
        if (radial) {
            radial = false; // the radial cancels into GUARD
            closing = false;
        } else if (f != null && tick <= attackEnd) { // in its last tick it's still ATTACK until the server's poll
            if (cancels(f, Into.FEINT, tick - 1)) {
                feint(micros, r.rules().machine());
            } else if (r.moves().style().guardCancel() != null
                    && phaseAt(f, Math.max(attackStart, tick - 1)) == Phase.RECOVERY) {
                endAttack();
                resetChain();
            }
        }
    }

    /**
     * Breathe's press acting in {@code tick}, stamped {@code micros}: if its release comes before the radial's hold it
     * was a tap, which the server makes an Inhale at the press ({@code Timeline.addTapInhale}), so it's judged now. Until
     * the release, nothing tells whether it's a tap, so a Step pressed meanwhile counts no Inhale (step 5 reconciles it).
     */
    void breathe(long tick, long micros, Rules r) {
        tap = inhaleAt(tick, r);
        tapAt = micros;
    }

    /** An Inhale key press acting in {@code tick}, stamped {@code micros}: an Inhale at once. */
    void inhale(long tick, long micros, Rules r) {
        inhale(inhaleAt(tick, r), micros, r);
    }

    /** The guard let go: what it held runs at the next tick start, within the buffer. */
    void guardUp() {
        guard = false;
    }

    /**
     * Breathe's key-up acting in {@code tick}, stamped {@code micros}: from the open radial, {@code form} (a slotted
     * release's; null: none) fires if the Breath the snapshot shows covers it and it isn't Winded (Rule 3.7); either
     * way the radial is closed. Returns whether it fired.
     */
    boolean release(@Nullable FrameData form, long tick, long micros, NetMessage.@Nullable Snapshot shown, Rules r) {
        if (tapAt != NONE
                && micros - tapAt < Micros.ofMillis(r.rules().machine().radialHoldMs())) {
            inhale(tap, tapAt, r);
        }
        tapAt = NONE;
        if (!radial) {
            return false; // a tap, or a release with no aim: the server opens nothing of its own for it
        }
        radial = false;
        closing = false;
        if (form == null) {
            return false;
        }
        boolean offBeat = beat(micros, r) == OnBeat.OFF_BEAT;
        FrameData fired = mushin(shown, offBeat, r) ? r.moves().atMushin(form) : form;
        long cost = Fixed.of(fired.breathCost());
        long at = Micros.stepOf(micros);
        if (windedUntil != NONE && at < windedUntil) {
            return false; // its own Recovery Breath off the beat, which the snapshot may not show yet
        }
        if (shown != null && (winded(shown) || breath(at, shown) < cost)) {
            return false;
        }
        start(fired, tick);
        formCost = cost;
        formAt = at;
        ownForm = true;
        spend(at, cost);
        resetChain();
        return true;
    }

    /**
     * The radial aims or not ({@link RadialStroke}), the snapshot showing {@code shown}. An aim that ends with nothing
     * fired ran into the limit, which the server closes it at in a tick's poll ({@code Combatant.advance}): presses
     * acting in that tick still meet it, and what it held runs at the poll. A hit closed it already, and emptied the
     * buffer.
     */
    void radial(boolean open, NetMessage.@Nullable Snapshot shown) {
        if (open) {
            radial = true;
            closing = false;
        } else if (radial && shown != null && !RadialStroke.opens(shown.state())) {
            radial = false;
            closing = false;
            clearBuffer();
        } else if (radial) {
            closing = true;
        }
    }

    /**
     * Engine step {@code tick} ends at {@code micros} ({@code Combatant.advance}), the spends {@code shown} already
     * shows let go: the radial's limit closes it, a guard held into a recovery cancels it, a guard that comes up ends
     * the Light Chain, presses held past the buffer go, the rest that can run now do, Step first, and a Light Chain past
     * its window's deadline starts over.
     */
    void tick(long tick, long micros, NetMessage.@Nullable Snapshot shown, Rules r) {
        if (shown != null) {
            forget(shown.settledTick());
        }
        if (closing) {
            radial = false; // its limit
            closing = false;
        }
        FrameData f = attack;
        if (f != null
                && tick < attackEnd
                && guard
                && r.moves().style().guardCancel() != null
                && phaseAt(f, tick) == Phase.RECOVERY) {
            endAttack();
            resetChain();
        }
        if (state(tick, false, r) == State.GUARD) {
            resetChain(); // Combatant.setState: GUARD ends the chain
        }
        int keep = r.rules().machine().bufferTicks();
        if (bufferedStep != NONE && tick > bufferedStep + keep) {
            bufferedStep = NONE;
        }
        if (bufferedPommel != NONE && tick > bufferedPommel + keep) {
            bufferedPommel = NONE;
        }
        if (bufferedAttack != NONE && tick > bufferedAttack + keep) {
            bufferedAttack = NONE;
        }
        if (bufferedStep != NONE && runStep(tick, tick, micros, shown, r, false)) {
            bufferedStep = NONE;
        }
        if (bufferedPommel != NONE && state(tick, false, r) == State.NEUTRAL) {
            bufferedPommel = NONE;
            start(r.moves().pommel(), tick);
            resetChain();
        }
        if (bufferedAttack != NONE && runAttack(tick, bufferedAttackStamp, r, false)) {
            bufferedAttack = NONE;
        }
        if (chainNext > 0 && micros >= chainUntil) {
            resetChain(); // no press by the end of the next beat's window: it starts over, a held one too
        }
    }

    /** The slotted release that would have started a form in {@code tick} never went (the lost focus restamped it). */
    void withdraw(long tick) {
        if (attack != null && formCost >= 0 && attackStart == tick) {
            if (formAt != NONE) {
                spend(formAt, -formCost); // at its own step: never paid, whichever snapshot comes
            }
            endAttack();
        }
    }

    /** The level went (a dimension change, a respawn): nothing of its own carries over. Its guard follows the key. */
    void reset() {
        endAttack();
        resetChain();
        stepEnd = NONE;
        stepRan = NONE;
        radial = false;
        closing = false;
        clearBuffer();
    }

    /** A new timeline ({@code ClockEpoch}): its spends' steps and its stamps were on the old one. */
    void epoch() {
        spends = 0;
        formAt = NONE;
        lastFeintStep = NONE;
        tapAt = NONE;
        inhaleBeat = NONE;
        windedUntil = NONE;
        resetChain();
        clearBuffer();
    }

    // ------------------------------------------------------------------------------------------------- views

    /** Whether its own guard, attack or Step keeps the server from NEUTRAL in {@code tick}: the radial can't open. */
    boolean blocked(long tick) {
        return guard || tick < stepEnd || (attack != null && tick < attackEnd);
    }

    /** The attack its own presses run in {@code tick}, or null. */
    @Nullable
    FrameData attackAt(long tick) {
        return attack != null && tick >= attackStart && tick < attackEnd ? attack : null;
    }

    /**
     * What the HUD shows of its own moves at engine step {@code tick} (D-141's call 7), the snapshot showing
     * {@code shown}, into {@code out}: the Breath it spent that the snapshot can't show yet; the grey segment, its own
     * form's while that form runs (none once a Recovery Breath took it, refunded or lost, which a snapshot shows only a
     * round trip later), else the snapshot's; and Winded, by its own off-beat Recovery Breath to the step it ends, or
     * by a snapshot showing one its own moves don't account for.
     */
    void hud(long tick, NetMessage.@Nullable Snapshot shown, HudReadout out) {
        long settled = shown != null ? shown.settledTick() : Long.MIN_VALUE;
        long s = 0;
        for (int i = 0; i < spends; i++) {
            if (spentAt[i] > settled) {
                s += spent[i];
            }
        }
        out.spent = s;
        out.grey = ownForm && attackAt(tick) != null ? Math.max(formCost, 0) : shown != null ? shown.grey() : 0;
        out.winded = windedAt(tick, shown);
    }

    /**
     * Whether an attack press acting in {@code tick} would fire the style's falling form, were the fall far enough
     * ({@code Combatant.contextual}, Rule 5.5): from NEUTRAL as far as its own moves and the snapshot tell (no guard,
     * attack, Step or radial of its own, nothing only the server knows holding it), not Winded, and with the Breath for
     * the form, its Mushin finisher's at Mushin. The contextual preview's gate (5d-3b); allocates (the Mushin check),
     * so it's asked once a client tick.
     */
    boolean canStartFalling(long tick, NetMessage.@Nullable Snapshot shown, Rules r) {
        FrameData falling = r.moves().falling();
        if (falling == null
                || falling.contextual() == null
                || blocked(tick)
                || radial
                || !stepAllowed(shown)
                || windedAt(tick, shown)) {
            return false;
        }
        FrameData fired = mushin(shown, false, r) ? r.moves().atMushin(falling) : falling;
        return shown == null || breath(tick, shown) >= Fixed.of(fired.breathCost());
    }

    /** The HUD's numbers from its own moves ({@link #hud}), one reused instance. */
    static final class HudReadout {
        /** Breath spent that the snapshot can't show yet, in {@code Fixed}'s units (below 0: given back). */
        long spent;

        /** The grey segment, in {@code Fixed}'s units. */
        long grey;

        boolean winded;
    }

    /** How many of its spends it holds: those the last snapshot it ticked with can't show yet (for its test). */
    int spendsHeld() {
        return spends;
    }

    /** The first tick of a Step that started since the last call, for the dash, or {@link #NONE}. */
    long takeStep() {
        long t = stepRan;
        stepRan = NONE;
        return t;
    }

    // ------------------------------------------------------------------------------------------------- the rules

    /**
     * The server's state in {@code tick} as its own moves left it: at the tick's poll ({@code Combatant.advance}), its
     * timers run; at a press ({@code atPress}), as the last tick left it: an attack or Step ending in this tick still
     * runs (the press waits in the buffer for the poll, which the radial may open before), and a guard pressed
     * {@code guard_hold_ticks} ago is still rising, so offence runs from NEUTRAL.
     */
    private State state(long tick, boolean atPress, Rules r) {
        long last = atPress ? tick - 1 : tick; // the last tick whose timers ran
        if (radial) {
            return State.RADIAL;
        }
        if (last < stepEnd) {
            return State.STEP;
        }
        if (attack != null && last < attackEnd) {
            return State.ATTACK;
        }
        return guard && last >= guardSince + r.rules().machine().guardHoldTicks() ? State.GUARD : State.NEUTRAL;
    }

    /**
     * {@code Combatant.runStep}: from NEUTRAL or GUARD, or cancelling an attack whose Step cancel is open at
     * {@code lived}; short of Breath, or in a state only the snapshot shows, nothing. Returns whether it's used up.
     */
    private boolean runStep(
            long tick, long lived, long micros, NetMessage.@Nullable Snapshot shown, Rules r, boolean atPress) {
        State s = state(tick, atPress, r);
        FrameData f = attack;
        boolean cancel = s == State.ATTACK && f != null && cancels(f, Into.STEP, lived);
        if (s != State.NEUTRAL && s != State.GUARD && !cancel) {
            return false;
        }
        FrameData step = r.moves().step();
        long cost = Fixed.of(step.breathCost());
        long at = Micros.stepOf(micros);
        if (stepAllowed(shown) && (shown == null || breath(at, shown) >= cost)) {
            if (cancel) {
                endAttack();
            }
            resetChain();
            stepEnd = tick + step.total();
            stepRan = tick;
            spend(at, cost);
        }
        return true;
    }

    /**
     * The server's state as its last snapshot shows it (null: a fresh fighter, standing) lets a Step through: a hit, a
     * hold or a fall doesn't; an attack, a Step or the radial are its own presses' to tell, as for the radial.
     */
    private static boolean stepAllowed(NetMessage.@Nullable Snapshot shown) {
        return RadialStroke.opens(shown != null ? shown.state() : null);
    }

    /**
     * {@code Combatant.runAttack} (the falling form aside): into the Light Chain's window its next hit or a stumble,
     * from NEUTRAL Chain 1. Returns whether it's used up.
     */
    private boolean runAttack(long tick, long stamp, Rules r, boolean atPress) {
        State s = state(tick, atPress, r);
        if (chainNext > 0 && stamp > chainUntil) {
            resetChain(); // pressed after the next beat's window: it starts over
        }
        boolean inChainHit = s == State.ATTACK && chainHit >= 0;
        if (chainNext > 0 && tick >= chainFrom && (s == State.NEUTRAL || inChainHit)) {
            chainPress(tick, stamp, s == State.ATTACK, r);
            return true;
        }
        if (s != State.NEUTRAL) {
            return false;
        }
        startChainHit(0, tick, stamp, r);
        return true;
    }

    /** {@code Combatant.chainPress}: on the beat after the last hit's, its next hit; anything else stumbles. */
    private void chainPress(long tick, long stamp, boolean inAttack, Rules r) {
        long beat = beat(stamp, r);
        if (beat != OnBeat.OFF_BEAT && beat == chainBeat + 1) {
            startChainHit(chainNext, tick, stamp, r);
            return;
        }
        int stumble = r.rules().machine().stumbleTicks();
        if (inAttack) {
            attackEnd += stumble;
        } else {
            FrameData hit = r.moves().chain(chainNext - 1); // back into the last hit's recovery for the stumble
            attack = hit;
            attackStart = tick - hit.windup() - hit.active();
            attackEnd = tick + stumble;
            formCost = -1;
            ownForm = false;
        }
        chainHit = -1;
        resetChain();
    }

    private void startChainHit(int index, long tick, long stamp, Rules r) {
        FrameData hit = r.moves().chain(index);
        start(hit, tick);
        chainHit = index;
        int opens = hit.opens(Into.LIGHT_CHAIN);
        if (opens >= 0 && index + 1 < r.moves().chainLength()) {
            chainNext = index + 1;
            chainBeat = r.pulse().nearestBeat(stamp);
            chainFrom = tick + opens + 1;
            chainUntil = r.pulse().beatMicros(chainBeat + 1)
                    + OnBeat.windowMicros(r.rules().timing(), Math.min(r.assist(), 2));
        } else {
            resetChain(); // the last hit completes the chain
        }
    }

    private void start(FrameData f, long tick) {
        attack = f;
        attackStart = tick;
        attackEnd = tick + f.total();
        chainHit = -1;
        formCost = -1;
        ownForm = false;
    }

    /**
     * {@code Combatant.inhale}, as its press at {@code tick} finds it: from NEUTRAL a plain Inhale; in the recovery of a
     * form that has one, with its grey bar still to come back, a Recovery Breath; anywhere else nothing.
     */
    private Inhale inhaleAt(long tick, Rules r) {
        State s = state(tick, true, r);
        FrameData f = attack;
        if (s == State.NEUTRAL) {
            return Inhale.PLAIN;
        }
        if (s == State.ATTACK
                && f != null
                && f.recoveryBreath()
                && formCost > 0
                && phaseAt(f, Math.max(attackStart, tick - 1)) == Phase.RECOVERY) {
            tapRefund = Math.round(formCost * r.breath().recoveryRefundShare());
            return Inhale.RECOVERY;
        }
        return Inhale.NONE;
    }

    /**
     * An Inhale stamped {@code micros} ({@code Combatant.plainInhale}, {@code recoveryBreath}): on the beat, once a beat,
     * the Inhale's Breath, and a Recovery Breath's refund with it; a Recovery Breath off the beat winds. Its grey bar is
     * gone either way. Counted at the step it's stamped in, as the server does.
     */
    private void inhale(Inhale kind, long micros, Rules r) {
        if (kind == Inhale.NONE) {
            return;
        }
        long beat = beat(micros, r);
        long at = Micros.stepOf(micros);
        long gain = 0;
        if (beat != OnBeat.OFF_BEAT && beat != inhaleBeat) {
            inhaleBeat = beat;
            gain = Fixed.of(r.breath().onBeatInhale());
        }
        if (kind == Inhale.RECOVERY) {
            formCost = -1; // the grey bar: refunded or lost
            if (beat != OnBeat.OFF_BEAT) {
                gain += tapRefund;
            } else {
                windedUntil = at + r.breath().windedTicks();
            }
        }
        if (gain > 0) {
            spend(at, -gain);
        }
    }

    /**
     * {@code Combatant.feint}: the form never fired, so its Breath comes back at the Feint ({@code Breath.refundForm}),
     * whether or not a snapshot shows it paid yet, and the Feint pays its own.
     */
    private void feint(long micros, MachineRules m) {
        long back = Math.max(formCost, 0);
        long at = Micros.stepOf(micros);
        double cost = lastFeintStep != NONE && at - lastFeintStep <= m.feintDoublingTicks()
                ? lastFeintCost * 2
                : m.feintBreath();
        spend(at, Fixed.of(cost) - back);
        lastFeintStep = at;
        lastFeintCost = cost;
        endAttack();
    }

    private void endAttack() {
        attack = null;
        attackEnd = NONE;
        chainHit = -1;
        formCost = -1;
        ownForm = false;
    }

    private void resetChain() {
        chainNext = 0;
        chainBeat = NONE;
        chainFrom = NONE;
        chainUntil = NONE;
    }

    private void dropOffence() {
        bufferedPommel = NONE;
        bufferedAttack = NONE;
    }

    private void clearBuffer() {
        bufferedStep = NONE;
        dropOffence();
    }

    /** {@code Combatant.cancels}: whether {@code f}'s cancel into {@code into} is open at {@code lived}. */
    private boolean cancels(FrameData f, Into into, long lived) {
        long at = Math.max(attackStart, lived);
        Phase p = phaseAt(f, at);
        long frame = Math.max(0, at - attackStart);
        int inPhase = (int)
                switch (p) {
                    case WINDUP -> Math.min(frame, Math.max(0, f.windup() - 1));
                    case ACTIVE -> frame - f.windup();
                    case RECOVERY -> frame - f.windup() - f.active();
                };
        return f.cancels(into, p, inPhase);
    }

    /** The phase of {@code f} at {@code tick}: the client holds no windup (Rule 2.2's hold is the server's). */
    private Phase phaseAt(FrameData f, long tick) {
        long frame = tick - attackStart;
        if (frame < f.windup()) {
            return Phase.WINDUP;
        }
        return frame < f.windup() + f.active() ? Phase.ACTIVE : Phase.RECOVERY;
    }

    private static long beat(long micros, Rules r) {
        return OnBeat.beat(r.pulse(), micros, OnBeat.windowMicros(r.rules().timing(), r.assist()));
    }

    /**
     * Whether the snapshot shows a Winded its own moves don't account for. One settled inside its own off-beat Recovery
     * Breath's spell shows that spell, whose end it knows to the step: the server's {@code Breath.wind} sets it
     * outright, and Breath run down to 0 never stretches a spell already running. Anything else is the server's, with
     * no end the snapshot tells, so it stands.
     */
    private boolean winded(NetMessage.Snapshot shown) {
        return shown.winded() && (windedUntil == NONE || shown.settledTick() >= windedUntil);
    }

    /** Winded at engine step {@code tick}: its own off-beat Recovery Breath's spell, or one only the snapshot shows. */
    private boolean windedAt(long tick, NetMessage.@Nullable Snapshot shown) {
        return (windedUntil != NONE && tick < windedUntil) || (shown != null && winded(shown));
    }

    /** {@code Combatant.tryForm}'s Mushin: every point of Rhythm after the release's own judgment. */
    private static boolean mushin(NetMessage.@Nullable Snapshot shown, boolean offBeat, Rules r) {
        if (shown == null) {
            return false;
        }
        Rhythm rhythm = new Rhythm(r.rules().rhythm());
        rhythm.add(shown.rhythmPoints());
        return rhythm.mushinAfter(offBeat ? r.rules().rhythm().offBeat() : 0);
    }

    /** The Breath at engine step {@code at}: the snapshot's, less what its own moves spent after it was settled. */
    private long breath(long at, NetMessage.Snapshot shown) {
        long b = shown.breath().at(at);
        for (int i = 0; i < spends; i++) {
            if (spentAt[i] > shown.settledTick()) {
                b -= spent[i];
            }
        }
        return b;
    }

    private void spend(long at, long cost) {
        if (spends == spent.length) { // more than a round trip's worth: rare, so it grows rather than forgets
            spentAt = Arrays.copyOf(spentAt, spends * 2);
            spent = Arrays.copyOf(spent, spends * 2);
        }
        spentAt[spends] = at;
        spent[spends] = cost;
        spends++;
    }

    /** Lets go of the spends a snapshot settled at engine step {@code settled} shows already. */
    private void forget(long settled) {
        int kept = 0;
        for (int i = 0; i < spends; i++) {
            if (spentAt[i] > settled) {
                spentAt[kept] = spentAt[i];
                spent[kept] = spent[i];
                kept++;
            }
        }
        spends = kept;
    }
}
