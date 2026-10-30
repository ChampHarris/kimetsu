package dev.champ.kimetsu.client.present;

/**
 * The contextual form's preview (spec:582, ruling Q11; D-141's call 10): an icon while an attack press would fire the
 * falling form. The fall is the server's own reading (core's {@code FallHeight}, from the local player's last two poses
 * and the level, asked once a client tick), and the form's gate is {@code OwnMoves.canStartFalling}'s, mirroring
 * {@code Combatant.contextual}.
 */
public final class ContextualPreview {
    private ContextualPreview() {}

    /** Whether the icon shows: a fall of {@code fallBlocks} reaches {@code minFallBlocks} and the form can start. */
    public static boolean shows(double fallBlocks, double minFallBlocks, boolean canStart) {
        return canStart && fallBlocks >= minFallBlocks;
    }
}
