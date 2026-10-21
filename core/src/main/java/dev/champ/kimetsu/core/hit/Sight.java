package dev.champ.kimetsu.core.hit;

/**
 * Whether a line between two points is clear of blocks (Rule 7.3): no hit passes through a wall. The mod raycasts the
 * level; tests use open air or a wall.
 */
@FunctionalInterface
public interface Sight {
    /** Nothing in the way, anywhere. */
    Sight OPEN = (x0, y0, z0, x1, y1, z1) -> true;

    boolean clear(double x0, double y0, double z0, double x1, double y1, double z1);
}
