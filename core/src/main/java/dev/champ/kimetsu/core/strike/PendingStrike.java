package dev.champ.kimetsu.core.strike;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.hit.Pose;
import java.util.Arrays;

/**
 * One committed NPC strike (Rules 7.11–7.13): its schedule, fixed at the commit; its attacker's pose when its cue
 * fired, from which its own displacement carries it ({@link StrikePath}); the players it involves, in the order they
 * joined (its target from the commit, others from the cue through the impact, Rule 2.9), and which of them its verdict
 * has been decided for; the best answer so far, which its attacker pays once every player is decided (D-039); and its
 * string, so an interrupt or a Posture break can cancel the strikes still to come.
 */
public final class PendingStrike {
    public enum Status {
        PENDING,
        DECIDED,
        CANCELLED
    }

    private final long id;
    private final int attacker;
    private final int target;
    private final long string;
    private final boolean last;
    private final Scheduler.Slot slot;
    private Status status = Status.PENDING;
    private boolean locked;
    private double lockX;
    private double lockY;
    private double lockZ;
    private double lockedYaw;
    private int[] involved = new int[4];
    private boolean[] decided = new boolean[4];
    private int count;
    private int decidedCount;
    private int bestRank = -1;
    private double bestPosture;
    private int bestRecovery;

    public PendingStrike(long id, int attacker, int target, long string, boolean last, Scheduler.Slot slot) {
        this.id = id;
        this.attacker = attacker;
        this.target = target;
        this.string = string;
        this.last = last;
        this.slot = slot;
        join(target);
    }

    public long id() {
        return id;
    }

    /** The striking mob's id. */
    public int attacker() {
        return attacker;
    }

    /** The player it's beat-locked to. */
    public int target() {
        return target;
    }

    public long string() {
        return string;
    }

    /** Whether it's its string's last strike (a Perfect on it lengthens the attacker's recovery, Rule 11.3). */
    public boolean last() {
        return last;
    }

    public FrameData attack() {
        return slot.attack();
    }

    public Scheduler.Slot slot() {
        return slot;
    }

    public long impactTick() {
        return slot.impactTick();
    }

    public long impactMicros() {
        return Micros.ofTicks(slot.impactTick());
    }

    public long cueTick() {
        return slot.cueTick();
    }

    public long cueMicros() {
        return Micros.ofTicks(slot.cueTick());
    }

    /** Its target's beat it lands on (the Pulse's global index), which the per-beat cap counts. */
    public long beat() {
        return slot.beat();
    }

    public boolean white() {
        return slot.attack().cue().type() == FrameData.Cue.Type.WHITE;
    }

    public Status status() {
        return status;
    }

    public boolean locked() {
        return locked;
    }

    /** The attacker's yaw when the cue fired: the strike's direction from then (Rule 7.13). */
    public double lockedYaw() {
        return lockedYaw;
    }

    /** The attacker's feet when the cue fired. */
    public double lockX() {
        return lockX;
    }

    public double lockY() {
        return lockY;
    }

    public double lockZ() {
        return lockZ;
    }

    /** Its cue fired with the attacker's feet at {@code (x, y, z)}, facing {@code yaw}: its direction is locked. */
    public void lock(double x, double y, double z, double yaw) {
        locked = true;
        lockX = x;
        lockY = y;
        lockZ = z;
        lockedYaw = yaw;
    }

    /** Where the strike carries its attacker by the end of tick {@code tick} (it must be locked). */
    public void attackerAt(long tick, Pose out) {
        StrikePath.at(
                slot.attack(),
                slot.windupStartTick(),
                slot.windupTicks(),
                slot.cueTick(),
                lockX,
                lockY,
                lockZ,
                lockedYaw,
                tick,
                out);
    }

    /** Every involved player's verdict is decided and its attacker has paid: it's over. */
    public void decide() {
        status = Status.DECIDED;
    }

    /** Whether the {@code i}th involved player's verdict is decided. */
    public boolean decidedFor(int i) {
        return decided[i];
    }

    /** The {@code i}th involved player's verdict is decided. */
    public void decideFor(int i) {
        if (!decided[i]) {
            decided[i] = true;
            decidedCount++;
        }
    }

    /** Whether every involved player's verdict is decided. */
    public boolean allDecided() {
        return decidedCount == count;
    }

    /** How many involved players' verdicts are decided. */
    public int decidedCount() {
        return decidedCount;
    }

    /** Whether {@code player} is involved and its verdict still to be decided. */
    public boolean awaits(int player) {
        for (int i = 0; i < count; i++) {
            if (involved[i] == player) {
                return !decided[i];
            }
        }
        return false;
    }

    /**
     * One player's verdict {@code kind}, which costs the attacker {@code posture} and {@code recoveryTicks}: the best
     * answer so far is what it pays (D-039), whole, a Perfect over a deflect over anything else, and between two of a
     * kind the dearer (two Perfects at different Rhythm).
     */
    public void answer(Verdict.Kind kind, double posture, int recoveryTicks) {
        int rank = kind == Verdict.Kind.PERFECT ? 2 : kind == Verdict.Kind.DEFLECT ? 1 : 0;
        if (rank > bestRank || (rank == bestRank && posture > bestPosture)) {
            bestRank = rank;
            bestPosture = posture;
            bestRecovery = recoveryTicks;
        }
    }

    /** The Posture the best answer so far costs the attacker. */
    public double bestPosture() {
        return bestPosture;
    }

    /** The ticks the best answer so far adds to the attacker's recovery. */
    public int bestRecovery() {
        return bestRecovery;
    }

    public void cancel() {
        status = Status.CANCELLED;
    }

    /** Adds a player (it joins in the order it's added); returns false if it's already involved. */
    public boolean join(int player) {
        if (involves(player)) {
            return false;
        }
        if (count == involved.length) {
            involved = Arrays.copyOf(involved, count * 2);
            decided = Arrays.copyOf(decided, count * 2);
        }
        decided[count] = false;
        involved[count++] = player;
        return true;
    }

    /** A player left the fight (death, logout): it's no longer involved. */
    public void leave(int player) {
        for (int i = 0; i < count; i++) {
            if (involved[i] == player) {
                if (decided[i]) {
                    decidedCount--;
                }
                System.arraycopy(involved, i + 1, involved, i, count - i - 1);
                System.arraycopy(decided, i + 1, decided, i, count - i - 1);
                count--;
                return;
            }
        }
    }

    public boolean involves(int player) {
        for (int i = 0; i < count; i++) {
            if (involved[i] == player) {
                return true;
            }
        }
        return false;
    }

    public int involvedCount() {
        return count;
    }

    /** The {@code i}th involved player, in join order (the target first). */
    public int involved(int i) {
        return involved[i];
    }
}
