package dev.champ.kimetsu.client.kfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.champ.kimetsu.Kimetsu;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * The mod's render types (5d-4): {@link #CUE}, the world cues on {@link KfxPipelines#CUE}, binding the cue sheet as
 * {@code Sampler0} clamped to its edge and sampled nearest (D-145's A3; a texture bound by id defaults to nearest, but
 * not to clamp). One type for every cue, so a frame's cues are one draw.
 */
public final class KfxRenderTypes {
    public static final Identifier CUE_SHEET = Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "textures/cue/cues.png");

    public static final RenderType CUE = RenderType.create(
            "kimetsu_kfx_cue",
            RenderSetup.builder(KfxPipelines.CUE)
                    .withTexture("Sampler0", CUE_SHEET, KfxRenderTypes::nearestClamped)
                    .createRenderSetup());

    private KfxRenderTypes() {}

    private static GpuSampler nearestClamped() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }
}
