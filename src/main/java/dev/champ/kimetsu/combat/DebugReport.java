package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.clock.TickRecord;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Fights;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Segment;
import dev.champ.kimetsu.entity.M1Demon;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jspecify.annotations.Nullable;

/**
 * What {@code /kimetsu debug} prints about one player (slice 5c-4a): its session (the link, its client's clock, the
 * frame gate), its fighter's meters, and its arena (the last second's budget figures and each demon). A few lines,
 * nothing per tick. A demon's Hunger is hidden information (spec Netcode, "Need-to-know"), so only a dev run shows it.
 * Main thread only.
 */
public final class DebugReport {
    private DebugReport() {}

    /** The lines about player {@code p} on combat server {@code c}. */
    public static List<String> of(CombatServer c, ServerPlayer p) {
        List<String> out = new ArrayList<>();
        out.add("Kimetsu debug: " + p.getScoreboardName());
        PlayerSession s = c.sessions().session(p.connection);
        if (s == null) {
            out.add("No combat session: its client doesn't have the mod's channels");
            return out;
        }
        out.add(session(c, s));
        Fighter f = s.fighter();
        Arena a = s.arena();
        out.add(f == null ? "Fighter: none, out of the fight" : fighter(f, a, s.combatId()));
        if (a != null) {
            arena(a, out);
        }
        return out;
    }

    private static String session(CombatServer c, PlayerSession s) {
        TickRecord r = c.published();
        double lag = s.intake().clockLagMicros();
        PlayerSession.GateCounts g = s.gateCounts();
        return String.format(
                Locale.ROOT,
                "Session: %s, RTT %.1f ms, its clock %s; server epoch %d, generation %d, T̄ %s; %d frames, %d merged,"
                        + " %s",
                s.ready() ? "ready" : "not ready",
                s.intake().rttMicros() / 1000.0,
                Double.isNaN(lag)
                        ? "unknown (no frames)"
                        : String.format(Locale.ROOT, "%.1f ms %s", Math.abs(lag) / 1000, lag >= 0 ? "behind" : "ahead"),
                c.epoch(),
                c.generation(),
                r == null ? "none" : String.format(Locale.ROOT, "%.2f ms", r.tickLengthNanos() / 1e6),
                g.arrived(),
                g.merged(),
                g.abuse() == null ? "no abuse" : "cut off for " + g.abuse());
    }

    private static String fighter(Fighter f, @Nullable Arena a, int id) {
        Timeline t = f.timeline();
        Combatant c = t.live();
        long now = t.current();
        Segment breath = c.breath().segment();
        Segment posture = c.posture().segment();
        Fights.Fight fight = a == null ? null : a.fights().fight(id);
        return String.format(
                Locale.ROOT,
                "Fighter: %s; Breath %.1f of %.1f (grey %.1f), Posture %.1f of %.1f, Rhythm %d (tier %d); %s; %s;"
                        + " tolerance %.1f ms, hold cap %.1f ms",
                c.state(),
                Fixed.toUnits(breath.at(now)),
                Fixed.toUnits(breath.max()),
                Fixed.toUnits(c.breath().grey()),
                Fixed.toUnits(posture.at(now)),
                Fixed.toUnits(posture.max()),
                c.rhythm().points(),
                c.rhythm().tier(),
                combatFlag(c.combatUntil(), now),
                fight == null ? "no open fight" : "in a fight since step " + fight.from(),
                f.toleranceMicros() / 1000.0,
                f.holdCapMicros() / 1000.0);
    }

    /**
     * Rule 13.1's flag at tick {@code now}, for a flag that drops at tick {@code until} ({@code Long.MIN_VALUE}: never
     * raised): compared, never subtracted, since the subtraction overflows for a fighter never in combat.
     */
    static String combatFlag(long until, long now) {
        return until > now
                ? String.format(Locale.ROOT, "in combat %.1f s more", (until - now) / 20.0)
                : "out of combat";
    }

    private static void arena(Arena a, List<String> out) {
        CombatServer c = a.server();
        List<ArenaMember> members = a.members();
        int fighting = 0; // a fight of the budget's: one Slayer, and the demons on it
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i) instanceof PlayerSession s && a.fights().fight(s.combatId()) != null) {
                fighting++;
            }
        }
        out.add(String.format(
                Locale.ROOT,
                "Arena %s, the last second of steps: steps %.1f µs mean, %.1f µs longest; the server's tick work, the"
                        + " steps among it, %.1f µs, frames %.1f µs a tick; %d players in a fight",
                a.level().dimension().identifier(),
                a.stepMeanMicros(),
                a.stepMaxMicros(),
                c.tickMeanMicros(),
                c.framesMeanMicros(),
                fighting));
        long tick = a.lastStep();
        boolean dev = !FMLEnvironment.isProduction();
        for (int i = 0; i < members.size(); i++) {
            if (!(members.get(i) instanceof M1Demon m)) {
                continue;
            }
            Demon d = m.body();
            if (d == null) {
                continue;
            }
            long thread = d.threadUntil(tick);
            out.add(String.format(
                    Locale.ROOT,
                    "  demon %d: %s, HP %.1f of %.1f, Posture %.1f of %.1f, Thread %s%s",
                    d.id(),
                    d.state(tick),
                    m.getHealth(),
                    m.getMaxHealth(),
                    d.posture(tick),
                    d.maxPosture(),
                    thread == Long.MIN_VALUE
                            ? "closed"
                            : thread == Long.MAX_VALUE ? "open" : "open " + (thread - tick) + " ticks more",
                    dev ? String.format(Locale.ROOT, ", Hunger %.1f (dev only)", d.hunger()) : ""));
        }
    }
}
