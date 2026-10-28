package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.data.AttackClass;
import dev.champ.kimetsu.core.data.Phase;
import org.junit.jupiter.api.Test;

/**
 * Rules 2.2, 2.3 and 2.10 and D-034: presses at their stamps, the back-dating limit, the one-strike barrier, the
 * watermark, the history ring and taps. A press delivered before tick n arrives in n.
 */
class TimelineTest {
    private static final long IMPACT = Rig.ms(2000); // tick 40

    @Test
    void onTimeItRunsExactlyAsTheMachineDoes() {
        Server s = new Server();
        Rig r = new Rig();
        Input[] script = {
            Input.of(Input.Kind.ATTACK, Rig.beat(1)),
            Input.of(Input.Kind.ATTACK, Rig.beat(2)),
            Input.of(Input.Kind.BREATHE_DOWN, Rig.ms(1500)),
            Input.release(Rig.ms(1950), 2),
            Input.of(Input.Kind.INHALE, Rig.beat(4)),
            Input.of(Input.Kind.STEP, Rig.ms(3500)),
            Input.guard(Rig.ms(3800)),
            Input.of(Input.Kind.GUARD_UP, Rig.ms(4100)),
        };
        for (Input in : script) {
            r.press(in);
        }
        r.effect(new Effect.Hit(AttackClass.LIGHT, 4), 60);
        int next = 0;
        for (long n = 1; n <= 100; n++) {
            while (next < script.length && script[next].tick() == n) {
                s.deliver(script[next++]);
            }
            long tick = n;
            s.tick(t -> {
                if (tick == 60) {
                    t.effect(new Effect.Hit(AttackClass.LIGHT, 4), 60);
                }
            });
            r.through(n);
            assertTrue(s.live().sameAs(r.c), "tick " + n);
        }
    }

    @Test
    void aLateStepIsAlreadyAlongWhenItArrives() {
        Server s = new Server().through(21);
        s.deliver(Input.Kind.STEP, Rig.during(19, 10)).through(22); // stamped for 20, arrived in 22
        assertEquals(State.STEP, s.live().state());
        assertEquals(26, s.live().stateUntil());
        assertEquals(State.STEP, s.t.stateAt(20).state());
        assertEquals(State.NEUTRAL, s.t.stateAt(19).state());
    }

    @Test
    void aLateAttackIsNeverActiveBeforeTheTickItArrived() {
        Server s = new Server().through(24);
        s.deliver(Input.Kind.ATTACK, Rig.during(19, 10)).through(25);
        assertEquals(20, s.live().attackStart());
        assertEquals(2, s.live().attackHold());
        assertEquals(Phase.ACTIVE, s.live().phase(25));
        assertEquals(Phase.WINDUP, s.t.stateAt(24).phase(24));

        Server whole = new Server().through(24);
        whole.deliver(Input.Kind.ATTACK, Rig.during(21, 10)).through(25); // back-dated by its whole windup
        assertEquals(0, whole.live().attackHold());
        assertEquals(Phase.ACTIVE, whole.live().phase(25));
    }

    @Test
    void aChainHitPressedJustAfterAnImpactThatHitsNeverStarts() {
        Server s = new Server().through(29).tick(t -> t.commitStrike(1, IMPACT)).through(40);
        s.deliver(Input.Kind.ATTACK, IMPACT + Rig.ms(20)).through(41);
        assertEquals(State.NEUTRAL, s.live().state()); // held behind the barrier
        assertEquals(40, s.t.sweepableThrough());
        s.through(43).tick(t -> {
            t.effect(new Effect.Hit(AttackClass.HEAVY, 12), 40);
            t.resolve(1);
        });
        assertEquals(State.HITSTUN, s.live().state());
        assertEquals(40, s.t.watermark());
        assertEquals(44, s.t.sweepableThrough());
        s.through(52);
        assertEquals(State.NEUTRAL, s.live().state());
        assertNull(s.live().attack()); // the buffer let it go while stunned
    }

    @Test
    void ifTheStrikeMissesTheHeldPressStartsAtItsStampAndItsSweepsComeLate() {
        Server s = new Server().through(29).tick(t -> t.commitStrike(1, IMPACT)).through(40);
        s.deliver(Input.Kind.ATTACK, IMPACT + Rig.ms(20)).through(43).tick(t -> t.resolve(1));
        assertEquals(41, s.live().attackStart());
        assertEquals(0, s.live().attackHold()); // it arrived in 41: nothing after 40 had been swept
        assertEquals(Phase.ACTIVE, s.live().phase(44));
        assertEquals(Phase.WINDUP, s.t.stateAt(43).phase(43));
    }

    @Test
    void aStrikeDecidedLateSeesAStepStampedBeforeItsImpactThatArrivedAfterIt() {
        Server s = new Server().through(29).tick(t -> t.commitStrike(1, IMPACT)).through(45);
        assertEquals(State.NEUTRAL, s.t.stateAt(40).state());
        s.deliver(Input.Kind.STEP, IMPACT - Rig.ms(200)); // stamped 200 ms before, arriving 300 ms after
        s.tick(t -> {
            assertEquals(State.STEP, t.stateAt(40).state());
            t.resolve(1);
        });
        s.through(49);
        assertEquals(State.STEP, s.t.stateAt(40).state()); // still kept 9 ticks on
        s.through(57);
        assertThrows(IllegalArgumentException.class, () -> s.t.stateAt(40));
    }

    @Test
    void aPressStampedAtOrBeforeWhatTheWorldReadAppliesJustAfterIt_itsStampStillJudgesTheBeat() {
        Server s = new Server().through(29).tick(t -> t.swept(30));
        s.deliver(Input.Kind.ATTACK, Rig.beat(2)).through(31);
        assertEquals(31, s.live().attackStart());
        assertTrue(s.live().attackOnBeat());
        assertEquals(2, s.live().attackBeat());

        Server step = new Server().through(29).tick(t -> t.swept(30)).through(31);
        step.deliver(Input.Kind.STEP, Rig.during(27, 10)).through(32);
        assertEquals(State.STEP, step.t.stateAt(31).state());
        assertEquals(State.NEUTRAL, step.t.stateAt(30).state());
    }

    @Test
    void aPressOlderThanTheHistoryAppliesAtTheOldestTickKept() {
        Server s = new Server().through(40);
        s.deliver(Input.Kind.STEP, Rig.during(19, 10)).through(41); // stamped for 20; tick 24's end is the oldest kept
        assertEquals(State.STEP, s.t.stateAt(25).state());
        assertEquals(25 + 6, s.t.stateAt(25).stateUntil());
    }

    @Test
    void aTapIsAnInhaleAtThePress_aReleaseAtTheThresholdIsAHold() {
        Server tap = new Server(c -> c.breath().spend(0, Rig.points(50)));
        tap.deliver(Input.Kind.BREATHE_DOWN, Rig.beat(1)).through(13);
        tap.deliver(Input.release(Rig.beat(1) + Rig.ms(149.999), 0)).through(16);
        assertEquals(State.NEUTRAL, tap.live().state());
        assertEquals(Rig.points(50 + 8 + 6), tap.live().breath().at(16)); // +6 on beat 1, replayed from 13

        Server hold = new Server(c -> c.breath().spend(0, Rig.points(50)));
        hold.deliver(Input.Kind.BREATHE_DOWN, Rig.beat(1)).through(13);
        hold.deliver(Input.release(Rig.beat(1) + Rig.ms(150), 0)).through(16);
        assertSame(hold.rig.moves.radial(0), hold.live().attack());
        assertEquals(Rig.points(50 + 8 - 20), hold.live().breath().at(16)); // the form, and no Inhale
    }

    @Test
    void aTapsRecoveryBreathIsJudgedAtThePressEvenIfTheRecoveryEndedBeforeTheRelease() {
        Server s = new Server(c -> c.effect(new Effect.RhythmDelta(8), 0));
        s.deliver(Input.Kind.BREATHE_DOWN, Rig.ms(1000)).through(20);
        s.deliver(Input.release(Rig.ms(1200), 0)).through(24); // the 1st form: recovery 33–49, NEUTRAL at 50
        s.through(49);
        s.deliver(Input.Kind.BREATHE_DOWN, Rig.ms(2490)).through(50);
        assertEquals(State.NEUTRAL, s.live().state());
        assertEquals(8, s.live().rhythm().points());
        s.deliver(Input.release(Rig.ms(2600), -1)).through(52);
        assertEquals(9, s.live().rhythm().points()); // a Recovery Breath on beat 4, as of the press
        assertEquals(0, s.live().breath().grey());
        assertTrue(s.live().ledger().spent(4));
    }

    @Test
    void anEffectLoggedAtAnEarlierTickReplaysFromThere() {
        Server s = new Server().through(30).tick(t -> t.effect(new Effect.Hit(AttackClass.LIGHT, 10), 28));
        assertEquals(State.HITSTUN, s.live().state());
        assertEquals(38, s.live().stateUntil());
        assertEquals(State.NEUTRAL, s.t.stateAt(28).state()); // the state the world judged, before the hit
        assertEquals(State.HITSTUN, s.t.stateAt(29).state());
    }

    @Test
    void theWorldCantRewriteWhatItRead() {
        Server s = new Server().through(29).tick(t -> t.swept(30));
        assertThrows(IllegalArgumentException.class, () -> s.tick(t -> t.effect(new Effect.Blocked(), 29)));
        Server pending =
                new Server().through(29).tick(t -> t.commitStrike(1, IMPACT)).through(44);
        assertThrows(IllegalArgumentException.class, () -> pending.tick(t -> t.swept(41)));
    }

    @Test
    void aClockEpochDiscardsPressesStampedAfterTheCutoffAndThePendingStrikes() {
        Server s = new Server().through(29).tick(t -> t.commitStrike(1, IMPACT)).through(40);
        s.deliver(Input.Kind.STEP, IMPACT + Rig.ms(20)); // held behind the strike
        s.through(41);
        s.deliver(Input.Kind.ATTACK, IMPACT + Rig.ms(80));
        s.t.onEpoch(IMPACT + Rig.ms(50));
        s.through(42);
        assertEquals(State.STEP, s.live().state()); // released with the strikes gone, at its stamp
        assertEquals(41, s.live().stateUntil() - 6);
        s.through(60);
        assertNull(s.live().attack()); // the press after the cutoff never ran
    }

    @Test
    void theTickProtocolIsEnforced() {
        Server s = new Server().through(5);
        assertThrows(IllegalStateException.class, () -> s.t.beginTick(7));
        assertThrows(IllegalStateException.class, () -> s.t.endTick(6));
        assertThrows(IllegalStateException.class, () -> s.t.effect(new Effect.Blocked(), 5));
        s.t.beginTick(6);
        assertThrows(IllegalStateException.class, () -> s.t.beginTick(7));
        s.t.endTick(6);
    }

    @Test
    void theClientReconcilesOntoTheServersStateAndReplaysItsOwnLaterPresses() {
        Rig shared = new Rig();
        Server server = new Server(shared, c -> {});
        Server client = new Server(shared, c -> {});
        Combatant snapshot = shared.fresh();
        Input[] own = {
            Input.of(Input.Kind.ATTACK, Rig.beat(1)),
            Input.of(Input.Kind.STEP, Rig.during(28, 10)),
            Input.of(Input.Kind.ATTACK, Rig.beat(3)),
        };
        int next = 0;
        for (long n = 1; n <= 45; n++) {
            while (next < own.length && own[next].tick() == n) {
                server.deliver(own[next]);
                client.deliver(own[next]);
                next++;
            }
            long tick = n;
            server.tick(t -> {
                if (tick == 25) {
                    t.effect(new Effect.PostureDamage(Rig.points(30), true), 25); // the client didn't predict it
                }
            });
            client.tick(t -> {});
            if (n == 30) {
                snapshot.copyFrom(server.live());
            }
            if (n == 33) {
                assertFalse(client.live().sameAs(server.live()));
                client.t.rebase(30, snapshot); // the server's tick-30 state reaches the client at 33
            }
        }
        assertTrue(client.live().sameAs(server.live()));
    }

    /**
     * The guard toggle (D-104) sends a tap while the guard is up as a release and a press at one stamp, and drops the
     * guard before an action as a release at the action's stamp, sent first: presses at one stamp act in the order they
     * came, so the release acts first, as a holder's let-go before the key goes down again. A re-press then ends with
     * the guard down (the other order would end let go); an action starts in its own step (pressed into the guard it
     * would wait in the buffer for the release, which runs it at the same step's end).
     */
    @Test
    void aReleaseAndAPressAtOneStampActInTheOrderTheyCame() {
        long tap = Rig.during(6, 20); // acts in step 7
        Server s = new Server().deliver(Input.guard(Rig.during(2, 10))).through(5);
        assertEquals(State.GUARD, s.live().state());
        s.deliver(Input.of(Input.Kind.GUARD_UP, tap)).deliver(Input.guard(tap)).through(7);
        assertTrue(s.live().guardDown(), "let go, then down again");
        assertEquals(State.NEUTRAL, s.live().state(), "rising for guard_hold_ticks, as a holder's re-press");
        assertEquals(State.GUARD, s.through(8).live().state());

        Server reversed = new Server().deliver(Input.guard(Rig.during(2, 10))).through(5);
        reversed.deliver(Input.guard(tap))
                .deliver(Input.of(Input.Kind.GUARD_UP, tap))
                .through(7);
        assertFalse(reversed.live().guardDown(), "the other way round it ends let go");

        Server attack = new Server().deliver(Input.guard(Rig.during(2, 10))).through(5);
        attack.deliver(Input.of(Input.Kind.GUARD_UP, tap))
                .deliver(Input.of(Input.Kind.ATTACK, tap))
                .through(7);
        assertEquals(State.ATTACK, attack.live().state());
        assertEquals(7, attack.live().attackStart(), "in its own step");
    }
}
