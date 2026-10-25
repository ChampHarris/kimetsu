package dev.champ.kimetsu.client;

import java.util.Arrays;

/**
 * Rule 9.1's Step input (the spec's key table, D-070), kept free of game state so its cases are unit-tested: the Step
 * key (Mouse 5) in any direction, or a second tap of vanilla's left, back or right key {@code machine.double_tap_ticks}
 * client ticks or fewer after the first, counted in client ticks as vanilla counts its sprint window. Forward stays
 * vanilla's sprint. The Step's direction is the movement keys held at the press, in 8; with none held it's a backstep
 * (D-060). The wire's Step press carries no direction: a dodge is judged from the poses (Rule 2.5).
 */
final class StepInput {
    /** Vanilla's four movement keys. */
    enum Key {
        FORWARD,
        LEFT,
        BACK,
        RIGHT
    }

    /** A Step's direction against the look, as vanilla's move vector has it: strafe (left positive) and forward. */
    enum Direction {
        FORWARD(0, 1),
        FORWARD_LEFT(DIAGONAL, DIAGONAL),
        LEFT(1, 0),
        BACK_LEFT(DIAGONAL, -DIAGONAL),
        BACK(0, -1),
        BACK_RIGHT(-DIAGONAL, -DIAGONAL),
        RIGHT(-1, 0),
        FORWARD_RIGHT(-DIAGONAL, DIAGONAL);

        final double strafe;
        final double forward;

        Direction(double strafe, double forward) {
            this.strafe = strafe;
            this.forward = forward;
        }

        /** The direction of the keys held; opposite keys cancel, and with nothing left it's a backstep. */
        static Direction held(boolean forward, boolean back, boolean left, boolean right) {
            int f = (forward ? 1 : 0) - (back ? 1 : 0);
            int s = (left ? 1 : 0) - (right ? 1 : 0);
            return switch (f * 3 + s) {
                case 3 -> FORWARD;
                case 4 -> FORWARD_LEFT;
                case 1 -> LEFT;
                case -2 -> BACK_LEFT;
                case 2 -> FORWARD_RIGHT;
                case -1 -> RIGHT;
                case -4 -> BACK_RIGHT;
                default -> BACK; // back alone, or nothing held
            };
        }
    }

    private static final double DIAGONAL = Math.sqrt(0.5);
    private static final long NONE = Long.MIN_VALUE;

    private final long[] lastTap = {NONE, NONE, NONE}; // left, back, right

    /**
     * A press of {@code key} at client tick {@code tick}: whether it's a Step, the second tap of left, back or right
     * within {@code doubleTapTicks} ticks of the first. The pair is used up, so a third tap starts a new one.
     */
    boolean tap(Key key, long tick, int doubleTapTicks) {
        int k;
        switch (key) {
            case FORWARD -> {
                return false; // a double-tap of forward is vanilla's sprint
            }
            case LEFT -> k = 0;
            case BACK -> k = 1;
            case RIGHT -> k = 2;
            default -> throw new AssertionError(key);
        }
        long last = lastTap[k];
        if (last != NONE && tick - last <= doubleTapTicks) {
            lastTap[k] = NONE;
            return true;
        }
        lastTap[k] = tick;
        return false;
    }

    /** Forgets every first tap (a screen opened, the blade left the hand). */
    void reset() {
        Arrays.fill(lastTap, NONE);
    }
}
