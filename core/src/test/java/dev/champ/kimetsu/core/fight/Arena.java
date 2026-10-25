package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.strike.Scheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A pretend server: a {@link CombatWorld} with players on Water's Pulse and box-dummy demons with scripted brains.
 * Each player's client is {@code latencyTicks} away: its presses and its client-tick frames (one per tick, carrying its
 * pose) arrive that many ticks after their stamps. A tick n runs after the frames and presses due at n arrive.
 */
final class Arena {
    static final int BEAT = 13;
    static final Moveset WATER = Fixtures.water();

    /** One player's script: where it stands from which tick, and what it presses. */
    static final class Player {
        final Fighter f;
        final int latencyTicks;
        final TreeMap<Long, double[]> stands = new TreeMap<>(); // tick → x, y, z, yaw
        final TreeMap<Long, List<Input>> presses = new TreeMap<>(); // the step each acts in → the presses
        final List<Snapshot> snapshots = new ArrayList<>(); // what the server sent it, when snapshotting
        long silentFrom = Long.MAX_VALUE; // its client ticks from here on never arrive (a stalled connection)
        boolean snapshotting;
        private @Nullable Random jitter;
        private int maxJitterTicks;
        private long nextSent = 1; // the next client tick to arrive
        private long lastArrival = Long.MIN_VALUE;
        private long pendingArrival = Long.MIN_VALUE;

        Player(Fighter f, int latencyTicks) {
            this.f = f;
            this.latencyTicks = latencyTicks;
            stands.put(Long.MIN_VALUE, new double[] {0, 64, 2, 180});
        }

        Player stand(long fromTick, double x, double y, double z, double yaw) {
            stands.put(fromTick, new double[] {x, y, z, yaw});
            return this;
        }

        Player press(Input in) {
            presses.computeIfAbsent(in.tick(), k -> new ArrayList<>()).add(in);
            return this;
        }

        /** Its presses that act in step {@code tick}, in the order they were made. */
        List<Input> pressesIn(long tick) {
            return presses.getOrDefault(tick, List.of());
        }

        Player press(Input.Kind kind, long micros) {
            return press(Input.of(kind, micros));
        }

        double[] standAt(long tick) {
            return stands.floorEntry(tick).getValue();
        }

        /**
         * Each of its client ticks (presses, then its frame) arrives up to {@code maxExtraTicks} later than its latency,
         * at random, never before the tick before it: a connection that jitters but keeps its order.
         */
        Player jitter(Random rnd, int maxExtraTicks) {
            jitter = rnd;
            maxJitterTicks = maxExtraTicks;
            return this;
        }

        /** The server tick client tick {@code sent} arrives in. Asked in order, once per client tick. */
        private long arrival(long sent) {
            long extra = jitter == null ? 0 : jitter.nextInt(maxJitterTicks + 1);
            return Math.max(lastArrival, sent + latencyTicks + extra);
        }

        /** Its connection stalls: no frame or press from client tick {@code tick} on arrives. */
        Player silentFrom(long tick) {
            silentFrom = tick;
            return this;
        }

        Combatant now() {
            return f.timeline().live();
        }
    }

    /** An event the fight loop recorded, with the tick it was recorded in. */
    record Seen(long tick, Outbox.Event event) {}

    /**
     * What the server sends a player's client after tick {@code sentTick} (Netcode, "Prediction and reconciliation"):
     * the player at the end of its settled tick {@code tick}.
     */
    record Snapshot(long sentTick, long tick, Combatant state) {}

    /** A brain that commits the strings it's given at the ticks it's given. */
    static final class Script implements Brain {
        record Commit(long tick, int target, List<FrameData> string, long beatAtLeast) {}

        final TreeMap<Long, List<Commit>> commits = new TreeMap<>();
        final List<Scheduler.Plan> plans = new ArrayList<>();

        Script at(long tick, int target, FrameData... string) {
            return atBeat(tick, target, Scheduler.ANY_BEAT, string);
        }

        Script atBeat(long tick, int target, long beat, FrameData... string) {
            commits.computeIfAbsent(tick, k -> new ArrayList<>()).add(new Commit(tick, target, List.of(string), beat));
            return this;
        }

        @Override
        public void decide(long tick, StrikeMob self, CombatWorld world) {
            for (Commit c : commits.getOrDefault(tick, List.of())) {
                plans.add(world.commit(self.id(), c.target(), c.string(), c.beatAtLeast()));
            }
        }
    }

    final PulseSchedule pulse = new PulseSchedule(BEAT);
    final CombatWorld world;
    final List<Player> players = new ArrayList<>();
    final List<BoxDummy> demons = new ArrayList<>();
    final List<Script> brains = new ArrayList<>();
    final List<Seen> seen = new ArrayList<>();
    long now;

    Arena() {
        this(Sight.OPEN);
    }

    Arena(Sight sight) {
        this(sight, Ground.OPEN);
    }

    Arena(Sight sight, Ground ground) {
        this(sight, ground, Fixtures.strikes());
    }

    /** On strike rules {@code strikes} (a variant). */
    Arena(StrikeRules strikes) {
        this(Sight.OPEN, Ground.OPEN, strikes);
    }

    /** On strike rules {@code strikes}, in {@code ground}. */
    Arena(Sight sight, Ground ground, StrikeRules strikes) {
        world = new CombatWorld(Fixtures.hits(), strikes, Fixtures.timing(), Fixtures.rhythm(), WATER, sight, ground);
    }

    Player player(int id, int latencyTicks) {
        return player(id, latencyTicks, 0);
    }

    /** A Water player's machine at the start, its Breath {@code breathSpent} below full: the server's and its client's. */
    static Combatant combatant(PulseSchedule pulse, double breathSpent) {
        Combatant c = new Combatant(
                Fixtures.machine(),
                Fixtures.timing(),
                Fixtures.breath(),
                Fixtures.playerPosture(),
                Fixtures.rhythm(),
                WATER, // one moveset, so a client's machine can take the server's snapshot
                pulse,
                0);
        c.breath().spend(0, Fixed.of(breathSpent));
        return c;
    }

    /** A player whose Breath starts {@code breathSpent} below full, so a cost shows. */
    Player player(int id, int latencyTicks, double breathSpent) {
        return player(id, latencyTicks, breathSpent, pulse);
    }

    /** A player on a Pulse of its own: the demons' strikes on it land on its beats. */
    Player player(int id, int latencyTicks, PulseSchedule own) {
        return player(id, latencyTicks, 0, own);
    }

    private Player player(int id, int latencyTicks, double breathSpent, PulseSchedule own) {
        Timeline t = new Timeline(combatant(own, breathSpent), 1);
        long cap = InputHorizon.holdCapMicros(
                Fixtures.timing(),
                Micros.ofTicks(2L * latencyTicks),
                Micros.ofMillis(Fixtures.timing().toleranceMinMs()));
        Fighter f = new Fighter(
                id,
                t,
                new InputHorizon(Fixtures.timing()),
                own,
                Fixtures.PLAYER_HALF_WIDTH,
                Fixtures.PLAYER_HEIGHT,
                Fixtures.PLAYER_EYE,
                cap);
        world.add(f);
        Player p = new Player(f, latencyTicks);
        players.add(p);
        return p;
    }

    Player player(int id) {
        return player(id, 0);
    }

    /** A demon at the origin facing +z, toward a player standing 2 blocks in front of it, with a scripted brain. */
    BoxDummy demon(int id, Script brain) {
        BoxDummy d = new BoxDummy(id).follow(world);
        world.add(d, brain);
        demons.add(d);
        brains.add(brain);
        return d;
    }

    /** A real demon of {@code profile} at the origin facing +z, its first tick the next one, with {@code brain}. */
    Demon demon(int id, DemonProfile profile, Brain brain) {
        Demon d = new Demon(id, profile, Fixtures.hits(), now + 1, 34, 0, 64, 0, 0);
        world.add(d, brain);
        return d;
    }

    /** The M1 demon. */
    Demon demon(int id, Brain brain) {
        return demon(id, Fixtures.demon(), brain);
    }

    Player get(int id) {
        for (Player p : players) {
            if (p.f.id() == id) {
                return p;
            }
        }
        throw new IllegalArgumentException("no player " + id);
    }

    /** Runs every tick up to and including {@code tick}. */
    Arena through(long tick) {
        while (now < tick) {
            long n = ++now;
            for (Player p : players) {
                while (p.nextSent < p.silentFrom) { // the client ticks that arrive now, in order
                    long sent = p.nextSent;
                    if (p.pendingArrival == Long.MIN_VALUE) {
                        p.pendingArrival = p.arrival(sent);
                    }
                    if (p.pendingArrival > n) {
                        break;
                    }
                    p.lastArrival = p.pendingArrival;
                    p.pendingArrival = Long.MIN_VALUE;
                    for (Input in : p.pressesIn(sent)) { // they act in step sent
                        p.f.timeline().receive(in);
                    }
                    double[] s = p.standAt(sent);
                    p.f.poses().put(Micros.ofTicks(sent), s[0], s[1], s[2], s[3], 0);
                    p.f.horizon().frame(Micros.ofTicks(sent), 16_667, Micros.ofTicks(sent));
                    p.nextSent++;
                }
            }
            world.tick(n);
            for (Outbox.Event e : world.outbox().drain()) {
                seen.add(new Seen(n, e));
            }
            for (Player p : players) {
                if (p.snapshotting && world.fighter(p.f.id()) != null) {
                    long newest = p.nextSent - 1; // its newest frame's stamp
                    long settled = p.f.timeline().settledThrough(Micros.ofTicks(newest), p.f.earliestHeldMicros());
                    if (settled != Long.MIN_VALUE) {
                        Combatant end = p.f.timeline().endAt(settled);
                        Combatant copy = end.blank();
                        copy.copyFrom(end);
                        p.snapshots.add(new Snapshot(n, settled, copy));
                    }
                }
            }
        }
        return this;
    }

    /** Every recorded event of type {@code type}, in order. */
    <T extends Outbox.Event> List<T> all(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Seen s : seen) {
            if (type.isInstance(s.event())) {
                out.add(type.cast(s.event()));
            }
        }
        return out;
    }

    /** The tick {@code e} was recorded in. */
    @SuppressWarnings("ReferenceEquality") // the very event recorded: two results can be equal
    long tickOf(Outbox.Event e) {
        for (Seen s : seen) {
            if (s.event() == e) {
                return s.tick();
            }
        }
        throw new IllegalArgumentException("never recorded: " + e);
    }

    /** Where {@code e} sits in the record: an earlier event has a smaller index. */
    @SuppressWarnings("ReferenceEquality") // the very event recorded: two results can be equal
    int indexOf(Outbox.Event e) {
        for (int i = 0; i < seen.size(); i++) {
            if (seen.get(i).event() == e) {
                return i;
            }
        }
        throw new IllegalArgumentException("never recorded: " + e);
    }

    /** The results of strike {@code strike}, one per player it was decided for. */
    List<Outbox.StrikeResult> results(long strike) {
        List<Outbox.StrikeResult> out = new ArrayList<>();
        for (Outbox.StrikeResult r : all(Outbox.StrikeResult.class)) {
            if (r.strike() == strike) {
                out.add(r);
            }
        }
        return out;
    }

    /** Strike {@code strike}'s result for player {@code fighter}. */
    Outbox.StrikeResult result(long strike, int fighter) {
        for (Outbox.StrikeResult r : results(strike)) {
            if (r.fighter() == fighter) {
                return r;
            }
        }
        throw new IllegalArgumentException("strike " + strike + " has no result for player " + fighter);
    }
}
