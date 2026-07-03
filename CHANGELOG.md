# Changelog

All notable changes to **VonixGuardian** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Z2 — Forge natural-block event-bus fallback.** Round-3 audit finding P1-A:
  the three Forge cells (1.18.2 / 1.19.2 / 1.20.1) shipped 15 mixin source
  files (`FireBlockMixin`, `IceBlockMixin`, `LeavesBlockMixin`,
  `SpreadingSnowyDirtBlockMixin`, `DispenserBlockMixin` × 3) that compiled
  dormant — no `vg.mixins.json` wiring — so `BURN`, `IGNITE`, `FADE`, `FORM`,
  `SPREAD`, `LEAVES_DECAY`, and `DISPENSE` action rows were never emitted on
  Forge. Z2 wires the same coverage via `BlockEvent.NeighborNotifyEvent` +
  a bounded LRU pre-state cache inside each cell's `ForgeEvents.java`. All
  reserved `#fire` / `#natural` sourceTag prefixes preserved so the v1.3.0
  W4 mixin-hot kill-switch still short-circuits the natural-block flood.
  DISPENSE is explicitly not covered on Forge — see
  `docs/PERF-NOTES-1.3.3.md` § Z2 for the acknowledged gap and rationale.

### Changed

- **Z2 — orphan mixin cleanup.** Fifteen dormant Forge mixin files deleted
  (`Fire`/`Ice`/`Leaves`/`SpreadingSnowyDirt`/`DispenserBlockMixin.java` × 3
  cells). Fabric + NeoForge cells unchanged — they still log the natural
  block surface via their wired mixins.

## [1.3.2] - 2026-07-03

**Wave-Y integration close-out.** Every X-series (v1.3.1) knob and producer
that shipped as validated-but-partially-unwired is now end-to-end live.
Round-2 post-1.3.1 audit surfaced three regressions where the boot / reload
paths never actually plumbed the operator knob into the running engine
(P0-1, P1-1, P2-4); Y3 closes all of them and pins the fix with regression
tests. Y1 activates the X1 NBT surface all the way from producer to
migration; Y2 restores Forge cell event-bus parity and drops the last
orphan mixins; Y5 amortises attribution-memory eviction so it can't stall
the server thread on Berk-scale traffic.

### Added

- **Y1 — NBT surface activation end-to-end.** X1 (v1.3.1) shipped the
  Schema V5 columns, the `config.storage.persistNbt` toggle, and the
  producer-side setters — but nothing on the loader side actually captured
  NBT into a live event. Y1 wires the capture on every producer path
  (block placements, block breaks, entity kills, container operations,
  item drops), lands the `V5NbtFidelity` migration under the coreonly and
  all three mc-1.21.1 loader profiles, and pins the round-trip through
  rollback with new regression coverage. When `storage.persistNbt=true`,
  a diamond-sword-with-Sharpness-V now round-trips through
  `/vg rollback`; when the toggle is off, the hot path stays
  allocation-free.
- **Y2 — Forge cell event-bus parity + orphan mixin cleanup.** The Forge
  loader cells (`mc-1.21.1/forge` etc.) now dispatch producer events
  through the same event-bus contract the NeoForge cells use, closing a
  loader-specific gap where certain block-change events fired on
  NeoForge/Fabric but not Forge. Also drops the last three orphan
  mixins retained during v1.3.1's parallel waves that had been superseded
  by X2 and X9's dedicated mixins.
- **Y3 — Reload + boot config plumbing (this section is the P0-1 / P1-1 /
  P2-4 close-out).**
  - `Guardian.reloadConfig` now builds its merged config via the canonical
    **12-arg** `GuardianConfig(...)` constructor. Pre-Y3 it used the 9-arg
    backward-compat overload which silently backfilled `storage`, `rollback`,
    and `language` with `defaults()` — meaning every `/vg reload` reverted
    operator-configured `storage.persistNbt=true` to `false`, snapped
    `rollback.explosionSupplementalReach` back to 16, and forced
    `language` back to `"en_us"`.
  - `Guardian.boot` now passes `config.rollback().explosionSupplementalReach()`
    into a 4-arg `new RollbackEngine(...)`. Pre-Y3 the 3-arg constructor
    hard-coded `MAX_TNT_REACH=16`, so the X8 config knob was validated but
    ignored at runtime.
  - `RollbackEngine.explosionSupplementalReach` is now `volatile`, with a
    `setExplosionSupplementalReach(int)` setter and a
    `getExplosionSupplementalReach()` accessor. `Guardian.reloadConfig`
    calls the setter after every merge, so the running engine picks up the
    new reach without a restart and without a torn read racing a concurrent
    `/vg rollback`.
  - `AutoPurgeScheduler.applyConfig(GuardianConfig)` (new). Cancels the
    pending `nextTask`, updates `retentionSeconds` / `runTime` /
    `enabled` (all now `volatile`), and reschedules under the fresh
    `HH:mm`. `Guardian.reloadConfig` invokes it when
    `cfg.purge()` changed — pre-Y3 the daemon kept its boot-time schedule
    until server restart even though `/vg status` reported the new value.
  - `/vg reload` now records `storage.persistNbt`,
    `rollback.explosionSupplementalReach`, and `language` in the
    hot-swapped diff so operators get the same feedback for these knobs
    as for `theme` or `purge`.
- **Y5 — Attribution memory amortization.** `TntPrimeMemory.hardEvict` and
  `FluidSourceMemory.lookup` now use the DamageHistory-style
  `evictCounter % STRIDE` gate, capping the second-pass scan and letting
  the map overshoot the cap by a bounded slice. Removes the two remaining
  O(n) scans on the server thread called out in the round-2 audit; keeps
  memory ceilings unchanged.

### Changed

- Boot: `Guardian.boot` line 226 uses the 4-arg `RollbackEngine`
  constructor (see Y3 P1-1 close-out).
- Reload: `Guardian.reloadConfig` uses the 12-arg canonical
  `GuardianConfig` constructor (see Y3 P0-1 close-out).
- `AutoPurgeScheduler`: `retentionSeconds`, `runTime`, and `enabled`
  fields changed from `final` to `volatile` to support `applyConfig`.
- `RollbackEngine.explosionSupplementalReach` changed from `final int` to
  `volatile int` to support hot-swap on `/vg reload`.
- Version bump `1.3.1 → 1.3.2` (`gradle.properties#mod_version`,
  `GuardianAPI.PLUGIN_VERSION`).

### Fixed

- **P0-1 (round-2 audit)** — `/vg reload` no longer silently drops
  `storage`, `rollback`, and `language` on every reload.
- **P1-1 (round-2 audit)** — `rollback.explosionSupplementalReach` is now
  actually applied to the running engine, at boot and on reload. Modded
  servers running mega-TNT mods whose affected-list reaches more than 16
  blocks no longer silently miss rows in rollback.
- **P2-4 (round-2 audit)** — `/vg reload` now reschedules the running
  auto-purge daemon under the new `autoPurgeTime` /
  `autoPurgeSeconds`. Operators editing the config to move the nightly
  run out of a raid window no longer have to restart to pick it up.

### Regression tests

- `ReloadPreservesSectionsTest` — pins `storage.persistNbt`,
  `rollback.explosionSupplementalReach`, and `language` across
  `/vg reload`.
- `ReloadRebuildsAutoPurgeTest` — pins `applyConfig` reschedule /
  disable / no-op paths against the same live `AutoPurgeScheduler`.
- `ReloadUpdatesRollbackReachAtomicTest` — pins the `volatile`
  publication contract on `RollbackEngine.explosionSupplementalReach`
  under a hot writer / hot reader race and the setter's `< 0`
  validation.

### Deferred to v1.3.3

- P1-2 / P1-3 shape-only findings in `TntPrimeMemory` (dead constructor
  argument, second-pass sweep bounds) — non-regression, filed for the
  next v1.3.3 wave.
- P1-4 `FluidSourceMemory` empty-map fast-path (`byPos.isEmpty()` short
  circuit) — bounded impact at 1.3.2 traffic scale, deferred with Y5's
  amortisation absorbing the current hot path.

## [1.3.1] - 2026-07-03

**Parity + perf close-out.** Closes v1.3.0's audit findings across nine parallel
X-waves (X1-X9). Three P0 parity gaps: entity-block-change coverage, NBT
fidelity, fluid-flow attribution. Six P1 parity gaps: TNT-prime attribution,
hopper/portal producers, /fill //setblock command mixins, auto-purge daemon,
query-parser polish (decimal/range time, WorldEdit bridge). Three P1 perf items:
HikariCP prep-stmt cache, DamageHistory eviction, W5 supplemental spatial bound.
Perf P2/P3 polish: shutdown ordering, reload atomicity, DAO amortization, and
misc cleanups.

### Added

- **X1 — Schema V5 NBT fidelity + producer surface (P0).** Five nullable
  columns on `vg_actions` (`old_block_state TEXT`, `new_block_state TEXT`,
  `block_entity_nbt BLOB`, `item_nbt BLOB`, `entity_nbt BLOB`) with a
  `V5NbtFidelity` migration. Five NBT setters on `ActionBuilder`; NBT-aware
  default overloads on `EventSubmitter` and `WorldMutator`; new config toggle
  `config.storage.persistNbt` (default `false`) gating producer-side NBT
  capture. Closes the largest CoreProtect / Ledger parity gap — named +
  enchanted swords, chest contents, custom-named tamed mobs, waterlogged /
  facing state all round-trip through rollback when the toggle is on. Zero
  hot-path allocation when the toggle is off.
- **X2 — Entity-caused block-change dedicated mixins (P0).** New mixins on
  EnderDragon, Ravager, SnowGolem, FallingBlockEntity, LightningBolt,
  Silverfish; EntitySentinel additions. Closes the "modded entities that break
  blocks without firing `LivingDestroyBlockEvent`" gap.
- **X3 — Fluid-flow attribution producer (P0).** New `LiquidBlockMixin`,
  `Sources.FLUID` + `#fluid` sentinel, `ActionType.FLUID_FLOW`, RollbackEngine
  admit path. Water / lava flow now attributes to the placing player through
  the standard producer surface.
- **X4 — Hopper + Portal producers + /fill //setblock command mixins (P1).**
  New HopperBlockEntityMixin, PortalShapeMixin, FillCommandMixin,
  SetBlockCommandMixin; new `submitHopperPush/Pull` producer wiring.
- **X5 — Auto-purge daemon + query-parser polish (P1).** New
  `AutoPurgeScheduler` daemon under `config.purge.autoPurgeSeconds +
  autoPurgeTime`, gated by the 30-day minimum. QueryParser now accepts decimal
  time (`t:1.5h`), range time (`t:1h..2h`), and the WorldEdit selection bridge
  (`t:we-sel`). GuardianSuggestions aligned.
- **X7 — TNT-prime attribution (P1).** New TntBlockMixin + PrimedTntEntityMixin
  (8 cells), attribution resolver extension. Player who primed the TNT is
  credited on the resulting EXPLOSION row instead of `#tnt`.
- **X8 — W5 supplemental spatial bounding (P1 perf).** RollbackEngine
  supplemental EXPLOSION scan now widens the DAO spatial predicate by
  `config.rollback.explosionSupplementalReach` (default 16 blocks) instead of
  dropping it entirely, keeping the DAO scan bounded on high-TNT servers.
- **X9 — Container coverage widening + `BaseContainerBlockEntityMixin` (P2).**
  New base mixin, ContainerMixin widening; closes the "some modded containers
  didn't fire CONTAINER_* events because they extend the base class directly"
  gap.

### Changed

- **X6 — HikariCP tuning, DAO polish, shutdown ordering, reload atomicity,
  perf polish (P1/P2/P3).**
  - `MysqlDao` — enable server-side prepared-statement caching via
    `cachePrepStmts / prepStmtCacheSize=250 / prepStmtCacheSqlLimit=2048 /
    useServerPrepStmts=true` matching HikariCP's MySQL recommendation.
    Biggest remaining backend win on busy audit shards.
  - `PostgresDao` — set `prepareThreshold=3` so pgJDBC switches to named
    server-side prepared statements after three executes of the same PS.
  - `GuardianConfig.database.hikari` — new nested record with
    `maxPoolSize / connectionTimeoutMs / maxLifetimeMs / leakDetectionMs`
    knobs; defaults match Hikari's own except pool=10 (pre-X6 hard-coded
    value). `ConfigLoader` backfills the section on pre-X6 configs.
  - `StorageFactory` — switch the read-side rate-limit `Semaphore` from
    fair (FIFO) to unfair. Cuts p99 lookup latency under contention.
  - `AbstractJdbcDao.resolveUserOn` — amortize the `UPDATE vg_users SET
    last_seen` write. Only rewrite when the in-memory record drifts by more
    than 60 s.
  - `DamageHistory.record` — amortize the O(n) `evictOldest` sweep. Only run
    every 64th over-cap insert instead of every damage event at cap.
  - `EntityBlockChangeCoalescer.shouldLog` — short-circuit consecutive
    over-cap inserts. After a sweep that evicted zero entries, drop new
    events for a 50 ms back-off window before probing again.
  - `Guardian.shutdown` — reorder so `ExplosionJoinWorker.close()` runs
    BEFORE `queue.drainAndFlush(...)`, and give it `awaitTermination(5s)`.
    Pending join tasks now land their `submitExplosion` calls in the write
    queue before the queue drains and the DAO closes.
  - `Guardian.reloadConfig` — build the fresh `EventGate` on a local
    reference, register per-world / blacklist / PreLog hooks against the
    local before publishing to `this.gate`. Concurrent submitters no longer
    observe a half-registered hook chain during `/vg reload`.
  - `Guardian.reloadConfig` — route `ConfigLoader.load` onto the common
    ForkJoinPool with a 10 s timeout, so `Files.readString + Gson.fromJson`
    do not stall the server thread on a slow or networked filesystem.
  - `Guardian.submitExplosion` — use the per-thread scratch builder via
    `seed()` instead of `new ActionBuilder()`. Consistent with every other
    submit path and cuts ~40 B / allocation on the explosion-join worker.
  - `NeoForgeEvents.reset` — shut down the previously-static-daemon
    `WORKER` executor. Fixes a dev-mode thread leak on in-JVM restart.
  - `JsonLinesLogFile` — new `config.logFile.forceSyncOnFlush` toggle
    (default `true`). Operators on slow storage can trade &lt; flushIntervalMs
    of durability for zero per-batch `FileChannel.force(false)` cost.
  - `PerWorldConfigStore.reload` — cache file mtimes; unchanged files reuse
    the previous merged snapshot instead of re-reading and re-parsing.
  - `EventGate.addInternalHook` — soft cap of 32 internal hooks with a
    one-shot WARN when exceeded; `/vg status` now surfaces the count under
    the Event Hooks section.
  - `RollbackEngine.hasMoreRows` — replace the extra `dao.query(..., 1)`
    round-trip per page-boundary with `dao.query(..., PAGE_SIZE+1)` so the
    "has more" signal comes from the main page fetch.

### Fixed

- All P0/P1 parity findings from the v1.3.0 post-integration audit (see
  `docs/PERF-NOTES-1.3.1.md` for the wave-by-wave rationale).

### Version

- `gradle.properties` — `mod_version` bumped `1.3.0` → `1.3.1`.
- `GuardianAPI.PLUGIN_VERSION` bumped `1.3.0` → `1.3.1`.

## [1.3.0] - 2026-07-03

**Async / server-thread performance wave.** Full v1.3.0 release notes covering
all six workstreams that landed against `feature/v1.3.0-integration`:
W1a (fire mixin tighten), W1b (spread mixin tighten), W1c (other natural / dispenser mixins),
W2 (server-thread allocation cuts + version bump),
W3 (explosion off-thread join + EventGate fast-path + P2 cleanups),
W4 (mixin diagnostics + kill-switch config), and W5 (explosion rollback fidelity).

### Added

- **W3 — Off-thread `ExplosionJoinWorker`.** New core class
  `network.vonix.guardian.core.event.ExplosionJoinWorker`, exposed via
  `Guardian.explosionJoinWorker()`. Loader-side `onExplosionDetonate`
  (all 4 Forge/NeoForge cells) and `FabricMixinBridge.explosion(...)`
  (all 4 Fabric cells) now capture affected-block coordinates + resolved
  block ids into a pooled `ThreadLocal<ExplosionScratch>` on the server
  thread and hand the `StringBuilder` join + queue submit to a daemon
  worker. A 5,000-block TNT chain that previously stalled the server
  thread for tens of ms now returns in single-digit µs on the caller.
  Regression: `ExplosionJoinWorkerTest` (6 cases) +
  `BenchExplosionAffectedListJoin` (measured 94.24 % server-thread wall-time
  reduction; target ≥ 90 %).
- **W3 — Chunked EXPLOSION rows.** The join worker splits large explosions
  into multiple EXPLOSION rows of ≤ 96 entries each, sharing the same
  (center, sourceTag, actor). Prevents the pre-1.3.0 silent truncation at
  the 4096-char storage cap. W5's rollback engine already iterates the
  affected-list per row so chunking is transparent to `/vg rollback`.
  Regression: `ExplosionAffectedListChunkedParsingTest`.
- **W3 — `EventGate.addInternalHook(EventHook)`.** New opt-in hook list
  consulted for mixin-authored events (`#fire`, `#natural`, `#dispenser`
  sourceTag prefixes) that bypass the standard hook chain. Observers that
  genuinely need visibility to hot-tick submits register here; the standard
  chain (`PerWorldEventHook`, `BlacklistFileHook`, `PreLogEventHook`) never
  sees these events. New counter: `EventGate.internalBypassCount()`.
  Regression: `EventGateFastPathTest` (7 cases).
- **W4 — Diagnostics + operator kill-switch (`actions.mixinHotEvents`).** New
  boolean config field (default `true`) that, when set to `false`, drops any
  submission whose `sourceTag` was authored by one of the W1a/b/c hot-tick
  mixin pipelines (`#fire`, `#natural`, `#dispenser` reserved prefixes) before
  the gate or queue see it. Ships with a per-type sliding-window submit-rate
  meter (`BatchedAsyncWriteQueue.submitRateByType()`,
  `.allocationRatePerSecond()`, 30 s / 1 s bucket) surfaced in `/vg status`
  as a "Mixin hot events" section for load-shedding decisions.
  Classifier: `network.vonix.guardian.core.diagnostics.MixinHotEventFilter`.
- **W5 — Explosion rollback fidelity (CoreProtect-parity, Option A).** The
  rollback engine now loops the stored `affected` list on rollback (piston
  chains, WorldEdit-shaped effects) so blocks displaced by an explosion are
  restored to their pre-explosion state, not just at the stored explosion
  center. Ships a supplemental-scan pattern
  (`RollbackEngine.streamPlan` → `withExplosionOnlyNoSpatial(base)`) that
  reuses the same cancel/limits/pages plumbing without double-counting rows
  already covered by the primary spatial scan, backed by a new
  `ExplosionAffectedList` value class (`parse` / `serialize` /
  `anyWithinRadius`) with a pure-unit + engine-driven test pair.
- **W2 — ThreadLocal ActionBuilder scratch on `Guardian`.** `Guardian.seed(...)`
  now reuses one `ActionBuilder` per producer thread via a `ThreadLocal` +
  `ActionBuilder.reset()`, cutting mutable builder allocation on the
  server-thread hot path (piston farm, fire, entity spam, hoppers).
  Regression: `ActionBuilderPoolingTest`.
- **W2 — Pre-populated per-type maps in `BatchedAsyncWriteQueue`.**
  `submittedByType`, `droppedByType`, and `submitRateByType` are seeded at
  boot with one entry per `ActionType.values()` (plus an `UNKNOWN` sentinel).
  Hot-path `submit(...)` is now a plain `map.get()` — no `computeIfAbsent`,
  no lambda capture, no `LongAdder`/`RateBuckets` first-touch allocation.
  Regression: `BatchedAsyncWriteQueueNoComputeIfAbsentTest`.
- **W2 — De-boxed `SPAWN_LIMIT` across all 8 cells.**
  `FabricEvents.java` × 4 + `ForgeEvents.java` × 3 + `NeoForgeEvents.java` × 1
  now use `ConcurrentHashMap<String, AtomicLong>` with in-place
  `AtomicLong.set(now)` instead of `Map<String, Long>` + `put(type, now)` —
  no `Long` autoboxing per spawn. Regression: `SpawnLimitDeBoxTest`.
- **`ActionBuilder.reset()`** — public method that clears every field back
  to freshly-constructed state, contract for `ThreadLocal` scratch reuse.

### Changed

- **W1a — `FireBlockMixin` tightened from HEAD `@Inject` to guarded
  `@Redirect`** across all 8 cells. Old-style HEAD injection fired a BURN
  submit on every random tick regardless of whether fire consumed anything;
  the new `@Redirect` around `removeBlock`/`setBlock` only submits when the
  call actually returned true and the affected block was not air. Measured
  ~90 % reduction in `FireBurnHotPathBench` (target ≥80 %). Regression:
  `FireBurnHotPathBenchTest` + behaviour tests.
- **W1b — `SpreadingSnowyDirtBlockMixin` tightened** using the same
  `@Redirect` pattern; submissions now only fire when the setBlockAndUpdate
  call actually converted dirt → grass, not on cosmetic snowy state flips.
  Measured 98 % reduction in `SpreadHotPathBench` (target ≥95 %). Regression:
  `SpreadNoOpSuppressionTest`, `SpreadRealSpreadTest`.
- **W1c — `LeavesBlock`, `IceBlock`, `ConcretePowder` mixins tightened.**
  Same `@Redirect` shape. `DispenserBlock` intentionally keeps `@Inject(HEAD)`
  because it is a discrete redstone event (locked in with a
  `requiresRedirectRefinement()==false` behaviour test). Reductions: Leaves
  ≥70 %, Ice ≥90 %, ConcretePowder ≥95 % — all met on `OtherMixinsHotPathBench`.
  Regression: `OtherMixinsHotPathBenchTest`.
- **W2 — Mixin-hot-events kill-switch folded from `Guardian.submit` into
  `EventGate.shouldLog`.** The `!mixinHotEvents && MixinHotEventFilter.isMixinSourced(...)`
  short-circuit now sits at the top of the gate so an activated kill-switch
  drops the action before any type check, blacklist lookup, or hook-chain
  traversal. Behaviour is identical; the surface is smaller.
  Regression: `MixinHotEventsGateTest`.
- **W3 — `onExplosionDetonate` moved off the server thread.** The per-block
  `getBlockState` + `Registry.BLOCK.getKey().toString()` calls stay on the
  server thread (they are not documented as thread-safe — Prism / Ledger /
  CoreProtect all agree), but the `StringBuilder` join and the
  `EventSubmitter.submitExplosion(...)` enqueue move to a daemon executor.
  Applied consistently to all 4 Forge/NeoForge cells' `*Events.java` and all
  4 Fabric cells' `FabricMixinBridge.explosion(...)`. Silent 4 KiB
  truncation is gone — large explosions now emit multiple chunked
  EXPLOSION rows (see Added).
- **W3 — `onRightClickBlock` reads `BlockState` once.** The 4 Forge/NeoForge
  cells now capture the block state at the top of the handler and reuse it
  across the container-entity gate + CLICK submit. The container-entity
  check is now guarded by `state.getBlock() instanceof EntityBlock`, so
  right-clicks on plain terrain no longer hit the chunk `BlockEntity` map.
- **W3 — `cleanupContainerSnapshots` amortized.** The full O(n) TTL scan now
  runs at most every 32 opens; the bounded over-cap eviction still runs on
  every open, so the snapshot map never grows unboundedly. Applied
  consistently across all 4 Forge/NeoForge cells and all 4 Fabric bridges.
  Regression: `ContainerCleanupAmortizationTest`.
- **`Actions` config record widened to 32 fields** (adds `mixinHotEvents`,
  default `true`). Pre-existing 31-arg and 18-arg deprecated constructors
  keep every previous test call site source-compatible.

### Fixed

- **Explosion rollback affecting only the center coordinate** — Option A
  (loop-the-affected-list) restore is now the shipped behaviour; the
  supplemental-scan pattern avoids duplicating rows already touched by the
  primary spatial scan.

### Performance

- **Server-thread allocation on the submit hot path is ~50 % lower** —
  `BenchGuardianSubmitAllocation` (100 k iterations, HotSpot 21): 17.6 MB
  allocated old vs 8.8 MB new = 50.0 % reduction; wall-time 37 % lower.
  On production hot paths (piston farm, fire spread) the win is bigger
  because the surrounding record scaffolding is thicker than the bench
  models.
- **Mixin kill-switch response is O(1)** — a disabled `mixinHotEvents`
  drops a hot-path action in 3 `startsWith` probes without touching the
  hook chain.
- **W3 — Server-thread wall-time on explosion detonate reduced 94 %**
  (`BenchExplosionAffectedListJoin`, 5,000-block × 200 iterations):
  11,391 µs → 656 µs total caller time (56.9 µs → 3.3 µs per explosion).
  Target ≥ 90 % met. A 5,000-block TNT chain that previously stalled the
  server thread for tens of ms now hands the join off in single-digit µs.
- **W3 — EventGate fast-path for internal (mixin-authored) events** skips
  the standard hook chain entirely (`PerWorldEventHook`,
  `BlacklistFileHook`, `PreLogEventHook`). Non-mixin actions still traverse
  the full chain. Counter surfaced as `EventGate.internalBypassCount()`
  for diagnostics.
- **W3 — `onRightClickBlock` reads `BlockState` once** across container-entity
  gate + CLICK submit; container-entity check now uses `state.getBlock()
  instanceof EntityBlock` so plain-terrain RCs skip the chunk BE-map lookup.
- **W3 — `cleanupContainerSnapshots` runs the O(n) TTL scan at most every
  32 opens** (was: every open). Bounded over-cap eviction still runs on
  every open, so the snapshot map never grows unboundedly.

## [1.2.7] - 2026-07-03

### Fixed

- **Async command output now reaches the player** — `/vg lookup`, `/vg rollback`, `/vg restore`, `/vg purge`, `/vg undo`, `/vg status`, `/vg config`, `/vg consumer`, and `/vg migrate-db` all resolve the calling player by UUID inside `server.execute(...)` and deliver via the per-version `sendSystemMessage` / `sendMessage(Component, UUID)` API. Previously the captured `CommandSourceStack` was stale by the time the async worker returned, so `sendSuccess` fell back to the server console and players saw nothing in chat. Applies to all 8 cells (Fabric + Forge/NeoForge across 1.18.2, 1.19.2, 1.20.1, 1.21.1).
- **Bare-command usage instead of Brigadier `<--[HERE]`** — running `/vg`, `/vg lookup`, `/vg rollback`, `/vg restore`, `/vg purge`, or any alias (`l`, `rb`, `rs`) with no arguments now prints a compact 3-line CoreProtect-style hint (usage + examples + filter-token summary) instead of the cryptic Brigadier `Unknown or incomplete command` error.
- **`/vg status` output now reaches the player's chat**, not just the server console. Same underlying fix as the async paths — `sendSystemMessage` / `sendMessage` is used when a player is present.

### Changed

- **Inspector click radius widened from `r:0` to `r:2`** — left-clicking a block with `/vg inspect` on now returns the 5×5×5 window around the clicked block (matching CoreProtect's click semantics) instead of only exact-match rows on the clicked block, which almost always came back empty.
- **Empty result messaging** — `/vg lookup` with zero rows now prints `No results found. Try a wider radius (r:20+) or longer time (t:24h+), or check filter tokens.` `/vg rollback` and `/vg restore` returning 0 affected actions print an explosion-aware hint: explosions record their blast CENTER, so try r:20+ or move to the source of the damage.
- **Inspector toggle line clarity** — the ENABLED/disabled toggle lines now go through the theme's success/muted colors distinctly so operators can see at a glance which mode they're in.


## [1.2.6] - 2026-07-03

### Added

- **Blockstate rollback fidelity** — `WorldMutator.setBlock` on all Fabric and Forge/NeoForge cells now parses `targetMeta` block-state properties (`facing=`, `waterlogged=`, `half=`, `axis=`, etc.) so rollback and restore reapply the actual pre-mutation blockstate instead of the block's default state. Unknown properties are silently skipped so mismatched meta blobs never block rollback.
- **Position-anchored query token** — the shared `QueryParser` now recognises `p:x,y,z`, letting inspector-style callers pin a lookup to a specific block regardless of caller position. `/vg` inspector lookups across all 8 loader cells now use `p:` instead of relying on caller radius, matching CoreProtect inspector semantics.
- **Async queue introspection** — `BatchedAsyncWriteQueue.pendingSnapshot()` exposes an immutable non-draining view of still-buffered actions, backing a real `VonixGuardianAPI.queueLookup(worldId, x, y, z)` implementation for third-party mods that need the pre-flush tail.

### Changed

- **VonixGuardianAPI.queueLookup** now returns pending queue rows filtered by `(worldId, x, y, z)` instead of always returning an empty list.
- **Stale TODO cleanup** — retired stale `TODO(v1.2.0/W4-04/W4-06/A9-style)` labels in FabricEvents / ForgeEvents / NeoForgeEvents that were already resolved by shipped mixin waves (W5-08). Comment text was relabelled `HISTORY(...)` so future audits distinguish live follow-up from wave history.
- **ContainerMixin scope note** — non-chest container coverage (barrel, shulker, hopper) is served by the slot-level `AbstractContainerMenu` mixin path; the chest ContainerMixin now documents its intentional scope instead of implying missing coverage.

## [1.2.5] - 2026-07-02

### Added

- **Fabric world-event parity mixins** — wired FireBlock, IceBlock, LeavesBlock, SpreadingSnowyDirtBlock, ConcretePowderBlock, and DispenserBlock mixins across 1.18.2, 1.19.2, 1.20.1, and 1.21.1 Fabric cells so BURN/IGNITE/FADE/FORM/SPREAD/LEAVES_DECAY/DISPENSE producers are no longer Forge-only.
- **Bounded rollback controls** — rollback/restore now support scan and mutation caps, cancellation, and progress callbacks; `/vg rollback` and `/vg restore` surface live progress/cap/cancel state instead of silently materializing unbounded result sets.
- **NeoForge 1.21.1 live smoke coverage** — the dev run classpath now includes shaded runtime libraries so `:mc-1.21.1:neoforge:runServer` can be used as a real boot smoke for the restored mixin config.

### Fixed

- **Rollback expansion reachability** — rollback planning now treats engine-supported world expansion actions (burn, ignite, fade, form, spread, leaves decay, buckets, hopper moves, structure grow, portals, entity block changes) as rollbackable while leaving audit-only/unsafe actions skipped.
- **Explosion rollback fidelity** — Fabric, Forge, and NeoForge explosion producers now store affected blocks as rollback-restorable `x:y:z=block` entries instead of audit-only coordinates, matching the core rollback parser.
- **Container snapshot bounds** — Fabric, Forge, and NeoForge container diff snapshots now have TTL, global cap, and slot cap cleanup to avoid retaining full modded mega-container copies indefinitely.
- **Entity interaction parity** — Fabric `UseEntityCallback` and Forge/NeoForge `EntityInteract` now emit audit-only `ENTITY_INTERACT` rows behind `actions.logInteractions`, aligning right-click entity behavior across loaders.
- **Hanging entity rollback fidelity** — rollback/restore now remove or respawn hanging entities for place/break actions through a loader `WorldMutator.removeEntity` implementation instead of treating them as audit-only.
- **Modded entity attribution policy** — player-attributed modded entity block changes now still require the entity source allowlist unless `entityChangeLogAllEntities` is enabled, preserving fail-closed behavior across Fabric and Forge/NeoForge.
- **WorldEdit rollback context preservation** — rollback/restore filter normalization now preserves the WorldEdit player context used by `r:#we` selections.
- **Entity block coalescer hard cap** — high-cardinality entity block changes now fail closed at the configured cap and report `capDrops` in `/vg status`, avoiding heap growth under modded flood conditions.

## [1.2.4] - 2026-07-02

### Fixed

- **Manual purge retention semantics** — `/vg purge t:<age>` now deletes rows **older than** the requested age, matching CoreProtect retention behavior. Previously the shared lookup filter was passed through as `sinceMillis`, which could target newer rows instead of expired history. Added regression coverage for old/new row preservation and too-recent/missing time bounds.
- **Rollback/restore safety guard** — rollback and restore planning now require an explicit time filter before querying or mutating, reducing the risk of accidental unbounded world changes.
- **Lookup page bounds** — `/vg lookup <page>` now clamps out-of-range page requests to the real last page across all 8 loader cells, avoiding empty/stale-looking results from bad offsets.
- **Public API version test drift** — API version tests now assert against the source version constant instead of a stale literal.

### Optimized

- Precomputed `EventGate` action-type enablement instead of switching per submitted event.
- Reduced unnecessary lowercasing in blacklist matching hot paths.
- Cached common SQL placeholder strings in `QueryCompiler`.

## [1.2.3] - 2026-07-02

### Fixed

- **Berk/HTTYD ENTITY_CHANGE_BLOCK flood** — Forge/NeoForge `LivingDestroyBlockEvent` is a prospective permission-check event, not Bukkit/CoreProtect's real `EntityChangeBlockEvent`. v1.2.1 recorded the default vanilla griefer allowlist and on Isle of Berk produced 5.4M queued `ENTITY_CHANGE_BLOCK` actions with 4.1M drops before shutdown. Runtime policy is now fail-closed by default: this event records only entities explicitly listed in `actions.entityChangeAllowlist` or when `actions.entityChangeLogAllEntities=true`. This preserves server performance and stops missed writes caused by queue saturation.

## [1.2.2] - 2026-07-02

### Fixed

- **NeoForge 1.21.1 boot crash — `MixinInitialisationError`** (found during v1.2.1 fleet deploy on Stone Block 4 and Better MC 5). v1.2.1's `mods.toml` declared `[[mixins]] config = "vg.mixins.json"` on all 4 Forge/NeoForge cells, but the SpongePowered Mixin Gradle plugin was never wired into the build, so no refmap was generated. NeoForge 1.21.1 hard-crashes at bootstrap with `The specified resource 'vg.mixins.json' was invalid or could not be read` because the mixin config references classes it can't resolve without a refmap. Forge 1.18-1.20 have looser mixin validation so they booted OK — but their mixin classes weren't actually running either. Removed the `[[mixins]]` declaration and the `vg.mixins.json` config from all 4 cells to restore pre-W5-01 boot behaviour. Real mixin support returns in a future release once the Mixin gradle plugin is wired end-to-end with proper refmap generation.

## [1.2.1] - 2026-07-02

Companion release to the internal 1.2.0 build. **Version bumped so operators
who already deployed the earlier 1.2.0 jar (e.g. FTB Architects @ 02:39 UTC)
don't hit a "same version, different bytes" collision** — see
`docs/CHANGES-SINCE-1.2.0-INTERNAL.md` for the exact delta.

Everything below was already in the intended v1.2.0 scope; the tag was
re-cut as 1.2.1 to preserve SemVer + Keep-a-Changelog integrity with the
previously-shipped `vonixguardian-forge-1.20.1-1.2.0.jar` (20,246,097 bytes,
mtime `2026-07-02T02:39:06Z`).

### Added since the internal 1.2.0 jar

- **Forge / NeoForge world-event mixins** (W5-01) — 5 classes × 4 cells
  (FireBlock, IceBlock, SpreadingSnowyDirtBlock, LeavesBlock, DispenserBlock).
  The internal 1.2.0 jar declared the `submitBurn/Ignite/Fade/Form/Spread/Dispense/LeavesDecay` API but had zero handlers firing them.
- **NeoForge 1.21.1 bucket mixins** (W5-02) — `BucketItemMixin`, `MilkBucketItemMixin`. Restores CP-parity bucket logging after NeoForge removed `FillBucketEvent` upstream.
- **Fabric event mixins P0+P1** (W5-08) — 10 types × 4 cells = 40 mixin classes: `BlockPlace`, `LivingDestroyBlock`, `Explosion`, `SignChange`, `BucketItem`, `Piston`, `Container` (chest), `ItemToss`, `ItemPickup`, `CraftItem`.
- **CoreProtect-parity public API** (W5-03) — `itemLookup`, `inventoryLookup`, `sessionLookup`, `usernameLookup`, `signLookup`, `queueLookup`, `logChat`, `logCommand`, `logInteraction`, `logPlacement`, `logRemoval` + 5 new typed result records.
- **WorldEdit selection bridge** (W5-04) — `r:#worldedit` / `r:#we` now actually reads the caller's selection via reflection-only soft-dep.
- **`#optimize` flag** (W5-05) — MySQL `OPTIMIZE TABLE`, PostgreSQL `VACUUM ANALYZE`, SQLite `VACUUM`. Regression-tested per dialect.
- **14 CoreProtect-parity language bundles** (W5-06) — en, de, es, fr, ja, ko, pl, ru, tr, tt, uk, vi, zh_cn, zh_tw. Selectable via `config.language`.
- **13 additional per-event config toggles** (W5-07) — natural breaks, tree/mushroom/vine/sculk growth, portals, water/lava flow, fire extinguish, campfire start, hopper meta filter, duplicate suppression, cancelled chat.
- **Salt reuse is now fail-closed** (public-release polish) — `GuardianConfig.validate()` hard-errors on `privacy.hashIps=true` + default placeholder salt. Previously only WARNed.
- **`SECURITY.md`**, **`CONTRIBUTING.md`**, refreshed **`README.md`**.

### Fixed

- **CI `build.yml` was using `-PbuildProfile=core`**, a profile that did not exist in `settings.gradle`. Silent no-op that happened to produce a correct build only because `:core` is always included. Fixed to `-PbuildProfile=coreonly` and added an explicit `coreonly` profile with `core` as a backwards-compat alias.

### What was already in the internal 1.2.0 jar (2026-07-02T02:39:06Z)

All these were present before the bump:

- CP 1:1 command surface: `/vg teleport`, `/vg tp`, `/vg give`, `/vg config get/set`, `/vg migrate-db`, refreshed `/vg reload`, real `/vg undo`, full `/vg status`.
- `PreLogEvent` native bridges on all 8 loader cells.
- Fabric native event parity (hanging place/break, async inspector left-click).
- English i18n foundation + `Messages` lookup.
- Fabric + Forge + NeoForge mixin infrastructure (`vg.mixins.json` + `mods.toml`).
- Maven Central publishing config + `docs/PLUGINS.md` + `docs/API.md` refresh.
- Command audit doc + CoreProtect comparison refresh.

## [1.2.0] - 2026-07-02 (internal / superseded by 1.2.1)

### Added

- **`/vg teleport <world> <x> [y] <z>` (alias `/vg tp`)** and **`/vg give <itemId> [amount]`** — full CoreProtect 1:1 command parity across all 8 loader cells. Both mirror the shape of `net.coreprotect.command.TeleportCommand` / `GiveCommand` from the CP v24.0 source. New permission nodes `vonixguardian.command.teleport` (default op) and `vonixguardian.command.give` (default false).
- **`/vg config get <key>`** and **`/vg config set <key> <value>`** — key-level runtime hot-swap for whitelisted config keys (theme, log level, purge floors, action toggles, entity-block coalescer, page size / max radius / max results, IP hashing toggle, etc.). Persists via `ConfigLoader.save(...)` and applies via `Guardian.reloadConfig(...)`.
- **CoreProtect-parity public API** on `network.vonix.guardian.core.api.VonixGuardianAPI` (W5-03): `itemLookup`, `inventoryLookup`, `sessionLookup`, `usernameLookup`, `signLookup`, `queueLookup` for query surface; `logChat`, `logCommand`, `logInteraction`, `logPlacement`, `logRemoval` for direct-write surface. Ships 5 new typed result records: `ItemLookupResult`, `InventoryLookupResult`, `SessionLookupResult`, `UsernameLookupResult`, `SignLookupResult`.
- **WorldEdit selection bridge** (`r:#worldedit` / `r:#we`) — reflection-only soft-dep resolver `WorldEditRegionResolver`. Console callers get `query.error.radius_no_position`; players without a selection get `query.error.worldedit_no_selection`; missing WorldEdit yields zero rows rather than crashing.
- **`#optimize` hashtag flag** — now actually executes: MySQL runs `OPTIMIZE TABLE vg_actions, vg_rollback_batches, vg_rollback_batch_actions`; PostgreSQL runs `VACUUM ANALYZE` per table in autocommit; SQLite runs whole-DB `VACUUM`. Regression tests pin every dialect path.
- **Forge / NeoForge world-event mixins** (W5-01) — 5 mixin classes × 4 cells (1.18.2, 1.19.2, 1.20.1 Forge and 1.21.1 NeoForge) covering `FireBlock` (burn + ignite), `IceBlock` (fade), `SpreadingSnowyDirtBlock` (grass / mycelium spread), `LeavesBlock` (persistent-false decay), `DispenserBlock` (dispense). Closes the long-standing "declared in `EventSubmitter` but zero handlers fire" gap.
- **NeoForge 1.21.1 bucket mixins** (W5-02) — `BucketItemMixin` and `MilkBucketItemMixin`, restoring CP-parity bucket fill/empty logging after NeoForge upstream removed `FillBucketEvent`.
- **Fabric event coverage via mixins** (W5-08) — 10 mixin types × 4 Fabric cells = 40 classes, plus 4 `FabricMixinBridge` helpers. P0: `BlockPlace`, `LivingDestroyBlock`, `Explosion`, `SignChange`, `BucketItem`. P1: `Piston`, `Container` (chest open/close), `ItemToss`, `ItemPickup`, `CraftItem`. All mixins swallow throwables so a logging failure can never poison a world tick.
- **Forge / NeoForge / Fabric mixin infrastructure** (W4-04, W4-05) — `vg.mixins.json` scaffolding + `mods.toml` declarations across all 8 cells, so future waves can drop mixin classes into pre-wired packages with no build-file churn.
- **Native `PreLogEvent` bridges** on all 8 loader cells (W4-09) — third-party mods can cancel or annotate audit entries via their platform's native event bus (`MinecraftForge.EVENT_BUS`, `NeoForge.EVENT_BUS`, Fabric `Event<T>` callback). Bridges into the core `PreLogDispatcher`.
- **14 CoreProtect-parity language bundles** (W5-06) — ported `en, de, es, fr, ja, ko, pl, ru, tr, tt, uk, vi, zh_cn, zh_tw` from CP's `lang/*.yml`. Selectable via new `config.language` field (validated against `KNOWN_LANGUAGES`; defaults to `en_us`). `Messages.setLanguage(...)` swaps the active bundle; unknown / missing keys silently fall back to `en_us`.
- **English message-key foundation** (W4-12) — `network.vonix.guardian.core.i18n.Messages` central lookup + `lang/en_us.properties` covering the entire `QueryParser` player-error surface + `/vg status` section headers. All `QueryParser` player errors now route through `Messages.get(...)`.
- **13 additional per-event config toggles** on `GuardianConfig.Actions` (W5-07) — CP-parity granular knobs for natural breaks, tree/mushroom/vine/sculk growth, portals, water/lava flow, fire extinguish, campfire start, hopper meta filter, duplicate suppression, cancelled chat. Backward-compat via `@Deprecated` 18-arg positional constructor + `ConfigLoader.migrateForwardCompat()` heuristic backfill.
- **Fabric event wiring parity via native API** (W4-03) — `AttackEntityCallback` → HANGING_BREAK, `ServerEntityEvents.ENTITY_LOAD` → HANGING_PLACE, async left-click inspector lookup on all 4 Fabric cells. Every gap Fabric API cannot cover natively is now enumerated as a single `TODO(v1.2.0): mixin` block in each cell's `register()` — the audit trail is honest about what needs a mixin (now delivered by W5-08).
- **Ecosystem publishing config** (W4-14) — Maven Central staging URL + POM enrichment + `withJavadocJar()` + `signing` block (GPG-driven, only required when publishing to Central). GitHub Packages publishing preserved unchanged. Full `docs/PLUGINS.md` authoring guide for third-party mods (soft-dep Gradle coordinates, PreLogEvent hook example, PermissionNode reuse, EventSubmitter façade). `docs/API.md` gains a `Public class index` section + CI Javadoc site link.
- **`docs/COREPROTECT-GAP-INVENTORY.md`** — exhaustive gap audit against CoreProtect v24.0 local source (`/root/staging/coreprotect-ref/CoreProtect`). Source-verified, not docs-derived.
- **`docs/COMMAND-AUDIT-1.2.0.md`** — source-traced audit of every `/vg` subcommand + registration lines + permission nodes + handler methods + core service paths.
- **`SECURITY.md`** — disclosure policy, salt-hashing notes, security scope.
- **`CONTRIBUTING.md`** — contributor guide, development workflow, license notes.

### Changed

- **Salt reuse is now fail-closed.** `GuardianConfig.validate()` now refuses to boot when `privacy.hashIps=true` AND `privacy.salt` is still the shipped placeholder `"vonix-guardian-default-salt-CHANGE-ME"`. Prior versions emitted a WARN; v1.2.0 hard-errors so a public-release operator cannot accidentally ship trivially-reversible IP hashes. Operators who don't want hashing: set `privacy.hashIps=false` and the salt is ignored.
- **`docs/COREPROTECT-COMPARISON.md`** refreshed to reflect the v1.2.0 snapshot. 18 wired command/alias entries, 0 stubs, 0 missing CoreProtect command surfaces. Remaining gaps are runtime/API polish, not command surface.
- **`README.md`** refreshed from stale v0.1.0 feature list to the full v1.2.0 command surface, filter mini-language, storage backends, permissions, public API, extensibility, i18n, performance, diagnostics, and compat notes.

### Fixed

- **CI `build.yml` was using `-PbuildProfile=core`, a profile that did not exist in `settings.gradle`.** Silent no-op that happened to produce a correct build only because `:core` is always included. Fixed to `-PbuildProfile=coreonly` and added an explicit `coreonly` profile with `core` as a backwards-compat alias.

### Deferred to v1.2.1

- Fabric P2 vanilla block-tick mixins (`FireBurn/Ignite`, `IceFade`, `Dispense`, `LeavesDecay`, `BlockForm/Spread`) — documented in each cell's `FabricEvents.java`.
- Barrel / shulker / hopper container snapshots (`ContainerMixin` currently covers `ChestBlockEntity` only).
- Non-player hanging break paths (arrow / explosion / mob).
- `queueLookup` deep snapshot — currently returns empty list with TODO; `BatchedAsyncWriteQueue` needs a `pendingSnapshot()` accessor.


## [1.1.8] - 2026-07-01

### Fixed

- **Diagnostic log spam eliminated** (`BatchedAsyncWriteQueue`). The v1.1.3-diag
  histogram fired unconditionally every 30s at `WARN` level, producing hundreds
  of lines per hour on busy servers (operator report from Linggango deploy of
  1.1.5). Now:
  - Emits at `WARN` only when there are dropped rows (`droppedTotal > 0`).
  - Emits at `WARN` when queue depth is materially non-empty (> 25% of
    capacity).
  - Otherwise silent, unless the operator drops the logger to `DEBUG`
    (`network.vonix.guardian.core.queue`).
  - Zero-drops steady-state is completely quiet — matching CoreProtect's
    posture that diagnostics are on-demand (`/vg status`), not spam.

### Added

- **`/vg status` is now a full multi-line diagnostic**, matching CoreProtect's
  `/co status` contract. Single on-demand surface for every subsystem — no
  new subcommand tree, no always-on logs.
  - New `core.diagnostics.GuardianStatus.render(Guardian)` returns an ordered
    `List<String>` of report lines. Section headers begin with `§ ` and are
    rendered bold-accent-colored by cells; body lines primary.
  - Sections: VonixGuardian version · Storage (backend / schema / health) ·
    Writer queue (consumer state, depth, submitted, gated, dropped, per-type
    histogram) · Coalescer (window / tracked / active) · Event hooks
    (registered chain in order) · Per-world overrides (count + list) ·
    Blacklist file (rule count) · Auto-purge (state + rows purged since
    restart) · Permissions (default-op + per-node overrides) · Public API
    (version).
  - Every subsystem accessor wrapped in `try/catch` in the renderer — a
    broken subsystem yields a single `(err: XxxException)` line, never
    aborts the whole status.
  - Wired into all 8 cells' `Status.run` with `ChatRenderer.section()`
    (new helper: bold + secondary color).
- `BatchedAsyncWriteQueue.submittedByTypeSnapshot()` /
  `droppedByTypeSnapshot()` — public, thread-safe, allocation-per-call
  snapshots of the per-`ActionType` histograms. Consumed by
  `GuardianStatus.render()`.
- `Guardian.gate()` / `Guardian.entityBlockCoalescer()` — new public
  accessors so diagnostics can read the live `EventGate` hook chain and the
  coalescer's state.
- `ChatRenderer.section(Theme, String)` in all 8 cells — bold + secondary
  color renderer for `/vg status` section headers.


### Added

- **W3-B12+B13 — Public typed Java API surface.** New
  `network.vonix.guardian.core.api` package exposes a stable, third-party-friendly
  handle documented in `docs/API.md` § "Public Java API (v1)" and flagged
  in `docs/COREPROTECT-COMPARISON.md` (CoreProtect parity gap).
  - `VonixGuardianAPI` interface — `apiVersion()` (returns `1`),
    `pluginVersion()` (`"1.1.7"`), `testAPI()` (wiring smoke test),
    `hasPlaced(UUID,String,int,int,int,long)`,
    `hasRemoved(UUID,String,int,int,int,long)`,
    `blockLookup(...)`, `containerLookup(...)`,
    `chatLookup(UUID,long,int)`, `commandLookup(UUID,long,int)`.
  - Per-family typed result records: `BlockLookupResult`,
    `ContainerLookupResult`, `MessageLookupResult` — each carries typed
    time / actor / world / xyz / target fields plus per-family specifics
    (e.g. `ContainerLookupResult#amountDelta` is signed by direction:
    positive = deposit, negative = withdraw).
  - `GuardianAPI` — default impl backed by `Guardian.dao().query(...)`.
  - `Guardian.api()` — latched `AtomicReference<GuardianAPI>` so the impl
    is constructed lazily and cached for the lifetime of the instance.
  - `GuardianDao.hasActionsInWindow(UUID user, String worldId, int x,
    int y, int z, ActionType[] types, long withinMillis)` — new indexed
    existence probe. Implementation in `AbstractJdbcDao` uses a
    `SELECT 1 ... LIMIT 1` on the `vg_actions_pos` index; a `null` or
    empty `types` array matches ANY action type, and a non-positive
    `withinMillis` disables the temporal bound.
  - Coverage: `VonixGuardianAPITest` (mocked `GuardianDao`, verifies each
    API method calls the right DAO shape) and
    `HasActionsInWindowIntegrationTest` (real in-memory SQLite; asserts
    inside/outside window, wrong coord/user/type, `null` types matches
    any, negative window disables temporal bound).
  - Docs: new "Public Java API (v1)" section in `docs/API.md` with Maven
    coord `network.vonix.guardian:vonixguardian-core:1.1.7` (published by
    B14), the reflection soft-dep wire-up pattern mirroring
    `LuckPermsBridge`, method summary table, result-record shapes, and
    the versioning contract on `apiVersion()`.
- **W3-B6 — CoreProtect-compatible `blacklist.txt` file.** VonixGuardian now
  reads a per-server `config/vonixguardian/blacklist.txt` at boot and
  short-circuits logging for any action matching one of its rules. Fills the
  gap flagged in `docs/COREPROTECT-COMPARISON.md` and `NIGHTSHIFT.md` B6 —
  previously the engine only honoured the three coarse JSON arrays
  (`worldBlacklist`, `blockBlacklist`, `sourceBlacklist`) and could not
  express per-user, per-command, or composite rules.
  - Grammar mirrors CoreProtect: `user:<name>`, `user_uuid:<uuid>`,
    `command:<name>`, `block:<namespaced_id>`, `entity:<namespaced_id>`,
    and the composite form `<id>@<user>` ("drop when player X touches
    block/entity Y").
  - `#` starts a comment; blank lines OK; unknown rule kinds log WARN and
    are skipped without aborting boot.
  - Rule matching is O(1) per action (hash-set lookups) and case-insensitive
    for user names, command names, and target ids.
  - New classes: `blacklist.BlacklistFile` (parser),
    `blacklist.BlacklistMatcher` (precompiled lookup),
    `event.BlacklistFileHook` (`EventHook` adapter returning DENY/PASS).
  - `Guardian.boot()` registers the hook automatically when the file is
    present; silently skips otherwise.
  - `/vg reload` now also re-parses `blacklist.txt` and rebuilds the hook,
    reporting the new rule count under the hot-swapped list
    (`blacklist.txt (N rules)`).
  - Coverage: `BlacklistFileTest`, `BlacklistMatcherTest`,
    `BlacklistFileHookTest`.
- **W3-B7 — CoreProtect-compatible child permission nodes + silent result
  filter.** Closes the granular-permissions gap flagged in
  `docs/COREPROTECT-COMPARISON.md` and `NIGHTSHIFT.md` B7. Admins can now
  hand out scoped access (e.g. "can lookup blocks but not chat", "can
  rollback containers but not blocks") using LuckPerms-friendly nodes that
  mirror CoreProtect's own naming.
  - `core.perms.PermissionNode` gains 12 new child constants:
    `LOOKUP_BLOCK`, `LOOKUP_CONTAINER`, `LOOKUP_ITEM`, `LOOKUP_KILL`,
    `LOOKUP_SESSION`, `LOOKUP_SIGN` (opLevel 2);
    `ROLLBACK_BLOCK`, `ROLLBACK_CONTAINER`, `ROLLBACK_ITEM`,
    `RESTORE_BLOCK`, `RESTORE_CONTAINER`, `RESTORE_ITEM` (opLevel 3). All
    strings follow `vonixguardian.<family>.<child>` so
    `vonixguardian.lookup.*` LuckPerms wildcards work naturally.
  - `PermissionNode.childFor(family, category)` and
    `PermissionNode.childForAction(family, actionType)` dispatch a raw
    action to its tightest applicable child, or fall-open to the family
    node when no per-category scoping is defined (e.g. rollback-of-chat).
  - New `core.perms.CommandGate` utility centralises the LP-first +
    op-level-fallback check so cells no longer hand-write raw permission
    strings for family-level gating.
  - New `core.perms.LookupPermissionFilter` runs every lookup-result row
    through `childForAction` and silently drops the ones the source can't
    see — the CoreProtect contract of *"show only what you're allowed to
    inspect"*, applied automatically inside `Lookup.runWithFilter` in all
    8 loader cells (1.18.2 forge/fabric, 1.19.2 forge/fabric, 1.20.1
    forge/fabric, 1.21.1 fabric/neoforge).
  - Regression coverage:
    `core/src/test/java/network/vonix/guardian/core/perms/PermissionNodeChildTest.java`
    (10 tests — uniqueness, opLevel bounds, family/category dispatch,
    fall-open, null rejection) and
    `LookupPermissionFilterTest.java` (6 tests — console bypass,
    grant-all, deny-all, rollback fall-open on messages, empty in/out,
    null-arg rejection).
- **W3-B8: per-node op-level fallback for granular permission gating when
  LuckPerms is absent.** Previously VonixGuardian fell back to a single
  coarse `permissions.defaultOpLevel` regardless of which `PermissionNode`
  a subcommand required — so once LuckPerms was out of the picture,
  `/vg purge` and `/vg lookup` were gated identically. Closes the
  CoreProtect-parity gap called out in `NIGHTSHIFT.md` B8 and
  `COREPROTECT-COMPARISON.md`.
  - New `PermissionResolver.has(UUID, PermissionNode)` overload consults
    LuckPerms first (unchanged), and on `UNDEFINED`/absent LP falls back
    to `perNodeOpLevel(node)` — which honors `PermissionNode.defaultOpLevel()`
    plus any operator override.
  - New `PermissionResolver.perNodeOpLevel(PermissionNode)` reads
    `GuardianConfig.Permissions.perNodeOpLevels`; out-of-range or unknown
    entries are ignored with a WARN and the node's default is used.
  - New optional `permissions.perNodeOpLevels: Map<String,Integer>` config
    field. Keys must be known `PermissionNode.node()` strings; values 0..4.
    Unknown keys → WARN and skip. `null` is tolerated and treated as an
    empty map.
  - `ConfigLoader.migrateForwardCompat` backfills `perNodeOpLevels = {}` on
    pre-W3-B8 configs (same forward-compat treatment as W2-06's schema
    migration).
  - Legacy `PermissionResolver.has(UUID, String)` still works, but now
    emits a throttled one-shot WARN per known node string, nudging callers
    to migrate to the enum-based overload so per-node overrides are
    honored.
  - Regression coverage: `PermissionResolverPerNodeTest`,
    `PermsPerNodeOverrideTest`. Existing string-based tests unchanged.

### Fixed

- **`/vg reload` now actually reloads** (Wave-3 B1). Previously the command
  in every one of the 8 loader cells printed *"not implemented yet — restart
  the server to re-read config"* — a CoreProtect-parity gap flagged in
  `docs/COREPROTECT-COMPARISON.md` § 1.2 and `NIGHTSHIFT.md` B1.
  - New `Guardian.reloadConfig(Path)` re-reads `config.json` via
    `ConfigLoader.load`, hot-swaps the safe subset in-flight, and reports
    what would need a server restart.
  - Hot-swap safe: `actions.*` (toggles, blacklists, coalescer knobs,
    entity allowlist), `logFile.enabled`, `lookup.defaultPageSize` /
    `maxRadius` / `maxResultRows`, `privacy.hashIps`, `purge.*`, `theme`.
  - Restart required (change detected but held back): `database.*`,
    `queue.*`, `logFile.directory` / `gzipRotated` / `retentionDays`,
    `permissions.*`, `lookup.maxConcurrent`, `privacy.salt`.
  - `Guardian.ReloadResult(hotSwapped, requiresRestart, errors)` is an
    immutable record; the `Reload.run` command handler in every cell prints
    a 3-line color-formatted summary. Regression coverage in
    `core/src/test/java/network/vonix/guardian/core/GuardianReloadTest.java`.

- **W3-B2 — `/vg undo` now actually reverts world state.** Previously the
  command just popped `UndoStack` and printed `"dropped N entries from
  history"`; the world stayed in whatever state the previous
  rollback/restore left it. `Undo.run` in all 8 cells' `GuardianCommands`
  now dispatches asynchronously and calls
  `RollbackEngine.execute(plan(popped.originalFilter(), popped.inverseMode(), actor), false)`
  — running the inverse of the previous operation on exactly the same
  action set (rollback→restore, restore→rollback), CoreProtect-parity.
  The undo result is deliberately **not** re-pushed onto `UndoStack` so
  repeated `/vg undo` invocations cannot ping-pong indefinitely. Legacy
  pre-v1.1.6 stack entries (no `originalFilter`) fall back to the old
  history-only message with an explicit warning that world state was not
  reverted. New `UndoRevertTest` locks in the round-trip: rollback flags
  `markRolledBack(ids, true)`, `/vg undo` (inverse plan) flags
  `markRolledBack(ids, false)`, and the per-actor `UndoStack` cap
  remains at 20.

### Added

- **W3-B15 — Schema v3→v4 migration + CoreProtect-v24 sign metadata wire.**
  CoreProtect's 1.20+ sign lookup returns front/back side, dye color and the
  waxed flag; VG was collapsing every SIGN row into a single joined-lines
  string in `target` and dropping the rest on the floor
  (`docs/COREPROTECT-COMPARISON.md`, `NIGHTSHIFT.md` B15).
  - New migration
    `network.vonix.guardian.core.storage.migration.V4SignMetadata` adds three
    nullable columns to `vg_actions`:
    `sign_side VARCHAR(8)`, `sign_dye_color VARCHAR(16)`, `sign_waxed BOOLEAN`.
    All three dialects use `ALTER TABLE ... ADD COLUMN` (metadata-only, no
    table rewrite); the runner tolerates a duplicate-column error on rerun
    to survive the MySQL DDL-autocommit crash window.
  - `Schema.CURRENT_VERSION` bumped from 3 → 4; fresh-install DDL adds the
    new columns from the start.
  - New `EventSubmitter.submitSign(UUID, String, String, int, int, int,
    String, String, String, Boolean)` overload carries the side/dye/waxed
    metadata; the legacy 7-arg overload is preserved and delegates via a
    `default` method that drops the metadata (source-compat).
  - `Action` gains three trailing nullable fields (`signSide`,
    `signDyeColor`, `signWaxed`); a legacy 14-arg constructor is retained
    so every pre-v1.1.7 `new Action(...)` call site keeps compiling.
    `ActionBuilder` grows matching setters; `AbstractJdbcDao.insertBatch` +
    `readAction` and `QueryCompiler.SELECT_PROJECTION` all persist and
    materialise the new fields.
  - **Cell-side wire (all 8 cells):** each cell's `common/` package now
    ships a `SignMetadataExtractor` helper that reads dye color, waxed flag
    and (on 1.20+) front/back side straight off a `SignBlockEntity`:
    * MC 1.20.1 forge, 1.20.1 fabric, 1.21.1 neoforge, 1.21.1 fabric —
      `SignBlockEntity.getFrontText()` / `getBackText()` / `SignText.getColor()`
      / `isWaxed()`.
    * MC 1.19.2 forge/fabric, 1.18.2 forge/fabric — single-sided:
      always `side="front"`, `waxed=null`, `dyeColor` from
      `SignBlockEntity.getColor()`.
    Extraction is fully defensive — any `Throwable` short-circuits to
    `null` for the affected field so the event dispatch never crashes.
  - Regression coverage: `V4SignMetadataMigrationTest` (v3→v4 in place,
    reentrancy), `SignMetadataRoundTripTest` (front + back sides with dye
    + waxed), `LegacySignSubmitTest` (legacy 7-arg overload persists NULL
    metadata and the interface's default 10-arg method routes back through
    the legacy impl).
- **`core/build.gradle`** — `publishing {}` block with a `maven(MavenPublication)` from `components.java` (POM: name, description, url, MIT license, developer, SCM) and a `GitHubPackages` remote (`https://maven.pkg.github.com/Vonix-Network/VonixGuardian`, credentials via `GITHUB_ACTOR` / `GITHUB_TOKEN` env vars). Artifact coord: `network.vonix.guardian:vonixguardian-core:1.1.7`.
- **`docs/API.md`** — new **§1a "Using in Gradle"** section covering the Maven coordinate, `mavenLocal()` and `GitHubPackages` repository snippets, the `compileOnly(...) { transitive = false }` pattern (so consumers don't pull sqlite/hikaricp/gson onto their compile classpath), and a bootstrap example that resolves via the Maven coord instead of a locally-built jar.


- **`GuardianDao#optimize(long)` + `PurgeEngine` `#optimize` handler (W3-B10).**
  The `#optimize` hashtag on `/vg purge` (already parsed by `QueryParser`) now
  dispatches to real storage-optimization SQL after a successful purge:
  - **MySQL / MariaDB** — `OPTIMIZE TABLE vg_actions, vg_rollback_batches, vg_rollback_batch_actions`.
  - **PostgreSQL** — `VACUUM ANALYZE` per table, forced into autocommit
    (VACUUM cannot run inside a transaction).
  - **SQLite** — whole-db `VACUUM` (SQLite auto-optimizes; VACUUM reclaims
    pages freed by the delete).
  Runtime is capped at **5 minutes** via per-statement `setQueryTimeout`; if
  the cap trips or a statement errors, the purge's `deletedCount` is still
  returned and the failure is logged as `WARN` — optimize is opportunistic and
  MUST NOT undo a successful purge. `PurgeEngine.PurgeResult` now exposes the
  optional `OptimizeResult` (duration ms, best-effort bytes reclaimed from
  `information_schema.tables` / `pg_total_relation_size` / `pragma_page_count`).
  CoreProtect parity per NIGHTSHIFT.md B10.

- **Auto-purge daemon** (W3-B4, CoreProtect Patreon 24+ parity):
  new `AutoPurgeScheduler` runs a single background daemon thread that wakes
  daily at the operator-configured `HH:mm` (server local time) and deletes
  `vg_actions` rows older than `purge.autoPurgeSeconds`. Chunked
  (`DELETE ... LIMIT 10_000`, 200 ms pause between chunks) and mutex-safe:
  uses `PurgeEngine.mutex()` via `tryLock`, so a manual `/vg purge` or an
  in-flight migration cleanly skips the scheduled run and reschedules for
  tomorrow. New config keys: `purge.autoPurgeSeconds` (`0` = disabled, else
  ≥ 30d = 2 592 000 s) and `purge.autoPurgeTime` (`HH:mm` 24h, default
  `03:30`). New DAO primitive: `GuardianDao.purgeOlderThan(cutoffMs, limit)`.
  Wired into `Guardian.boot()` (start) and `Guardian.shutdown()`
  (5-second graceful await). Rows deleted since JVM restart are exposed via
  `AutoPurgeScheduler.getRowsPurgedSinceRestart()` for a follow-up
  `/vg status` line in cells (TODO: parent ticket, cells layer).

- **Backend-migration pipeline** (`network.vonix.guardian.core.storage.dbmigrate`):
  new `BackendMigrationJob`, `TableCopier`, `MigrateDbCommand`, `ProgressUpdate`,
  `RawJdbcAccess` types implementing CoreProtect Patreon's `/co migrate-db`
  contract for VonixGuardian. Chunked (default 1000 rows/chunk) copy of every
  table (`vg_worlds`, `vg_users`, `vg_actions`, `vg_rollback_batches`,
  `vg_rollback_batch_actions`) from the running source DAO to a freshly-opened
  destination DAO. Ids are preserved verbatim so cross-table FK-like references
  (e.g. `vg_actions.user_id -> vg_users.id`) survive the copy. On MySQL and
  PostgreSQL the copier bumps the identity sequence past `max(id)` after each
  table so subsequent writes on the destination don't collide with copied ids.
  Emits `ProgressUpdate(table, rowsCopied, rowsTotal, elapsedMs)` every 1000
  rows OR every 5 seconds, whichever comes first.
  - Idempotency: the destination must be at
    `Schema.CURRENT_VERSION` and must be **empty** (row-count 0 across every
    data-carrying table). Non-empty destinations are refused unless a `force`
    flag is passed at the API level. This matches CoreProtect's model of a
    one-shot copy into a freshly-init'd database.
- **`/vg migrate-db <sqlite|mysql|postgresql> CONFIRM`** subcommand in all 8
  loader cells (`mc-1.18.2/{fabric,forge}`, `mc-1.19.2/{fabric,forge}`,
  `mc-1.20.1/{fabric,forge}`, `mc-1.21.1/fabric`, `mc-1.21.1/neoforge`).
  Console-only — invoking it as a player yields an error message and no work.
  Requires the operator to append the literal `CONFIRM` as a second argument
  to actually run (mirrors CoreProtect's safety pattern). Reads the target
  connection block from a new optional `config.database.migrationTarget`
  sub-record; if that block is absent, missing-fields, or its `type` disagrees
  with the CLI argument, the command refuses. Progress is streamed back to the
  console one table at a time.
- **`GuardianConfig.Database.migrationTarget`** — new nullable `MigrationTarget`
  sub-record on the `Database` block. Validator enforces the same
  {type, file, jdbcUrl, user, password} shape as the top-level backend
  descriptor. Explicitly **not** hot-swappable — the running server keeps its
  active backend until `/vg migrate-db` is issued. A backward-compat
  five-argument constructor on `Database` preserves the pre-1.2.0 record
  shape used by existing tests and callers.
- **`RawJdbcAccess`** interface implemented by `AbstractJdbcDao`, granting
  `BackendMigrationJob` scoped raw-`Connection` access without leaking the
  connection through the public `GuardianDao` contract.
- **`BackendMigrationJobTest`** — end-to-end test that seeds an in-memory
  SQLite source with 10 000 actions across every ActionType category (BLOCK_*,
  CONTAINER_*, CHAT, COMMAND), plus a `vg_rollback_batches` audit row, runs
  the migration into a fresh SQLite destination, and asserts row-for-row
  parity on `vg_actions` (`id`, `ts`, `type`, `target`, `actor_uuid`,
  `actor_name`, `world`, `x/y/z`, `amount`, `rolled_back`, `source_tag`) plus
  identity preservation on `resolve_user` and `resolve_world`. Also verifies
  the non-empty-destination refusal path and the `force`-flag bypass.

### Changed

- **`gradle.properties`** — `mod_version` bumped `1.1.6` → `1.1.7`.

### Verified

- `./gradlew -PbuildProfile=coreonly :core:publishToMavenLocal` → **BUILD SUCCESSFUL**. Installs `vonixguardian-core-1.1.7.jar`, `-sources.jar`, `.module`, `.pom` to `~/.m2/repository/network/vonix/guardian/vonixguardian-core/1.1.7/`.
- `./gradlew -PbuildProfile=coreonly :core:build` → **BUILD SUCCESSFUL** (unchanged).

### Tests

- `AbstractJdbcDaoOptimizeSqlTest` — asserts the exact SQL emitted for each
  dialect (`OPTIMIZE TABLE ...` on MySQL, three `VACUUM ANALYZE` statements on
  Postgres with an enforced autocommit flip, and `VACUUM` on SQLite) plus a
  swallow-on-error case proving optimize failure does not surface to the purge
  caller.
- `SqliteDaoIntegrationTest#optimize_runs_vacuum_and_returns_completed_result` —
  real end-to-end VACUUM against an in-memory SQLite DB after a purge; the
  result is `completed=true` and the DB remains healthy and queryable.


## [1.1.6] — 2026-07-01

**Wave-2 nightshift: 6 parallel subagent audits + fixes.** Fixes for the CRITICAL RollbackPlan silent-default (14 handlers restored), the CRITICAL Berk truncation storm (target column widened to 4096 chars), and 5 HIGH-severity wiring/parity issues surfaced by the Wave-1 CP-comparison + adversarial audit. See docs/COREPROTECT-COMPARISON.md and docs/WAVE-AUDIT-1.1.5.md for the underlying analysis.

### Fixed

### Added

- **`RollbackPlanTest`** — regression suite proving the plan/execute split:
  `plan(...)` opens no batch, calls no mutator, calls no `markRolledBack`;
  a plan can be built, inspected, and discarded without side effects; a
  single plan can be executed twice (preview then real) independently; a
  legacy plan built without a mode is rejected by `execute(...)`; the
  result carries `originalFilter` for undo replay.

- **Versioned schema-migration harness**
  (`network.vonix.guardian.core.storage.migration`): new `Migration`
  interface, `MigrationRunner` (ordered, idempotent, dialect-aware), and
  `V3WidenActionTarget` (the v2 → v3 in-place ALTER). Runner is invoked from
  `AbstractJdbcDao.init()` after `Schema.createTables()` so both fresh and
  existing installs land at `Schema.CURRENT_VERSION` on startup.
  - MySQL/MariaDB path: `ALTER TABLE vg_actions MODIFY target VARCHAR(4096) NOT NULL`.
  - PostgreSQL path: `ALTER TABLE vg_actions ALTER COLUMN target TYPE VARCHAR(4096)`.
  - SQLite path: documented no-op (TEXT affinity; declared length is
    decorative). Still stamps the version so operators land at v3 cleanly.
- **Regression test** `SchemaTargetWidthTest`: round-trips a 512-char CHAT
  submit through `SqliteDao.insertBatch` + `query` and asserts the persisted
  `target` column contains all 512 chars.
- **Migration runner tests** `MigrationRunnerTest`: verifies advance from a
  simulated v2 install to `CURRENT_VERSION`, idempotency on re-run, and
  contiguous-chain contract on the default migration list.
- **Post-incident audit** `docs/A11-BATCH-DEGRADATION-AUDIT.md`: traces
  `BatchedAsyncWriteQueue.flushWithRetry` and rules out WeedMeister's
  "poison-row amplification storm" concern (no bisection logic exists).
  Documents the *actual* failure mode observed on Berk (silent per-row data
  loss on persistent truncation) and lists follow-up work for W2-07:
  SQLSTATE-aware retry classification, dead-letter path, and a
  `permanently_dropped_total` metric.

### Changed

- **W2-01: RollbackEngine two-phase pipeline (plan → execute).**
  `RollbackEngine.rollback(...)` and `restore(...)` no longer interleave DAO
  reads, plan construction, and executor dispatch in a single method. The
  refactor splits the workflow into `plan(filter, mode, actorUuid)` — pure,
  no side effects, returns an immutable `RollbackPlan` — and
  `execute(RollbackPlan, boolean preview)` — opens the audit batch row and
  dispatches world mutations. The old `rollback(...)` / `restore(...)`
  signatures are kept as thin wrappers so no callers break. Failures during
  planning can no longer leave a half-open `vg_rollback_batches` row
  because the batch is opened strictly inside `execute(...)`, after the
  plan is fully built.
- **`RollbackPlan` is now a tagged value object** carrying `actionIds`,
  `skippedIds`, `steps` (`PlannedStep` records), the originating
  `QueryFilter`, `RollbackResult.Mode`, and `actorUuid`. Adds
  `plannedSteps()` alongside the existing `size()` / `isEmpty()` accessors.
  Legacy `RollbackPlan.build(List<Action>)` is retained for backwards
  compatibility.
- **`RollbackResult` carries `originalFilter`** (A2) so `/vg undo` can pop
  an entry and invoke the inverse operation (`restore(filter)` after a
  rollback, `rollback(filter)` after a restore) on the exact same action
  set without reconstructing the filter from user input. Added
  `inverseMode()` helper. The pre-existing 7-arg constructor is retained
  as a delegator to the new 8-arg canonical form so pre-v1.1.6 call sites
  (including `UndoStackTest`) still compile.


- `Schema.CURRENT_VERSION` bumped from `2` to `3`.
- `Schema.createTables(...)` now only stamps `CURRENT_VERSION` when
  `vg_schema_version` is empty; on a populated older install it defers
  version-stamping to `MigrationRunner`, which stamps each step as it
  applies. Prevents the runner from mistakenly seeing a fresh-stamp on top
  of pre-existing older data.


- **Griefer whitelist consolidation** (WAVE-AUDIT-1.1.5 A3+A4):
  `stripMobPrefix` is now a single `public static` helper on
  `network.vonix.guardian.core.filter.VanillaGrieferSet` (previously duplicated
  verbatim across four cell `Events.java` files). `Guardian.submitEntityChangeBlock`
  now enforces the vanilla-griefer whitelist centrally at the core boundary; the
  cell-side check remains in place as a redundant fast-path and will be dropped
  in a follow-up wave.

### Removed

- **Dead allowlist entries** (WAVE-AUDIT-1.1.5 A5): `minecraft:wind_charge` and
  `minecraft:breeze_wind_charge` removed from `VanillaGrieferSet.DEFAULT_ALLOWLIST`.
  Both are `Projectile`, not `LivingEntity`, so they could never satisfy
  `LivingDestroyBlockEvent`'s dispatch signature — their presence was misleading
  dead code with zero runtime effect.

### Test infra

- Updated `EventGateTest` and `ConfigLoaderTest` call sites for
  `GuardianConfig.Actions` to pass the four v1.1.5-added fields
  (`entityBlockChangeCoalesceWindowMs`, `entityBlockChangeMaxTracked`,
  `entityChangeAllowlist`, `entityChangeLogAllEntities`). Pre-existing
  compilation bug in the tree that predated this ticket.

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