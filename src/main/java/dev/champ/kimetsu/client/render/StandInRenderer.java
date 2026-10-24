package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.StrikeAnim;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/**
 * Draws a combatant as the stand-in (D-052): vanilla's humanoid mesh with a placeholder skin. During a strike its body
 * follows the strike's path on the client's combat clock, from core's own math, so the claw and the body arrive on the
 * beat together and {@code snapTo} reaching the client a tick late doesn't show; its arms follow the windup.
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
        state.striking = ClientCombat.strikeAnim(entity.getId(), anim);
        if (!state.striking) {
            return;
        }
        state.windup = anim.windup;
        state.swing = anim.swing;
        if (anim.placed) {
            state.x = anim.x;
            state.y = anim.y;
            state.z = anim.z;
            state.bodyRot = anim.yaw;
            state.yRot = 0; // the head faces where the body does
        }
    }
}
