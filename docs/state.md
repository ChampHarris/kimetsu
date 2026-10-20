# State

`/session-close` rewrites the `## Now` section at the end of every session. Keep it under 25 lines; history goes below.

## Now
- **Date:** 2026-10-20 · **Milestone:** M1. The technical spec is written and Champ answered its open questions; the build starts next.
- **Decided by Champ today:** D-026 (Open Question 1: R6-03 to R6-09 and the v0 numbers as R9-10 and R9-11 revise them, and R8-01 to R8-04) and D-027 (Open Questions 2 to 11 on Claude's recommendations). D-006 is Decided; D-008, the Nichirin seal, is withdrawn.
- **Written (`/forge "M1 technical spec"`):**
  - `docs/design/specs/m1-combat-core.md`: status REVIEW, `check_spec.py` PASS. One `core/` engine that the server runs for everyone and each client runs for itself (Overwatch's model), beats of whole ticks, NPC strikes judged at the impact.
  - `docs/design/round9-revision.md` (R9-01 to R9-11) and four briefs in `docs/research/briefs/m1-combat-core-*.md`.
  - The red team found that `duel_sim.py` never let the demon recover Posture between strings, so Round 6's duel table came from a bug. The sim now steps per tick (`test_duel_sim.py`, 21 tests), and the demon is retuned: wins 100 / 99.7 / 57.6 / 95.5% (expert / average / casual / casual on assist), about 40 s for an average fight.
  - `netcode_sim.py --m1`: 2.5–3.7% of taps judged worse than earned up to 800 ms RTT (all of it frame-rate sampling), and the client predicts the server's verdict at least 99.8% of the time. Also new: `clash_sim.py`, `grab_sim.py` and `duel_m1_report.py`. Tool tests 64/64 OK.
- **Next, in order:**
  1. Champ: answer the three open taste questions (D-027: which Flow finishers, whether "Kata" and "Flow" are renamed, the guard stance's look), then set the spec to `APPROVED`. R9-02, R9-04, R9-05 and R9-07 were in no open question and stay PROPOSED until he says.
  2. A duel-sim pass for the deflect-counter (D-027, 11b), before the demon's brain is built.
  3. Build in the spec's order: `core/` test-first (the clock, the judge, the state machine), the server, a server-confirmed client, the first gate run in single player (5 testers, 2 of them on assist level 2 or 3), then client prediction (4–6 days) and the two-client test, then travel.
  4. The vanilla-scope Java parts (R7-01), first the strip-items loot modifier: until it's registered every world load logs "Unknown registry key ... kimetsu:strip_items".
- **Unverified until the grey box:** the real dodge windows and how often players chip the guard, a tick rate that changes mid-fight, whether MCTest's input reaches the game's input events, whether AzureLib's catch-up can be gradual, and the deflect-counter.
- **Waiting on Champ:** merging the accepted R6, R8 and R9 items into the live GDD; the R7 decide list (`round7-revision.md`); R8-05 and R8-06; R6-22 for M5; the library review's two additions (D-025); the M2 cold open turns the player with a bite, which canon forbids (R9-07), so its script changes before M2.
- **Decided earlier:** private build (D-010); the R7 goals (D-012 to D-016); platform and animation stack (D-004, D-005); mod identity (D-019); repo location (D-020).

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
