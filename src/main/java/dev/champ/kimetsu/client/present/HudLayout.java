package dev.champ.kimetsu.client.present;

/**
 * The combat HUD's places that must hold at every GUI and HUD scale (D-142's review, D-143): the Rhythm pips round the
 * Pulse ring, clear of its open ring; the cue marker above the top pip, on the crosshair's axis; the Breath arc's
 * anchor, clear of the off-hand slot. GUI px at HUD scale 1 from the crosshair's centre, PROPOSED; pure, so a test can
 * walk every scale.
 */
public final class HudLayout {
    /** Rhythm's pips, clockwise from the top. */
    public static final int PIPS = 5;

    /**
     * The pips' circle: the least whole radius whose pips, rounded to whole px, clear the open ring's outline by a HUD
     * pixel (2 px at the lower pips, D-143; 23 cleared by 0.5).
     */
    public static final float PIP_RADIUS = 25;
    /** Half a pip: a 3 px square (the diamond is the cue's shape, art bible section 6). */
    public static final float PIP = 1.5f;
    /** Half a pip with its outline. */
    public static final float PIP_EDGE = 2.5f;
    /** Half the ring's width with its outline: a 1 px line, 1 px of outline each side. */
    public static final float RING_EDGE = 1.5f;
    /** The top pip's top edge above the crosshair's centre. */
    public static final float PIP_TOP = PIP_RADIUS + PIP_EDGE;

    /** Vanilla's crosshair sprite: 15 px, its centre 7.5 px in from its corner. */
    public static final int CROSSHAIR = 15;

    public static final float CROSSHAIR_CENTRE = CROSSHAIR / 2f;
    /** The cue diamond's half-width with its outline, in its own px (its fill is one less). */
    public static final int CUE_OUTLINE = 4;
    /** The cue's px between its outline and the top pip's at the impact, at its own scale. */
    static final float CUE_GAP = 1;
    /** How far it closes from the cue to the impact, at its own scale: 43 to 33 px up at HUD scale 1. */
    static final float CUE_TRAVEL = 10;

    /** The Breath arc's centre left of the screen's centre at HUD scale 1, and above its bottom. */
    public static final float ARC_LEFT = 140;

    public static final float ARC_UP = 20;
    /** The arc's inner and outer radii. */
    public static final float ARC_IN = 14;

    public static final float ARC_OUT = 18;
    /** Its outline's right end, right of its centre: the point {@code hud_scale} scales it about. */
    public static final float ARC_RIGHT = ARC_OUT + 1;

    private static final float[] PIP_X = new float[PIPS];
    private static final float[] PIP_Y = new float[PIPS];

    static {
        for (int i = 0; i < PIPS; i++) {
            double a = Math.toRadians(i * 360.0 / PIPS); // clockwise from the top
            PIP_X[i] = Math.round(Math.sin(a) * PIP_RADIUS); // whole px from the crosshair's centre: crisp squares
            PIP_Y[i] = Math.round(-Math.cos(a) * PIP_RADIUS);
        }
    }

    private HudLayout() {}

    /** Pip {@code i}'s centre right of the crosshair's centre, in whole px. */
    public static float pipX(int i) {
        return PIP_X[i];
    }

    /** Pip {@code i}'s centre below the crosshair's centre, in whole px. */
    public static float pipY(int i) {
        return PIP_Y[i];
    }

    /** The cue's scale with the HUD at {@code s}: a cue never shrinks, as {@code hud_scale} is cosmetic (D-142). */
    public static float cueScale(float s) {
        return Math.max(1, s);
    }

    /**
     * The cue diamond's centre above the crosshair's centre, in GUI px (plus up to half a screen pixel from {@link #cueInset}'s rounding), at {@code progress} from its cue (0) to its
     * impact (1): it rides on the top pip at the ring's scale {@code s}, keeps its gap and travel at its own scale, and
     * rounds up to a whole screen pixel at GUI scale {@code gui}, so the gap never shrinks.
     */
    public static float cueUp(float s, int gui, float progress) {
        int g = Math.max(1, gui);
        double up = PIP_TOP * s + (CUE_OUTLINE + 0.5 + CUE_GAP + CUE_TRAVEL * (1 - progress)) * cueScale(s);
        return (float) (Math.ceil(up * g - 1e-4) / g);
    }

    /**
     * The cue diamond's centre pixel's corner, right of and below the crosshair sprite's corner, in GUI px, at the cue's
     * scale {@code k}: centred on the crosshair's centre pixel, on whole screen pixels; half a screen pixel up and left
     * when the two can't share a centre.
     */
    public static float cueInset(float k, int gui) {
        int g = Math.max(1, gui);
        return Math.floorDiv(CROSSHAIR * g - Math.round(k * g), 2) / (float) g;
    }

    /**
     * The Breath arc's centre at HUD scale {@code s} on a GUI {@code guiWidth} wide, in GUI px from its left: the arc
     * scales about its right end, which stays 1 px left of the off-hand slot (vanilla's, at the hotbar's whole-px
     * centre) at every scale.
     */
    public static float arcX(int guiWidth, float s) {
        int centre = guiWidth / 2; // whole px, as vanilla's hotbar
        return centre - ARC_LEFT + ARC_RIGHT - ARC_RIGHT * s;
    }
}
