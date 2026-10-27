package dev.champ.kimetsu.client;

/**
 * The radial as it's drawn now (Rule 5.4): its slots around the crosshair, clockwise from up, the one the stroke
 * points into, and each slot's form name. One reused instance, filled each frame by {@link ClientCombat#radial}.
 */
public final class RadialMark {
    public int slots;

    /** The lit slot, or −1 inside the dead zone. */
    public int selected = -1;

    /** The forms' names by slot, resolved once per data generation and language. */
    public String[] labels = new String[0];
}
