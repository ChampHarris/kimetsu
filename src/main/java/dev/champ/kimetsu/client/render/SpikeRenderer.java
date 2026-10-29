package dev.champ.kimetsu.client.render;

import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.client.StrikeAnim;
import dev.champ.kimetsu.entity.M1Demon;
import mod.azure.azurelib.render.entity.AzEntityRenderState;
import mod.azure.azurelib.render.entity.AzEntityRenderer;
import mod.azure.azurelib.render.entity.AzEntityRendererConfig;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;

/**
 * The AzureLib spike (5d-3a Step 2, D-123): the M1 demon drawn through AzureLib 4.1.2, on the stand-in's skin, behind
 * {@code -Dkimetsu.azure_spike=true} ({@code ./gradlew runClient -PazureSpike=true}); the stand-in stays the default.
 * Its animator runs on the demon's model time ({@link SpikeAnimator}); during a strike the body follows the strike's
 * path as the stand-in's does (its position; the facing stays the entity's).
 */
public final class SpikeRenderer extends AzEntityRenderer<M1Demon> {
    private static final Identifier MODEL =
            Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "geo/entity/spike_demon.geo.json");

    private final StrikeAnim anim = new StrikeAnim();

    public SpikeRenderer(EntityRendererProvider.Context context, Identifier texture) {
        super(
                AzEntityRendererConfig.<M1Demon>builder(MODEL, texture)
                        .setAnimatorProvider(SpikeAnimator::new)
                        .setShadowRadius(0.5f)
                        .build(),
                context);
    }

    @Override
    public void extractRenderState(M1Demon entity, AzEntityRenderState state, float partialTick) {
        anim.active = false; // drive() fills it; nothing from the last frame survives without it
        anim.place = 0;
        if (provider.provideAnimator(entity, entity) instanceof SpikeAnimator a) {
            a.drive(entity, anim); // before AzureLib animates it, inside super
        }
        super.extractRenderState(entity, state, partialTick);
        float w = anim.place; // on the path, then blended back to the entity's own position (spec:534)
        if (w > 0) {
            state.x += (anim.x - state.x) * w;
            state.y += (anim.y - state.y) * w;
            state.z += (anim.z - state.z) * w;
        }
    }
}
