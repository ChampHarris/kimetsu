package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.strike.PendingStrike;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * One arena's need-to-know (spec Netcode): each event goes to the players {@link Routing} lets see it, into a buffer
 * per player (reused; its message copies it, since single player's connection passes the message itself) that goes
 * out as a single {@code Tick} message when the step is over (D-049: nothing in a step overtakes
 * anything else). Viewers are the sessions in the arena's level whose clients are ready. One per arena, since strike
 * ids are per engine world. Main thread only.
 */
final class Router implements Arena.Listener {
    private final Arena arena;
    private final Sessions sessions;
    private final Routing routing;
    private final LongPredicate pending = this::pending; // made once: endStep runs every step

    Router(Arena arena, Sessions sessions, Trackers trackers) {
        this.arena = arena;
        this.sessions = sessions;
        this.routing = new Routing(trackers);
    }

    @Override
    public void accept(Outbox.Event e) {
        List<PlayerSession> viewers = sessions.viewersIn(arena.level());
        for (int i = 0; i < viewers.size(); i++) {
            PlayerSession v = viewers.get(i);
            if (routing.reaches(e, v.combatId())) {
                v.see(e);
            }
        }
    }

    @Override
    public void endStep(long n) {
        List<PlayerSession> viewers = sessions.viewersIn(arena.level());
        for (int i = 0; i < viewers.size(); i++) {
            viewers.get(i).flush(n);
        }
        routing.prune(pending);
    }

    private boolean pending(long strike) {
        List<PendingStrike> book = arena.world().pending();
        for (int i = 0; i < book.size(); i++) {
            if (book.get(i).id() == strike) {
                return true;
            }
        }
        return false;
    }
}
