package dev.champ.kimetsu.client.render;

/**
 * The HUD's shapes as quads into a {@link HudQuads} group (D-141's craft call 6): arcs of a ring, diamonds and
 * rectangles, in GUI pixels before the group's pose. Bearings are degrees clockwise from up on the y-down screen (90°
 * is to the right), as the radial's slots go. Allocation-free.
 */
public final class RingGeometry {
    private RingGeometry() {}

    /**
     * An arc of the ring between radii {@code r0} and {@code r1} about ({@code cx}, {@code cy}), from bearing
     * {@code fromDeg} clockwise through {@code sweepDeg}, in {@code segments} quads of one colour (ARGB).
     */
    public static void arc(
            HudQuads q, float cx, float cy, float r0, float r1, float fromDeg, float sweepDeg, int segments, int argb) {
        if (sweepDeg <= 0 || segments <= 0) {
            return;
        }
        double step = Math.toRadians(sweepDeg) / segments;
        double a = Math.toRadians(fromDeg);
        float sin0 = (float) Math.sin(a);
        float cos0 = (float) Math.cos(a);
        for (int i = 1; i <= segments; i++) {
            double b = Math.toRadians(fromDeg) + step * i;
            float sin1 = (float) Math.sin(b);
            float cos1 = (float) Math.cos(b);
            q.quad(
                    cx + sin0 * r0,
                    cy - cos0 * r0,
                    cx + sin0 * r1,
                    cy - cos0 * r1,
                    cx + sin1 * r1,
                    cy - cos1 * r1,
                    cx + sin1 * r0,
                    cy - cos1 * r0,
                    argb);
            sin0 = sin1;
            cos0 = cos1;
        }
    }

    /** A diamond whose tips are {@code r} above, below and to each side of ({@code cx}, {@code cy}). */
    public static void diamond(HudQuads q, float cx, float cy, float r, int argb) {
        q.quad(cx, cy - r, cx - r, cy, cx, cy + r, cx + r, cy, argb);
    }

    /** The rectangle from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}). */
    public static void rect(HudQuads q, float x0, float y0, float x1, float y1, int argb) {
        q.quad(x0, y0, x0, y1, x1, y1, x1, y0, argb);
    }
}
