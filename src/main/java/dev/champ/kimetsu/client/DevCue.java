package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code /kimetsu_dev cue <kind>}'s staging (5d-4a's sheets, D-145's A12): the events the server would route for one
 * cue on you, from scheduled tick {@code tick}, with a negative strike id so it never meets a real one. They go through
 * the view's own {@code accept}, so a staged cue is drawn by the very path a real one takes.
 */
final class DevCue {
    /** How long a staged clash's pose holds: {@code /kimetsu_dev pose clash}'s 2 s. */
    private static final int CLASH_TICKS = 40;

    /** What it stages. */
    enum Kind {
        WHITE,
        RED,
        PURPLE,
        FEINT,
        THREAD,
        FLASH
    }

    private DevCue() {}

    /** The demon's most frequent attack in its strings with a cue of {@code type} (ties: the lower id), or null. */
    static @Nullable FrameData attack(DemonProfile demon, Map<String, FrameData> frames, FrameData.Cue.Type type) {
        FrameData best = null;
        int weight = -1;
        for (Map.Entry<String, Integer> w : demon.strings().weights().entrySet()) { // by id
            FrameData f = frames.get(w.getKey());
            if (f != null && f.cue().type() == type && w.getValue() > weight) {
                best = f;
                weight = w.getValue();
            }
        }
        return best;
    }

    /**
     * The events for {@code kind}, strike {@code id}, by {@code attacker} (its feet and yaw where it stands) on
     * {@code self}; {@code state} is the demon's last {@code MobState}, if any. Empty if the demon has no such attack.
     */
    static List<Outbox.Event> events(
            Kind kind,
            long id,
            int attacker,
            int self,
            long tick,
            double x,
            double y,
            double z,
            float yaw,
            DemonProfile demon,
            Map<String, FrameData> frames,
            Outbox.@Nullable MobState state) {
        FrameData.Cue.Type type = switch (kind) {
            case WHITE, FEINT -> FrameData.Cue.Type.WHITE;
            case RED -> FrameData.Cue.Type.RED;
            case PURPLE -> FrameData.Cue.Type.PURPLE;
            case THREAD, FLASH -> FrameData.Cue.Type.NONE;
        };
        FrameData f = type != FrameData.Cue.Type.NONE ? attack(demon, frames, type) : null;
        return switch (kind) {
            case WHITE, RED, PURPLE -> f != null ? strike(f, id, attacker, self, tick, x, y, z, yaw) : List.of();
            case FEINT -> {
                if (f == null) {
                    yield List.of();
                }
                List<Outbox.Event> s = strike(f, id, attacker, self, tick, x, y, z, yaw);
                long impact = tick + f.windup();
                yield List.of(
                        s.get(0),
                        s.get(1),
                        new Outbox.AttackCancelled(
                                id, impact - demon.strings().feintBreakTicks(), Outbox.AttackCancelled.FEINT));
            }
            case THREAD ->
                List.of(
                        state != null
                                ? new Outbox.MobState(
                                        attacker,
                                        tick,
                                        state.state(),
                                        tick + demon.threadTicks(),
                                        state.posture(),
                                        state.postureRate(),
                                        state.postureFrom(),
                                        state.stake())
                                : new Outbox.MobState(
                                        attacker,
                                        tick,
                                        State.NEUTRAL,
                                        tick + demon.threadTicks(),
                                        Fixed.of(demon.posture().max()),
                                        0,
                                        tick,
                                        0));
            case FLASH ->
                List.of(
                        new Outbox.ClashStarted(id, attacker, self, tick, Micros.ofTicks(tick)),
                        new Outbox.ClashEnded(id, attacker, self, tick + CLASH_TICKS, Outbox.ClashOutcome.TIE));
        };
    }

    /** Attack {@code f} on {@code self}: its windup from {@code tick}, cued at its lead, locked where it stands. */
    static List<Outbox.Event> strike(
            FrameData f, long id, int attacker, int self, long tick, double x, double y, double z, float yaw) {
        long impact = tick + f.windup();
        long cue = Math.max(tick, impact - f.cue().lead());
        return List.of(
                new Outbox.AttackStarted(id, attacker, self, f.id(), tick, tick, cue, impact, id, true),
                new Outbox.StrikeLocked(id, yaw, x, y, z, 0));
    }
}
