package dev.champ.kimetsu.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The fight log's SYNCED switch (D-104): applied at the server's start and when it changes, so {@code /kimetsu debug
 * log} overrides it until then and a reload of another key leaves that choice standing.
 */
class SyncedSwitchTest {
    @Test
    void aReloadThatLeavesTheValueAsItWasAppliesNothing_soACommandsChoiceStands() {
        SyncedSwitch logging = new SyncedSwitch(true); // on at the start; then /kimetsu debug log off
        assertFalse(logging.changed(true), "another key reloaded: the command's off stands");
        assertFalse(logging.changed(true), "and again");
    }

    @Test
    void aChangeAppliesOnce_eachWay() {
        SyncedSwitch logging = new SyncedSwitch(true);
        assertTrue(logging.changed(false), "turned off in the file");
        assertFalse(logging.changed(false), "already applied");
        assertTrue(logging.changed(true), "turned back on");
        assertFalse(logging.changed(true), "already applied");
    }

    @Test
    void aServerStartedOffTurnsOnOnlyWhenTheFileDoes() {
        SyncedSwitch logging = new SyncedSwitch(false); // off in production; then /kimetsu debug log on
        assertFalse(logging.changed(false), "another key reloaded: the command's on stands");
        assertTrue(logging.changed(true), "turned on in the file");
    }
}
