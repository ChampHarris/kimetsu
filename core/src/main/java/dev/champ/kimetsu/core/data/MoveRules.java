package dev.champ.kimetsu.core.data;

/**
 * The movement bound's numbers (the spec's Netcode, "The movement bound"; D-070; part of {@code combat_rules}). Over
 * any {@code windowTicks}, a player's filed pose may move at most {@code windowBlocks} horizontally and
 * {@code riseBlocks} up from where it stood, each times its scale (the tick rate and its speed), plus the envelopes of
 * an accepted Step and any impulse; downward it may move freely. A window, not a tick, so a low frame rate's bunched
 * ticks change nothing. Sprint-jumping up stairs covers more than a Step over the window (3.98 against 3.0, the MCTest
 * trace), so with the measured numbers the bound catches teleports and sustained speed, not a lone Step's (D-072).
 *
 * @param windowTicks the window the displacement is measured over
 * @param windowBlocks how far a body may move horizontally over the window: 1.2 × the honest maximum measured
 *     (sprint-jumping up stairs)
 * @param riseBlocks how far it may rise over the window: 1.2 × the honest maximum measured (the same run)
 * @param freeDashBlocks how far a window may move horizontally on level ground before it's counted as a free dash for
 *     the fight report, the limit Champ accepted (D-070): 1.2 × open ground's honest maximum (sprint-jumping, 2.35), so
 *     a Step faked with no Step paid (3.0) counts (Champ, D-073)
 * @param freeDashRise a window that climbed this much or more isn't counted: honest stair-running covers more than a
 *     Step (3.98 across, 3.99 up) and isn't a free dash; 1.2 × open ground's honest rise (1.25)
 * @param speedGain the share of a movement-speed bonus the honest maximum gains (Swiftness II's +40% gains about 9% over
 *     the window, since a sprint-jump's push is fixed): a bonus {@code b} scales the bound by 1 + {@code speedGain} × b
 * @param impulseGraceTicks a vanilla impulse (knockback, an explosion) opens the bound for RTT/50 plus this many ticks
 * @param slipperyScale the bound's factor while standing on ice or slime, which keep a body's speed: vanilla's physics
 *     gives about 1.4 (blue ice, jumps tapped under a low ceiling), and 2 leaves a margin until a blue-ice trace
 *     measures it (Champ, D-073; it was unbounded, so a client could blink from any ice block)
 */
public record MoveRules(
        int windowTicks,
        double windowBlocks,
        double riseBlocks,
        double freeDashBlocks,
        double freeDashRise,
        double speedGain,
        int impulseGraceTicks,
        double slipperyScale) {

    public MoveRules {
        Check.positive("movement.window_ticks", windowTicks);
        Check.that(
                windowBlocks > 0 && Double.isFinite(windowBlocks),
                "movement.window_blocks must be positive and finite, was " + windowBlocks);
        Check.that(
                riseBlocks > 0 && Double.isFinite(riseBlocks),
                "movement.rise_blocks must be positive and finite, was " + riseBlocks);
        Check.that(
                freeDashBlocks > 0 && freeDashBlocks <= windowBlocks,
                "movement.free_dash_blocks must be positive and at most window_blocks, was " + freeDashBlocks);
        Check.that(
                freeDashRise > 0 && freeDashRise <= riseBlocks,
                "movement.free_dash_rise must be positive and at most rise_blocks, was " + freeDashRise);
        Check.between("movement.speed_gain", speedGain, 0.0, 1.0);
        Check.atLeast("movement.impulse_grace_ticks", impulseGraceTicks, 0);
        Check.that(
                slipperyScale >= 1 && Double.isFinite(slipperyScale),
                "movement.slippery_scale must be at least 1 and finite, was " + slipperyScale);
    }

    /**
     * A pose's scale: {@code clientTicks}, the client ticks one combat tick holds (the measured tick length over the
     * nominal one: a server that can't keep up at 20 TPS runs 15, and the client still runs 20, so 4/3 of its movement
     * lands in each; under {@code /tick rate} the client follows the server and it's 1), times the larger of its speed's
     * gain (a movement-speed bonus {@code speedBonus}, 0.4 for Swiftness II, gains {@code speedGain} of it) and its
     * jump's ({@code jumpRatio}, its jump's power over the default; a jump's height grows as its square), times
     * {@code slipperyScale} while it stands on ice or slime ({@code slippery}). None of them shrinks the bound below 1
     * (a server catching up, Slowness), and an unknown tick rate counts as 1.
     */
    public double scale(double clientTicks, double speedBonus, double jumpRatio, boolean slippery) {
        double rate = Double.isFinite(clientTicks) ? Math.max(1, clientTicks) : 1;
        double speed = 1 + speedGain * Math.max(0, speedBonus);
        double jump = Double.isFinite(jumpRatio) ? jumpRatio * jumpRatio : 1;
        return rate * Math.max(1, Math.max(speed, jump)) * (slippery ? slipperyScale : 1);
    }
}
