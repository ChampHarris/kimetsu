package dev.champ.kimetsu.client.present;

/**
 * Where a combatant's body is drawn this frame ({@link Poser#place}): at {@code weight} of the way from its entity's
 * own position to ({@code x, y, z}) facing {@code yaw} (degrees), its strike path's place. 1 rides the path; 0 is the
 * entity's own.
 */
public final class Placement {
    public float weight;
    public double x;
    public double y;
    public double z;
    public float yaw;
}
