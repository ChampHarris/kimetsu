package dev.champ.kimetsu.client.kfx;

/**
 * The cue sheet's cells, {@code textures/cue/cues.png} (D-145's A3: one sheet, one render type, one draw), as
 * {@code tools/cue_sheet.py} draws them. A cell's UVs land half a texel inside it, so nearest sampling never reads a
 * neighbour; a strip's frames sit side by side, and its frame count is the table's ({@code CueTimeline} steps through
 * it). A band (the ring's, the Thread's) runs across v and repeats along u, so only its top and bottom rows are clear.
 */
public enum CueSheet {
    /** The white four-point star: deflect. */
    GLINT(0, 0, 16, 16, 1, false),
    /** The star's larger frame, one cue step on its last half-beat (D-134 (4)). */
    GLINT_FLARE(16, 0, 16, 16, 1, false),
    /** The star splintering as a feint breaks. */
    SHATTER(32, 0, 16, 16, 4, false),
    /** The red jagged burst: dodge. */
    BURST(96, 0, 16, 16, 1, false),
    /** The purple ring's band: a grab. */
    RING(112, 0, 16, 16, 1, true),
    /** The gold Thread line's band, its shimmer stepping along it. */
    NECK(0, 16, 16, 16, 4, true),
    /** The purple hollow ring: a grab's point, at its box's near face beside its ring (D-148). */
    HOLLOW(64, 16, 16, 16, 1, false),
    /** The micro-clash's white eight-point flash. */
    CLASH(0, 32, 32, 32, 1, false);

    public static final int WIDTH = 128;
    public static final int HEIGHT = 64;

    private final int x;
    private final int y;
    private final int w;
    private final int h;
    private final int frames;
    private final boolean band;

    CueSheet(int x, int y, int w, int h, int frames, boolean band) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.frames = frames;
        this.band = band;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int w() {
        return w;
    }

    public int h() {
        return h;
    }

    public int frames() {
        return frames;
    }

    /** Whether it's a band across v that repeats along u. */
    public boolean band() {
        return band;
    }

    public float u0(int frame) {
        return (x + frame * w + 0.5f) / WIDTH;
    }

    public float u1(int frame) {
        return (x + (frame + 1) * w - 0.5f) / WIDTH;
    }

    public float v0() {
        return (y + 0.5f) / HEIGHT;
    }

    public float v1() {
        return (y + h - 0.5f) / HEIGHT;
    }
}
