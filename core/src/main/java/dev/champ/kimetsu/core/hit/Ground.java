package dev.champ.kimetsu.core.hit;

/**
 * How far a body can move along the level before a block stops it: where a strike's lunge is clipped when its cue
 * locks it (Rule 7.13, D-042), so the strike's path, its sweep and the body agree on every side. The mod sweeps the box
 * through the level's collision shapes; tests use open air or a wall.
 */
@FunctionalInterface
public interface Ground {
    /** Nothing in the way, anywhere. */
    Ground OPEN = (x, y, z, halfWidth, height, dirX, dirZ, distance) -> distance;

    /**
     * How far, at most {@code distance}, a box {@code halfWidth} each side and {@code height} tall standing with its
     * feet at {@code (x, y, z)} moves along the unit direction {@code (dirX, dirZ)} before it meets a block.
     */
    double clear(
            double x, double y, double z, double halfWidth, double height, double dirX, double dirZ, double distance);
}
