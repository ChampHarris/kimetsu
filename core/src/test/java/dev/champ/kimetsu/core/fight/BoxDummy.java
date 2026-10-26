package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.core.strike.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The demon's body until slice 4 builds it: a zombie-sized box with 300 Posture that keeps a log of what happened to
 * it. While a strike of its carries it (from the cue, Rule 7.13) it stands where the strike's displacement puts it;
 * otherwise where it's put, facing where it's turned (a demon would walk back; the box is simply there again).
 */
final class BoxDummy implements StrikeMob {
    final int id;
    final List<String> log = new ArrayList<>();
    double x;
    double y = 64;
    double z;
    double yaw;
    double posture = 300;
    private double start = 300;
    private double max = 300;
    double paid;
    int recoveryAdded;
    long stunnedFrom = Long.MIN_VALUE;
    long brokenAt = Long.MIN_VALUE;
    private final TreeMap<Long, Double> losses = new TreeMap<>();
    private int recoverTicks = -1; // after a break, its Posture refills this many ticks later; -1: never
    private @Nullable CombatWorld world; // whose strike paths it follows

    BoxDummy(int id) {
        this.id = id;
    }

    /** Its maximum Posture is {@code p}, and it starts full (a Perfect costs 7% of it). */
    BoxDummy maxPosture(double p) {
        max = p;
        return posture(p);
    }

    /** It starts with {@code p} Posture (its maximum stays 300, so a Perfect still costs it 21). */
    BoxDummy posture(double p) {
        posture = p;
        start = p;
        return this;
    }

    /** After each break it gets its Posture back {@code ticks} later, so it can break again. */
    BoxDummy recovering(int ticks) {
        recoverTicks = ticks;
        return this;
    }

    /** It follows its strikes' paths in {@code w}, as slice 4's demon will. */
    BoxDummy follow(CombatWorld w) {
        world = w;
        return this;
    }

    BoxDummy at(double px, double pz, double pyaw) {
        x = px;
        z = pz;
        yaw = pyaw;
        return this;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public long clashReactionMicros() {
        return 380_000; // the M1 demon's mean (ruling Q12)
    }

    @Override
    public double halfWidth() {
        return Fixtures.DEMON_HALF_WIDTH;
    }

    @Override
    public double height() {
        return Fixtures.DEMON_HEIGHT;
    }

    @Override
    public double eyeHeight() {
        return Fixtures.DEMON_EYE;
    }

    @Override
    public double maxPosture() {
        return max;
    }

    @Override
    public int knockbackTicks() {
        return Fixtures.demon().knockbackTicks(); // the M1 demon's, whose body it stands in for
    }

    @Override
    public void pose(long tick, Pose out) {
        if (world != null && world.strikePose(id, tick, out)) {
            return;
        }
        out.set(tick * 50_000L, x, y, z, yaw, 0);
    }

    @Override
    public HitResult hit(SwingHit h) {
        if (h.neck()) {
            return HitResult.NO_EFFECT; // a box has no neck
        }
        FrameData attack = h.attack();
        lose(h.posture(), h.tick());
        log.add(String.format(
                Locale.ROOT,
                "hit @%d by %d: %s w%d %.2f/%.2f",
                h.tick(),
                h.fighter(),
                attack.id(),
                h.window(),
                h.damage(),
                h.posture()));
        return HitResult.LANDED;
    }

    @Override
    public void paid(long strike, long tick, Verdict.Kind best, double posture, int recoveryTicks) {
        if (posture <= 0 && recoveryTicks <= 0) {
            return;
        }
        lose(posture, tick);
        paid += posture;
        recoveryAdded += recoveryTicks;
        log.add(String.format(Locale.ROOT, "paid %d: %.2f, +%d", strike, posture, recoveryTicks));
    }

    /**
     * Posture lost at {@code tick}. Losses can arrive out of tick order (a late sweep, a payment at an earlier impact),
     * so the break is the first tick at which the losses, taken in tick order, use up what it started with.
     */
    private void lose(double amount, long tick) {
        posture -= amount;
        losses.merge(tick, amount, Double::sum);
        double left = start;
        brokenAt = Long.MIN_VALUE;
        for (Map.Entry<Long, Double> e : losses.entrySet()) {
            left -= e.getValue();
            if (left <= 0) {
                brokenAt = e.getKey();
                break;
            }
        }
    }

    @Override
    public void interrupted(long strike, long atMicros, int stunTicks) {
        stunnedFrom = atMicros;
        log.add("interrupted " + strike + " @" + atMicros + " for " + stunTicks);
    }

    @Override
    public void cancelled(long strike, long tick) {
        log.add("cancelled " + strike);
    }

    @Override
    public long stoppedAt() {
        return brokenAt;
    }

    @Override
    public void endTick(long tick) {
        if (recoverTicks >= 0 && brokenAt != Long.MIN_VALUE && tick >= brokenAt + recoverTicks) {
            posture = start;
            losses.clear();
            brokenAt = Long.MIN_VALUE;
            log.add("recovered @" + tick);
        }
    }
}
