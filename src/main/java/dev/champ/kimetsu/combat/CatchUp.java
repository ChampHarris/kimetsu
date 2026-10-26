package dev.champ.kimetsu.combat;

import dev.champ.kimetsu.core.fight.Audience;
import dev.champ.kimetsu.core.fight.Outbox;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;

/**
 * What a player who starts seeing a mob late needs to show it as its live trackers do (slice 5c-4b, Step 5; S534,
 * S672): each mob's latest {@code MobState}, {@code GuardYaw} and {@code Targeting}, and each pending strike's
 * {@code AttackStarted}, {@code StrikeLocked} and {@code Involved}s, kept from the events its router routes. They're
 * the events already made, kept by reference: a mob's go when it dies or leaves, a strike's when it's cancelled or
 * no longer in the strike book. A replay goes through the router's need-to-know like a live event, and the client
 * keys mob states and strikes by id, so one it already has is overwritten with itself. What happens once (a result, a
 * hit, a clang, a push) isn't a view to restore; a hold or a clash under way isn't replayed (5d's HUD). Apart from the
 * router so it can be tested without a world. Main thread only.
 */
final class CatchUp {
    /** A mob's view, as its trackers last got it. */
    private static final class View {
        Outbox.@Nullable MobState state;
        Outbox.@Nullable GuardYaw yaw;
        Outbox.@Nullable Targeting targeting;
    }

    /** A pending strike's events that last for it, in the order they came. */
    private static final class Strike {
        final Outbox.AttackStarted start;
        Outbox.@Nullable StrikeLocked lock;
        final List<Outbox.Involved> involved = new ArrayList<>(2);

        Strike(Outbox.AttackStarted start) {
            this.start = start;
        }
    }

    private final Int2ObjectOpenHashMap<View> views = new Int2ObjectOpenHashMap<>();
    private final List<Strike> strikes = new ArrayList<>(); // in commit order: a few per arena

    /** An event the router routes. Every event is named, so a new one can't be left out unthought. */
    void record(Outbox.Event e) {
        switch (e) {
            case Outbox.MobState m -> view(m.mob()).state = m;
            case Outbox.GuardYaw g -> view(g.mob()).yaw = g;
            case Outbox.Targeting t -> view(t.mob()).targeting = t;
            case Outbox.MobDied d -> views.remove(d.mob());
            case Outbox.MobLeft l -> views.remove(l.mob());
            case Outbox.AttackStarted a -> strikes.add(new Strike(a));
            case Outbox.StrikeLocked l -> {
                Strike s = strike(l.strike());
                if (s != null) {
                    s.lock = l;
                }
            }
            case Outbox.Involved v -> {
                Strike s = strike(v.strike());
                if (s != null) {
                    s.involved.add(v);
                }
            }
            // at once: the book may hold a cancelled strike until it purges, and a start without its end is a phantom
            case Outbox.AttackCancelled c -> forget(c.strike());
            case Outbox.StrikeResult _ -> {} // a strike decided for everyone leaves the book: prune drops it
            case Outbox.PlayerEffect _ -> {}
            case Outbox.SwingLanded _ -> {}
            case Outbox.SwingDealt _ -> {}
            case Outbox.HoldStarted _ -> {}
            case Outbox.HoldEnded _ -> {}
            case Outbox.ClashStarted _ -> {}
            case Outbox.ClashEnded _ -> {}
            case Outbox.Clang _ -> {}
            case Outbox.Pushed _ -> {}
            case Outbox.WallSplat _ -> {}
            case Outbox.PoseClamped _ -> {}
            case Outbox.GuardJudged _ -> {}
        }
    }

    /** At a step's end: the strikes no longer {@code pending} are forgotten. Allocation-free. */
    void prune(LongPredicate pending) {
        for (int i = strikes.size() - 1; i >= 0; i--) {
            if (!pending.test(strikes.get(i).start.strike())) {
                strikes.remove(i);
            }
        }
    }

    /**
     * Mob {@code mob}'s view and pending strikes, or every mob's ({@link Audience#NOBODY}), to {@code to}: a strike's
     * start before the rest of it, which only the viewers of its start may see.
     */
    void replay(int mob, Consumer<Outbox.Event> to) {
        if (mob == Audience.NOBODY) {
            for (View v : views.values()) {
                replay(v, to);
            }
        } else {
            View v = views.get(mob);
            if (v != null) {
                replay(v, to);
            }
        }
        for (int i = 0; i < strikes.size(); i++) {
            Strike s = strikes.get(i);
            if (mob != Audience.NOBODY && s.start.attacker() != mob) {
                continue;
            }
            to.accept(s.start);
            if (s.lock != null) {
                to.accept(s.lock);
            }
            for (int k = 0; k < s.involved.size(); k++) {
                to.accept(s.involved.get(k));
            }
        }
    }

    private static void replay(View v, Consumer<Outbox.Event> to) {
        if (v.state != null) {
            to.accept(v.state);
        }
        if (v.yaw != null) {
            to.accept(v.yaw);
        }
        if (v.targeting != null) {
            to.accept(v.targeting);
        }
    }

    private View view(int mob) {
        View v = views.get(mob);
        if (v == null) {
            v = new View();
            views.put(mob, v);
        }
        return v;
    }

    private @Nullable Strike strike(long id) {
        for (int i = 0; i < strikes.size(); i++) {
            if (strikes.get(i).start.strike() == id) {
                return strikes.get(i);
            }
        }
        return null;
    }

    private void forget(long id) {
        for (int i = strikes.size() - 1; i >= 0; i--) {
            if (strikes.get(i).start.strike() == id) {
                strikes.remove(i);
            }
        }
    }
}
