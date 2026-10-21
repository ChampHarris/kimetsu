package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayList;
import java.util.List;

/**
 * What the fight loop decided that clients need (slice 5 sends it; Netcode, "Payloads"): recorded in order, drained by
 * the caller. Events are rare (a commit, a cue, a result and the effects it logs, a mob's view when it changes), so
 * each is a small record.
 */
public final class Outbox {
    public sealed interface Event {}

    /** What a mob tells clients about itself ({@link #publish}): public, like its cues (Netcode, "Need-to-know"). */
    public sealed interface MobEvent extends Event {}

    /** A strike committed (Rule 7.11): its schedule, sent at once so clients play its cue on time. */
    public record AttackStarted(
            long strike,
            int attacker,
            int target,
            String attack,
            long commitTick,
            long windupStartTick,
            long cueTick,
            long impactTick,
            long string,
            boolean last)
            implements Event {}

    /**
     * Its cue fired and its direction locked (Rule 7.13): the attacker's yaw and feet then, and the share of its lunge
     * it runs before stopping short of its target or a wall (D-042). {@code AttackStarted} can't carry them, since the
     * commit comes before the lock.
     */
    public record StrikeLocked(long strike, double yaw, double x, double y, double z, double lunge) implements Event {}

    /**
     * A player became involved in a strike that isn't aimed at them (Rule 2.9), at tick {@code tick}: for display (its
     * HUD shows the strike as its own). Its machine gets the commit as a {@link PlayerEffect}, logged at the tick the
     * server logged it (the cue, or later): a client mustn't log this as a second commit, which would lose a claim.
     */
    public record Involved(long strike, int fighter, long tick) implements Event {}

    /** A strike won't land: {@code tick} is the tick its cancel takes effect in. */
    public record AttackCancelled(long strike, long tick, String why) implements Event {}

    /** A strike's verdict on one player, and the damage the mod applies for a hit (Rule 12). */
    public record StrikeResult(long strike, int fighter, Verdict.Kind kind, double damage) implements Event {}

    /**
     * What the loop logged on player {@code fighter}'s machine at {@code tick}, in the order it logged them: the
     * player's client logs each with {@code Timeline.serverEffect} at the same tick, so its prediction replays what the
     * server decided (Netcode, "Prediction and reconciliation"). Sent to that player alone.
     */
    public record PlayerEffect(int fighter, long tick, Effect effect) implements Event {}

    /**
     * A player's swing touched a mob at {@code tick}: what it did ({@code result}) and with which part
     * ({@code SwingTrack.BODY} or {@code NECK}). Public: every tracker sees the contact and its result (a block sparks,
     * a staked one doesn't, Rule 11.4).
     */
    public record SwingLanded(int fighter, int mob, String attack, int window, int part, long tick, HitResult result)
            implements Event {}

    /**
     * What that swing dealt: its damage and Posture reveal the swinger's Rhythm multiplier and assist level, so slice 5
     * sends this to the swinger alone.
     */
    public record SwingDealt(int fighter, int mob, int window, long tick, double damage, double posture)
            implements Event {}

    /**
     * A grab connected at {@code tick}: {@code fighter} is held until it breaks free, an ally frees it, or the throw at
     * {@code throwTick} (Rule 6.3).
     */
    public record HoldStarted(long strike, int mob, int fighter, long tick, long throwTick) implements Event {}

    /** The hold ended at {@code tick}, {@code how}; a throw deals {@code damage} (the mod applies it, Rule 12). */
    public record HoldEnded(long strike, int fighter, long tick, Release how, double damage) implements Event {}

    /** How a micro-clash ended (Rule 10.3): the player won, the mob won, or a tie, whose shockwave pushes both apart. */
    public enum ClashOutcome {
        PLAYER,
        MOB,
        TIE
    }

    /**
     * A micro-clash froze {@code fighter} and {@code mob} at {@code tick}, strike {@code strike}'s impact (Rule 10.2): the
     * white flash comes at {@code flashMicros}, sent now, so every client shows it at the same moment.
     */
    public record ClashStarted(long strike, int mob, int fighter, long tick, long flashMicros) implements Event {}

    /**
     * It ended at {@code tick}: the loser's Posture breaks; a tie's shockwave pushes both apart, the player and the mob
     * alike, from the point between them (the mod applies both pushes; core sends no {@code Pushed} for it).
     */
    public record ClashEnded(long strike, int mob, int fighter, long tick, ClashOutcome outcome) implements Event {}

    /** A mob died at {@code tick} (the demon: decapitated by player {@code by}, or −1: killed from outside). */
    public record MobDied(int mob, long tick, int by) implements Event {}

    /**
     * A mob's state for its trackers, sent when it changes (CombatantState and {@code posture_view}): GUARD while its
     * guard stance is up, NEUTRAL while it isn't (its strikes come from {@code AttackStarted}), or STAGGER, COLLAPSED,
     * KNOCKDOWN, CLASH or DEAD; the tick its Thread closes ({@code Long.MIN_VALUE} if closed, {@code Long.MAX_VALUE}
     * while it stays open); its Posture as a segment (value at {@code from}, rate a tick, in thousandths) and the stake
     * its guard holds (Rule 11.4). Its HP is the entity's own health, which vanilla syncs; never its Hunger: the clang
     * tells you what you need.
     */
    public record MobState(
            int mob,
            long tick,
            State state,
            long threadUntil,
            long posture,
            long postureRate,
            long postureFrom,
            long stake)
            implements MobEvent {}

    /**
     * Its guard stance's yaw from {@code tick}, in 256ths of a turn, sent when the step changes (Rule 11.4); the server
     * judges front and flank by this value, so every client sees the guard it's judged by.
     */
    public record GuardYaw(int mob, long tick, int step) implements MobEvent {}

    /** Its brain's target changed at {@code tick} (Rule 11.2: the head turns to it), or −1: none. */
    public record Targeting(int mob, long tick, int fighter) implements MobEvent {}

    /** A cut across its hardened neck clanged at {@code tick} (Rule 8.2): the sound, and its recoil if any. */
    public record Clang(int mob, long tick, int fighter, boolean recoils) implements MobEvent {}

    /**
     * A hit pushes it from {@code tick}: {@code distance} blocks along {@code (dirX, dirZ)} over {@code ticks}, the level
     * moving it (Rule 7.10: on mobs the server moves them); with {@code splats}, a wall within the splat window splats
     * it (Rule 9.4).
     */
    public record Pushed(int mob, long tick, double dirX, double dirZ, double distance, int ticks, boolean splats)
            implements MobEvent {}

    private final List<Event> events = new ArrayList<>();

    void add(Event e) {
        events.add(e);
    }

    /** A mob tells its trackers about itself. */
    public void publish(MobEvent e) {
        events.add(e);
    }

    /** Everything recorded since the last drain, in order. Draining an empty outbox (most ticks) allocates nothing. */
    public List<Event> drain() {
        if (events.isEmpty()) {
            return List.of();
        }
        List<Event> out = List.copyOf(events);
        events.clear();
        return out;
    }

    /** Whether nothing has been recorded since the last drain. */
    public boolean peekEmpty() {
        return events.isEmpty();
    }

    /** Everything recorded since the last drain, without draining it. */
    public List<Event> peek() {
        return List.copyOf(events);
    }
}
