package dev.champ.kimetsu.client.present;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * {@code /kimetsu_dev hudtime}'s probe (dev runs; spec:597's HUD budget, 0.1 ms): each frame's HUD cost, the sum of
 * the spans its parts report (the layers' extraction, the vertex builds), kept for the next frames asked for and summed
 * up as nearest-rank percentiles, as {@link FrameProbe} does its frames. Main thread only.
 */
public final class SpanProbe {
    /** n frames' HUD costs, in ns. */
    public record Summary(int frames, long p50, long p95, long p99, long max) {}

    private long[] frames = new long[0];
    private int n;
    private long frame; // the frame under way's sum
    private boolean armed;

    /** Records the next {@code count} frames, dropping any recording under way. */
    public void arm(int count) {
        frames = new long[count];
        n = 0;
        frame = 0;
        armed = true;
    }

    public boolean armed() {
        return armed;
    }

    /** A part of the HUD took {@code nanos} this frame. */
    public void add(long nanos) {
        if (armed) {
            frame += nanos;
        }
    }

    /** The frame ended: the summary once the last frame asked for is in, else null. */
    public @Nullable Summary frameEnd() {
        if (!armed) {
            return null;
        }
        frames[n++] = frame;
        frame = 0;
        if (n < frames.length) {
            return null;
        }
        armed = false;
        Arrays.sort(frames);
        return new Summary(
                frames.length,
                FrameProbe.rank(frames, 50),
                FrameProbe.rank(frames, 95),
                FrameProbe.rank(frames, 99),
                frames[frames.length - 1]);
    }
}
