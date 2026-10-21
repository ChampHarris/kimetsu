package dev.champ.kimetsu.core.fight;

import dev.champ.kimetsu.core.Fixtures;
import dev.champ.kimetsu.core.data.DemonProfile;
import dev.champ.kimetsu.core.data.PostureRules;

/** The M1 demon's profile with one part changed, for tests. */
final class DemonProfiles {
    private DemonProfiles() {}

    static DemonProfile with(PostureRules posture) {
        return DemonFightTest.profile(Fixtures.demon().maxHp(), posture, Fixtures.hunger());
    }
}
