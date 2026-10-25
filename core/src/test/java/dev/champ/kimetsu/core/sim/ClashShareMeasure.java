package dev.champ.kimetsu.core.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.ClashRules;
import dev.champ.kimetsu.core.data.StrikeRules;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The measurement behind D-050 and R9-12, rerun with {@code ./gradlew :core:simMeasure}: forcing clashes (the
 * interrupter, and the clasher alone) against honest play of the same skill, for each share of the demon's max Posture a
 * won clash takes (1.0 breaks it, as Rule 10.3 was written). It writes {@code clash-shares.md} to the sim report folder;
 * it holds no bar, since it informs a decision rather than guarding one.
 */
@Tag("measure")
class ClashShareMeasure {
    private static final int FIGHTS = 400;
    private static final long SEED = 20_000;

    @Test
    void forcingClashesAgainstHonestPlayAtEachShare() {
        StringBuilder out = new StringBuilder("# Clash shares\n\n")
                .append("Forcing clashes against honest play of the same skill, ")
                .append(FIGHTS)
                .append(" fights a row, seeds from ")
                .append(SEED)
                .append(". Cut: in the time by which half the fights are won (negative is faster).\n\n")
                .append("| Share | Skill | Bots | Win % (honest) | Half won by, s (honest) | Cut | Clashes won % |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (double share : new double[] {1.0, 0.5, 0.35, 0.2}) {
            StrikeRules rules = rules(share);
            for (Skill s : List.of(Skill.EXPERT, Skill.AVERAGE, Skill.CASUAL, Skill.CASUAL_ASSIST)) {
                Batch honest = batch(rules, s, Bot.Kind.HONEST);
                for (Bot.Kind k : List.of(Bot.Kind.INTERRUPTER, Bot.Kind.CLASHER)) {
                    Batch b = batch(rules, s, k);
                    out.append(String.format(
                            "| %.2f | %s | %s | %.1f (%.1f) | %.1f (%.1f) | %+.1f%% | %.1f |%n",
                            share,
                            s.name(),
                            k,
                            b.winPct(),
                            honest.winPct(),
                            b.halfWonByS(),
                            honest.halfWonByS(),
                            100 * (b.halfWonByS() / honest.halfWonByS() - 1),
                            b.clashWinPct()));
                }
            }
        }
        Batch.report("clash-shares.md", out.toString());
        assertFalse(out.isEmpty());
    }

    private static Batch batch(StrikeRules rules, Skill s, Bot.Kind k) {
        return Batch.of(s.name() + " " + k, rules, List.of(Duel.Seat.solo(s, k)), FIGHTS, SEED);
    }

    /** The combat rules with a clash win that takes {@code share} of the mob's max Posture. */
    private static StrikeRules rules(double share) {
        StrikeRules s = Fixtures.strikes();
        ClashRules c = s.clash();
        return new StrikeRules(
                s.strikesPerTargetPerBeat(),
                s.minCueLeadTicks(),
                s.commitMarginTicks(),
                s.guardArcDeg(),
                s.deflectDefenderPosture(),
                s.deflectAttackerPosture(),
                s.perfectAttackerShare(),
                s.perfectRecoveryTicks(),
                s.pommelStunTicks(),
                s.grabHoldBeats(),
                s.throwDamage(),
                s.knockbackTicks(),
                s.launchUp(),
                s.splatWindowTicks(),
                s.splatDamage(),
                s.wallKeepShare(),
                new ClashRules(
                        c.flashHalfBeats(),
                        c.flashMarginMs(),
                        c.windowMs(),
                        c.falseStartMs(),
                        c.tieMs(),
                        c.cooldownTicks(),
                        share,
                        c.tiePushBlocks()));
    }
}
