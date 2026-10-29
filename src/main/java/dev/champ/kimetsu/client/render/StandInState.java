package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.client.present.StandInPose;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** A stand-in combatant's render state: vanilla's humanoid one plus its pose (5d-3a Step 3). Made fresh each frame. */
public class StandInState extends HumanoidRenderState {
    public final StandInPose standIn = new StandInPose();
}
