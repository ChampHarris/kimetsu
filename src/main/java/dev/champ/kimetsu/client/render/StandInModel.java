package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.client.present.StandInPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Vanilla's humanoid model posed by its {@link StandInPose} (D-052: the stand-in until the rig; 5d-3a Step 3): vanilla
 * poses it first (its walk and look), then each part goes the pose's weight of the way to the pose's angles, and a
 * clang's recoil throws the head back on top. One instance draws every combatant of its type and {@code setupAnim}
 * resets the pose first, so the pose lives in the render state, never here.
 */
final class StandInModel extends HumanoidModel<StandInState> {
    StandInModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(StandInState state) {
        super.setupAnim(state);
        StandInPose p = state.standIn;
        apply(head, p, StandInPose.HEAD);
        apply(body, p, StandInPose.BODY);
        apply(rightArm, p, StandInPose.RIGHT_ARM);
        apply(leftArm, p, StandInPose.LEFT_ARM);
        apply(rightLeg, p, StandInPose.RIGHT_LEG);
        apply(leftLeg, p, StandInPose.LEFT_LEG);
        head.xRot -= p.recoil;
    }

    private static void apply(ModelPart part, StandInPose p, int i) {
        float w = p.weight[i];
        if (w <= 0) {
            return;
        }
        part.xRot += (p.rot[i * 3] - part.xRot) * w;
        part.yRot += (p.rot[i * 3 + 1] - part.yRot) * w;
        part.zRot += (p.rot[i * 3 + 2] - part.zRot) * w;
    }
}
