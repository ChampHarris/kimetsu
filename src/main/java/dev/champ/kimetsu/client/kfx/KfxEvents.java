package dev.champ.kimetsu.client.kfx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.FeelLoader;
import dev.champ.kimetsu.client.KfxMark;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterFeatureRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.submit.RenderPhaseKeys;
import org.slf4j.Logger;

/**
 * kfx's registrations and its frame (5d-4): the cue pipeline on the mod bus; the feature renderer, the frame's fill and
 * its submit on the game bus. The frame's {@link KfxMark} is filled as the level's render state is extracted (after its
 * entities) and carried to the submit under a {@code ContextKey} on that state, which vanilla clears every frame (D-144's
 * craft call 11); it's set only when the mark holds a cue, so an empty frame submits nothing (D-145's A3).
 *
 * <p>The cue pipeline is registered as optional (D-146): a required one that fails to compile fails the resource reload
 * and triggers vanilla's resource-pack recovery (gotcha, D-145), while an optional one only logs. Whether it compiled
 * is checked once after each reload, since asking the pipeline cache for a failed one compiles it again, blocking, on
 * every call; while it hasn't, nothing is filled or submitted, and the crosshair diamond stands in (D-146's call (a)).
 */
public final class KfxEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ContextKey<KfxMark> MARK_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "kfx"));
    private static final KfxMark MARK = new KfxMark();
    private static final KfxSubmit SUBMIT = new KfxSubmit();
    private static boolean cueReady;
    private static boolean smoke;
    private static double smokeX;
    private static double smokeY;
    private static double smokeZ;

    private KfxEvents() {}

    /** Mod bus. */
    public static void onRegisterPipelines(RegisterRenderPipelinesEvent event) {
        event.registerOptionalPipeline(KfxPipelines.CUE);
    }

    /** Game bus. */
    public static void onRegisterFeatureRenderers(RegisterFeatureRenderersEvent event) {
        event.register(KfxFeature.TYPE, new KfxFeature());
    }

    /**
     * Game bus, after every successful resource load: the cue sheet loads now rather than mid-frame (a texture bound by
     * id loads on first use), and the cue pipeline's compile is checked.
     */
    public static void onResourcesLoaded(ClientResourceLoadFinishedEvent event) {
        Minecraft.getInstance().getTextureManager().getTexture(KfxRenderTypes.CUE_SHEET);
        cueReady = RenderSystem.getCompiledPipelineNullable(KfxPipelines.CUE) != null;
        if (!cueReady) {
            LOGGER.error("Kimetsu: {} didn't compile (its error is above); no world cues", KfxPipelines.CUE);
        }
    }

    /** Game bus, once the level's state is extracted: the frame's cues, on the extraction's own partial ticks. */
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        if (!cueReady) {
            return;
        }
        boolean any = ClientCombat.kfx(MARK, event.getDeltaTracker());
        if (smoke && !MARK.full()) { // build step 1's smoke: one glint at a fixed world point
            KfxMark.Cue c = MARK.add();
            c.cell = CueSheet.GLINT;
            c.x = smokeX;
            c.y = smokeY;
            c.z = smokeZ;
            any = true;
        }
        if (any) {
            event.getRenderState().setRenderData(MARK_KEY, MARK);
        }
    }

    /** Game bus: the frame's cues go to the always-on-top phase on the event's own collector (D-145's A2). */
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        KfxMark m = event.getLevelRenderState().getRenderData(MARK_KEY);
        if (!cueReady || m == null) {
            return;
        }
        SUBMIT.set(
                m,
                event.getLevelRenderState().cameraRenderState,
                FeelLoader.feel().kfx());
        event.getSubmitNodeCollector().submitSpecial(RenderPhaseKeys.ALWAYS_ON_TOP, SUBMIT);
    }

    /** Whether the cue pipeline compiled at the last resource load: whether world cues draw. */
    public static boolean cueReady() {
        return cueReady;
    }

    /** {@code /kimetsu_dev kfx smoke <x> <y> <z>}: build step 1's smoke, one glint at a fixed world point. */
    public static void smoke(double x, double y, double z) {
        smoke = true;
        smokeX = x;
        smokeY = y;
        smokeZ = z;
    }

    /** {@code /kimetsu_dev kfx smoke off}. */
    public static void smokeOff() {
        smoke = false;
    }
}
