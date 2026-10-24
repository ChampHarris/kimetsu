package dev.champ.kimetsu.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.champ.kimetsu.core.fight.Outbox;
import dev.champ.kimetsu.core.machine.State;
import dev.champ.kimetsu.net.NetMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the client keeps of the routed state (slice 5c-1): a mob's state goes when the mob does, however it goes. */
class ClientViewTest {
    private static final int SELF = 1;
    private static final int DEMON = 10;

    private static ClientView seeing() {
        ClientView v = new ClientView();
        Outbox.MobState state = new Outbox.MobState(DEMON, 120, State.NEUTRAL, Long.MIN_VALUE, 100, 0, 120, 0);
        v.accept(new NetMessage.Tick(120, List.of(state), null), SELF);
        assertNotNull(v.mob(DEMON));
        return v;
    }

    @Test
    void aMobWhoseEntityLeavesTheClientsLevelIsForgotten_thoughNoMobLeftReachedIt() {
        // the server ends the tracking before an unloading mob leaves, so its MobLeft reaches nobody (both reviews)
        ClientView v = seeing();
        v.mobGone(DEMON);
        assertNull(v.mob(DEMON));
    }

    @Test
    void aMobLeftThatReachesTheClientForgetsTheMobToo() {
        ClientView v = seeing();
        v.accept(new NetMessage.Tick(121, List.of(new Outbox.MobLeft(DEMON, 121)), null), SELF);
        assertNull(v.mob(DEMON));
    }
}
