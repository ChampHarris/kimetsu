package dev.champ.kimetsu.core.demon;

import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.HitRules;
import dev.champ.kimetsu.core.hit.Combo;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.core.meter.Hunger;
import dev.champ.kimetsu.core.meter.Posture;
import dev.champ.kimetsu.core.strike.Verdict;

/**
 * The demon's state at the end of one tick: its meters (HP, Hunger, Posture and the stake its guard holds), its timers
 * (stun, STAGGER, collapse, knockdown, the clang's interval, the Thread), and its own strikes' schedules, which say when
 * it winds up, strikes and recovers. Everything it does is a method taking the tick it happens at; {@link Demon} keeps
 * one of these per recent tick and replays from the one before a late event (as a player's Timeline does), so a hit or
 * a payment learnt late is judged as of its own tick. Copyable; allocates nothing but meter segments.
 */
final class DemonBody {
    static final long NONE = Long.MIN_VALUE;
    static final int STOP_BREAK = 0;
    static final int STOP_COLLAPSE = 1;
    static final int STOP_DEATH = 2;
    private static final int CAP = 16;
    private static final byte UNDECIDED = 0;
    private static final byte ANSWERED = 1;
    private static final byte UNANSWERED = 2;

    private final DemonProfile p;
    private final HitRules hits;
    final long maxHp;
    long hp;
    final Hunger hunger;
    final Posture posture;
    long damageTaken; // HP its wounds took this fight (vitality, Rule 4.2)
    long regenPausedUntil = NONE;
    long stake; // Posture its guard holds for its counter (Rule 11.4)
    long stunUntil = NONE;
    long collapsedAt = NONE;
    long knockdownUntil = NONE;
    long splatUntil = NONE;
    long lastClang = NONE;
    long hungerOpenUntil = NONE; // the Thread's 30 ticks after Hunger leaves 0
    boolean wasEmpty;
    long brokenAt = NONE;
    long stoppedAt = NONE;
    int stop = STOP_BREAK;
    long deadAt = NONE;
    long clashFrom = NONE; // a micro-clash, until it ends (Long.MAX_VALUE while it runs), and with whom
    long clashUntil = NONE;
    int clashFighter = -1;
    long holdStrike = NONE; // the grab holding players, and how many
    int holds;
    int blocked; // its target's blocks toward a counter
    boolean asked; // a guard counter is wanted: its brain commits it next tick
    long askBeat;
    int askFighter;
    final Combo combo = new Combo();
    int n; // its strikes, in commit order
    final long[] sId = new long[CAP];
    final long[] sCommit = new long[CAP];
    final long[] sWindupStart = new long[CAP];
    final long[] sImpact = new long[CAP];
    final long[] sRecEnd = new long[CAP];
    final long[] sCancel = new long[CAP];
    final int[] sActive = new int[CAP];
    final boolean[] sCounter = new boolean[CAP];
    final boolean[] sLeap = new boolean[CAP];
    final byte[] sAnswer = new byte[CAP];

    DemonBody(DemonProfile p, HitRules hits, long tick) {
        this.p = p;
        this.hits = hits;
        this.maxHp = Fixed.of(p.maxHp());
        this.hp = maxHp;
        this.hunger = new Hunger(p.hunger());
        this.posture = new Posture(p.posture(), tick);
    }

    void copyFrom(DemonBody o) {
        hp = o.hp;
        hunger.copyFrom(o.hunger);
        posture.copyFrom(o.posture);
        damageTaken = o.damageTaken;
        regenPausedUntil = o.regenPausedUntil;
        stake = o.stake;
        stunUntil = o.stunUntil;
        collapsedAt = o.collapsedAt;
        knockdownUntil = o.knockdownUntil;
        splatUntil = o.splatUntil;
        lastClang = o.lastClang;
        hungerOpenUntil = o.hungerOpenUntil;
        wasEmpty = o.wasEmpty;
        brokenAt = o.brokenAt;
        stoppedAt = o.stoppedAt;
        stop = o.stop;
        deadAt = o.deadAt;
        clashFrom = o.clashFrom;
        clashUntil = o.clashUntil;
        clashFighter = o.clashFighter;
        holdStrike = o.holdStrike;
        holds = o.holds;
        blocked = o.blocked;
        asked = o.asked;
        askBeat = o.askBeat;
        askFighter = o.askFighter;
        combo.copyFrom(o.combo);
        n = o.n;
        System.arraycopy(o.sId, 0, sId, 0, n);
        System.arraycopy(o.sCommit, 0, sCommit, 0, n);
        System.arraycopy(o.sWindupStart, 0, sWindupStart, 0, n);
        System.arraycopy(o.sImpact, 0, sImpact, 0, n);
        System.arraycopy(o.sRecEnd, 0, sRecEnd, 0, n);
        System.arraycopy(o.sCancel, 0, sCancel, 0, n);
        System.arraycopy(o.sActive, 0, sActive, 0, n);
        System.arraycopy(o.sCounter, 0, sCounter, 0, n);
        System.arraycopy(o.sLeap, 0, sLeap, 0, n);
        System.arraycopy(o.sAnswer, 0, sAnswer, 0, n);
    }

    /**
     * Its wounds as they stand in tick {@code now}, the tick being run (slice 5c-2): HP, Hunger, Posture and the HP its
     * wounds took, and the ticks each of its stagger, recovery delay, regeneration pause, collapse and the Threads a
     * Posture break or empty Hunger opened has left counting {@code now}. Nothing else: its strikes, holds and clash go
     * with the world it leaves, and a stun or a knockdown with them. A guard counter going with them refunds the Posture
     * its guard staked (Rule 11.4: dropped by anything but its own stop), as a restore would, which a stagger blocks.
     */
    Demon.Wounds wounds(long now) {
        long kept = posture.at(now);
        if (!posture.staggered(now)) {
            kept = Math.min(Fixed.of(p.posture().max()), kept + stake);
        }
        return new Demon.Wounds(
                hp,
                hunger.value(),
                kept,
                damageTaken,
                posture.staggerLeft(now),
                posture.recoveryDelay(now),
                left(regenPausedUntil, now),
                collapsedAt == NONE ? 0 : left(collapsedAt + p.collapse().ticks() + 1, now), // it rises at its end
                brokenAt == NONE ? 0 : left(brokenAt + p.threadTicks(), now),
                left(hungerOpenUntil, now),
                wasEmpty);
    }

    private static long left(long until, long now) {
        return until == NONE ? 0 : Math.max(0, until - now);
    }

    /**
     * Before its first tick, {@code now}: it takes over wounds {@code w} (a rebuilt world, the same demon back from an
     * unloaded chunk, or one loaded from its save). HP, Hunger and Posture are held to this profile's maxima, so new data
     * never heals it; every timer runs on for the ticks it had left, cut to the longest this profile runs it (a
     * collapse's recovery hold the longest delay), so a reloaded profile or a save edited by hand can't stretch one, and
     * a collapse or a Thread never starts later than {@code now}.
     */
    void restore(Demon.Wounds w, long now) {
        int lying = p.collapse().ticks() + 1;
        hp = Math.min(w.hp(), maxHp);
        hunger.carry(w.hunger());
        long delay = Math.min(w.recoveryDelay(), Math.max(p.posture().delayTicks(), lying));
        posture.carry(w.posture(), w.staggerLeft(), delay, now);
        damageTaken = w.damageTaken();
        posture.setDamageTaken(now, Math.min(1.0, damageTaken / (double) maxHp));
        long pause = Math.min(w.regenPauseLeft(), p.regen().pauseTicks());
        regenPausedUntil = pause > 0 ? now + pause : NONE;
        collapsedAt = w.collapseLeft() > 0 ? now + Math.min(w.collapseLeft(), lying) - lying : NONE;
        brokenAt =
                w.breakThreadLeft() > 0 ? now + Math.min(w.breakThreadLeft(), p.threadTicks()) - p.threadTicks() : NONE;
        long refilled = Math.min(w.hungerThreadLeft(), p.threadTicks());
        hungerOpenUntil = refilled > 0 ? now + refilled : NONE;
        wasEmpty = w.hungerWasEmpty();
    }

    // ------------------------------------------------------------------------------------------- what it's doing

    boolean dead(long t) {
        return deadAt != NONE && t >= deadAt;
    }

    /** Collapsed from its collapse through the tick it rises at the end of (Rule 8.4). */
    boolean collapsed(long t) {
        return collapsedAt != NONE
                && t >= collapsedAt
                && t <= collapsedAt + p.collapse().ticks();
    }

    boolean staggered(long t) {
        return posture.staggered(t);
    }

    boolean stunned(long t) {
        return stunUntil != NONE && t < stunUntil;
    }

    boolean knockedDown(long t) {
        return knockdownUntil != NONE && t < knockdownUntil;
    }

    boolean holding() {
        return holds > 0;
    }

    /** Frozen in a micro-clash at {@code t} (Rule 10.2). */
    boolean clashing(long t) {
        return clashFrom != NONE && t >= clashFrom && t < clashUntil;
    }

    /** The strike it's winding up, striking with or recovering from at {@code t}: the latest begun; −1 if none. */
    int current(long t) {
        int cur = -1;
        for (int i = 0; i < n; i++) {
            if (sWindupStart[i] <= t && t < sRecEnd[i] && t < sCancel[i]) {
                cur = i;
            }
        }
        return cur;
    }

    /** A string of its is committed and hasn't begun to wind up (Rule 11.4's free blocks): the guard counter aside. */
    boolean committed(long t) {
        for (int i = 0; i < n; i++) {
            if (!sCounter[i] && sCommit[i] <= t && t < sWindupStart[i] && t < sCancel[i]) {
                return true;
            }
        }
        return false;
    }

    /** A guard counter is wanted, or committed and still to land. */
    boolean counterPending(long t) {
        if (asked) {
            return true;
        }
        for (int i = 0; i < n; i++) {
            if (sCounter[i] && t < sImpact[i] && t < sCancel[i]) {
                return true;
            }
        }
        return false;
    }

    /** A committed strike's super armour, from its commit to its impact (Rule 3.9). */
    boolean superArmour(long t) {
        for (int i = 0; i < n; i++) {
            if (sCommit[i] <= t && t < sImpact[i] && t < sCancel[i]) {
                return true;
            }
        }
        return false;
    }

    private boolean inRecovery(int i, long t) {
        return t >= sImpact[i] + sActive[i];
    }

    /** Whether strike {@code i}'s recovery is an opening: a guard counter's only once answered (Rule 11.4). */
    private boolean opens(int i) {
        return !sCounter[i] || sAnswer[i] == ANSWERED || p.guard().counter().opens() == DemonProfile.Opens.ALWAYS;
    }

    /**
     * Its guard stance is up at {@code t} (Rule 11.4): between strings, and with a string committed but not begun; in a
     * guard counter's windup (it holds its guard until the impact); in a counter's recovery nobody answered (it
     * recovers into its guard). Never while dead, collapsed, staggered, stunned, knocked down or holding a grab.
     */
    boolean guardUp(long t) {
        if (dead(t) || collapsed(t) || staggered(t) || stunned(t) || knockedDown(t) || holding() || clashing(t)) {
            return false;
        }
        int cur = current(t);
        if (cur < 0) {
            return true;
        }
        if (sCounter[cur] && t < sImpact[cur]) {
            return true;
        }
        return inRecovery(cur, t) && !opens(cur);
    }

    /** Whether it may walk at {@code t} (the brain's movement): circling, not striking or reeling. */
    boolean canMove(long t) {
        return !dead(t)
                && !collapsed(t)
                && !staggered(t)
                && !stunned(t)
                && !knockedDown(t)
                && !holding()
                && !clashing(t)
                && current(t) < 0;
    }

    /** The tick what it's doing at {@code t} ends: its stun, or the recovery of its strike. */
    private long busyEnd(long t) {
        long end = t;
        if (stunUntil != NONE) {
            end = Math.max(end, stunUntil);
        }
        int cur = current(t);
        if (cur >= 0) {
            end = Math.max(end, sRecEnd[cur]);
        }
        return end;
    }

    /**
     * The tick its Thread closes, if it's open at {@code t} (Rule 8.3): 30 ticks after a Posture break, the tick after a
     * collapse rises, {@code Long.MAX_VALUE} while Hunger is 0, 30 ticks after it leaves 0; {@code Long.MIN_VALUE} if
     * closed.
     */
    long threadUntil(long t) {
        if (dead(t)) {
            return NONE;
        }
        long until = NONE;
        if (brokenAt != NONE && t >= brokenAt && t < brokenAt + p.threadTicks()) {
            until = brokenAt + p.threadTicks();
        }
        if (collapsed(t)) {
            until = Math.max(until, collapsedAt + p.collapse().ticks() + 1);
        }
        if (hunger.value() == 0) {
            return Long.MAX_VALUE;
        }
        if (hungerOpenUntil != NONE && t < hungerOpenUntil) {
            until = Math.max(until, hungerOpenUntil);
        }
        return until;
    }

    boolean threadOpen(long t) {
        return threadUntil(t) != NONE;
    }

    /** What it's doing (Rule 3's states); clients are sent a coarser view ({@code Demon}'s public state). */
    State state(long t) {
        if (dead(t)) {
            return State.DEAD;
        }
        if (collapsed(t)) {
            return State.COLLAPSED;
        }
        if (staggered(t)) {
            return State.STAGGER;
        }
        if (clashing(t)) {
            return State.CLASH;
        }
        if (knockedDown(t)) {
            return State.KNOCKDOWN;
        }
        if (stunned(t)) {
            return State.HITSTUN;
        }
        if (holding()) {
            return State.ATTACK;
        }
        if (guardUp(t)) {
            return State.GUARD;
        }
        return current(t) >= 0 ? State.ATTACK : State.NEUTRAL;
    }

    /**
     * Regenerating at {@code t}: below full, not collapsed or dead, not paused, with Hunger to pay for some of an HP
     * (Rule 8.5).
     */
    boolean regenerating(long t) {
        return hp < maxHp
                && hunger.value() >= Math.max(1, p.hunger().perHp())
                && !dead(t)
                && !collapsed(t)
                && (regenPausedUntil == NONE || t >= regenPausedUntil)
                && p.regen().hpPerS() > 0;
    }

    int find(long strike) {
        for (int i = 0; i < n; i++) {
            if (sId[i] == strike) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------------------------- what happens

    /** A strike of its committed at {@code t} (its schedule, Rule 7.11). */
    void scheduled(
            long t,
            long strike,
            long windupStart,
            int windupTicks,
            long impact,
            int active,
            int recovery,
            boolean counter,
            boolean leap) {
        if (n == CAP) {
            prune(t);
        }
        if (n == CAP) {
            throw new IllegalStateException("demon has more than " + CAP + " strikes under way");
        }
        int i = n++;
        sId[i] = strike;
        sCommit[i] = t;
        sWindupStart[i] = windupStart;
        sImpact[i] = impact;
        sActive[i] = active;
        sRecEnd[i] = windupStart + windupTicks + active + recovery;
        sCancel[i] = Long.MAX_VALUE;
        sCounter[i] = counter;
        sLeap[i] = leap;
        sAnswer[i] = UNDECIDED;
        if (counter) {
            asked = false;
            blocked = 0;
        }
    }

    /** Forgets strikes over before {@code t}. */
    void prune(long t) {
        int keep = 0;
        for (int i = 0; i < n; i++) {
            if (Math.min(sRecEnd[i], sCancel[i]) >= t || (sCounter[i] && sAnswer[i] == UNDECIDED)) {
                move(i, keep++);
            }
        }
        n = keep;
    }

    private void move(int from, int to) {
        if (from == to) {
            return;
        }
        sId[to] = sId[from];
        sCommit[to] = sCommit[from];
        sWindupStart[to] = sWindupStart[from];
        sImpact[to] = sImpact[from];
        sRecEnd[to] = sRecEnd[from];
        sCancel[to] = sCancel[from];
        sActive[to] = sActive[from];
        sCounter[to] = sCounter[from];
        sLeap[to] = sLeap[from];
        sAnswer[to] = sAnswer[from];
    }

    /**
     * It pays, as of its strike's impact {@code t}, what its best answer so far costs beyond what it paid: Posture, and
     * ticks on its recovery (D-039, D-045). A deflect or a Perfect on a guard counter keeps the stake and opens the
     * counter's recovery (Rule 11.4). Nothing moves a collapsed demon (D-036).
     */
    void paid(long t, long strike, Verdict.Kind best, long postureCost, int recoveryTicks) {
        int i = find(strike);
        boolean answered = best == Verdict.Kind.PERFECT || best == Verdict.Kind.DEFLECT;
        if (i >= 0 && sCounter[i] && sAnswer[i] == UNDECIDED && answered) {
            sAnswer[i] = ANSWERED;
            stake = 0; // kept: the Posture is lost
        }
        if (postureCost > 0 && !collapsed(t) && !dead(t) && posture.damage(t, postureCost, true)) {
            onBreak(t);
        }
        if (i >= 0 && recoveryTicks > 0) {
            sRecEnd[i] += recoveryTicks;
        }
    }

    /** Its strike is decided for everyone: a guard counter nobody deflected refunds its stake (Rule 11.4). */
    void decided(long t, long strike) {
        int i = find(strike);
        if (i >= 0 && sCounter[i] && sAnswer[i] == UNDECIDED) {
            sAnswer[i] = UNANSWERED;
            refund(t);
        }
    }

    /**
     * Its strike won't land from {@code t} on. A guard counter dropped by anything but its own stop refunds its stake;
     * {@code kept} says which, decided when the cancel was learnt.
     */
    void cancelled(long t, long strike, boolean kept) {
        int i = find(strike);
        if (i < 0) {
            return;
        }
        sCancel[i] = Math.min(sCancel[i], t);
        if (sCounter[i] && sAnswer[i] == UNDECIDED) {
            sAnswer[i] = UNANSWERED;
            if (kept) {
                stake = 0;
            } else {
                refund(t);
            }
        }
    }

    /** A Pommel Strike interrupted its strike at {@code t}: cancelled, and stunned from then (Rule 5.2). */
    void interrupted(long t, long strike, int stunTicks) {
        int i = find(strike);
        if (i >= 0) {
            sCancel[i] = Math.min(sCancel[i], t);
        }
        stun(t + stunTicks);
    }

    /** The guard counter it asked for couldn't be scheduled: the blocks it staked are refunded. */
    void counterRefused(long t) {
        refund(t);
        asked = false;
        blocked = 0;
    }

    /** Frozen in a micro-clash from {@code t} (Rule 10.2): it strikes nothing more, and its guard is down. */
    void clashed(long t, int fighter) {
        clashFrom = t;
        clashFighter = fighter;
        clashUntil = Long.MAX_VALUE;
        stunUntil = NONE;
    }

    /**
     * The clash ended at {@code t}; {@code lost}, it loses {@code shareFixed} of its max Posture then (D-050: half; all of
     * it breaks it, as Rule 10.3 was written).
     */
    void clashEnded(long t, boolean lost, long shareFixed) {
        clashUntil = t;
        if (!lost || shareFixed <= 0 || dead(t) || collapsed(t)) {
            return;
        }
        long amount = Math.round(Fixed.of(p.posture().max()) * (double) shareFixed / Fixed.ONE);
        if (posture.damage(t, amount, true)) {
            onBreak(t);
        }
    }

    /** Its grab connected on one more player (Rule 6.3). */
    void holding(long strike) {
        if (holdStrike != strike) {
            holdStrike = strike;
            holds = 0;
        }
        holds++;
    }

    /**
     * A hold of its grab ended at {@code t} (Rule 6.3): once it holds nobody, its recovery ends there; a player who broke
     * free, or was freed, leaves it reeling (an opening), and a throw leaves it straight in its guard.
     */
    void released(long t, long strike, boolean reels) {
        if (holdStrike == strike && holds > 0 && --holds == 0) {
            holdStrike = NONE;
            int i = find(strike);
            if (i >= 0) {
                sRecEnd[i] = Math.min(sRecEnd[i], Math.max(t, sImpact[i]));
            }
        }
        if (reels) {
            stun(t + p.escapeRecoveryTicks());
        }
    }

    private void stun(long until) {
        stunUntil = stunUntil == NONE ? until : Math.max(stunUntil, until);
    }

    private void refund(long t) {
        if (stake > 0) {
            posture.restore(t, stake);
            stake = 0;
        }
    }

    /**
     * A block by its guard stance at {@code t} costing {@code cost} Posture; with {@code staked}, held as its counter's
     * stake and never taking it below 1; with {@code asks}, one more of its target's blocks toward a counter landing two
     * beats after {@code pressBeat} (Rule 11.4). A replay can find it no longer guarding (a break learnt late): then the
     * block costs and asks nothing.
     */
    void block(long t, long cost, boolean staked, boolean asks, long pressBeat, int fighter) {
        if (dead(t) || collapsed(t) || staggered(t)) {
            return;
        }
        boolean restart = p.guard().blocksRestartRecovery();
        if (staked) {
            long amount = Math.max(0, Math.min(cost, posture.at(t) - Fixed.ONE));
            if (amount > 0) {
                posture.damage(t, amount, restart);
                stake += amount;
            }
        } else if (cost > 0 && posture.damage(t, cost, restart)) {
            onBreak(t);
            return;
        }
        if (asks && ++blocked >= p.guard().counter().afterBlocks()) {
            asked = true;
            askBeat = pressBeat + 2;
            askFighter = fighter;
        }
    }

    /**
     * A hit reached its body at {@code t}: HP and Posture lost, the combo counted, and unless it lands into super armour,
     * a stun for the hit's hitstun (the longer of it and the recovery left, Rule 7.5); with {@code splats}, a push that
     * can splat it on a wall (Rule 9.4).
     */
    void hitBody(long t, long damage, long postureDamage, int hitstun, boolean armoured, boolean splats) {
        if (dead(t)) {
            return;
        }
        wound(t, damage, true);
        if (postureDamage > 0 && !collapsed(t) && !dead(t) && posture.damage(t, postureDamage, true)) {
            onBreak(t);
        }
        int place = combo.land(t, hits);
        if (!armoured && hitstun > 0 && !staggered(t) && !collapsed(t) && !knockedDown(t) && !clashing(t) && !dead(t)) {
            stun(t + Combo.hitstun(hitstun, place, hits));
        }
        if (splats && !armoured && !collapsed(t)) {
            splatUntil = t + p.knockdown().splatWindowTicks();
        }
    }

    /**
     * A cut across its hardened neck clanged at {@code t} (Rule 8.2): the extra damage a crit multiplier adds, and a
     * recoil added to what it's doing, unless a committed strike's super armour holds it.
     */
    void clang(long t, long extraDamage) {
        if (extraDamage > 0) {
            wound(t, extraDamage, true);
        }
        lastClang = t;
        if (!superArmour(t) && !staggered(t) && !collapsed(t) && !dead(t)) {
            stunUntil = busyEnd(t) + p.clang().recoilTicks();
        }
    }

    /** A Nichirin decapitation at {@code t} (Rule 8.1): the only death. */
    void die(long t) {
        if (deadAt == NONE || t < deadAt) {
            deadAt = t;
        }
        stopAt(t, STOP_DEATH);
        holdStrike = NONE;
        holds = 0;
        asked = false;
        blocked = 0;
    }

    /** Damage from outside the mod (Rule 8.6): never below 25% HP, never a collapse; {@code killing} (the void) kills. */
    void outside(long t, long amount, boolean killing) {
        if (killing) {
            die(t);
            return;
        }
        if (dead(t) || collapsed(t)) {
            return;
        }
        long floor = Math.round(maxHp * p.outsideFloor());
        if (hp > floor) {
            hp = Math.max(floor, hp - amount);
        }
    }

    /** It met a wall at {@code t}: within a splatting push's window, a wall splat (Rule 9.4). */
    void wall(long t) {
        if (splatUntil == NONE || t >= splatUntil || dead(t) || collapsed(t)) {
            return;
        }
        splatUntil = NONE;
        wound(t, Fixed.of(p.knockdown().splatDamage()), false);
        if (!dead(t) && !collapsed(t)) {
            knockdownUntil = t + p.knockdown().downTicks() + p.knockdown().riseTicks();
            stunUntil = NONE;
        }
    }

    /**
     * HP lost to a mod wound at {@code t} ({@code nichirin}: a Nichirin cut): lethal damage collapses it (Rule 8.4), and
     * a collapsed one loses nothing. A wound of the kind the profile names pauses regeneration.
     */
    private void wound(long t, long amount, boolean nichirin) {
        if (amount <= 0 || dead(t) || collapsed(t)) {
            return;
        }
        long before = hp;
        hp -= amount;
        if (hp <= 0) {
            hp = Fixed.of(p.collapse().floorHp());
            collapse(t);
        }
        damageTaken += before - hp;
        posture.setDamageTaken(t, Math.min(1.0, damageTaken / (double) maxHp));
        int pause = p.regen().pauseTicks();
        if (pause > 0 && (nichirin || p.regen().pauseOn() == DemonProfile.PauseOn.ANY)) {
            regenPausedUntil = regenPausedUntil == NONE ? t + pause : Math.max(regenPausedUntil, t + pause);
        }
    }

    private void collapse(long t) {
        collapsedAt = t;
        stopAt(t, STOP_COLLAPSE);
        stunUntil = NONE;
        knockdownUntil = NONE;
        holdStrike = NONE;
        holds = 0;
        asked = false;
        blocked = 0;
        posture.holdRecovery(t, t + p.collapse().ticks() + 1);
    }

    private void onBreak(long t) {
        brokenAt = t;
        stopAt(t, STOP_BREAK);
        stunUntil = NONE;
        holdStrike = NONE;
        holds = 0;
        if (counterPending(t)) {
            stake = 0; // a Posture break cancels the counter and keeps the stake (Rule 11.4)
        }
        asked = false;
        blocked = 0;
    }

    private void stopAt(long t, int why) {
        stoppedAt = t;
        stop = why;
    }

    /**
     * The tick's own processes, after its events (Rule 3's step 7): Posture recovers or resets, a collapse rises at the
     * end of its last tick, a leap winding up pays its Hunger, regeneration heals and pays, and the Thread follows Hunger
     * to 0.
     */
    void advance(long u) {
        posture.tick(u);
        if (collapsedAt != NONE && u == collapsedAt + p.collapse().ticks() && !dead(u)) {
            rise();
        }
        for (int i = 0; i < n; i++) {
            if (sLeap[i] && sWindupStart[i] == u && u < sCancel[i]) {
                hunger.payLeap();
            }
        }
        if (regenerating(u)) {
            long heal = Math.min(Fixed.perTick(p.regen().hpPerS()), maxHp - hp);
            hp += hunger.payForHp(heal);
        }
        boolean empty = hunger.value() == 0;
        if (wasEmpty && !empty) {
            hungerOpenUntil = u + p.threadTicks();
        }
        wasEmpty = empty;
    }

    /** It rises from its collapse at 25% HP paid in Hunger, or with what Hunger pays, at least 1 HP (Rule 8.4). */
    private void rise() {
        long cost = Fixed.of(p.hunger().riseCost());
        long paid = hunger.payRise();
        long riseHp = Math.round(maxHp * p.collapse().riseFraction());
        long risen = cost == 0 ? riseHp : riseHp * paid / cost;
        hp = Math.min(maxHp, Math.max(Fixed.ONE, risen));
    }
}
