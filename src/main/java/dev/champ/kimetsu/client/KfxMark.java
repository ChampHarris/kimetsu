package dev.champ.kimetsu.client;

import dev.champ.kimetsu.client.kfx.CueSheet;
import java.util.Objects;

/**
 * One frame's kfx as it's drawn (slice 5d-4, D-144's craft call 11): the world cues (5d-4a); the ribbons and the impact
 * frame come with 5d-4b. One reused instance, filled each frame in the level's extraction and written as vertices in its
 * submit; its slots are made once and handed out reset, so a frame allocates nothing.
 */
public final class KfxMark {
    /** The most cues a frame draws (D-145's A7: yours first, then the nearest). */
    public static final int CUES = 16;

    private final Cue[] cues = new Cue[CUES];
    private int count;

    public KfxMark() {
        for (int i = 0; i < CUES; i++) {
            cues[i] = new Cue();
        }
    }

    /** Empties it for the next frame. */
    public void clear() {
        count = 0;
    }

    /** The next cue, reset to a full-strength glint at the origin; there must be room ({@link #full}). */
    public Cue add() {
        if (count == CUES) {
            throw new IllegalStateException("a frame draws at most " + CUES + " cues");
        }
        Cue c = cues[count++];
        c.reset();
        return c;
    }

    /** Whether it holds {@link #CUES} cues. */
    public boolean full() {
        return count == CUES;
    }

    /** How many cues it holds this frame. */
    public int cueCount() {
        return count;
    }

    /** This frame's cue {@code i}, from 0 to {@link #cueCount()}. */
    public Cue cue(int i) {
        return cues[Objects.checkIndex(i, count)];
    }

    /**
     * One cue as it's drawn ({@code CueQuads}): its cell says how, a billboard, the ring or the Thread's band. World
     * positions; the ring's box in its attacker's frame (x right, z forward, from its feet).
     */
    public static final class Cue {
        public CueSheet cell = CueSheet.GLINT;

        /** The strip's frame: the shatter's, the Thread's shimmer's. */
        public int frame;

        /**
         * Where it is: a billboard's centre; the ring's centre (its attacker's axis at its box's centre height); the
         * Thread's neck box's centre.
         */
        public double x;

        public double y;
        public double z;

        /** Its attacker's drawn yaw, in degrees (Minecraft's): the ring's frame, and the Thread's axis (its right). */
        public float yaw;

        /** The ring: its box's centre across (right) and forward from its attacker's axis. */
        public double boxX;

        public double boxZ;

        /** The box's half extents across and forward; the Thread's neck box's across, half its length. */
        public double boxHx;

        public double boxHz;

        /** The ring: its attacker's half-width, the radius it grows from. */
        public double body;

        /** The ring: how far it has grown, 0 at its cue to 1 at its impact (its box's forward reach). */
        public float progress;

        /** Its strength (1, or {@code bystander_alpha} for a cue neither on you nor yours: D-145's A7). */
        public float alpha = 1;

        /** Its sprite's size (1, or {@code bystander_scale}); never the reach or the neck it marks. */
        public float scale = 1;

        /** A point cue of yours (D-147): drawn whole on screen, above the HUD's band. */
        public boolean framed;

        /** ... and a strike's: no farther on screen from its attacker's eyes, here, than {@code cue_tether_px}. */
        public boolean tethered;

        public double tetherX;
        public double tetherY;
        public double tetherZ;

        private void reset() {
            cell = CueSheet.GLINT;
            frame = 0;
            x = 0;
            y = 0;
            z = 0;
            yaw = 0;
            boxX = 0;
            boxZ = 0;
            boxHx = 0;
            boxHz = 0;
            body = 0;
            progress = 0;
            alpha = 1;
            scale = 1;
            framed = false;
            tethered = false;
            tetherX = 0;
            tetherY = 0;
            tetherZ = 0;
        }
    }
}
