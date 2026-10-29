package dev.champ.kimetsu.client;

/**
 * How a combatant's strike is drawn this frame: the strike it's in, its model time, and where its body goes, from its
 * strike path (the same core math the server sweeps) and the blend back after it (spec:534). Its stand-in's pose is
 * {@code StandInPose}'s. One reused instance per renderer; the renderer reads it right after it's filled.
 */
public final class StrikeAnim {
    /** Whether the combatant is in a strike now (from its windup's start to its recovery's end or its cancel). */
    public boolean active;

    /** The strike's id and its impact tick (the AzureLib spike's clip is timed to the impact). */
    public long strike;

    public long impactTick;

    /** The combatant's model time this frame, in µs ({@code ModelTime}): what the strike was drawn at. */
    public long modelMicros;

    /**
     * How far the body goes from its entity's own position to ({@link #x}, {@link #y}, {@link #z}) facing {@link #yaw}
     * (degrees): 1 on the strike's path, 0 its own, between while it blends back.
     */
    public float place;

    public double x;
    public double y;
    public double z;
    public float yaw;

    void clear() {
        active = false;
        strike = 0;
        impactTick = 0;
        modelMicros = 0;
        place = 0;
    }
}
