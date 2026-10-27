package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.machine.State;
import org.jspecify.annotations.Nullable;

/**
 * Rule 5.4's radial flick (a marking menu, R6-09; D-104), kept free of game state so its cases are unit-tested. The
 * server opens the radial from NEUTRAL alone ({@code Combatant.breatheUp}, {@code advance}); from anywhere else a
 * slotted release waits in its buffer and is dropped. So the client aims only while the server could open it: from the
 * later of the press plus {@code machine.radial_hold_ms} and the first moment its own guard, attack and Step are over
 * and its last snapshot shows nothing that holds it ({@link #opens}). While it aims, the mouse doesn't turn the camera;
 * its motion is the stroke, summed in the degrees it would have turned the camera ({@link #degrees}), so a flick feels
 * the same at any DPI, with Y as moved on screen (a menu isn't a camera, so invert-Y doesn't apply).
 *
 * <p>Times are the server's judged times ({@code OwnPresses}): an update at a moment says whether a release judged then
 * would meet the radial open. A client tick's start stands at an engine step's end instead ({@link #stepEnded}), where
 * the server's {@code advance} opens and closes it. The aim starts in the engine step the server opened it in: its
 * edge's (the press plus the hold) if nothing held it back across that edge, else the step of the moment the client
 * first saw it free (a step's end, or a press or a frame within the next step).
 *
 * <p>The aim ends at a guard press (the radial cancels into GUARD), at the end of its opening step plus the radial's
 * limit (none on assist, D-036), where the server's {@code advance} closes it (a release judged in that last step still
 * meets it), or when a snapshot settled in or after the opening step shows a hit, a hold or a fall that closed the
 * radial, or shows anything else but RADIAL: the server never opened it (a windup it held, a press it cut or dropped,
 * none of which the client sees), the backstop of D-112; ended, it doesn't aim again until a release, as the server's
 * {@code radialSpent}. A snapshot settled before the opening step only delays it: the server couldn't have opened the
 * radial yet. At the release
 * the slot is the on-axis sector the stroke points into ({@link #slot}), or −1 inside {@code machine.radial_flick_deg}
 * or when it isn't aiming. It's drawn once Breathe has been held to {@code machine.radial_reveal_ms} while it aims, so a
 * practised flick fires without the menu appearing.
 */
final class RadialStroke {
    /**
     * The mouse sensitivity at which vanilla's turn ({@code MouseHandler.turnPlayer}: (s · 0.6F + 0.2F)³ · 8) is exactly
     * zero; the literal −1/3 leaves about 1e-25° a pixel.
     */
    static final double FROZEN_SENSITIVITY = -(double) 0.2F / (double) 0.6F;

    /** No radial limit: assist players keep it open (D-036). */
    static final long NO_LIMIT = Long.MIN_VALUE;

    private static final long NONE = Long.MIN_VALUE;

    private long pressMicros = NONE; // Breathe's press, while it's held
    private long openStep = NONE; // the engine step the server opened it in, while it aims
    private boolean spent; // the aim ended with Breathe still held
    private boolean lastFree; // the last update found nothing holding it back
    private double x; // the stroke, degrees: right
    private double y; // and down, as on screen
    private int lastSlot = -1; // the last real release's slot and stroke length, for the overlay
    private double lastLength;

    /**
     * The degrees vanilla would turn the camera for {@code pixels} of mouse motion at {@code sensitivity} (the option's
     * 0 to 1): {@code MouseHandler.turnPlayer}'s (s · 0.6 + 0.2)³ · 8, then {@code Entity.turn}'s 0.15.
     */
    static double degrees(double pixels, double sensitivity) {
        double s = sensitivity * 0.6F + 0.2F;
        return pixels * s * s * s * 8.0 * 0.15F;
    }

    /**
     * Whether the server's state the last snapshot shows ({@code null}: none yet, a fresh fighter) lets the radial open as
     * far as the client can tell: GUARD, ATTACK and STEP are the client's own presses, which tell better than a snapshot
     * a round trip old; anything else is a hit, a hold or a fall whose end only a later snapshot shows.
     */
    static boolean opens(@Nullable State shown) {
        if (shown == null) {
            return true;
        }
        return switch (shown) {
            case NEUTRAL, GUARD, ATTACK, STEP, RADIAL -> true;
            default -> false;
        };
    }

    /**
     * The slot of {@code slots} the stroke ({@code x} right, {@code y} down, in degrees) points into, each taking an
     * equal sector centred on its axis, clockwise from up (four: up, right, down, left, the style's order), or −1 if
     * it's shorter than {@code flickDeg}.
     */
    static int slot(double x, double y, int slots, double flickDeg) {
        if (slots <= 0 || Math.hypot(x, y) < flickDeg) {
            return -1;
        }
        double clockwise = Math.toDegrees(Math.atan2(x, -y)); // 0 up, 90 right
        return Math.floorMod(Math.round(clockwise * slots / 360.0), slots);
    }

    /** Breathe went down at {@code stampMicros}: a fresh stroke. */
    void press(long stampMicros) {
        pressMicros = stampMicros;
        openStep = NONE;
        spent = false;
        lastFree = false;
        x = 0;
        y = 0;
    }

    /**
     * What the client knows for a release judged at {@code nowMicros} (a press's, a release's or a frame's moment); returns
     * whether it aims.
     *
     * @param holdMicros {@code machine.radial_hold_ms}
     * @param limitMicros how long the radial stays open from its opening step with nothing fired, a whole number of
     *     ticks, or {@link #NO_LIMIT}
     * @param blocked the client's own guard is held, or its own attack or Step runs: the server isn't NEUTRAL
     * @param shown the state the server's last snapshot shows, or null before the first
     * @param shownSettledMicros the time that snapshot was settled at
     */
    boolean update(
            long nowMicros,
            long holdMicros,
            long limitMicros,
            boolean blocked,
            @Nullable State shown,
            long shownSettledMicros) {
        return see(
                nowMicros,
                Micros.stepOf(nowMicros),
                false,
                holdMicros,
                limitMicros,
                blocked,
                shown,
                shownSettledMicros);
    }

    /**
     * What the client knows at the end of engine step {@code step} (a client tick's start, after the step's presses), as
     * {@link #update}: the server's {@code advance} closes it there at its limit and opens it there if it's free.
     */
    boolean stepEnded(
            long step,
            long holdMicros,
            long limitMicros,
            boolean blocked,
            @Nullable State shown,
            long shownSettledMicros) {
        return see(Micros.ofTicks(step), step, true, holdMicros, limitMicros, blocked, shown, shownSettledMicros);
    }

    /**
     * At {@code atMicros} in engine step {@code step}: at its end ({@code stepEnd}, after the step's own advance) or within
     * it (before it).
     */
    private boolean see(
            long atMicros,
            long step,
            boolean stepEnd,
            long holdMicros,
            long limitMicros,
            boolean blocked,
            @Nullable State shown,
            long shownSettledMicros) {
        if (pressMicros == NONE || spent) {
            return false;
        }
        boolean opens = opens(shown);
        if (openStep != NONE) {
            long close = limitMicros != NO_LIMIT ? openStep + limitMicros / Micros.PER_TICK : NONE;
            if (!opens) {
                end(shownSettledMicros >= Micros.ofTicks(openStep)); // settled before it: the server hadn't opened it
            } else if (shown != State.RADIAL && shown != null && shownSettledMicros >= Micros.ofTicks(openStep)) {
                end(true); // the backstop: settled where the server would show it open, it never opened it (D-112)
            } else if (close != NONE && (stepEnd ? step >= close : step > close)) {
                end(true); // it closed with nothing fired
            }
        }
        boolean free = !blocked && opens;
        if (openStep == NONE && !spent && free && atMicros - pressMicros >= holdMicros) {
            // free since the last update: from the edge's step; else from this moment's, which a step's end opened
            openStep = lastFree ? Micros.stepOf(pressMicros + holdMicros) : step;
        }
        lastFree = free;
        return openStep != NONE;
    }

    /**
     * A guard press the server will see, stamped {@code stampMicros}: an open radial cancels into GUARD, and so does one
     * that opened at the hold's edge ({@code holdMicros}) before an update saw it, nothing holding it back at the last
     * (D-107's review); one not yet open isn't touched.
     */
    void guardPressed(long stampMicros, long holdMicros) {
        if (openStep != NONE
                || (pressMicros != NONE && !spent && lastFree && stampMicros - pressMicros >= holdMicros)) {
            end(true);
        }
    }

    /** Mouse motion, in degrees of would-be turn ({@code dy} down, as on screen): the stroke's while it aims. */
    void move(double dx, double dy) {
        if (openStep != NONE) {
            x += dx;
            y += dy;
        }
    }

    /**
     * A real Breathe key-up, with the motion polled before it in its frame ({@code dx}, {@code dy}): the slot it fires,
     * or −1. The stroke is over either way.
     */
    int release(double dx, double dy, int slots, double flickDeg) {
        move(dx, dy);
        int s = openStep != NONE ? slot(x, y, slots, flickDeg) : -1;
        if (pressMicros != NONE) {
            lastSlot = s;
            lastLength = openStep != NONE ? Math.hypot(x, y) : 0;
        }
        cancel();
        return s;
    }

    /** Breathe let go with no slot (a screen, the pause, the blade leaving the hand, the lost focus), or forgotten. */
    void cancel() {
        pressMicros = NONE;
        openStep = NONE;
        spent = false;
        x = 0;
        y = 0;
    }

    private void end(boolean spends) {
        openStep = NONE;
        spent = spends;
        x = 0;
        y = 0;
    }

    boolean aiming() {
        return openStep != NONE;
    }

    /** Whether it's drawn at {@code nowMicros}: aiming, with Breathe held at least {@code revealMicros}. */
    boolean drawn(long nowMicros, long revealMicros) {
        return openStep != NONE && nowMicros - pressMicros >= revealMicros;
    }

    /** The slot the stroke points into now, lit on the drawn radial, or −1. */
    int selected(int slots, double flickDeg) {
        return openStep != NONE ? slot(x, y, slots, flickDeg) : -1;
    }

    /** The last real release's slot (−1: none fired), for the overlay. */
    int lastSlot() {
        return lastSlot;
    }

    /** And its stroke's length in degrees (0 if it wasn't aiming). */
    double lastLength() {
        return lastLength;
    }
}
