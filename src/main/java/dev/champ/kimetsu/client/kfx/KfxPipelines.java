package dev.champ.kimetsu.client.kfx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.champ.kimetsu.Kimetsu;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

/**
 * The mod's render pipelines (5d-4), registered on the mod bus ({@code RegisterRenderPipelinesEvent}).
 *
 * <p>{@link #CUE}, the world cues (D-144's craft call 3, D-145's A2): drawn in the always-on-top phase, which clears
 * depth, so its test only matters between cues. Its vertex shader puts every cue at one depth and the test is strict
 * with a depth write, so a pixel keeps the first cue drawn on it: translucent pieces that overlap on screen (a faded
 * ring's, where it turns edge-on) never stack into full strength, and {@code CueQuads} draws the cues that matter most
 * first (D-146's checks). Translucent blend, both faces (a billboard's winding flips with the camera's side),
 * {@code POSITION_TEX_COLOR} quads sampling the cue sheet. No fog, unlike the plan's snippet: a cue is a telegraph on top
 * of everything, and fog would fade one under water or blindness just when it matters (D-146).
 */
public final class KfxPipelines {
    public static final RenderPipeline CUE = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withLocation(id("pipeline/kfx/cue"))
            .withVertexShader(id("core/kfx_cue"))
            .withFragmentShader(id("core/kfx_cue"))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, true))
            .withCull(false)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    private KfxPipelines() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, path);
    }
}
