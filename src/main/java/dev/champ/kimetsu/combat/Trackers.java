package dev.champ.kimetsu.combat;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Which players track which combatants (spec Netcode, "Need-to-know"), kept from NeoForge's {@code StartTracking} and
 * {@code StopTracking}, which vanilla fires as an entity enters and leaves a player's view and again across a respawn
 * or a dimension change: no per-tick scan of the chunk map. Entity ids are unique on a server, so one set serves every
 * arena. Main thread only.
 */
final class Trackers {
    private final Int2ObjectOpenHashMap<IntOpenHashSet> byTarget = new Int2ObjectOpenHashMap<>();

    void start(int player, int target) {
        IntOpenHashSet s = byTarget.get(target);
        if (s == null) {
            s = new IntOpenHashSet(4);
            byTarget.put(target, s);
        }
        s.add(player);
    }

    void stop(int player, int target) {
        IntOpenHashSet s = byTarget.get(target);
        if (s != null) {
            s.remove(player);
            if (s.isEmpty()) {
                byTarget.remove(target);
            }
        }
    }

    boolean tracks(int player, int target) {
        IntOpenHashSet s = byTarget.get(target);
        return s != null && s.contains(player);
    }

    /** The player left: it tracks nothing, and nothing tracks it. */
    void forget(int player) {
        byTarget.remove(player);
        var it = byTarget.values().iterator();
        while (it.hasNext()) {
            IntOpenHashSet s = it.next();
            s.remove(player);
            if (s.isEmpty()) {
                it.remove();
            }
        }
    }
}
