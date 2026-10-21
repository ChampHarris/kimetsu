package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.Phase;
import org.junit.jupiter.api.Test;

/**
 * What the world may ask of a Timeline in the middle of a tick, and the strike bookkeeping (the reviews' H1, M2, L1–L4
 * and L8): reads settle first, strikes resolve in impact order and not before their impact, a strike nobody decides is
 * dropped at the history's edge, releases are never dropped, and a tap straddling an impact keeps its Inhale.
 */
class StrikesAndSettlingTest {
    private static final long IMPACT = Rig.ms(2000); // tick 40

    private static Server struckAt40() {
        return new Server().through(29).tick(t -> t.commitStrike(1, IMPACT));
    }

    @Test
    void readsRightAfterAResolveSeeWhatItReleased() {
        Server miss = struckAt40().through(40);
        miss.deliver(Input.Kind.ATTACK, IMPACT + Rig.ms(20)).through(46);
        miss.tick(t -> {
            t.resolve(1);
            assertEquals(Phase.ACTIVE, t.stateAt(44).phase(44)); // the held swing: windup 41–43, active 44–45
            assertEquals(State.ATTACK, t.live().state());
        });

        Server hit = struckAt40().through(40);
        hit.deliver(Input.Kind.ATTACK, IMPACT + Rig.ms(20)).through(46);
        hit.tick(t -> {
            t.effect(new Effect.Hit(AttackClass.HEAVY, 12), 40);
            t.resolve(1);
            assertEquals(State.HITSTUN, t.stateAt(41).state()); // nothing left to sweep late
        });
    }

    @Test
    void aSecondStrikeResolvedInTheSameTickSeesWhatTheFirstReleased() {
        Server s = new Server().through(29).tick(t -> {
            t.commitStrike(1, IMPACT);
            t.commitStrike(2, Rig.ms(2300)); // tick 46
        });
        s.through(40).deliver(Input.Kind.STEP, IMPACT + Rig.ms(10)).through(49); // held by the first strike only
        s.tick(t -> {
            t.resolve(1);
            assertEquals(State.STEP, t.stateAt(46).state()); // the Step, 41–46, as the second strike judges it
            t.resolve(2);
        });
        assertEquals(46, s.t.watermark());
    }

    @Test
    void strikesResolveInImpactOrderAndNeverBeforeTheirImpact() {
        Server early = struckAt40();
        assertThrows(IllegalStateException.class, () -> early.tick(t -> t.resolve(1)));

        Server order = new Server().through(29).tick(t -> {
            t.commitStrike(1, IMPACT);
            t.commitStrike(2, Rig.ms(2300));
        });
        order.through(49);
        assertThrows(IllegalStateException.class, () -> order.tick(t -> t.resolve(2)));
    }

    @Test
    void aStrikeCancelledAfterItsImpactLeavesThatTickAsTheWorldReadIt() {
        Server late = struckAt40().through(41).tick(t -> t.cancelStrike(1));
        assertEquals(40, late.t.watermark());
        late.deliver(Input.Kind.STEP, Rig.during(38, 10)).through(43); // stamped for 39, arriving in 43
        assertEquals(State.STEP, late.t.stateAt(41).state());
        assertEquals(State.NEUTRAL, late.t.stateAt(40).state());

        Server early = struckAt40().through(35).tick(t -> t.cancelStrike(1));
        assertEquals(Long.MIN_VALUE, early.t.watermark());
    }

    @Test
    void aStrikeNobodyDecidesIsDroppedWhenItsImpactLeavesTheHistory() {
        Server s = struckAt40().through(40);
        s.deliver(Input.Kind.STEP, IMPACT + Rig.ms(20)).through(56);
        assertEquals(State.NEUTRAL, s.t.stateAt(56).state()); // still held
        assertEquals(40, s.t.sweepableThrough());
        s.through(57); // tick 40 is no longer among the states kept
        assertEquals(57, s.t.sweepableThrough());
        assertEquals(State.STEP, s.t.stateAt(41).state()); // released at its stamp
    }

    @Test
    void aRepeatedCommitReplacesTheEarlierOne() {
        Server s = new Server().through(29).tick(t -> {
            t.commitStrike(1, IMPACT);
            t.commitStrike(1, Rig.ms(3000));
        });
        s.through(45);
        assertEquals(45, s.t.sweepableThrough()); // only the impact at 60 is pending
    }

    @Test
    void releasesAreNeverDroppedAtTheCap() {
        Server s = new Server();
        for (int i = 0; i < 64; i++) {
            assertTrue(s.t.receive(Input.of(Input.Kind.INHALE, 1_000 + i)));
        }
        assertFalse(s.t.receive(Input.of(Input.Kind.ATTACK, 2_000)));
        assertTrue(s.t.receive(Input.of(Input.Kind.GUARD_UP, 2_000)));
        assertTrue(s.t.receive(Input.release(2_001, -1)));
    }

    @Test
    void aTapThatStraddlesAnImpactKeepsItsRecoveryBreath() {
        // The 1st form released on beat 2: windup 26–31, active 32–34, recovery 35–51. Beat 4 and the impact are at 52.
        long impact = Rig.beat(4);
        Server s = new Server(c -> c.effect(new Effect.RhythmDelta(8), 0));
        s.deliver(Input.Kind.BREATHE_DOWN, Rig.ms(1100)).deliver(Input.release(Rig.beat(2), 0));
        s.through(29).tick(t -> t.commitStrike(1, impact)).through(51);
        s.deliver(Input.Kind.BREATHE_DOWN, impact - Rig.ms(30)).through(52); // on the last recovery tick
        s.deliver(Input.release(impact + Rig.ms(60), -1)).through(54); // the release waits behind the strike
        assertEquals(9, s.live().rhythm().points()); // its press's Recovery Breath didn't wait
        assertTrue(s.live().ledger().spent(4));
        s.tick(t -> t.resolve(1));
        assertEquals(9, s.live().rhythm().points());
    }
}
