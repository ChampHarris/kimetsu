package dev.champ.kimetsu.core.fight;

/**
 * Who receives each outbox event (spec Netcode, "Need-to-know" and the payload table). A player's own effects, the
 * damage its own swings deal and its wall splats go to that player alone. Everything else is public: it goes to every
 * player tracking the combatant it concerns (its subject: a mob, or a strike's attacker) and to the player it names (its party: the one
 * targeted, struck, held or swinging), and a strike's result, a hold and a clash also to the party's own trackers
 * ("trackers of either side", "both fighters' trackers"). A strike event that names only its strike (its lock, an
 * involvement, a cancel) goes to whoever got that strike's {@code AttackStarted}, which the router remembers. A pose
 * clamp and a guard's record name no subject, strike or party here (the record carries a strike and a player, but
 * names neither for routing), so they reach nobody: they're the fight log's. No event carries a demon's
 * Hunger. Every switch names every event, with no default, so a new event can't go out unrouted.
 * Allocation-free, for the mod's per-tick routing pass; the bots route by it too.
 */
public final class Audience {
    /** No player. */
    public static final int NOBODY = -1;

    /** No strike. */
    public static final long NO_STRIKE = Long.MIN_VALUE;

    private Audience() {}

    /** The one player {@code e} is for, or {@link #NOBODY} if it's public. */
    public static int privateTo(Outbox.Event e) {
        return switch (e) {
            case Outbox.PlayerEffect p -> p.fighter();
            case Outbox.SwingDealt d -> d.fighter();
            case Outbox.WallSplat w -> w.fighter();
            case Outbox.AttackStarted _ -> NOBODY;
            case Outbox.StrikeLocked _ -> NOBODY;
            case Outbox.Involved _ -> NOBODY;
            case Outbox.AttackCancelled _ -> NOBODY;
            case Outbox.StrikeResult _ -> NOBODY;
            case Outbox.SwingLanded _ -> NOBODY;
            case Outbox.HoldStarted _ -> NOBODY;
            case Outbox.HoldEnded _ -> NOBODY;
            case Outbox.ClashStarted _ -> NOBODY;
            case Outbox.ClashEnded _ -> NOBODY;
            case Outbox.MobDied _ -> NOBODY;
            case Outbox.MobLeft _ -> NOBODY;
            case Outbox.MobState _ -> NOBODY;
            case Outbox.GuardYaw _ -> NOBODY;
            case Outbox.Targeting _ -> NOBODY;
            case Outbox.Clang _ -> NOBODY;
            case Outbox.Pushed _ -> NOBODY;
            case Outbox.PoseClamped _ -> NOBODY;
            case Outbox.GuardJudged _ -> NOBODY;
        };
    }

    /**
     * The combatant whose trackers see public event {@code e}; {@link #NOBODY} for an event that names only its strike
     * ({@link #strike}), which reaches whoever got that strike's {@code AttackStarted}.
     */
    public static int subject(Outbox.Event e) {
        return switch (e) {
            case Outbox.AttackStarted a -> a.attacker();
            case Outbox.StrikeResult r -> r.attacker();
            case Outbox.SwingLanded l -> l.mob();
            case Outbox.HoldStarted h -> h.mob();
            case Outbox.HoldEnded h -> h.mob();
            case Outbox.ClashStarted c -> c.mob();
            case Outbox.ClashEnded c -> c.mob();
            case Outbox.MobDied d -> d.mob();
            case Outbox.MobLeft l -> l.mob();
            case Outbox.MobState m -> m.mob();
            case Outbox.GuardYaw g -> g.mob();
            case Outbox.Targeting t -> t.mob();
            case Outbox.Clang c -> c.mob();
            case Outbox.Pushed p -> p.mob();
            case Outbox.StrikeLocked _ -> NOBODY;
            case Outbox.Involved _ -> NOBODY;
            case Outbox.AttackCancelled _ -> NOBODY;
            case Outbox.PlayerEffect _ -> NOBODY;
            case Outbox.SwingDealt _ -> NOBODY;
            case Outbox.WallSplat _ -> NOBODY;
            case Outbox.PoseClamped _ -> NOBODY; // nobody tracks it: the log's alone
            case Outbox.GuardJudged _ -> NOBODY; // its strike's viewers mustn't get it: the log's alone
        };
    }

    /** The strike {@code e} belongs to, or {@link #NO_STRIKE}. */
    public static long strike(Outbox.Event e) {
        return switch (e) {
            case Outbox.AttackStarted a -> a.strike();
            case Outbox.StrikeLocked l -> l.strike();
            case Outbox.Involved v -> v.strike();
            case Outbox.AttackCancelled c -> c.strike();
            case Outbox.StrikeResult r -> r.strike();
            case Outbox.HoldStarted h -> h.strike();
            case Outbox.HoldEnded h -> h.strike();
            case Outbox.ClashStarted c -> c.strike();
            case Outbox.ClashEnded c -> c.strike();
            case Outbox.PlayerEffect _ -> NO_STRIKE;
            case Outbox.SwingLanded _ -> NO_STRIKE;
            case Outbox.SwingDealt _ -> NO_STRIKE;
            case Outbox.MobDied _ -> NO_STRIKE;
            case Outbox.MobLeft _ -> NO_STRIKE;
            case Outbox.MobState _ -> NO_STRIKE;
            case Outbox.GuardYaw _ -> NO_STRIKE;
            case Outbox.Targeting _ -> NO_STRIKE;
            case Outbox.Clang _ -> NO_STRIKE;
            case Outbox.Pushed _ -> NO_STRIKE;
            case Outbox.WallSplat _ -> NO_STRIKE;
            case Outbox.PoseClamped _ -> NO_STRIKE;
            case Outbox.GuardJudged _ -> NO_STRIKE;
        };
    }

    /** The player public event {@code e} names, who gets it whatever it tracks, or {@link #NOBODY}. */
    public static int party(Outbox.Event e) {
        return switch (e) {
            case Outbox.AttackStarted a -> a.target();
            case Outbox.Involved v -> v.fighter();
            case Outbox.StrikeResult r -> r.fighter();
            case Outbox.SwingLanded l -> l.fighter();
            case Outbox.HoldStarted h -> h.fighter();
            case Outbox.HoldEnded h -> h.fighter();
            case Outbox.ClashStarted c -> c.fighter();
            case Outbox.ClashEnded c -> c.fighter();
            case Outbox.MobDied d -> d.by(); // −1 when nobody cut it: NOBODY
            case Outbox.MobLeft _ -> NOBODY;
            case Outbox.Targeting t -> t.fighter();
            case Outbox.Clang c -> c.fighter();
            case Outbox.StrikeLocked _ -> NOBODY;
            case Outbox.AttackCancelled _ -> NOBODY;
            case Outbox.PlayerEffect _ -> NOBODY;
            case Outbox.SwingDealt _ -> NOBODY;
            case Outbox.MobState _ -> NOBODY;
            case Outbox.GuardYaw _ -> NOBODY;
            case Outbox.Pushed _ -> NOBODY;
            case Outbox.WallSplat _ -> NOBODY;
            case Outbox.PoseClamped _ -> NOBODY;
            case Outbox.GuardJudged _ -> NOBODY;
        };
    }

    /**
     * Whether the trackers of {@code e}'s party see it too: a strike's result (the payload table's "trackers of either
     * side"), a hold and a clash ("both fighters' trackers"), which show on the player as well as on the mob.
     */
    public static boolean partyTrackers(Outbox.Event e) {
        return switch (e) {
            case Outbox.StrikeResult _ -> true;
            case Outbox.HoldStarted _ -> true;
            case Outbox.HoldEnded _ -> true;
            case Outbox.ClashStarted _ -> true;
            case Outbox.ClashEnded _ -> true;
            case Outbox.AttackStarted _ -> false;
            case Outbox.StrikeLocked _ -> false;
            case Outbox.Involved _ -> false;
            case Outbox.AttackCancelled _ -> false;
            case Outbox.PlayerEffect _ -> false;
            case Outbox.SwingLanded _ -> false;
            case Outbox.SwingDealt _ -> false;
            case Outbox.MobDied _ -> false;
            case Outbox.MobLeft _ -> false;
            case Outbox.MobState _ -> false;
            case Outbox.GuardYaw _ -> false;
            case Outbox.Targeting _ -> false;
            case Outbox.Clang _ -> false;
            case Outbox.Pushed _ -> false;
            case Outbox.WallSplat _ -> false;
            case Outbox.PoseClamped _ -> false;
            case Outbox.GuardJudged _ -> false;
        };
    }

    /**
     * Whether player {@code viewer} receives {@code e}, given whether it sees the event's subject ({@code seesSubject}:
     * it tracks the subject, or, for an event that names only its strike, it got that strike's {@code AttackStarted})
     * and whether it tracks the event's party ({@code tracksParty}).
     */
    public static boolean reaches(Outbox.Event e, int viewer, boolean seesSubject, boolean tracksParty) {
        int only = privateTo(e);
        if (only != NOBODY) {
            return only == viewer;
        }
        int named = party(e);
        return seesSubject || named == viewer || (tracksParty && named != NOBODY && partyTrackers(e));
    }
}
