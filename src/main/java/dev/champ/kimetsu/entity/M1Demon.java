package dev.champ.kimetsu.entity;

import dev.champ.kimetsu.combat.Arena;
import dev.champ.kimetsu.combat.ArenaMember;
import dev.champ.kimetsu.combat.CombatServer;
import dev.champ.kimetsu.combat.DamagePath;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.demon.Demon;
import dev.champ.kimetsu.core.demon.DemonBrain;
import dev.champ.kimetsu.core.fight.Brain;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.hit.Pose;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.CombatRules;
import dev.champ.kimetsu.data.Profile;
import java.util.EnumSet;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import org.jspecify.annotations.Nullable;

/**
 * The M1 demon (Rules 8, 11; D-052's calls 1 and 3): its body and brain are the engine's, its entity a vanilla
 * stand-in. One writer per phase: between strings its walking goal moves the entity and the engine is told where it
 * went; while a strike carries it, its own movement is off ({@link #travel}) and the entity is put where the strike's
 * path says after each step. Its HP is the engine's alone: the entity's health mirrors it, vanilla damage becomes the
 * engine's outside damage (Rule 8.6, {@code DamagePath}), and it dies, with its death events, when the engine says it
 * died. No vehicle, portal or despawn moves or ends it behind the engine's back.
 */
public final class M1Demon extends PathfinderMob implements ArenaMember {
    public static final String PROFILE = CombatDataBuilder.M1_DEMON;

    /** The health it shows at 0 engine HP while it can still rise (a collapse): above 0, so vanilla keeps it. */
    private static final float ALIVE = 0.01f;

    /** Vanilla's strafe moves at this share of the movement speed at full sideways input ({@code MoveControl}). */
    private static final float VANILLA_STRAFE = 0.25f;

    private final Pose strikePose = new Pose();
    private @Nullable Arena arena;
    private @Nullable Arena chosen;
    private @Nullable Arena away; // the arena it left alive, to rejoin if the same object comes back (B-001: rare)
    private @Nullable Function<Demon, Brain> brains;
    private @Nullable Demon body;
    private @Nullable DemonBrain ownBrain;
    private Profile.@Nullable Movement movement;
    // its size, from its profile on the server; synced, so a client's hitbox, picking and culling match
    private static final EntityDataAccessor<Float> WIDTH =
            SynchedEntityData.defineId(M1Demon.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT =
            SynchedEntityData.defineId(M1Demon.class, EntityDataSerializers.FLOAT);
    private @Nullable EntityDimensions size;
    private boolean carried;
    private boolean dying;

    public M1Demon(EntityType<? extends M1Demon> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes();
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new KeepClose(this));
    }

    /** Before it's added to the level: fight in {@code a} rather than its level's default arena (a GameTest's). */
    public void fightIn(Arena a) {
        chosen = a;
    }

    /** Before it's added to the level: a brain of the test's making instead of the M1 demon's own. */
    public void useBrain(Function<Demon, Brain> make) {
        brains = make;
    }

    /** Its engine body, once it has joined an arena. */
    public @Nullable Demon body() {
        return body;
    }

    // ------------------------------------------------------------------------------------------------- joining

    /**
     * In the level: it joins its arena. Only if the level holds it, since a load the level refused (its UUID already
     * there, or a cancelled join event) is told it was added all the same ({@code processPendingLoads}), and joining
     * then would leave a body fighting with no entity.
     */
    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel level) {
            CombatServer combat = CombatServer.of(level.getServer());
            Entity held = level.getEntity(getUUID());
            if (combat != null && held != null && held.getId() == getId()) {
                Arena a = chosen != null ? chosen : combat.arenaFor(level);
                arena = a;
                a.add(this);
            }
        }
    }

    /**
     * Gone from the level. Killed or discarded, it's dead to the engine, as {@code /kill} is. Otherwise it went out of
     * tracking (its chunk hidden or unloaded), so it leaves the fight alive (slice 5c-1): its strikes still to land are
     * cancelled. Its chunk's entities unload in the same tick, saved first (B-001, D-063): it comes back from its save as
     * a new object, with its wounds ({@link #join}). Only if the chunk shows again before its entities finished unloading
     * does the same object come back, and then it rejoins when it ticks ({@link #tick}).
     */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        Arena a = arena;
        if (a == null) {
            return;
        }
        Entity.RemovalReason why = getRemovalReason();
        Demon b = body;
        if (why != null && why.shouldDestroy()) {
            if (b != null && !dying && !a.closed()) {
                b.outsideDamage(a.nextTick(), b.hp(), true);
            }
        } else if (!dying && !a.closed()) {
            a.world().leave(getId());
            away = a;
        }
        a.remove(this);
        arena = null;
        carried = false;
    }

    /**
     * Back in tracking after it left alive: it rejoins its arena once the engine has let its old self go, idle, its HP,
     * Hunger and Posture as they were (spec Edge Cases, "Chunk unload"; {@link #join} carries them). If that arena
     * closed meanwhile (its GameTest ended), there's nothing to come back to.
     */
    @Override
    public void tick() {
        super.tick();
        Arena a = away;
        if (level().isClientSide() || body == null || arena != null || dying || a == null) {
            return;
        }
        if (a.closed()) {
            away = null;
            discard();
        } else if (!a.world().hasMob(getId())) {
            away = null;
            arena = a;
            a.add(this);
        }
    }

    @Override
    public int combatId() {
        return getId();
    }

    @Override
    public @Nullable Arena arena() {
        return arena;
    }

    @Override
    public boolean hostile() {
        return true;
    }

    /**
     * It joins with a fresh engine body on {@code data}; one that had a body (a rebuilt world, or back from an unloaded
     * chunk) carries its wounds over, held to the profile's maxima, so a {@code /reload} no longer heals it; one loaded
     * from its save carries the wounds it was saved with (D-063), its timers frozen while it was away.
     */
    @Override
    public void join(Arena a, CombatData data, long tick) {
        DemonProfile profile = data.demons().get(PROFILE);
        Map<String, FrameData> attacks = data.demonAttacks().get(PROFILE);
        Profile.Movement walk = data.demonMovement().get(PROFILE);
        if (profile == null || attacks == null || walk == null) {
            throw new IllegalStateException(PROFILE + " isn't loaded"); // the builder requires it
        }
        CombatRules r = data.rules();
        Demon d = new Demon(
                getId(),
                profile,
                r.hits(),
                tick,
                r.machine().historyTicks(),
                getX(),
                getY(),
                getZ(),
                getYRot(),
                a.server().seed());
        Demon old = body;
        Demon.Wounds saved = getExistingDataOrNull(KimetsuAttachments.COMBATANT);
        if (old != null) {
            d.carry(old);
        } else if (saved != null) {
            d.carry(saved);
        }
        Function<Demon, Brain> make = brains;
        Brain brain;
        if (make != null) {
            brain = make.apply(d);
            ownBrain = null;
        } else {
            DemonBrain own = new DemonBrain(d, r.strikes(), attacks, a.server().seed());
            brain = own;
            ownBrain = own;
        }
        a.world().add(d, brain);
        body = d;
        movement = walk;
        carried = false;
        entityData.set(WIDTH, (float) (2 * profile.body().halfWidth()));
        entityData.set(HEIGHT, (float) profile.body().height());
        attribute(Attributes.MAX_HEALTH, profile.maxHp());
        attribute(Attributes.MOVEMENT_SPEED, walk.walkSpeed());
        attribute(Attributes.FOLLOW_RANGE, profile.senses().targetRange());
        setHealth((float) d.hp());
    }

    private void attribute(Holder<Attribute> a, double v) {
        AttributeInstance instance = getAttribute(a);
        if (instance != null) {
            instance.setBaseValue(v);
        }
    }

    /**
     * Saved (with its chunk, or as the server stops): its wounds go in {@code kimetsu:combatant} as they stand, set here
     * because vanilla writes attachments before {@code addAdditionalSaveData}. Not yet joined, it keeps the ones it was
     * loaded with.
     */
    @Override
    public void saveWithoutId(ValueOutput output) {
        Demon b = body;
        if (b != null && !level().isClientSide()) {
            setData(KimetsuAttachments.COMBATANT, b.wounds()); // synced to nobody: the attachment has no sync
        }
        super.saveWithoutId(output);
    }

    /**
     * A dead demon isn't written: loaded, its corpse would join the fight and rise. It's still unloaded with its chunk,
     * as vanilla's own {@code noSave} entities are ({@code shouldBeSaved} false would keep it in memory instead).
     */
    @Override
    public boolean saveAsPassenger(ValueOutput output) {
        return !dying && !isDeadOrDying() && super.saveAsPassenger(output);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(WIDTH, 0f);
        entityData.define(HEIGHT, 0f);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (WIDTH.equals(accessor) || HEIGHT.equals(accessor)) {
            float w = entityData.get(WIDTH);
            float h = entityData.get(HEIGHT);
            if (w > 0 && h > 0) {
                EntityDimensions d = EntityDimensions.scalable(w, h);
                size = d;
                refreshDimensions();
            }
        }
    }

    @Override
    protected EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        EntityDimensions s = size;
        return s != null ? s : super.getDefaultDimensions(pose);
    }

    // ------------------------------------------------------------------------------------------------- the step

    @Override
    public void beforeStep(long n) {
        Demon b = body;
        Arena a = arena;
        if (b == null || a == null) {
            return;
        }
        DemonBrain own = ownBrain;
        if (own != null) {
            own.pause(a.sprinting()); // /tick sprint: no strikes (Rule 1.1)
        }
        if (!carried) {
            b.moveTo(getX(), getY(), getZ(), getYRot());
        }
    }

    @Override
    public void afterStep(long n) {
        Demon b = body;
        Arena a = arena;
        if (b == null || a == null || dying) {
            return;
        }
        carried = a.world().strikePose(getId(), n, strikePose);
        if (carried) {
            float yaw = (float) strikePose.yaw();
            snapTo(strikePose.x(), strikePose.y(), strikePose.z(), yaw, getXRot());
            setYHeadRot(yaw);
            setYBodyRot(yaw);
            setDeltaMovement(Vec3.ZERO);
        }
        float hp = (float) Math.max(b.hp(), ALIVE);
        if (hp != getHealth()) {
            setHealth(hp); // also undoes a vanilla heal: the engine's HP is the only one
        }
    }

    /** The engine said it died: it leaves the fight and dies in the level, with its death events. */
    @Override
    public void onEvent(Outbox.Event e) {
        Arena a = arena;
        if (!(e instanceof Outbox.MobDied d) || d.mob() != getId() || dying || a == null) {
            return;
        }
        dying = true;
        carried = false; // its corpse falls where the strike left it
        a.remove(this);
        arena = null;
        if (level() instanceof ServerLevel level) {
            Entity killer = d.by() >= 0 ? level.getEntity(d.by()) : null;
            DamageSource cause = killer != null
                    ? level.damageSources().source(DamagePath.NICHIRIN, killer, killer)
                    : level.damageSources().genericKill();
            setHealth(0);
            die(cause);
        }
    }

    /** No movement of its own while a strike carries it: the strike's path is the one writer then. */
    @Override
    public void travel(Vec3 input) {
        if (!carried) {
            super.travel(input);
        }
    }

    /**
     * Vanilla damage at the tick being run, after vanilla's own cooldown (Rule 8.6): the engine's outside damage, never
     * below its floor, and death only from {@code killing} damage ({@code /kill}, the void). False when it fights in no
     * running arena, so vanilla's own damage applies.
     */
    public boolean outsideDamage(double amount, boolean killing) {
        Demon b = body;
        Arena a = arena;
        if (b == null || a == null || dying || a.closed()) {
            return false;
        }
        b.outsideDamage(a.nextTick(), Math.min(amount, b.profile().maxHp()), killing);
        return true;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return false; // a boat or a cart would be a second writer
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false; // Rule 8.8: nobody rides it
    }

    /**
     * Rule 8.8: no mounting, forced ({@code /ride}) or not: a combatant is never a rider or a mount. Players still ride.
     */
    public static void onMount(EntityMountEvent event) {
        if (event.isMounting()
                && (event.getEntityMounting() instanceof ArenaMember
                        || event.getEntityBeingMounted() instanceof ArenaMember)) {
            event.setCanceled(true);
        }
    }

    /**
     * Rule 12.5, the safety net for a death the engine didn't decide (another mod's damage; {@code /damage} with any
     * type, the mod's included, since the engine never deals its damage through the entity): unless its damage is
     * tagged {@code bypasses_invulnerability} ({@code /kill}, the void), it's cancelled, its health comes back at once
     * (a cancelled death leaves 0, which vanilla removes 20 ticks later) and the loss goes to the engine as outside
     * damage, which stops at its floor (Rule 8.6). Only on the server, while it fights in a running arena, and never the
     * engine's own death. Returns whether to cancel.
     */
    public boolean deathNet(DamageSource source) {
        Demon b = body;
        Arena a = arena;
        if (level().isClientSide()
                || b == null
                || a == null
                || dying
                || a.closed()
                || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        b.outsideDamage(a.nextTick(), b.hp(), false);
        setHealth((float) Math.max(b.hp(), ALIVE)); // the step after mirrors the engine's floored HP
        return true;
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false; // a copy in another dimension would come back at full HP
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false; // a despawn would be a death to the engine
    }

    /** The player its brain targets, if it's in this level. */
    @Nullable
    LivingEntity walkTarget() {
        Demon b = body;
        if (b == null || b.target() < 0) {
            return null;
        }
        return level().getEntity(b.target()) instanceof LivingEntity t ? t : null;
    }

    /**
     * Its one vanilla goal (D-052's call 3; SmartBrainLib waits for M3): beyond {@code close_max} blocks of its brain's
     * target it walks in to the middle of its band, then circles at {@code circle_share} of its walking speed, steering
     * back toward the middle, so a circle's drift can't carry it out of the band (whose outer edge is its commit range:
     * hovering there, it never commits). Nothing while a strike carries it.
     */
    private static final class KeepClose extends Goal {
        private final M1Demon demon;
        private boolean circling;

        KeepClose(M1Demon demon) {
            this.demon = demon;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return demon.walkTarget() != null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            demon.getNavigation().stop();
            circling = false;
        }

        @Override
        public void tick() {
            LivingEntity target = demon.walkTarget();
            Profile.Movement m = demon.movement;
            if (target == null || m == null || demon.carried) {
                demon.getNavigation().stop();
                return;
            }
            demon.getLookControl().setLookAt(target);
            double distance = demon.distanceTo(target);
            double middle = (m.closeMin() + m.closeMax()) / 2;
            double half = (m.closeMax() - m.closeMin()) / 2;
            if (distance > m.closeMax()) {
                circling = false;
            }
            if (!circling) {
                if (distance > middle) {
                    demon.getNavigation().moveTo(target, 1.0); // at its walking speed
                    return;
                }
                circling = true;
                demon.getNavigation().stop();
            }
            float forwards = half > 0 ? (float) Math.clamp((distance - middle) / half, -1.0, 1.0) : 0f;
            float sideways = (float) Math.min(1.0, m.circleShare() / VANILLA_STRAFE);
            demon.getMoveControl().strafe(forwards, sideways);
        }
    }
}
