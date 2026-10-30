package dev.champ.kimetsu.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.Supplier;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

/**
 * The HUD's flat geometry as the GUI renderer takes it (D-141's craft call 3): one element per group, its quads in the
 * order they were added, each wound as vanilla winds its own (TL, BL, BR, TR in y-down screen space, which
 * {@code RenderPipelines.GUI}'s back-face culling keeps), its bounds covering every vertex under the pose it was begun
 * with, and nothing allocated once it's warm. The first mod test to load {@code net.minecraft.client} classes in plain
 * JUnit: if this file can't run, nothing of the HUD's rendering can be tested off the game.
 */
class HudQuadsTest {
    private static final Supplier<RenderPipeline> NO_PIPELINE = HudQuadsTest::noPipeline;

    private static RenderPipeline noPipeline() {
        throw new AssertionError("a test never asks for the pipeline");
    }

    /** A {@link VertexConsumer} that records positions and colours into preallocated arrays. */
    static final class Recorder implements VertexConsumer {
        final float[] x = new float[4096];
        final float[] y = new float[4096];
        final float[] z = new float[4096];
        final int[] colour = new int[4096];
        int n;

        void clear() {
            n = 0;
        }

        @Override
        public VertexConsumer addVertex(float vx, float vy, float vz) {
            x[n] = vx;
            y[n] = vy;
            z[n] = vz;
            colour[n] = 0;
            n++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            colour[n - 1] = (a << 24) | (r << 16) | (g << 8) | b;
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            colour[n - 1] = argb;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            throw new AssertionError("flat quads carry no UV");
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            throw new AssertionError("flat quads carry no UV");
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            throw new AssertionError("flat quads carry no UV");
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            throw new AssertionError("flat quads carry no UV");
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            throw new AssertionError("flat quads carry no normal");
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            throw new AssertionError("flat quads carry no line width");
        }

        /** Twice the signed (shoelace) area of quad {@code q}, in the recorded screen coordinates. */
        double area2(int q) {
            double a = 0;
            for (int i = 0; i < 4; i++) {
                int p = q * 4 + i;
                int r = q * 4 + (i + 1) % 4;
                a += (double) x[p] * y[r] - (double) x[r] * y[p];
            }
            return a;
        }
    }

    /** Twice the signed area of vanilla's own winding: TL, BL, BR, TR of a unit square, y down. */
    private static final double VANILLA = -2;

    @Test
    void itLoadsOffTheGame_andIsAGuiElementWithNoTextureAndNoScissorOfItsOwn() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        assertSame(TextureSetup.noTexture(), q.textureSetup());
        assertNull(q.scissorArea(), "begun with none");
        assertNull(q.bounds(), "nothing added yet: no bounds, so the renderer drops it");
    }

    @Test
    void theRendererAsksForThePipelineEachTime_fromTheSupplier() {
        int[] asked = {0};
        HudQuads q = new HudQuads(() -> {
            asked[0]++;
            throw new IllegalStateException("the pipeline " + asked[0]);
        });
        assertThrows(IllegalStateException.class, q::pipeline);
        assertThrows(IllegalStateException.class, q::pipeline);
        assertEquals(2, asked[0], "not cached at construction, so tests never touch RenderPipelines");
    }

    @Test
    void eachQuadIsFourVerticesInTheOrderAdded_woundAsVanillaWindsItsOwn_whicheverWayItWasGiven() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        q.begin(new Matrix3x2f(), null);
        q.quad(0, 0, 0, 1, 1, 1, 1, 0, 0xFF112233); // vanilla's own order
        q.quad(10, 0, 11, 0, 11, 1, 10, 1, 0xFF445566); // the other way round
        q.quad(20, 0, 22, 0, 22, 0, 20, 0, 0xFF000000); // no area: dropped
        assertTrue(q.end());
        assertEquals(2, q.quads());
        Recorder r = new Recorder();
        q.buildVertices(r);
        assertEquals(8, r.n);
        for (int i = 0; i < 2; i++) {
            assertEquals(VANILLA, r.area2(i), 1e-6, "quad " + i + " wound as vanilla's");
        }
        assertEquals(0xFF112233, r.colour[0]);
        assertEquals(0xFF445566, r.colour[4], "the second quad follows the first");
        for (int i = 0; i < r.n; i++) {
            assertEquals(0f, r.z[i]);
        }
        assertEquals(10f, Math.min(Math.min(r.x[4], r.x[5]), Math.min(r.x[6], r.x[7])));
    }

    @Test
    void aMirroredPoseStillWindsAsVanilla_soCullingKeepsIt() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        q.begin(new Matrix3x2f().scale(-2, 1), null);
        q.quad(0, 0, 0, 1, 1, 1, 1, 0, -1);
        q.end();
        Recorder r = new Recorder();
        q.buildVertices(r);
        assertTrue(r.area2(0) < 0, "mirrored, it would have culled");
        assertEquals(VANILLA * 2, r.area2(0), 1e-6, "twice as wide");
    }

    @Test
    void perVertexColoursFollowTheirVertices_evenWhenTheWindingIsTurned() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        q.begin(new Matrix3x2f(), null);
        q.quad(0, 0, 1, 0, 1, 1, 0, 1, 1, 2, 3, 4); // TL, TR, BR, BL: turned
        q.end();
        Recorder r = new Recorder();
        q.buildVertices(r);
        for (int i = 0; i < 4; i++) {
            int expected = r.x[i] == 0 && r.y[i] == 0
                    ? 1
                    : r.x[i] == 1 && r.y[i] == 0 ? 2 : r.y[i] == 1 && r.x[i] == 1 ? 3 : 4;
            assertEquals(expected, r.colour[i], "vertex " + i);
        }
    }

    @Test
    void theBoundsCoverEveryVertexUnderATranslatedAndScaledPose_flooredAndCeiled() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        Matrix3x2f pose = new Matrix3x2f().translate(100.25f, 50.5f).scale(1.5f);
        q.begin(pose, null);
        q.quad(-3.3f, -2.2f, -3.3f, 4.1f, 7.7f, 4.1f, 7.7f, -2.2f, -1);
        pose.translate(1000, 1000); // the stack moves on after the layer: the element keeps its own copy
        assertTrue(q.end());
        Recorder r = new Recorder();
        q.buildVertices(r);
        ScreenRectangle b = q.bounds();
        assertNotNull(b);
        for (int i = 0; i < r.n; i++) {
            assertTrue(r.x[i] >= b.left() && r.x[i] <= b.right(), "x " + r.x[i] + " in " + b);
            assertTrue(r.y[i] >= b.top() && r.y[i] <= b.bottom(), "y " + r.y[i] + " in " + b);
        }
        assertEquals(100.25f + -3.3f * 1.5f, r.x[0], 1e-4);
        assertEquals((int) Math.floor(100.25 - 3.3 * 1.5), b.left());
        assertEquals((int) Math.ceil(100.25 + 7.7 * 1.5), b.right());
        assertEquals((int) Math.floor(50.5 - 2.2 * 1.5), b.top());
        assertEquals((int) Math.ceil(50.5 + 4.1 * 1.5), b.bottom());
    }

    @Test
    void aRotatedPoseTurnsEveryVertex_asJomlsOwnTransformDoes() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        Matrix3x2f pose = new Matrix3x2f()
                .translate(5, 7)
                .rotate((float) Math.toRadians(30))
                .scale(1.5f, 0.5f);
        q.begin(pose, null);
        q.quad(1, 0, 1, 2, 3, 2, 3, 0, -1);
        q.end();
        Recorder r = new Recorder();
        q.buildVertices(r);
        org.joml.Vector2f v = new org.joml.Vector2f();
        float[][] corners = {{1, 0}, {1, 2}, {3, 2}, {3, 0}};
        for (int i = 0; i < 4; i++) {
            boolean found = false;
            pose.transformPosition(corners[i][0], corners[i][1], v);
            for (int k = 0; k < 4; k++) {
                found |= Math.abs(r.x[k] - v.x) < 1e-4 && Math.abs(r.y[k] - v.y) < 1e-4;
            }
            assertTrue(found, "corner " + i + " at " + v);
        }
    }

    @Test
    void theBoundsFollowTheGroupWhenOnlyOneEdgeMoves() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        for (int bottom = 1; bottom <= 4; bottom++) {
            for (int edge = 0; edge < 4; edge++) {
                float x0 = edge == 0 ? -bottom : 0;
                float y0 = edge == 1 ? -bottom : 0;
                float x1 = edge == 2 ? 1 + bottom : 1;
                float y1 = edge == 3 ? 1 + bottom : 1;
                q.begin(new Matrix3x2f(), null); // the unit square first, so this edge is the only one that moves
                q.quad(0, 0, 0, 1, 1, 1, 1, 0, -1);
                q.end();
                q.begin(new Matrix3x2f(), null);
                q.quad(x0, y0, x0, y1, x1, y1, x1, y0, -1);
                q.end();
                ScreenRectangle b = q.bounds();
                assertNotNull(b);
                assertEquals(
                        new ScreenRectangle((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0)),
                        b,
                        "edge " + edge + " moved by " + bottom);
            }
        }
        ScreenRectangle a = new ScreenRectangle(0, 0, 100, 100);
        q.begin(new Matrix3x2f(), a);
        q.quad(0, 0, 0, 1, 1, 1, 1, 0, -1);
        q.end();
        q.begin(new Matrix3x2f(), new ScreenRectangle(0, 0, 0, 0));
        q.quad(0, 0, 0, 1, 1, 1, 1, 0, -1);
        assertFalse(q.end(), "a new scissor makes them again");
    }

    @Test
    void aSecondPoseMovesOnlyTheQuadsAfterIt_andTheBoundsCoverBoth() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        q.begin(new Matrix3x2f().translate(10, 10), null);
        q.quad(0, 0, 0, 1, 1, 1, 1, 0, -1);
        q.pose(new Matrix3x2f().translate(100, 0).scale(2));
        q.quad(0, 0, 0, 1, 1, 1, 1, 0, -1);
        q.end();
        Recorder r = new Recorder();
        q.buildVertices(r);
        assertEquals(10f, r.x[0]);
        assertEquals(100f, r.x[4]);
        assertEquals(VANILLA * 4, r.area2(1), 1e-6, "scaled by its own pose");
        assertEquals(new ScreenRectangle(10, 0, 92, 11), q.bounds());
    }

    @Test
    void aScissorClipsTheBounds_andOneItMissesDropsTheElement() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        ScreenRectangle scissor = new ScreenRectangle(0, 0, 50, 50);
        q.begin(new Matrix3x2f(), scissor);
        q.quad(40, 40, 40, 60, 60, 60, 60, 40, -1);
        assertTrue(q.end());
        assertSame(scissor, q.scissorArea());
        assertEquals(new ScreenRectangle(40, 40, 10, 10), q.bounds());

        q.begin(new Matrix3x2f(), scissor);
        q.quad(70, 70, 70, 80, 80, 80, 80, 70, -1);
        assertFalse(q.end(), "fully outside: null bounds, which the renderer would drop");
        assertNull(q.bounds());
    }

    @Test
    void anEmptyGroupHasNoBounds_andBeginningAgainForgetsTheLastFrame() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        q.begin(new Matrix3x2f(), null);
        q.quad(0, 0, 0, 1, 1, 1, 1, 0, -1);
        assertTrue(q.end());
        q.begin(new Matrix3x2f(), null);
        assertFalse(q.end());
        assertEquals(0, q.quads());
        assertNull(q.bounds());
    }

    @Test
    void itGrowsPastItsFirstSize() {
        HudQuads q = new HudQuads(NO_PIPELINE);
        q.begin(new Matrix3x2f(), null);
        for (int i = 0; i < 500; i++) {
            q.quad(i, 0, i, 1, i + 1, 1, i + 1, 0, i);
        }
        q.end();
        assertEquals(500, q.quads());
        Recorder r = new Recorder();
        q.buildVertices(r);
        assertEquals(2000, r.n);
        assertEquals(499, r.colour[1999]);
    }

    @Test
    void warm_itAllocatesNothingAcrossAThousandFrames() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assertTrue(
                bean instanceof com.sun.management.ThreadMXBean t && t.isThreadAllocatedMemorySupported(),
                "this JVM can't count a thread's allocations: the pinned JDK 25 (HotSpot) can, so this check never skips");
        com.sun.management.ThreadMXBean t = (com.sun.management.ThreadMXBean) bean;
        HudQuads q = new HudQuads(NO_PIPELINE);
        Matrix3x2f pose = new Matrix3x2f().translate(200, 120).scale(2);
        Recorder r = new Recorder();
        for (int i = 0; i < 1000; i++) {
            frame(q, pose, r); // warm: the arrays grown, the bounds made once, the JIT done
        }
        long id = Thread.currentThread().threadId();
        long before = t.getThreadAllocatedBytes(id);
        for (int i = 0; i < 1000; i++) {
            frame(q, pose, r);
        }
        long after = t.getThreadAllocatedBytes(id);
        assertTrue(after - before < 1024, "allocated " + (after - before) + " bytes over 1,000 frames");
    }

    private static void frame(HudQuads q, Matrix3x2f pose, Recorder r) {
        q.begin(pose, null);
        for (int k = 0; k < 200; k++) {
            q.quad(k, 0, k, 2, k + 1, 2, k + 1, 0, 0xFF000000 | k);
        }
        q.end();
        r.clear();
        q.buildVertices(r);
    }
}
