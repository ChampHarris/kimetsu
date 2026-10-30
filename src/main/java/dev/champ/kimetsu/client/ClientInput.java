package dev.champ.kimetsu.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champ.kimetsu.client.present.HudFade;
import dev.champ.kimetsu.client.present.PresentClock;
import dev.champ.kimetsu.combat.LevelGround;
import dev.champ.kimetsu.config.KimetsuClientConfig;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FightRules;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.data.CombatDataBuilder;
import dev.champ.kimetsu.data.SlayerProfile;
import dev.champ.kimetsu.item.NichirinItem;
import dev.champ.kimetsu.net.NetCodec;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The combat keys into input frames (spec Netcode, "Input capture" and "Input frames"). A combat key's press or release
 * is read where the game first sees it ({@code MouseButton.Pre}, {@code Key}), stamped there on the combat clock, and
 * goes out in a frame at the start of the next client tick; a pose frame follows at each tick's end whose tick time,
 * the clock at the tick's start floored to a whole tick, has moved on. Pose frames flow in combat and near a hostile
 * combatant whatever is in hand (D-052: a demon strikes a player who put the blade away), and while a key the mod took is held,
 * a delta waits or the server hasn't heard the held state. A screen, the blade leaving the hand, lost focus or the
 * pause releases every held key, so none is left held on the server; a Breathe release the lost focus forces is a
 * hold, never an Inhale ({@link FocusRelease}). Deltas polled in different render frames go in different frames, each
 * with its own frame time for the half-frame correction; one at an exact time (a restamped release, the dev hooks')
 * goes alone, with none. A Step (the Step key, or a double-tap of left, back or right:
 * {@link StepInput}) goes out like any press, at the next tick's start, and the client dashes ({@link ForcedMotion})
 * from the tick the server would start it, as its own moves tell ({@link OwnMoves}), which also gate the radial; both
 * hear its presses as the server will judge them, in the server's order ({@link OwnPresses}: half the press's frame
 * early, never before what the client already acted on or sent, so a press after a hitch goes out with a shorter frame).
 * Presses of vanilla's Jump go out the same way and stay vanilla's (D-083; Sneak waits for the
 * juggle cut's drop, D-085). Breathe held past the radial's hold aims the radial while the server could open it
 * ({@link RadialStroke}): the camera holds still, the mouse's motion is the stroke, and a real key-up carries the slot it
 * points into; every release a screen, the pause, the blade or the lost focus forces carries none. Lock-on's key is the
 * client's own and never goes on the wire ({@link LockOn}, Rule 13.3): its press is taken under the combat keys' rule, a
 * tap or a hold acts on it, and each render frame the lock eases the look toward its target. With {@code guard_mode}
 * TOGGLE the guard key's taps go as a holder's presses and releases would ({@link GuardToggle}): the held state's guard
 * bit is the toggled guard, and an Attack, Pommel, Step, Breathe or Inhale press lets go of it at its own stamp first.
 * Main thread only.
 */
final class ClientInput {
    private static final int GUARDS = 16; // guard presses kept for the overlay's offsets
    private static final long DEV_RELEASE_MICROS = 20_000; // the dev hook lets go 20 ms after the later of both
    private static final long NONE = Long.MIN_VALUE;
    private static final double DEV_STROKE_DEG = 45; // the dev hook's flick: three times the dead zone

    private final ClientCombat combat;
    private final List<PlayerIntake.Delta> pending = new ArrayList<>();
    private int held; // the keys whose press the mod took, by wire bit
    private int heldSent; // the held state the last frame carried
    private long renderFrame; // counts render frames
    private long pendingFrame = -1; // the render frame the pending deltas were polled in
    private long pendingFrameMicros; // and its frame time
    private long seq;
    private long lastTickTime = Long.MIN_VALUE;
    private long tickStartNanos;
    private long frameMicros = 16_667;
    private long lastFrameNanos = Long.MIN_VALUE;
    private final long[] guards = new long[GUARDS];
    private int guardCount;
    private int guardHead;
    private boolean overlay;
    private final FocusRelease focus = new FocusRelease();
    private long breatheDownMicros = NONE; // the stamp of the last Breathe press taken
    private long lastCombatPress = NONE; // the stamp of the last combat press but Jump and Sneak (the HUD's fade)
    private long deferredUp = NONE; // a Breathe release the lost focus restamped: it goes once its stamp has passed

    // the guard toggle (D-104's Open Question 1): in TOGGLE the held state's guard bit is the toggled guard
    private final GuardToggle guardToggle = new GuardToggle();
    private boolean toggle; // guard_mode TOGGLE, changed only by guardMode, which lets go of a guard the old mode held

    // the Step (D-070): its keys and its dash; and what its own presses start on the server, which gates the dash,
    // the radial and the attack until the client predicts (step 5; D-107's review)
    private final StepInput steps = new StepInput();
    private final ForcedMotion dash = new ForcedMotion();
    private @Nullable FrameData dashStep; // the Step whose table is kept: a data reload brings a new one
    private double[] dashPerTick = new double[0];
    private StepInput.Direction stepDirection = StepInput.Direction.BACK; // the keys held at the last Step's press
    private long clientTick; // the client tick running, or the last one run
    private long lastPose = NONE; // the engine step its start stood at
    private final OwnPresses presses = new OwnPresses(); // its presses as the server will judge them (D-110's review)
    private final OwnMoves own = presses.moves;
    private OwnMoves.@Nullable Rules ownRules;

    // the radial (Rule 5.4, D-104): its stroke, and the render frame of a release that ended an aim, which stays frozen
    private final RadialStroke radial = presses.radial;
    private long releaseFrame = -1;
    private String @Nullable []
            labels; // the forms' names on the radial, resolved once per data generation and language
    private @Nullable CombatData labelsData;
    private String labelsLanguage = "";

    // lock-on (Rule 13.3, D-104): the client's own, no wire key
    private final LockOn lock = new LockOn();
    private @Nullable LocalPlayer lockPlayer; // the player object that locked: a respawn makes a new one, with its id
    private boolean turned; // this render frame's turn event ran, and eased the lock

    // the dev hook (spec Tools: a guard press at an exact combat time)
    private boolean armed;
    private long armOffsetMicros;
    private long armedStrike = Long.MIN_VALUE;
    private int armedAttacker;
    private long pressAt = Long.MIN_VALUE;
    private long releaseAt = Long.MIN_VALUE;
    private boolean jumpArmed; // and a Jump at an offset from the next push's first tick (D-083)
    private long jumpOffsetMicros;
    private long jumpAt = Long.MIN_VALUE;
    private int devSlot = -1; // and a radial flick toward a slot, released after a hold (5d-2)
    private long devHoldMicros;
    private boolean devOnBeat;
    private long devReleaseAt = NONE; // set once its press went
    private boolean devStroked;
    private PlayerIntake.@Nullable Key tapKey; // and a tap of a key at an offset from the next beat (5d-3b)
    private long tapOffsetMicros;
    private long tapAt = NONE; // its press's time, once set
    private boolean tapDown; // its press went

    ClientInput(ClientCombat combat, boolean overlay, boolean toggle) {
        this.combat = combat;
        this.overlay = overlay;
        this.toggle = toggle;
    }

    boolean overlay() {
        return overlay;
    }

    // ------------------------------------------------------------------------------------------------- capture

    void mouse(InputEvent.MouseButton.Pre e, long nanos) {
        if (e.getAction() == InputConstants.REPEAT) {
            return;
        }
        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(e.getButton());
        if (KimetsuKeys.lockOn(key) && lockKey(e.getAction() == InputConstants.PRESS, true, nanos)) {
            e.setCanceled(true); // vanilla's pick block never sees it
        }
        PlayerIntake.Key k = KimetsuKeys.combatKey(key);
        if (k != null && capture(k, key, e.getAction() == InputConstants.PRESS, true, nanos)) {
            e.setCanceled(true);
        }
        PlayerIntake.Key v =
                e.getAction() == InputConstants.PRESS ? vanillaKey(Minecraft.getInstance().options, key) : null;
        if (v != null) {
            vanillaPressed(v, nanos);
        }
    }

    void key(InputEvent.Key e, long nanos) {
        if (e.getAction() == InputConstants.REPEAT) {
            return;
        }
        InputConstants.Key key = InputConstants.getKey(e.getKeyEvent());
        Minecraft mc = Minecraft.getInstance();
        if (e.getAction() == InputConstants.PRESS
                && mc.gui.screen() == null
                && !key.equals(InputConstants.UNKNOWN)
                && KimetsuKeys.DEBUG_OVERLAY.getKey().equals(key)) {
            overlay = !overlay;
        }
        if (KimetsuKeys.lockOn(key)) {
            lockKey(e.getAction() == InputConstants.PRESS, false, nanos); // a key event can't be cancelled
        }
        PlayerIntake.Key k = KimetsuKeys.combatKey(key);
        if (k != null) {
            capture(k, key, e.getAction() == InputConstants.PRESS, false, nanos); // a key event can't be cancelled
        }
        StepInput.Key move = e.getAction() == InputConstants.PRESS ? movementKey(mc.options, key) : null;
        if (move != null) {
            tapped(move, nanos);
        }
        PlayerIntake.Key v = e.getAction() == InputConstants.PRESS ? vanillaKey(mc.options, key) : null;
        if (v != null) {
            vanillaPressed(v, nanos);
        }
    }

    /**
     * The wire's key for a press of vanilla's {@code key} (D-083), or null: Jump. Sneak, the juggle cut's, waits for
     * the cut's drop: without one it would only spend Breath (the netcode review; Champ, D-085).
     */
    private static PlayerIntake.@Nullable Key vanillaKey(Options o, InputConstants.Key key) {
        return !key.equals(InputConstants.UNKNOWN) && o.keyJump.getKey().equals(key) ? PlayerIntake.Key.JUMP : null;
    }

    /**
     * A press of vanilla's Jump (D-083): a Tech, a dodge's or a breakfall's, sent like any press under the combat
     * keys' rule, and still vanilla's jump. A tap: the machine has no release for it, so none goes and a held key keeps
     * no frames flowing. Sent even while vanilla's jump is let go ({@link #movementInput}): the server judges the
     * press, not the hop. Returns whether it was stamped.
     */
    private boolean vanillaPressed(PlayerIntake.Key k, long nanos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null
                || CaptureRules.vanillaKey(
                                NichirinItem.isBlade(p.getMainHandItem()),
                                mc.gui.screen() != null || mc.gui.overlay() != null)
                        == CaptureRules.Action.PASS
                || !take(k, true, nanos)) {
            return false;
        }
        held &= ~k.bit();
        return true;
    }

    /** Which of vanilla's movement keys {@code key} is bound to, or null. */
    private static StepInput.@Nullable Key movementKey(Options o, InputConstants.Key key) {
        if (key.equals(InputConstants.UNKNOWN)) {
            return null;
        }
        if (o.keyLeft.getKey().equals(key)) {
            return StepInput.Key.LEFT;
        }
        if (o.keyDown.getKey().equals(key)) {
            return StepInput.Key.BACK;
        }
        if (o.keyRight.getKey().equals(key)) {
            return StepInput.Key.RIGHT;
        }
        return o.keyUp.getKey().equals(key) ? StepInput.Key.FORWARD : null;
    }

    /**
     * A press of vanilla's movement key {@code move}: the second tap of left, back or right within the double-tap
     * window is a Step, the mod's under the same rule as its keyboard keys (the blade in hand, no screen), and the
     * movement key stays vanilla's too.
     */
    private void tapped(StepInput.Key move, long nanos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        CombatData d = combat.data();
        if (p == null || d == null) {
            return;
        }
        boolean screen = mc.gui.screen() != null || mc.gui.overlay() != null;
        if (CaptureRules.press(
                        NichirinItem.isBlade(p.getMainHandItem()),
                        screen,
                        false,
                        false,
                        mc.options.keyShift.isDown(),
                        false)
                == CaptureRules.Action.PASS) {
            steps.reset();
            return;
        }
        if (steps.tap(move, clientTick + 1, d.rules().machine().doubleTapTicks())
                && !dash.pushWaiting() // dropped, so the server never runs a Step its client won't show (both reviews)
                && take(PlayerIntake.Key.STEP, true, nanos)) {
            held &= ~PlayerIntake.Key.STEP.bit(); // a tap has no key-up to release it, and frames flow while it's held
        }
    }

    /**
     * Vanilla's attack or use triggered anyway: stopped, with no swing and no punch packet, while the blade is held,
     * except an attack that mines (D-055), whose held click keeps mining only while it's still away from fights; and
     * pick block on lock-on's key, the capture's backstop ({@link CaptureRules#stopPick}).
     */
    void interaction(InputEvent.InteractionKeyMappingTriggered e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        boolean blade = NichirinItem.isBlade(p.getMainHandItem());
        if (CaptureRules.stopVanilla(
                        blade,
                        e.isAttack() && !CaptureRules.mines(true, onBlock(), combat.fighting(p, Util.getNanos())),
                        e.isUseItem(),
                        Minecraft.getInstance().options.keyShift.isDown())
                || (e.isPickBlock()
                        && CaptureRules.stopPick(
                                blade, KimetsuKeys.lockOn(e.getKeyMapping().getKey())))) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    /** Whether the crosshair is on a block, which vanilla's attack would start breaking. */
    private static boolean onBlock() {
        HitResult hit = Minecraft.getInstance().hitResult;
        return hit != null && hit.getType() == HitResult.Type.BLOCK;
    }

    /** Returns whether vanilla must not see this event. */
    private boolean capture(PlayerIntake.Key k, InputConstants.Key key, boolean down, boolean mouseButton, long nanos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return false;
        }
        if (!down) {
            if (k == PlayerIntake.Key.GUARD && toggle) {
                guardToggle.keyUp(guardRaised(), d -> take(k, d, nanos)); // the pair's second tap lets go
                return false;
            }
            if (CaptureRules.release((held & k.bit()) != 0) == CaptureRules.Action.TAKE) {
                FocusRelease.Up up = k == PlayerIntake.Key.BREATHE
                        ? focus.breatheUp(mc.isWindowActive(), deferredUp != NONE)
                        : FocusRelease.Up.TAKE;
                switch (up) {
                    case TAKE -> {
                        if (k == PlayerIntake.Key.BREATHE) {
                            breatheUp(nanos);
                        } else {
                            take(k, false, nanos);
                        }
                    }
                    case DEFER -> deferRelease(nanos); // the key came up after the focus went: alt-tab, not a tap
                    case DROP -> {} // the restamped release already waiting goes in its place
                }
            }
            return false; // never cancel a release
        }
        if (k == PlayerIntake.Key.BREATHE && deferredUp != NONE) {
            return false; // its last release hasn't gone yet: nothing to press
        }
        boolean screen = mc.gui.screen() != null || mc.gui.overlay() != null;
        if (CaptureRules.mines(mc.options.keyAttack.getKey().equals(key), onBlock(), combat.fighting(p, nanos))) {
            return false; // vanilla's: it mines (D-055)
        }
        CaptureRules.Action a = CaptureRules.press(
                NichirinItem.isBlade(p.getMainHandItem()),
                screen,
                mouseButton,
                k == PlayerIntake.Key.GUARD,
                mc.options.keyShift.isDown(),
                mc.isWindowActive() && !mc.mouseHandler.isMouseGrabbed()); // grabMouse() needs the window active
        if (a == CaptureRules.Action.PASS) {
            return false;
        }
        if (k == PlayerIntake.Key.STEP && dash.pushWaiting()) {
            return a == CaptureRules.Action.TAKE_AND_CANCEL; // dropped, never sent: as a double-tap's (both reviews)
        }
        if (k == PlayerIntake.Key.GUARD && toggle) {
            tapGuard(nanos);
        } else {
            take(k, true, nanos);
        }
        return a == CaptureRules.Action.TAKE_AND_CANCEL;
    }

    /**
     * A tap of the guard key in TOGGLE ({@link GuardToggle}): a guard press, with the guard up a release and a press at
     * the tap's stamp, as a holder's quickest re-press. With no data there's no double-tap window, so no pair.
     */
    private void tapGuard(long nanos) {
        CombatData d = combat.data();
        guardToggle.keyDown(
                guardRaised(),
                clientTick + 1,
                d != null ? d.rules().machine().doubleTapTicks() : -1,
                down -> take(PlayerIntake.Key.GUARD, down, nanos));
    }

    /** Whether the guard is held, as the frames tell the server: in TOGGLE, whether the toggled guard is up. */
    private boolean guardRaised() {
        return (held & PlayerIntake.Key.GUARD.bit()) != 0;
    }

    /**
     * The guard mode changed (the CLIENT config, on the main thread): a guard the old mode holds is let go, so a toggled
     * guard never waits for a key-up that already came, nor a held key's guard for a tap.
     */
    void guardMode(boolean toggle, long nanos) {
        if (toggle == this.toggle) {
            return;
        }
        this.toggle = toggle;
        if (guardRaised()) {
            take(PlayerIntake.Key.GUARD, false, nanos);
        }
    }

    /**
     * A real Breathe key-up: the radial's slot, or −1 ({@link #slotAtRelease}), with the motion polled before it in this
     * frame (SDL runs its events in queue order, so the frame's turn hasn't taken it yet).
     */
    private void breatheUp(long nanos) {
        Minecraft mc = Minecraft.getInstance();
        ClientClock clock = combat.clock();
        if (!pending.isEmpty() && clock != null) {
            // what waits goes first: its horizon could end a step, which must run while the stroke still aims
            sendAhead(clock.micros(nanos));
        }
        double s = mc.options.sensitivity().get();
        int slot = slotAtRelease(
                nanos,
                clock != null ? clock.micros(nanos) : 0,
                frameMicros,
                RadialStroke.degrees(mc.mouseHandler.getXVelocity(), s),
                RadialStroke.degrees(mc.mouseHandler.getYVelocity(), s));
        take(PlayerIntake.Key.BREATHE, false, nanos, slot);
    }

    /**
     * The slot a Breathe release stamped {@code stampMicros} in a frame of {@code frameTimeMicros} fires, the radial
     * brought up to its stamp and the frame's unturned motion ({@code dx}, {@code dy} degrees) added to the stroke, or
     * −1; the stroke ends. A release that ended an aim keeps its frame's turn frozen: that frame's motion was the
     * stroke's, or came after the key-up.
     */
    private int slotAtRelease(long nanos, long stampMicros, long frameTimeMicros, double dx, double dy) {
        Moveset moves = combat.moves();
        CombatData d = combat.data();
        if (moves == null || d == null) {
            radial.cancel();
            return -1;
        }
        if (aimAt(nanos, stampMicros, frameTimeMicros)) {
            releaseFrame = renderFrame;
        }
        return radial.release(
                dx, dy, moves.style().radial().size(), d.rules().machine().radialFlickDeg());
    }

    /** Whether the radial aims at the client's {@code nanos}, a render frame's moment ({@link #aimAt}). */
    private boolean aim(long nanos) {
        ClientClock clock = combat.clock();
        return clock != null && aimAt(nanos, clock.micros(nanos), frameMicros);
    }

    /**
     * Whether the radial aims for a press or release stamped {@code stampMicros} in a frame of {@code frameTimeMicros},
     * at the client's {@code nanos} ({@link OwnPresses#aim}): the server's rule for opening it as the client can see it,
     * from its own guard, attack and Step ({@link OwnMoves#blocked}), and from the last snapshot in combat (out of
     * combat one is stale: a death, a respawn; none comes until frames flow). Nothing aims without a clock or data.
     * {@link OwnMoves} hears the answer: while it aims, the presses wait in the buffer.
     */
    private boolean aimAt(long nanos, long stampMicros, long frameTimeMicros) {
        ClientClock clock = combat.clock();
        OwnMoves.Rules r = ownRules();
        if (clock == null || !clock.inputOpen() || r == null) {
            return false;
        }
        return presses.aim(stampMicros, frameTimeMicros, r, combat.view().snapshot(), inCombat(nanos));
    }

    /** The last snapshot, in combat; null out of it (D-107's craft call b). */
    private NetMessage.@Nullable Snapshot inCombat(long nanos) {
        return combat.inCombat(nanos) ? combat.view().snapshot() : null;
    }

    /**
     * {@code CalculatePlayerTurnEvent}, once a render frame as the mouse turns the camera (only while it's grabbed),
     * after the frame's client ticks and before it's drawn: lock-on eases the look ({@link #easeLock}), and while the
     * radial aims, the frame's motion is its stroke ({@code getXVelocity}/{@code getYVelocity} are this frame's pixel
     * deltas, not velocities) and the mouse turns nothing, at vanilla's exact zero with the cinematic camera off, so its
     * smoother keeps no remainder to play out after the hold; the lock still turns it, so a flicked form fires at the
     * target. A release's frame stays frozen too ({@link #slotAtRelease}).
     */
    void turn(CalculatePlayerTurnEvent e, long nanos) {
        turned = true;
        easeLock(); // the mouse turns the look on top of it, so it still aims
        boolean aiming = aim(nanos);
        if (aiming) {
            MouseHandler mouse = Minecraft.getInstance().mouseHandler;
            double s = e.getMouseSensitivity();
            radial.move(RadialStroke.degrees(mouse.getXVelocity(), s), RadialStroke.degrees(mouse.getYVelocity(), s));
        }
        if (aiming || releaseFrame == renderFrame) {
            e.setMouseSensitivity(RadialStroke.FROZEN_SENSITIVITY);
            e.setCinematicCameraEnabled(false);
        }
    }

    /**
     * Fills {@code out} with the radial as it's drawn at combat time {@code nowMicros} (the frame's own time,
     * {@code PresentClock.now()}): false when it isn't, or before the clock is set (Rule 5.4: held to
     * {@code radial_reveal_ms} while it aims). It reads the aim the frame's start, its turn and its presses left:
     * drawing changes nothing (D-110's review).
     */
    boolean radialMark(RadialMark out, long nowMicros) {
        CombatData d = combat.data();
        Moveset moves = combat.moves();
        if (nowMicros == PresentClock.NOT_READY || d == null || moves == null || !radial.aiming()) {
            return false;
        }
        MachineRules m = d.rules().machine();
        long now = OwnPresses.judged(nowMicros, frameMicros, d.rules().timing());
        if (!radial.drawn(now, Micros.ofMillis(m.radialRevealMs()))) {
            return false;
        }
        out.slots = moves.style().radial().size();
        out.selected = radial.selected(out.slots, m.radialFlickDeg());
        out.labels = labels(d, moves);
        return true;
    }

    /** The radial's form names, looked up again when the data or the language changes (a lang key: form.ns.path). */
    @SuppressWarnings("ReferenceEquality") // the very data instance: a new generation is a new one, equal or not
    private String[] labels(CombatData d, Moveset moves) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        String[] l = labels;
        if (l != null && d == labelsData && language.equals(labelsLanguage)) {
            return l;
        }
        List<String> ids = moves.style().radial();
        l = new String[ids.size()];
        for (int i = 0; i < l.length; i++) {
            l[i] = I18n.get(Util.makeDescriptionId("form", Identifier.parse(ids.get(i))));
        }
        labels = l;
        labelsData = d;
        labelsLanguage = language;
        return l;
    }

    /** The radial's stroke, for the overlay's line. */
    RadialStroke radial() {
        return radial;
    }

    /**
     * The Step its own moves started ({@link OwnMoves#takeStep}), at once or from the buffer: the dash from the movement
     * keys held at its press (none: a backstep), from the client tick that stands at the Step's first engine step, as
     * near as client tick {@code nextTick}, the first it can run in, standing at {@code nextPose} tells.
     */
    private void startStep(OwnMoves.Rules r, long nextTick, long nextPose) {
        long at = own.takeStep();
        if (at != NONE) {
            dash.press(
                    dashTable(r.moves().step()),
                    stepDirection,
                    nextTick,
                    nextPose == NONE ? nextTick : nextTick + Math.max(0, at - nextPose),
                    r.rules().machine().bufferTicks());
        }
    }

    /**
     * The rules {@link OwnMoves} reads, kept until the data, the moveset or the assist level changes; null before the
     * data has come.
     */
    @SuppressWarnings("ReferenceEquality") // the very instances: a new generation is a new one, equal or not
    private OwnMoves.@Nullable Rules ownRules() {
        CombatData d = combat.data();
        Moveset moves = combat.moves();
        SlayerProfile slayer = d != null ? d.slayers().get(CombatDataBuilder.SLAYER) : null;
        if (d == null || moves == null || slayer == null) {
            return null;
        }
        int assist = combat.view().assist();
        OwnMoves.Rules r = ownRules;
        if (r == null
                || r.moves() != moves
                || r.rules() != d.rules()
                || r.breath() != slayer.breath()
                || r.assist() != assist) {
            r = OwnMoves.Rules.of(moves, d.rules(), slayer.breath(), assist);
            ownRules = r;
        }
        return r;
    }

    /** The Step's displacement in each of its ticks, from its frame data. */
    private double[] dashTable(FrameData step) {
        if (!step.equals(dashStep)) {
            double[] table = new double[step.total()];
            for (FrameData.Displacement d : step.displacement()) {
                double blocks = Math.sqrt(d.forward() * d.forward() + d.right() * d.right() + d.up() * d.up());
                for (int k = d.from(); k <= d.to(); k++) {
                    table[step.start(d.phase()) + k] += blocks;
                }
            }
            dashStep = step;
            dashPerTick = table;
        }
        return dashPerTick;
    }

    /**
     * The server pushed the player (Rule 7.10): its client moves it from the client tick whose pose frame carries the
     * push's first tick, or the next client tick if that's past, never from the impact it lived long ago
     * ({@link ForcedMotion#push}); it waits no longer than the longest hold and two ticks after the tick it arrives
     * before (the server starts it at most the longest hold after the decision's next tick). It ends any dash.
     */
    void push(Effect.Push p) {
        CombatData d = combat.data();
        int wait = d == null
                ? 0
                : Math.toIntExact(
                                Math.ceilDiv(Micros.ofMillis(d.rules().timing().holdCapMaxMs()), Micros.PER_TICK))
                        + 2;
        dash.push(p.dirX(), p.dirZ(), p.blocks() / p.ticks(), p.ticks(), p.lift(), clientTick + 1, p.fromTick(), wait);
        if (jumpArmed && jumpAt == Long.MIN_VALUE) {
            jumpAt = Micros.ofTicks(p.fromTick()) + jumpOffsetMicros; // the dev hook's Jump
        }
    }

    /**
     * The client tick's start, after its presses went: the dash's or the push's motion for this tick
     * ({@link ForcedMotion}), set before the player's own tick moves it; the vertical only for a launch's lift. Its
     * combat tick is the one this tick's pose frame will carry ({@link #tickEnd}). Whether the last tick's move met a
     * wall as the server counts it ({@link #metWall}) decides whether a push's stop tick holds the jump
     * ({@link ForcedMotion#lastMoveBlocked}).
     */
    void dashTick(LocalPlayer p) {
        ClientClock clock = combat.clock();
        long tick = clock != null && clock.inputOpen()
                ? poseTick(clock)
                : Long.MAX_VALUE; // no pose frame carries a tick to wait for
        dash.lastMoveBlocked(metWall(p));
        ForcedMotion.Phase ph = dash.tick(clientTick, tick, p.getYRot());
        if (ph == ForcedMotion.Phase.IDLE) {
            return;
        }
        Vec3 v = p.getDeltaMovement();
        if (ph == ForcedMotion.Phase.MOVE) {
            p.setDeltaMovement(dash.dx, Double.isNaN(dash.dy) ? v.y : dash.dy, dash.dz);
        } else {
            p.setDeltaMovement(0, v.y, 0);
        }
    }

    /**
     * Whether the player's pose meets a wall along the running push as the server counts it (D-086, {@code PushWall}):
     * the Slayer's box the server judges, swept through this client's own level, on the push's heading and the rules'
     * {@code wall_keep_share} ({@link ForcedMotion#metWall}). A graze, which sets vanilla's horizontal collision too,
     * doesn't. Asked on a push's stop tick only; with no data the server has sent no push.
     */
    private boolean metWall(LocalPlayer p) {
        CombatData d = combat.data();
        SlayerProfile slayer = d != null ? d.slayers().get(CombatDataBuilder.SLAYER) : null;
        if (!dash.stopsPush() || d == null || slayer == null) {
            return false;
        }
        return dash.metWall(
                new LevelGround(p.level()),
                p.getX(),
                p.getY(),
                p.getZ(),
                slayer.body().halfWidth(),
                slayer.body().height(),
                d.rules().strikes().wallKeepShare());
    }

    /**
     * {@code MovementInputUpdateEvent}, inside the player's tick: during a dash or a push its movement keys, jump and
     * sprint are let go (Sneak is kept) and its move vector zeroed, so vanilla adds no push and no sprint-jump to it;
     * while a push waits, or stops after a wall, or the server shows a KNOCKDOWN in combat, its jump alone is
     * ({@link ForcedMotion#holdsJump}). Vanilla reads the input after this event, auto-jump aside (off by default).
     */
    void movementInput(MovementInputUpdateEvent e) {
        net.minecraft.client.player.ClientInput in = e.getInput();
        net.minecraft.world.entity.player.Input k = in.keyPresses;
        if (dash.moving()) {
            in.keyPresses =
                    new net.minecraft.world.entity.player.Input(false, false, false, false, false, k.shift(), false);
            in.moveVector = Vec2.ZERO;
            return;
        }
        NetMessage.Snapshot s = combat.view().snapshot();
        if (k.jump() && dash.holdsJump(s != null ? s.state() : null, combat.inCombat(Util.getNanos()))) {
            in.keyPresses = new net.minecraft.world.entity.player.Input(
                    k.forward(), k.backward(), k.left(), k.right(), false, k.shift(), k.sprint());
        }
    }

    /**
     * The level went (a dimension change, a respawn): no dash carries over, nor a first tap, nor the dev hook's Jump, nor
     * the lock.
     */
    void leftLevel() {
        dash.cancel();
        steps.reset();
        radial.cancel(); // the release then carries no slot
        own.reset();
        lock.drop();
        jumpAt = Long.MIN_VALUE; // still armed: the next push sets it again (code review)
    }

    /**
     * Breathe came up as the focus went (alt-tab): the release goes stamped no earlier than its press plus the radial's
     * hold, once that time has passed (no press is later than its frame), its bit held meanwhile so no frame's held
     * state releases it sooner.
     */
    private void deferRelease(long nanos) {
        radial.cancel(); // it goes with no slot
        ClientClock clock = combat.clock();
        CombatData d = combat.data();
        if (clock == null || !clock.inputOpen() || d == null) {
            take(PlayerIntake.Key.BREATHE, false, nanos);
            return;
        }
        long hold = Micros.ofMillis(d.rules().machine().radialHoldMs());
        deferredUp = FocusRelease.restamp(clock.micros(nanos), breatheDownMicros, hold);
        held |= PlayerIntake.Key.BREATHE.bit();
    }

    /** The deferred Breathe release goes once its stamp has passed; with the clock closed, the held state tells. */
    private void flushDeferred(long nanos) {
        if (deferredUp == NONE) {
            return;
        }
        ClientClock clock = combat.clock();
        if (clock == null || !clock.inputOpen()) {
            deferredUp = NONE;
            held &= ~PlayerIntake.Key.BREATHE.bit();
            return;
        }
        if (clock.micros(nanos) >= deferredUp) {
            long at = deferredUp;
            deferredUp = NONE;
            delta(PlayerIntake.Key.BREATHE, false, at, -1, 0);
            send(nanos, InputHorizon.NO_TICK, 0); // no frame to correct for: its time is the rule's
        }
    }

    /** The tick's lost-focus edge: a Breathe release still waiting to go, from a key-up before the flag flipped. */
    private void restampPending(long nanos) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            PlayerIntake.Delta d = pending.get(i);
            if (d.key() == PlayerIntake.Key.BREATHE && !d.down()) {
                pending.remove(i);
                if (d.slot() >= 0) {
                    presses.withdraw(); // its form won't fire: the restamped release carries no slot
                }
                deferRelease(nanos);
                return;
            }
        }
    }

    /**
     * A press or release taken at the client's {@code nanos}; a press while the clock can't stamp it goes nowhere.
     * Returns whether it was stamped.
     */
    private boolean take(PlayerIntake.Key k, boolean down, long nanos) {
        return take(k, down, nanos, -1);
    }

    /** As {@link #take(PlayerIntake.Key, boolean, long)}; a Breathe release carries radial slot {@code slot} (−1: none). */
    private boolean take(PlayerIntake.Key k, boolean down, long nanos, int slot) {
        ClientClock clock = combat.clock();
        if (clock == null || !clock.inputOpen()) {
            if (!down) {
                held &= ~k.bit(); // the frame's held state will tell the server
                if (k == PlayerIntake.Key.BREATHE) {
                    radial.cancel();
                    own.radial(false, combat.view().snapshot());
                } else if (k == PlayerIntake.Key.GUARD) {
                    own.guardUp();
                }
            }
            return false;
        }
        delta(k, down, clock.micros(nanos), slot, frameMicros);
        return true;
    }

    /**
     * A press or release at {@code stampMicros} polled in a frame of {@code frameTimeMicros} (0: an exact time, no frame
     * to correct for). It goes out with that frame time, or a shorter one where half of it would judge the press before
     * what the client already acted on or sent ({@link OwnPresses#frameTime}), which the server's half-frame correction
     * and its own moves judge it by alike.
     */
    private void delta(PlayerIntake.Key k, boolean down, long stampMicros, int slot, long frameTimeMicros) {
        if (down && toggle) {
            // the toggled guard drops at the action's stamp, sent first so it acts first, as a holder's let-go would
            GuardToggle.pressed(
                    k, guardRaised(), g -> delta(PlayerIntake.Key.GUARD, g, stampMicros, -1, frameTimeMicros));
        }
        if (!pending.isEmpty() && pendingFrame != renderFrame) {
            sendAhead(stampMicros); // another render frame
        }
        long f = frameTime(stampMicros, frameTimeMicros);
        if (!pending.isEmpty() && pendingFrameMicros != f) {
            sendAhead(stampMicros); // another frame time
            f = frameTime(stampMicros, frameTimeMicros); // the frame that went is the server's horizon now
        }
        if (pending.isEmpty()) {
            pendingFrame = renderFrame;
            pendingFrameMicros = f;
        }
        if (down) {
            held |= k.bit();
            if (k == PlayerIntake.Key.BREATHE) {
                breatheDownMicros = stampMicros;
            }
            if (HudFade.counts(k)) {
                lastCombatPress = Math.max(lastCombatPress, stampMicros); // a restamped press may come later
            }
            pending.add(PlayerIntake.Delta.press(k, stampMicros));
            if (k == PlayerIntake.Key.GUARD) {
                guards[guardHead] = stampMicros;
                guardHead = (guardHead + 1) % GUARDS;
                guardCount = Math.min(guardCount + 1, GUARDS);
            }
        } else {
            held &= ~k.bit();
            pending.add(PlayerIntake.Delta.release(k, stampMicros, slot));
        }
        own(k, down, stampMicros, f, slot);
    }

    /** The frame time a press stamped {@code stampMicros} in a frame of {@code frameTimeMicros} goes out with. */
    private long frameTime(long stampMicros, long frameTimeMicros) {
        OwnMoves.Rules r = ownRules();
        return r != null
                ? presses.frameTime(stampMicros, frameTimeMicros, r.rules().timing())
                : frameTimeMicros;
    }

    /**
     * What a press or release the server will see starts of its own ({@link OwnPresses}), acting in the engine step the
     * server judges it into, by the frame time it goes out with ({@code frameTimeMicros}, the radial and its moves
     * brought up to it in the server's order); a Step dashes from its start, at once or from the buffer, along the
     * movement keys held now.
     */
    private void own(PlayerIntake.Key k, boolean down, long stampMicros, long frameTimeMicros, int slot) {
        OwnMoves.Rules r = ownRules();
        if (r == null) {
            if (!down && k == PlayerIntake.Key.GUARD) {
                own.guardUp();
            } else if (!down && k == PlayerIntake.Key.BREATHE) {
                radial.cancel();
            }
            return;
        }
        boolean step = down && k == PlayerIntake.Key.STEP;
        if (step) {
            Options o = Minecraft.getInstance().options;
            stepDirection = StepInput.Direction.held(
                    o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown());
        }
        presses.key(
                k, down, stampMicros, frameTimeMicros, slot, r, combat.view().snapshot(), inCombat(Util.getNanos()));
        if (step) {
            startStep(r, clientTick + 1, lastPose == NONE ? NONE : lastPose + 1);
        }
    }

    // ------------------------------------------------------------------------------------------------- frames

    /**
     * {@code RenderFrameEvent.Pre}: the frame time the next presses carry, the dev hook's presses when due, the radial's
     * aim, which the turn event updates too but only while the mouse is grabbed, and lock-on's frame.
     */
    void frameStart(long nanos) {
        if (lastFrameNanos != Long.MIN_VALUE) {
            frameMicros = (nanos - lastFrameNanos) / 1000;
        }
        lastFrameNanos = nanos;
        renderFrame++;
        devHook(nanos);
        devRadial(nanos);
        devTap(nanos);
        flushDeferred(nanos);
        aim(nanos);
        lockFrame(nanos);
    }

    /** {@code ClientTickEvent.Pre}: the releases a screen or a lost blade forces, then the presses since the last tick. */
    void tickStart(long nanos) {
        tickStartNanos = nanos;
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        boolean lostFocus = focus.tick(mc.isWindowActive()); // alt-tab; a window never focused (a test) holds keys
        if (lostFocus) {
            restampPending(nanos);
        }
        if (held != 0
                && (p == null
                        || mc.gui.screen() != null
                        || mc.gui.overlay() != null
                        || !NichirinItem.isBlade(p.getMainHandItem())
                        || lostFocus
                        || mc.isPaused())) {
            ClientClock clock = combat.clock();
            for (PlayerIntake.Key k : PlayerIntake.Key.values()) {
                if ((held & k.bit()) == 0) {
                    continue;
                }
                if (k == PlayerIntake.Key.BREATHE && lostFocus) {
                    if (deferredUp == NONE) {
                        deferRelease(nanos); // still down as the focus went: alt-tab
                    }
                } else {
                    take(k, false, nanos);
                }
            }
            held = deferredUp != NONE ? PlayerIntake.Key.BREATHE.bit() : 0;
            if (clock == null || !clock.inputOpen()) {
                pending.clear(); // nothing can be stamped; the next frame's held state releases them
            }
        }
        lockTick(nanos);
        ownTick(nanos);
        if (!pending.isEmpty()) {
            send(nanos, InputHorizon.NO_TICK, pendingFrameMicros);
        }
        flushDeferred(nanos);
    }

    /**
     * The tick's start for {@link OwnMoves}: every engine step up to the one its pose frame carries ({@link #poseTick})
     * has ended, as the server runs them ({@link OwnPresses#tick}): at each the radial opens or closes first (an attack
     * that ends there lets it open), then what its own moves held back runs, a Step's dash from this tick.
     */
    private void ownTick(long nanos) {
        ClientClock clock = combat.clock();
        OwnMoves.Rules r = ownRules();
        if (clock == null || !clock.inputOpen() || r == null) {
            return;
        }
        lastPose = poseTick(clock);
        presses.tick(lastPose, r, combat.view().snapshot(), inCombat(nanos));
        startStep(r, clientTick, lastPose);
    }

    /** {@code ClientTickEvent.Post}, after vanilla's movement and tick-end packets: the tick's pose frame. */
    void tickEnd(long nanos) {
        ClientClock clock = combat.clock();
        if (clock == null || !clock.inputOpen() || !flowing(nanos)) {
            return;
        }
        long tickTime = poseTick(clock) * Micros.PER_TICK;
        if (tickTime <= lastTickTime) {
            return; // frozen, or the clock stood still: no fresh pose
        }
        lastTickTime = tickTime;
        send(nanos, tickTime, pending.isEmpty() ? frameMicros : pendingFrameMicros);
    }

    /**
     * The combat tick this client tick's pose frame carries: the tick its start fell in on the combat clock. A waiting
     * push starts by the same number ({@link #dashTick}), so its first push pose is the one stamped with its first tick
     * (the code review).
     */
    private long poseTick(ClientClock clock) {
        return Math.floorDiv(clock.micros(tickStartNanos), Micros.PER_TICK);
    }

    /**
     * {@code ClockEpoch}, before the clock takes it: what's pending was stamped on the old timeline, so it goes now with
     * the old epoch, for the server to keep or drop as Rule 1.8 says (code review).
     */
    void beforeEpoch(long nanos) {
        if (!pending.isEmpty()) {
            send(nanos, InputHorizon.NO_TICK, pendingFrameMicros);
        }
    }

    /**
     * {@code ClockEpoch}: tick times start again behind the old timeline's, so its own Steps, in the old one's ticks,
     * would all look newer than any snapshot and leave the dash no Breath (code review).
     */
    void onEpoch() {
        lastTickTime = Long.MIN_VALUE;
        lastPose = NONE;
        lastCombatPress = NONE; // on the old timeline, which may run ahead of the new one
        presses.epoch();
        jumpAt = Long.MIN_VALUE; // the dev hook's, on the old timeline
        tapAt = NONE; // and the tap's: unpressed, it's set again; pressed, its release goes at once
        radial.cancel(); // its press is on the old timeline: the release carries no slot
    }

    private boolean flowing(long nanos) {
        return held != 0 || held != heldSent || !pending.isEmpty() || engaged(nanos);
    }

    /**
     * In combat, or a hostile combatant within the engagement range (D-052), whatever is in hand: the server needs the
     * pose either way, and a player in combat sends frames even out of range (the spec's Input frames), which brings
     * the snapshots that keep its flag current (code review).
     */
    private boolean engaged(long nanos) {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && combat.fighting(p, nanos);
    }

    private void send(long nanos, long tickTime, long frameTime) {
        ClientClock clock = combat.clock();
        if (clock != null) {
            send(nanos, clock.micros(nanos), tickTime, frameTime);
        }
    }

    /**
     * What waits goes now, ahead of a press or release stamped {@code pressMicros} that waits for the next frame: stamped
     * no later than it ({@link OwnPresses#earlyStamp}), which the server's bracket needs to judge it at its stamp
     * however long it waits, up to the stall bracket (300 ms).
     */
    private void sendAhead(long pressMicros) {
        ClientClock clock = combat.clock();
        if (clock == null) {
            return;
        }
        long newest = Long.MIN_VALUE;
        for (int i = 0; i < pending.size(); i++) {
            newest = Math.max(newest, pending.get(i).stampMicros());
        }
        long nanos = Util.getNanos();
        send(
                nanos,
                OwnPresses.earlyStamp(newest, clock.micros(nanos), pressMicros),
                InputHorizon.NO_TICK,
                pendingFrameMicros);
    }

    /** A frame stamped {@code stamp} on the combat clock, sent at the client's {@code nanos}. */
    private void send(long nanos, long stamp, long tickTime, long frameTime) {
        ClientClock clock = combat.clock();
        if (clock == null || !clock.inputOpen()) {
            return;
        }
        int n = Math.min(pending.size(), NetCodec.MAX_DELTAS);
        List<PlayerIntake.Delta> out = new ArrayList<>(pending.subList(0, n));
        pending.subList(0, n).clear(); // the rest go in the next frame
        ClientPacketDistributor.sendToServer(new NetMessage.InputFrame(
                new PlayerIntake.Frame(seq++, stamp, frameTime, tickTime, clock.epoch(), held, out)));
        heldSent = held;
        OwnMoves.Rules r = ownRules();
        if (r != null) {
            // the server's input horizon reaches it (InputHorizon.frame): no press still to come is judged before it
            presses.sent(stamp, frameTime, r, combat.view().snapshot(), inCombat(nanos));
        }
    }

    // ------------------------------------------------------------------------------------------------- lock-on

    /**
     * Lock-on's key (Rule 13.3): its press is the mod's under the combat keys' rule (the blade in hand, no screen; a
     * mouse button's press is cancelled, so vanilla's pick block never sees it), and its key-up is a tap, answered at
     * once, or ends a hold ({@link LockOn}). Returns whether vanilla must not see this event.
     */
    private boolean lockKey(boolean down, boolean mouseButton, long nanos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return false;
        }
        if (!down) {
            CombatData d = combat.data();
            if (d != null && lock.release(nanos, d.rules().fights())) {
                lockTap(p, d.rules().fights());
            }
            return false; // never cancel a release
        }
        CaptureRules.Action a = CaptureRules.press(
                NichirinItem.isBlade(p.getMainHandItem()),
                mc.gui.screen() != null || mc.gui.overlay() != null,
                mouseButton,
                false,
                mc.options.keyShift.isDown(),
                mc.isWindowActive() && !mc.mouseHandler.isMouseGrabbed()); // grabMouse() needs the window active
        if (a == CaptureRules.Action.PASS) {
            return false;
        }
        lock.press(nanos);
        return a == CaptureRules.Action.TAKE_AND_CANCEL;
    }

    /**
     * A tap of lock-on: the hostile combatants within its range, each with one sight line from the player's eyes to
     * theirs, for {@link LockOn#tap} to lock or switch among.
     */
    private void lockTap(LocalPlayer p, FightRules r) {
        List<Entity> hostiles = new ArrayList<>();
        combat.hostiles(p, hostiles);
        Vec3 eyes = p.getEyePosition();
        List<LockOn.Candidate> candidates = new ArrayList<>(hostiles.size());
        for (Entity e : hostiles) {
            Vec3 at = e.getEyePosition();
            if (eyes.distanceTo(at) <= r.lockRangeBlocks()) { // no sight line spent on one that can't lock
                candidates.add(new LockOn.Candidate(e.getId(), at.x, at.y, at.z, inSight(p, eyes, at)));
            }
        }
        lock.tap(eyes.x, eyes.y, eyes.z, p.getYRot(), p.getXRot(), candidates, r);
        if (lock.locked()) {
            lockPlayer = p;
        }
    }

    /** Whether nothing that stops movement stands between {@code from} and {@code to} in {@code p}'s level. */
    private static boolean inSight(LocalPlayer p, Vec3 from, Vec3 to) {
        return p.level()
                        .clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p))
                        .getType()
                == HitResult.Type.MISS;
    }

    /**
     * The client tick's start for lock-on: the lock and the key's press drop where {@link LockOn#drops} says (a
     * dimension change drops them in {@link #leftLevel}). Else the target is checked where Rule 13.3 breaks it, with
     * one sight line a tick ({@link LockOn#check}).
     */
    private void lockTick(long nanos) {
        if (!lock.locked() && !lock.held()) {
            lockPlayer = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        CombatData d = combat.data();
        if (p == null || d == null || lockDrops(mc, p)) {
            lock.drop();
            lockPlayer = null;
            return;
        }
        if (!lock.locked()) {
            return;
        }
        Entity t = combat.hostile(p, lock.target());
        if (t == null) {
            lock.check(nanos, false, 0, false, d.rules().fights()); // died, or left the level or the fight
            return;
        }
        Vec3 eyes = p.getEyePosition();
        Vec3 at = t.getEyePosition();
        lock.check(
                nanos,
                true,
                eyes.distanceTo(at),
                inSight(p, eyes, at),
                d.rules().fights());
    }

    /** Whether the lock drops for player {@code p} now ({@link LockOn#drops}). */
    @SuppressWarnings("ReferenceEquality") // the very player object: a respawn makes a new one with the same id
    private boolean lockDrops(Minecraft mc, LocalPlayer p) {
        return LockOn.drops(
                !lock.locked() || p == lockPlayer,
                p.isDeadOrDying(),
                p.isSpectator(),
                NichirinItem.isBlade(p.getMainHandItem()),
                mc.gui.screen() != null || mc.gui.overlay() != null);
    }

    /**
     * The render frame's start for lock-on: a hold releases the lock at its mark, and a frame whose turn event didn't
     * run (the window inactive or the mouse free, as under MCTest) eases here instead, shown a frame later, since the
     * frame's camera was already read.
     */
    private void lockFrame(long nanos) {
        CombatData d = combat.data();
        if (d != null) {
            lock.frame(nanos, d.rules().fights());
        }
        if (!turned) {
            easeLock();
        }
        turned = false;
    }

    /**
     * Lock-on's ease for this render frame ({@link LockOn#ease}): the look turned from the player's eyes toward the
     * target's, both where they're drawn this frame, at the partial tick the camera reads, by the CLIENT config's
     * smoothing over the last frame's time. A lock whose target went, or that drops ({@link #lockDrops}), turns
     * nothing while it waits for the tick to end it, so a screen opened between ticks isn't eased behind.
     */
    private void easeLock() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        CombatData d = combat.data();
        Entity t = p != null && lock.locked() ? combat.hostile(p, lock.target()) : null;
        if (p == null || d == null || t == null || lockDrops(mc, p)) {
            return;
        }
        float pt = partialTick(mc, p);
        float tt = partialTick(mc, t);
        if (lock.ease(
                Mth.lerp(pt, p.xo, p.getX()),
                Mth.lerp(pt, p.yo, p.getY()) + p.getEyeHeight(),
                Mth.lerp(pt, p.zo, p.getZ()),
                p.getYRot(),
                p.getXRot(),
                Mth.lerp(tt, t.xo, t.getX()),
                Mth.lerp(tt, t.yo, t.getY()) + t.getEyeHeight(),
                Mth.lerp(tt, t.zo, t.getZ()),
                KimetsuClientConfig.client().lockOnSmoothing(),
                frameMicros,
                d.rules().hits().maxTurnDegPerTick())) {
            p.turn(lock.turnYaw() / LockOn.TURN_SCALE, lock.turnPitch() / LockOn.TURN_SCALE);
        }
    }

    /** The partial tick {@code e} is drawn at this frame, as the camera reads its own entity's ({@code Camera}). */
    private static float partialTick(Minecraft mc, Entity e) {
        return e.level().tickRateManager().isEntityFrozen(e)
                ? 1
                : mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    /** The lock, for the overlay's line and the HUD's target panel. */
    LockOn lock() {
        return lock;
    }

    // ------------------------------------------------------------------------------------------------- the HUD

    /** The combat-clock stamp of the last combat press but Jump and Sneak ({@code HudFade.NONE}: none on this timeline). */
    long lastCombatPress() {
        return lastCombatPress;
    }

    /**
     * The combat time of the tick this client tick's pose frame carries ({@link #poseTick}, in µs), the local player's
     * place then being what the server files: {@code Long.MIN_VALUE} while input is closed.
     */
    long tickTime() {
        ClientClock clock = combat.clock();
        return clock != null && clock.inputOpen() ? poseTick(clock) * Micros.PER_TICK : NONE;
    }

    /** What the HUD shows of its own moves at engine step {@code tick} ({@link OwnMoves#hud}). */
    void hud(long tick, OwnMoves.HudReadout out) {
        own.hud(tick, combat.view().snapshot(), out);
    }

    /** Whether an attack press acting in {@code tick} would fire the falling form, falling far enough. */
    boolean canStartFalling(long tick) {
        OwnMoves.Rules r = ownRules();
        return r != null && own.canStartFalling(tick, combat.view().snapshot(), r);
    }

    // ------------------------------------------------------------------------------------------------- the overlay

    /** The guard press nearest {@code impactMicros} in [impact − 500 ms, impact + 200 ms], or {@code Long.MIN_VALUE}. */
    long guardNear(long impactMicros) {
        long best = Long.MIN_VALUE;
        for (int i = 0; i < guardCount; i++) {
            long g = guards[i];
            if (g >= impactMicros - 500_000
                    && g <= impactMicros + 200_000
                    && (best == Long.MIN_VALUE || Math.abs(g - impactMicros) < Math.abs(best - impactMicros))) {
                best = g;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------- the dev hook

    /**
     * Arms a guard press {@code offsetMs} from the next white-cued impact on this player (negative: before it), timed
     * from the impact tick, which the judge measures from too; if the strike goes before the press (a feint), the next
     * one gets it. It's a holder's press and release whatever the guard mode: in TOGGLE it presses onto a raised guard
     * and its release lets go of it, so a deflect check run in TOGGLE doesn't play as a tap would.
     */
    void arm(int offsetMs) {
        armed = true;
        armOffsetMicros = offsetMs * 1000L;
        armedStrike = Long.MIN_VALUE;
        pressAt = Long.MIN_VALUE;
        releaseAt = Long.MIN_VALUE;
    }

    /**
     * Arms a Jump press {@code offsetMs} from the first tick of the next push on this player, where a push into a flush
     * wall meets it (D-082): MCTest can't time a key within Rule 9.2's window (D-083).
     */
    void armJump(int offsetMs) {
        jumpArmed = true;
        jumpOffsetMicros = offsetMs * 1000L;
        jumpAt = Long.MIN_VALUE;
    }

    /**
     * Arms a radial flick toward {@code slot} (5d-2): Breathe pressed in the next render frame, a 45° stroke toward the
     * slot fed into the stroke's summing path once it aims, and the release {@code holdMs} after the press, or with
     * {@code onBeat} on the first beat at least that long after it, through the capture's own path, so MCTest can check
     * the flick, on the beat, without raw mouse motion. A slot the style's radial doesn't have arms nothing.
     */
    void armRadial(int slot, int holdMs, boolean onBeat) {
        Moveset moves = combat.moves();
        LocalPlayer p = Minecraft.getInstance().player;
        int slots = moves != null ? moves.style().radial().size() : 0;
        if (slot >= slots) {
            if (p != null) {
                p.sendSystemMessage(Component.literal(
                        slots > 0
                                ? "Kimetsu dev: radial dropped (slots 0 to " + (slots - 1) + ")"
                                : "Kimetsu dev: radial dropped (no moveset yet)"));
            }
            return;
        }
        devSlot = slot;
        devHoldMicros = holdMs * 1000L;
        devOnBeat = onBeat;
        devReleaseAt = NONE;
        devStroked = false;
    }

    private void devRadial(long nanos) {
        ClientClock clock = combat.clock();
        Moveset moves = combat.moves();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (devSlot < 0 || clock == null || !clock.inputOpen() || moves == null || p == null) {
            return;
        }
        long now = clock.micros(nanos);
        int breathe = PlayerIntake.Key.BREATHE.bit();
        if (devReleaseAt == NONE) {
            boolean screen = mc.gui.screen() != null || mc.gui.overlay() != null;
            if (!NichirinItem.isBlade(p.getMainHandItem()) || screen || (held & breathe) != 0 || deferredUp != NONE) {
                p.sendSystemMessage(Component.literal(
                        "Kimetsu dev: radial dropped (no blade in hand, a screen open, or Breathe already down)"));
                devSlot = -1;
                return;
            }
            delta(PlayerIntake.Key.BREATHE, true, now, -1, 0); // through the capture's own path
            send(nanos, InputHorizon.NO_TICK, 0);
            long at = now + devHoldMicros;
            if (devOnBeat) {
                long beat = Micros.ofTicks(moves.style().beatTicks());
                at = Math.ceilDiv(at, beat) * beat; // every Pulse is anchored at 0 (Rule 1.3)
            }
            devReleaseAt = at;
            p.sendSystemMessage(Component.literal(
                    "Kimetsu dev: Breathe at " + now + " µs, release at " + at + " toward slot " + devSlot));
            return;
        }
        if ((held & breathe) == 0) {
            p.sendSystemMessage(Component.literal("Kimetsu dev: radial dropped (Breathe was let go)"));
            devSlot = -1;
            devReleaseAt = NONE;
            return;
        }
        if (!devStroked && aim(nanos)) {
            double a = Math.toRadians(devSlot * 360.0 / moves.style().radial().size());
            radial.move(DEV_STROKE_DEG * Math.sin(a), -DEV_STROKE_DEG * Math.cos(a)); // screen y grows down
            devStroked = true;
        }
        if (now >= devReleaseAt) {
            long at = devReleaseAt;
            int slot = slotAtRelease(nanos, at, 0, 0, 0);
            delta(PlayerIntake.Key.BREATHE, false, at, slot, 0);
            send(nanos, InputHorizon.NO_TICK, 0); // no frame to correct for: it's exact
            p.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "Kimetsu dev: Breathe released at %d µs, slot %d, stroke %.1f°",
                    at,
                    slot,
                    radial.lastLength())));
            devSlot = -1;
            devReleaseAt = NONE;
        }
    }

    /**
     * Arms a tap of {@code k} (5d-3b's sheets: Attack or Inhale) {@code offsetMs} from the next beat, or from the first
     * beat after that it can still reach, pressed at that exact time and let go {@link #DEV_RELEASE_MICROS} after,
     * through the capture's own path: MCTest can't land a key within {@code on_beat_ms} of a beat, so this stages the
     * on-beat Chain hits, the Recovery Breath that takes the grey segment and the off-beat Inhale that leaves you Winded.
     */
    void armTap(PlayerIntake.Key k, int offsetMs) {
        PlayerIntake.Key down = tapKey;
        ClientClock clock = combat.clock();
        if (tapDown && down != null && (held & down.bit()) != 0 && clock != null && clock.inputOpen()) {
            long nanos = Util.getNanos();
            delta(down, false, clock.micros(nanos), -1, 0); // the tap still down goes up first (code review)
            send(nanos, InputHorizon.NO_TICK, 0);
        }
        tapKey = k;
        tapOffsetMicros = offsetMs * 1000L;
        tapAt = NONE;
        tapDown = false;
    }

    private void devTap(long nanos) {
        PlayerIntake.Key k = tapKey;
        ClientClock clock = combat.clock();
        Moveset moves = combat.moves();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (k == null || clock == null || !clock.inputOpen() || moves == null || p == null) {
            return;
        }
        long now = clock.micros(nanos);
        if (!tapDown) {
            if (tapAt == NONE) {
                long beat = Micros.ofTicks(moves.style().beatTicks());
                long at = Math.ceilDiv(now, beat) * beat + tapOffsetMicros; // every Pulse is anchored at 0 (Rule 1.3)
                if (at < now) {
                    at += Math.ceilDiv(now - at, beat) * beat; // a negative offset from a beat too near
                }
                tapAt = at;
                p.sendSystemMessage(Component.literal("Kimetsu dev: " + k + " tap at " + at + " µs"));
            }
            if (now < tapAt) {
                return;
            }
            boolean screen = mc.gui.screen() != null || mc.gui.overlay() != null;
            if (!NichirinItem.isBlade(p.getMainHandItem()) || screen || (held & k.bit()) != 0) {
                p.sendSystemMessage(Component.literal(
                        "Kimetsu dev: tap dropped (no blade in hand, a screen open, or " + k + " already down)"));
                tapKey = null;
                tapAt = NONE;
                return;
            }
            delta(k, true, tapAt, -1, 0); // the exact time, through the capture's own path
            send(nanos, InputHorizon.NO_TICK, 0); // no frame to correct for: it's exact
            tapDown = true;
        }
        if ((held & k.bit()) == 0) { // a screen or the lost blade let go of it first
            tapKey = null;
            tapAt = NONE;
            tapDown = false;
            return;
        }
        long up = tapAt == NONE ? now : tapAt + DEV_RELEASE_MICROS; // NONE: an epoch came between
        if (now >= up) {
            delta(k, false, up, -1, 0);
            send(nanos, InputHorizon.NO_TICK, 0);
            p.sendSystemMessage(
                    Component.literal("Kimetsu dev: " + k + " tapped at " + (tapAt == NONE ? up : tapAt) + " µs"));
            tapKey = null;
            tapAt = NONE;
            tapDown = false;
        }
    }

    private void devHook(long nanos) {
        devJump(nanos);
        ClientClock clock = combat.clock();
        LocalPlayer p = Minecraft.getInstance().player;
        if (!armed || clock == null || !clock.inputOpen() || p == null) {
            return;
        }
        long now = clock.micros(nanos);
        if (pressAt != Long.MIN_VALUE && !combat.view().strikes().containsKey(armedStrike)) {
            // its strike went before the press (a feint cancelled it, D-091): the next white-cued one gets it instead
            p.sendSystemMessage(Component.literal("Kimetsu dev: strike #" + armedStrike + " went, armed for the next"));
            armedStrike = Long.MIN_VALUE;
            pressAt = Long.MIN_VALUE;
            releaseAt = Long.MIN_VALUE;
        }
        if (armedStrike == Long.MIN_VALUE) {
            Outbox.AttackStarted s = next(p.getId(), now);
            if (s == null) {
                return;
            }
            long impact = Micros.ofTicks(s.impactTick());
            armedStrike = s.strike();
            armedAttacker = s.attacker();
            pressAt = impact + armOffsetMicros;
            releaseAt = impact + Math.max(armOffsetMicros, 0) + DEV_RELEASE_MICROS;
            p.sendSystemMessage(Component.literal(
                    "Kimetsu dev: guard at " + armOffsetMicros / 1000 + " ms from strike #" + s.strike()));
        }
        faceAttacker(p);
        if (pressAt != Long.MIN_VALUE && now >= pressAt) {
            delta(PlayerIntake.Key.GUARD, true, pressAt, -1, 0); // the exact time, through the capture's own path
            send(nanos, InputHorizon.NO_TICK, 0); // no frame to correct for: it's exact
            pressAt = Long.MIN_VALUE;
        }
        if (pressAt == Long.MIN_VALUE && releaseAt != Long.MIN_VALUE && now >= releaseAt) {
            delta(PlayerIntake.Key.GUARD, false, releaseAt, -1, 0);
            send(nanos, InputHorizon.NO_TICK, 0);
            releaseAt = Long.MIN_VALUE;
            armed = false;
        }
    }

    /**
     * The armed Jump, in the first render frame at or past its time, through the capture's own path as a press of the
     * jump key then would go (stamped then, at most a frame late; judged within half a frame of it), so only the key's
     * lookup is skipped, and no hop goes with it.
     */
    private void devJump(long nanos) {
        ClientClock clock = combat.clock();
        LocalPlayer p = Minecraft.getInstance().player;
        if (jumpAt == Long.MIN_VALUE || clock == null || !clock.inputOpen() || p == null) {
            return;
        }
        long now = clock.micros(nanos);
        if (now < jumpAt) {
            return;
        }
        long armedFor = jumpAt;
        jumpAt = Long.MIN_VALUE;
        jumpArmed = false;
        p.sendSystemMessage(Component.literal(
                vanillaPressed(PlayerIntake.Key.JUMP, nanos)
                        ? "Kimetsu dev: Jump at " + now + " µs, armed for " + armedFor
                        : "Kimetsu dev: Jump dropped (no blade in hand, or a screen open)"));
    }

    /**
     * Faces the armed strike's attacker until the release: the deflect needs it in front (Rule 2.6), and this tool tests
     * timing, not aim (lock-on turns the camera for real in 5d).
     */
    private void faceAttacker(LocalPlayer p) {
        ClientLevel level = Minecraft.getInstance().level;
        Entity a = level != null ? level.getEntity(armedAttacker) : null;
        if (a != null) {
            p.lookAt(EntityAnchorArgument.Anchor.EYES, a.getEyePosition());
        }
    }

    /** The next white-cued strike on player {@code self} (a guard's to answer) the armed press can still reach. */
    private Outbox.@Nullable AttackStarted next(int self, long now) {
        CombatData d = combat.data();
        Outbox.AttackStarted best = null;
        for (Outbox.AttackStarted s : combat.view().strikes().values()) {
            long at = Micros.ofTicks(s.impactTick()) + armOffsetMicros;
            FrameData f = d != null ? d.frames().get(s.attack()) : null;
            if (s.target() == self
                    && at > now
                    && f != null
                    && f.cue().type() == FrameData.Cue.Type.WHITE
                    && (best == null || s.impactTick() < best.impactTick())) {
                best = s;
            }
        }
        return best;
    }
}
