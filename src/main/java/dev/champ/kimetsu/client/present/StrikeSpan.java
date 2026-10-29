package dev.champ.kimetsu.client.present;

import dev.champ.kimetsu.core.strike.StrikeView;
import org.jspecify.annotations.Nullable;

/**
 * One strike as a stand-in draws it, in µs: from its windup's start to its end (its recovery's end, or its cancel), its
 * lock (its cue, or its windup's start if later), its impact, how long its follow-through runs (its active and recovery
 * frames), and its path once it has locked. Mutable and reused.
 */
public final class StrikeSpan {
    private long id;
    private long start;
    private long lock;
    private long impact;
    private long end;
    private long follow;
    private @Nullable StrikeView path;

    public void set(long id, long start, long lock, long impact, long end, long follow, @Nullable StrikeView path) {
        this.id = id;
        this.start = start;
        this.lock = lock;
        this.impact = impact;
        this.end = end;
        this.follow = follow;
        this.path = path;
    }

    void copy(StrikeSpan o) {
        set(o.id, o.start, o.lock, o.impact, o.end, o.follow, o.path);
    }

    public long id() {
        return id;
    }

    public long start() {
        return start;
    }

    /** When its path locks (at its cue, D-042), never before its windup's start. */
    public long lock() {
        return lock;
    }

    public long impact() {
        return impact;
    }

    public long end() {
        return end;
    }

    public long follow() {
        return follow;
    }

    /** Its path from its lock (core's own math, as the server sweeps it), or null before its lock. */
    public @Nullable StrikeView path() {
        return path;
    }

    /** The windup, 0 at its start to 1 at the impact. */
    float windup(long t) {
        return impact > start ? Math.clamp((t - start) / (float) (impact - start), 0f, 1f) : 1f;
    }

    /** The follow-through, 0 at the impact to 1 at its recovery's end. */
    float swing(long t) {
        return t <= impact ? 0f : follow > 0 ? Math.clamp((t - impact) / (float) follow, 0f, 1f) : 1f;
    }
}
