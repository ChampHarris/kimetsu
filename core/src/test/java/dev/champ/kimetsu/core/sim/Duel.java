package dev.champ.kimetsu.core.sim;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.StrikeRules;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.demon.DemonBrain;
import dev.champ.kimetsu.core.fight.CombatWorld;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.hit.Ground;
import dev.champ.kimetsu.core.hit.Sight;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.strike.PendingStrike;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One fight through the real engine: the M1 demon and its seeded brain against bot players, run tick by tick to a result
 * (a decapitation, every player down, or the time cap), with stand-ins for what slice 5's mod will do.
 *
 * <ul>
 *   <li><b>Each bot's client</b> keeps its own Timeline, as slice 5's will: its presses apply as it makes them, and what
 *       the server logged on its player arrives {@code latencyTicks} after the server's tick and is logged there
 *       ({@code serverEffect}). The bot plays from that view, never from the server's.
 *   <li><b>Latency:</b> each client tick t is sent at t and reaches the server {@code latencyTicks} later; the server's
 *       events reach the client as late (its own effects and its own swings' damage to it alone, the rest to everyone).
 *   <li><b>Players' movement</b> (vanilla's, for now a model): each keeps {@code SPACING} from the demon, walking at a
 *       sprint's 0.28 blocks a tick and facing it, its look at the demon's chest (or its neck for a Thread attempt); a
 *       Step dashes 3 blocks over 6 ticks to the side (Water's dash, alternating sides); a Jump follows vanilla's arc;
 *       a launched body lands 6 ticks after the hit, and the runner reports the landing.
 *   <li><b>The demon's movement</b> (SmartBrainLib's, a model): between its strikes it closes to 3 blocks of its target
 *       at 0.25 blocks a tick, facing it; its strikes move it themselves.
 *   <li><b>Damage to players</b> (vanilla's): each has 20 HP; a strike's damage and a throw's come off it, and at 0 it
 *       leaves the fight.
 * </ul>
 *
 * <p>A logged fight also keeps what the world was fed from outside it ({@link Recording}), so {@link #replay} can fight
 * it again without the bots.
 */
final class Duel {
    static final int DEMON = 10;
    static final double SPACING =
            2.4; // blocks, centre to centre: well inside a Chain hit's 2.9 (its 2.6 and the demon's half-width)
    static final double PLAYER_HP = 20; // m1_v0.json player.hp
    static final int MAX_TICKS = 12_000; // the duel sim's 600 s cap
    private static final double[] DASH = {0.7, 0.6, 0.5, 0.5, 0.4, 0.3};
    private static final double SPRINT = 0.28;
    private static final double DEMON_SPEED = 0.25;
    private static final double DEMON_CLOSES_TO = 3.0;
    private static final double FLOOR = 64;
    private static final double CHEST = 1.2;
    private static final int CLIENT_HISTORY_TICKS = 40;

    /** A player in the fight: its skill, its kind, its one-way latency in ticks, and where it starts around the demon. */
    record Seat(Skill skill, Bot.Kind kind, int latencyTicks, double angleDeg) {
        static Seat solo(Skill skill, Bot.Kind kind) {
            return new Seat(skill, kind, 1, 0);
        }
    }

    /**
     * One player's tally: the strikes on it by colour and how it answered them, the hits it took, its HP left, and its
     * clashes and the ones it won.
     */
    record Tally(
            int whites,
            int perfects,
            int deflects,
            int blocks,
            int redPurple,
            int dodges,
            int hits,
            double hp,
            int clashes,
            int clashWins) {}

    /**
     * How the fight ended, when, each player's tally, and (when asked for) every event as text, the world's state at
     * the end as text, and what it was fed.
     */
    record Result(Outcome outcome, long ticks, List<Tally> tallies, List<String> log, String end, Recording recording) {
        double seconds() {
            return ticks / 20.0;
        }
    }

    enum Outcome {
        WIN,
        LOSS,
        TIMEOUT
    }

    /** What the world was fed from outside it, tick by tick from tick 1, in the order it was fed. */
    record Recording(List<List<Feed>> ticks) {}

    /** One thing fed to the world between its ticks. */
    sealed interface Feed {}

    /** A player's client tick {@code sent} arriving: its presses, then its pose. */
    record Frame(int fighter, long sent, List<Input> inputs, double x, double y, double z, double yaw, double pitch)
            implements Feed {}

    /** The mod reporting a knocked-down player's landing. */
    record Landed(int fighter, long micros) implements Feed {}

    /** The demon moved by its movement model. */
    record Moved(double x, double y, double z, double yaw) implements Feed {}

    /** A player leaving the fight (down at 0 HP). */
    record Left(int fighter) implements Feed {}

    private static final class Player {
        final Seat seat;
        final Fighter f;
        final Timeline client;
        final Bot bot;
        final ArrayDeque<Object[]> frames = new ArrayDeque<>(); // {arrival, sent tick, inputs, pose}
        final ArrayDeque<Object[]> inbox = new ArrayDeque<>(); // {deliver at, event}
        final List<long[]> landings = new ArrayList<>(); // {report at, landing tick}
        double x;
        double z;
        double y = FLOOR;
        double vy;
        int dash = -1;
        double dashX;
        double dashZ;
        int side = 1;
        double hp = PLAYER_HP;
        boolean down;
        int whites;
        int perfects;
        int deflects;
        int blocks;
        int redPurple;
        int dodges;
        int hits;
        int clashes;
        int clashWins;

        Player(Seat seat, Fighter f, Timeline client, Bot bot) {
            this.seat = seat;
            this.f = f;
            this.client = client;
            this.bot = bot;
        }
    }

    /** The world a fight runs in, with its players' server-side fighters and its demon. */
    private record Setup(CombatWorld world, PulseSchedule pulse, List<Fighter> fighters, Demon demon) {}

    private Duel() {}

    private static Setup setUp(StrikeRules strikes, List<Seat> seats, long seed) {
        CombatWorld world = new CombatWorld(
                Fixtures.hits(),
                strikes,
                Fixtures.timing(),
                Fixtures.rhythm(),
                Fixtures.water(),
                Sight.OPEN,
                Ground.OPEN);
        PulseSchedule pulse = new PulseSchedule(Fixtures.water().style().beatTicks());
        List<Fighter> fighters = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            long rtt = Micros.ofTicks(2L * s.latencyTicks());
            long tolerance = Micros.ofMillis(Fixtures.timing().toleranceMinMs());
            Fighter f = new Fighter(
                    i + 1,
                    new Timeline(combatant(s, pulse), 1),
                    new InputHorizon(Fixtures.timing()),
                    pulse,
                    Fixtures.PLAYER_HALF_WIDTH,
                    Fixtures.PLAYER_HEIGHT,
                    Fixtures.PLAYER_EYE,
                    InputHorizon.holdCapMicros(Fixtures.timing(), rtt, tolerance));
            f.rttMicros(rtt);
            f.toleranceMicros(tolerance);
            world.add(f);
            fighters.add(f);
        }
        Demon demon = new Demon(DEMON, Fixtures.demon(), Fixtures.hits(), 1, 34, 0, FLOOR, 0, 0, seed);
        world.add(demon, new DemonBrain(demon, strikes, Fixtures.demonAttacks(), seed ^ 0x5DEECE66DL));
        return new Setup(world, pulse, fighters, demon);
    }

    private static Combatant combatant(Seat s, PulseSchedule pulse) {
        Combatant c = new Combatant(
                Fixtures.machine(),
                Fixtures.timing(),
                Fixtures.breath(),
                Fixtures.playerPosture(),
                Fixtures.rhythm(),
                Fixtures.water(),
                pulse,
                0);
        if (s.skill().assist() > 0) {
            c.effect(new Effect.AssistChanged(s.skill().assist()), 0);
        }
        return c;
    }

    /**
     * Runs a fight of {@code seats} seeded with {@code seed}; {@code log} keeps every event as text, the end state and
     * the recording (determinism).
     */
    static Result run(List<Seat> seats, long seed, int maxTicks, boolean log) {
        return run(Fixtures.strikes(), seats, seed, maxTicks, log);
    }

    /** The same on combat rules {@code strikes}: a rule under measurement ({@code ClashShareMeasure}). */
    static Result run(StrikeRules strikes, List<Seat> seats, long seed, int maxTicks, boolean log) {
        Setup u = setUp(strikes, seats, seed);
        CombatWorld world = u.world();
        Demon demon = u.demon();
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            Timeline client = new Timeline(combatant(s, u.pulse()), 1, CLIENT_HISTORY_TICKS);
            Bot bot = new Bot(i + 1, DEMON, s.skill(), s.kind(), seed * 31 + i + 1, u.pulse());
            Player p = new Player(s, u.fighters().get(i), client, bot);
            double a = Math.toRadians(s.angleDeg());
            p.x = SPACING * Math.sin(a);
            p.z = SPACING * Math.cos(a);
            players.add(p);
        }
        Map<Long, FrameData.Cue.Type> cues = new HashMap<>();
        Map<Long, Integer> targets = new HashMap<>();
        List<String> lines = new ArrayList<>();
        List<List<Feed>> fedTicks = new ArrayList<>();
        List<Feed> next = new ArrayList<>(); // what the next tick is fed: departures, from the tick before
        Outcome outcome = Outcome.TIMEOUT;
        long n = 0;
        while (n < maxTicks) {
            n++;
            List<Feed> fed = next;
            next = new ArrayList<>();
            for (Player p : players) {
                if (!p.down) {
                    clientTick(p, n, demon);
                }
            }
            Moved m = steer(demon, world, players);
            if (m != null) {
                feed(u, m, fed);
            }
            for (Player p : players) {
                if (p.down) {
                    continue;
                }
                while (!p.frames.isEmpty() && (long) p.frames.peekFirst()[0] <= n) {
                    Object[] fr = p.frames.pollFirst();
                    @SuppressWarnings("unchecked")
                    List<Input> ins = (List<Input>) fr[2];
                    double[] q = (double[]) fr[3];
                    feed(u, new Frame(p.f.id(), (long) fr[1], ins, q[0], q[1], q[2], q[3], q[4]), fed);
                }
                for (int i = p.landings.size() - 1; i >= 0; i--) {
                    long[] l = p.landings.get(i);
                    if (l[0] <= n) {
                        feed(u, new Landed(p.f.id(), Micros.ofTicks(l[1])), fed);
                        p.landings.remove(i);
                    }
                }
            }
            world.tick(n);
            if (log) {
                fedTicks.add(fed);
            }
            boolean won = false;
            for (Outbox.Event e : world.outbox().drain()) {
                if (log) {
                    lines.add(n + " " + e);
                }
                switch (e) {
                    case Outbox.AttackStarted a -> {
                        FrameData f = Fixtures.demonAttacks().get(a.attack());
                        if (f != null) {
                            cues.put(a.strike(), f.cue().type());
                            targets.put(a.strike(), a.target());
                        }
                    }
                    case Outbox.StrikeResult r -> tally(players, r, cues, targets);
                    case Outbox.HoldEnded h -> {
                        Player p = player(players, h.fighter());
                        if (p != null) {
                            p.hp -= h.damage();
                        }
                    }
                    case Outbox.MobDied d -> won |= d.mob() == DEMON;
                    case Outbox.ClashEnded c -> {
                        Player p = player(players, c.fighter());
                        if (p != null) {
                            p.clashes++;
                            p.clashWins += c.outcome() == Outbox.ClashOutcome.PLAYER ? 1 : 0;
                        }
                    }
                    default -> {}
                }
                for (Player p : players) {
                    if (!p.down && reaches(e, p.f.id())) {
                        p.inbox.addLast(new Object[] {n + p.seat.latencyTicks(), e});
                    }
                }
            }
            if (won) {
                outcome = Outcome.WIN;
                break;
            }
            boolean anyUp = false;
            for (Player p : players) {
                if (!p.down && p.hp <= 0) {
                    p.down = true;
                    world.remove(p.f.id());
                    next.add(new Left(p.f.id()));
                }
                anyUp |= !p.down;
            }
            if (!anyUp) {
                outcome = Outcome.LOSS;
                break;
            }
        }
        List<Tally> tallies = new ArrayList<>();
        for (Player p : players) {
            tallies.add(new Tally(
                    p.whites,
                    p.perfects,
                    p.deflects,
                    p.blocks,
                    p.redPurple,
                    p.dodges,
                    p.hits,
                    p.hp,
                    p.clashes,
                    p.clashWins));
        }
        String end = log ? end(u, n) : "";
        return new Result(outcome, n, tallies, lines, end, new Recording(fedTicks));
    }

    /**
     * Fights a logged fight again from what it was fed alone, with the same seats and seed and no bots: its events as
     * text, then the world's state at the end.
     */
    static List<String> replay(List<Seat> seats, long seed, Recording r) {
        Setup u = setUp(Fixtures.strikes(), seats, seed);
        List<String> lines = new ArrayList<>();
        long n = 0;
        for (List<Feed> fed : r.ticks()) {
            n++;
            for (Feed f : fed) {
                feed(u, f, null);
            }
            u.world().tick(n);
            for (Outbox.Event e : u.world().outbox().drain()) {
                lines.add(n + " " + e);
            }
        }
        lines.add(end(u, n));
        return lines;
    }

    /** Feeds {@code f} to the world, and records it in {@code fed} unless that's null. */
    private static void feed(Setup u, Feed f, List<Feed> fed) {
        switch (f) {
            case Frame fr -> {
                Fighter p = u.fighters().get(fr.fighter() - 1);
                for (Input in : fr.inputs()) {
                    p.timeline().receive(in);
                }
                long at = Micros.ofTicks(fr.sent());
                p.poses().put(at, fr.x(), fr.y(), fr.z(), fr.yaw(), fr.pitch());
                p.horizon().frame(at, 16_667, at);
            }
            case Landed l -> u.world().landed(l.fighter(), Effect.Surface.FLOOR, l.micros());
            case Moved m -> u.demon().moveTo(m.x(), m.y(), m.z(), m.yaw());
            case Left l -> u.world().remove(l.fighter());
        }
        if (fed != null) {
            fed.add(f);
        }
    }

    /** The demon's and every player's state at tick {@code n}, as text. */
    private static String end(Setup u, long n) {
        StringBuilder sb = new StringBuilder("end ")
                .append(n)
                .append(": demon ")
                .append(u.demon().state(n))
                .append(" hp ")
                .append(u.demon().hp())
                .append(" posture ")
                .append(u.demon().posture(n));
        for (Fighter f : u.fighters()) {
            Combatant c = f.timeline().live();
            sb.append("; player ")
                    .append(f.id())
                    .append(' ')
                    .append(c.state())
                    .append(" breath ")
                    .append(c.breath().at(n))
                    .append(" posture ")
                    .append(c.posture().at(n))
                    .append(" tier ")
                    .append(c.rhythm().tier());
        }
        return sb.toString();
    }

    /**
     * Player {@code p}'s client tick {@code n}: what reached it, then its bot's presses, applied to its own Timeline,
     * then its movement; the frame goes to the server, arriving its latency later.
     */
    private static void clientTick(Player p, long n, Demon demon) {
        while (!p.inbox.isEmpty() && (long) p.inbox.peekFirst()[0] <= n) {
            Outbox.Event e = (Outbox.Event) p.inbox.pollFirst()[1];
            if (e instanceof Outbox.PlayerEffect pe && pe.fighter() == p.f.id()) {
                p.client.serverEffect(pe.effect(), pe.tick());
            }
            p.bot.see(e, n);
        }
        List<Input> ins = p.bot.tick(n, p.client.live());
        for (Input in : ins) {
            p.client.receive(in);
        }
        p.client.beginTick(n);
        p.client.endTick(n);
        move(p, ins, demon);
        p.frames.addLast(new Object[] {
            n + p.seat.latencyTicks(), n, ins, new double[] {p.x, p.y, p.z, yaw(p, demon), pitch(p, demon, n)}
        });
        long landed = p.bot.takeLanding(n);
        if (landed != Long.MIN_VALUE) {
            p.landings.add(new long[] {n + p.seat.latencyTicks(), landed});
        }
    }

    /** Whether event {@code e} goes to player {@code fighter}'s client: need-to-know, as slice 5 routes it. */
    private static boolean reaches(Outbox.Event e, int fighter) {
        return switch (e) {
            case Outbox.PlayerEffect pe -> pe.fighter() == fighter;
            case Outbox.SwingDealt sd -> sd.fighter() == fighter;
            default -> true;
        };
    }

    private static void tally(
            List<Player> players,
            Outbox.StrikeResult r,
            Map<Long, FrameData.Cue.Type> cues,
            Map<Long, Integer> targets) {
        Player p = player(players, r.fighter());
        FrameData.Cue.Type cue = cues.get(r.strike());
        Integer target = targets.get(r.strike());
        if (p == null || cue == null) {
            return;
        }
        p.hp -= r.damage();
        Verdict.Kind k = r.kind();
        boolean hit = k == Verdict.Kind.HIT || k == Verdict.Kind.GRAB || k == Verdict.Kind.LAUNCH;
        if (hit) {
            p.hits++;
        }
        if (target == null || target != p.f.id()) {
            return; // a strike on someone else that caught it: hurts, but isn't its to answer
        }
        if (cue == FrameData.Cue.Type.WHITE) {
            p.whites++;
            if (k == Verdict.Kind.PERFECT) {
                p.perfects++;
            } else if (k == Verdict.Kind.DEFLECT) {
                p.deflects++;
            } else if (k == Verdict.Kind.BLOCK) {
                p.blocks++;
            }
        } else {
            p.redPurple++;
            if (k == Verdict.Kind.MISS || k == Verdict.Kind.CLEARED) {
                p.dodges++;
            }
        }
    }

    private static Player player(List<Player> players, int id) {
        for (Player p : players) {
            if (p.f.id() == id) {
                return p;
            }
        }
        return null;
    }

    /** Its client's movement in tick {@code t}: a Step's dash, a Jump's arc, or keeping its spacing. */
    private static void move(Player p, List<Input> ins, Demon demon) {
        Combatant me = p.client.live();
        State st = me.state();
        boolean held = st == State.GRABBED || st == State.CLASH || st == State.KNOCKDOWN || st == State.DEAD;
        double dx = demon.x() - p.x;
        double dz = demon.z() - p.z;
        double dist = Math.max(1e-6, Math.hypot(dx, dz));
        for (Input in : ins) {
            if (in.kind() == Input.Kind.STEP && !held && p.dash < 0 && st != State.HITSTUN && st != State.STAGGER) {
                p.dash = 0;
                p.side = -p.side; // alternating sides
                p.dashX = -dz / dist * p.side;
                p.dashZ = dx / dist * p.side;
            } else if (in.kind() == Input.Kind.JUMP && !held && p.y <= FLOOR) {
                p.vy = 0.42;
            }
        }
        if (p.dash >= 0) {
            p.x += p.dashX * DASH[p.dash];
            p.z += p.dashZ * DASH[p.dash];
            p.dash = p.dash + 1 < DASH.length ? p.dash + 1 : -1;
        } else if (!held && st != State.STAGGER) {
            double step = Math.max(-SPRINT, Math.min(SPRINT, dist - SPACING));
            p.x += dx / dist * step;
            p.z += dz / dist * step;
        }
        if (p.y > FLOOR || p.vy > 0) {
            p.y += p.vy;
            p.vy = (p.vy - 0.08) * 0.98;
            if (p.y <= FLOOR) {
                p.y = FLOOR;
                p.vy = 0;
            }
        }
    }

    /** Between its strikes, the demon closes on its target (SmartBrainLib's, a model): where it moves, or null. */
    private static Moved steer(Demon demon, CombatWorld world, List<Player> players) {
        for (PendingStrike s : world.pending()) {
            if (s.attacker() == DEMON) {
                return null;
            }
        }
        Player target = player(players, demon.target());
        if (target == null || target.down) {
            return null;
        }
        double dx = target.x - demon.x();
        double dz = target.z - demon.z();
        double dist = Math.hypot(dx, dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double step = dist > DEMON_CLOSES_TO ? Math.min(DEMON_SPEED, dist - DEMON_CLOSES_TO) : 0;
        return new Moved(
                demon.x() + dx / Math.max(dist, 1e-6) * step,
                demon.y(),
                demon.z() + dz / Math.max(dist, 1e-6) * step,
                yaw);
    }

    private static double yaw(Player p, Demon demon) {
        return Math.toDegrees(Math.atan2(-(demon.x() - p.x), demon.z() - p.z));
    }

    /** Its look's pitch (positive: down): at the demon's chest, or at its neck (standing or collapsed) for the Thread. */
    private static double pitch(Player p, Demon demon, long t) {
        double h = CHEST;
        if (p.bot.aimsNeck(t)) {
            FrameData.Sweep.Box neck = p.bot.neckCollapsed()
                    ? demon.profile().neck().collapsed()
                    : demon.profile().neck().standing();
            h = neck.cy();
        }
        double eye = p.y + Fixtures.PLAYER_EYE;
        double dist = Math.hypot(demon.x() - p.x, demon.z() - p.z);
        return Math.toDegrees(Math.atan2(eye - (demon.y() + h), dist));
    }
}
