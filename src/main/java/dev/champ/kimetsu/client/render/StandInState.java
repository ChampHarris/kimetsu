package dev.champ.kimetsu.client.render;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** A stand-in combatant's render state: vanilla's humanoid one plus its strike's progress. Made fresh each frame. */
public class StandInState extends HumanoidRenderState {
    public boolean striking;

    /** The windup, 0 at its start to 1 at the impact. */
    public float windup;

    /** The follow-through after the impact, 0 to 1. */
    public float swing;
}
