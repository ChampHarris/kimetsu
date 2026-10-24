package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.TickInput;
import dev.champ.kimetsu.core.clock.TickNumber;
import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.clock.TickSchedule;
import dev.champ.kimetsu.core.data.ClockRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The server's combat clock (Rules 1.1, 1.2, 1.8). In {@code ServerTickEvent.Pre} it measures the tick's start,
 * guesses G (the game time the tick will reach) and publishes the tick record the network thread converts arrivals
 * with; in {@code Post} it says whether the engine steps, from the game time reached alone. Vanilla drops its backlog
 * only by moving {@code lastOverloadWarningNanos} (made public by the mod's access transformer): a sprint tick moves it
 * too, but adds no tick length after it, so a drop is the field moving with {@code getNextTickTime()} past it.
 */
final class TickClock {
    private final MinecraftServer server;
    private TickSchedule schedule;
    private volatile @Nullable TickRecord record;
    private long lastWarning;
    private long previousEnd = Long.MIN_VALUE;
    private boolean runs;
    private boolean sprinting;
    private boolean inTick;
    private long reached; // the game time Post found
    private boolean recordWrong;
    private long expected;
    private long stepped = TickNumber.NONE;
    private int epochs; // re-anchors (the schedule counts its own epochs and carries them across rules)

    TickClock(MinecraftServer server, ClockRules rules) {
        this.server = server;
        this.schedule = new TickSchedule(rules);
        this.lastWarning = server.lastOverloadWarningNanos;
    }

    /** A reload changed the clock rules: a fresh schedule on them, which counts an epoch (it re-anchors). */
    void rules(ClockRules rules) {
        schedule = schedule.retuned(rules);
    }

    /** {@code ServerTickEvent.Pre}: the tick's guessed number and its record. */
    void onTickStart() {
        long now = Util.getNanos();
        ServerTickRateManager rate = server.tickRateManager();
        runs = TickNumber.runs(rate.isFrozen(), rate.frozenTicksToRun());
        expected = TickNumber.expected(server.overworld().getGameTime(), runs);
        long nominal = rate.nanosecondsPerTick();
        sprinting = rate.isSprinting();
        long warning = server.lastOverloadWarningNanos;
        boolean dropped = warning != lastWarning && server.getNextTickTime() != warning;
        lastWarning = warning;
        long late = now - (server.getNextTickTime() - nominal); // vanilla has moved its schedule on by one tick
        record = schedule.onTickStart(
                new TickInput(expected, now, nominal, !runs, false, sprinting, dropped, false, previousEnd, late));
        inTick = true;
    }

    /**
     * {@code ServerTickEvent.Post}, after the levels have ticked: whether the engine steps now, at the game time the
     * tick reached ({@link #tick()}). A re-anchor counts as an epoch.
     */
    TickNumber.Step onTickEnd() {
        long gameTime = server.overworld().getGameTime();
        reached = gameTime;
        recordWrong = gameTime != expected;
        TickNumber.Step s = TickNumber.atEnd(gameTime, stepped);
        if (s == TickNumber.Step.REANCHOR) {
            epochs++;
        }
        if (s != TickNumber.Step.HOLD) {
            stepped = gameTime;
        }
        return s;
    }

    /** The end of the tick's work, for the next tick's schedule (a pause that skipped the tick events). */
    void endMeasured() {
        previousEnd = Util.getNanos();
        inTick = false;
    }

    /** The engine tick last stepped, or {@link TickNumber#NONE}. */
    long tick() {
        return stepped;
    }

    /**
     * The tick the engine steps next: the one after the last, or before the first step, the one this tick reaches
     * (between Pre and Post, the game time has moved on already) or the next.
     */
    long nextTick() {
        if (stepped != TickNumber.NONE) {
            return stepped + 1;
        }
        return inTick ? expected : server.overworld().getGameTime() + 1;
    }

    /** Whether ticking is sprinting ({@code /tick sprint}: mobs commit no strikes, Rule 1.1). */
    boolean sprinting() {
        return sprinting;
    }

    /**
     * The clock epoch: it moves when vanilla's backlog drop moved the schedule (Rule 1.8), when game time re-anchored
     * and when the clock rules changed. The combat server sends {@code ClockEpoch} when it moves.
     */
    int epoch() {
        return epochs + schedule.epoch();
    }

    /** Whether this tick's record named the wrong tick (Pre's guess was wrong): a fresh one goes out. */
    boolean recordWrong() {
        return recordWrong;
    }

    /** This tick's record under the tick it really reached, once Post found Pre's guess wrong; else null. */
    @Nullable
    TickRecord corrected() {
        TickRecord r = record;
        if (!recordWrong || r == null) {
            return null;
        }
        return new TickRecord(
                reached, r.startNanos(), r.tickLengthNanos(), r.frozen(), r.paused(), r.sprinting(), r.epoch());
    }

    /** Where Rule 1.8 discards presses after a drop: those stamped after the last tick that ran before it. */
    long epochCutoffMicros() {
        return stepped == TickNumber.NONE ? Long.MIN_VALUE : Micros.ofTicks(stepped);
    }

    /** This tick's record, for the network thread; null before the first tick. */
    @Nullable
    TickRecord record() {
        return record;
    }
}
