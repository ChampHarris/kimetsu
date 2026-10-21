package dev.champ.kimetsu.core.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.champ.kimetsu.core.clock.Micros;
import dev.champ.kimetsu.core.data.AttackClass;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client's reconciliation (Netcode, "Prediction and reconciliation"; the reviews' H2): the server snapshots its
 * settled tick, the client rebases onto it and replays its own later presses, so a press still on its way to the
 * server never vanishes from the client, and the client converges on what the server alone knew.
 */
class ReconciliationTest {
    private static final int LAG = 2; // ticks for a press or frame to reach the server
    private static final int BACK = 2; // ticks for a snapshot to reach the client

    private record Snapshot(long due, long tick, Combatant state) {}

    @Test
    void theClientKeepsItsPressesInFlightAndConvergesOnTheServer() {
        Rig shared = new Rig();
        Server server = new Server(shared, c -> {});
        Timeline client = new Timeline(shared.fresh(), 1, 32);
        Input[] own = {
            Input.of(Input.Kind.ATTACK, Rig.beat(1)),
            Input.of(Input.Kind.STEP, Rig.during(29, 10)),
            Input.of(Input.Kind.ATTACK, Rig.beat(3)),
            Input.of(Input.Kind.POMMEL, Rig.during(45, 10)),
        };
        List<Snapshot> mail = new ArrayList<>();
        int applied = 0;
        for (long n = 1; n <= 80; n++) {
            for (Input in : own) {
                if (in.tick() == n) {
                    client.receive(in);
                }
                if (in.tick() == n - LAG) {
                    server.deliver(in);
                }
            }
            for (int i = 0; i < mail.size(); i++) {
                Snapshot m = mail.get(i);
                if (m.due() == n && client.rebase(m.tick(), m.state())) {
                    applied++;
                }
            }
            client.beginTick(n);
            client.endTick(n);
            long tick = n;
            server.tick(t -> {
                if (tick == 25) {
                    t.effect(new Effect.PostureDamage(Rig.points(30), true), 25); // the client can't predict it
                }
            });
            long h = server.t.settledThrough(Micros.ofTicks(n - LAG), Long.MAX_VALUE);
            if (h != Long.MIN_VALUE) {
                Combatant copy = shared.fresh();
                copy.copyFrom(server.t.endAt(h));
                mail.add(new Snapshot(n + 1 + BACK, h, copy));
            }
            if (n == 26) {
                assertEquals(Rig.points(100), client.live().posture().at(26)); // it can't know of the hit yet
            }
            if (n == 40) {
                assertEquals(State.ATTACK, client.live().state()); // its Chain hit from step 39, still in flight
            }
        }
        assertTrue(applied > 60);
        assertTrue(client.live().sameAs(server.live())); // the server's hit included
    }

    @Test
    void ofTwoSnapshotsInOneFrameTheNewerWins_anOlderOneIsIgnored() {
        Rig shared = new Rig();
        Server server = new Server(shared, c -> {});
        Timeline client = new Timeline(shared.fresh(), 1, 32);
        server.through(29).tick(t -> t.effect(new Effect.Hit(AttackClass.LIGHT, 20), 30));
        server.tick(t -> t.effect(new Effect.PostureDamage(Rig.points(30), true), 31));
        Combatant s30 = shared.fresh();
        s30.copyFrom(server.t.endAt(30));
        Combatant s31 = shared.fresh();
        s31.copyFrom(server.t.endAt(31));
        for (long n = 1; n <= 33; n++) {
            client.beginTick(n);
            client.endTick(n);
        }
        assertTrue(client.rebase(30, s30));
        assertTrue(client.rebase(31, s31));
        assertFalse(client.rebase(30, s30)); // older than one already applied
        client.beginTick(34);
        client.endTick(34);
        server.through(34);
        assertTrue(client.live().sameAs(server.live()));
    }

    @Test
    void theSettledTickWaitsForPressesStillComingPendingImpactsAndOpenTaps() {
        Server s = new Server().through(30);
        assertEquals(30, s.t.settledThrough(Micros.ofTicks(35), Long.MAX_VALUE)); // never past now
        assertEquals(27, s.t.settledThrough(Rig.ms(1370), Long.MAX_VALUE)); // the newest frame
        assertEquals(24, s.t.settledThrough(Micros.ofTicks(30), Rig.ms(1240))); // a press held for its anchor
        s.deliver(Input.Kind.STEP, Rig.during(27, 10)); // received, not yet applied
        assertEquals(27, s.t.settledThrough(Micros.ofTicks(30), Long.MAX_VALUE));
        s.through(31).tick(t -> t.commitStrike(1, Rig.ms(2250))).through(50);
        assertEquals(44, s.t.settledThrough(Micros.ofTicks(50), Long.MAX_VALUE)); // its result goes at 45
        s.tick(t -> t.resolve(1));
        s.deliver(Input.Kind.BREATHE_DOWN, Rig.ms(2560)).through(52);
        assertEquals(51, s.t.settledThrough(Rig.ms(2600), Long.MAX_VALUE)); // a release could still make a tap
        assertEquals(52, s.t.settledThrough(Rig.ms(2720), Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, s.t.settledThrough(Micros.ofTicks(10), Long.MAX_VALUE)); // before the history
    }
}
