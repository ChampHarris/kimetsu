# Tech brief: M1 combat core (`m1-combat-core`)

Two api-verifier passes on 2026-10-19 against `ref-src/` (Minecraft 26.3, NeoForge 26.3.0.92; `ref-src/VERSION` matches `gradle.properties`) and the pinned libraries. Part 1 is the server side; part 2 the client side and the animation and AI libraries. Paths are relative to `ref-src/minecraft/` unless they say otherwise. Evidence only; the design is in `docs/design/specs/m1-combat-core.md`.

# Part 1: server side

FML's `ModConfig` and `ModContainer` aren't in ref-src; they were read from FML 12.0.12's sources jar in the Gradle cache (NeoForge 26.3.0.92 requires loader 12.0.12).

**Gap:** `ref-src/vanilla-data/` has no `damage_type/` or `tags/damage_type/` folder. Tag names come from `DamageTypeTags.java` and their contents from vanilla's datagen provider `net/minecraft/data/tags/DamageTypeTagsProvider.java`; the shipped JSON hasn't been read. Fix: `tools/refsrc.py --mcmeta` should also fetch `damage_type` and `tags/damage_type`.

## 1. Damage events [tech]

### The damage path, in firing order (`net/minecraft/world/entity/LivingEntity.java`)
1. **`hurtServer(ServerLevel, DamageSource, float)`** (:1223) is the entry point; `hurt` is a deprecated final wrapper (`Entity.java:1968`).
   - `Player.hurtServer` (`player/Player.java:699`) runs first: it checks `abilities.invulnerable` unless the source is tagged `BYPASSES_INVULNERABILITY` (:704), then scales damage by difficulty through `source.type().scaling()` (:714).
   - `ServerPlayer.hurtServer` (`server/level/ServerPlayer.java:1031`) applies the team and PvP check `canHarmPlayer`.
2. **`isInvulnerableTo`** (:1224, declared :4214) calls `Entity.isInvulnerableToBase` (`Entity.java:3134`), which fires NeoForge's **`EntityInvulnerabilityCheckEvent`** (`net/neoforged/neoforge/event/entity/EntityInvulnerabilityCheckEvent.java:20`). `ServerPlayer.isInvulnerableTo` (:1397) also returns true while `!connection.hasClientLoaded()`.
3. **`LivingIncomingDamageEvent`** (:1236–1240, `CommonHooks.onEntityIncomingDamage` :329) fires before the shield, armour and i-frames.
4. Shield: **`applyItemBlocking`** (:1254, declared :1378) fires **`LivingShieldBlockEvent`** (:1406), only when the defender is using an item with `minecraft:blocks_attacks`.
5. Cooldown (:1272): `if (this.damageCooldownTime > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN))`. If `damage <= lastHurt` the hit is dropped (:1273–1276); otherwise only the difference goes through, with side effects off (:1280–1284).
6. **`actuallyHurt(ServerLevel, DamageSource, float)`** (:2035): armour (:2039, `getDamageAfterArmorAbsorb` :1979, skipped for `BYPASSES_ARMOR`), magic absorb (:2040, `getDamageAfterMagicAbsorb` :1990: `BYPASSES_EFFECTS` skips all of it, `BYPASSES_RESISTANCE` the Resistance effect, `BYPASSES_ENCHANTMENTS` enchantments), then **`LivingDamageEvent.Pre`** (:2043), absorption hearts, `setHealth` (:2064). `Player.actuallyHurt` (:764) has its own copy, Pre at :772.
7. Back in `hurtServer`: knockback (:1313, skipped for `NO_KNOCKBACK`; `dealDefaultKnockback` :1360, `knockback` :1714), the totem check (:1319), **`die(source)`** (:1325), which fires **`LivingDeathEvent`** (:1533; players at `Player.java:542`, `ServerPlayer.java:930`).
8. **`LivingDamageEvent.Post`** (:1355), then the damage container is popped.

### The events (package `net.neoforged.neoforge.event.entity.living`)
| Event | Read / change the amount | Cancelable |
|---|---|---|
| `LivingIncomingDamageEvent` (:28) | `getAmount()` :47, `getOriginalAmount()` :52, `setAmount(float)` :59, `addReductionModifier(Reduction, IReductionFunction)` :70, `setInvulnerabilityTicks(int)` :84, `getContainer()` :37 | yes; cancelling makes `hurtServer` return false |
| `LivingShieldBlockEvent` (:26) | `getBlockedDamage()`/`setBlockedDamage(float)` :64/:84, `setBlocked(boolean)` :123, `setShieldDamage(int)` :94, `getOriginalBlock()` :101 | no; use `setBlocked(false)` |
| `LivingDamageEvent.Pre` (:48) | `getNewDamage()` :67, `setNewDamage(float)` :81, `getContainer()` :57 | no; killing the entity here throws (`LivingEntity.java:2045`) |
| `LivingDamageEvent.Post` (:97) | read-only: `getInflictedDamage()` :139, `getHealthDamage()` :144, `getBlockedDamage()` :149, `getReduction(Reduction)` :167, `getPostAttackInvulnerabilityTicks()` :159 | no |
| `LivingDeathEvent` (:32) | `getSource()` :40 | yes; the entity doesn't die, but its health stays ≤ 0 |
| `LivingKnockBackEvent` (:36) | `get/setStrength` :49/:73, `get/setRatioX/Z` :53–81 | yes (checked at `LivingEntity.java:1716`) |

`DamageContainer` (`net/neoforged/neoforge/common/damagesource/DamageContainer.java:41`): `Reduction` (:42) is INVULNERABILITY, ARMOR, ENCHANTMENTS, MOB_EFFECTS, ABSORPTION; `setNewDamage` :91, `addModifier` :116, `setPostAttackInvulnerabilityTicks` :135, `setShouldCauseSideEffects` :152; post-attack invulnerability defaults to **20 ticks** (:64).

### Invulnerability frames
- **`invulnerableTime` is no longer the i-frame counter.** Living i-frames are **`LivingEntity.damageCooldownTime`** (`public int`, :242), counted down in `LivingEntity` tick (:495) and `ServerPlayer.tick` (:602). `Entity.invulnerableTime` (private, `Entity.java:260`) is a separate temporary invulnerability (skeleton traps, conversions) that only `BYPASSES_INVULNERABILITY` passes.
- **`minecraft:bypasses_cooldown`** (`DamageTypeTags.java:12`, empty in vanilla: `DamageTypeTagsProvider.java:218`) skips the i-frame check.
- **Trap:** a bypass-cooldown hit still sets `damageCooldownTime` to the container's post-attack invulnerability (default 20) at :1292, which shields the target from the next vanilla hit for 10 ticks. Fix: `setInvulnerabilityTicks(0)` in `LivingIncomingDamageEvent` for mod damage.

### Damage-type tags (`net/minecraft/tags/DamageTypeTags.java:8–43`)
damages_helmet, bypasses_armor, bypasses_shield, bypasses_invulnerability, bypasses_cooldown, bypasses_effects, bypasses_resistance, bypasses_enchantments, is_fire, is_projectile, witch_resistant_to, is_explosion, is_fall, is_drowning, is_freezing, is_lightning, no_anger, no_wolf_retaliation, no_impact, always_most_significant_fall, wither_immune_to, sulfur_cube_with_block_immune_to, ignites_armor_stands, burns_armor_stands, avoids_guardian_thorns, always_triggers_silverfish, always_hurts_ender_dragons, no_knockback, always_kills_armor_stands, can_break_armor_stand, bypasses_wolf_armor, is_player_attack, burn_from_stepping, panic_causes, panic_environmental_causes, mace_smash. NeoForge adds `Tags.DamageTypes` (`net/neoforged/neoforge/common/Tags.java:2102–2124`): is_magic, is_poison, is_wither, is_environment, is_physical.

For mod damage: bypasses_armor, bypasses_shield, bypasses_enchantments, bypasses_effects (makes bypasses_resistance redundant), bypasses_cooldown, no_knockback (the mod applies its own). **Not** bypasses_invulnerability: it also gets past creative mode (`Player.java:704`, `Entity.java:3136`) and the totem (`LivingEntity.java:1466`).

### `damage_type/*.json` (`net/minecraft/world/damagesource/DamageType.java:13–21`)
`message_id` (required), `scaling` (required: `never`, `when_caused_by_living_non_player`, `always`; `DamageScaling.java:11–13`), `exhaustion` (required float), `effects` (optional, default `hurt`; `DamageEffects.java:13–18`), `death_message_type` (optional, default `default`). Use `"scaling": "never"` (otherwise `Player.hurtServer` scales by difficulty) and `"exhaustion": 0.0`.

### Making the source
`level.damageSources()` (`Level.java:1096`); `DamageSources.source(ResourceKey<DamageType>, @Nullable Entity)` (`DamageSources.java:79`, the cause is both direct and causing), `source(key, direct, causing)` (:83); both call `getOrThrow(key)`, so a missing JSON throws. `new DamageSource(Holder<DamageType>, direct, causing, @Nullable Vec3)` (`DamageSource.java:34`).

## 2. Vanilla melee [tech]
- **`AttackEntityEvent`** (`net/neoforged/neoforge/event/entity/player/AttackEntityEvent.java:25`, cancelable) fires from `CommonHooks.onPlayerAttackTarget` (:837), called at the top of **`Player.attack(Entity)`** (`Player.java:986–987`); the same hook returns false when `stack.getItem().onLeftClickEntity(...)` returns true (`IItemExtension.java:171`). `Player.attack` also runs on the client (`MultiPlayerGameMode.java:470–473`).
- Server path: `ServerGamePacketListenerImpl.handleAttack(ServerboundAttackPacket)` (:1909): range check `isWithinAttackRange(item, bounds, 3.0)` (:1918), **skips everything if the item has `piercing_weapon`** (:1919), `cannotAttackWithItem(item, 5)` (:1927), then `player.attack` (:1928). Spears: `ServerboundPlayerActionPacket.Action.STAB` → `PiercingWeapon.attack` (:1360–1371).
- Client: `Minecraft.startAttack` (`client/Minecraft.java:1672`) fires `InputEvent.InteractionKeyMappingTriggered` (cancelable; `setSwingHand(boolean)` at `InputEvent.java:331`) at :1709, before any attack packet.
- Attack strength (`Player.java`): `getAttackStrengthScale(float)` :1876, `resetAttackStrengthTicker()` :1884, `getCurrentItemAttackStrengthDelay()` :1866, `cannotAttackWithItem(ItemStack, int)` :1870 (reads `MINIMUM_ATTACK_CHARGE`), `attackStrengthTicker` :279. Also `SweepAttackEvent` (cancelable, :20) and `CriticalHitEvent` (`setDisableSweep` :112).
- Item components (`net/minecraft/core/component/DataComponents.java`): `use_effects` 127, `minimum_attack_charge` 133, `damage_type` 136, `tool` 190, `weapon` 191, `attack_range` 194 (`min_reach`, `max_reach`, `min_creative_reach`, `max_creative_reach`, `hitbox_margin`, `mob_factor`), `blocks_attacks` 213, `piercing_weapon` 216, `kinetic_weapon` 219, **`attack_animation`** 222 (`SwingAnimation(type, duration)`, type `none`/`whack`/`stab`, default whack/6), `interact_animation` 225. **There is no `minecraft:swing_animation`.**
- `diamond_sword` has `weapon`, `tool` (the cobweb rule) and `attribute_modifiers`, and no `blocks_attacks`.
- **A Nichirin can't switch vanilla attacks off by component alone.** No `blocks_attacks` means it can't block; `attack_animation {type: none}` removes the swing; `piercing_weapon` stops the attack packet but turns on STAB, and `attack_range` with `max_reach: 0` is a hack. Recommended: cancel `InteractionKeyMappingTriggered` on the client (`setSwingHand(false)`), and as a server backstop cancel `AttackEntityEvent` or override `onLeftClickEntity` to return true. Keep the sword's cobweb `tool` rule.

## 3. Data Attachments [tech]
Package `net.neoforged.neoforge.attachment`; register in `NeoForgeRegistries.Keys.ATTACHMENT_TYPES` (`registries/NeoForgeRegistries.java:58`).
- Builder (`AttachmentType.java`): `builder(Supplier<T>)` :109, `builder(Function<IAttachmentHolder,T>)` :123; **`serialize(MapCodec<T>)`** :198 (no `serialize(Codec)` overload), `serialize(MapCodec, Predicate)` :210; `copyOnDeath()` :237; **`sync(StreamCodec)`** :274 (all receivers), **`sync(BiPredicate<IAttachmentHolder, ServerPlayer>, StreamCodec)`** :286, `sync(AttachmentSyncHandler)` :263; `build()` :307.
- Holder (`IAttachmentHolder.java`): `hasData` :24, `getData` :39, `getExistingData` :55, `setData` :87, `removeData` :103, `syncData(type)` :122.
- **When it sends** (`AttachmentHolder.java:74–114`): on initial tracking, on **every `setData`** (no equality check), on `removeData`, on **a default-creating `getData`**, and on `syncData`.
- **Who receives it** (`AttachmentSync.syncEntityUpdate` :138–151): players tracking the entity, plus the player itself, each filtered by `sendToPlayer`. Every receiver gets the same bytes (`write` runs once), and the filter must be consistent per holder and player, so per-receiver content or reveal rules that change over time need a custom payload.

## 4. Networking [tech]
- `RegisterPayloadHandlersEvent.registrar(String version)` (`network/event/RegisterPayloadHandlersEvent.java:40`). `PayloadRegistrar` (`network/registration/PayloadRegistrar.java`): `playToClient` :44/:52 (handler-less ones register in `RegisterClientPayloadHandlersEvent`), `playToServer` :60, `playBidirectional` :70/:80, configuration and common variants :87–166, **`executesOn(HandlerThread)`** :180 (**default `MAIN`**; `NETWORK` runs on the network thread), `versioned` :196, `optional()` :209.
- `CustomPacketPayload.Type<T>(Identifier)` is a record (:79); **`createType(String)` (:20) uses the `minecraft:` namespace**, so construct it with `Identifier.fromNamespaceAndPath`.
- `StreamCodec.composite` goes up to 14 fields (`codec/StreamCodec.java:673`). `ByteBufCodecs`: BOOL :63, BYTE :72, SHORT :82, UNSIGNED_SHORT :91, INT :100, VAR_INT :109, LONG :121, VAR_LONG :130, FLOAT :139, DOUBLE :148.
- `PacketDistributor`: `sendToPlayer` :36, `sendToPlayersNear` :51, `sendToPlayersTrackingEntity` :75, `sendToPlayersTrackingEntityAndSelf` :87, `sendToPlayersTrackingChunk` :99. `IPayloadContext`: `player()` :52, `reply` :59, `disconnect` :66, `enqueueWork` :80.
- **No per-payload rate limit.** `ServerCommonPacketListenerImpl.handleCustomPayload` (:101) has no throttle; the only limit is the whole-connection `rate-limit` in `server.properties` (default 0, off; it kicks). The mod must rate-limit its own inputs.

## 5. Datapack registries and reloadable data [tech]
- **`DataPackRegistryEvent.NewRegistry` doesn't exist.** NeoForge 26.3 has **`NewDatapackRegistryEvent`** (`net/neoforged/neoforge/registries/NewDatapackRegistryEvent.java:30`, mod bus): `worldRegistry(key, codec[, networkCodec])` :48/:70 ("only loaded once during server startup"; can sync), and **`reloadableRegistry(key, codec)`** :101 (loaded at startup and on `/reload`, **can't sync**: `DatapackRegistryBuilder.networkCodec` throws "Reloadable datapack registries cannot be synced", `DatapackRegistryBuilder.java:63–66`; read through `server.reloadableRegistries().lookup()`).
- **Reload listener:** `AddServerReloadListenersEvent` (`event/AddServerReloadListenersEvent.java:24`): `addListener(Identifier, PreparableReloadListener)`; `SimpleJsonResourceReloadListener<T>(Codec<T>, FileToIdConverter)` (`server/packs/resources/SimpleJsonResourceReloadListener.java:26`), whose ops carry the registry lookup, so holder codecs work. **A file that fails to parse is logged and skipped** (:57–60), unlike `RegistryDataLoader`, which is fatal at world load. A failed `/reload` keeps the old data (`MinecraftServer.java:1562`; read from code, not run).
- Push to clients in **`OnDatapackSyncEvent`** (`event/OnDatapackSyncEvent.java:24`; `getRelevantPlayers()` :50; fires on join, `PlayerList.java:193`, and on reload, :880).

## 6. Time [tech]
- **`getGameTime()` exists** (`LevelData.getGameTime()` :21, `LevelAccessor.getGameTime()` :42). `ServerLevel.tickTime()` (:503) advances it only while `tickRateManager().runsNormally()` (:404), so it **stops while the game is frozen**.
- **`Level.getDefaultClockTime()` exists** (`Level.java:873`, beside `getOverworldClockTime()` :869); the gotcha that says otherwise is out of date.
- `ClientboundSetTimePacket(long gameTime, Map<Holder<WorldClock>, ClockNetworkState>)` is broadcast every 20 server ticks (`MinecraftServer.java:1107`, `forceGameTimeSynchronization` :1174–1182) with the overworld's game time.
- **No public tick-start time:** `tickServer` (:962) keeps `Util.getNanos()` in a local; `nextTickTimeNanos` is protected (:260). Record `Util.getNanos()` in `ServerTickEvent.Pre`. `getTickCount()` :1444, `getAverageTickTimeNanos()` :1831.
- `ServerTickRateManager` (`server/ServerTickRateManager.java`): `setFrozen` :28, `stepGameIfPaused(int)` :41, `setTickRate(float)` :115. `TickRateManager`: `millisecondsPerTick()` :24, `runsNormally()` :32, `isFrozen()` :52.
- `Util.getMillis()` :180, `Util.getNanos()` :184 (replaceable through `setTimeSource` :171).
- **Vanilla ping is too coarse:** a keepalive every 15,000 ms (`ServerCommonPacketListenerImpl.java:35`, skipped for the single-player owner), smoothed `latency = (latency*3 + sample)/4` (:89). Measure RTT with the mod's own payload.

## 7. Tick events [tech]
`net.neoforged.neoforge.event.tick`: `PlayerTickEvent.Pre` :39 / `.Post` :52, `EntityTickEvent.Pre` :28 (cancelable) / `.Post` :51, `ServerTickEvent.Pre` :48 / `.Post` :59, `LevelTickEvent.Pre` :50 / `.Post` :61 (fired from `event/EventHooks.java:964–1031`).

## 8. Attributes [tech]
`AttributeModifier(Identifier, double, Operation)` is a record (`world/entity/ai/attributes/AttributeModifier.java:14`; ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL). `AttributeInstance`: `addOrUpdateTransientModifier` :83, `addTransientModifier` :91 (**throws if the id is already applied**), `removeModifier(Identifier)` :121. `Attributes`: ATTACK_DAMAGE :17, ATTACK_SPEED :19, ENTITY_INTERACTION_RANGE :41, GRAVITY :55, JUMP_STRENGTH :58, KNOCKBACK_RESISTANCE :61, MAX_HEALTH :68, MOVEMENT_SPEED :77, SCALE :90, STEP_HEIGHT :99.

## 9. Entities [tech]
`EntityType.Builder.of(factory, MobCategory)` :543, `sized(w, h)` :551, `build(ResourceKey<EntityType<?>>)` :683; `DeferredRegister.createEntities(modid)` :185 → `registerEntityType(...)` :813/:827; `EntityAttributeCreationEvent.put(...)` (:30); `Monster.createMonsterAttributes()` :118; `MobCategory.MISC` :17 (no cap); `IEntityExtension.getClassification(boolean)` :100; `Entity.getBoundingBox()` :3582, `getEyePosition(float)` :2036. Vanilla mob melee is `Mob.doHurtTarget(ServerLevel, Entity)` (`Mob.java:1416`), called only by `MeleeAttackGoal` :131 and the brain behaviour `MeleeAttack` :34: a mob without them never hits through it.

## 10. Commands and config [tech]
`RegisterCommandsEvent` (`getDispatcher()` :36). `ModConfigSpec.Builder` (`common/ModConfigSpec.java:309`: `define` :742, `defineInRange` :762/:779, `defineEnum` :642, `comment` :812, `push`/`pop` :851/:869). `ModContainer.registerConfig(ModConfig.Type, IConfigSpec)` (FML `ModContainer.java:102`). **`ModConfig.Type` has no `SERVER` or `COMMON`:** FML 12.0.12 has `LOCAL` (not synced), `CLIENT`, **`SYNCED`** (per server, synced to clients, per-world override in `syncedconfig/`) and `STARTUP` (`config/ModConfig.java:86–120`).

## 11. GameTests [tech]
- Ours register through `Registries.TEST_FUNCTION` and `RegisterGameTestsEvent` (`src/main/java/dev/champ/kimetsu/gametest/ScopeGameTests.java:46–54`).
- `GameTestHelper`: `makeMockPlayer(GameType)` :360, `makeMockServerPlayer(GameType)` :374, `makeMockServerPlayerInLevel()` :398, `spawn` :161–272, `runAtTickTime` :956, `runAfterDelay` :964, `succeedWhen` :946, `onEachTick` :1152, `getTick()` :1125, `assertEntityProperty` :893, `hurt(entity, source, dmg)` :221.
- **No mock player fits combat tests.** `makeMockPlayer` is a plain `Player`, not a `ServerPlayer`. `makeMockServerPlayer` has a null `connection`, so `hurtServer` (via `isInvulnerableTo`, :1397) and a synced `setData` (`AttachmentSync.java:110`) should throw (read from code, not run). `makeMockServerPlayerInLevel` is deprecated for removal and forced creative. NeoForge's `FakePlayer` (`common/util/FakePlayer.java:95`) is permanently invulnerable and never dies. No mock player runs payload handlers: tests call the server-side judge and handlers directly with synthetic inputs.

## 12. Knockback and movement [tech]
- **`hurtMarked` is gone; it's `Entity.syncVelocity`** (public, :284; `markHurt()` :1964 sets it), which sends `ClientboundSetEntityMotionPacket` to trackers **and the player itself** (`server/level/ServerEntity.java:207–211`). `Entity.needsSync` (:282, set by `push` and `knockback`) reaches only trackers, never the player itself.
- **Players are client-authoritative** (`Player.isClientAuthoritative()`, `Player.java:1320`): the client moves itself; the server sets the player back for "moved too quickly" (`ServerGamePacketListenerImpl.java:1171–1176`) or "moved wrongly" (more than 0.0625 off its own simulation, :1205–1213), the latter skipped during `LivingEntity.isInPostImpulseGraceTime()` (:1912), granted by `applyPostImpulseGraceTime(int)` (:1898).
- **So the server can't force a player's exact dash displacement**, only an impulse (`setDeltaMovement` + `syncVelocity = true`) or a teleport (`connection.teleport`, :1341). The client must apply frame-data displacement itself; the server validates it with grace time.

## APIs the spec can rely on (server)
`LivingIncomingDamageEvent` with `setInvulnerabilityTicks(0)`; a mod `damage_type` (scaling never, exhaustion 0) tagged bypasses_armor, bypasses_shield, bypasses_enchantments, bypasses_effects, bypasses_cooldown and no_knockback; `LivingDeathEvent` cancel (restore health yourself); attachments with `serialize(MapCodec)` and `sync(BiPredicate, StreamCodec)`; `PayloadRegistrar` with `executesOn(NETWORK)` for arrival stamps; `PacketDistributor`; `NewDatapackRegistryEvent` or a JSON reload listener plus `OnDatapackSyncEvent`; `getGameTime()`, `Util.getNanos()`, `ServerTickRateManager`; the tick events; `addOrUpdateTransientModifier`; `ModConfig.Type.SYNCED` and `CLIENT`; `syncVelocity`, `setDeltaMovement`, `applyPostImpulseGraceTime`; client-side cancel of `InteractionKeyMappingTriggered` plus `AttackEntityEvent` or `onLeftClickEntity` on the server.

## Don't exist (design around them)
`DataPackRegistryEvent.NewRegistry`; syncing a reloadable registry; `ModConfig.Type.SERVER`/`COMMON`; `AttachmentType.serialize(Codec)`; per-receiver attachment bytes; `minecraft:swing_animation`; `Entity.hurtMarked`; a public server tick-start time; per-payload rate limits; a mock player that runs payload handlers; server-forced exact player displacement; damage-type JSON in `ref-src/vanilla-data/`.

## Surprises worth a gotchas.md line
- Living i-frames are `LivingEntity.damageCooldownTime`; `bypasses_cooldown` skips the check but still sets the cooldown to 20, which shields the target from the next vanilla hit: call `setInvulnerabilityTicks(0)` for mod damage.
- Don't tag mod damage `bypasses_invulnerability` (it passes creative mode and the totem). Set `"scaling": "never"` or players take difficulty-scaled mod damage.
- `NewDatapackRegistryEvent`: `worldRegistry` loads once and can sync; `reloadableRegistry` reloads on `/reload` but can't sync.
- `ModConfig.Type` is LOCAL, CLIENT, SYNCED, STARTUP.
- An attachment syncs on every `setData` and on a default-creating `getData`; every receiver gets the same bytes.
- `CustomPacketPayload.createType(String)` puts the id in `minecraft:`.
- `hurtMarked` is `syncVelocity`; `push` and `knockback` never reach the player itself.
- `makeMockServerPlayer` has a null connection; `FakePlayer` is invulnerable.
- `Level#getDefaultClockTime()` does exist (`Level.java:873`).
- A `piercing_weapon` item makes the server ignore attack packets and turns on STAB.
- `ref-src/vanilla-data` lacks `damage_type` and `tags/damage_type`.

## Corrections from the netcode review (2026-10-20, checked in `ref-src/`)
- **`MinecraftServer.getNextTickTime()` is public** (`MinecraftServer.java:1515`, returns `nextTickTimeNanos`): the tick schedule is reachable without an access transformer, so §6's "no public tick-start time" is too strong.
- **Queued packets run before `ServerTickEvent.Pre`:** `processPacketsAndTick` calls `packetProcessor.processQueuedPackets()` and then `tickServer`, which fires Pre, ticks the levels (game time advances inside the level tick) and fires Post (`MinecraftServer.java:962–1021`). Main-thread payload handlers therefore run before Pre.
- **The integrated server's pause skips `super.tickServer`**, so neither Pre nor Post fires while paused (`IntegratedServer.java:144`).
- **Entity `updateInterval` defaults to 3 ticks** (`EntityType.Builder`, `EntityType.java:521`), and client interpolation uses 3 steps.
- **"Moved too quickly"** compares the movement against 100 (300 when fall-flying) per packet (`ServerGamePacketListenerImpl.java:1172`), about 10 blocks: it bounds nothing in a fight.
- `PlayerEvent.StartTracking` and `StopTracking` exist (`net/neoforged/neoforge/event/entity/player/PlayerEvent.java:278, 300`), as does `ServerboundClientTickEndPacket`.
- **Vanilla's tick schedule isn't game time under sustained lag** (the numbers follow-up, checked again by the coordinator). `runServer` adds one tick length to `nextTickTimeNanos` before every tick, whether or not the last one finished on time, and moves it forward only when the server is more than `OVERLOADED_THRESHOLD_NANOS + 20 ticks` = 2 s behind and the schedule is at least `OVERLOADED_WARNING_INTERVAL_NANOS + 100 ticks` = 15 s past the last warning ("Can't keep up!", `MinecraftServer.java:195–197, 734–754`). The skip drops the backlog; game time doesn't jump. So `getNextTickTime()` minus a tick is the true tick start only at full speed and through a short stall that vanilla catches up: at a steady 19 TPS it falls behind wall time by 2.6 ms a tick until it's 2 s behind (about 40 s), at 15 TPS by 16.7 ms a tick (skipping every 20 s, up to 5 s behind). The spec converts arrivals on its own schedule instead (Rule 1.2). It's also set before `processPacketsAndTick`, so during a tick it already names the next one.

# Part 2: client side and libraries

Library sources were downloaded from the ADR 0001 coordinates (all HTTP 200) into the session scratchpad and only read, never run: PAL 1.2.8+mc.26.3 with PlayerAnimationLibCore, AzureLib 4.0.14, GeckoLib 5.5.7, SmartBrainLib 2.0.3.

## Headline findings [tech]
1. **SDL numbering.** Mouse buttons are `InputConstants.MOUSE_BUTTON_LEFT = 1`, `MIDDLE = 2`, `RIGHT = 3` (`com/mojang/blaze3d/platform/InputConstants.java:148-150`); keys are SDL scancodes (`KEY_A = 4`, :41). NeoForge's `InputEvent.MouseButton#getButton` javadoc still describes GLFW values (`net/neoforged/neoforge/client/event/InputEvent.java:59-67`), and `InteractionKeyMappingTriggered#isAttack()` is NeoForge's action code 0, not a mouse button (:356).
2. **Input arrives once per frame with no timestamp.** `SDLEventHandler.pollEvents` (`com/mojang/blaze3d/platform/SDLEventHandler.java:37-76`) drops SDL's event timestamp and dispatches inside `RenderSystem.pollEvents` at the start of each frame (`net/minecraft/client/Minecraft.java:937`). The best stamp is `Util.getNanos()` (or `Blaze3D.getTime()`, `com/mojang/blaze3d/Blaze3D.java:24`) read in the handler: accurate to one frame, 7–17 ms. SDL's own timestamp would need a Mixin.
3. **Cancelling `InteractionKeyMappingTriggered` on held left-click still swings and sends `ServerboundPunchPacket` every tick unless you also call `setSwingHand(false)`** (`Minecraft.java:1659-1663`).
4. **`ViewportEvent.ComputeFov` fires twice per frame,** once for the world and once for the hand FOV (base 70) (`net/minecraft/client/Camera.java:105-106, 238-255`). A punch added there also changes the first-person hand projection unless the two calls are told apart.
5. **No mod-added `SoundSource`** (a plain enum, not `IExtensibleEnum`: `net/minecraft/sounds/SoundSource.java`), and **no post-effect registration event** (26.3 has per-player post-effect lists instead).
6. **PAL's first-person mode turns `RenderHandEvent` off:** its Mixin cancels `submitHandsWithItems` at HEAD while a PAL animation is active (PAL `mixin/firstPerson/ItemInHandRendererMixin.java:53-60`).
7. **AzureLib renderers extend `EntityRenderer`, not `LivingEntityRenderer`** (AzureLib `render/entity/AzEntityRenderer.java:42`), so `RenderLivingEvent` never fires for AzureLib demons; their animation and geometry run in `extractRenderState` (:38-41).
8. **Two gotchas are wrong:** `Level#getDefaultClockTime()` exists (`Level.java:873`), and audio and input stayed in `com.mojang.blaze3d`; only the render API moved to `com.mojang.renderpearl`.

## 1. Input [tech]
- `RegisterKeyMappingsEvent` (mod bus): `register(KeyMapping)`, `registerCategory(KeyMapping.Category)` (`client/event/RegisterKeyMappingsEvent.java:30,42,49`); `KeyMapping.Category` is `record Category(Identifier id)` (`net/minecraft/client/KeyMapping.java:304`).
- `KeyMapping` constructors (`KeyMapping.java`): `(String, int, Category)` :100, `(String, InputConstants.Type, int, Category)` :104, NeoForge's `(String, IKeyConflictContext, InputConstants.Type, int, Category)` :122 and `(String, IKeyConflictContext, KeyModifier, InputConstants.Type, int, Category)` :136; `isDown()` :193, `consumeClick()` :201. `ToggleKeyMapping(String, int, Category, BooleanSupplier, boolean)` (`ToggleKeyMapping.java:14`) fits a guard hold/toggle option.
- `KeyConflictContext.UNIVERSAL/GUI/IN_GAME`; `KeyModifier.CONTROL/SHIFT/ALT/NONE`; `InputConstants.Type.KEYBOARD/MOUSE` (:306, :322).
- **`InputEvent.MouseButton.Pre`** (`InputEvent.java:105`) is cancellable and fires before `KeyMapping.set/click` (`MouseHandler.java:82` vs :157-160): cancelling it keeps vanilla's attack and use keys up. It also fires with a screen open (check for no screen). `MouseButton.Post` (:122) isn't cancellable. **`InputEvent.Key`** (:240) isn't cancellable and fires after `KeyMapping.set` (`KeyboardHandler.java:589`).
- **`InteractionKeyMappingTriggered`** (:313; `setSwingHand`, `isAttack`, `isUseItem`, `isPickBlock`, :331-380) fires from `startAttack` (`Minecraft.java:1709`), `continueAttack` (:1659), `startUseItem` (:1773) and `pickBlockOrEntity` (:2477), but **not** when `missTime > 0`, `isHandsBusy()` or `cannotAttackWithItem` (:1673-1705), and not while an item is in use (:2020-2032). Use it to block vanilla, never to capture combat input.
- `ClientTickEvent.Pre/Post` fire at the head and tail of `Minecraft.tick()` (`client/ClientHooks.java:874-884`). Frame order (`Minecraft.java:1180-1237`): input dispatch in `pollEvents` (:937), `deltaTracker.advanceGameTime` (:1193), queued packets and tasks (:1198-1200), up to 10 `tick()` calls (:1211), mouse movement (:1232), render.
- `Minecraft.getDeltaTracker()` (:2817); `DeltaTracker.getGameTimeDeltaPartialTick(boolean)` (`DeltaTracker.java:107`) still holds the previous frame's value at input time.
- **Client game time:** `ClientLevel` adds 1 per tick while the tick-rate manager runs normally (`ClientLevel.java:309-314, 455-458`) and is overwritten by the server every 20 ticks (`ClientPacketListener.java:1127-1130`, `ClientLevel.setTimeFromServer` :461). It lags by the one-way latency and can jump once a second: smooth a separate estimate rather than hanging the Pulse on it.

## 2. Camera [tech]
- `ViewportEvent.ComputeCameraAngles` (`client/event/ViewportEvent.java:240`): `get/setYaw`, `get/setPitch`, `get/setRoll` (:256-297), posted in `Camera.alignWithEntity` (`Camera.java:270-273`).
- `ViewportEvent.ComputeFov` (`getFOV`/`setFOV`, :313-336), twice per frame (headline 4). `ComputeFovModifierEvent` (`ComputeFovModifierEvent.java:25`) runs once per tick and is eased 0.5 per tick and clamped 0.1–1.5 (`Camera.java:177-184`): too slow for a 1–2 tick punch.
- `CalculateDetachedCameraDistanceEvent` (`setDistance`, :101); `Attributes.CAMERA_DISTANCE` (`Attributes.java:35`).
- `CalculatePlayerTurnEvent` (`CalculatePlayerTurnEvent.java:24`) offers only mouse sensitivity and cinematic camera: **no event carries the mouse delta**, so lock-on steers the player's rotation itself.
- `RenderHandEvent` (`RenderHandEvent.java:30`, cancellable, per hand: `getPoseStack`, `getSubmitNodeCollector`, `getPlayerState()`, `getPartialTick`, `getSwingProgress`, `getItemStack`), fired at `FirstPersonHandsAndItemsRenderer.java:343,362`. `RenderArmEvent` (:27) fires only for empty-hand and map arms.
- No camera-offset event (confirms R8-01).

## 3. HUD [tech]
- `RegisterGuiLayersEvent` (mod bus, `RegisterGuiLayersEvent.java:33`): `registerBelowAll`, `registerBelow`, `registerAbove`, `registerAboveAll`, `replaceLayer`, `wrapLayer` (:47-107). `GuiLayer.render(GuiGraphicsExtractor, DeltaTracker)`. `VanillaGuiLayers` ids include `CROSSHAIR`, `PLAYER_HEALTH`, `HOTBAR`, `BOSS_OVERLAY`, `SUBTITLE_OVERLAY` (`client/gui/VanillaGuiLayers.java:17-41`); 26.3 has a separate `Hud` class (`Hud.java:247`).
- `RenderGuiLayerEvent.Pre` (cancellable, `getName()`, :70) hides vanilla layers.
- `GuiGraphicsExtractor` drawing methods are **not** prefixed `extract*`: `fill` :184, `fillGradient` :204, `outline` :212, `text` :236-261, `centeredText` :265-274, `blit` :301-374, `blitSprite` :378-437, `pose()` (a `Matrix3x2fStack`) :139, `enableScissor` :151, `submitGuiElementRenderState` :1295. No circle primitive: the Pulse ring is a sprite set or a custom `GuiElementRenderState` (`renderer/state/gui/GuiElementRenderState.java:12`).

## 4. Sound [tech]
- `SoundSource` has 11 constants (MASTER, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, AMBIENT, VOICE, UI) and isn't extensible; sliders are generated per value (`Options.java:559`), so a new one would need a Mixin.
- Audio is still LWJGL OpenAL in `com/mojang/blaze3d/audio/` (`Channel.java:13` imports `AL10`; `Library.java:19,62-107`); SDL3 didn't touch it. `org/lwjgl/openal/EXTEfx.class` is in `lwjgl-openal-3.4.3.jar` (whether 26.3 ships 3.4.3 is unconfirmed).
- `PlaySoundEvent` (`getSound`/`setSound`) runs before playback (`client/sounds/SoundEngine.java:373`); **`PlaySoundSourceEvent` / `PlayStreamingSourceEvent`** hand you `getSound()` and `getChannel()` right after `channel.play()`, **on the sound executor thread** (`SoundEngine.java:456-466`, `ChannelAccess.java:79`). `Channel.source` is `private final int` (`Channel.java:21`): an EFX direct filter needs an access transformer, reflection or a Mixin. `ChannelAccess.executeOnChannels` (:43) reaches sounds already playing.
- Ducking without a Mixin: `SoundManager.updateCategoryVolume(SoundSource, float)` (`SoundManager.java:197`); `MusicManager` rewrites MUSIC every tick (`MusicManager.java:144`) and `stopAll` clears the gains (`SoundEngine.java:173`). Vanilla has no ducking of its own.

## 5. Rendering hooks [tech]
- `RenderLevelStageEvent` uses subclasses (`AfterSky`, `AfterOpaqueBlocks`, `AfterOpaqueFeatures`, `AfterTranslucentFeatures`, `AfterTranslucentBlocks`, `AfterTranslucentParticles`, `AfterWeather`, `OitTranslucent`, `AfterLevel`; `RenderLevelStageEvent.java:110-210`); `getPoseStack()` can be null.
- `RegisterRenderPipelinesEvent` (mod bus, `registerPipeline`, `registerOptionalPipeline`, `registerOitPipelineSet`, :35-49); `RenderPipeline` is in `com/mojang/renderpearl/api/pipeline/RenderPipeline.java` (`withDepthStencilState` :298-303). Depth test off: `new DepthStencilState(CompareOp.ALWAYS_PASS, false)` (used by vanilla in `RenderPipelines.java:331,1246`).
- World geometry: extract in `ExtractLevelRenderStateEvent` (`LevelExtractor.java:243`; store with `BaseRenderState.setRenderData`), submit in `SubmitCustomGeometryEvent` (`LevelRenderer.java:345`); custom node types through `RegisterFeatureRenderersEvent` (:37).
- Post effects: `PostChain` (JSON in `post_effect/`, `ShaderManager.java:53`); **no `RegisterPostEffectsEvent`**. `GameRenderer.update` (`GameRenderer.java:430-443`) builds each frame's list from `LocalPlayer.getActivePostEffects()` (`LocalPlayer.java:1348`); the server's `ServerPlayer.addPostEffect` sends a packet that **replaces** the client list (`ClientCommonPacketListenerImpl.java:242`). Vanilla ships an `invert` effect (`GameRenderer.java:235`).

## 6. Client hit-stop [tech]
- `RenderLivingEvent.Pre` (cancellable, carries the render state, no entity) fires before `model.setupAnim(state)` (`LivingEntityRenderer.java:76, 103`); freezable fields are `EntityRenderState.ageInTicks` and `partialTick` and `LivingEntityRenderState.walkAnimationPos/Speed`. Preferred: `RegisterRenderStateModifiersEvent` (`registerEntityModifier`, `registerAvatarEntityModifier`, :58-101), which runs right after `extractRenderState` with entity access.
- Library models keep their own clocks. **PAL:** a `SpeedModifier` (public `speed`) set to 0 for the freeze and above 1 to catch up gradually (`playeranimcore/animation/layered/modifier/SpeedModifier.java`). **AzureLib:** `AzAnimator.animate(T, float, boolean updateTimer)` with `false` holds the timer and the next tick catches up the whole gap at once (`animation/AzAnimator.java:148`, `AzAnimationTimer.java:51-93`); a gradual catch-up through runtime controller speed is unverified.

## 7. Libraries [tech]
**PAL 1.2.8+mc.26.3**
- Layers: `PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(@Nullable Identifier, int priority, factory)`; `PlayerAnimationController(Avatar, AnimationStateHandler)` with `triggerAnimation(Identifier[, float startFrom])` (:53, :62), `replaceAnimationWithFade` (:66-77) and `getBoneWorldPositionPoseStack(String, float, Vec3)` (:81, a world-space locator).
- Core `AnimationController`: `addModifier` (:986-998), `get3DTransform` (:900, offset only), `setCustomInstructionKeyframeHandler` (:173), sound and particle handlers, `setFirstPersonMode` (:920), `setFirstPersonConfiguration` (:932), `setFirstPersonFollowsCamera` (:131). Fades: `AbstractFadeModifier.standardFadeIn/standardFadeOut(int ticks, EasingType)` (:139, :160).
- First person: `FirstPersonMode` NONE / VANILLA / THIRD_PERSON_MODEL / DISABLED; `FirstPersonConfiguration(showRightArm, showLeftArm, showRightItem, showLeftItem[, showArmor])`.
- Fixed bones: body, torso, head, right_arm, left_arm, right_leg, left_leg, right_item, left_item, cape, elytra, with fixed pivots (`HumanoidAnimationController.java:46-57, 93-103`); extra bones need `parents`.
- Clips load from `assets/<ns>/player_animations/*.json` (client resources).
- **No server-triggered play** (no network classes in the jar): the mod's own payload triggers clips. **Players and mannequins only** (`Avatar`).

**AzureLib 4.0.14**
- `AzEntityAnimator<T>` with `registerControllers` and `getAnimationLocation` (`animation/AzAnimator.java:144,146`); `AzAnimationController.builder(animator, name)` with `setAnimationSpeed`, `setTransitionLength`, `setKeyframeCallbacks`, `setEasingType`, `setWeight`, `setBlendMode(OVERRIDE|ADDITIVE)`, `setBoneMask` (`AzAnimationControllerBuilder.java:51-140`); runtime `setWeight` and `fadeWeight(target, ticks)` (`AzAnimationController.java:280,291`).
- Server trigger: `AzCommand.create(controller, anim).sendForEntity(entity)` to trackers and self (`dispatch/command/AzCommand.java:86, 223-230`), with per-command `setSpeed`, `setTransitionSpeed`, `setFreezeTickOffset`, `setWeight`. `AzSequencePlayer` counts ticks on the server; **its event ticks ignore animation speed, and each stage is preceded by the client's transition length.**
- Keyframe events: `AzKeyframeCallbacks.builder().setCustomInstructionKeyframeHandler(...)` (:97); `timeline` parsed at `animation/parse/AzKeyframesAdapter.java:101`. Bones: `AzBakedModel.getBone` (:53-57), `AzBone.getWorldSpaceMatrix()` (:321). Assets in `animations/`. Molang variables are global (`core/molang/MolangParser.java:51`).

**GeckoLib 5.5.7 (fallback):** `AnimationController(name, transitionTicks, handler)` with `setAnimationSpeed` (:211), `triggerableAnim` (:266), custom instruction keyframes (:199); `GeoEntity.triggerAnim` sends over the network; no layer weights, blend modes or bone masks found.

**SmartBrainLib 2.0.3:** `SmartBrainOwner<BO>`, wired and ticked by SBL's own Mixins (`mixin/common/LivingEntityMixin.java:25`, `MobMixin.java:16`); `SmartBrainBuilder` (`getSensors`, core, idle and fighting groups); `ExtendedBehaviour` (`start`/`tick`/`stop`/`shouldKeepRunning`, `noTimeout`, `cooldownFor`, `getMemoryRequirements()` returning `Set<MemoryCondition<?,?>>`); `ExtendedSensor` (`scanRate`, `doTick`). To hand timing to the strike scheduler: a custom `ExtendedBehaviour` with `noTimeout()` whose `start()` asks the scheduler for a strike and whose `shouldKeepRunning()` follows it; never `DelayedBehaviour` or `AnimatableMeleeAttack`, which would put a second clock on the hit.

**One clip in both PAL and AzureLib (R8-04).** Both read Bedrock `"animations"` with `animation_length`, `loop`, `bones`, `pre`/`post`, `lerp_mode`/`easing`, `timeline`, `sound_effects` and `particle_effects`. Differences: the folder (`player_animations/` vs `animations/`: copy at build), bones (PAL's names and pivots are fixed, so KHR-1 must use them), PAL's `bend` channel (absent from AzureLib's sources: likely ignored, unverified), Molang engines (PAL scopes per controller, AzureLib globally), blends (fade modifiers vs transition length), and bezier data layout (unverified). R8-04's M1 test settles it.

## 8. First-person arms (R8-01) [tech]
- **Option A, PAL `THIRD_PERSON_MODEL`:** PAL cancels vanilla's hands and extracts the local player's body as if the camera were detached, flagged as the first-person pass (`LevelExtractorMixin.java:47-61`). Arms and blade draw in the world pass: world FOV and depth (they can clip into walls), and they move with camera roll and FOV punches.
- **Option B, the mod's own:** cancel `RenderHandEvent` and submit arms and blade through its pose stack (hand FOV 70, its own pass, `GameRenderer.java:388-417`). No vanilla API draws an animated humanoid arm there (unverified), and it can't coexist with A: while PAL is active the event never fires.

## APIs that don't exist or are unverified (client)
A mod-added `SoundSource`; reading `Channel.source` without an access transformer; SDL event timestamps; a mouse-delta event; a camera-offset event; `RegisterPostEffectsEvent`; PAL networking, PAL on non-players, PAL's `HeadBoundCameraModifier`; `RenderHandEvent` while PAL first person is active; `RenderLivingEvent` for AzureLib entities; GeckoLib weights and masks. Unverified: the LWJGL version 26.3 ships; one clip parsing cleanly in both runtimes; gradual AzureLib catch-up; drawing animated arms in the hand pass.

## Surprises worth a gotchas.md line (client)
- SDL numbering for mouse buttons and keys; `isAttack()` is an action code.
- Input is dispatched once per frame with no timestamp: stamp with `Util.getNanos()` in the handler.
- Cancel `InteractionKeyMappingTriggered` with `setSwingHand(false)`, or held left-click still sends punch packets.
- `ComputeFov` fires twice per frame (world, then the 70° hand FOV); `ComputeFovModifierEvent` is per tick and eased.
- `SoundSource` isn't extensible; `PlaySoundSourceEvent` runs on the sound thread; `Channel.source` is private.
- No post-effect registration: per-player lists, and the server's packet replaces the client's list.
- `Level#getDefaultClockTime()` exists; audio and input stayed in `com.mojang.blaze3d`.
- `GuiGraphicsExtractor`'s drawing methods are `fill`, `text`, `blit`, `blitSprite` (not `extract*`).
- PAL first person cancels the vanilla hand at HEAD, so `RenderHandEvent` never fires while it's active.
- AzureLib renderers extend `EntityRenderer`: no `RenderLivingEvent` for them.
- `AzSequencePlayer` event ticks ignore animation speed and each stage is preceded by the client transition length.
- PAL reads `player_animations/`, AzureLib `animations/`.
- SmartBrainLib ticks the brain through its own Mixins.
