package dev.champ.kimetsu.entity;

import dev.champ.kimetsu.combat.Arena;
import dev.champ.kimetsu.combat.ArenaMember;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.combat.DamagePath;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Input;
import dev.champ.kimetsu.core.machine.Timeline;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.data.SlayerProfile;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * {@code kimetsu:test_dummy} (Rule 1.6): a mob that plays a player's part in GameTests, which can't fight as a player
 * (a mock player has no connection). It runs a Fighter on the Slayer profile, Pulse included, fed each tick as a
 * client with no latency would feed it: the presses its test gives it, then its pose and an input frame. The engine's
 * hits on it are vanilla damage, as on a player.
 */
public final class TestDummy extends PathfinderMob implements ArenaMember {
    public static final String PROFILE = CombatDataBuilder.TEST_DUMMY;

    /** The frame time its frames claim: a client at 60 FPS. */
    private static final long FRAME_MICROS = 16_667;

    private final TreeMap<Long, List<Input>> presses = new TreeMap<>(); // the step each acts in → the presses
    private @Nullable Arena arena;
    private @Nullable Arena chosen;
    private @Nullable Fighter fighter;
    private @Nullable EntityDimensions size;

    public TestDummy(EntityType<? extends TestDummy> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes();
    }

    /** Before it's added to the level: fight in {@code a} rather than its level's default arena. */
    public void fightIn(Arena a) {
        chosen = a;
    }

    /** It presses {@code in}, which the engine takes in the step its stamp falls in. */
    public void press(Input in) {
        presses.computeIfAbsent(in.tick(), k -> new ArrayList<>()).add(in);
    }

    /** Its engine player, once it has joined an arena. */
    public @Nullable Fighter fighter() {
        return fighter;
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel level) {
            CombatServer combat = CombatServer.of(level.getServer());
            if (combat != null) {
                Arena a = chosen != null ? chosen : combat.arenaFor(level);
                arena = a;
                a.add(this);
            }
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        leave();
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (dead) {
            leave(); // a defeat takes a player out of the fight; a death another mod cancelled doesn't
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && fighter != null && arena == null && !dead) {
            discard(); // back from out of tracking, after it left the fight
        }
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    private void leave() {
        Arena a = arena;
        if (a != null) {
            a.world().remove(getId());
            a.remove(this);
            arena = null;
        }
    }

    @Override
    public int combatId() {
        return getId();
    }

    @Override
    public void join(Arena a, CombatData data, long tick) {
        SlayerProfile p = data.slayers().get(PROFILE);
        if (p == null) {
            throw new IllegalStateException(PROFILE + " isn't loaded"); // the builder requires it
        }
        CombatRules r = data.rules();
        Moveset moves = data.moveset(p);
        PulseSchedule pulse = new PulseSchedule(moves.style().beatTicks());
        Combatant start =
                new Combatant(r.machine(), r.timing(), p.breath(), p.posture(), r.rhythm(), moves, pulse, tick - 1);
        long tolerance = Micros.ofMillis(r.timing().toleranceMinMs());
        Fighter f = new Fighter(
                getId(),
                new Timeline(start, tick),
                new InputHorizon(r.timing()),
                pulse,
                p.body().halfWidth(),
                p.body().height(),
                p.body().eye(),
                InputHorizon.holdCapMicros(r.timing(), 0, tolerance));
        f.rttMicros(0);
        f.toleranceMicros(tolerance);
        a.world().add(f);
        fighter = f;
        size = EntityDimensions.scalable(
                        (float) (2 * p.body().halfWidth()), (float) p.body().height())
                .withEyeHeight((float) p.body().eye());
        refreshDimensions();
        var health = getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(p.hp());
        }
        setHealth((float) p.hp());
    }

    @Override
    protected EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        EntityDimensions s = size;
        return s != null ? s : super.getDefaultDimensions(pose);
    }

    @Override
    public void beforeStep(long n) {
        Fighter f = fighter;
        if (f == null) {
            return;
        }
        Iterator<Map.Entry<Long, List<Input>>> due =
                presses.headMap(n, true).entrySet().iterator();
        while (due.hasNext()) {
            for (Input in : due.next().getValue()) {
                f.timeline().receive(in);
            }
            due.remove();
        }
        long t = Micros.ofTicks(n);
        f.poses().put(t, getX(), getY(), getZ(), getYRot(), getXRot());
        f.horizon().frame(t, FRAME_MICROS, t);
    }

    @Override
    public void afterStep(long n) {}

    @Override
    public void onEvent(Outbox.Event e) {
        Arena a = arena;
        if (a == null) {
            return;
        }
        switch (e) {
            case Outbox.StrikeResult r
            when r.fighter() == getId() && r.damage() > 0 ->
                DamagePath.hurt(this, DamagePath.DEMON_CLAW, r.damage(), a.entity(r.attacker()));
            case Outbox.HoldEnded h
            when h.fighter() == getId() && h.damage() > 0 ->
                DamagePath.hurt(this, DamagePath.DEMON_CLAW, h.damage(), a.entity(h.mob()));
            default -> {}
        }
    }
}
