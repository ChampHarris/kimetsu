package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.strike.PendingStrike;
import dev.champ.kimetsu.core.strike.Verdict;
import org.jspecify.annotations.Nullable;

/**
 * A mob that strikes players, as the fight loop sees its body: where it stands, how big it is, and what the strikes'
 * answers and the players' swings do to it. The demon ({@code demon.Demon}) runs its own state machine behind this;
 * tests also use a box dummy that records what happened to it. Every call names the tick it takes effect at, which can
 * be earlier than the tick being run (a late sweep, a payment decided after its impact): the body judges it as of that
 * tick.
 */
public interface StrikeMob {
    int id();

    double halfWidth();

    double height();

    double eyeHeight();

    double maxPosture();

    /** It joined {@code world}, whose outbox carries what it tells clients. */
    default void joined(CombatWorld world) {}

    /** Step 1 of tick {@code tick}, before {@link #pose}: the tick begins (the demon turns its guard). */
    default void beginTick(long tick) {}

    /**
     * Where it stood at tick {@code tick} (step 1 of that tick: after the level moved it): its feet and look, into
     * {@code out}. From a strike's cue it moves only by the strike's own displacement (Rule 7.13), so while
     * {@link CombatWorld#strikePose} has a pose for it, that's where it is: the strike's sweep, a Pommel's target and the
     * box players' swings hit must agree, on the server and on every client.
     */
    void pose(long tick, Pose out);

    /** Its neck box in its own frame (x right, y up, z forward, from the feet) at tick {@code tick}, or null. */
    default Sweep.@Nullable Box neck(long tick) {
        return null;
    }

    /** How far from where its target stood at the cue a lunge of its stops (D-042); 0: it doesn't stop short. */
    default double lungeStandoff() {
        return 0;
    }

    /** Step 2: a strike of its was committed, its schedule fixed ({@link PendingStrike#slot}). */
    default void scheduled(PendingStrike strike) {}

    /** A player's swing touched it: what it did, decided once, from its state at the swing's tick. */
    HitResult hit(SwingHit hit);

    /** Whether a hit its guard blocks scores the hitter's beat credit (Rule 11.4: no). */
    default boolean blocksScore() {
        return false;
    }

    /**
     * Its strike {@code strike}'s best answer so far improved to {@code best}, as of its impact, tick {@code tick}: it
     * pays {@code posture} more and adds {@code recoveryTicks} more to its recovery (D-039, D-045). Paid as each player
     * is decided, so a Perfect's break doesn't wait for a slower ally; never more in all than the best answer, or than an
     * answer already paid.
     */
    void paid(long strike, long tick, Verdict.Kind best, double posture, int recoveryTicks);

    /** Its strike {@code strike} is decided for every player it involved: {@code best} was its best answer. */
    default void decided(long strike, long tick, Verdict.Kind best) {}

    /**
     * A Pommel Strike interrupted its strike at {@code atMicros}: the strike won't land, and it's stunned for
     * {@code stunTicks} from then (Rule 5.2). The rest of the string is {@linkplain #cancelled cancelled}.
     */
    void interrupted(long strike, long atMicros, int stunTicks);

    /**
     * Its strike won't land from tick {@code tick} on: a feint's break, the rest of an interrupted string, a stop (its
     * own {@link #stoppedAt}), a stall, its target leaving.
     */
    void cancelled(long strike, long tick);

    /**
     * It's frozen in a micro-clash with player {@code fighter} from tick {@code tick} (Rule 10.2): its strikes to come
     * are cancelled, its guard is down, hits on it don't stun it, and that player's own hits come to nothing (its form
     * met the heavy, not the body) until {@link #clashEnded}.
     */
    default void clashed(long tick, int fighter) {}

    /**
     * The clash ended at tick {@code tick}: {@code lost}, it loses {@code postureShare} of its max Posture then (D-050:
     * half; 1.0 breaks it, as Rule 10.3 was written).
     */
    default void clashEnded(long tick, boolean lost, double postureShare) {}

    /** Its press in a clash, this long after the flash (Rule 10.4: drawn per clash, from its profile). */
    long clashReactionMicros();

    /** Which of {@code choices} half-beats the flash falls on, 0 for the first (Rule 10.2: at random). */
    default int clashFlash(int choices) {
        return 0;
    }

    /** Its grab {@code strike} connected at tick {@code tick}: it holds a player (Rule 6.3). */
    default void holding(long strike, long tick) {}

    /** The hold of its grab {@code strike} ended at tick {@code tick}, {@code how}. */
    default void released(long strike, long tick, Release how) {}

    /**
     * The tick it last stopped striking (a Posture break's STAGGER; for the demon also a collapse or its death), or
     * {@code Long.MIN_VALUE}: its strikes landing after that tick are cancelled, not those already landed (Rule 7.8's
     * trades; a late hit's break keeps the tick it happened at).
     */
    long stoppedAt();

    /** Why it stopped at {@link #stoppedAt}, for {@code AttackCancelled}. */
    default String stopReason() {
        return "posture break";
    }

    /** It's dead (the demon: decapitated): the loop drops it after the tick. */
    default boolean dead() {
        return false;
    }

    /** The tick is over (step 7). */
    void endTick(long tick);
}
