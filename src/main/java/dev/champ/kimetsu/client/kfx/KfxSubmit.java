package dev.champ.kimetsu.client.kfx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.champ.kimetsu.client.KfxMark;
import dev.champ.kimetsu.client.present.Feel;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;

/**
 * One frame's kfx submit (D-145's A3: one reused node, submitted only when the frame's mark holds a cue): its cues are
 * written as vertices by {@link KfxFeature} through {@link CueQuads}, relative to the camera under the level's identity
 * pose, in the order that should win each pixel, billboards facing the camera; their sizes from feel's {@code kfx},
 * their floor from the frame's own projection.
 */
public final class KfxSubmit implements SubmitNode {
    // /kimetsu_dev cuetime: the geometry's time, spent in the frame's prepare (buildGroup), before the render pass
    private static boolean timing;
    private static long built;

    private final CueQuads quads = new CueQuads();
    private @Nullable KfxMark mark;

    @Override
    public FeatureRendererType<KfxSubmit> featureType() {
        return KfxFeature.TYPE;
    }

    /**
     * The frame's cues, and the camera they face ({@code projectionMatrix.m00()} and {@code m11()}: its projection's
     * horizontal and vertical scales).
     */
    void set(KfxMark mark, CameraRenderState camera, Feel.Kfx k) {
        this.mark = mark;
        quads.camera(
                camera.pos.x,
                camera.pos.y,
                camera.pos.z,
                camera.orientation,
                camera.projectionMatrix.m00(),
                camera.projectionMatrix.m11());
        quads.sizes(k.cueSize(), k.cueMinPx(), k.cueMaxPx(), k.ringFadedAlpha());
        quads.screen(k.cueTetherPx(), k.cueHudPx());
    }

    void write(VertexConsumer out) {
        KfxMark m = mark;
        if (m == null) {
            return;
        }
        long start = timing ? System.nanoTime() : 0;
        quads.write(m, out);
        if (timing) {
            built += System.nanoTime() - start;
        }
    }

    /** {@code /kimetsu_dev cuetime}: whether the geometry is timed. */
    public static void timing(boolean on) {
        timing = on;
        built = 0;
    }

    /** The geometry's time since the last take, in ns. */
    public static long takeBuilt() {
        long b = built;
        built = 0;
        return b;
    }
}
