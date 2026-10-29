package dev.champ.kimetsu.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.StrikeAnim;
import dev.champ.kimetsu.client.present.StandInPose;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

/**
 * Draws a combatant as the stand-in (D-052): vanilla's humanoid mesh with a placeholder skin, posed from what the
 * server routed (5d-3a Step 3). During a strike its body follows the strike's path on the client's combat clock, from
 * core's own math, so the claw and the body arrive on the beat together and {@code snapTo} reaching the client a tick
 * late doesn't show; after it, the body blends back to the entity's own position (spec:534). In its guard stance the
 * body turns to the guard's yaw the server judges by (Rule 11.4), the head keeping its look; a knockdown, a collapse
 * or a stagger leans and lifts the whole body about its feet.
 */
public final class StandInRenderer<T extends Mob> extends HumanoidMobRenderer<T, StandInState, StandInModel> {
    private final Identifier texture;
    private final StrikeAnim anim = new StrikeAnim();

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
        double ex = state.x; // the entity's own interpolated position, for the dev trace
        double ey = state.y;
        double ez = state.z;
        float w = anim.place;
        if (w > 0) {
            float head = state.bodyRot + state.yRot; // the head's own heading, which the path turns with the body
            state.x += (anim.x - state.x) * w;
            state.y += (anim.y - state.y) * w;
            state.z += (anim.z - state.z) * w;
            state.bodyRot = Mth.rotLerp(w, state.bodyRot, anim.yaw);
            state.yRot = Mth.wrapDegrees(Mth.rotLerp(w, head, anim.yaw) - state.bodyRot);
        }
        StandInPose p = state.standIn;
        ClientCombat.standInPose(entity.getId(), p);
        if (p.guardWeight > 0) {
            float head = state.bodyRot + state.yRot; // the head keeps its look as the body turns to its guard
            state.bodyRot = Mth.rotLerp(p.guardWeight, state.bodyRot, p.guardYaw);
            state.yRot = Mth.wrapDegrees(head - state.bodyRot);
        }
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
