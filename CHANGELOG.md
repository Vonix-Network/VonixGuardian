# Changelog

All notable changes to **VonixGuardian** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
