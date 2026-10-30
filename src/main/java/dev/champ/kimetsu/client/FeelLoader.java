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
                    HUD.fieldOf("hud").forGetter(Feel::hud))
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
