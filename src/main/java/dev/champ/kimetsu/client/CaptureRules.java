package dev.champ.kimetsu.client;

/**
 * The capture's one decision for a combat key (spec Netcode, "Input capture"; D-055), kept free of game state so its
 * cases are unit-tested. With the blade in the main hand and no screen open, a combat press is the mod's: a mouse
 * button's press event is cancelled, so vanilla's attack and use keys never go down (a keyboard event can't be). Sneak
 * with the guard key is vanilla's use. A release is the mod's when its press was, and is never cancelled: a cancelled
 * release would leave a vanilla key held, and releasing a key that's up does nothing. The click that grabs the mouse
 * (the window back in focus, the cursor free) is vanilla's, since cancelling it would skip the grab; its attack or use
 * is stopped by {@link #stopVanilla}. Until slice 5c brings the combat flag and D-055's mining, the blade never mines.
 */
final class CaptureRules {
    enum Action {
        /** Vanilla's: the mod takes nothing. */
        PASS,
        /** The mod's, and vanilla still sees it (a keyboard key, a release). */
        TAKE,
        /** The mod's, and vanilla never sees it. */
        TAKE_AND_CANCEL
    }

    private CaptureRules() {}

    /** {@code grabsMouse}: vanilla would grab the mouse with this press (the window is active, the mouse isn't held). */
    static Action press(
            boolean bladeInMainHand,
            boolean screenOpen,
            boolean mouseButton,
            boolean guardKey,
            boolean sneaking,
            boolean grabsMouse) {
        if (!bladeInMainHand || screenOpen || (mouseButton && grabsMouse)) {
            return Action.PASS;
        }
        if (guardKey && sneaking) {
            return Action.PASS; // Sneak + right mouse: vanilla's use, as the key table moves it
        }
        return mouseButton ? Action.TAKE_AND_CANCEL : Action.TAKE;
    }

    static Action release(boolean pressTaken) {
        return pressTaken ? Action.TAKE : Action.PASS;
    }

    /**
     * Whether vanilla's attack or use, triggered anyway (a toggle that latched before the blade came out, a binding on
     * another key, a click held from before), is stopped: with the blade in the main hand, attack always, and use unless
     * sneaking.
     */
    static boolean stopVanilla(boolean bladeInMainHand, boolean attack, boolean use, boolean sneaking) {
        return bladeInMainHand && (attack || (use && !sneaking));
    }
}
