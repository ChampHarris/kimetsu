package dev.champ.kimetsu.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Vanilla's humanoid model with a code-posed strike (D-052: the stand-in until the rig): the arms rise through the
 * windup, overhead at the impact, and come down through the follow-through. One instance draws every combatant of its
 * type and {@code setupAnim} resets the pose first, so the pose lives in the render state, never here.
 */
final class StandInModel extends HumanoidModel<StandInState> {
    private static final float RAISED = -2.6f; // radians: the arm overhead
    private static final float THROUGH = 0.5f; // and past the hip at the end of the swing
    private static final float TWIST = 0.35f; // the body winds toward the striking arm

    StandInModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(StandInState state) {
        super.setupAnim(state);
        if (!state.striking) {
            return;
        }
        float arm = RAISED * state.windup + (THROUGH - RAISED) * state.swing;
        rightArm.xRot = arm;
        leftArm.xRot = arm * 0.6f;
        body.yRot = TWIST * state.windup * (1 - state.swing);
    }
}
