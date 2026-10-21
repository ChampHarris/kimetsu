package dev.champ.kimetsu.core.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A combatant's style and the frame data it can fire, resolved once: every id the style names (its Step, Light Chain,
 * radial and falling form), the Pommel and every Mushin finisher must exist. A missing one fails here, naming it, rather
 * than when a player first presses the key that needs it.
 */
public final class Moveset {
    private final Style style;
    private final Map<String, FrameData> frames;
    private final FrameData pommel;
    private final FrameData step;
    private final List<FrameData> chain;
    private final List<FrameData> radial;
    private final @Nullable FrameData falling;

    public Moveset(Style style, Collection<FrameData> frameData, String pommelId) {
        this.style = style;
        Map<String, FrameData> byId = new HashMap<>();
        for (FrameData f : frameData) {
            Check.that(byId.put(f.id(), f) == null, "frame data " + f.id() + " is defined twice");
        }
        this.frames = Map.copyOf(byId);
        this.pommel = find(pommelId, "pommel");
        this.step = find(style.step(), style.id() + ": step");
        List<FrameData> c = new ArrayList<>();
        for (String id : style.lightChain()) {
            c.add(find(id, style.id() + ": light_chain"));
        }
        this.chain = List.copyOf(c);
        List<FrameData> r = new ArrayList<>();
        for (String id : style.radial()) {
            r.add(find(id, style.id() + ": radial"));
        }
        this.radial = List.copyOf(r);
        String fallingId = style.fallingForm();
        this.falling = fallingId == null ? null : find(fallingId, style.id() + ": contextual.falling");
        for (FrameData f : frames.values()) {
            String m = f.mushin();
            if (m != null) {
                judged(find(m, f.id() + ": mushin"), FrameData.Judge.RELEASE, f.id() + ": mushin");
            }
        }
        for (FrameData f : radial) {
            judged(f, FrameData.Judge.RELEASE, style.id() + ": radial");
        }
        for (FrameData f : chain) {
            judged(f, FrameData.Judge.PRESS, style.id() + ": light_chain");
        }
        judged(pommel, FrameData.Judge.PRESS, "pommel");
        judged(step, FrameData.Judge.PRESS, style.id() + ": step");
    }

    /** The machine judges a form at its release and an attack at its press; the data must say the same. */
    private static void judged(FrameData f, FrameData.Judge judge, String field) {
        Check.that(
                f.judge() == judge,
                field + " names " + f.id() + ", judged at the "
                        + f.judge().name().toLowerCase(Locale.ROOT) + " where it must be at the "
                        + judge.name().toLowerCase(Locale.ROOT));
    }

    public Style style() {
        return style;
    }

    public FrameData pommel() {
        return pommel;
    }

    public FrameData step() {
        return step;
    }

    /** Light Chain hit {@code index} (0 for Chain 1). */
    public FrameData chain(int index) {
        return chain.get(index);
    }

    public int chainLength() {
        return chain.size();
    }

    /** The form in radial slot {@code slot}, or none for a slot the style doesn't fill. */
    public @Nullable FrameData radial(int slot) {
        return slot >= 0 && slot < radial.size() ? radial.get(slot) : null;
    }

    /** The form an attack press fires while falling, if the style has one. */
    public @Nullable FrameData falling() {
        return falling;
    }

    /** The frame data with this id; it must exist (every reference was checked at construction). */
    public FrameData frame(String id) {
        FrameData f = frames.get(id);
        if (f == null) {
            throw new IllegalArgumentException("no frame data " + id);
        }
        return f;
    }

    /** What {@code form} fires at Mushin: its finisher if it has one, otherwise itself (with the stand-in's damage). */
    public FrameData atMushin(FrameData form) {
        String m = form.mushin();
        return m == null ? form : frame(m);
    }

    private FrameData find(String id, String field) {
        FrameData f = frames.get(id);
        if (f == null) {
            throw new IllegalArgumentException(field + " names frame data " + id + ", which doesn't exist");
        }
        return f;
    }
}
