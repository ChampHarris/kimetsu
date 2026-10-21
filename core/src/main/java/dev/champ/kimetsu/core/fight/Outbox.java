package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayList;
import java.util.List;

/**
 * What the fight loop decided that clients need (slice 5 sends it; Netcode, "Payloads"): recorded in order, drained by
 * the caller. Events are rare (a commit, a cue, a result and the effects it logs), so each is a small record.
 */
public final class Outbox {
    public sealed interface Event {}

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
     * Its cue fired and its direction locked (Rule 7.13): the attacker's yaw and feet then. {@code AttackStarted} can't
     * carry them, since the commit comes before the lock.
     */
    public record StrikeLocked(long strike, double yaw, double x, double y, double z) implements Event {}

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
     * A player's swing landed on a mob at {@code tick}. Its damage and Posture reveal the swinger's Rhythm multiplier
     * and assist level, so slice 5 sends them to the swinger alone; others are told only of the contact.
     */
    public record SwingLanded(int fighter, int mob, String attack, int window, long tick, double damage, double posture)
            implements Event {}

    private final List<Event> events = new ArrayList<>();

    void add(Event e) {
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
