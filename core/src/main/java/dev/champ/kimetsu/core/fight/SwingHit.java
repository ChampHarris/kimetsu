package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.HitWindow;
import dev.champ.kimetsu.core.hit.SwingTrack;

/**
 * One contact of a player's swing on a mob, as the loop hands it to the mob ({@link StrikeMob#hit}): when (a late
 * sweep keeps its tick), whose, which window and part, what {@code HitMath} reckons it deals, where the swinger stood
 * (the guard stance's front is decided by the hitter's position, never the contact point, Rule 11.4) and the beat
 * nearest its press (a guard counter lands two beats after it). Mutable and reused: read it during the call.
 */
public final class SwingHit {
    private long tick;
    private int fighter;
    private long swing;
    private FrameData attack;
    private int window;
    private int part;
    private double damage;
    private double posture;
    private double hitterX;
    private double hitterZ;
    private long pressBeat;

    SwingHit(FrameData placeholder) {
        attack = placeholder;
    }

    void set(
            long tick,
            int fighter,
            long swing,
            FrameData attack,
            int window,
            int part,
            double damage,
            double posture,
            double hitterX,
            double hitterZ,
            long pressBeat) {
        this.tick = tick;
        this.fighter = fighter;
        this.swing = swing;
        this.attack = attack;
        this.window = window;
        this.part = part;
        this.damage = damage;
        this.posture = posture;
        this.hitterX = hitterX;
        this.hitterZ = hitterZ;
        this.pressBeat = pressBeat;
    }

    public long tick() {
        return tick;
    }

    public int fighter() {
        return fighter;
    }

    /** Which of the swinger's attacks it is (one key per attack started), so a window's contacts can be told apart. */
    public long swing() {
        return swing;
    }

    public FrameData attack() {
        return attack;
    }

    public int window() {
        return window;
    }

    public HitWindow hitWindow() {
        return attack.hits().get(window);
    }

    /** {@link SwingTrack#BODY} or {@link SwingTrack#NECK}. */
    public int part() {
        return part;
    }

    public boolean neck() {
        return part == SwingTrack.NECK;
    }

    /** HP damage, after the swinger's Rhythm multiplier, the Mushin stand-in and assist (Rule 7.5). */
    public double damage() {
        return damage;
    }

    /** Posture damage, the same way. */
    public double posture() {
        return posture;
    }

    public double hitterX() {
        return hitterX;
    }

    public double hitterZ() {
        return hitterZ;
    }

    /** The beat nearest the press that started the swing (the swinger's Pulse's global index). */
    public long pressBeat() {
        return pressBeat;
    }
}
