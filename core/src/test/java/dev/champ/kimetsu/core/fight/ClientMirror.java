package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Cue;
import dev.champ.kimetsu.core.fight.Outbox.AttackStarted;
import dev.champ.kimetsu.core.fight.Outbox.StrikeLocked;
import dev.champ.kimetsu.core.hit.PoseRing;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.strike.StrikeView;
import dev.champ.kimetsu.core.strike.Verdict;
import dev.champ.kimetsu.core.strike.Verdicts;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A player's client as slice 5 will run it, without the wire (Netcode, "Prediction and reconciliation"): its own
 * Timeline (no barrier, a 40-tick history), each press applied as it's made, its own poses, and whatever the server
 * recorded arriving {@code latency} ticks after the server's tick: the effects the server logged on its player, logged
 * at the server's ticks ({@code serverEffect}), the strikes' schedules and locks (from which it works out where each
 * strike carries its attacker, as the server does), and the snapshots it rebases onto. It predicts each strike on its
 * player with the server's own {@link Verdicts}, at the impact or as soon as it knows of the strike: the bell it plays
 * at once, a round trip before the server's verdict. It predicts the verdict, not what follows from it: its own state
 * waits for the server's effects (slice 5 logs them ahead, provisionally).
 */
final class ClientMirror {
    static final String INTERRUPTED = "INTERRUPTED";

    private final Arena.Player p;
    private final BoxDummy body;
    private final int latency;
    final Timeline timeline;
    private final PoseRing poses = new PoseRing(64);
    private final Verdicts verdicts =
            new Verdicts(Fixtures.hits(), Fixtures.strikes(), Fixtures.timing(), Fixtures.rhythm());
    private final Verdict out = new Verdict();
    private final Map<String, FrameData> attacks = new HashMap<>();
    private final TreeMap<Long, AttackStarted> started = new TreeMap<>();
    private final Map<Long, StrikeLocked> locked = new HashMap<>();
    private final Set<Long> committed = new HashSet<>();
    final TreeMap<Long, String> predicted = new TreeMap<>();
    final List<String> corrections = new ArrayList<>(); // the first few: tick, then what differed
    int corrected; // snapshots that weren't what the client already had at their tick
    int snapshots;
    private int delivered;
    private int snapshotsTaken;

    ClientMirror(Arena.Player p, BoxDummy body, int latency, double breathSpent, Arena a) {
        this.p = p;
        this.body = body;
        this.latency = latency;
        this.timeline = new Timeline(Arena.combatant(a.pulse, breathSpent), 1, 40);
        for (FrameData f :
                List.of(Fixtures.swipe(), Fixtures.heavy(), Fixtures.leap(), Fixtures.lowSweep(), Fixtures.grab())) {
            attacks.put(f.id(), f);
        }
    }

    /**
     * Client tick {@code n}, run after the server's tick n. What the server recorded by tick n − latency arrives first
     * (at least a tick back: what the server's tick n records reaches a client already running its tick n after it).
     */
    void tick(long n, Arena a) {
        long from = n - Math.max(latency, 1);
        while (delivered < a.seen.size() && a.seen.get(delivered).tick() <= from) {
            take(a.seen.get(delivered++).event());
        }
        while (snapshotsTaken < p.snapshots.size()
                && p.snapshots.get(snapshotsTaken).sentTick() <= from) {
            Arena.Snapshot s = p.snapshots.get(snapshotsTaken++);
            if (s.tick() > n - 38) {
                snapshots++;
                if (!timeline.endAt(s.tick()).sameAs(s.state())) {
                    if (corrections.size() < 5) {
                        corrections.add(s.tick() + ": " + diff(timeline.endAt(s.tick()), s.state()));
                    }
                    corrected++;
                }
                timeline.rebase(s.tick(), s.state());
            }
        }
        double[] at = p.standAt(n);
        poses.put(Micros.ofTicks(n), at[0], at[1], at[2], at[3], 0);
        for (Input in : p.pressesIn(n)) {
            timeline.receive(in);
        }
        timeline.beginTick(n);
        predict(n);
        timeline.endTick(n);
    }

    private void take(Outbox.Event e) {
        switch (e) {
            case Outbox.PlayerEffect pe -> {
                if (pe.fighter() == p.f.id()) {
                    timeline.serverEffect(pe.effect(), pe.tick());
                    if (pe.effect() instanceof Effect.StrikeCommitted c) {
                        committed.add(c.id());
                    }
                }
            }
            case AttackStarted s -> started.put(s.strike(), s);
            case StrikeLocked l -> locked.put(l.strike(), l);
            default -> {}
        }
    }

    /** Predicts every strike on this player it knows of whose impact it has run. */
    private void predict(long n) {
        Iterator<AttackStarted> it = started.values().iterator();
        while (it.hasNext()) {
            AttackStarted s = it.next();
            long id = s.strike();
            if (s.impactTick() > n || !committed.contains(id) || !locked.containsKey(id)) {
                continue;
            }
            it.remove();
            FrameData f = attacks.get(s.attack());
            StrikeLocked l = locked.get(id);
            StrikeView view = new StrikeView(f)
                    .set(
                            id,
                            f,
                            s.windupStartTick(),
                            (int) (s.impactTick() - s.windupStartTick()),
                            s.cueTick(),
                            s.impactTick(),
                            s.last(),
                            l.x(),
                            l.y(),
                            l.z(),
                            Fixtures.DEMON_EYE,
                            l.yaw());
            Combatant state = timeline.stateAt(s.impactTick());
            boolean pommelled = f.attackClass() == AttackClass.GRAB
                    || (f.cue().type() == Cue.Type.RED && f.cue().charged());
            if (pommelled
                    && verdicts.pommelInterrupt(
                                    view,
                                    state,
                                    poses,
                                    Fixtures.PLAYER_EYE,
                                    Fixtures.pommel(),
                                    Fixtures.DEMON_HALF_WIDTH,
                                    Fixtures.DEMON_HEIGHT,
                                    Sight.OPEN)
                            != Verdicts.NONE) {
                predicted.put(id, INTERRUPTED);
                continue;
            }
            Verdict.Kind k = verdicts.judge(
                    view,
                    state,
                    poses,
                    Fixtures.PLAYER_HALF_WIDTH,
                    Fixtures.PLAYER_HEIGHT,
                    Sight.OPEN,
                    body.maxPosture(),
                    out);
            predicted.put(id, k.name());
        }
    }

    /**
     * The fields in which two snapshots of a machine differ, the client's first: a test failure's explanation. Reads
     * private state reflectively, which only a test may.
     */
    static String diff(Object client, Object server) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : client.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object x = f.get(client);
                Object y = f.get(server);
                if (x == null || y == null) {
                    if (x != null || y != null) {
                        sb.append(f.getName())
                                .append(": client ")
                                .append(x)
                                .append(", server ")
                                .append(y)
                                .append("; ");
                    }
                    continue;
                }
                Method sameAs = sameAs(x.getClass());
                if (sameAs != null) {
                    if (!(Boolean) sameAs.invoke(x, y)) {
                        sb.append(f.getName()).append(" {").append(diff(x, y)).append("} ");
                    }
                } else if (!Objects.deepEquals(x, y)) {
                    sb.append(f.getName())
                            .append(": client ")
                            .append(text(x))
                            .append(", server ")
                            .append(text(y))
                            .append("; ");
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }

    private static @Nullable Method sameAs(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals("sameAs") && m.getParameterCount() == 1) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static String text(Object o) {
        return switch (o) {
            case long[] l -> Arrays.toString(l);
            case int[] l -> Arrays.toString(l);
            case double[] l -> Arrays.toString(l);
            case Object[] l -> Arrays.deepToString(l);
            default -> String.valueOf(o);
        };
    }

    /** What the server decided strike {@code strike} did to player {@code fighter}, in the prediction's terms. */
    static String serverOutcome(Arena a, long strike, int fighter) {
        for (Outbox.AttackCancelled c : a.all(Outbox.AttackCancelled.class)) {
            if (c.strike() == strike) {
                return c.why().equals("pommel") ? INTERRUPTED : "CANCELLED " + c.why();
            }
        }
        return a.result(strike, fighter).kind().name();
    }
}
