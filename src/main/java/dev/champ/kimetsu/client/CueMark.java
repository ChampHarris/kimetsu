package dev.champ.kimetsu.client;

import dev.champ.kimetsu.core.data.FrameData;

/**
 * The cue a strike on this player shows now (the art bible's reserved palette by type), and how far it has come from
 * its cue to its impact. One reused instance, filled each frame by {@link ClientCombat#cue}.
 */
public final class CueMark {
    public FrameData.Cue.Type type = FrameData.Cue.Type.NONE;

    /** 0 at the cue, 1 at the impact. */
    public float progress;
}
