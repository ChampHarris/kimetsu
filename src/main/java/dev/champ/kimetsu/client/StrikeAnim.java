package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.data.FrameData;

/**
 * How a combatant's strike is drawn this frame (the stand-in's code-posed windup until the rig, D-052): how far its
 * windup has come, how far its swing after the impact, its cue, and where the strike path puts its body, the same
 * core math the server sweeps. One reused instance per renderer; the renderer reads it right after it's filled.
 */
public final class StrikeAnim {
    /** Whether the combatant is in a strike now. */
    public boolean active;

    /** The windup, 0 at its start to 1 at the impact. */
    public float windup;

    /** The swing after the impact, 0 to 1 over its follow-through. */
    public float swing;

    public FrameData.Cue.Type cue = FrameData.Cue.Type.NONE;

    /** Whether the path placed the body (once the strike locked); then its position and facing. */
    public boolean placed;

    public double x;
    public double y;
    public double z;
    public float yaw;

    void clear() {
        active = false;
        windup = 0;
        swing = 0;
        cue = FrameData.Cue.Type.NONE;
        placed = false;
    }
}
