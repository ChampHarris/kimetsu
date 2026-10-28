package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.fight.Audience;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.strike.PendingStrike;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
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
    private final List<PlayerSession> dueTo = new ArrayList<>(); // the catch-ups the next step takes, in order
    private final IntArrayList dueMob = new IntArrayList();

    Router(Arena arena, Sessions sessions, Trackers trackers) {
        this.arena = arena;
        this.sessions = sessions;
        this.routing = new Routing(trackers);
    }

    @Override
    public void accept(Outbox.Event e) {
        takeCatchUps();
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
        takeCatchUps();
        List<PlayerSession> viewers = sessions.viewersIn(arena.level());
        for (int i = 0; i < viewers.size(); i++) {
            viewers.get(i).flush(n);
        }
        routing.prune(pending);
        kept.prune(pending);
    }

    /**
     * Ready viewer {@code v} starts seeing mob {@code mob}, or turned ready ({@link Audience#NOBODY}: every mob): at the
     * next step, before its own events, what live trackers then have of it goes into its buffer, each event
     * {@link Routing} lets it see, so that step's {@code Tick} carries it first; a strike's replayed start lets it see
     * the strike's rest. Taken at the step, not now: vanilla pairs a player in the level tick and can unpair it before
     * the step, and a catch-up taken at once still reached it, with the rest of each strike it replayed (D-102).
     */
    void catchUp(PlayerSession v, int mob) {
        for (int i = 0; i < dueTo.size(); i++) {
            int due = dueMob.getInt(i);
            if (dueTo.get(i) == v && (due == mob || due == Audience.NOBODY)) {
                return; // re-paired before the step (as under /tick freeze, where none runs): once is enough
            }
        }
        dueTo.add(v);
        dueMob.add(mob);
    }

    /** The catch-ups due, for the viewers still in this arena's level (another level's strike ids aren't its). */
    private void takeCatchUps() {
        if (dueTo.isEmpty()) {
            return;
        }
        List<PlayerSession> viewers = sessions.viewersIn(arena.level());
        for (int i = 0; i < dueTo.size(); i++) {
            PlayerSession v = dueTo.get(i);
            if (viewers.contains(v)) {
                int id = v.combatId();
                kept.replay(dueMob.getInt(i), e -> {
                    if (routing.reaches(e, id)) {
                        v.seeAgain(e);
                    }
                });
            }
        }
        dueTo.clear();
        dueMob.clear();
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
