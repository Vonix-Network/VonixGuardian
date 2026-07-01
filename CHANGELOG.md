# Changelog

All notable changes to **VonixGuardian** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — W2-02 event wiring (A8/A9/A10)

- **HANGING_PLACE / HANGING_BREAK submit paths wired on all 4 Forge/NeoForge
  cells** (mc-1.18.2/forge, mc-1.19.2/forge, mc-1.20.1/forge, mc-1.21.1/neoforge).
  Forge/NeoForge do not expose Bukkit-style `HangingPlaceEvent`/`HangingBreakEvent`,
  so:
  - `HANGING_PLACE` is observed via `EntityJoinLevelEvent` / `EntityJoinWorldEvent`
    filtered for `net.minecraft.world.entity.decoration.HangingEntity` subclasses
    (ItemFrame, GlowItemFrame, Painting, LeashFenceKnotEntity). Attribution uses
    the existing damage-history resolver so player-placed frames get the placer.
    Chunk-load reanimations (`loadedFromDisk()`) are filtered out for parity with
    `ENTITY_SPAWN`.
  - `HANGING_BREAK` is observed via `AttackEntityEvent` for the player-caused
    path. Non-player breaks (arrows, explosions, mob damage) are left as
    `TODO(A9-style)` requiring a mixin wave.

### Documented — A9 bucket & block-state mixin plan

- `docs/A9-BUCKET-NEOFORGE-1211-DESIGN.md` — mixin plan for NeoForge 1.21.1
  `BUCKET_EMPTY` / `BUCKET_FILL` after the upstream removal of `FillBucketEvent`.
  Recommends `@Mixin(BucketItem)` on `use(Level,Player,InteractionHand)` and
  `@Mixin(MilkBucketItem)` on `finishUsingItem` with concrete injection points.
  **Not shipped in this wave.**
- Inline `TODO(A9-style)` in each cell's events file documenting that
  `submitBurn` / `submitIgnite` / `submitFade` / `submitForm` / `submitSpread` /
  `submitDispense` / `submitLeavesDecay` are Bukkit-only APIs; Forge/NeoForge
  expose no fire-able event surface for those state changes. Wiring them
  requires a companion mixin wave (targets: `FireBlock#tick`, `IceBlock#tick`,
  `SpreadingSnowyDirtBlock#tick`, `LeavesBlock#randomTick`,
  `DispenserBlock#dispenseFrom`, etc.). Handlers intentionally left unwired
  rather than half-wired so the audit log doesn't misrepresent coverage.

## [1.1.5] — 2026-07-01

**CoreProtect-style vanilla-griefer allowlist at the listener — the real fix
for the HTTYD dragon flood.**

v1.1.4's producer-side coalescer helped but didn't solve the root problem:
Forge's `LivingDestroyBlockEvent` is a **prospective query** event (fires as
`Block.canEntityDestroy` is asked per-tick per-block-collision), not an actual
state-change notification like Bukkit's `EntityChangeBlockEvent`. On a modpack
with 300+ dragon variants (Berk / HTTYD), each active dragon fires the event
100k+/sec regardless of whether it destroys anything.

CoreProtect's solution in the Bukkit world: a hardcoded whitelist of ~10
vanilla entity classes (Enderman, EnderDragon, Wither, Ravager, etc.). Any
entity not on that list has its event silently discarded before reaching
the queue. Guardian now ports that pattern to Forge/NeoForge.

### Added

- **`network.vonix.guardian.core.filter.VanillaGrieferSet`**: hardcoded set of
  vanilla entity registry keys ported from CoreProtect (`minecraft:enderman`,
  `minecraft:ender_dragon`, `minecraft:wither`, `minecraft:ravager`,
  `minecraft:silverfish`, `minecraft:turtle`, `minecraft:fox`,
  `minecraft:zombie`, `minecraft:falling_block`, `minecraft:wind_charge`,
  `minecraft:breeze_wind_charge`) plus a static `shouldRecord(...)` helper.
- **`actions.entityChangeAllowlist`** (`List<String>`, default `[]`): additional
  entity keys to record beyond the vanilla set. Admins who want HTTYD dragon
  griefing recorded add e.g. `"isleofberk:night_fury"` here.
- **`actions.entityChangeLogAllEntities`** (`boolean`, default `false`): bypass
  the whitelist entirely for debugging. Not for production.

### Changed

- **`ForgeEvents.onLivingDestroyBlock` (all 4 loader modules)**: consults
  `VanillaGrieferSet.shouldRecord(entityKey, allowlist, logAll)` immediately
  after fetching the entity. Non-matching entities return without any
  attribution work or queue interaction. `EntitySentinel.of(EntityType)` is
  reused as the cross-version-safe way to get the `namespace:path` key.
- **`ConfigLoader.migrateForwardCompat`**: extended to backfill the new
  allowlist to `[]` when loading a pre-1.1.5 config. INFO line explicitly
  notes that modded mob-griefing recording remains OFF by default.

### Design rationale

Guardian's mission is to record **what a rollback command would meaningfully
undo** — player actions plus vanilla mob griefing. Ambient world behavior
(dragon fire ticks, mob movement queries, chunk-load re-simulations) isn't
griefing and isn't rollback-worthy. When a player rides a dragon to torch
someone's base, that shows up as the player's command + explosion events,
both already logged separately. So the correct default for a modded audit
mod is **vanilla mob griefing only, opt-in per modded entity** — not
"log everything and hope batching saves us."

The v1.1.4 coalescer stays as a belt-and-braces safety net for the entities
that do pass the whitelist. Both defenses are cheap.

### Verified

- Local staging Forge 1.18.2 boot with Berk mods + config + local MySQL:
  clean cascade, `Backfilling entityBlockChange defaults from pre-1.1.5
  config (allowlistSize=0)` fires, `EntityBlockChange coalescer enabled`
  fires, VG + DragonGuard 1.0.1 both online.
- RCON test spawned vanilla `minecraft:enderman`, modded
  `isleofberk:night_fury`, and vanilla `minecraft:ravager` into an empty
  world; the modded night_fury (a class that in production fires
  `LivingDestroyBlockEvent` ~100k/sec) produced zero
  `ENTITY_CHANGE_BLOCK` submissions across a 45s window with the entity
  live — proof the whitelist filter is executing and blocking.
- All 4 cells (1.18.2 / 1.19.2 / 1.20.1 Forge + 1.21.1 NeoForge) build
  clean with `-PbuildProfile=forgeonly`.

### Migration

Existing servers auto-migrate: config load emits INFO, no manual action
needed. To keep pre-1.1.5 behavior (record everything, risk flood), set
`entityChangeLogAllEntities: true` in config. To opt in specific modded
entities, add their registry keys to `entityChangeAllowlist`, e.g.:

```json
"entityChangeAllowlist": [
  "iceandfire:fire_dragon",
  "iceandfire:ice_dragon"
]
```

The Berk (HTTYD) modpack entity registry is documented in
`docs/entity-registries/httyd-1.18.2.md` (300+ dragon variants across the
`isleofberk:*` and `iobvariantloader:*` namespaces) if you want to opt-in
selectively.

## [1.1.4] — 2026-07-01

**Producer-side coalescing for `LivingDestroyBlockEvent` — stops HTTYD-class
modpack write-queue floods.**

Field report from `a0b7f085` (Berk / How To Train Your Dragon): a single active
dragon fired `submitEntityChangeBlock()` ~200k times per second per dragon,
overwhelming `BatchedAsyncWriteQueue` and generating relentless
`AsyncWriteQueue full — dropping actions` warnings. Analysis of the producer
histogram (v1.1.3-diag build) showed **99.994%** of ~47M events in 4 minutes
were `ENTITY_CHANGE_BLOCK`, all attributable to the ~350 dragon variants
Berk's modpack registers via `iobvariantloader:*`.

### Added

- **`EntityBlockChangeCoalescer`** (`core.queue`): producer-side dedup keyed by
  `(actorId, worldId, x, y, z)` with a configurable time window. Same tuple
  within window → suppressed. Different actor OR different coord → logged.
  ~500KB memory footprint at default cap.
- **`actions.entityBlockChangeCoalesceWindowMs`** (long, default `500`):
  time window inside which repeat `(actor, coord)` events are collapsed.
  `-1` or `0` (post-migration) disables. Pre-1.1.4 configs auto-backfill.
- **`actions.entityBlockChangeMaxTracked`** (int, default `8192`): cap on
  live `(actor, coord)` tuples tracked at once. LRU-evicted on cap pressure.
- **`ConfigLoader.migrateForwardCompat`**: rewrites `0` values to sensible
  defaults on load, so servers upgrading from ≤ 1.1.3 don't silently ship
  with the coalescer disabled. Emits an INFO line naming the fill-ins.

### Changed

- **`Guardian.submitEntityChangeBlock()`**: consults the coalescer before
  seeding an Action; coalesced events increment `gated` counter. All other
  event surfaces unaffected.
- **`Guardian` constructor + `boot()`**: takes an optional
  `EntityBlockChangeCoalescer`; `boot()` builds one from `config.actions()`
  and logs an INFO/WARN depending on enablement.

### Verified

- Boot-parity smoke test on local staging Forge 1.18.2 server with `mods/`
  and `config/` mirrored from Berk. Both VG and DragonGuard 1.0.1 came up
  clean. `Backfilling entityBlockChange coalescer defaults` INFO fired
  as expected on the pre-1.1.4 config file.
- Local MySQL 8.0.46 backend (parity with McProHosting panel1 fleet):
  no `SQLSyntaxError`, HikariPool started clean, no schema regressions.
- CoreProtect's `EntityChangeBlockListener` was studied for prior art;
  their approach is a hardcoded whitelist of vanilla entity classes
  (Enderman, EnderDragon, Fox, Wither, Turtle, Ravager, Zombie,
  Silverfish, WindCharge, BreezeWindCharge, FallingBlock). We chose a
  coord-time coalescer over an entity-class allowlist because it
  requires zero mod-specific knowledge and handles the modded case
  (300+ dragon variants) without operator intervention.

### Known limitations

- The coalescer suppresses at the granularity of `(actor, exact coord)`.
  A single dragon flying across 100 blocks per second still submits
  ~100 events/sec — but that's 3 orders of magnitude below the pre-fix rate.
- If a dragon repeatedly destroys and reforms the same block within 500ms,
  only the first destruction is logged. Rollback correctness is preserved
  (the block state is the same before and after the dropped events).

## [1.1.2] — 2026-06-30

**Hotfix release: MySQL dialect compatibility.**

v1.1.1 successfully bundled the MySQL JDBC driver, but the schema
initialization in `Schema.java` used `CREATE INDEX IF NOT EXISTS` —
which is supported by SQLite and MariaDB but **not by stock MySQL**
(8.0.46 confirmed rejecting it). The mod booted fine on MariaDB
panels but failed on real MySQL with:

```
java.sql.SQLSyntaxErrorException: You have an error in your SQL syntax;
... near 'IF NOT EXISTS vg_actions_pos ON vg_actions(world_id, x, z, y, ts)'
```

This was hidden in v1.1.0 (the driver wasn't bundled — failed earlier)
and v1.1.1 (every successful boot in dev was against MariaDB). The
mismatch only surfaced when the first real MySQL backend connected
successfully and tried to run the index DDL.

### Fixed

- **`Schema.java`**: split DDL into table-DDL and index-DDL. Index DDL
  branches on `Dialect.MYSQL`: emit bare `CREATE INDEX` (no `IF NOT
  EXISTS`) and swallow MySQL error code 1061 (`ER_DUP_KEYNAME`) for
  idempotency. SQLite/PostgreSQL paths unchanged.
- **`stampVersion()`**: rewrote `INSERT ... SELECT ?, ? WHERE NOT
  EXISTS (...)` to use a derived-table form (`SELECT v, a FROM (SELECT
  ? AS v, ? AS a) AS src WHERE NOT EXISTS ...`) — MySQL refuses
  `SELECT ... WHERE NOT EXISTS` without a `FROM`. SQLite and PostgreSQL
  accept both forms; the derived-table form is portable.

### Backward compatibility

- **No data migration.** Pure DDL/SQL dialect change.
- **MariaDB / SQLite / PostgreSQL behavior unchanged** — they still
  use `CREATE INDEX IF NOT EXISTS` directly.
- **The `ddlFor(Dialect)` static API is preserved** for tests + tooling
  but a Javadoc warning notes that callers running the list verbatim
  against MySQL must catch `SQLException` with error code 1061. The
  primary `createTables(Connection, Dialect)` entrypoint always takes
  the safe code path.

### Tests

380/380 core tests still pass (no test-shape changes).

## [1.1.1] — 2026-06-30

**Hotfix release: bundle MySQL + PostgreSQL JDBC drivers.**

v1.1.0 only bundled the SQLite driver. Servers configured with
`database.type = mysql` or `database.type = postgresql` failed to boot with
`Failed to get driver instance for jdbcUrl=jdbc:mysql://…` because HikariCP
couldn't resolve a driver class. Operators had to manually drop
`mysql-connector-j` / `postgresql` into `/mods` — undocumented and easy to miss.

This release ships both drivers inside the mod jar using the same
JarInJar / shaded packaging the SQLite driver uses (best-practice
"batteries included"). No operator action required: existing config keeps
working, and the driver is loaded automatically when `database.type` selects
its backend.

### Fixed

- **Forge / NeoForge cells**: `mysql-connector-j` 8.4.0 and `postgresql` 42.7.4
  added to the `jarJar` block alongside `sqlite-jdbc`. Loader extracts at
  runtime and dedupes by Maven coords across mods.
- **Fabric cells**: `mysql-connector-j` and `postgresql` added to the `shaded`
  Shadow configuration and excluded from `minimize` (driver classes are loaded
  reflectively via `java.sql.DriverManager` so the Shadow class-graph minimizer
  cannot see references and would otherwise strip them).
- Drivers are pure-Java (no JNI), so unlike sqlite-jdbc they're safe to ship
  flat inside the Fabric fat jar — no relocation needed.

### Diff

```
mc-1.18.2/{forge,fabric}/build.gradle      +6 lines (JIJ entries + minimize exclude)
mc-1.19.2/{forge,fabric}/build.gradle      +6 lines (JIJ entries + minimize exclude)
mc-1.20.1/{forge,fabric}/build.gradle      +6 lines (JIJ entries + minimize exclude)
mc-1.21.1/{neoforge,fabric}/build.gradle   +6 lines (JIJ entries + minimize exclude)
gradle.properties                          mod_version 1.1.0 → 1.1.1
```

No source-code changes. All 380 core tests still pass (unchanged).

## [1.1.0] — 2026-06-30

**CoreProtect 1:1 command-surface parity wave.**

`/vg` remains the primary command root (Vonix branding). `/co` and `/guardian` are now first-class aliases that resolve to the same Brigadier tree — operators with CoreProtect muscle memory can type `/co lookup …` and get identical behaviour.

### Added

- **CoreProtect 1:1 command surface.** Every CP subcommand, short alias, filter token, and hash flag from https://docs.coreprotect.net/commands/ is now supported:
  - Subcommands: `inspect`, `lookup`, `rollback`, `restore`, `purge`, `near`, `undo`, `consumer pause|resume|toggle`, `status`, `reload`, `help`.
  - Short aliases: `i` (inspect), `l` (lookup), `rb` (rollback), `rs` (restore).
  - Root aliases: `/vg` (primary) + `/co` + `/guardian`.
  - Filter tokens: `u:` `t:` `r:` `a:` `i:` `e:`.
  - Hash flags: `#preview`, `#count`, `#verbose`, `#silent`, **`#optimize`** (new).
  - Action aliases: `a:login` → `+session`, `a:logout` → `-session`, `a:inventory` → INVENTORY_DEPOSIT + INVENTORY_WITHDRAW.
  - User actor sentinels: `u:#fire,#tnt,#creeper,#explosion` (CP-style non-player attribution tokens).
- **Tab completion** (`GuardianSuggestions`) — context-aware completions for every CP token:
  - Empty prefix → suggests every filter prefix + every hash flag.
  - `u:` → online player names + actor sentinels.
  - `t:` → common durations (`1h`, `30m`, `1d`, `7d`, `1w`, `2w`, `30d`).
  - `r:` → numeric radii + `#global`, `#worldedit`, `#we`, `#nether`, `#overworld`, `#end`, and every loaded `#world_<key>` from the live server.
  - `a:` → every action token with `+`/`-` variants where applicable (block, container, inventory, item, kill, session, chat, command, click, sign, username, login, logout).
  - `i:`/`e:` → starter common-block list (full registry follow-up planned for v1.2.0).
  - `#` → all hash flags.
- **`/vg lookup` pagination** matching CP-style `<page>` and `<page>:<perPage>` syntax. `/vg lookup 2` jumps to page 2; `/vg lookup 1:25` sets page-size to 25.
- **Default `r:10` on rollback/restore** when caller omits a radius and has a position (matches CP default). Lookup keeps the global default per CP.
- **`/vg near`** — quick lookup at `r:5 t:1h` centered on the caller.
- **`/vg consumer pause|resume|toggle`** — pause/resume the async writer queue (CP-parity for high-volume admin operations).
- **`r:#nether` / `r:#overworld` / `r:#end` shortcuts** — parser maps to canonical `minecraft:the_nether` / `minecraft:overworld` / `minecraft:the_end` so DAO rows match (rows are persisted with the full namespaced key).
- 25 new `CoreProtectFidelityTest` test cases covering every example from the CP command docs.

### Fixed

- **CRITICAL: `/vg purge` safety floor bypass.** `Purge.run` previously called `g.dao().purge(filter)` directly, skipping the CP-spec minimum-age floor (console 24h, in-game 30d) configured in `GuardianConfig.Purge`. `/vg purge u:foo` with no `t:` filter or with `t:1m` would delete rows that CoreProtect would refuse. Now routes through `g.purgeEngine().purge(filter, minAgeSeconds)` with the floor enforced by source (console vs in-game). Under-age filters now produce a friendly `"Purge refused: …; use a larger t: window"` reply instead of silent overdeletion.
- **HIGH: `r:#nether` / `r:#overworld` / `r:#end` previously stored bare tokens** (`"nether"`, `"overworld"`, `"end"`) which silently matched zero rows because the DAO compares against the full namespaced key. Now maps to canonical vanilla keys at parse time.

### Changed

- **Default page size** is now configurable per-cell via constructor constant `DEFAULT_PAGE_SIZE` (was hardcoded `10`).
- **`/vg purge` success reply** now reports both `removed N rows` and the `minAge=Ns` enforced — audit clarity for ops.
- **Help text** rewritten in CP-style listing for every subcommand, alias, filter token, hash flag, action, and user sentinel.

### Engineering

- 8 jars in matrix (Fabric 1.18.2/1.19.2/1.20.1/1.21.1 + Forge 1.18.2/1.19.2/1.20.1 + NeoForge 1.21.1).
- 380 core tests passing (216 + 25 new CP-fidelity).
- 13 commits on `v1.1.0-cp-fidelity` branch.
- Wave 3 adversarial code review caught + closed 1 CRITICAL and 4 HIGH findings before tag.

## [1.0.4] — 2026-06-30

**Read-after-write fix + CoreProtect parity hotfix wave.**

### Fixed

- **`/vg lookup` returned stale results until server restart** (user report:
  "admin breaks something then runs lookup and it doesn't show recent
  interactions; restart brings the interactions to the surface"). Root cause
  was a flush-trigger bug in `BatchedAsyncWriteQueue.runWorker()`: it only
  flushed the in-memory batch when `poll()` timed out OR the batch hit
  `batchSize`. A steady arrival rate (anything faster than `flushIntervalMs`)
  kept `poll()` returning a non-null head, so the batch grew without ever
  flushing to SQLite. `drainAndFlush()` on shutdown then dumped the buffer,
  which is what made restart appear to "surface" the rows. **Fix**: switched
  to a **time-budgeted poll** (Kafka `linger.ms` / log4j2 `AsyncAppender`
  pattern). Worker now computes the remaining slice of the current
  flush-window for every `poll()` call and force-flushes when the window
  expires, regardless of batch fill. Verified end-to-end with a 10-command
  trickle smoketest at /root/staging/vg-smoketest/forge-1.20.1: all rows
  landed within one flush window.
- **`/vg lookup` cross-dimension scoping** (user report: "Lookup appears to
  see cross dimensions?"). `AbstractJdbcDao.buildWhereClause` only applied
  the world filter when `WorldSel` was explicitly set, so player-issued
  lookups without an explicit `w:` token returned events from every
  dimension. CoreProtect's default = caller's current world. Fixed in
  `QueryParser.parse` — when the source is a player and no `w:` token is
  present, the caller's current world is now folded into the filter at
  parse-time. NeoForge inspector-wand path was already correct.
- **`PISTON_RETRACT` action type never emitted**. `onPistonPre` had a binary
  `else` branch on `PistonMoveType` that fell into the `RETRACT` submit even
  for non-EXTEND/non-RETRACT pulses, but the `Pre` event fires both
  directions — net effect was that the EXTEND branch took everything and
  RETRACT was dead. Replaced with explicit `else if (RETRACT)`; both
  directions now produce their own rows. Across all 4 Forge-family loaders.
- **`ENTITY_SPAWN` flood**. `EntityJoinLevelEvent` fires for every entity,
  *including* chunk-load reanimations (the cause of the 213M dropped events
  observed on SB4 in 16 min after a restart). Now skipped when
  `ev.loadedFromDisk()` returns true, and the surviving rows tag
  `sourceTag = "spawn:join"` so future log-mining can spot them. Full
  `MobSpawnType` classification (`NATURAL` vs `SPAWNER` vs `EGG` vs
  `BREEDING`) is queued for 1.0.5 — needs `LivingSpawnEvent.CheckSpawn` plus
  a small spawn-cause cache; this filter alone cuts the bulk of the noise.

### Added

- **`BUCKET_FILL` / `BUCKET_EMPTY` parity**. New `onFillBucket` handler on
  every Forge/NeoForge loader using `FillBucketEvent`. Distinguishes
  fill-vs-empty by inspecting the player's held item (`minecraft:bucket` =
  fill, anything else = empty). Resolves the affected block via the
  raytrace `BlockHitResult`. Water/lava grief is the canonical CoreProtect
  rescue case; it now has 0% → 100% Forge-family coverage.
- **Chat captured at `EventPriority.HIGHEST` with `receiveCanceled=true`**
  (Forge 1.18.2/1.19.2/1.20.1, NeoForge 1.21.1). Previously VG ran at default
  priority, so any anti-spam/chat-filter mod that cancelled `ServerChatEvent`
  first would have its cancelled messages dropped from VG too. Forge's bus
  order is `HIGHEST→HIGH→NORMAL→LOW→LOWEST` (higher fires first), so HIGHEST
  guarantees VG runs before any default-priority canceller; `receiveCanceled`
  closes the remaining hole where an even-higher-priority listener cancels
  the event. Net result: we now log the chat as the user typed it, even when
  a downstream mod cancels broadcast — matches CP's contract.

### Notes

- The `/root/staging/coreprotect-parity-matrix-v2.md` audit (generated this
  release) catalogues every CoreProtect listener vs VG handler across all
  four MC versions × three loaders. 8 P0 items identified; this release
  closes 4 (chat priority, piston retract, spawn filter, bucket
  fill/empty). The remaining 4 P0 items (Fabric BLOCK_PLACE; SIGN edits
  via mixin; HANGING place/break; deeper `ENTITY_CHANGE_BLOCK` beyond
  LivingDestroyBlock) are tracked for 1.0.5 because they require
  mixin scaffolding rather than `@SubscribeEvent` additions.
- Wave A (queue flush) is the user-visible fix. Wave B (parity matrix)
  is the documentation artefact. Wave C (this release's P0 closures)
  is the listener wiring. Wave D (1.0.5) will be the mixin wave.

## [1.0.3] — 2026-06-30

**Rollback handler completion: `/vg rollback` and `/vg restore` now cover the v0.1.0 griefing expansion.**

### Fixed

- **`/rollback doesnt work`** (user report). `RollbackEngine.applyInverse` / `applyForward` previously had a silent `default` branch that logged `rollback not implemented for <type>` and did nothing for every v0.1.0-expansion `ActionType` (entries 15–39 in `ActionType.java`). In practice this meant the entire modded griefing surface — entity-driven block changes, fire spread, fluid placement, hopper transfers, hanging entities, structure growth, portals — was audited but **never** revertable. The default branch is gone; every `ActionType` is now explicitly handled (or explicitly refused with a reason).

### Added

- **19 new rollback handlers** in `RollbackEngine.applyInverse` and `applyForward`, mirrored across the rollback/restore directions:
  - `ENTITY_CHANGE_BLOCK` — restores `oldBlockId` from `targetMeta` on rollback, re-applies `newBlockId` from `targetId` on restore (per `EventSubmitter.submitEntityChangeBlock` contract).
  - `BURN`, `IGNITE`, `FADE`, `LEAVES_DECAY`, `BUCKET_FILL` — block was destroyed/changed-away; inverse restores the original block, forward re-destroys.
  - `FORM`, `SPREAD`, `BUCKET_EMPTY`, `STRUCTURE_GROW` (lossy), `PORTAL_CREATE` — block was created; inverse clears to AIR, forward re-places.
  - `HOPPER_PUSH` — inverse removes from destination container, forward re-deposits.
  - `HOPPER_PULL` — inverse gives/drops into source, forward re-withdraws.
  - `HANGING_BREAK` — inverse re-spawns the hanging entity via `WorldMutator.respawnEntity`.
  - `HANGING_PLACE` — forward re-spawns the hanging.
- **Per-action explicit refusal WARNs** replacing the silent default branch. Each refused `ActionType` now logs a one-line reason (e.g. `refusing to roll back DISPENSE — container slot tracking required`) instead of the generic `not implemented` message. Refused: `DISPENSE`, `PISTON_EXTEND`, `PISTON_RETRACT`, `INVENTORY_DEPOSIT`, `INVENTORY_WITHDRAW`, `ITEM_CRAFT`, `ENTITY_SPAWN`, `ENTITY_INTERACT`, `CHUNK_POPULATE`, `CLICK`, plus the forward direction of `HANGING_PLACE`/`HANGING_BREAK` and the rollback direction of `HANGING_PLACE` (TODO: add `WorldMutator.removeHangingAt(worldId, x, y, z, entityType)` so hanging place/restore-break can be honoured). `CHAT`, `COMMAND`, `SIGN`, `SESSION_JOIN`, `SESSION_LEAVE`, `USERNAME_CHANGE` continue to be refused as before.

### Notes

- Inspector wand position lookup and `CONTAINER_*` delta wiring will land in a sibling subagent in the same release.
- Pure-Java change in `:core`. No `WorldMutator` API additions yet (the `removeHangingAt` TODO is parked). All 4 jars rebuilt from a single repo state.

## [1.0.2] — 2026-06-30

**Hotfix: log spam from `EntitySentinel.of()` on heavy modpacks (PZ-class load).**

### Fixed (all Forge/NeoForge loaders)

- **`NoSuchMethodError: 'net.minecraft.world.entity.EntityType net.minecraft.world.entity.Entity.getType()'` flooding the log once per entity tick** on Forge 1.20.1 modpacks with deep mixin/coremod chains (e.g. PZ / 284 mods). The call site (`EntitySentinel.of(Entity)`) compiled against Mojang official mappings invokes `Entity.getType()`. Some modpack mod combinations leave that name unresolved on the bytecode-link classloader path at runtime (the SRG name `m_6095_` is what's actually exposed), producing `NoSuchMethodError` every time an entity joins a level. The exception is caught by `ForgeEvents.onEntityJoinLevel`, but the WARN was unconditional — so log noise was unbounded under load.
- **Fix.** `EntitySentinel` now resolves `Entity::getType` via a `MethodHandles.publicLookup().findVirtual` chain at class-init: it tries the Mojang name first (`getType`), then SRG (`m_6095_`), then a reflective scan for any zero-arg method on `Entity` returning `EntityType`. The handle is invoked from `of(Entity)` with the direct call retained as a last-ditch fallback. NeoForge 1.21.1 only exposes the Mojang name so the fast path is unchanged there.
- **Belt-and-braces:** `ForgeEvents.onEntityJoinLevel` (and the NeoForge 1.21.1 equivalent) now rate-limits its catch-block WARN to one entry per minute per unique `<exception class>:<message>` key via a `ConcurrentHashMap`. Any future per-entity-throwable bug surfaces clearly but cannot DOS the log file.

### Notes

- Pure-Java change in `:common` + `:forge` / `:neoforge`. No mappings change, no dependency bump, no Gradle change. SLF4J/sqlite-jdbc/JarInJar all unchanged from 1.0.1.
- All 4 hotfix jars built clean from a single repo state: `./gradlew -PbuildProfile=forgeonly :mc-1.18.2:forge:build :mc-1.19.2:forge:build :mc-1.20.1:forge:build` (JDK 17) + `./gradlew -PbuildProfile=mc1211 :mc-1.21.1:neoforge:build` (JDK 21).

## [1.0.1] — 2026-06-29

**Hotfix: silent boot kill on Forge/NeoForge servers running Sinytra Connector.**

### Fixed (critical, all Forge-family loaders)

#### 1. Silent boot kill from leaked Gson module descriptor

- **Leaked `META-INF/versions/9/module-info.class` from shaded Gson 2.10.1.** Gson 2.10.1 ships its module descriptor (`module com.google.gson@2.10.1`) as a Java-9+ multi-release entry under `META-INF/versions/9/module-info.class`. The Shadow `exclude 'module-info.class'` directive only stripped the top-level entry, leaving the multi-release variant intact. When a Fabric-on-Forge compatibility layer (e.g. Sinytra Connector ≥ `1.0.0-beta.46+1.20.1`) rewrites the module layer at boot via `org.sinytra.connector.service.hacks.ModuleLayerMigrator`, the JPMS sees VonixGuardian's outer jar claiming to be the `com.google.gson` module — which conflicts with the loader's own Gson module already in the layer. ModuleLayer construction fails inside a native frame, so no Forge crash report is written; the JVM is killed silently mid-boot. Symptom: log truncates at the line `Successfully made module authlib transformable`, no FML failure, no `crash-reports/*.txt`, Wings sees the server go offline within ~10s of start.
- **Fix:** all 4 Forge-family `build.gradle` files now exclude both `module-info.class` and `META-INF/versions/*/module-info.class` from the Shadow output. Verified `unzip -l vonixguardian-forge-1.20.1-1.0.1.jar | grep versions/9/module-info` returns nothing.

#### 2. `NoSuchMethodError: MinecraftServer.getServerDirectory()` on Sinytra-Connector servers

- **Cause.** Sinytra Connector remaps `MinecraftServer.getServerDirectory()` to return `java.nio.file.Path` (the post-1.21.2 Mojang signature) instead of the Forge-1.20.1-compiled `java.io.File`. VG's bootstrap call site, compiled against the official Forge MDK signature, throws `NoSuchMethodError` at `onServerStarting` on Connector-enabled servers. Stack trace points at `ForgeBootstrap.java:40`.
- **Fix.** Replaced direct call with `resolveServerDir(server)` reflection helper in all 3 Forge bootstraps (`mc-1.18.2`, `mc-1.19.2`, `mc-1.20.1`). The helper invokes `getServerDirectory()` via `Method.invoke()` and accepts both `Path` and `File` return types (fallback: `Paths.get("").toAbsolutePath()` which equals the Wings working directory). NeoForge 1.21.1 unaffected (vanilla signature is already `Path`).

### Build system

- Added `buildProfile=forgeonly` to `settings.gradle` to skip Fabric modules during Forge-family-only builds. Works around a fabric-loom-1.7.4 cross-version classloader conflict when configuring Loom plugins from multiple sibling Fabric subprojects simultaneously (`BuildSharedServiceManager$Inject` ClassCastException). Hotfix-build canonical command: `./gradlew -PbuildProfile=forgeonly :mc-1.18.2:forge:shadowJar :mc-1.19.2:forge:shadowJar :mc-1.20.1:forge:shadowJar :mc-1.21.1:neoforge:shadowJar`.

### Known issues (deferred to v1.0.2)

- On Sinytra Connector servers, the `/vg` command tree fails to register with `NoSuchMethodError: 'LiteralArgumentBuilder net.minecraft.commands.Commands.literal(String)'`. Connector remaps `Commands.literal()` similarly to `getServerDirectory()`. VG's core auditing and rollback engine still function — only the in-game command surface is missing. Console-side use is unaffected. Permission-based `/co i` interop layer is unaffected.

### Verified

- All 4 Forge-family jars rebuilt clean. `META-INF/versions/9/module-info.class` confirmed absent in every jar.
- Boot-tested `vonixguardian-forge-1.20.1-1.0.1.jar` against Sinytra Connector `1.0.0-beta.47+1.20.1` (matching production Linggango server). Result: Forge `Done (3.248s)!` reached, `VonixGuardian online` log line emitted, server stays running. Boot kill regression fixed.

### Scope of impact (v1.0.0 → v1.0.1)

- Fabric jars (4): unaffected by the bug, identical behavior to v1.0.0. Upgrade is optional (re-released for version-string consistency).
- Forge/NeoForge jars (4): **mandatory upgrade** for any server running Sinytra Connector, Forgix, or any other mod that rewrites the JPMS module layer at boot. Servers without layer manipulation will also benefit (defensive fix).

---



**First production-grade release.** All 8 jars live-boot-validated on real servers (NeoForge 1.21.1, Forge 1.18.2/1.19.2/1.20.1, Fabric all 4) with SQLite schema initialised, `/vg` command tree registered, zero JPMS/JNI failures, zero shaded-library leaks. CoreProtect 1:1 feature parity carried over from v0.1.0 — this release fixes the production blockers that would have stopped v0.1.0 from working in the wild.

### Fixed (production blockers from v0.1.0)

- **JNI relocation trap** — `sqlite-jdbc` was previously shaded + relocated to `network.vonix.guardian.shadow.sqlite.*`, which broke the JNI native-library symbol lookup (`Java_org_sqlite_core_NativeDB_*` baked into `.so/.dll/.dylib` files) → guaranteed `UnsatisfiedLinkError` on first DB op in production. **Fix**: `sqlite-jdbc` now nested via JarInJar (NeoForge `jarJar` / Forge FG6 `jarJar`) on Forge family loaders, shade-unrelocated on Fabric loaders. JNI symbol path preserved end-to-end.
- **Transitive `api` JPMS leak** — `core/build.gradle` used `api libs.sqlite.jdbc` (and Hikari, Gson), which dragged those packages through Shadow into every loader's outer jar even when the loader removed direct shading. On NeoForge 1.21.1, this triggered `java.lang.module.ResolutionException: Module vonixguardian contains package org.sqlite, module org.xerial.sqlitejdbc exports package org.sqlite to vonixguardian` at boot. **Fix**: demoted all runtime deps in `core/build.gradle` from `api` to `compileOnly`; loaders explicitly choose each lib's packaging (shaded+relocated, JarInJar, or external). Tests retained via `testRuntimeOnly`.
- **`extendsFrom` exclude cascade** — Fabric loaders' `implementation.extendsFrom shaded` caused `shaded.exclude(group: 'org.slf4j')` to propagate into `compileClasspath`, breaking compile-time references to `org.slf4j.Logger`. **Fix**: Fabric loaders now use a standalone `shaded` configuration fed explicitly into Shadow via `shadowJar.configurations = [shaded]`, with explicit `compileOnly libs.slf4j.api` for compile-time access. `org/slf4j/*` leak count = 0 on all 8 outer jars.
- **`RegisterCommandsEvent` timing race** — NeoForge/Forge `RegisterCommandsEvent` fires on a `Worker-Main-*` thread during datapack reload, BEFORE any server-lifecycle event. Previously, commands were silently skipped on first boot (`Commands fired before Guardian.boot — skipping`), making `/vg` unavailable until a `/reload`. **Fix**: deferred-and-replay pattern — dispatcher is captured at `RegisterCommandsEvent` time in a `volatile` static field, then registered when `Guardian.boot()` completes. Verified on live boot tests across all 8 jars: `Deferred /vg command registration until Guardian.boot` → `/vg command tree registered (deferred from RegisterCommandsEvent)`. Same pattern applied to Fabric `CommandRegistrationCallback` for consistency.
- **`mods.toml` schema mismatch** — Forge 1.18.2/1.19.2/1.20.1 use the legacy `mandatory = true` schema; NeoForge 1.20.4+ introduced the new `type = "required"` enum. Sharing one `mods.toml` template across loaders silently caused Forge to refuse loading the mod with NO error in the log (no manifest line, no exception, just absent). **Fix**: corrected all three Forge `mods.toml` files to `mandatory = true`. Mod manifest now visible at boot on every loader.
- **Forge legacy `mods.toml`** also needed correct `versionRange` per loader (Forge 1.18.2 uses `[40,)`, 1.19.2 `[43,)`, 1.20.1 `[47,)`).

### Hardened (defensive fixes from PR code review)

- **`pendingDispatcher` lifecycle** — every `*Events.java` (8 loaders) now exposes `reset()`, wired into the corresponding `*Bootstrap.onServerStopping` after `setGuardian(null)`. Eliminates the cross-restart stale-dispatcher hazard if the JVM survives a server stop (integrated server / LAN flow / dev) AND covers the case where `Guardian.boot()` throws before the replay path runs.
- **NeoForge `build.gradle` `jarjarDir.eachFile`** now guarded by an existence check mirroring the Forge files — defensive against future refactors that drop JarInJar nesting.
- **Fabric `shaded.exclude`** broadened from `module: 'slf4j-api'` to `group: 'org.slf4j'` — future-proofs against transitive `slf4j-jdk14` / `slf4j-simple` / `jul-to-slf4j` from a Hikari bump.
- **Atomic shadow rewrite** — all 8 loader `build.gradle` files now use `java.nio.file.Files.move(REPLACE_EXISTING, ATOMIC_MOVE)` instead of `File.renameTo()` for the post-shadow zip rewrite. `renameTo` silently returns `false` on Windows if antivirus / another tool holds the file; the build would have emitted a half-written or stale jar with no error.

### Added

- `docs/USAGE.md` — comprehensive operator's guide: TL;DR cheat sheet, filter syntax reference, all 39 action types, sentinel tokens for modded griefing attribution, common workflows (inspect / near / lookup / rollback+undo / mass purge), rollback transactional semantics, status/health/reload, LuckPerms node reference, verified-live boot matrix with timings + library packaging per loader, troubleshooting guide.
- `docs/INSTALL.md` — server-side install procedure, prerequisites per loader+MC, SHA-256 verification, first-boot verification.
- `docs/CONFIG.md` — full `config.json` reference: every field with type, default, valid range, reload-vs-restart matrix.
- `docs/PERMISSIONS.md` — every LuckPerms node, op-level fallback, `bypass` + `viewothers` security semantics.
- `docs/MIGRATION.md` — storage backend switching, version upgrade procedure, no-import-from-CoreProtect-yet status.
- `docs/DATABASE.md` — full schema reference (8 tables, 4 indices, ER diagram), backup/restore procedures per backend, direct SQL query examples.
- `docs/DEVELOPMENT.md` — contributor environment setup, IDE config (IntelliJ + Loom/ForgeGradle/NeoGradle), adding an ActionType, CI overview.
- `docs/ARCHITECTURE.md` — deep-dive: core/ engine → mc-common → loader glue layering, event flow, threading model, soft-dep pattern.
- `docs/API.md` — public Java API for third-party mods: soft-dep reflection pattern, `EventSubmitter` reference, `Guardian` facade, worked example.
- `docs/MODDED-ATTRIBUTION.md` — the differentiating feature documented in depth: TamableAnimal/OwnableEntity/Projectile/passenger chain, sentinel tokens, worked examples (Dragon Mounts, Create, wither).
- `docs/FAQ.md` — 40 Q&A covering compatibility (Create, Pixelmon, Dragon Mounts, MineColonies, WorldEdit), performance, security, operations, contributing.
- `CONTRIBUTING.md` — contribution workflow, branch + commit conventions, DCO sign-off, code style, PR process, what we will not accept.
- `SECURITY.md` — public disclosure policy, response SLA, scope, separate from internal `SECURITY-AUDIT.md`.
- README link to `docs/USAGE.md` + CHANGELOG + SHARED-CONTRACTS.
- `.coderabbit.yaml` — auto-review config including `release/*` and `baseline-*` base branches.

### Build / CI

- `core` test job now passes `-PbuildProfile=core` so `settings.gradle` skips configuring loader subprojects. Resolves the `fabric-loom` `BuildSharedServiceManager$Inject` configure-time crash that previously failed `:core:test` on CI.
- Build workflow now triggers on push to `main` and any `release/**` branch, and on PRs targeting any branch (was: `main` only).
- Artifact names sanitised: `:` → `-` in matrix module names (Gradle `mc-1.21.1:neoforge` → upload-artifact `mc-1.21.1-neoforge`) because `actions/upload-artifact@v4` rejects colons in artifact names (NTFS portability).

### Verified-live boot matrix (v1.0.0)

| Loader | MC | Library packaging | Boot time | SQLite | Status |
|---|---|---|---|---|---|
| NeoForge | 1.21.1 | JarInJar (sqlite-jdbc + slf4j-api nested) | 1.4s | ✅ 69632B | 🟢 |
| Fabric   | 1.21.1 | shade-unrelocated sqlite-jdbc, slf4j excluded | 0.9s | ✅ 69632B | 🟢 |
| Forge    | 1.20.1 | FG6 jarJar (sqlite-jdbc + slf4j-api nested) | 3.4s | ✅ 69632B | 🟢 |
| Fabric   | 1.20.1 | shade-unrelocated, slf4j excluded | ≤2s | ✅ 69632B | 🟢 |
| Forge    | 1.19.2 | FG6 jarJar | 8.3s | ✅ 69632B | 🟢 |
| Fabric   | 1.19.2 | shade-unrelocated | ≤2s | ✅ 69632B | 🟢 |
| Forge    | 1.18.2 | FG6 jarJar | 44s (cold worldgen) | ✅ 69632B | 🟢 |
| Fabric   | 1.18.2 | shade-unrelocated | ≤2s | ✅ 69632B | 🟢 |

Every jar verified to: load mod manifest, boot Guardian engine, initialise SQLite schema (8 tables), register `/vg` command tree, complete bootstrap before MC `Done`, survive zero `ResolutionException` / `UnsatisfiedLinkError` / shaded-library leak (`org/slf4j/*` = 0 on all outer jars).

### Deferred to v1.1.0

- Fabric mixins for `LivingDestroyBlock` / `Explosion.Detonate` / piston / `ItemToss` / `ItemPickup` / container — Fabric currently has player-driven coverage only; mob/explosion griefing capture needs per-MC-version mixins (~20 files).
- WorldEdit soft-dep region lookup via pure reflection.
- MySQL / PostgreSQL live integration smoke-test (code path wired but never exercised against a real DB).
- Forge/NeoForge `shaded` configuration decoupling (currently relies on Minecraft re-exporting slf4j; not broken today, less defensive than the Fabric pattern).

## [0.1.0] — 2026-06-27

First public release. CoreProtect 1:1 feature surface with universal modded-entity griefing attribution. **8 drop-in jars** covering the four most-deployed modded Minecraft versions × {Fabric, Forge/NeoForge}.

### Coverage matrix

| MC version | Fabric | Forge | NeoForge |
|-----------:|:------:|:-----:|:--------:|
| 1.21.1     |   ✅   |   —   |    ✅    |
| 1.20.1     |   ✅   |   ✅  |    —     |
| 1.19.2     |   ✅   |   ✅  |    —     |
| 1.18.2     |   ✅ * |   ✅  |    —     |

\* Fabric 1.18.2: chat capture deferred to v0.2.0 (no `ServerMessageEvents.CHAT_MESSAGE` in fabric-api 0.77 — needs a Mixin).

### Added

#### Core engine (`core/`)
- **39 action types** matching the full CoreProtect listener surface plus the modded griefing path:
  - Block: `BLOCK_PLACE`, `BLOCK_BREAK`, `BURN`, `IGNITE`, `FADE`, `FORM`, `SPREAD`, `DISPENSE`, `PISTON_EXTEND`, `PISTON_RETRACT`, `BUCKET_EMPTY`, `BUCKET_FILL`, `LEAVES_DECAY`, `ENTITY_CHANGE_BLOCK`
  - Container: `CONTAINER_DEPOSIT/WITHDRAW`, `INVENTORY_DEPOSIT/WITHDRAW`, `HOPPER_PUSH/PULL`
  - Item: `ITEM_DROP`, `ITEM_PICKUP`, `ITEM_CRAFT`
  - Entity: `ENTITY_KILL`, `ENTITY_SPAWN`, `ENTITY_INTERACT`, `HANGING_PLACE/BREAK`
  - World: `EXPLOSION`, `STRUCTURE_GROW`, `PORTAL_CREATE`, `CHUNK_POPULATE`
  - Message: `CHAT`, `COMMAND`, `SIGN`
  - Session: `SESSION_JOIN/LEAVE`, `USERNAME_CHANGE`
  - Interact: `CLICK`
- **`/vg lookup` filter mini-language** parser supporting CoreProtect `u:` / `t:` / `r:` / `a:` / `i:` / `e:` / `#preview` / `#count` / `#verbose` / `#silent`, with family expansion (`a:block`, `a:container`, etc.).
- **SQLite, MySQL, and PostgreSQL storage backends** behind a single `GuardianDao` interface. HikariCP pooling; SQLite serialized via `ReentrantLock`. Schema v2 with `vg_actions` + `vg_users` + `vg_worlds` + `vg_rollback_batches` + `vg_rollback_batch_actions`.
- **Server-thread-friendly async write queue** (`BatchedAsyncWriteQueue`): bounded `ArrayBlockingQueue`, batch INSERTs, 3× retry on sink failure with 250ms backoff, marker-logged drops.
- **Rolling JSON-Lines audit log file** at `logs/vonixguardian/audit-YYYY-MM-DD.log.jsonl` with daily gzip rotation and configurable retention.
- **Rollback engine** with position-dedup, newest-first ordering, SQL-side rolledBack filtering, batched main-thread dispatch (max 1000 per tick), and crash-recovery via `vg_rollback_batches` audit table.
- **`/vg purge`** with configurable minimum-age guard (CoreProtect parity: 24h from console / 30d in-game by default).
- **Permission resolver** with reflective LuckPerms bridge (zero hard dependency) and tri-state availability cache; op-level fallback if LP absent.
- **`/vg lookup` rate limit** via fair semaphore (configurable `maxConcurrent`); result-row cap (configurable `maxResultRows`, default 100,000).
- **IP hashing** (SHA-256, salted, 64-bit prefix) for `SESSION_JOIN` events; default disabled.
- **Universal modded-entity attribution** (`core/attribution`):
  - `AttributionResolver` interface walks a 6-step universal chain (rider → tameable owner → ownable owner → projectile recurse → recent damage → NBT scan → natural classification).
  - `DamageHistory` ring buffer (20s window, 10k entry cap, LRU eviction) for "berserk mob" indirect attribution.
  - `Attribution` record encodes the responsible-party UUID + kind + entity sentinel + chain hops.
- **Themed chat** (7 built-in palettes): aqua (default), blue, gold, green, purple, red, white.
- **354 unit + integration tests** (JUnit 5 + AssertJ + Mockito) including a 1000-action SQLite integration test, semaphore-cap test, and crash-recovery batch test.

#### Loader modules

8 loader jars implementing the universal event surface using only first-class loader events on every MC version × loader pair:

- **NeoForge 1.21.1**: full event coverage including `LivingDestroyBlockEvent`, the universal modded griefing path.
- **Forge 1.20.1 / 1.19.2 / 1.18.2**: same coverage; per-MC API drift handled (event package rename, Registry vs BuiltInRegistries, Component.literal vs TextComponent, getControllingPassenger return type, etc.).
- **Fabric 1.21.1 / 1.20.1 / 1.19.2**: player-driven event coverage via fabric-api callbacks (`PlayerBlockBreakEvents`, `UseBlockCallback`, `AttackBlockCallback`, `ServerLivingEntityEvents`, `ServerEntityEvents`, `ServerPlayConnectionEvents`, `ServerMessageEvents`, `CommandRegistrationCallback`).
- **Fabric 1.18.2**: same except chat/command capture (no `ServerMessageEvents` until 1.19) and `ALLOW_DAMAGE` indirect-attribution feed (no event) — flagged for v0.2.0.

Each loader implements:
- `WorldMutator` for rollback world mutations (setBlock / give-or-drop / removeFromContainer / respawnEntity).
- `OpLevelFallback` (op-level resolution via the server's player list).
- `AttributionResolver` (the universal chain using vanilla `Entity` / `TamableAnimal` / `OwnableEntity` / `Projectile` interfaces that all modded entities inherit).
- Brigadier command tree wiring for `/vg` (alias `/guardian`) with all subcommands.
- Inspector mode left-click cancel and per-player toggle state.

Each loader jar shades:
- The pure-Java `core` engine
- `sqlite-jdbc 3.46.1.0` (default DB driver)
- `HikariCP 5.1.0`
- `Gson 2.10.1`

with package relocation under `network.vonix.guardian.shadow.*` to avoid mod-pack conflicts.

#### Build infrastructure

- **Profile-based build**: `-PbuildProfile=core|mc1211|mc1201|mc1192|mc1182|all` (default `all`) so any subset of jars can be built without configuring unrelated loader plugins.
- **Gradle version catalog** (`gradle/libs.versions.toml`) with every dependency pinned.
- **GitHub Actions matrix workflows**: `build.yml` runs every push (full test + 8-jar matrix); `release.yml` runs on tag push (builds + SHA-256 checksums + creates the GH release with all 8 jars attached).

### Known limitations (planned for v0.2.0)

- Fabric versions 1.18.2 / 1.19.2 / 1.20.1 / 1.21.1: `LivingDestroyBlock` (modded mob griefing), piston, leaves decay, neighbor notify, explosion-detonate affected-block list, item toss/pickup/craft, and sign change events all require Mixins on Fabric (fabric-api exposes no first-class hooks). Player-driven events are fully covered today; non-player block changes by mobs are partially deferred.
- Fabric 1.18.2 only: chat + command capture also deferred to v0.2.0 (no `ServerMessageEvents.CHAT_MESSAGE` in fabric-api 0.77 — needs a Mixin into `ServerGamePacketListenerImpl#handleChat`).
- Metrics export (Prometheus): no scrape endpoint in v0.1.0.
- WorldEdit selection (`r:#we` / `r:#worldedit`): the filter parser accepts the token but the loader-side WE region lookup is not wired yet.
- MySQL and PostgreSQL backends: code paths land but are documented as **beta** — SQLite is the fully tested backend for v0.1.0.

### Architecture

- Pure-Java engine in `core/` with zero Minecraft dependencies (10,465 LOC).
- Per-MC `common/` packages with MC-version-specific Mojmap code (the brigadier commands, chat rendering, source tagging, attribution helpers).
- Thin loader modules (~30-50 LOC each for the entrypoint + event-routing classes; rest is shared `common/`).
- No `architectury` runtime dependency; the engine is a regular shaded library.

### License

MIT. See [LICENSE](LICENSE). Inspired by CoreProtect (Artistic-2.0; clean-room implementation, no source copied) and Ledger (LGPL-3; same).

[0.1.0]: https://github.com/Vonix-Network/VonixGuardian/releases/tag/v0.1.0
