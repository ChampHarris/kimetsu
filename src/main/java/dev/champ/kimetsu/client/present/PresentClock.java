package dev.champ.kimetsu.client.present;

import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/**
 * The combat clock as a frame presents it (spec:586, D-123's craft call 1): one sample of the client's counter, taken
 * at the first render-side read after {@link #endFrame} and shared by every reader until the next, so the entity
 * extraction, the GUI layers and the render all draw one instant.
 *
 * <ul>
 *   <li>{@link #scheduled()} is the clock plus the visual offset: what the server scheduled (the Pulse ring, remote
 *       strikes and poses, cues, the impact notch). The offset is the CLIENT config's "how late you see the game", so a
 *       positive one draws ahead.
 *   <li>{@link #now()} is the clock alone: meters and your own predicted feedback (spec:572), which you see when you
 *       made it.
 * </ul>
 *
 * Both are {@link #NOT_READY} until the clock is set. A pause holds the clock and an epoch can step it back; this
 * follows whatever the clock gives, unclamped. Readers that run on client ticks use the clock itself: up to 10 catch-up
 * ticks run before a frame's extraction after a hitch, so a sample taken there would fix the frame's time tens of ms
 * early. A dev client throws on such a read; a release client answers it fresh without keeping it. Main thread only.
 */
public final class PresentClock {
    /** What {@link #now()} and {@link #scheduled()} give before the clock is set. */
    public static final long NOT_READY = Long.MIN_VALUE;

    private final LongSupplier nanos;
    private final LongUnaryOperator micros;
    private final LongSupplier offsetMicros;
    private final boolean checked;
    private boolean sampled;
    private long now = NOT_READY;
    private long offset;
    private boolean ticking;

    /**
     * @param nanos the client's counter ({@code Util.getNanos()})
     * @param micros the combat time at a counter value, in µs, or {@link #NOT_READY} before the clock is set
     * @param offsetMicros the visual offset, in µs (positive: you see the game late)
     * @param checked whether a read during a client tick throws (dev runs)
     */
    public PresentClock(LongSupplier nanos, LongUnaryOperator micros, LongSupplier offsetMicros, boolean checked) {
        this.nanos = nanos;
        this.micros = micros;
        this.offsetMicros = offsetMicros;
        this.checked = checked;
    }

    /** The frame's combat time, in µs: meters and your own predicted feedback. */
    public long now() {
        if (ticking) {
            return duringTick();
        }
        sample();
        return now;
    }

    /** The frame's combat time plus the visual offset, in µs: what the server scheduled. */
    public long scheduled() {
        if (ticking) {
            long n = duringTick();
            return n == NOT_READY ? NOT_READY : n + offsetMicros.getAsLong();
        }
        sample();
        return now == NOT_READY ? NOT_READY : now + offset;
    }

    /** {@code RenderFrameEvent.Post}: the next read takes the next frame's sample. */
    public void endFrame() {
        sampled = false;
    }

    /** {@code ClientTickEvent.Pre}. */
    public void tickStarted() {
        ticking = true;
    }

    /** {@code ClientTickEvent.Post}. */
    public void tickEnded() {
        ticking = false;
    }

    private void sample() {
        if (sampled) {
            return;
        }
        sampled = true;
        now = micros.applyAsLong(nanos.getAsLong());
        offset = offsetMicros.getAsLong();
    }

    private long duringTick() {
        if (checked) {
            throw new IllegalStateException("PresentClock read during a client tick: read ClientClock there (D-123)");
        }
        return micros.applyAsLong(nanos.getAsLong());
    }
}
