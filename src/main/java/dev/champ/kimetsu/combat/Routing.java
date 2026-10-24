package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.fight.Audience;
import dev.champ.kimetsu.core.fight.Outbox;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.LongPredicate;

/**
 * Who may see each event (spec Netcode, "Need-to-know"), by core's {@link Audience}: whether the viewer tracks the
 * event's subject and its party, and for an event that names only its strike, whether it got that strike's
 * {@code AttackStarted}, a list kept while the strike is pending. A player sees its own events though it never tracks
 * itself. Apart from the router so it can be tested without a world.
 */
final class Routing {
    private final Trackers trackers;
    private final Long2ObjectOpenHashMap<IntOpenHashSet> strikeViewers = new Long2ObjectOpenHashMap<>();

    Routing(Trackers trackers) {
        this.trackers = trackers;
    }

    /** Whether player {@code viewer} may see {@code e}; an {@code AttackStarted} it sees lets it see its strike's rest. */
    boolean reaches(Outbox.Event e, int viewer) {
        int subject = Audience.subject(e);
        boolean seesSubject;
        if (subject == Audience.NOBODY) {
            IntOpenHashSet got = strikeViewers.get(Audience.strike(e));
            seesSubject = got != null && got.contains(viewer);
        } else {
            seesSubject = subject == viewer || trackers.tracks(viewer, subject);
        }
        int party = Audience.party(e);
        boolean tracksParty = party != Audience.NOBODY && trackers.tracks(viewer, party);
        boolean r = Audience.reaches(e, viewer, seesSubject, tracksParty);
        if (r && e instanceof Outbox.AttackStarted a) {
            IntOpenHashSet got = strikeViewers.get(a.strike());
            if (got == null) {
                got = new IntOpenHashSet(4);
                strikeViewers.put(a.strike(), got);
            }
            got.add(viewer);
        }
        return r;
    }

    /** Forgets the viewers of every strike that is no longer {@code pending}: it sends nothing more. */
    void prune(LongPredicate pending) {
        if (strikeViewers.isEmpty()) {
            return;
        }
        var it = strikeViewers.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<IntOpenHashSet> entry = it.next();
            if (!pending.test(entry.getLongKey())) {
                it.remove();
            }
        }
    }
}
