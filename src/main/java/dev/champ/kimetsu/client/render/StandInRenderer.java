package dev.champ.kimetsu.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.StrikeAnim;
import dev.champ.kimetsu.client.present.DrawnFrame;
import dev.champ.kimetsu.client.present.StandInPose;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/**
 * Draws a combatant as the stand-in (D-052): vanilla's humanoid mesh with a placeholder skin, posed from what the
 * server routed (5d-3a Step 3). During a strike its body follows the strike's path on the client's combat clock, from
 * core's own math, so the claw and the body arrive on the beat together and {@code snapTo} reaching the client a tick
 * late doesn't show; after it, the body blends back to the entity's own position (spec:534). In its guard stance the
 * body turns to the guard's yaw the server judges by (Rule 11.4), the head keeping its look; both through the
 * {@link DrawnFrame} the world cues put the real hitbox on (D-145's A5). A knockdown, a collapse or a stagger leans and
 * lifts the whole body about its feet.
 */
public final class StandInRenderer<T extends Mob> extends HumanoidMobRenderer<T, StandInState, StandInModel> {
    private final Identifier texture;
    private final StrikeAnim anim = new StrikeAnim();
    private final DrawnFrame frame = new DrawnFrame();

    public StandInRenderer(EntityRendererProvider.Context context, Identifier texture) {
        super(context, new StandInModel(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
        this.texture = texture;
    }

    @Override
    public StandInState createRenderState() {
        return new StandInState();
    }

    @Override
    public Identifier getTextureLocation(StandInState state) {
        return texture;
    }

    @Override
    public void extractRenderState(T entity, StandInState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        ClientCombat.strikeAnim(entity.getId(), anim);
        StandInPose p = state.standIn;
        ClientCombat.standInPose(entity.getId(), p);
        double ex = state.x; // the entity's own interpolated position, for the dev trace
        double ey = state.y;
        double ez = state.z;
        frame.entity(state.x, state.y, state.z, state.bodyRot, state.yRot) // the frame the cues share (D-145's A5)
                .place(anim.place, anim.x, anim.y, anim.z, anim.yaw)
                .guard(p.guardWeight, p.guardYaw);
        state.x = frame.x;
        state.y = frame.y;
        state.z = frame.z;
        state.bodyRot = frame.bodyYaw;
        state.yRot = frame.headYaw;
        ClientCombat.tracePlace(entity.getId(), anim, ex, ey, ez, state.x, state.y, state.z, state.bodyRot);
    }

    @Override
    protected void setupRotations(StandInState state, PoseStack poseStack, float bodyRot, float entityScale) {
        super.setupRotations(state, poseStack, bodyRot, entityScale);
        StandInPose p = state.standIn;
        if (p.lift != 0) {
            poseStack.translate(0f, p.lift, 0f);
        }
        if (p.lean != 0) {
            poseStack.rotateDegrees(Axis.XP, p.pitchDegrees());
        }
    }
}
