---
name: milestone-check
description: Milestone gate — build, tests, GameTests (with save migration), specs, textures and the vanilla blend check, audio, the vanilla scope and restyle coverage, placeholders left in the art log, the townsfolk name check, and the sims — producing a checklist with evidence. Run only when Champ asks to close a milestone or make a build to play.
argument-hint: [milestone, e.g. M1]
disable-model-invocation: true
allowed-tools: Bash(./gradlew build*) Bash(./gradlew :core:test*) Bash(./gradlew runGameTestServer*) Bash(python tools/check_spec.py *) Bash(python3 tools/check_spec.py *) Bash(python tools/texture_lint.py*) Bash(python3 tools/texture_lint.py*) Bash(python tools/vanilla_style.py *) Bash(python3 tools/vanilla_style.py *) Bash(python tools/audio_lint.py *) Bash(python3 tools/audio_lint.py *) Bash(python tools/refsrc.py *) Bash(python3 tools/refsrc.py *) Bash(python tools/vanilla_scope.py *) Bash(python3 tools/vanilla_scope.py *) Bash(python tools/vanilla_coverage.py *) Bash(python3 tools/vanilla_coverage.py *) Bash(python tools/townsfolk_names.py *) Bash(python3 tools/townsfolk_names.py *) Bash(python tools/sims/duel_sim.py *) Bash(python3 tools/sims/duel_sim.py *) Bash(python tools/sims/night_sim.py *) Bash(python3 tools/sims/night_sim.py *) Bash(git status*) Bash(git log*) Read Glob Grep
---

# /milestone-check $ARGUMENTS

Run every check, keep going after failures, and report all of them. Nothing is "probably fine".

| # | Check | Command | Pass when |
|---|---|---|---|
| 1 | Clean tree | `git status --porcelain` | empty, or Champ says go anyway |
| 2 | Build | `./gradlew build` | BUILD SUCCESSFUL |
| 3 | Unit tests | `./gradlew :core:test` | 0 failures |
| 4 | GameTests | `./gradlew runGameTestServer` | exit code 0, including the test that loads the previous milestone's fixture world (save migration; from M5, which starts a new Taishō world, an older world must still load) |
| 5 | Specs | `python tools/check_spec.py docs/design/specs/*.md` | every APPROVED spec passes |
| 6 | Textures | `python tools/texture_lint.py` | 0 errors |
| 7 | Blend with vanilla | `python tools/vanilla_style.py --all` | every mod block and item texture inside its vanilla family's envelope, or Champ accepts the listed exceptions |
| 8 | Audio | `python tools/audio_lint.py --loudness` | 0 errors |
| 9 | Vanilla scope | `python tools/refsrc.py --mcmeta 26.3` (once per version), `python tools/vanilla_scope.py --stages`, then `--check`; in game, MCTest searches JEI and the creative tab for netherite, a vanilla sword and a Strength potion; while vanilla villagers exist, summons three fletchers and three weaponsmiths at each level 1–5 (a villager gets only its own level's offers) plus a cleric at level 3 and a mason at level 5, and reads every offer; while skeletons exist, summons a skeleton holding a bow with its mainhand drop chance forced to 1 (check the 26.3 NBT name in `ref-src/`) and kills it | `milestone` in `art/vanilla-scope.json` is $ARGUMENTS; exit 0: nothing unclassified, no orphan, dead recipe, empty first trade level or unwaived worldgen leak at any milestone up to the target, no rule entry that can't apply, the generated datapack and lock fresh (rules, data and tool hashes); none of the three shows in game, no offer sells a sword, bow, crossbow, arrow or tipped arrow, the level-3 cleric, the level-5 mason and the level-5 weaponsmiths have no offers (the scope leaves those levels empty) and the log shows no error from their empty trade sets (on an error, set `empty_trade_levels` to `lower` in the rules file and rerun `--datapack`), and the skeleton drops no bow |
| 10 | Restyle coverage | `python tools/refsrc.py --assets` then `python tools/vanilla_coverage.py --strict --json` | exit 0: every restyled vanilla texture has its override (or Champ accepts the listed debt) |
| 11 | Placeholders | read `art/art-log.csv` | every `status=placeholder` row listed; Champ decides what can stay |
| 12 | Townsfolk names | `python tools/townsfolk_names.py --check` | no canon names in 2,000 towns, repetition caps held |
| 13 | Sims | `python tools/sims/duel_sim.py --fights 2000` and, from M3, `python tools/sims/night_sim.py --nights 300 --moon quarter` | inside the targets of the approved specs, or the change is explained |
| 14 | Changelog | `CHANGELOG.md` | has an entry for $ARGUMENTS |

Then list what Champ must decide (known issues to accept, debt to carry), and update `## Now` in `docs/state.md`. When the next milestone starts, its first task is to bump `milestone` in `art/vanilla-scope.json` and regenerate the pack (`--datapack src/generated/scope --lock`), so the new removals land with that milestone's work.
