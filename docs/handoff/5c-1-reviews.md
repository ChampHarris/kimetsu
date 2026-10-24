# Slice 5c-1 reviews (2026-10-24)

The two outside reviews of the uncommitted 5c-1 diff (`git diff HEAD`, HEAD ba38dcc, plus the untracked `FrameGate`, `FrameMeter` and their tests), against the plan's 5c-1 section, the spec and D-060 to D-063. Champ's call: fix the blocker first, in a fresh session, then commit and push (with a75d779, ba38dcc).

Gate both reviewers saw: `./gradlew spotlessCheck build runGameTestServer --dependency-verification strict` → BUILD SUCCESSFUL; core JUnit 788, sims 4, mod JUnit 25, GameTests "All 16 required tests passed".

## Netcode review: commit after one fix

1. **BLOCKER: epoch churn gets past the frame meter.** `core/.../fight/FrameGate.java:79` and `:138-147`, `core/.../judge/FrameMeter.java:85`. A client sends frames with no deltas and changes the `epoch` it claims every frame (N, N+1, N, ... or counting up). `StampCheck.java:84-88` takes a claimed future epoch as current; each epoch change restarts the meter's baseline with no credit; the held merge "is of another epoch" so it flushes through `alone()`, which charges `frames -= 1` with no floor (`debt()` checks only the event bucket). Every arrival then lands on the main thread, up to the 200/s flood line, 5× the 40/s meter. With deltas, event debt catches it past 64; empty frames still cost main-thread time. No test covers it. **Fix:** pass the published record's epoch into `gate(...)` from `ServerNet:64`; clamp the claimed epoch to `min(f.epoch(), serverEpoch)` for the meter and the flush check; flush only when the clamped epoch is above the held merge's; bound frame debt like event debt (e.g. `frames < -frameBurst` → abuse); test alternating and rising epochs.
2. NON-BLOCKER: `MobLeft` reaches nobody on a chunk unload. `ServerLevel.EntityCallbacks.onTrackingEnd:2080` runs `removeEntity` (StopTracking → `Trackers.stop`) before `onRemovedFromLevel`, and `MobLeft` goes out at a later tick's `bury()` (`CombatWorld.java:527`), when `Routing.reaches` is false for everyone. `ClientView.mobs` (`ClientView.java:43-44`) keeps the stale `MobState`; `ClientCombat.onLeave:321-326` clears `combatants` but not the view. No leak; under-delivery. Fix: `onLeave` also drops the mob from `view`.
3. NON-BLOCKER: a stale pong after `ClockEpoch` on a rules swap. `CombatServer.java:282` sends `ClockEpoch` before `c.onTickStart()` publishes the new record; a ping answered meanwhile (`ServerNet:43`) uses the old record and spends `snapNext` in `ClockEstimator.onRecord`; the next `ClockRecord` snaps again (`:99`), so it self-corrects. Optional fix: publish (or null) `published` before `epochMoved`.
4. NON-BLOCKER: `retime` resets `Snapshot.lastSeq` to −1 (`PlayerSession.java:346`, a fresh `PlayerIntake` loses the ring, `settledSeq` and held presses). Carry it, or make it a step-5 prerequisite. Also: snapshots go only on change, so reconciliation will need its own ack or a snapshot when the seq moves.
5. NON-BLOCKER: wrong-direction packets log a WARN each (`NetworkRegistry.getCodec:261-264`) before the flood count sees them. Any NeoForge server has it; note it in D-056's amendment.

Sound: the channel split and `VERSION` 2 refusal (`NetworkComponentNegotiator:173`), per-direction codecs, `Ready` once, echoes only in flight, the ping bucket, forged stamps (credit min(stamp, arrival+80 ms), forward-only baseline), RTT independence at 20/150/250 ms, the thread handoff, the epoch order (DataSync → `ClockEpoch` → cancels), `MobLeft`'s ~13 bytes to trackers only, merge bounds.

## Code review: ready to commit, no blockers

1. Should fix (same as netcode 2): `Routing.java:30-31` with `ClientView.java:44`: `MobLeft` never reaches the trackers (`ServerEntity.java:294` removes pairings in the same call). Route it to every session in the arena, or drop the `ClientView` entry when the client's entity goes. Nothing tests the routing.
2. `CombatServer.java:276-287`: no test that `ClockEpoch` precedes the rebuild's cancels; D-061 defers it to 5c-2's session seam. Order read as right.
3. Same as netcode 4 (`retime`, around `PlayerSession.java:339-353`).
4. Missing edge tests: a mob leaving mid-clash ends it a tie (`CombatWorld.java:1353`); the 256-frame ring overflow in `PlayerIntake.open` (~:375) reports the evicted frame settled even when it isn't; the same-object rejoin (`M1Demon.tick`, `TestDummy.tick`) can't be set up (B-001).
5. The kick is unconditional (`ServerNet.cut`); spec Config (line 494) and Edge Cases (line 606) want a SYNCED switch; add "whether flooding kicks" to 5d's config list.

Nits: event-debt abuse is found on the main thread (`takeMerge`) but the disconnect queues only on the next message, so a silent client stays connected; frame-token debt has no floor (only that client suffers; ≤ ~60 frames/s reach the main thread); `aReloadKeepsTheDemonsWounds` drops the reload future, so a failed reload reads "no reload has swapped in yet"; per-message `Pass`/list and per-merge `ArrayList` allocation on the network thread (acceptable). Scope creep: none. Weakened tests: none (migrations only). Core purity, nullness, tuning numbers and every 26.3 API: checked.

## Netcode re-review of the fix (2026-10-24): items 1 and 2 closed, three more fixed (D-065)

1. **BLOCKER, fixed:** back-dated stamps (31 years back, each a second after the last) bought a full bucket every frame; 1,000 of 1,000 reached the main thread. A stamp now counts no earlier than its arrival − `StampCheck.STAMP_RANGE_MICROS` (10 s).
2. **Fixed:** in the window between `ClockEpoch` and the record carrying its epoch, a new-epoch frame joined the old merge. The flush now counts claims against the announced epoch (`CombatServer.epoch`, volatile); the meter keeps the record's epoch, so a baseline is never started on the old timeline (the reviewer's version, the announced epoch for both, would have cut an honest client after a backlog drop of more than 10 s).
3. **Fixed:** a switch between metering by stamps and by arrival restarted with no credit, so `/tick freeze` and step could push an honest client into `FRAME_DEBT`; it now credits the server's own time since the frame before.
4. Open (5c-2 or 5d): a mob tracked again shows no state until it changes (send its `MobState` on StartTracking); a gone mob's strikes stay drawn until impact + 40 ticks.
5. Tooling, now a gotcha: `./gradlew test --tests X` filters `:core:test` too; use `./gradlew :test --tests X`.

## Netcode re-review, round 2 (2026-10-24): findings 1–3 closed; two more fixed (D-065)

- **A, BLOCKER, fixed:** the clamp bounded a claimed epoch only from above, so claims counting up from `Integer.MIN_VALUE` in pairs (10 s back, then ahead) were a rise every pair: 1,000 of 1,000 reached the main thread. A claim now starts an epoch only when it reaches the server's (the record's for the meter, the announced for the flush); any other credits nothing. Not the suggested join-epoch floor: a login in the window between the epoch moving and its record would floor above the client's first record.
- **B, fixed:** a freeze switch reset the baseline to the frame's stamp, so back-dating each switch frame sold a bucket a step; the baseline never moves back on a switch, and an epoch clears it (also while frozen).
- C (nit, fixed): a javadoc line past 120 columns.

## Netcode re-review, round 3 (2026-10-24): A and B closed; verdict commit

- Cheat probes at 200 frames/s for 5 s: at most 221 of 1,000 reach the main thread (pairs counting up from `MIN_VALUE`: 41, then `FRAME_DEBT`); a freeze flipped every tick with back-dating on the flips: 336, inside the documented 2x bound and only while an operator steps. Honest probes at 20, 150 and 250 ms RTT (two epochs inside one RTT, frozen at join, 15 TPS, joining a record behind): never cut, every press in order.
- L1, known limit (Champ: log it): epochs faster than a client's RTT starve and cut it; vanilla can't cause it (D-065).
- L2, 5c-2: no test that `ServerNet` passes `max(r.epoch(), c.epoch())` as the announced epoch; it belongs with the session seam, beside the `ClockEpoch`-order test.
