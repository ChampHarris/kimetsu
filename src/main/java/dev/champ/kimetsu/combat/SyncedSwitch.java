package dev.champ.kimetsu.combat;

/**
 * A SYNCED config switch the server applies only when its value changes (D-104): a reload of any other key leaves a
 * command's own choice standing ({@code /kimetsu debug log} for {@code fight_logging}), until the switch itself
 * changes. Main thread only.
 */
final class SyncedSwitch {
    private boolean applied;

    /** A switch the server started with {@code initial}. */
    SyncedSwitch(boolean initial) {
        this.applied = initial;
    }

    /** Whether the config's value {@code value} is a change from the one last applied: if so, it's applied now. */
    boolean changed(boolean value) {
        if (value == applied) {
            return false;
        }
        applied = value;
        return true;
    }
}
