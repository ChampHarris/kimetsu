package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.data.FightRules;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Rule 13.1's fights, each combatant's own (D-068): a fight opens at its first combat trigger and ends after {@code
 * fights.end_ticks} with none, at its combatant's defeat, or once every foe it fought in it is defeated, so a Stray
 * killed mid-boss ends no boss fight. Fed an arena's events: each trigger links its two ends, opening or refreshing
 * both fights, and names each the other's foe. The triggers are a strike committed on a player or one whose sweep can
 * reach it (Rule 2.9), its result, a swing that landed or was blocked, a grab, a throw that deals damage and a clash; a
 * mob's death defeats it, and the mod reports a player's ({@link #defeated}). A fight's time runs from the step the
 * server learnt each trigger, which is at most a hold cap after the machine's own tick: the 15 s flag stays the
 * machine's ({@code Combatant.combatUntil}). Its only reader in M1 is the fight log. Kept by the mod across a world's
 * rebuild, since a {@code /reload} doesn't end a fight; a re-anchor's new tick numbering {@linkplain #rebase rebases}
 * it. Deterministic; a step with no trigger allocates nothing.
 */
public final class Fights {
    /** Why a fight ended (D-068). */
    public enum End {
        /** {@code fights.end_ticks} with no trigger. */
        QUIET,
        /** Its combatant was defeated. */
        DEFEATED,
        /** Every foe it fought in it was defeated. */
        FOES_DEFEATED
    }

    /** What sees fights start and end. */
    public interface Listener {
        void started(Fight f, long step);

        /** {@code f} ended at {@code step}, {@code why}; it's read only until this returns. */
        void ended(Fight f, long step, End why);
    }

    /** One combatant's fight: when it opened, its last trigger and the foes it has fought in it. */
    public static final class Fight {
        private final int id;
        private long from;
        private long last;
        private int[] foes = new int[4];
        private boolean[] down = new boolean[4];
        private int count;

        Fight(int id, long from) {
            this.id = id;
            this.from = from;
            this.last = from;
        }

        /** The combatant whose fight it is (its engine id). */
        public int id() {
            return id;
        }

        /** The step it opened in. */
        public long from() {
            return from;
        }

        /** The step of its last trigger. */
        public long last() {
            return last;
        }

        public int foeCount() {
            return count;
        }

        /** Its {@code i}th foe, in the order it first met them. */
        public int foe(int i) {
            return foes[i];
        }

        /** Whether its {@code i}th foe is defeated. */
        public boolean down(int i) {
            return down[i];
        }

        /** A trigger at {@code step} against {@code foe}, who is up again if it was down. */
        void met(int foe, long step) {
            last = Math.max(last, step);
            for (int i = 0; i < count; i++) {
                if (foes[i] == foe) {
                    down[i] = false;
                    return;
                }
            }
            if (count == foes.length) {
                foes = Arrays.copyOf(foes, count * 2);
                down = Arrays.copyOf(down, count * 2);
            }
            foes[count] = foe;
            down[count] = false;
            count++;
        }

        /** {@code foe} was defeated: whether every foe it fought is now down. */
        boolean beaten(int foe) {
            boolean met = false;
            boolean all = true;
            for (int i = 0; i < count; i++) {
                if (foes[i] == foe) {
                    down[i] = true;
                    met = true;
                }
                all &= down[i];
            }
            return met && all;
        }
    }

    private static final int NONE = Integer.MIN_VALUE;

    private final Listener listener;
    private final List<Fight> open = new ArrayList<>(); // in the order they opened
    private long endTicks;
    // the strikes committed and not yet past their impact, so an Involved names who struck
    private long[] strike = new long[8];
    private int[] attacker = new int[8];
    private long[] impact = new long[8];
    private int strikes;

    public Fights(FightRules rules, Listener listener) {
        this.endTicks = rules.endTicks();
        this.listener = listener;
    }

    /** New data: fights end after its {@code end_ticks} from now on. */
    public void rules(FightRules rules) {
        endTicks = rules.endTicks();
    }

    /** Step {@code step}'s event {@code e}, in the order the world sent them. */
    public void accept(Outbox.Event e, long step) {
        switch (e) {
            case Outbox.AttackStarted a -> {
                remember(a.strike(), a.attacker(), a.impactTick());
                link(a.attacker(), a.target(), step);
            }
            case Outbox.Involved i -> {
                int by = attackerOf(i.strike());
                if (by != NONE) {
                    link(by, i.fighter(), step);
                }
            }
            case Outbox.AttackCancelled c -> forget(c.strike());
            case Outbox.StrikeResult r -> link(r.attacker(), r.fighter(), step);
            case Outbox.SwingLanded s -> link(s.fighter(), s.mob(), step);
            case Outbox.HoldStarted h -> link(h.mob(), h.fighter(), step);
            case Outbox.HoldEnded h -> {
                if (h.damage() > 0) {
                    link(h.mob(), h.fighter(), step); // a throw deals damage
                }
            }
            case Outbox.ClashStarted c -> link(c.mob(), c.fighter(), step);
            case Outbox.MobDied d -> defeated(d.mob(), step);
            // not triggers: what a mob shows (a target alone isn't one, Rule 13.1), a lock, a swing's damage (its
            // landing is), what the machine logged, a mob leaving alive (its foes' fights wait out their time), a clamp
            case Outbox.StrikeLocked _,
                    Outbox.PlayerEffect _,
                    Outbox.SwingDealt _,
                    Outbox.ClashEnded _,
                    Outbox.MobLeft _,
                    Outbox.MobState _,
                    Outbox.GuardYaw _,
                    Outbox.Targeting _,
                    Outbox.Clang _,
                    Outbox.Pushed _,
                    Outbox.PoseClamped _ -> {}
        }
    }

    /**
     * Combatant {@code id} was defeated at {@code step} (a player's death; a mob's comes as {@link Outbox.MobDied}): its
     * own fight ends, and so does every fight whose foes are now all down.
     */
    public void defeated(int id, long step) {
        Fight own = fight(id);
        if (own != null) {
            end(own, step, End.DEFEATED);
        }
        for (int i = open.size() - 1; i >= 0; i--) {
            Fight f = open.get(i);
            if (f.beaten(id)) {
                end(f, step, End.FOES_DEFEATED);
            }
        }
    }

    /** After step {@code n}'s events: the fights with no trigger for {@code end_ticks} end. */
    public void endStep(long n) {
        for (int i = open.size() - 1; i >= 0; i--) {
            Fight f = open.get(i);
            if (n - f.last >= endTicks) {
                end(f, n, End.QUIET);
            }
        }
        int kept = 0;
        for (int i = 0; i < strikes; i++) {
            if (impact[i] > n) { // an Involved comes at the latest in its impact's step
                strike[kept] = strike[i];
                attacker[kept] = attacker[i];
                impact[kept] = impact[i];
                kept++;
            }
        }
        strikes = kept;
    }

    /** Game time re-anchored: what was at tick t is at t + {@code shift} on the new numbering. */
    public void rebase(long shift) {
        for (int i = 0; i < open.size(); i++) {
            Fight f = open.get(i);
            f.from += shift;
            f.last += shift;
        }
        for (int i = 0; i < strikes; i++) {
            impact[i] += shift;
        }
    }

    /** Combatant {@code id}'s open fight, or null. */
    public @Nullable Fight fight(int id) {
        for (int i = 0; i < open.size(); i++) {
            if (open.get(i).id == id) {
                return open.get(i);
            }
        }
        return null;
    }

    /** The fights open now, in the order they opened. Read only. */
    public List<Fight> open() {
        return open;
    }

    private void link(int a, int b, long step) {
        if (a == b) {
            return;
        }
        opened(a, step).met(b, step);
        opened(b, step).met(a, step);
    }

    private Fight opened(int id, long step) {
        Fight f = fight(id);
        if (f == null) {
            f = new Fight(id, step);
            open.add(f);
            listener.started(f, step);
        }
        return f;
    }

    private void end(Fight f, long step, End why) {
        open.remove(f);
        listener.ended(f, step, why);
    }

    private void remember(long id, int by, long impactTick) {
        forget(id); // a repeated id replaces the earlier entry
        if (strikes == strike.length) {
            strike = Arrays.copyOf(strike, strikes * 2);
            attacker = Arrays.copyOf(attacker, strikes * 2);
            impact = Arrays.copyOf(impact, strikes * 2);
        }
        strike[strikes] = id;
        attacker[strikes] = by;
        impact[strikes] = impactTick;
        strikes++;
    }

    private void forget(long id) {
        for (int i = 0; i < strikes; i++) {
            if (strike[i] == id) {
                strikes--;
                strike[i] = strike[strikes];
                attacker[i] = attacker[strikes];
                impact[i] = impact[strikes];
                return;
            }
        }
    }

    private int attackerOf(long id) {
        for (int i = 0; i < strikes; i++) {
            if (strike[i] == id) {
                return attacker[i];
            }
        }
        return NONE;
    }
}
