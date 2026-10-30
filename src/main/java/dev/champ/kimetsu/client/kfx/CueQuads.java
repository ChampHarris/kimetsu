package dev.champ.kimetsu.client.kfx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.champ.kimetsu.client.KfxMark;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * A frame's world cues as quads for the cue pipeline (slice 5d-4a; D-144's craft calls 3 and 4, D-145's A7, A8 and
 * A11): positions relative to the camera, built in double and written as float so they hold far from the origin. The
 * pipeline keeps each pixel's first cue (D-146's checks), so they're drawn in the order that should win: the point cues
 * (billboards and the Thread) near to far, so the nearest is on top and no ring's band covers one; then every ring's
 * full pieces, then their faded ones, each near to far, so a faded piece never stacks on another or dims a full one.
 *
 * <ul>
 *   <li><b>A billboard</b> (the glint, its flare and shatter, the burst, the clash flash) faces the camera with its
 *       cell's inset UVs at its corners, so the sprite's outline is drawn whole. A 16-texel cell is {@code cue_size}
 *       across (the sheet's 32 texels a block: the clash's 32 is twice that), never less on screen than
 *       {@code cue_min_px} of a 1080-line screen's height (the same share of any other's), so it reads at 16 blocks,
 *       and never more than {@code cue_max_px}, so up close it keeps the size it reads at 4 blocks (D-147). One of
 *       yours is drawn whole on screen and above the HUD's band ({@code cue_hud_px}), and a strike's no farther on
 *       screen from its attacker's eyes than {@code cue_tether_px} (D-147, B3: at the fight's range the arc's start
 *       lies past the screen's side or under the chat and the Breath crest).
 *   <li><b>The ring</b>: its outer edge a circle round its attacker at its box's centre height, grown from the body to
 *       the box's forward reach; full strength where it runs inside the box and {@code ring_faded_alpha} outside, split
 *       exactly at the box's edge, since one quad's alpha blends across it and would spill full strength past the
 *       reach. Its band faces the camera and grows inward on screen from that edge, at least the floor thick and never
 *       past the centre, so it reads edge-on and never claims more reach than the grab has. Exactly edge-on, it
 *       stands up.
 *   <li><b>The Thread's band</b> (A8): a square on the neck box's centre, turned along the on-screen direction of the
 *       neck's x axis (its demon's right at the drawn yaw), as long as the neck is on screen and at least the floor;
 *       its sheet runs left to right with its top up, and an axis seen end-on lies screen horizontal.
 * </ul>
 *
 * A bystander's cue (A7) takes its {@code alpha}, and its {@code scale} shrinks the sprite and its floor, never the
 * reach or the neck it marks. Main thread; mutable and reused; allocates nothing.
 */
public final class CueQuads {
    /** The screen height {@code cue_min_px} counts in: a cue keeps the same share of any screen's height. */
    public static final int REFERENCE_LINES = 1080;

    /** How many segments a whole ring is drawn in, before its splits at its box's edges. */
    static final int RING_SEGMENTS = 48;

    private static final int TEXELS = 16; // a cell's texels across at cue_size
    private static final double TAU = 2 * Math.PI;
    private static final double EDGE_ON = 1e-6; // a ring's plane or a neck's axis seen no wider than this: edge-on
    private static final double SAME_ANGLE = 1e-12;
    // vanilla's near plane (Camera.PROJECTION_Z_NEAR): nearer, or behind you, a point has no place on screen
    private static final double NEAR = 0.05;

    private final Vector3f axis = new Vector3f();
    private double camX;
    private double camY;
    private double camZ;
    // the camera's right, up and forward
    private double rx;
    private double ry;
    private double rz;
    private double ux;
    private double uy;
    private double uz;
    private double fx;
    private double fy;
    private double fz;
    private float xScale = 1;
    private float yScale = 1;
    // the knobs (feel's kfx), set before the first write
    private float cueSize;
    private float minShare;
    private float maxShare;
    private float ringFaded;
    private float tetherShare;
    private float hudShare;
    // the attacker's eyes of the cue being written: their place on the tangent plane, and their depth
    private double eyeX;
    private double eyeY;
    private double eyeDepth;
    // near to far
    private final int[] order = new int[KfxMark.CUES];
    private final double[] distance = new double[KfxMark.CUES];
    // the ring being written: its angles, two spokes (its edge, then its band's inner end) and its frame
    private final double[] angles = new double[RING_SEGMENTS + 9];
    private final double[] spokeA = new double[6];
    private final double[] spokeB = new double[6];
    private double ringX;
    private double ringY;
    private double ringZ;
    private double rightX;
    private double rightZ;
    private double fwdX;
    private double fwdZ;
    private double radius;
    private double band;
    private boolean edgeOn;

    /**
     * The frame's camera: where it is, how it's turned ({@code CameraRenderState.orientation}) and its projection's
     * horizontal and vertical scales, 1 / tan of half the field of view across and up ({@code projectionMatrix.m00()}
     * and {@code m11()}).
     */
    public void camera(double x, double y, double z, Quaternionfc orientation, float xScale, float yScale) {
        camX = x;
        camY = y;
        camZ = z;
        orientation.transform(1, 0, 0, axis);
        rx = axis.x;
        ry = axis.y;
        rz = axis.z;
        orientation.transform(0, 1, 0, axis);
        ux = axis.x;
        uy = axis.y;
        uz = axis.z;
        orientation.transform(0, 0, -1, axis);
        fx = axis.x;
        fy = axis.y;
        fz = axis.z;
        this.xScale = xScale;
        this.yScale = yScale;
    }

    /**
     * The knobs: a 16-texel cell's size in blocks ({@code cue_size}), the smallest and the largest a billboard's is drawn
     * in px of a 1080-line screen ({@code cue_min_px}, {@code cue_max_px}; the floor wins where they cross), and the
     * ring's strength outside its box ({@code ring_faded_alpha}).
     */
    public void sizes(float cueSize, float minPx, float maxPx, float ringFaded) {
        this.cueSize = cueSize;
        this.minShare = minPx / REFERENCE_LINES;
        this.maxShare = maxPx / REFERENCE_LINES;
        this.ringFaded = ringFaded;
    }

    /**
     * Where a point cue of yours may sit (D-147), in px of a 1080-line screen: no farther from its attacker's eyes than
     * {@code cue_tether_px}, and out of the band {@code cue_hud_px} tall at the screen's bottom.
     */
    public void screen(float tetherPx, float hudPx) {
        this.tetherShare = tetherPx / REFERENCE_LINES;
        this.hudShare = hudPx / REFERENCE_LINES;
    }

    /**
     * Writes the mark's cues: the point cues, then the rings' full pieces, then their faded ones; within each, full
     * strength before dimmed, then near to far. A pixel keeps the first cue drawn on it, so a nearer bystander's cue
     * never takes a pixel from yours (D-146's review).
     */
    public void write(KfxMark mark, VertexConsumer out) {
        int n = mark.cueCount();
        for (int i = 0; i < n; i++) {
            KfxMark.Cue c = mark.cue(i);
            double dx = c.x - camX;
            double dy = c.y - camY;
            double dz = c.z - camZ;
            double d = dx * dx + dy * dy + dz * dz;
            int j = i;
            for (; j > 0 && after(mark.cue(order[j - 1]), distance[j - 1], c, d); j--) {
                distance[j] = distance[j - 1];
                order[j] = order[j - 1];
            }
            distance[j] = d;
            order[j] = i;
        }
        boolean rings = false;
        for (int k = 0; k < n; k++) {
            KfxMark.Cue c = mark.cue(order[k]);
            if (c.cell == CueSheet.RING) {
                rings = true;
            } else if (c.cell == CueSheet.NECK) {
                thread(c, out);
            } else {
                billboard(c, out);
            }
        }
        if (rings) {
            for (int pass = 0; pass < 2; pass++) {
                for (int k = 0; k < n; k++) {
                    KfxMark.Cue c = mark.cue(order[k]);
                    if (c.cell == CueSheet.RING) {
                        ring(c, pass == 0, out);
                    }
                }
            }
        }
    }

    /** Whether {@code a}, {@code da} away (squared), is drawn after {@code b}: the stronger first, then the nearer. */
    private static boolean after(KfxMark.Cue a, double da, KfxMark.Cue b, double db) {
        int strength = Float.compare(a.alpha, b.alpha);
        return strength != 0 ? strength < 0 : da > db;
    }

    /**
     * A billboard; one of yours (D-147) is placed on the view's tangent plane at its own depth (across and up over
     * depth): pulled in along its line to its attacker's eyes until within {@code cue_tether_px} of them, then moved the
     * least that puts it whole on screen and above the HUD's band. Behind the camera's plane, a tethered one sits at its
     * tether toward where it is, at its eyes' depth.
     */
    private void billboard(KfxMark.Cue c, VertexConsumer out) {
        double cx = c.x - camX;
        double cy = c.y - camY;
        double cz = c.z - camZ;
        double depth = depth(cx, cy, cz);
        boolean tether = c.framed && c.tethered && eyes(c);
        double at = depth > NEAR || !tether ? depth : eyeDepth; // the depth it's drawn at
        double texel = c.scale * size(at) / TEXELS;
        double hw = texel * c.cell.w() / 2;
        double hh = texel * c.cell.h() / 2;
        if (c.framed && (tether || !c.tethered) && at > NEAR) { // a strike's demon behind you: where it stands

            double across = cx * rx + cy * ry + cz * rz;
            double up = cx * ux + cy * uy + cz * uz;
            double reach = 2 * tetherShare / yScale; // on the tangent plane
            double sx;
            double sy;
            if (depth > NEAR) {
                sx = across / depth;
                sy = up / depth;
                if (tether) {
                    double dx = sx - eyeX;
                    double dy = sy - eyeY;
                    double off = Math.hypot(dx, dy);
                    if (off > reach) {
                        sx = eyeX + dx * reach / off;
                        sy = eyeY + dy * reach / off;
                    }
                }
            } else { // behind the camera's plane, tethered: at its tether, toward where it is
                double n = Math.hypot(across, up);
                sx = eyeX + (n > 0 ? across / n * reach : 0);
                sy = eyeY + (n > 0 ? up / n * reach : 0);
            }
            double side = 1 / xScale;
            double top = 1 / yScale;
            sx = within(sx, hw / at - side, side - hw / at);
            sy = within(sy, (2 * hudShare - 1) * top + hh / at, top - hh / at);
            cx = (fx + rx * sx + ux * sy) * at;
            cy = (fy + ry * sx + uy * sy) * at;
            cz = (fz + rz * sx + uz * sy) * at;
        }
        quad(out, cx, cy, cz, rx * hw, ry * hw, rz * hw, ux * hh, uy * hh, uz * hh, c.cell, c.frame, colour(c.alpha));
    }

    /** Whether {@code c}'s attacker's eyes lie ahead of the camera; their place and depth into the eye fields if so. */
    private boolean eyes(KfxMark.Cue c) {
        double ex = c.tetherX - camX;
        double ey = c.tetherY - camY;
        double ez = c.tetherZ - camZ;
        eyeDepth = depth(ex, ey, ez);
        if (!(eyeDepth > NEAR)) {
            return false;
        }
        eyeX = (ex * rx + ey * ry + ez * rz) / eyeDepth;
        eyeY = (ex * ux + ey * uy + ez * uz) / eyeDepth;
        // off the screen, only their direction counts: onto its edge along the line from its centre, so the place
        // doesn't climb as their depth nears the camera's plane (the code review: a jump of 760 px in a degree)
        double k = Math.min(1, Math.min(1 / xScale / Math.abs(eyeX), 1 / yScale / Math.abs(eyeY)));
        eyeX *= k;
        eyeY *= k;
        return true;
    }

    /** {@code v} kept from {@code lo} to {@code hi}; their midpoint when the sprite is too large to fit between. */
    private static double within(double v, double lo, double hi) {
        return lo <= hi ? Math.clamp(v, lo, hi) : (lo + hi) / 2;
    }

    /**
     * A 16-texel billboard cell's size in blocks at {@code depth}: {@code cue_size}, no larger than {@code cue_max_px}
     * and no smaller than {@code cue_min_px} on a 1080-line screen; behind the camera, {@code cue_size}.
     */
    private double size(double depth) {
        return depth > 0 ? Math.max(floor(depth), Math.min(cueSize, 2 * maxShare * depth / yScale)) : cueSize;
    }

    private void thread(KfxMark.Cue c, VertexConsumer out) {
        double cx = c.x - camX;
        double cy = c.y - camY;
        double cz = c.z - camZ;
        double yaw = Math.toRadians(c.yaw);
        double ax = -Math.cos(yaw); // the neck's x axis: its demon's right, as core's Frame turns it
        double az = -Math.sin(yaw);
        double dx = ax * rx + az * rz; // its direction on screen
        double dy = ax * ux + az * uz;
        double seen = Math.hypot(dx, dy);
        if (seen < EDGE_ON) { // end-on
            dx = 1;
            dy = 0;
        } else if (dx < 0 || (dx == 0 && dy < 0)) { // left to right on screen, so the sheet's top stays up
            dx = -dx / seen;
            dy = -dy / seen;
        } else {
            dx /= seen;
            dy /= seen;
        }
        double half = Math.max(2 * c.boxHx * seen, c.scale * floor(depth(cx, cy, cz)) * c.cell.w() / TEXELS) / 2;
        double across = half * c.cell.h() / c.cell.w();
        double lx = (rx * dx + ux * dy) * half;
        double ly = (ry * dx + uy * dy) * half;
        double lz = (rz * dx + uz * dy) * half;
        double nx = (ux * dx - rx * dy) * across;
        double ny = (uy * dx - ry * dy) * across;
        double nz = (uz * dx - rz * dy) * across;
        quad(out, cx, cy, cz, lx, ly, lz, nx, ny, nz, c.cell, c.frame, colour(c.alpha));
    }

    /** The ring's pieces inside its box ({@code full}) or outside it, in angle order. */
    private void ring(KfxMark.Cue c, boolean full, VertexConsumer out) {
        double p = Math.clamp(c.progress, 0f, 1f);
        radius = c.body * (1 - p) + (c.boxZ + c.boxHz) * p; // from its body to its box's forward reach
        if (!(radius > 0)) {
            return;
        }
        double yaw = Math.toRadians(c.yaw);
        rightX = -Math.cos(yaw); // its attacker's right and forward, as core's Frame turns them
        rightZ = -Math.sin(yaw);
        fwdX = -Math.sin(yaw);
        fwdZ = Math.cos(yaw);
        ringX = c.x - camX;
        ringY = c.y - camY;
        ringZ = c.z - camZ;
        edgeOn = Math.abs(ringY) < EDGE_ON;
        band = c.scale * c.cell.h() / (double) TEXELS;
        int n = 0;
        for (int k = 0; k <= RING_SEGMENTS; k++) {
            angles[n++] = TAU * k / RING_SEGMENTS;
        }
        n = crossAcross(n, (c.boxX - c.boxHx) / radius); // where the edge crosses the box's four sides
        n = crossAcross(n, (c.boxX + c.boxHx) / radius);
        n = crossForward(n, (c.boxZ - c.boxHz) / radius);
        n = crossForward(n, (c.boxZ + c.boxHz) / radius);
        int strong = colour(c.alpha);
        int faded = colour(c.alpha * ringFaded);
        float u0 = c.cell.u0(c.frame);
        float u1 = c.cell.u1(c.frame);
        float v0 = c.cell.v0();
        float v1 = c.cell.v1();
        double[] a = spokeA;
        double[] b = spokeB;
        double from = angles[0];
        spoke(from, a);
        for (int i = 1; i < n; i++) {
            double to = angles[i];
            if (to - from < SAME_ANGLE) {
                continue;
            }
            spoke(to, b);
            double mid = (from + to) / 2; // a split segment lies wholly inside the box or wholly out
            boolean in = Math.abs(radius * Math.sin(mid) - c.boxX) <= c.boxHx
                    && Math.abs(radius * Math.cos(mid) - c.boxZ) <= c.boxHz;
            if (in == full) {
                int colour = in ? strong : faded;
                vertex(out, a, 0, u0, v0, colour); // outer, inner, inner, outer
                vertex(out, a, 3, u0, v1, colour);
                vertex(out, b, 3, u1, v1, colour);
                vertex(out, b, 0, u1, v0, colour);
            }
            double[] t = a;
            a = b;
            b = t;
            from = to;
        }
    }

    /** The ring's spoke at angle {@code theta} (0 ahead, π/2 to its attacker's right): its edge, then its band's end. */
    private void spoke(double theta, double[] s) {
        double sin = Math.sin(theta);
        double cos = Math.cos(theta);
        double wx = (rightX * sin + fwdX * cos) * radius; // from the axis out to the edge
        double wz = (rightZ * sin + fwdZ * cos) * radius;
        double px = ringX + wx;
        double py = ringY;
        double pz = ringZ + wz;
        double sx = 0; // edge-on, the band stands up
        double sy = 1;
        double sz = 0;
        if (!edgeOn) {
            double tx = rightX * cos - fwdX * sin; // the edge's tangent, across the ground
            double tz = rightZ * cos - fwdZ * sin;
            double nx = -tz * py; // tangent × view ray: square to both, so the band faces the camera
            double ny = tz * px - tx * pz;
            double nz = tx * py;
            // toward the axis; how high the camera sits over the ring fixes this sign all the way round: no twist
            double k = (nx * wx + nz * wz > 0 ? -1 : 1) / Math.sqrt(nx * nx + ny * ny + nz * nz);
            sx = nx * k;
            sy = ny * k;
            sz = nz * k;
        }
        double thick = Math.min(radius, band * Math.max(cueSize, floor(depth(px, py, pz))));
        s[0] = px;
        s[1] = py;
        s[2] = pz;
        s[3] = px + sx * thick;
        s[4] = py + sy * thick;
        s[5] = pz + sz * thick;
    }

    /** Adds the angles where the ring's edge crosses the box's side at {@code s} radii across (sin θ = s). */
    private int crossAcross(int n, double s) {
        if (!(Math.abs(s) <= 1)) {
            return n;
        }
        double a = Math.asin(s);
        return insert(insert(n, a < 0 ? a + TAU : a), Math.PI - a);
    }

    /** Adds the angles where the ring's edge crosses the box's face at {@code s} radii forward (cos θ = s). */
    private int crossForward(int n, double s) {
        if (!(Math.abs(s) <= 1)) {
            return n;
        }
        double a = Math.acos(s);
        return insert(insert(n, a), TAU - a);
    }

    private int insert(int n, double a) {
        int i = n;
        for (; i > 0 && angles[i - 1] > a; i--) {
            angles[i] = angles[i - 1];
        }
        angles[i] = a;
        return n + 1;
    }

    /** The full size of a 16-texel cell at {@code depth} that's {@code cue_min_px} of a 1080-line screen. */
    private double floor(double depth) {
        return 2 * minShare * depth / yScale;
    }

    private double depth(double x, double y, double z) {
        return x * fx + y * fy + z * fz;
    }

    private static void vertex(VertexConsumer out, double[] s, int i, float u, float v, int colour) {
        out.addVertex((float) s[i], (float) s[i + 1], (float) s[i + 2])
                .setUv(u, v)
                .setColor(colour);
    }

    /** A quad on ({@code cx, cy, cz}), {@code l} from its centre to its right edge and {@code n} to its top. */
    private static void quad(
            VertexConsumer out,
            double cx,
            double cy,
            double cz,
            double lx,
            double ly,
            double lz,
            double nx,
            double ny,
            double nz,
            CueSheet cell,
            int frame,
            int colour) {
        float u0 = cell.u0(frame);
        float u1 = cell.u1(frame);
        float v0 = cell.v0();
        float v1 = cell.v1();
        out.addVertex((float) (cx - lx + nx), (float) (cy - ly + ny), (float) (cz - lz + nz))
                .setUv(u0, v0)
                .setColor(colour);
        out.addVertex((float) (cx - lx - nx), (float) (cy - ly - ny), (float) (cz - lz - nz))
                .setUv(u0, v1)
                .setColor(colour);
        out.addVertex((float) (cx + lx - nx), (float) (cy + ly - ny), (float) (cz + lz - nz))
                .setUv(u1, v1)
                .setColor(colour);
        out.addVertex((float) (cx + lx + nx), (float) (cy + ly + ny), (float) (cz + lz + nz))
                .setUv(u1, v0)
                .setColor(colour);
    }

    /** White at {@code alpha}: the sheet carries the cues' colours. */
    private static int colour(float alpha) {
        int a = (int) (Math.clamp(alpha, 0f, 1f) * 255 + 0.5f);
        return (a << 24) | 0xFFFFFF;
    }
}
