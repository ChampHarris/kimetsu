package dev.champ.kimetsu.client.kfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The cue pipeline as slice 5d-4a's plan builds it (D-144's craft call 3, D-145's A2): drawn in the always-on-top phase,
 * every cue at one depth with a strict test and a depth write, so a pixel keeps the first cue drawn on it and a faded
 * ring's overlapping pieces never stack (D-146's checks); blended translucent, both faces, {@code POSITION_TEX_COLOR}
 * quads sampling the cue sheet; and its shaders written to ShaderC's rules (gotcha: every in and out has an explicit
 * location; {@code #include}, never {@code #moj_import}), since a shader that fails on one backend shows only in game.
 */
class KfxPipelinesTest {
    private static final Pattern IN_OUT =
            Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(in|out)\\s+\\w+\\s+\\w+\\s*;");
    private static final Pattern LOCATED = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)");
    private static final Pattern ONE_DEPTH =
            Pattern.compile("gl_Position\\.z\\s*=\\s*\\d*\\.\\d+\\s*\\*\\s*gl_Position\\.w\\s*;");

    @Test
    void cue_isTheModsOwnPipelineOnItsOwnShaders() {
        RenderPipeline p = KfxPipelines.CUE;
        assertEquals(Identifier.fromNamespaceAndPath("kimetsu", "pipeline/kfx/cue"), p.getLocation());
        Identifier shader = Identifier.fromNamespaceAndPath("kimetsu", "core/kfx_cue");
        assertEquals(shader, p.getShaders().get(ShaderType.VERTEX));
        assertEquals(shader, p.getShaders().get(ShaderType.FRAGMENT));
    }

    @Test
    void cue_keepsEachPixelsFirstCue() {
        DepthStencilState d = KfxPipelines.CUE.getDepthStencilState();
        assertNotNull(d, "a pipeline with no depth state can't sit in a pass with a depth attachment");
        assertEquals(CompareOp.GREATER_THAN, d.depthTest(), "strict: a second cue at the same depth fails");
        assertTrue(d.writeDepth(), "the first cue on a pixel claims it");
    }

    @Test
    void cueVertexShader_putsEveryCueAtOneDepth() throws IOException {
        String vertex = Files.readString(
                Path.of("src/main/resources/assets/kimetsu/shaders/core/kfx_cue.vsh"), StandardCharsets.UTF_8);
        Matcher m = ONE_DEPTH.matcher(vertex);
        assertTrue(m.find(), "the clip depth is a fixed share of w, so every cue's fragments land at one depth");
        double share = Double.parseDouble(m.group().replaceAll("^.*=\\s*(\\d*\\.\\d+).*$", "$1"));
        assertTrue(share > 0 && share < 1, "inside the clip range on both backends, and over the pass's cleared 0");
        int at = vertex.indexOf(m.group());
        assertTrue(at > vertex.indexOf("gl_Position = "), "set after the projection, which it overrides");
    }

    @Test
    void cue_blendsTranslucentAndDrawsBothFaces() {
        ColorTargetState c = KfxPipelines.CUE.getColorTargetStates().getFirst();
        assertNotNull(c);
        assertEquals(BlendFunction.TRANSLUCENT, c.blendFunction().orElseThrow());
        assertFalse(KfxPipelines.CUE.isCull(), "a billboard's winding flips with the camera's side");
    }

    @Test
    void cue_takesTexturedColouredQuadsFromTheCueSheet() {
        RenderPipeline p = KfxPipelines.CUE;
        assertEquals(DefaultVertexFormat.POSITION_TEX_COLOR, p.getVertexFormatBinding(0));
        assertEquals(PrimitiveTopology.QUADS, p.getPrimitiveTopology());
        assertTrue(p.getBindGroupLayouts().contains(BindGroupLayouts.SAMPLER0));
        assertTrue(p.getBindGroupLayouts().contains(BindGroupLayouts.PROJECTION));
        assertTrue(p.getBindGroupLayouts().contains(BindGroupLayouts.DYNAMIC_TRANSFORMS));
    }

    @Test
    void cueShaders_followShaderCsRules() throws IOException {
        for (String stage : new String[] {"vsh", "fsh"}) {
            String source = Files.readString(
                    Path.of("src/main/resources/assets/kimetsu/shaders/core/kfx_cue." + stage), StandardCharsets.UTF_8);
            assertFalse(source.contains("#moj_import"), stage + ": ShaderC takes #include");
            Matcher m = IN_OUT.matcher(source);
            int found = 0;
            while (m.find()) {
                found++;
                assertTrue(
                        LOCATED.matcher(m.group()).find(),
                        stage + ": no location on '" + m.group().trim() + "'");
            }
            assertTrue(found >= 2, stage + ": its ins and outs weren't found (" + found + ")");
        }
        String fragment = Files.readString(
                Path.of("src/main/resources/assets/kimetsu/shaders/core/kfx_cue.fsh"), StandardCharsets.UTF_8);
        assertTrue(fragment.contains("uniform sampler2D Sampler0;"), "the render type binds the sheet as Sampler0");
    }
}
