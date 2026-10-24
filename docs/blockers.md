# Blockers

Where Claude records work it can't finish honestly, so it stops instead of looping or weakening a check (ADR 0003). Written by `/blocked`; the session-start hook lists the open ones; `/session-close` keeps them current; `/retro` turns solved ones into rules or checks.

Format (newest last):

```
## B-<NNN> — <one-line title>
- **Status:** open | resolved
- **Date:** <YYYY-MM-DD> · **Task:** <spec id, slice or file>
- **Symptom:** <command> → <the error, 1–3 lines>
- **Files:** <every file the attempts changed, comma-separated>
- **Tried:** 1. <attempt> — <why it failed> 2. …
- **Hypothesis:** My read: <cause>
- **Options:** A) … B) … (one is always "park it and do <next task>")
- **Needs from Champ:** <the decision or resource>
- **Resolution:** <when resolved: the fix in one line, and the gotcha added>
```

## B-001 — A demon whose chunk unloads never comes back, so the unload GameTest's return can't pass
- **Status:** resolved
- **Date:** 2026-10-24 · **Task:** slice 5c-1 (D-060, D-062), GameTest `an_unloaded_demon_leaves_alive`, spec `m1-combat-core` Edge Cases ("Chunk unload or logout mid-fight", S617)
- **Symptom:** `./gradlew runGameTestServer --dependency-verification strict` → `kimetsu:an_unloaded_demon_leaves_alive ... stuck at stage 4 ...; as it left: holder 34 St: minecraft:full Ch: minecraft:full INACCESSIBLE, removed UNLOADED_TO_CHUNK; ... the entity: in the level false, removed UNLOADED_TO_CHUNK` → `1 required tests failed`. Stages 0–3 pass (it leaves alive: `MobLeft`, no `MobDied`).
- **Files:** src/main/java/dev/champ/kimetsu/gametest/CombatGameTests.java, src/main/java/dev/champ/kimetsu/entity/M1Demon.java, src/main/java/dev/champ/kimetsu/entity/TestDummy.java
- **Tried:** 1. (previous session) Unforce the demon's own chunk so it unloads — the demon is removed `UNLOADED_TO_CHUNK` and, being `noSave`, is never written, so nothing comes back. 2. (this session, `docs/state.md`'s fix) Keep the chunk loaded but inaccessible with a forced chunk 3 away (ticket level 34) — the probe shows the chunk still loaded as a full chunk, and the demon removed `UNLOADED_TO_CHUNK` all the same.
- **Hypothesis:** My read, verified in `ref-src/`: `PersistentEntitySectionManager.updateChunkStatus` puts every chunk that goes HIDDEN (any level past 33) in `chunksToUnload`, and `processUnloads` (`ServerLevel.tick` → `entityManager.tick()`, the same server tick) stores and unloads its entities whatever the chunk holder does (`processChunkUnload` → `unloadEntity` → `UNLOADED_TO_CHUNK`). `shouldBeSaved()` is true for the demon, so it's "saved", but `noSave` stops the write. So in 26.3 "out of tracking" means "unloaded" for entities: the same object only comes back if the chunk's entity load is still pending when it hides, a race no test can set up. The plan's premise (D-060 step 4: "an unloaded chunk hands back the same object") and `docs/gotchas.md` line 16 ("a chunk that only goes hidden hands back the same object") are both wrong. The `away` rejoin in `M1Demon` and `TestDummy` is right for that race but otherwise never runs; spec S617 ("saved meters persist; the combatant reloads NEUTRAL") needs the demon saved.
- **Options:** A) Save the demon now, in 5c-1: drop `noSave`, write its HP, Hunger, Posture and carried timers in its save data, and a demon loaded from disk rejoins with them; the test unloads for real and waits for it to load. About a day; a save format from M1 on (version it), a bigger diff before review. B) Park the return: 5c-1's test asserts what 5c-1 does (it leaves alive and is gone, never counted dead), 5c-1 goes to review and commit, and saving the demon becomes 5c-2's first item with its own test. About an hour now and a day in 5c-2. C) Gone is gone for M1: an unloaded demon just vanishes, as vanilla's `noSave` mobs do; S617 amended for M1 and persistence moved to M3's demon core. About an hour; every player death in the grey box ends the fight (the death screen unloads the chunks, gotcha). D) Keep the demon's engine state in memory by its UUID and save only the vanilla entity: HP survives a restart through vanilla's health, Hunger and Posture don't. About a day; parked state needs evicting.
- **Needs from Champ:** which option. Stage 4 of the test changes only with his pick.
- **Resolution:** Champ picked B (D-063): the GameTest now checks that the demon leaves alive, stays gone with its chunk back and is never counted dead; saving the demon so it returns is 5c-2's first item. `docs/gotchas.md` line 16 corrected: a hidden chunk unloads its entities in the same tick, loaded or not.
