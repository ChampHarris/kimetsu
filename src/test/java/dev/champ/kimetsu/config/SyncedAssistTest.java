package dev.champ.kimetsu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.champ.kimetsu.core.judge.FrameMeter;
import org.junit.jupiter.api.Test;

/**
 * SYNCED's cap on the assist level a client asks for (spec Rhythm assist, D-104): none past {@code assist.max_level},
 * and none at all when {@code assist.allowed} is off.
 */
class SyncedAssistTest {
    private static KimetsuSyncedConfig.Synced synced(boolean allowed, int max) {
        return new KimetsuSyncedConfig.Synced(allowed, max, false, FrameMeter.Limits.PROPOSED, true);
    }

    @Test
    void aLevelUnderTheCapIsTheAskedOne() {
        for (int asked = 0; asked <= 3; asked++) {
            assertEquals(asked, synced(true, 3).assist(asked), "asked " + asked);
        }
    }

    @Test
    void aLevelOverTheCapIsTheCap() {
        assertEquals(1, synced(true, 1).assist(3));
        assertEquals(1, synced(true, 1).assist(2));
        assertEquals(0, synced(true, 0).assist(3));
    }

    @Test
    void noAssistWhenItIsNotAllowed() {
        for (int asked = 0; asked <= 3; asked++) {
            assertEquals(0, synced(false, 3).assist(asked), "asked " + asked);
        }
    }
}
