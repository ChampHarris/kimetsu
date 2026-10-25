package dev.champ.kimetsu.entity;

import dev.champ.kimetsu.combat.Arena;
import dev.champ.kimetsu.combat.ArenaMember;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.clock.PulseSchedule;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.Fighter;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Combatant;
import dev.champ.kimetsu.core.machine.Effect;
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
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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
    // a push on it (Rule 7.10), moved as a client moves its player: its motion set at each push tick's start
    private double pushX;
    private double pushZ;
    private double pushPerTick;
    private double pushLift;
    private int pushTick = -1; // the push's next tick, or −1
    private int pushTicks;
    private @Nullable Arena arena;
    private @Nullable Arena chosen;
    private @Nullable Arena away; // the arena it left alive, to rejoin if the same object comes back (B-001: rare)
    private @Nullable Fighter fighter;
    // its size, from its profile on the server; synced, so a client's hitbox, picking and culling match
    private static final EntityDataAccessor<Float> WIDTH =
            SynchedEntityData.defineId(TestDummy.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT =
            SynchedEntityData.defineId(TestDummy.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> EYE =
            SynchedEntityData.defineId(TestDummy.class, EntityDataSerializers.FLOAT);
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

    /**
     * Gone from the level: it leaves the fight. Only out of tracking, its chunk's entities unload in the same tick and a
     * {@code noSave} dummy is gone (B-001); if the same object comes back, it rejoins when it ticks.
     */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        Arena a = arena;
        Entity.RemovalReason why = getRemovalReason();
        leave();
        if (a != null && !a.closed() && !dead && (why == null || !why.shouldDestroy())) {
            away = a;
        }
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        if (dead) {
            leave(); // a defeat takes a player out of the fight; a death another mod cancelled doesn't
        }
    }

    /**
     * Back in tracking after it left alive: it rejoins once the engine has let its old self go, its meters as they were
     * ({@link #join}), as a player who logs back in mid-fight would (spec Edge Cases, "Chunk unload or logout").
     */
    @Override
    public void tick() {
        if (pushTick >= 0 && !level().isClientSide()) {
            push();
        }
        super.tick();
        Arena a = away;
        if (level().isClientSide() || fighter == null || arena != null || dead || a == null) {
            return;
        }
        if (a.closed()) {
            away = null;
            discard();
        } else if (a.world().fighter(getId()) == null) {
            away = null;
            arena = a;
            a.add(this);
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
    public boolean canBeLeashed() {
        return false; // Rule 8.8: no leads on a combatant
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return false; // nor boats or carts
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false; // nor riders
    }

    @Override
    public int combatId() {
        return getId();
    }

    @Override
    public @Nullable Arena arena() {
        return arena;
    }

    /** It joins with a fresh fighter; one that had a fighter (a rebuilt world, or back in tracking) carries its meters. */
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
        Fighter old = fighter;
        if (old != null) {
            start.carry(old.timeline().live(), old.timeline().current(), tick - 1);
        }
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
        entityData.set(EYE, (float) p.body().eye());
        entityData.set(WIDTH, (float) (2 * p.body().halfWidth()));
        entityData.set(HEIGHT, (float) p.body().height());
        var health = getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(p.hp());
        }
        setHealth((float) p.hp());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(WIDTH, 0f);
        entityData.define(HEIGHT, 0f);
        entityData.define(EYE, 0f);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (WIDTH.equals(accessor) || HEIGHT.equals(accessor) || EYE.equals(accessor)) {
            float w = entityData.get(WIDTH);
            float h = entityData.get(HEIGHT);
            if (w > 0 && h > 0) {
                EntityDimensions d = EntityDimensions.scalable(w, h);
                float e = entityData.get(EYE);
                size = e > 0 ? d.withEyeHeight(e) : d;
                refreshDimensions();
            }
        }
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
        f.stagePose(t, getX(), getY(), getZ(), getYRot(), getXRot(), Double.NaN, 1); // unbounded: the server moves it
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
            when r.fighter() == getId() && r.damage() > 0 -> a.hurt(this, r.damage(), r.attacker());
            case Outbox.HoldEnded h when h.fighter() == getId() && h.damage() > 0 -> a.hurt(this, h.damage(), h.mob());
            case Outbox.WallSplat w when w.fighter() == getId() && w.damage() > 0 -> a.hurt(this, w.damage(), w.mob());
            case Outbox.PlayerEffect pe
            when pe.fighter() == getId() && pe.effect() instanceof Effect.Push p -> {
                pushX = p.dirX();
                pushZ = p.dirZ();
                pushPerTick = p.blocks() / p.ticks();
                pushLift = p.lift();
                pushTicks = p.ticks();
                pushTick = 0; // from its next tick, as a client with no latency lives it
            }
            default -> {}
        }
    }

    /**
     * A push tick: its horizontal motion set to the push's (and on the first tick its vertical to a launch's lift), so
     * vanilla's move carries it this tick, the level's collisions included, as a client's push carries its player; after
     * the last, its horizontal motion is zeroed.
     */
    private void push() {
        Vec3 v = getDeltaMovement();
        if (pushTick >= pushTicks) {
            setDeltaMovement(0, v.y, 0);
            pushTick = -1;
            return;
        }
        setDeltaMovement(pushX * pushPerTick, pushTick == 0 && pushLift > 0 ? pushLift : v.y, pushZ * pushPerTick);
        pushTick++;
    }
}
