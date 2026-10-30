package dev.champ.kimetsu.client.kfx;

import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;

/**
 * The mod's own feature renderer (D-145's A9), registered for {@link #TYPE} on the game bus
 * ({@code RegisterFeatureRenderersEvent}): each frame's {@link KfxSubmit} writes its vertices into the staged buffer
 * here, and vanilla's {@code executeGroup} draws them inside the phase they were submitted to. Its own type, rather than
 * vanilla's {@code CustomFeatureRenderer}, so 5d-4b can time its draws inside the pass.
 */
public final class KfxFeature extends RenderTypeFeatureRenderer<KfxSubmit> {
    public static final FeatureRendererType<KfxSubmit> TYPE = FeatureRendererType.create("Kimetsu kfx");

    @Override
    protected void buildGroup(FeatureFrameContext context, List<KfxSubmit> submits) {
        for (int i = 0; i < submits.size(); i++) {
            submits.get(i).write(getVertexBuilder(KfxRenderTypes.CUE));
        }
    }
}
