package dev.champ.kimetsu.client.present;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Dev runs: the frame times of the next n frames ({@code /kimetsu_dev frametime}, 5d-3a's spike mark 5). A frame's
 * interval runs from its start to the next frame's start (what the player sees as frame time, extraction included); its
 * render pass, from its start to its end ({@code RenderFrameEvent.Pre} to {@code Post}: {@code GameRenderer.render()}
 * alone, since 26.3 runs the extraction, where entity models animate, before {@code Pre}, and the blit, submit, present
 * and frame limiter after {@code Post}). Nothing is allocated between {@link #arm} and the summary. Main thread only.
 */
public final class FrameProbe {
    private static final long UNSET = Long.MIN_VALUE;

    private long[] intervals = new long[0];
    private long[] renders = new long[0];
    private int nIntervals;
    private int nRenders;
    private long lastStart = UNSET;
    private boolean armed;

    /** n frames' times, in ns, as nearest-rank percentiles. */
    public record Summary(
            int frames,
            double fps,
            long intervalP50,
            long intervalP95,
            long intervalP99,
            long intervalMax,
            long renderP50,
            long renderP95,
            long renderP99,
            long renderMax) {}

    /** Records the next {@code frames} frames, dropping any recording under way. */
    public void arm(int frames) {
        intervals = new long[frames];
        renders = new long[frames];
        nIntervals = 0;
        nRenders = 0;
        lastStart = UNSET;
        armed = true;
    }

    public boolean armed() {
        return armed;
    }

    /** A frame starts at {@code nanos}: the summary once the last frame's interval is in, else null. */
    public @Nullable Summary frameStart(long nanos) {
        if (!armed) {
            return null;
        }
        if (lastStart != UNSET && nIntervals < intervals.length) {
            intervals[nIntervals++] = nanos - lastStart;
        }
        lastStart = nanos;
        if (nIntervals < intervals.length || nRenders < renders.length) {
            return null;
        }
        armed = false;
        long total = 0;
        for (long i : intervals) {
            total += i;
        }
        Arrays.sort(intervals);
        Arrays.sort(renders);
        return new Summary(
                intervals.length,
                intervals.length * 1e9 / total,
                rank(intervals, 50),
                rank(intervals, 95),
                rank(intervals, 99),
                intervals[intervals.length - 1],
                rank(renders, 50),
                rank(renders, 95),
                rank(renders, 99),
                renders[renders.length - 1]);
    }

    /** The frame that started last ends at {@code nanos}. */
    public void frameEnd(long nanos) {
        if (armed && lastStart != UNSET && nRenders < renders.length) {
            renders[nRenders++] = nanos - lastStart;
        }
    }

    /** The nearest-rank {@code p}th percentile of sorted {@code a}. */
    private static long rank(long[] a, int p) {
        return a[Math.max(0, (p * a.length + 99) / 100 - 1)];
    }
}
