package dev.champ.kimetsu.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.data.MachineRules;
import dev.champ.kimetsu.core.data.Moveset;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.core.machine.Effect;
import dev.champ.kimetsu.core.meter.Fixed;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.item.NichirinItem;
import dev.champ.kimetsu.net.NetCodec;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
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
 * with its own frame time for the half-frame correction. A Step (the Step key, or a double-tap of left, back or right:
 * {@link StepInput}) goes out like any press, at the next tick's start, and the client dashes from that tick
 * ({@link ForcedMotion}). Main thread only.
 */
final class ClientInput {
    private static final int GUARDS = 16; // guard presses kept for the overlay's offsets
    private static final long DEV_RELEASE_MICROS = 20_000; // the dev hook lets go 20 ms after the later of both
    private static final long NONE = Long.MIN_VALUE;
    private static final int OWN_STEPS = 4; // the client's last Steps, whose Breath a snapshot may not show yet

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
    private long deferredUp = NONE; // a Breathe release the lost focus restamped: it goes once its stamp has passed

    // the Step (D-070): its keys, its dash, and what gates the dash until the client predicts (step 5)
    private final StepInput steps = new StepInput();
    private final ForcedMotion dash = new ForcedMotion();
    private @Nullable FrameData dashStep; // the Step whose table is kept: a data reload brings a new one
    private double[] dashPerTick = new double[0];
    private long clientTick; // the client tick running, or the last one run
    private long attackTick = NONE; // the client tick the last Attack or Pommel press starts its attack in
    private @Nullable FrameData attack; // and that attack's frames
    private final long[] ownSteps = new long[OWN_STEPS]; // the engine ticks of its last Steps
    private int ownStepHead;

    // the dev hook (spec Tools: a guard press at an exact combat time)
    private boolean armed;
    private long armOffsetMicros;
    private long armedStrike = Long.MIN_VALUE;
    private int armedAttacker;
    private long pressAt = Long.MIN_VALUE;
    private long releaseAt = Long.MIN_VALUE;

    ClientInput(ClientCombat combat, boolean overlay) {
        this.combat = combat;
        this.overlay = overlay;
        Arrays.fill(ownSteps, NONE);
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
        PlayerIntake.Key k = KimetsuKeys.combatKey(key);
        if (k != null && capture(k, key, e.getAction() == InputConstants.PRESS, true, nanos)) {
            e.setCanceled(true);
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
        PlayerIntake.Key k = KimetsuKeys.combatKey(key);
        if (k != null) {
            capture(k, key, e.getAction() == InputConstants.PRESS, false, nanos); // a key event can't be cancelled
        }
        StepInput.Key move = e.getAction() == InputConstants.PRESS ? movementKey(mc.options, key) : null;
        if (move != null) {
            tapped(move, nanos);
        }
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
            stepped(nanos);
        }
    }

    /**
     * Vanilla's attack or use triggered anyway: stopped, with no swing and no punch packet, while the blade is held,
     * except an attack that mines (D-055), whose held click keeps mining only while it's still away from fights.
     */
    void interaction(InputEvent.InteractionKeyMappingTriggered e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null
                && CaptureRules.stopVanilla(
                        NichirinItem.isBlade(p.getMainHandItem()),
                        e.isAttack() && !CaptureRules.mines(true, onBlock(), combat.fighting(p, Util.getNanos())),
                        e.isUseItem(),
                        Minecraft.getInstance().options.keyShift.isDown())) {
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
            if (CaptureRules.release((held & k.bit()) != 0) == CaptureRules.Action.TAKE) {
                FocusRelease.Up up = k == PlayerIntake.Key.BREATHE
                        ? focus.breatheUp(mc.isWindowActive(), deferredUp != NONE)
                        : FocusRelease.Up.TAKE;
                switch (up) {
                    case TAKE -> take(k, false, nanos);
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
        if (take(k, true, nanos)) {
            pressed(k, nanos);
        }
        return a == CaptureRules.Action.TAKE_AND_CANCEL;
    }

    /** A press went out: a Step dashes, an attack's frames gate the next Step, a Breathe press is kept for its release. */
    private void pressed(PlayerIntake.Key k, long nanos) {
        Moveset moves = combat.moves();
        switch (k) {
            case STEP -> stepped(nanos);
            case ATTACK, POMMEL -> {
                if (moves != null) {
                    attackTick = clientTick + 1; // it starts in the next tick, as the server's machine starts it
                    attack = k == PlayerIntake.Key.ATTACK ? moves.chain(0) : moves.pommel(); // the chain's hits agree
                }
            }
            default -> {}
        }
    }

    /**
     * A Step went out: the dash from the movement keys held (none: a backstep), gated until the client predicts (step
     * 5) by the server's last snapshot and the client's own presses: its Breath, evaluated at the client's tick, must
     * cover the Step's, less the Steps the snapshot can't show yet; its state must allow one; and in its own attack the
     * dash waits for the attack's Step cancel, as the server's buffer does, or is dropped.
     */
    private void stepped(long nanos) {
        Minecraft mc = Minecraft.getInstance();
        Moveset moves = combat.moves();
        CombatData d = combat.data();
        ClientClock clock = combat.clock();
        if (moves == null || d == null || clock == null) {
            return;
        }
        FrameData step = moves.step();
        long tick = Math.floorDiv(clock.micros(nanos), Micros.PER_TICK);
        if (!canStep(step, tick)) {
            return;
        }
        MachineRules m = d.rules().machine();
        long next = clientTick + 1;
        long opens = next;
        FrameData a = attack;
        if (a != null && next < attackTick + a.total()) {
            int at = a.opens(FrameData.Into.STEP);
            // no Step cancel: the server's buffer runs it once the attack ends, if that's within the buffer (netcode
            // review)
            opens = attackTick + (at < 0 ? a.total() : at);
        }
        Options o = mc.options;
        StepInput.Direction dir =
                StepInput.Direction.held(o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown());
        if (dash.press(dashTable(step), dir, next, opens, m.bufferTicks())) {
            ownSteps[ownStepHead] = tick;
            ownStepHead = (ownStepHead + 1) % OWN_STEPS;
        }
    }

    /** Whether the server's last snapshot, with the client's own Steps since, lets the Step through. */
    private boolean canStep(FrameData step, long tick) {
        NetMessage.Snapshot s = combat.view().snapshot();
        if (s == null) {
            return true; // a fresh fighter: full Breath, standing
        }
        switch (s.state()) {
            case NEUTRAL, GUARD, ATTACK, STEP -> {} // an attack or a Step: the client's own presses tell
            default -> {
                return false;
            }
        }
        long cost = Fixed.of(step.breathCost());
        long breath = s.breath().at(tick);
        for (long t : ownSteps) {
            if (t > s.settledTick()) {
                breath -= cost;
            }
        }
        return breath >= cost;
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
    }

    /**
     * The client tick's start, after its presses went: the dash's or the push's motion for this tick
     * ({@link ForcedMotion}), set before the player's own tick moves it; the vertical only for a launch's lift. Its
     * combat tick is the one this tick's pose frame will carry ({@link #tickEnd}).
     */
    void dashTick(LocalPlayer p) {
        ClientClock clock = combat.clock();
        long tick = clock != null && clock.inputOpen()
                ? poseTick(clock)
                : Long.MAX_VALUE; // no pose frame carries a tick to wait for
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
     * {@code MovementInputUpdateEvent}, inside the player's tick: during a dash its movement keys, jump and sprint are
     * let go (Sneak is kept) and its move vector zeroed, so vanilla adds no push and no sprint-jump to the dash.
     */
    void movementInput(MovementInputUpdateEvent e) {
        if (!dash.moving()) {
            return;
        }
        net.minecraft.client.player.ClientInput in = e.getInput();
        in.keyPresses = new net.minecraft.world.entity.player.Input(
                false, false, false, false, false, in.keyPresses.shift(), false);
        in.moveVector = Vec2.ZERO;
    }

    /** The level went (a dimension change, a respawn): no dash carries over, nor a first tap. */
    void leftLevel() {
        dash.cancel();
        steps.reset();
        attack = null;
    }

    /**
     * Breathe came up as the focus went (alt-tab): the release goes stamped no earlier than its press plus the radial's
     * hold, once that time has passed (no press is later than its frame), its bit held meanwhile so no frame's held
     * state releases it sooner.
     */
    private void deferRelease(long nanos) {
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
            delta(PlayerIntake.Key.BREATHE, false, at);
            send(nanos, InputHorizon.NO_TICK, 0); // no frame to correct for: its time is the rule's
        }
    }

    /** The tick's lost-focus edge: a Breathe release still waiting to go, from a key-up before the flag flipped. */
    private void restampPending(long nanos) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            PlayerIntake.Delta d = pending.get(i);
            if (d.key() == PlayerIntake.Key.BREATHE && !d.down()) {
                pending.remove(i);
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
        ClientClock clock = combat.clock();
        if (clock == null || !clock.inputOpen()) {
            if (!down) {
                held &= ~k.bit(); // the frame's held state will tell the server
            }
            return false;
        }
        delta(k, down, clock.micros(nanos));
        return true;
    }

    private void delta(PlayerIntake.Key k, boolean down, long stampMicros) {
        if (!pending.isEmpty() && pendingFrame != renderFrame) {
            send(Util.getNanos(), InputHorizon.NO_TICK, pendingFrameMicros); // polled in another render frame
        }
        if (pending.isEmpty()) {
            pendingFrame = renderFrame;
            pendingFrameMicros = frameMicros;
        }
        if (down) {
            held |= k.bit();
            if (k == PlayerIntake.Key.BREATHE) {
                breatheDownMicros = stampMicros;
            }
            pending.add(PlayerIntake.Delta.press(k, stampMicros));
            if (k == PlayerIntake.Key.GUARD) {
                guards[guardHead] = stampMicros;
                guardHead = (guardHead + 1) % GUARDS;
                guardCount = Math.min(guardCount + 1, GUARDS);
            }
        } else {
            held &= ~k.bit();
            pending.add(PlayerIntake.Delta.release(k, stampMicros));
        }
    }

    // ------------------------------------------------------------------------------------------------- frames

    /** {@code RenderFrameEvent.Pre}: the frame time the next presses carry, and the dev hook's presses when due. */
    void frameStart(long nanos) {
        if (lastFrameNanos != Long.MIN_VALUE) {
            frameMicros = (nanos - lastFrameNanos) / 1000;
        }
        lastFrameNanos = nanos;
        renderFrame++;
        devHook(nanos);
        flushDeferred(nanos);
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
        if (!pending.isEmpty()) {
            send(nanos, InputHorizon.NO_TICK, pendingFrameMicros);
        }
        flushDeferred(nanos);
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
        Arrays.fill(ownSteps, NONE);
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
        if (clock == null || !clock.inputOpen()) {
            return;
        }
        int n = Math.min(pending.size(), NetCodec.MAX_DELTAS);
        List<PlayerIntake.Delta> out = new ArrayList<>(pending.subList(0, n));
        pending.subList(0, n).clear(); // the rest go in the next frame
        ClientPacketDistributor.sendToServer(new NetMessage.InputFrame(
                new PlayerIntake.Frame(seq++, clock.micros(nanos), frameTime, tickTime, clock.epoch(), held, out)));
        heldSent = held;
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

    /** Arms a guard press {@code offsetMs} from the next white-cued impact on this player (negative: before it). */
    void arm(int offsetMs) {
        armed = true;
        armOffsetMicros = offsetMs * 1000L;
        armedStrike = Long.MIN_VALUE;
        pressAt = Long.MIN_VALUE;
        releaseAt = Long.MIN_VALUE;
    }

    private void devHook(long nanos) {
        ClientClock clock = combat.clock();
        LocalPlayer p = Minecraft.getInstance().player;
        if (!armed || clock == null || !clock.inputOpen() || p == null) {
            return;
        }
        long now = clock.micros(nanos);
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
            delta(PlayerIntake.Key.GUARD, true, pressAt); // the exact time, through the capture's own path
            send(nanos, InputHorizon.NO_TICK, 0); // no frame to correct for: it's exact
            pressAt = Long.MIN_VALUE;
        }
        if (pressAt == Long.MIN_VALUE && releaseAt != Long.MIN_VALUE && now >= releaseAt) {
            delta(PlayerIntake.Key.GUARD, false, releaseAt);
            send(nanos, InputHorizon.NO_TICK, 0);
            releaseAt = Long.MIN_VALUE;
            armed = false;
        }
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
