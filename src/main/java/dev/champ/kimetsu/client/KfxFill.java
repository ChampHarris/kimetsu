package dev.champ.kimetsu.client;

import dev.champ.kimetsu.client.kfx.CueSheet;
import dev.champ.kimetsu.client.present.ArcInBox;
import dev.champ.kimetsu.client.present.CueTimeline;
import dev.champ.kimetsu.client.present.DrawnFrame;
import dev.champ.kimetsu.client.present.Feel;
import dev.champ.kimetsu.client.present.Track;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.FrameData.Sweep;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.hit.Frame;
import dev.champ.kimetsu.core.hit.V3;
import java.util.Arrays;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A frame's world cues from what the server routed (slice 5d-4a; D-144's craft call 4, D-145's A1 and A5 to A11), on
 * the frame's scheduled time, so a hit-stop can't delay one. Every cue the client knows is drawn, not only those on its
 * player (they're public, spec:557):
 *
 * <ul>
 *   <li><b>A strike's cue</b> by its type: the white glint (its flare and a feint's shatter by {@link CueTimeline}) and
 *       the red burst stand at its arc's start in its own box ({@link ArcInBox}'s default, from the attacker's right),
 *       the purple ring on its attacker's axis at its box's centre height; each on its attacker's drawn body
 *       ({@link DrawnFrame}, D-145's A5), so before its lock it stands on the entity's own pose and after it follows
 *       the body onto its path. A feint's shatter starts at the later of its cancel tick and the first frame that knew
 *       of the cancel, which the view keeps (A6).
 *   <li><b>A demon's Thread</b> on its neck box, the kneeling one while COLLAPSED, from its opening tick (A8), its
 *       shimmer stepping. It's an opening any Slayer within the engage range may take, so it draws at full strength
 *       there (D-146's call (m)) and not beyond.
 *   <li><b>A micro-clash's flash</b>, once, midway between its two fighters at {@link #CHEST} height (A11), stamped
 *       with the first frame that showed it (flaw 13).
 * </ul>
 *
 * A cue on its player, or one it's involved in (its clash), draws at full strength wherever it is; another only within
 * the engage range, at {@code bystander_alpha} and {@code bystander_scale} (A7). The 16 slots take the full-strength
 * ones first, then the nearest to the player. Main thread; mutable and reused; allocates nothing once warm (A10: the
 * view's strikes come from its kept array, never its maps).
 */
final class KfxFill {
    /** How far up a body its chest is: where the clash flash stands, midway between its two fighters. */
    static final double CHEST = 0.75;

    private static final int STRIKE = 0;
    private static final int THREAD = 1;
    private static final int CLASH = 2;

    /** The bodies the client draws this frame. */
    interface Bodies {
        /** How many combatants (demons) the client has. */
        int combatants();

        /** The {@code i}th one's entity id. */
        int combatant(int i);

        /** Fills {@code out} with entity {@code id}'s drawn frame this frame; false if the client lacks it. */
        boolean drawn(int id, DrawnFrame out);
    }

    // the data, per generation
    private Map<String, FrameData> frames = Map.of();
    private @Nullable DemonProfile demon;
    private double fighterHeight;
    private double engageSq;
    private long period;
    // this frame's
    private Bodies bodies = NO_BODIES;
    private int self;
    private long t;
    private Feel.Kfx kfx = Feel.Kfx.DEFAULT;
    private long step;
    // each body's drawn frame, looked up once a frame
    private int[] bodyIds = new int[8];
    private boolean[] bodyHas = new boolean[8];
    private DrawnFrame[] bodyFrames = frames(8);
    private int bodyCount;
    // the cues that show, before the 16 slots are chosen
    private int[] kinds = new int[32];
    private int[] items = new int[32]; // a listed strike, a combatant's id or a clash, by kind
    private boolean[] full = new boolean[32];
    private double[] distSq = new double[32];
    private int[] order = new int[32];
    private int count;
    private final KfxMark.Cue probe = new KfxMark.Cue();
    private boolean yours; // whether the cue last written is on the player or its own: full strength
    private int strip; // the white glint's strip frame, as its look sets it
    private final ArcInBox arc = new ArcInBox();
    private final Frame frame = new Frame();
    private final V3 local = new V3();
    private final V3 world = new V3();

    private static final Bodies NO_BODIES = new Bodies() {
        @Override
        public int combatants() {
            return 0;
        }

        @Override
        public int combatant(int i) {
            throw new IndexOutOfBoundsException(i);
        }

        @Override
        public boolean drawn(int id, DrawnFrame out) {
            return false;
        }
    };

    /**
     * The data the cues read: the attacks' frame data, the demon's profile (its half-width, height and neck boxes), a
     * Slayer's height, the engage range and the target's beat (M1: one style for every Slayer), in µs.
     */
    void data(
            Map<String, FrameData> frames,
            DemonProfile demon,
            double fighterHeight,
            double engageBlocks,
            long periodMicros) {
        this.frames = frames;
        this.demon = demon;
        this.fighterHeight = fighterHeight;
        this.engageSq = engageBlocks * engageBlocks;
        this.period = periodMicros;
    }

    /** Fills {@code out} with the cues that show at scheduled time {@code t} to player {@code self}; whether any do. */
    boolean fill(ClientView view, Bodies bodies, int self, long t, Feel.Kfx k, KfxMark out) {
        out.clear();
        this.bodies = bodies;
        this.self = self;
        this.t = t;
        this.kfx = k;
        this.step = k.cueStepMicros();
        bodyCount = 0;
        count = 0;
        DrawnFrame me = drawn(self);
        if (demon == null || me == null) {
            return false;
        }
        for (int i = 0; i < view.listedCount(); i++) {
            consider(view, STRIKE, i, me);
        }
        for (int i = 0; i < bodies.combatants(); i++) {
            consider(view, THREAD, bodies.combatant(i), me);
        }
        for (int i = 0; i < view.clashCount(); i++) {
            consider(view, CLASH, i, me);
        }
        for (int i = 1; i < count; i++) { // full strength first, then the nearest
            int c = order[i];
            int j = i;
            for (; j > 0 && before(c, order[j - 1]); j--) {
                order[j] = order[j - 1];
            }
            order[j] = c;
        }
        for (int i = 0; i < count && !out.full(); i++) {
            int c = order[i];
            cue(view, kinds[c], items[c], out.add());
            if (kinds[c] == CLASH) {
                view.clash(items[c]).stamp(t);
            }
        }
        return out.cueCount() > 0;
    }

    /** Lists the cue of {@code kind} for {@code item} if it shows, and is yours or within the engage range of you. */
    private void consider(ClientView view, int kind, int item, DrawnFrame me) {
        if (!cue(view, kind, item, probe)) {
            return;
        }
        boolean strength = yours;
        double dx = probe.x - me.x;
        double dy = probe.y - me.y;
        double dz = probe.z - me.z;
        double d = dx * dx + dy * dy + dz * dz;
        if ((!strength || kind == THREAD) && !(d <= engageSq)) {
            return;
        }
        if (count == kinds.length) {
            grow();
        }
        kinds[count] = kind;
        items[count] = item;
        full[count] = strength;
        distSq[count] = d;
        order[count] = count;
        count++;
    }

    private boolean before(int a, int b) {
        return full[a] != full[b] ? full[a] : distSq[a] < distSq[b];
    }

    /** Writes the cue of {@code kind} for {@code item} into {@code c}; false if it doesn't show. */
    private boolean cue(ClientView view, int kind, int item, KfxMark.Cue c) {
        return switch (kind) {
            case STRIKE -> strike(view, view.listed(item), c);
            case THREAD -> thread(view, item, c);
            default -> clash(view.clash(item), c);
        };
    }

    private boolean strike(ClientView view, Outbox.AttackStarted s, KfxMark.Cue c) {
        FrameData f = frames.get(s.attack());
        Sweep sweep = f != null ? f.sweep() : null;
        DemonProfile d = demon;
        if (f == null || sweep == null || d == null) {
            return false; // M1's demon attacks all sweep a box (CombatDataTest)
        }
        long cue = Micros.ofTicks(s.cueTick());
        long impact = Micros.ofTicks(s.impactTick());
        long cancelTick = view.cancelTick(s.strike());
        long cancel = cancelTick == Long.MIN_VALUE ? CueTimeline.NONE : Micros.ofTicks(cancelTick);
        @Nullable
        CueSheet cell = switch (f.cue().type()) {
            case WHITE -> glint(view, s.strike(), cue, impact, cancel);
            case RED -> CueTimeline.shows(t, cue, impact, cancel) ? CueSheet.BURST : null;
            case PURPLE -> CueTimeline.shows(t, cue, impact, cancel) ? CueSheet.RING : null;
            case NONE -> null;
        };
        if (cell == null) {
            return false;
        }
        DrawnFrame a = drawn(s.attacker());
        if (a == null) {
            return false;
        }
        Sweep.Box box = sweep.box();
        c.cell = cell;
        c.frame = cell == CueSheet.SHATTER ? strip : 0;
        c.yaw = a.bodyYaw;
        if (cell == CueSheet.RING) {
            c.x = a.x; // its attacker's axis at its box's centre height
            c.y = a.y + box.cy();
            c.z = a.z;
            c.boxX = box.cx();
            c.boxZ = box.cz();
            c.boxHx = box.hx();
            c.boxHz = box.hz();
            c.body = d.body().halfWidth();
            c.progress = CueTimeline.progress(t, cue, impact);
        } else {
            arc.set(box, ArcInBox.defaultRoll(box), 0, 180, kfx.arcBase()).anchor(local);
            at(a, local.x(), local.y(), local.z(), c);
        }
        strength(view.incoming(s, self), c);
        return true;
    }

    /**
     * The white glint's cell at {@code t}, its strip frame into {@link #strip}; null when it doesn't show. A feint's
     * shatter starts at the later of its cancel tick and the first frame that knew of the cancel, stamped now if this
     * frame is that one (A6).
     */
    private @Nullable CueSheet glint(ClientView view, long strike, long cue, long impact, long cancel) {
        boolean feint = cancel != CueTimeline.NONE && view.feint(strike);
        long known = feint ? view.cancelKnown(strike, t) : CueTimeline.NONE;
        CueTimeline.Look look =
                CueTimeline.glint(t, cue, impact, cancel, feint, known, period, step, CueSheet.SHATTER.frames());
        strip = look == CueTimeline.Look.SHATTER
                ? CueTimeline.frame(t, CueTimeline.shatterStart(cancel, known), step)
                : 0;
        return switch (look) {
            case NONE -> null;
            case GLINT -> CueSheet.GLINT;
            case FLARE -> CueSheet.GLINT_FLARE;
            case SHATTER -> CueSheet.SHATTER;
        };
    }

    private boolean thread(ClientView view, int id, KfxMark.Cue c) {
        Track k = view.track(id);
        Outbox.MobState m = view.mob(id);
        DemonProfile d = demon;
        if (k == null || m == null || d == null || !CueTimeline.threadShows(t, k.threadOpened(), k.threadUntil())) {
            return false;
        }
        DrawnFrame a = drawn(id);
        if (a == null) {
            return false;
        }
        Sweep.Box neck = CueTimeline.neck(d.neck(), m.state());
        c.cell = CueSheet.NECK;
        c.frame = Math.floorMod(CueTimeline.frame(t, k.threadOpened(), step), CueSheet.NECK.frames());
        c.yaw = a.bodyYaw;
        c.boxHx = neck.hx();
        at(a, neck.cx(), neck.cy(), neck.cz(), c);
        strength(true, c);
        return true;
    }

    private boolean clash(ClientView.Clash k, KfxMark.Cue c) {
        DemonProfile d = demon;
        if (d == null || !CueTimeline.flashShows(t, k.flash(), kfx.flashMicros())) {
            return false;
        }
        DrawnFrame a = drawn(k.mob());
        DrawnFrame b = drawn(k.fighter());
        if (a == null || b == null) {
            return false;
        }
        c.cell = CueSheet.CLASH;
        c.frame = 0;
        c.x = (a.x + b.x) / 2;
        c.y = (a.y + b.y) / 2 + CHEST * (d.body().height() + fighterHeight) / 2;
        c.z = (a.z + b.z) / 2;
        strength(k.fighter() == self || k.mob() == self, c);
        return true;
    }

    /** Puts {@code c} at ({@code lx, ly, lz}) in drawn frame {@code a} (x right, y up, z forward, from the feet). */
    private void at(DrawnFrame a, double lx, double ly, double lz, KfxMark.Cue c) {
        frame.set(a.x, a.y, a.z, 0, a.bodyYaw, 0).toWorld(lx, ly, lz, world);
        c.x = world.x();
        c.y = world.y();
        c.z = world.z();
    }

    private void strength(boolean yours, KfxMark.Cue c) {
        this.yours = yours;
        c.alpha = yours ? 1 : kfx.bystanderAlpha();
        c.scale = yours ? 1 : kfx.bystanderScale();
    }

    /** Entity {@code id}'s drawn frame this frame, looked up once; null if the client lacks it. */
    private @Nullable DrawnFrame drawn(int id) {
        for (int i = 0; i < bodyCount; i++) {
            if (bodyIds[i] == id) {
                return bodyHas[i] ? bodyFrames[i] : null;
            }
        }
        if (bodyCount == bodyIds.length) {
            int n = bodyIds.length * 2;
            bodyIds = Arrays.copyOf(bodyIds, n);
            bodyHas = Arrays.copyOf(bodyHas, n);
            DrawnFrame[] more = frames(n);
            System.arraycopy(bodyFrames, 0, more, 0, bodyFrames.length);
            bodyFrames = more;
        }
        int i = bodyCount++;
        bodyIds[i] = id;
        bodyHas[i] = bodies.drawn(id, bodyFrames[i]);
        return bodyHas[i] ? bodyFrames[i] : null;
    }

    private void grow() {
        int n = kinds.length * 2;
        kinds = Arrays.copyOf(kinds, n);
        items = Arrays.copyOf(items, n);
        full = Arrays.copyOf(full, n);
        distSq = Arrays.copyOf(distSq, n);
        order = Arrays.copyOf(order, n);
    }

    private static DrawnFrame[] frames(int n) {
        DrawnFrame[] f = new DrawnFrame[n];
        for (int i = 0; i < n; i++) {
            f[i] = new DrawnFrame();
        }
        return f;
    }
}
