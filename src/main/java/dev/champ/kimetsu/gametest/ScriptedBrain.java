package dev.champ.kimetsu.gametest;

import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.fight.Brain;
import dev.champ.kimetsu.core.fight.CombatWorld;
import dev.champ.kimetsu.core.fight.StrikeMob;
import dev.champ.kimetsu.core.strike.PendingStrike;
import dev.champ.kimetsu.core.strike.Scheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.IntSupplier;

/**
 * A GameTest's demon brain: it targets the player its test names and commits the strings it's given at the ticks it's
 * given, or, striking {@link #always}, a string whenever its last one has gone, so a test controls exactly what lands
 * when.
 */
final class ScriptedBrain implements Brain {
    private final Demon demon;
    private final IntSupplier target;
    private final TreeMap<Long, List<List<FrameData>>> commits = new TreeMap<>();
    private final List<Scheduler.Plan> plans = new ArrayList<>();
    private List<FrameData> always = List.of();

    ScriptedBrain(Demon demon, IntSupplier target) {
        this.demon = demon;
        this.target = target;
    }

    /** It commits {@code string} at tick {@code tick}. */
    ScriptedBrain at(long tick, FrameData... string) {
        commits.computeIfAbsent(tick, k -> new ArrayList<>()).add(List.of(string));
        return this;
    }

    /** It commits {@code string} on every tick none of its strikes is pending. */
    ScriptedBrain always(FrameData... string) {
        always = List.of(string);
        return this;
    }

    /** The plans its commits got, refusals included. */
    List<Scheduler.Plan> plans() {
        return plans;
    }

    @Override
    public void decide(long tick, StrikeMob self, CombatWorld world) {
        int t = target.getAsInt();
        demon.target(t);
        for (List<FrameData> string : commits.getOrDefault(tick, List.of())) {
            plans.add(world.commit(self.id(), t, string, Scheduler.ANY_BEAT));
        }
        if (!always.isEmpty() && !striking(world, self.id())) {
            plans.add(world.commit(self.id(), t, always, Scheduler.ANY_BEAT));
        }
    }

    private static boolean striking(CombatWorld world, int mob) {
        for (PendingStrike s : world.pending()) {
            if (s.attacker() == mob && s.status() == PendingStrike.Status.PENDING) {
                return true;
            }
        }
        return false;
    }
}
