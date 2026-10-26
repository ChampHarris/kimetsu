package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.fight.Audience;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.strike.PendingStrike;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * One arena's need-to-know (spec Netcode): each event goes to the players {@link Routing} lets see it, into a buffer
 * per player (reused; its message copies it, since single player's connection passes the message itself) that goes
 * out as a single {@code Tick} message when the step is over (D-049: nothing in a step overtakes
 * anything else). Viewers are the sessions in the arena's level whose clients are ready. One per arena, since strike
 * ids are per engine world. It keeps what a late viewer needs ({@link CatchUp}): a player who starts tracking one of
 * its mobs, or whose session turns ready, gets what that mob's live trackers already have, through the same
 * need-to-know, ahead of the next step's own events (slice 5c-4b, Step 5). Main thread only.
 */
final class Router implements Arena.Listener {
    private final Arena arena;
    private final Sessions sessions;
    private final Routing routing;
    private final CatchUp kept = new CatchUp();
    private final LongPredicate pending = this::pending; // made once: endStep runs every step

    Router(Arena arena, Sessions sessions, Trackers trackers) {
        this.arena = arena;
        this.sessions = sessions;
        this.routing = new Routing(trackers);
    }

    @Override
    public void accept(Outbox.Event e) {
        kept.record(e);
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
        kept.prune(pending);
    }

    /**
     * Ready viewer {@code v} starts seeing mob {@code mob}, or turned ready ({@link Audience#NOBODY}: every mob): what
     * live trackers already have of it goes into its buffer, each event {@link Routing} lets it see, so the next
     * {@code Tick} carries it ahead of that step's own events; a strike's replayed start lets it see the strike's rest.
     */
    void catchUp(PlayerSession v, int mob) {
        int id = v.combatId();
        kept.replay(mob, e -> {
            if (routing.reaches(e, id)) {
                v.seeAgain(e);
            }
        });
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
