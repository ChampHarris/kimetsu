package dev.champ.kimetsu.core.machine;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The input buffer (Rule 3, Smash Ultimate's 3 ticks, adopted). An attack, Pommel, Step or form release pressed while
 * it can't happen yet is kept for the buffer's ticks and runs on the first tick it can, keeping its stamp. Each class
 * keeps one press, the newest. Defence drops offence: a guard press or a Step discards a queued attack, Pommel or form
 * release. Any hit that reaches you, a stagger, a grab or a knockdown empties it, so nothing swings by itself after you
 * get up. The slots are in priority order: when two become possible on the same tick, the earlier slot goes first.
 *
 * <p>In M1 a radial release that can't fire does nothing (Rule 3.7), so the form slot stays empty; it keeps the
 * spec's priority order for a style whose releases can wait.
 */
@SuppressWarnings("EnumOrdinal") // the slots index its arrays, and their declaration order is the priority order
public final class InputBuffer {
    public enum Slot {
        STEP,
        POMMEL,
        FORM,
        ATTACK
    }

    private static final int SLOTS = Slot.values().length;

    private final int keepTicks;
    private final @Nullable Input[] held = new Input[SLOTS];
    private final long[] pressedAt = new long[SLOTS];
    private final long[] minActive = new long[SLOTS];

    public InputBuffer(int keepTicks) {
        this.keepTicks = keepTicks;
    }

    /**
     * Keeps {@code input}, pressed in step {@code tick}, replacing the slot's older press. {@code minActiveTick} is the
     * earliest tick its attack may be active (Rule 2.2's back-dating limit, from the step it arrived in).
     */
    public void offer(Slot slot, Input input, long tick, long minActiveTick) {
        int i = slot.ordinal();
        held[i] = input;
        pressedAt[i] = tick;
        minActive[i] = minActiveTick;
    }

    /** A guard press or a Step: the queued attack, Pommel and form release go. */
    public void dropOffence() {
        held[Slot.POMMEL.ordinal()] = null;
        held[Slot.FORM.ordinal()] = null;
        held[Slot.ATTACK.ordinal()] = null;
    }

    public void clear() {
        Arrays.fill(held, null);
    }

    /** Drops the presses kept longer than the buffer's ticks by {@code tick}. */
    public void expire(long tick) {
        for (int i = 0; i < SLOTS; i++) {
            if (held[i] != null && tick > pressedAt[i] + keepTicks) {
                held[i] = null;
            }
        }
    }

    public @Nullable Input get(Slot slot) {
        return held[slot.ordinal()];
    }

    public long minActive(Slot slot) {
        return minActive[slot.ordinal()];
    }

    public long pressedAt(Slot slot) {
        return pressedAt[slot.ordinal()];
    }

    /** The slot's press ran (or was dropped for good). */
    public void take(Slot slot) {
        held[slot.ordinal()] = null;
    }

    public boolean isEmpty() {
        for (Input i : held) {
            if (i != null) {
                return false;
            }
        }
        return true;
    }

    /** Becomes an exact copy of {@code other} (a state snapshot). */
    public void copyFrom(InputBuffer other) {
        System.arraycopy(other.held, 0, held, 0, SLOTS);
        System.arraycopy(other.pressedAt, 0, pressedAt, 0, SLOTS);
        System.arraycopy(other.minActive, 0, minActive, 0, SLOTS);
    }

    public boolean sameAs(InputBuffer other) {
        for (int i = 0; i < SLOTS; i++) {
            if (!Objects.equals(held[i], other.held[i])) {
                return false;
            }
            if (held[i] != null && (pressedAt[i] != other.pressedAt[i] || minActive[i] != other.minActive[i])) {
                return false;
            }
        }
        return true;
    }
}
