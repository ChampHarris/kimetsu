package dev.champ.kimetsu.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.FrameData;
import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.fight.PlayerIntake;
import dev.champ.kimetsu.core.judge.InputHorizon;
import dev.champ.kimetsu.data.CombatData;
import dev.champ.kimetsu.item.NichirinItem;
import dev.champ.kimetsu.net.NetCodec;
import dev.champ.kimetsu.net.NetMessage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The combat keys into input frames (spec Netcode, "Input capture" and "Input frames"). A combat key's press or release
 * is read where the game first sees it ({@code MouseButton.Pre}, {@code Key}), stamped there on the combat clock, and
 * goes out in a frame at the start of the next client tick; a pose frame follows at each tick's end whose tick time,
 * the clock at the tick's start floored to a whole tick, has moved on. Pose frames flow near a hostile combatant
 * whatever is in hand (D-052: a demon strikes a player who put the blade away), and while a key the mod took is held,
 * a delta waits or the server hasn't heard the held state. A screen, the blade leaving the hand, lost focus or the
 * pause releases every held key, so none is left held on the server. Deltas polled in different render frames go in
 * different frames, each with its own frame time for the half-frame correction. Main thread only.
 */
final class ClientInput {
    private static final int GUARDS = 16; // guard presses kept for the overlay's offsets
    private static final double ENGAGE_BLOCKS = 32; // D-052: frames while a hostile combatant is this near
    private static final long DEV_RELEASE_MICROS = 20_000; // the dev hook lets go 20 ms after the later of both

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
    private boolean focused = true; // the window's focus at the last tick: losing it is an edge, not a state

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
    }

    boolean overlay() {
        return overlay;
    }

    // ------------------------------------------------------------------------------------------------- capture

    void mouse(InputEvent.MouseButton.Pre e, long nanos) {
        if (e.getAction() == InputConstants.REPEAT) {
            return;
        }
        PlayerIntake.Key k = KimetsuKeys.combatKey(InputConstants.Type.MOUSE.getOrCreate(e.getButton()));
        if (k != null && capture(k, e.getAction() == InputConstants.PRESS, true, nanos)) {
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
            capture(k, e.getAction() == InputConstants.PRESS, false, nanos); // a key event can't be cancelled
        }
    }

    /** Vanilla's attack or use triggered anyway: stopped, with no swing and no punch packet, while the blade is held. */
    void interaction(InputEvent.InteractionKeyMappingTriggered e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null
                && CaptureRules.stopVanilla(
                        NichirinItem.isBlade(p.getMainHandItem()),
                        e.isAttack(),
                        e.isUseItem(),
                        Minecraft.getInstance().options.keyShift.isDown())) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    /** Returns whether vanilla must not see this event. */
    private boolean capture(PlayerIntake.Key k, boolean down, boolean mouseButton, long nanos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return false;
        }
        if (!down) {
            if (CaptureRules.release((held & k.bit()) != 0) == CaptureRules.Action.TAKE) {
                take(k, false, nanos);
            }
            return false; // never cancel a release
        }
        boolean screen = mc.gui.screen() != null || mc.gui.overlay() != null;
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
        take(k, true, nanos);
        return a == CaptureRules.Action.TAKE_AND_CANCEL;
    }

    /** A press or release taken at the client's {@code nanos}; a press while the clock can't stamp it goes nowhere. */
    private void take(PlayerIntake.Key k, boolean down, long nanos) {
        ClientClock clock = combat.clock();
        if (clock == null || !clock.inputOpen()) {
            if (!down) {
                held &= ~k.bit(); // the frame's held state will tell the server
            }
            return;
        }
        delta(k, down, clock.micros(nanos));
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
    }

    /** {@code ClientTickEvent.Pre}: the releases a screen or a lost blade forces, then the presses since the last tick. */
    void tickStart(long nanos) {
        tickStartNanos = nanos;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        boolean lostFocus = focused && !mc.isWindowActive(); // alt-tab; a window never focused (a test) holds keys
        focused = mc.isWindowActive();
        if (held != 0
                && (p == null
                        || mc.gui.screen() != null
                        || mc.gui.overlay() != null
                        || !NichirinItem.isBlade(p.getMainHandItem())
                        || lostFocus
                        || mc.isPaused())) {
            ClientClock clock = combat.clock();
            for (PlayerIntake.Key k : PlayerIntake.Key.values()) {
                if ((held & k.bit()) != 0) {
                    take(k, false, nanos);
                }
            }
            held = 0;
            if (clock == null || !clock.inputOpen()) {
                pending.clear(); // nothing can be stamped; the next frame's held state releases them
            }
        }
        if (!pending.isEmpty()) {
            send(nanos, InputHorizon.NO_TICK, pendingFrameMicros);
        }
    }

    /** {@code ClientTickEvent.Post}, after vanilla's movement and tick-end packets: the tick's pose frame. */
    void tickEnd(long nanos) {
        ClientClock clock = combat.clock();
        if (clock == null || !clock.inputOpen() || !flowing()) {
            return;
        }
        long tickTime = Math.floorDiv(clock.micros(tickStartNanos), Micros.PER_TICK) * Micros.PER_TICK;
        if (tickTime <= lastTickTime) {
            return; // frozen, or the clock stood still: no fresh pose
        }
        lastTickTime = tickTime;
        send(nanos, tickTime, pending.isEmpty() ? frameMicros : pendingFrameMicros);
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

    /** {@code ClockEpoch}: tick times start again behind the old timeline's. */
    void onEpoch() {
        lastTickTime = Long.MIN_VALUE;
    }

    private boolean flowing() {
        return held != 0 || held != heldSent || !pending.isEmpty() || engaged();
    }

    /** A hostile combatant within 32 blocks (D-052), whatever is in hand: the server needs the pose either way. */
    private boolean engaged() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && combat.hostileWithin(p, ENGAGE_BLOCKS);
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
