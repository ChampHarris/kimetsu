package dev.champ.kimetsu.core.demon;

import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How the demon picks a string (Rules 11.3, 11.5): its length by the profile's length weights; each strike by weight
 * among the attacks allowed to follow the last (its first from all), the leap only with Hunger to pay for it; a leap or a
 * grab ends it early; and a share of strings opening with a white feint, the next strike drawn as if it followed it. The
 * feint counts in the length, so no string runs longer than a real one could and its length never gives it away (it
 * opens only strings two or more long, more often, for the same share of all strings). Draws in id order from the
 * brain's {@link Rng}, so a seed gives the same strings everywhere.
 */
final class StringDraw {
    private final DemonProfile p;
    private final Map<String, FrameData> attacks;
    private final List<String> ids;
    private final int[] weights;
    private final double feintShare; // of the strings two or more long: the profile's chance of all strings

    StringDraw(DemonProfile p, Map<String, FrameData> attacks) {
        this.p = p;
        this.attacks = attacks;
        this.ids = List.copyOf(p.strings().weights().keySet()); // a sorted map: id order
        this.weights = new int[ids.size()];
        int i = 0;
        for (int weight : p.strings().weights().values()) { // the same order
            weights[i++] = weight;
        }
        List<Integer> lengths = p.strings().lengthWeights();
        int total = 0;
        for (int k = 0; k < lengths.size(); k++) {
            total += lengths.get(k);
        }
        int longer = total - lengths.get(0);
        this.feintShare = longer == 0 ? 0 : p.strings().feintChance() * total / longer;
    }

    /**
     * Draws a string into {@code out}, with {@code hunger} to pay for a leap; returns whether its first strike is a feint.
     */
    boolean draw(Rng rng, double hunger, List<FrameData> out) {
        DemonProfile.Strings s = p.strings();
        out.clear();
        int length = 1 + pick(rng, s.lengthWeights());
        boolean feint = length > 1 && rng.nextDouble() < feintShare;
        String prev = null;
        if (feint) {
            prev = pick(rng, null, hunger, true);
            if (prev == null) {
                throw new IllegalStateException(p.id() + " has no white attack to feint with");
            }
            out.add(frame(prev));
            length--;
        }
        for (int k = 0; k < length; k++) {
            String next = pick(rng, prev, hunger, false);
            if (next == null) {
                break;
            }
            out.add(frame(next));
            prev = next;
            if (s.enders().contains(next)) {
                break;
            }
        }
        return feint;
    }

    /** An attack by weight: white ones only for a feint, else those allowed after {@code prev}; null if none can. */
    private @Nullable String pick(Rng rng, @Nullable String prev, double hunger, boolean white) {
        List<String> allowed = prev == null ? null : p.strings().follows().get(prev);
        int total = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (can(ids.get(i), allowed, hunger, white)) {
                total += weights[i];
            }
        }
        if (total <= 0) {
            return null;
        }
        int r = rng.nextInt(total);
        for (int i = 0; i < ids.size(); i++) {
            if (can(ids.get(i), allowed, hunger, white)) {
                r -= weights[i];
                if (r < 0) {
                    return ids.get(i);
                }
            }
        }
        return null;
    }

    private boolean can(String id, @Nullable List<String> allowed, double hunger, boolean white) {
        if (white && frame(id).cue().type() != FrameData.Cue.Type.WHITE) {
            return false;
        }
        if (allowed != null && !allowed.contains(id)) {
            return false;
        }
        return !id.equals(p.strings().leap()) || hunger >= p.hunger().leapCost();
    }

    private static int pick(Rng rng, List<Integer> w) {
        int total = 0;
        for (int i = 0; i < w.size(); i++) { // no iterator: the brain's quiet ticks allocate nothing
            total += w.get(i);
        }
        int r = rng.nextInt(total);
        for (int i = 0; i < w.size(); i++) {
            r -= w.get(i);
            if (r < 0) {
                return i;
            }
        }
        return w.size() - 1;
    }

    private FrameData frame(String id) {
        FrameData f = attacks.get(id);
        if (f == null) {
            throw new IllegalArgumentException(p.id() + " names attack " + id + ", which isn't loaded");
        }
        return f;
    }
}
