package dev.champ.kimetsu.client.render;

import com.mojang.logging.LogUtils;
import dev.champ.kimetsu.Kimetsu;
import dev.champ.kimetsu.client.ClientCombat;
import dev.champ.kimetsu.client.StrikeAnim;
import dev.champ.kimetsu.client.present.PresentClock;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.entity.M1Demon;
import mod.azure.azurelib.animation.AzAnimationTimer;
import mod.azure.azurelib.animation.AzAnimatorConfig;
import mod.azure.azurelib.animation.controller.AzAnimationController;
import mod.azure.azurelib.animation.controller.AzAnimationControllerContainer;
import mod.azure.azurelib.animation.dispatch.AzDispatchSide;
import mod.azure.azurelib.animation.dispatch.command.sequence.AzAnimationSequence;
import mod.azure.azurelib.animation.dispatch.command.sequence.AzAnimationSequenceBuilder;
import mod.azure.azurelib.animation.impl.AzEntityAnimator;
import mod.azure.azurelib.animation.play_behavior.AzPlayBehaviors;
import mod.azure.azurelib.model.AzBone;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * The AzureLib spike's animator (5d-3a Step 2, D-123): one per demon, as AzureLib keeps it. All its time is the demon's
 * model time ({@code ModelTime}, through {@link ModelTimer}), so a hold freezes the pose and a pause holds it with the
 * combat clock. Each strike plays {@code spike_swipe} once on its own controller, started where its strike frame
 * lands on the strike's impact: the stage's start offset is the clip time due at the trigger, so a late trigger
 * catches up on that clip alone and the shared timer is never shifted. It logs the model tick each strike frame
 * first shows at, for the spike's mark 2.
 */
final class SpikeAnimator extends AzEntityAnimator<M1Demon> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier ANIMATIONS =
            Identifier.fromNamespaceAndPath(Kimetsu.MOD_ID, "animations/entity/spike_demon.animation.json");
    private static final String CONTROLLER = "strike";
    private static final String CLIP = "spike_swipe";
    private static final double CLIP_IMPACT_TICKS = 10; // the clip's strike frame, its keyframe at 0.5 s
    private static final long NONE = Long.MIN_VALUE;

    private int entity = -1;
    private long playing = NONE; // the strike whose clip runs
    private long playingImpact;
    private boolean struck; // whether its strike frame has shown
    private double modelTicks;

    @Override
    public AzAnimationTimer createAzAnimationTimer(AzAnimatorConfig config) {
        return new ModelTimer(config, this);
    }

    @Override
    public void registerControllers(AzAnimationControllerContainer<M1Demon> controllers) {
        controllers.add(AzAnimationController.builder(this, CONTROLLER)
                .setTransitionLength(0)
                .build());
    }

    @Override
    public Identifier getAnimationLocation(M1Demon animatable) {
        return ANIMATIONS;
    }

    /**
     * Before AzureLib animates {@code demon} this frame: starts the clip of a strike once the clip time due is past the
     * trigger delay, and stops one whose strike went before its impact (a Feint). {@code anim} is filled here.
     */
    void drive(M1Demon demon, StrikeAnim anim) {
        entity = demon.getId();
        boolean in = ClientCombat.strikeAnim(entity, anim);
        long model = ClientCombat.modelMicros(entity);
        AzAnimationController<M1Demon> c = getAnimationControllerContainer().getOrNull(CONTROLLER);
        if (model == PresentClock.NOT_READY || c == null) {
            return;
        }
        double now = model / (double) Micros.PER_TICK;
        if (playing != NONE && now < playingImpact && (!in || anim.strike != playing)) {
            c.stateMachine().stop();
            LOGGER.info("Kimetsu spike: strike #{}'s clip stopped at model tick {} (its strike went)", playing, now);
            playing = NONE;
        }
        if (!in || anim.strike == playing) {
            return;
        }
        double due = CLIP_IMPACT_TICKS - (anim.impactTick - now); // the clip time whose strike frame meets the impact
        double delay = ClientCombat.spikeTriggerDelayMicros() / (double) Micros.PER_TICK;
        if (due < delay) {
            return;
        }
        c.stateMachine().stop(); // a clip still running starts over
        c.run(AzDispatchSide.CLIENT, clip(due));
        playing = anim.strike;
        playingImpact = anim.impactTick;
        struck = false;
        LOGGER.info(
                "Kimetsu spike: strike #{} clip started at model tick {} at clip tick {} (impact tick {})",
                playing,
                now,
                due,
                playingImpact);
    }

    @Override
    public void setCustomAnimations(M1Demon animatable, float partialTicks) {
        AzAnimationController<M1Demon> c = getAnimationControllerContainer().getOrNull(CONTROLLER);
        if (c != null && ClientCombat.holdTracing()) {
            AzBone arm = context().boneCache().getBakedModel().getBoneOrNull("right_arm");
            LOGGER.info(
                    "Kimetsu hold trace: model {} clip tick {} right_arm rotX {}",
                    modelTicks,
                    c.controllerTimer().getAdjustedTick(),
                    arm != null ? arm.getRotX() : Float.NaN);
        }
        if (c == null || playing == NONE || struck || c.controllerTimer().getAdjustedTick() < CLIP_IMPACT_TICKS) {
            return;
        }
        struck = true;
        LOGGER.info(
                "Kimetsu spike: strike #{} strike frame first shown at model tick {} (clip tick {}), impact tick {},"
                        + " off by {} ticks",
                playing,
                modelTicks,
                c.controllerTimer().getAdjustedTick(),
                playingImpact,
                modelTicks - playingImpact);
    }

    private static AzAnimationSequence clip(double startTicks) {
        return new AzAnimationSequenceBuilder()
                .queue(
                        CLIP,
                        p -> p.withPlayBehavior(AzPlayBehaviors.PLAY_ONCE)
                                .withTransitionLength(0)
                                .withStartTickOffset(startTicks))
                .build();
    }

    /**
     * AzureLib's clock for one demon: its model time in ticks, read fresh each call (the frame's one sample makes every
     * read in a frame agree). {@code tick()} does nothing: AzureLib's own accumulates the render clock and reads
     * {@code Minecraft.isPaused()}, while model time already holds through a pause and a hold.
     */
    static final class ModelTimer extends AzAnimationTimer {
        private final SpikeAnimator animator;

        ModelTimer(AzAnimatorConfig config, SpikeAnimator animator) {
            super(config);
            this.animator = animator;
        }

        @Override
        public void tick() {}

        @Override
        public double getAnimTime() {
            if (animator.entity >= 0) {
                long m = ClientCombat.modelMicros(animator.entity);
                if (m != PresentClock.NOT_READY) {
                    animator.modelTicks = m / (double) Micros.PER_TICK;
                }
            }
            return animator.modelTicks;
        }
    }
}
