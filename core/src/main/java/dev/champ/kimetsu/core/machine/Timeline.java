package dev.champ.kimetsu.core.machine;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.MachineRules;
import java.util.ArrayList;
import java.util.List;

/**
 * One player's {@link Combatant} through time: presses act at their stamps (Rule 2.2), one strike is decided at a time
 * (Rule 2.3), and a strike sees the player as they were at its impact, re-derived when presses arrive late (Rule 2.10).
 * The server runs one per player. That player's client runs one too, to predict its own presses: without the barrier
 * (it never calls {@link #commitStrike}), with a history long enough to cover its RTT, and reconciling with
 * {@link #rebase}.
 *
 * <p>Each server tick: {@link #beginTick} applies the presses that arrived (step 1) and advances the machine (step 3);
 * the world sweeps the player's active windows ({@link #swept}) and resolves the strikes on them ({@link #resolve}),
 * reading {@link #stateAt}; {@link #effect} logs what it did (step 6); {@link #endTick} runs the meters (step 7) and
 * keeps the tick's snapshots. Sweep tick by tick, logging each swept tick's effects before sweeping the next, and log
 * a strike's result at its impact before that tick's hits: an effect can't go before what the world has read.
 *
 * <p><b>Late presses.</b> A press stamped in an earlier tick is applied there: the Timeline restores the snapshot before
 * it and replays every press and effect logged since, so a Step pressed 75 ms ago is 75 ms along. Three limits keep the
 * world from being rewound:
 *
 * <ul>
 *   <li>an attack it starts is never active before the tick the press arrived in (the machine holds the windup);
 *   <li>a press stamped at or before the newest tick the world has read (a resolved impact, or a swept active window:
 *       the {@linkplain #watermark watermark}) is applied in the tick after it, its stamp still judging the beat;
 *   <li>and none is applied before the oldest snapshot kept ({@code history_ticks}).
 * </ul>
 *
 * <p><b>The barrier</b> (Rule 2.3, D-034). Presses stamped after the earliest pending impact wait until that strike is
 * resolved or cancelled, then apply at their stamps; until then the player's own active windows after the impact
 * aren't swept ({@link #sweepableThrough}), so a swing pressed after a strike that hits can't land first. A strike
 * still pending when its impact leaves the history is dropped.
 *
 * <p><b>Taps.</b> A Breathe release less than the radial's threshold after its press is a tap: an Inhale is logged at
 * the press's stamp (Rule 3.7), so a Recovery Breath is judged as of the press, wherever the recovery stood by the
 * release, and a release the barrier holds still lets its press's Inhale through.
 *
 * <p>Whatever changes an earlier tick (a late press, a past tick's effect, presses a resolved strike released) is
 * replayed before the next read: {@link #stateAt}, {@link #live}, {@link #endAt} and {@link #swept} settle first, and
 * so does {@code endTick}. The log allocates per press and per effect, never per tick.
 */
public final class Timeline {
    private static final long NONE = Long.MIN_VALUE;
    private static final int MAX_RECEIVED = 64; // presses per tick: a human makes a handful, a flood is cut off

    private record Logged(Input input, long seq, long step, long minActive) {}

    private record Waiting(Input input, long seq, long arrival, boolean tapped) {}

    private record LoggedEffect(Effect effect, long tick) {}

    private record Strike(long id, long impactMicros) {}

    private final MachineRules rules;
    private final Combatant live;
    private final int size;
    private final Combatant[] ends;
    private final long[] endTicks;
    private final Combatant[] judged;
    private final long[] judgedTicks;
    private final long firstEnd;

    private final List<Input> received = new ArrayList<>();
    private final List<Waiting> waiting = new ArrayList<>();
    private final List<Logged> inputs = new ArrayList<>();
    private final List<LoggedEffect> effects = new ArrayList<>();
    private final List<Strike> pending = new ArrayList<>();

    private long current;
    private boolean inTick;
    private boolean ranCurrent;
    private long watermark = NONE;
    private long dirty = NONE;
    private long rebasedThrough = NONE;
    private long nextSeq;

    /** Takes over {@code start}, the combatant as it stood at the end of tick {@code tick − 1}, with the rules' history. */
    public Timeline(Combatant start, long tick) {
        this(start, tick, start.rules().historyTicks());
    }

    /**
     * The same with {@code historyTicks} of snapshots: the server's {@code history_ticks}, or a client's longer one (its
     * presses wait an RTT for the server's state: the hold cap plus RTT/2 plus 2 ticks).
     */
    public Timeline(Combatant start, long tick, int historyTicks) {
        if (historyTicks < 2) {
            throw new IllegalArgumentException("historyTicks must be at least 2, was " + historyTicks);
        }
        this.rules = start.rules();
        this.live = start;
        this.size = historyTicks + 1;
        this.ends = new Combatant[size];
        this.judged = new Combatant[size];
        this.endTicks = new long[size];
        this.judgedTicks = new long[size];
        for (int i = 0; i < size; i++) {
            ends[i] = start.blank();
            judged[i] = start.blank();
            endTicks[i] = NONE;
            judgedTicks[i] = NONE;
        }
        this.firstEnd = tick - 1;
        this.current = tick - 1;
        keepEnd(tick - 1);
    }

    /** The combatant now: after {@code beginTick(n)} the state the world sees at n, after {@code endTick(n)} its end. */
    public Combatant live() {
        settle();
        return live;
    }

    /** How many past ticks of snapshots it keeps. */
    public int historyTicks() {
        return size - 1;
    }

    /** The tick begun, or the last one ended. */
    public long current() {
        return current;
    }

    // ------------------------------------------------------------------------------------------------- presses

    /**
     * A press or release the player sent, received between ticks; it arrives in the next tick begun. Returns false if
     * more than a tick's worth of presses have queued already (it's dropped). Releases are never dropped, so a key is
     * never left held; a client that floods them is the stamp check's to cut off.
     */
    public boolean receive(Input in) {
        boolean release = in.kind() == Input.Kind.GUARD_UP || in.kind() == Input.Kind.BREATHE_UP;
        if (received.size() >= MAX_RECEIVED && !(release && received.size() < 2 * MAX_RECEIVED)) {
            return false;
        }
        received.add(in);
        return true;
    }

    private void consider(Input in, long seq, long arrival) {
        boolean blocked = blocked(in.stampMicros());
        if (in.tick() > current || blocked) {
            boolean tapped = blocked && in.kind() == Input.Kind.BREATHE_UP && addTapInhale(in);
            waiting.add(new Waiting(in, seq, arrival, tapped));
        } else {
            accept(in, seq, arrival, false);
        }
    }

    /** Lets through the presses that no longer wait: their tick has come and no pending impact holds them. */
    private void release() {
        int keep = 0;
        for (int i = 0; i < waiting.size(); i++) {
            Waiting w = waiting.get(i);
            if (w.input().tick() <= current && !blocked(w.input().stampMicros())) {
                accept(w.input(), w.seq(), w.arrival(), w.tapped());
            } else {
                waiting.set(keep++, w);
            }
        }
        truncate(waiting, keep);
    }

    private void accept(Input in, long seq, long arrival, boolean tapped) {
        long step = Math.max(in.tick(), floor());
        if (in.kind() == Input.Kind.BREATHE_UP && !tapped) {
            addTapInhale(in);
        }
        insert(new Logged(in, seq, step, arrival));
        touched(step);
    }

    /**
     * A Breathe release shorter than the radial's hold after its press: an Inhale at the press's stamp. Returns whether
     * its press was found in the log (tap or not); a press still waiting is paired when it comes.
     */
    private boolean addTapInhale(Input up) {
        for (int i = inputs.size() - 1; i >= 0; i--) {
            Logged l = inputs.get(i);
            Input.Kind kind = l.input().kind();
            if (kind == Input.Kind.BREATHE_UP) {
                return false; // the last press is paired already: this release's press isn't logged yet
            }
            if (kind == Input.Kind.BREATHE_DOWN) {
                long down = l.input().stampMicros();
                if (up.stampMicros() - down < Micros.ofMillis(rules.radialHoldMs())) {
                    long step = Math.max(l.step(), floor());
                    insert(new Logged(Input.of(Input.Kind.INHALE, down), l.seq(), step, l.minActive()));
                    touched(step);
                }
                return true;
            }
        }
        return false;
    }

    /** Keeps the log in the order presses apply: by step, then stamp, then arrival. */
    private void insert(Logged e) {
        int i = inputs.size();
        while (i > 0 && after(inputs.get(i - 1), e)) {
            i--;
        }
        inputs.add(i, e);
    }

    private static boolean after(Logged a, Logged b) {
        if (a.step() != b.step()) {
            return a.step() > b.step();
        }
        if (a.input().stampMicros() != b.input().stampMicros()) {
            return a.input().stampMicros() > b.input().stampMicros();
        }
        return a.seq() > b.seq();
    }

    /** The oldest tick whose end snapshot is kept: inside a tick the ring holds the ends before it. */
    private long oldestEnd() {
        return Math.max(firstEnd, inTick ? current - size : current - size + 1);
    }

    /** The earliest step a press can still change: after the watermark and the oldest snapshot. */
    private long floor() {
        long f = oldestEnd() + 1;
        return watermark == NONE ? f : Math.max(f, watermark + 1);
    }

    private void touched(long step) {
        if (step < current || (step == current && ranCurrent)) {
            dirty = dirty == NONE ? step : Math.min(dirty, step);
        }
    }

    // ----------------------------------------------------------------------------------------------------- ticks

    /** Steps 1 and 3 of tick {@code n}: the presses that arrived (late ones replayed at their stamps), then timers. */
    public void beginTick(long n) {
        if (inTick || n != current + 1) {
            throw new IllegalStateException("tick " + n + " can't begin after " + current + (inTick ? " (open)" : ""));
        }
        settle(); // anything a rebase or an epoch left between ticks
        current = n;
        inTick = true;
        ranCurrent = false;
        dropUndecidable();
        release(); // the older presses first, so a release always finds its press logged
        for (int i = 0; i < received.size(); i++) {
            consider(received.get(i), nextSeq++, n);
        }
        received.clear();
        if (dirty != NONE && dirty < n) {
            replay(dirty, n);
        }
        dirty = NONE;
        run(n);
        applyEffects(n); // a client's server effects logged ahead of its tick; none on the server
        ranCurrent = true;
    }

    /** Step 7 of tick {@code n}: replays what changed during the tick, runs the meters and keeps the snapshot. */
    public void endTick(long n) {
        if (!inTick || n != current) {
            throw new IllegalStateException("tick " + n + " isn't open (current " + current + ")");
        }
        settle();
        live.meters(n);
        keepEnd(n);
        inTick = false;
        trim();
    }

    /**
     * Re-derives now whatever changed an earlier tick, so the next read sees it. Every read settles on its own; this
     * only chooses when the cost is paid.
     */
    public void settle() {
        if (dirty == NONE) {
            return;
        }
        if (inTick) {
            replay(dirty, current);
            run(current);
            applyEffects(current);
        } else {
            replay(dirty, current + 1);
        }
        dirty = NONE;
    }

    /** Restores the end of tick {@code from − 1} and re-derives every tick up to, not including, {@code to}. */
    private void replay(long from, long to) {
        live.copyFrom(endOf(from - 1));
        for (long t = from; t < to; t++) {
            run(t);
            applyEffects(t);
            live.meters(t);
            keepEnd(t);
        }
    }

    private void run(long t) {
        for (int i = 0; i < inputs.size(); i++) {
            Logged l = inputs.get(i);
            if (l.step() == t) {
                live.input(l.input(), t, l.minActive());
            } else if (l.step() > t) {
                break;
            }
        }
        live.advance(t);
        int slot = slot(t);
        judged[slot].copyFrom(live);
        judgedTicks[slot] = t;
    }

    private void applyEffects(long t) {
        for (int i = 0; i < effects.size(); i++) {
            LoggedEffect e = effects.get(i);
            if (e.tick() == t) {
                live.effect(e.effect(), t);
            } else if (e.tick() > t) {
                break;
            }
        }
    }

    private void keepEnd(long t) {
        int slot = slot(t);
        ends[slot].copyFrom(live);
        endTicks[slot] = t;
    }

    private Combatant endOf(long t) {
        int slot = slot(t);
        if (endTicks[slot] != t) {
            throw new IllegalStateException("no snapshot of tick " + t + " (holds " + endTicks[slot] + ")");
        }
        return ends[slot];
    }

    private int slot(long t) {
        return (int) Math.floorMod(t, (long) size);
    }

    /** Drops what no replay can reach any more. */
    private void trim() {
        long reach = oldestEnd() + 1;
        int keep = 0;
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).step() >= reach) {
                inputs.set(keep++, inputs.get(i));
            }
        }
        truncate(inputs, keep);
        keep = 0;
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i).tick() >= reach) {
                effects.set(keep++, effects.get(i));
            }
        }
        truncate(effects, keep);
    }

    private static <T> void truncate(List<T> list, int size) {
        while (list.size() > size) {
            list.remove(list.size() - 1);
        }
    }

    // ---------------------------------------------------------------------------------------------------- world

    /**
     * Step 6: the world did {@code e} to the player at {@code tick}, the current tick or an earlier one no older than
     * the watermark (a strike's result is logged at its impact). Effects at a tick apply in the order they're logged.
     */
    public void effect(Effect e, long tick) {
        if (!inTick || tick > current) {
            throw new IllegalStateException("effects apply inside the tick they're for (tick " + tick + ")");
        }
        if (tick <= oldestEnd() || (watermark != NONE && tick < watermark)) {
            throw new IllegalArgumentException("tick " + tick + " is before what the world has read or kept");
        }
        int i = effects.size();
        while (i > 0 && effects.get(i - 1).tick() > tick) {
            i--;
        }
        effects.add(i, new LoggedEffect(e, tick));
        if (dirty == NONE && tick == current) {
            live.effect(e, tick);
        } else {
            dirty = dirty == NONE ? tick : Math.min(dirty, tick);
        }
    }

    /**
     * The predicting client's log of what the server decided that it couldn't predict (that it joined a strike, a
     * strike's cancel), at the server's tick: ignored when the snapshot it last rebased onto already holds that tick, or
     * when the tick is older than its history; otherwise logged there and replayed before the next read (a tick still
     * to come applies when it runs). Callable between ticks or inside one.
     */
    public void serverEffect(Effect e, long tick) {
        if ((rebasedThrough != NONE && tick <= rebasedThrough) || tick <= oldestEnd()) {
            return;
        }
        int i = effects.size();
        while (i > 0 && effects.get(i - 1).tick() > tick) {
            i--;
        }
        effects.add(i, new LoggedEffect(e, tick));
        if (tick < current || (tick == current && (ranCurrent || !inTick))) {
            dirty = dirty == NONE ? tick : Math.min(dirty, tick);
        }
    }

    /**
     * An NPC strike on the player committed with its impact at {@code impactMicros} (Rule 7.11); a repeated id
     * replaces the earlier commit. Every player the strike's sweep can reach gets one, not only its target.
     */
    public void commitStrike(long id, long impactMicros) {
        long tick = Micros.stepOf(impactMicros);
        if (watermark != NONE && tick < watermark) {
            throw new IllegalArgumentException("an impact at tick " + tick + " is before what the world has read");
        }
        removeStrike(id);
        pending.add(new Strike(id, impactMicros));
        release();
    }

    /**
     * The strike won't land (its attacker was interrupted, or unloaded): the presses it held go through. After its
     * impact tick the world may have read the player there (a Pommel interrupt, a clash), so that tick stays as read.
     * Log {@link Effect.StrikeCancelled} for it too, so the machine drops it and settles a Feint its press claimed.
     */
    public void cancelStrike(long id) {
        long impact = removeStrike(id);
        if (impact == NONE) {
            return;
        }
        long tick = Micros.stepOf(impact);
        if (tick <= current) {
            read(tick);
        }
        release();
    }

    /**
     * The strike is gone and the world never read the player for it (a Posture break before its impact, its attacker or
     * this player gone): the presses it held go through at their stamps, and no tick is frozen. Log
     * {@link Effect.StrikeCancelled} for it too.
     */
    public void dropStrike(long id) {
        if (removeStrike(id) != NONE) {
            release();
        }
    }

    /**
     * The strike is decided, at or after its impact tick and after every strike on the player with an earlier impact:
     * that tick is now read, and the presses it held go through at their stamps. Log its effects on the player at its
     * impact tick with {@link #effect}, before or after this.
     */
    public void resolve(long id) {
        long impact = NONE;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).id() == id) {
                impact = pending.get(i).impactMicros();
            }
        }
        if (impact == NONE) {
            return;
        }
        long tick = Micros.stepOf(impact);
        if (tick > current) {
            throw new IllegalStateException("strike " + id + " resolved before its impact at tick " + tick);
        }
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).impactMicros() < impact) {
                throw new IllegalStateException("strike " + id + " resolved before strike "
                        + pending.get(i).id() + ", whose impact is earlier");
            }
        }
        removeStrike(id);
        read(tick);
        release();
    }

    /** The player's active windows at {@code tick} were swept, hit or miss: the world has read that tick. */
    public void swept(long tick) {
        if (tick > sweepableThrough()) {
            throw new IllegalArgumentException(
                    "tick " + tick + " isn't sweepable yet (through " + sweepableThrough() + ")");
        }
        read(tick);
    }

    /** The last tick the player's active windows may be swept: now, or the earliest pending impact (D-034). */
    public long sweepableThrough() {
        long impact = earliestImpact();
        return impact == NONE ? current : Math.min(current, Micros.stepOf(impact));
    }

    /**
     * The earliest tick an {@link #effect} may still be logged at in the tick under way: after the oldest snapshot kept
     * and no earlier than the watermark.
     */
    public long earliestEffectTick() {
        long first = oldestEnd() + 1;
        return watermark == NONE ? first : Math.max(first, watermark);
    }

    /** The newest tick the world has read, or {@code Long.MIN_VALUE}: presses at or before it apply after it. */
    public long watermark() {
        return watermark;
    }

    /**
     * The player as the world saw them at {@code tick}, after its presses and timers and before its effects (Rule
     * 2.10): what a strike with its impact then is judged against. Read it; don't change it.
     */
    public Combatant stateAt(long tick) {
        settle();
        int slot = slot(tick);
        if (judgedTicks[slot] != tick || tick > current) {
            throw new IllegalArgumentException("no state kept for tick " + tick + " (now " + current + ")");
        }
        return judged[slot];
    }

    /** The player at the end of tick {@code tick}, meters and all: what a snapshot to the client carries. Read only. */
    public Combatant endAt(long tick) {
        settle();
        if (tick > current || (inTick && tick == current)) {
            throw new IllegalArgumentException("tick " + tick + " hasn't ended (now " + current + ")");
        }
        return endOf(tick);
    }

    /**
     * The newest tick whose end no press still to come can change, or {@code Long.MIN_VALUE} if that's older than the
     * history: the tick a snapshot to the client is taken at (Netcode, "Prediction and reconciliation"). Every press
     * the client has made up to it is in the server's state, and none after it. {@code horizonMicros} is the newest
     * frame stamp received from the player (frames arrive in order); {@code earliestHeldMicros} is the stamp check's
     * earliest held press ({@code StampCheck.earliestHeldJudged}), or {@code Long.MAX_VALUE}. A pending impact bounds it
     * (its result is logged there), and so does a Breathe press whose release could still make it a tap.
     */
    public long settledThrough(long horizonMicros, long earliestHeldMicros) {
        long h = Math.min(inTick ? current - 1 : current, Micros.tickOf(horizonMicros));
        if (earliestHeldMicros != Long.MAX_VALUE) {
            h = Math.min(h, Micros.stepOf(earliestHeldMicros) - 1);
        }
        long impact = earliestImpact();
        if (impact != NONE) {
            h = Math.min(h, Micros.stepOf(impact) - 1);
        }
        for (int i = 0; i < received.size(); i++) {
            h = Math.min(h, Math.max(received.get(i).tick(), floor()) - 1);
        }
        for (int i = inputs.size() - 1; i >= 0; i--) {
            Logged l = inputs.get(i);
            Input.Kind kind = l.input().kind();
            if (kind == Input.Kind.BREATHE_UP) {
                break;
            }
            if (kind == Input.Kind.BREATHE_DOWN) {
                if (horizonMicros - l.input().stampMicros() < Micros.ofMillis(rules.radialHoldMs())) {
                    h = Math.min(h, l.step() - 1); // its release may still log an Inhale at its step
                }
                break;
            }
        }
        return h < oldestEnd() ? NONE : h;
    }

    /**
     * Rule 1.8: after a {@code ClockEpoch}, presses stamped after {@code cutoffMicros} (the last tick that ran before the
     * stall) are discarded, and the pending strikes are gone (the strike system cancels them all, settling their
     * claimed Feints). Call it in the tick the epoch is crossed, before any press of the new timeline arrives. A client
     * rebuilds its Timeline from the epoch's snapshot instead: its clock went back, and ticks can't.
     */
    public void onEpoch(long cutoffMicros) {
        pending.clear();
        int keep = 0;
        for (int i = 0; i < received.size(); i++) {
            if (received.get(i).stampMicros() <= cutoffMicros) {
                received.set(keep++, received.get(i));
            }
        }
        truncate(received, keep);
        keep = 0;
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).input().stampMicros() <= cutoffMicros) {
                waiting.set(keep++, waiting.get(i));
            }
        }
        truncate(waiting, keep);
        keep = 0;
        for (int i = 0; i < inputs.size(); i++) {
            Logged l = inputs.get(i);
            if (l.input().stampMicros() <= cutoffMicros) {
                inputs.set(keep++, l);
            } else {
                touched(l.step());
            }
        }
        truncate(inputs, keep);
        release();
    }

    /**
     * The client's reconciliation: the server's state at the end of {@code tick} (its {@link #settledThrough} tick, from
     * {@link #endAt}) replaces this Timeline's, and the presses logged after it replay on top. Call between ticks.
     * Returns false, changing nothing, for a snapshot older than one already applied or than the history.
     */
    public boolean rebase(long tick, Combatant authoritative) {
        if (inTick || tick > current) {
            throw new IllegalStateException("can't rebase onto tick " + tick + " (now " + current + ")");
        }
        if ((rebasedThrough != NONE && tick <= rebasedThrough) || tick < oldestEnd()) {
            return false;
        }
        rebasedThrough = tick;
        int slot = slot(tick);
        ends[slot].copyFrom(authoritative);
        endTicks[slot] = tick;
        watermark = watermark == NONE ? tick : Math.max(watermark, tick); // no settle: what's pending is superseded
        if (tick == current) {
            live.copyFrom(authoritative);
            dirty = NONE;
        } else {
            dirty = tick + 1; // everything up to it is the server's now
        }
        return true;
    }

    private void read(long tick) {
        settle(); // what was pending lands before the tick is frozen
        watermark = watermark == NONE ? tick : Math.max(watermark, tick);
    }

    /** A strike still pending when its impact leaves the history can't be judged any more: it's dropped. */
    private void dropUndecidable() {
        long oldest = current - size + 1; // the oldest judged state this tick keeps
        for (int i = pending.size() - 1; i >= 0; i--) {
            if (Micros.stepOf(pending.get(i).impactMicros()) < oldest) {
                pending.remove(i);
            }
        }
    }

    private boolean blocked(long stamp) {
        long impact = earliestImpact();
        return impact != NONE && stamp > impact;
    }

    private long earliestImpact() {
        long first = NONE;
        for (int i = 0; i < pending.size(); i++) {
            long t = pending.get(i).impactMicros();
            first = first == NONE ? t : Math.min(first, t);
        }
        return first;
    }

    private long removeStrike(long id) {
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).id() == id) {
                return pending.remove(i).impactMicros();
            }
        }
        return NONE;
    }
}
