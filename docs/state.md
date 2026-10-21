# State

`/session-close` rewrites the `## Now` section at the end of every session. Keep it under 25 lines; history goes below.

## Now
- **Date:** 2026-10-20 · **Milestone:** M1. The M1 spec `m1-combat-core` is **APPROVED** (rev 5a694639, D-032). `core/` slices 1 (clock, judge, meters) and 2 (the state machine, the input buffer and the Timeline) are built test-first and green; the quality gates run in every build (D-033). Slices 1 and 2 and the gates are committed and pushed.
- **Slice 2 (plan `lucky-petting-rocket`):** `core/machine/`:
  - `Combatant`: Rule 3's twelve states, the chain, forms and the radial, Feint, cancels, Recovery Breath, being hit, Tech and grabs.
  - `Timeline`: presses at their stamps, the back-dating limit, the barrier with D-034's held sweeps, the watermark, the 16-tick history, taps, and client `rebase` onto `settledThrough`.
  - `core/data/`: `FrameData`, `Style`, `MachineRules`, `Moveset`.
  - Decisions: D-034 (Champ), D-035 (Q1–Q5 on the plan's recommendations), D-036 (craft calls), D-037 (the Timeline's contract with slices 3 and 5), D-038 (Champ: Mushin pays when the finisher strikes; a long hold may un-judge an Inhale).
- **Evidence:** `./gradlew spotlessCheck build runGameTestServer --dependency-verification strict`: exit 0; 432 `core/` tests (240 in `machine/`, including a 130-case state-by-press table, a random-lateness replay property test and a reflection check of every snapshot field); GameTests 3/3. Mutation checks: 12/12 on the first build, and 13/14 on the review fixes (the 14th is equivalent). Traces of five mini-fights are in `core/build/traces/`.
- **Reviews:** a code review (REQUEST CHANGES: 4 high) and a netcode review (2 high) are folded in, each finding with a regression test:
  - Reads settle before answering.
  - `rebase` keeps in-flight presses and the newer snapshot.
  - A Posture break waits for a knockdown or a grab to end.
  - Form releases are buffered.
  - Presses that arrive after a verdict act as they would have on time.
  - The input horizon waits for stall-held presses (a slice-1 fix).
  - The strike bookkeeping is guarded.
- **Next, in order:**
  1. `core/` slice 3: hits and sweeps (including D-034's late sweeps, judged against the target as it stood then), the strike scheduler, the deflect judge wired to the machine (claims only from states that can guard), and a `FeintSettled` for every claimed press. Its plan goes to Champ first. Then slice 4 (the demon, the neck, the clash) and slice 5 (the mod).
  2. The vanilla-scope Java parts (R7-01).
- **Handed to slices 3 and 5** (D-037, the reviews):
  - Slice 3: log strike results first at an impact tick.
  - Slice 5: the server derives `fallBlocks` from the pose history; snapshots go as deltas against the last acked one, sent when the value changes (not the meters' versions); the client predicts `minActive` or accepts a snap past 250 ms RTT; telemetry for replay depth, floored presses and the barrier's hold time.
- **Spec erratum to fold in at the next touch:** Performance Budget's "about 1.5 KB per combatant": full snapshots make a player's Timeline about 42 KB (34 copies of about 1.2 KB). That is trivial for a server, but the line is wrong.
- **Unverified until the grey box:** the real dodge windows and chipping, how often players read the guard counter (casual target 60%+), whether MCTest's input reaches the game's input events, AzureLib's gradual catch-up, and real tick-start jitter and clock drift.
- **Waiting on Champ:** merging the accepted R6, R8 and R9 items into the live GDD; the R7 decide list; R8-05 and R8-06; R6-22 for M5; D-025's two library additions; R9-07 before M2. His own inbox and status-line changes are uncommitted in the tree.

## History
- 2026-10-12 to 10-16: GDD Rounds 1–5 on claude.ai (see `docs/gdd/merge-log.md`).
- 2026-10-18: Claude Code handoff pack (1.21.1 plan). Same day: platform research moved the recommendation to 26.3 (R6-01); Claude Code kit and Round 6 written.
- 2026-10-18 (later): Champ's Round 7 calls: menial vanilla stays, the mod's art blends with vanilla, demons spawn every night, custom achievements, townsfolk replace villagers, best tools. Round 7, ADR 0002 and the vanilla-scope tooling written.
- 2026-10-19: an adversarial review of Round 7 found the scope assumed the finished world while the pack ships at Build 0, plus field-name and load-order bugs that would stop the world loading. Fixed: the staged scope, worldgen-leak checks, the custom spawners, potion tags, the Corps advancements' conditions, and the docs.
- 2026-10-19 (later): two more reviews. Trades and mob equipment bypassed the loot strip (the weaponsmith still sold swords), waxed bulbs and ancient cities' blocks leaked, creeper discs orphaned at M3, cobweb string silently needed the removed sword, and the M5 safety net would have deleted villagers it should convert. Fixed in the tool, the rules, the pack and the docs; the tool now reads structure templates.
- 2026-10-19 (night): a third pair of reviews. Removing the ominous bottle at Build 0 had silently orphaned the ominous vault's rewards (Bad Omen comes only from the bottle), an axe could scrape a kept waxed bulb into a removed one, three trade levels emptied, and the base solve missed 19 vanilla sources. Fixed in the tool, rules, pack and docs; the docs now say M5 starts a new world.
- 2026-10-19 (late): a fourth review. Filling an emptied trade level from the level below would repeat a villager's own offers (certainly for four of the five levels), the old-world count ignored placed blocks (175 at M5, not 81), datagen would have deleted the scope pack in `src/generated/resources/`, mob-laid blocks counted as only placed, and the ocelot note overstated NeoForge. Fixed; emptied levels now stay empty by default.
- 2026-10-19 (Claude Code, first session): the kit committed as the M0 baseline and moved to `C:\Users\morga\dev\kimetsu`; Champ accepted R6-01, R6-02 and the mod identity; the Gradle project, `core/`, the toolchain and the Build 0 GameTests built. The GameTests caught two world-load bugs that four paper reviews couldn't (the trim advancements, NeoForge's recipe overrides); both fixed.
- 2026-10-19 (Claude Code, second session): Round 8 (3D animation standards), the effort check (D-024), the library review (D-025) and IntelliJ's MCP server connected.
- 2026-10-20: `/forge "M1 technical spec"` wrote the M1 spec, Round 9 and four briefs. The red team found the duel-sim bug behind Round 6's duel table (the demon never recovered Posture between strings); the netcode review found that vanilla's tick schedule drifts from wall time under sustained lag, so the mod keeps its own. Champ accepted the foundations and Claude's recommended answers (D-026, D-027).
