package dev.champ.kimetsu.client;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.client.present.Feel;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Loads {@code assets/kimetsu/feel/default.json}, the presentation's timings (spec:438 and :697: a client resource
 * pack file, reloaded with F3+T, so it isn't the datapack JSON {@code content-data.md} asks of tuning; D-138). Each key
 * is bounded; a file that doesn't decode is logged by vanilla's loader and the last good values stay.
 */
public final class FeelLoader extends SimpleJsonResourceReloadListener<Feel> {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final Identifier KEY = Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "feel");
    static final Identifier DEFAULT = Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "default");
    static final Codec<Feel.Hud> HUD = RecordCodecBuilder.create(i -> i.group(
                    Codec.intRange(0, Feel.Hud.MAX_MS).fieldOf("fade_after_ms").forGetter(Feel.Hud::fadeAfterMs),
                    Codec.intRange(0, Feel.Hud.MAX_MS).fieldOf("fade_in_ms").forGetter(Feel.Hud::fadeInMs),
                    Codec.intRange(0, Feel.Hud.MAX_MS).fieldOf("fade_out_ms").forGetter(Feel.Hud::fadeOutMs),
                    Codec.intRange(0, Feel.Hud.MAX_MS).fieldOf("tint_fade_ms").forGetter(Feel.Hud::tintFadeMs))
            .apply(i, Feel.Hud::new));
    static final Codec<Feel.Kfx> KFX = RecordCodecBuilder.create(i -> i.group(
                    Codec.floatRange(Feel.Kfx.MIN_CUE_SIZE, Feel.Kfx.MAX_CUE_SIZE)
                            .fieldOf("cue_size")
                            .forGetter(Feel.Kfx::cueSize),
                    Codec.intRange(0, Feel.Kfx.MAX_CUE_MIN_PX)
                            .fieldOf("cue_min_px")
                            .forGetter(Feel.Kfx::cueMinPx),
                    Codec.intRange(0, Feel.Kfx.MAX_CUE_MAX_PX)
                            .fieldOf("cue_max_px")
                            .forGetter(Feel.Kfx::cueMaxPx),
                    Codec.floatRange(0, 1).fieldOf("ring_faded_alpha").forGetter(Feel.Kfx::ringFadedAlpha),
                    Codec.intRange(0, Feel.Kfx.MAX_FLASH_MS).fieldOf("flash_ms").forGetter(Feel.Kfx::flashMs),
                    Codec.intRange(1, Feel.Kfx.MAX_CUE_STEP_FPS)
                            .fieldOf("cue_step_fps")
                            .forGetter(Feel.Kfx::cueStepFps),
                    Codec.floatRange(0, 1).fieldOf("arc_base").forGetter(Feel.Kfx::arcBase),
                    Codec.floatRange(0, 1).fieldOf("bystander_alpha").forGetter(Feel.Kfx::bystanderAlpha),
                    Codec.floatRange(Feel.Kfx.MIN_BYSTANDER_SCALE, 1)
                            .fieldOf("bystander_scale")
                            .forGetter(Feel.Kfx::bystanderScale),
                    Codec.intRange(0, Feel.Kfx.MAX_CUE_TETHER_PX)
                            .fieldOf("cue_tether_px")
                            .forGetter(Feel.Kfx::cueTetherPx),
                    Codec.intRange(0, Feel.Kfx.MAX_CUE_HUD_PX)
                            .fieldOf("cue_hud_px")
                            .forGetter(Feel.Kfx::cueHudPx),
                    Codec.floatRange(Feel.Kfx.MIN_GRAB_POINT_FROM, 1)
                            .fieldOf("grab_point_from")
                            .forGetter(Feel.Kfx::grabPointFrom),
                    Codec.BOOL.fieldOf("crosshair_cue").forGetter(Feel.Kfx::crosshairCue))
            .apply(i, Feel.Kfx::new));
    static final Codec<Feel> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.intRange(0, Feel.MAX_BLEND_TICKS)
                            .fieldOf("blend_back_ticks")
                            .forGetter(Feel::blendBackTicks),
                    Codec.intRange(0, Feel.MAX_BLEND_TICKS)
                            .fieldOf("blend_in_ticks")
                            .forGetter(Feel::blendInTicks),
                    Codec.intRange(0, Feel.MAX_BLEND_TICKS)
                            .fieldOf("pose_blend_ticks")
                            .forGetter(Feel::poseBlendTicks),
                    HUD.fieldOf("hud").forGetter(Feel::hud),
                    KFX.fieldOf("kfx").forGetter(Feel::kfx))
            .apply(i, Feel::new));

    private static volatile Feel feel = Feel.DEFAULT;

    FeelLoader() {
        super(CODEC, FileToIdConverter.json("feel"));
    }

    /** The timings in use: the file's last good values, or the defaults it ships with before it first loads. */
    public static Feel feel() {
        return feel;
    }

    @Override
    protected void apply(Map<Identifier, Feel> loaded, ResourceManager manager, ProfilerFiller profiler) {
        Feel f = loaded.get(DEFAULT);
        if (f == null) {
            LOGGER.error("Kimetsu: feel/default.json didn't load (its error is above); keeping {}", feel);
            return;
        }
        feel = f;
        LOGGER.info("Kimetsu: {}", f);
    }
}
