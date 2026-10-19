---
paths:
  - "**/*.java"
  - "**/build.gradle"
  - "**/settings.gradle"
  - "gradle.properties"
---

# Java and NeoForge rules (Minecraft 26.x)

**Verify, don't recall.** Minecraft 26.x is unobfuscated (Mojang names) and newer than most training data. Before using any Minecraft, NeoForge or library type, find it in `ref-src/` (`rg -n "class PostChain" ref-src/`) or with go-to-definition. Read the NeoForge primer for the version (`docs.neoforged.net/primer/docs/26.3/`) before touching rendering, GUI or networking. If a symbol isn't in `ref-src/`, it doesn't exist in this version.

## Modules
- `core/`: plain Java 25 plus JUnit. The rules live here: meters (Breath, Posture, Rhythm, Hunger), the Pulse clock, frame data, the combat state machine, hit resolution. No Minecraft, NeoForge or library imports (the PreToolUse hook blocks them). Deterministic: time comes in as ticks and milliseconds, never from `System.currentTimeMillis()`.
- The mod module adapts `core/` to the game: events, attachments, payloads, entities, rendering. Rendering stays in a `client.render` package (see `rendering.md`).
- Gradle: use `implementation`, not `modImplementation` (26.x is unobfuscated). Pin every dependency to an exact version in `gradle.properties`. Parchment is unnecessary on 26.x.

## Patterns
- Per-entity state: Data Attachments, synced only when the value changes, never every tick.
- World state: `SavedData`. Content and every tunable number: datapack registries (`content-data.md`). No balance numbers in Java.
- Networking: `CustomPacketPayload` + `StreamCodec`, registered in the payload registration event. Server-authoritative: the client predicts animation and the Pulse; the server decides hits, meters and outcomes. **Need-to-know:** never send a client information its player isn't entitled to (disguises, Threads, Ghosts, scent).
- Timing: 20 TPS, 1 tick = 50 ms. Beat maths in milliseconds; input packets carry tick + sub-tick fraction and the server judges with that stamp when it's within a jitter tolerance of `arrival − RTT/2` (R6-05). PvE windows never change size (cues carry their impact tick). Only in PvP does the defender's late edge get `min(RTT/2 + interpolation delay, 80 ms)`.
- Damage: map the GDD pipeline onto the 26.x damage events after checking their names and order in `ref-src/` (they changed shape across 1.21.x). Mod damage replaces vanilla invulnerability frames with per-attacker, per-swing cooldowns.
- Config: `ModConfigSpec` (client: assist level, reduced shake, guard hold/toggle; server: timing windows, caps).
- Logging: SLF4J via `LogUtils.getLogger()`. No `System.out`.
- Hot paths (per tick, per entity): no allocation in loops, no streams, no reflection.

## Anti-patterns (D-018)
- **Per-tick polling where an event exists.** Use NeoForge events, attachment change listeners and scheduled ticks. When a system must tick (the Director, the strike scheduler), it runs at its own rate and does bounded work: the Director once a second, at most 4 spawn attempts per party cluster.
- **Switch-case chains for forms, arts, abilities or bosses.** Each one is a datapack entry decoded by a `Codec`/`MapCodec` into a registered type, and behaviour is an event-driven state machine in `core/`. Adding a form adds JSON, not a `case`.
- **Vanilla particle spam for VFX.** Effects go through kfx: batched, pooled, allocation-free render passes on RenderPipelines (`rendering.md`).
- **Mixins as a first resort.** Use an event, a registry or a data hook first. A Mixin needs a written reason and the smallest target; never `@Overwrite`.
- **Client-decided outcomes.** The client predicts; the server decides hits, meters, spawns and achievements.

## Vanilla scope and generated data
- `src/generated/scope/` is written by `python tools/vanilla_scope.py --datapack src/generated/scope --lock`. Don't edit it by hand; change `art/vanilla-scope.json` and rerun the tool. CI fails when it's stale.
- Java's part of the scope: the strip-items loot modifier (registered in `NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS`), `BuildCreativeModeTabContentsEvent` removal of `#kimetsu:removed`, blocking Nether and End travel, and converting any vanilla villager that still appears (`docs/gotchas.md`).

## Tests
- `core/`: JUnit for every rule with a number in it (windows, tier changes, Feint cost doubling, one on-beat credit per beat).
- In-world: GameTests (registry-based since 1.21.5: test instances plus a registration event; check the current API in `ref-src/`). Run `./gradlew runGameTestServer`; its exit code is the number of failed required tests.
- Report the exact command and the pass/fail counts. A change is not done until they're in the transcript.
