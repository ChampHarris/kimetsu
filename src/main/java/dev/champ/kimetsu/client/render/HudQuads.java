package dev.champ.kimetsu.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * One drawn group of the HUD's flat, untextured quads (the Pulse ring, the Breath arc, the bars, the winded tint) as one
 * GUI element, refilled each frame and reused (D-141's craft call 3). In one element its quads draw in the order they
 * were added; the renderer sorts separate elements by pipeline and texture (gotcha: GUI in 26.3), so a group's
 * overlapping parts stay in order only inside one.
 *
 * <p>Each vertex is put through the pose the group was begun with by hand, as it's added ({@code addVertexWith2DPose}
 * allocates a vector a vertex), and each quad is wound as vanilla winds its own (TL, BL, BR, TR in y-down screen space)
 * whichever way it came, so {@code RenderPipelines.GUI}'s back-face culling never drops it, a mirrored pose's included.
 * Its bounds are the floor and ceiling of what it covers ({@code ScreenRectangle} is integer), clipped to the scissor;
 * none, and the renderer drops it. Warm, it allocates nothing: the bounds are made again only when they change. Main
 * thread only: a layer fills it during the GUI's extraction, and the renderer reads it in the same frame.
 */
public final class HudQuads implements GuiElementRenderState {
    private static boolean timing; // /kimetsu_dev hudtime: the builds' time, which the renderer spends after the layers
    private static long built;

    private final Supplier<RenderPipeline> pipeline;
    private final Matrix3x2f pose = new Matrix3x2f();
    private @Nullable ScreenRectangle scissor;
    private float[] xs = new float[256];
    private float[] ys = new float[256];
    private int[] colours = new int[256];
    private int vertices;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;
    private @Nullable ScreenRectangle bounds;
    // the last bounds made, and what they were made from, so an unchanged group makes none
    private @Nullable ScreenRectangle made;
    private @Nullable ScreenRectangle madeScissor;
    private int madeX0 = Integer.MIN_VALUE;
    private int madeY0;
    private int madeX1;
    private int madeY1;

    /**
     * @param pipeline the pipeline it draws with, asked each time the renderer asks ({@code RenderPipelines.GUI} in the
     *     game; tests never touch {@code RenderPipelines}' static setup)
     */
    public HudQuads(Supplier<RenderPipeline> pipeline) {
        this.pipeline = pipeline;
    }

    /** Starts the frame's group under {@code pose} (copied), clipped to {@code scissor}: last frame's quads go. */
    public void begin(Matrix3x2fc pose, @Nullable ScreenRectangle scissor) {
        this.pose.set(pose);
        this.scissor = scissor;
        vertices = 0;
        minX = Float.POSITIVE_INFINITY;
        minY = Float.POSITIVE_INFINITY;
        maxX = Float.NEGATIVE_INFINITY;
        maxY = Float.NEGATIVE_INFINITY;
        bounds = null;
    }

    /** Puts the quads that follow under {@code pose} (copied): a second anchor in the same group. */
    public void pose(Matrix3x2fc pose) {
        this.pose.set(pose);
    }

    /** A quad of one colour (ARGB), its corners in either winding. */
    public void quad(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, int argb) {
        quad(x0, y0, x1, y1, x2, y2, x3, y3, argb, argb, argb, argb);
    }

    /** A quad with a colour (ARGB) per corner, its corners in either winding; one with no area is left out. */
    public void quad(
            float x0,
            float y0,
            float x1,
            float y1,
            float x2,
            float y2,
            float x3,
            float y3,
            int c0,
            int c1,
            int c2,
            int c3) {
        float sx0 = px(x0, y0);
        float sy0 = py(x0, y0);
        float sx1 = px(x1, y1);
        float sy1 = py(x1, y1);
        float sx2 = px(x2, y2);
        float sy2 = py(x2, y2);
        float sx3 = px(x3, y3);
        float sy3 = py(x3, y3);
        float area2 = sx0 * sy1 - sx1 * sy0 + sx1 * sy2 - sx2 * sy1 + sx2 * sy3 - sx3 * sy2 + sx3 * sy0 - sx0 * sy3;
        if (area2 == 0) {
            return;
        }
        room();
        if (area2 < 0) { // vanilla's: TL, BL, BR, TR has a negative shoelace area with y down
            put(sx0, sy0, c0);
            put(sx1, sy1, c1);
            put(sx2, sy2, c2);
            put(sx3, sy3, c3);
        } else {
            put(sx0, sy0, c0);
            put(sx3, sy3, c3);
            put(sx2, sy2, c2);
            put(sx1, sy1, c1);
        }
    }

    /** Ends the frame's group: whether it has anything to draw within its scissor. */
    public boolean end() {
        if (vertices == 0) {
            bounds = null;
            return false;
        }
        int x0 = (int) Math.floor(minX);
        int y0 = (int) Math.floor(minY);
        int x1 = (int) Math.ceil(maxX);
        int y1 = (int) Math.ceil(maxY);
        ScreenRectangle s = scissor;
        if (made == null
                || x0 != madeX0
                || y0 != madeY0
                || x1 != madeX1
                || y1 != madeY1
                || (s == null ? madeScissor != null : !s.equals(madeScissor))) {
            ScreenRectangle raw = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
            made = s != null ? s.intersection(raw) : raw;
            madeScissor = s;
            madeX0 = x0;
            madeY0 = y0;
            madeX1 = x1;
            madeY1 = y1;
        }
        bounds = made;
        return bounds != null;
    }

    /** How many quads it holds. */
    public int quads() {
        return vertices / 4;
    }

    @Override
    public void buildVertices(VertexConsumer out) {
        long start = timing ? System.nanoTime() : 0;
        for (int i = 0; i < vertices; i++) {
            out.addVertex(xs[i], ys[i], 0f).setColor(colours[i]);
        }
        if (timing) {
            built += System.nanoTime() - start;
        }
    }

    /** Dev runs ({@code /kimetsu_dev hudtime}): whether every group's builds are timed, from nothing. */
    public static void timing(boolean on) {
        timing = on;
        built = 0;
    }

    /** The time every group's builds took since the last call, in ns. */
    public static long takeBuilt() {
        long b = built;
        built = 0;
        return b;
    }

    @Override
    public RenderPipeline pipeline() {
        return pipeline.get();
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return bounds;
    }

    private float px(float x, float y) {
        return pose.m00() * x + pose.m10() * y + pose.m20();
    }

    private float py(float x, float y) {
        return pose.m01() * x + pose.m11() * y + pose.m21();
    }

    private void room() {
        if (vertices + 4 > xs.length) { // a bigger group than any before: rare, so it grows rather than drops
            int n = xs.length * 2;
            xs = Arrays.copyOf(xs, n);
            ys = Arrays.copyOf(ys, n);
            colours = Arrays.copyOf(colours, n);
        }
    }

    private void put(float x, float y, int argb) {
        xs[vertices] = x;
        ys[vertices] = y;
        colours[vertices] = argb;
        vertices++;
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
    }
}
