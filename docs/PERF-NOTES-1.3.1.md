# PERF-NOTES-1.3.1.md

Wave-by-wave notes on the shape of v1.3.1 changes that touch schema, DAO, or
hot-path allocation. Owners: each wave appends their own paragraph at wave
close; do not rewrite prior sections.

## X1 — Schema V5 NBT fidelity + producer surface

**Ships:** five nullable columns on `vg_actions`
(`old_block_state TEXT`, `new_block_state TEXT`, `block_entity_nbt BLOB`,
`item_nbt BLOB`, `entity_nbt BLOB`); a `V5NbtFidelity` migration; five NBT
setters on `ActionBuilder`; NBT-aware default overloads on `EventSubmitter`
and `WorldMutator`; `config.storage.persistNbt` (default `false`) gating
whether producers populate the NBT payload.

**Rationale — Ledger parity.** The Ledger comparison audit (2026-07) flagged
NBT fidelity as the single largest CoreProtect / Ledger parity gap in VG.
Without these columns, `/vg rollback` downgrades a named+enchanted Netherite
sword back to a generic `minecraft:netherite_sword`, drops chest contents on
BLOCK_BREAK rollback, and cannot restore custom-named tamed mobs. The v1.2.6
`applyMeta()` block-state property parser was the first step; X1 finishes the
job for waterlogged/facing state, block-entity NBT, item NBT, and entity NBT.

**Schema shape.**

| Column             | SQLite | MySQL       | PostgreSQL |
|--------------------|--------|-------------|------------|
| `old_block_state`  | TEXT   | TEXT        | TEXT       |
| `new_block_state`  | TEXT   | TEXT        | TEXT       |
| `block_entity_nbt` | BLOB   | LONGBLOB    | BYTEA      |
| `item_nbt`         | BLOB   | LONGBLOB    | BYTEA      |
| `entity_nbt`       | BLOB   | LONGBLOB    | BYTEA      |

`LONGBLOB` (up to 4 GB) rather than `MEDIUMBLOB` (16 MB cap): a single BE is
comfortably under 16 MB today, but LONGBLOB costs nothing extra on-disk and
future-proofs against very large modded NBTs (large-inventory mods, custom
BEs). `TEXT` for block-state properties: they're compact `k=v,k=v` strings
produced by `BlockStateProperties.serialize()`, never longer than a few
hundred bytes.

**NBT encoding format.** Raw bytes produced by
`net.minecraft.nbt.NbtIo.write(CompoundTag, DataOutputStream)` — the same
serialization Minecraft uses for its own on-disk NBT (chunks, level.dat).
Decoded on rollback via `NbtIo.read(...)`. Loader cells that fail to decode
should log at DEBUG and fall back to the existing default-state behavior
(never throw, never leave the world in a partial state). Sibling wave X2/X4
etc. own the actual encode/decode wiring inside each cell's WorldMutator.

**Producer-side toggle.** `config.storage.persistNbt` defaults to `false` for
two reasons:

1. **Storage cost.** A chest's BE snapshot is easily a few KB per BLOCK_BREAK
   on a well-stocked base; multiply by the audit stream and disk grows fast.
   Operators who want Ledger-parity restore fidelity opt in explicitly.
2. **Rollout safety.** The X1 gate ships to v1.3.1 with zero producers wired
   yet — cell-side capture lands in sibling waves X2/X4/X7/X9. Defaulting to
   `false` means the toggle acts as a kill-switch: cells that ship
   NBT-capture code before X-β integration lands stay silent under the
   default config.

The DAO **always reads** whatever it finds in the columns, regardless of the
toggle. So an operator who flips `persistNbt=true` for a week then back to
`false` retains the rollback fidelity of every event captured during that
week. Historical rows have `NULL` in every NBT column and continue to route
through the pre-1.3.1 rollback path.

**Additive API surface.** Every extension is a new overload with an old
overload keeping identical semantics:

- `Action` record now has a canonical 22-arg constructor plus two
  `@Deprecated`-shim compat overloads (14-arg pre-v4, 17-arg pre-v1.3.1).
- `ActionBuilder` adds `.oldBlockState(...) / .newBlockState(...) /
  .blockEntityNbt(...) / .itemNbt(...) / .entityNbt(...)`; existing chains
  still compile.
- `ActionBuilder.reset()` now clears the five new fields — critical for the
  v1.3.0 W2 per-thread scratch builder so a chest-break's BE payload does
  not leak into the next unrelated submit on the same thread.
- `EventSubmitter` adds NBT-aware default overloads for the 10 submits that
  actually carry NBT (block break/place, container change, item drop/pickup,
  entity kill/spawn, entity change block, hopper push/pull). The default
  delegates to the non-NBT overload so existing test doubles and third-party
  integrations keep compiling. `Guardian` overrides them with the
  `persistNbt` gate.
- `WorldMutator` adds NBT-aware default overloads for `setBlock`,
  `giveOrDrop`, `respawnEntity`. Defaults delegate to the pre-1.3.1
  non-NBT overload, so every one of the eight `*WorldMutator.java` cells
  continues to compile unchanged; sibling waves override per cell.

**Hot-path allocation cost.** Zero when `persistNbt=false` (the default) —
Guardian's per-event `persistNbt()` branch skips straight to the pre-1.3.1
submit path with no NBT allocation, no wasted column writes. When
`persistNbt=true`, the loader-side allocation is unavoidable (an
`NbtIo.write` into a `ByteArrayOutputStream`) and the DAO writes five extra
`setNull` / `setBytes` calls per INSERT. Estimated MySQL round-trip impact
under a burst of 10k BLOCK_BREAK/sec with 1 KB average BE payload: +10 MB/s
network, batched into the existing async write queue. Within budget for the
opt-in scenario.

**Migration cost.** `ALTER TABLE ADD COLUMN c TYPE NULL` on all three
dialects is O(1) metadata-only for nullable-without-default trailing columns
(MySQL 8+, PostgreSQL 11+, SQLite always). Zero-downtime; no table rewrite
on any backend.

**Followups.**
- `GuardianAPI` should surface a `persistNbtEnabled()` accessor so
  third-party plugins can gate their producer wiring. Track for v1.3.1 X6.
- `/vg status` should report the persistNbt toggle in the storage section.
  Track for v1.3.1 X6.
- The eight `WorldMutator` cells will need per-cell overrides in X-β; the
  interface default keeps them source-compatible until then.

## X6 — HikariCP tuning + DAO polish + wind-down ordering + reload atomicity + perf polish

**Ships:** the tenth-of-a-second buckets of the v1.3.0 post-integration audit —
Hikari prep-stmt cache + connection tuning, `DamageHistory` +
`EntityBlockChangeCoalescer` amortized eviction, unfair `Semaphore`,
`resolveUserOn` last_seen amortization, wind-down reorder + await,
`reloadConfig` atomicity, `JsonLinesLogFile` sync toggle,
`PerWorldConfigStore` mtime cache, `EventGate.addInternalHook` cap,
`NeoForgeEvents.WORKER` teardown, `Guardian.submitExplosion` pooling,
`RollbackEngine.hasMoreRows` PAGE_SIZE+1. Version bump 1.3.0 → 1.3.1.

**Rationale — audit close-out.** The v1.3.0 post-integration audit surfaced
P1-1 (Hikari prep-stmt cache), P1-2 (DamageHistory O(n) sweep on tick),
P2-1 (wind-down-order race between `ExplosionJoinWorker` and queue),
P2-3 (coalescer O(n) sweep on tick), P2-4 (no operator knob for Hikari),
P2-5 (reload atomicity — half-registered hook chain), plus a P3 grab-bag
covering allocation, fair semaphore latency, and the extra
`hasMoreRows` round-trip per page. Everything under X6 is small individually
but cumulatively takes the queue worker and the tick thread out of the
"pays O(n) at cap" territory that the audit called out.

**Hikari tuning (P1-1 / P2-4).**
`MysqlDao` gains four DataSourceProperty knobs matching HikariCP's own MySQL
recommendation:

| Property                  | Value | Effect                                  |
|---------------------------|-------|-----------------------------------------|
| `cachePrepStmts`          | true  | client-side PS cache                    |
| `prepStmtCacheSize`       | 250   | up to 250 statements per connection     |
| `prepStmtCacheSqlLimit`   | 2048  | cache statements up to 2 KB text        |
| `useServerPrepStmts`      | true  | server-side PS (COM_STMT_PREPARE)       |

`PostgresDao` gains `prepareThreshold=3` (default is 5) — pgJDBC switches
to a named server-side PS after three executes of the same PreparedStatement.
`GuardianConfig.database.hikari` is a new nested record:
`{maxPoolSize=10, connectionTimeoutMs=30_000, maxLifetimeMs=1_800_000,
leakDetectionMs=0}` — defaults match Hikari's own except pool=10, which
preserves the pre-X6 hard-coded value. `ConfigLoader` backfills the section
when the on-disk config predates the field so pre-X6 installs keep booting.

**Amortized eviction (P1-2 / P2-3).**
`DamageHistory.evictOldest` was O(n) per damage event once the map hit cap;
X6 amortizes by only sweeping every 64th over-cap insert (`EVICT_STRIDE=64`),
and each sweep evicts a batch equal to the stride to keep the transient
overshoot bounded. Net effect: was O(n) per event at cap → is O(n) every 64
events, plus O(1) reads. `EntityBlockChangeCoalescer` gets a similar
short-circuit — after a sweep returns zero evictions the coalescer drops new
events for `ZERO_SWEEP_BACKOFF_NS=50ms` before scanning again.

**Wind-down reorder + await (P2-1).**
`Guardian.shutdown()` used to close `dao` and drain `queue` BEFORE
`ExplosionJoinWorker.close()`. A join task queued at t=stop-Δ would land its
`submitExplosion` call after the queue drain, race the DAO close, and get
silently dropped. X6 reorders `explosionJoinWorker.close()` to run FIRST, and
`close()` now blocks up to 5 s waiting for pending tasks to complete
(mirrors `AutoPurgeScheduler.shutdown(5_000L)`).

**Reload atomicity (P2-5).**
`Guardian.reloadConfig` used to do `this.gate = new EventGate(...)` and only
THEN register per-world / blacklist / PreLog hooks. Between those two lines,
concurrent submitters saw a freshly-empty gate — blacklist rules briefly did
not apply, per-world overrides briefly did not apply. X6 builds the fresh
gate on a LOCAL reference, registers every hook against the local, then
publishes with a single `this.gate = local` swap. `blacklistHook` is
similarly staged so the pair updates atomically.

**Reload off-thread (P2-2).**
`ConfigLoader.load` now runs on the common `ForkJoinPool` with a 10 s
timeout, so `Files.readString + Gson.fromJson` do not stall the server
thread on slow / networked filesystems.

**Per-world mtime cache (P3-7).**
`PerWorldConfigStore.reload` caches per-file mtimes. Files whose mtime is
unchanged reuse the previously-merged `Actions` snapshot instead of
re-reading + re-parsing. Trivial today (a handful of worlds) but future-proof
for the 20+ dimension case. `updateRoot(...)` invalidates the cache because
merged snapshots are computed against the OLD root.

**Log-file sync toggle (P3-4).**
`JsonLinesLogFile` gains `config.logFile.forceSyncOnFlush` (default `true`).
Operators on slow storage (NFS, network drives) can accept &lt;
`flushIntervalMs` of durability for zero per-batch `FileChannel.force(false)`
cost. Same semantics; new opt-out.

**Internal-hooks cap (P3-5).**
`EventGate.addInternalHook` gains a soft cap of 32 with a one-shot WARN when
exceeded. `/vg status` surfaces the count under the Event Hooks section.
Registration past the cap is still permitted (soft cap), but a misbehaving
plugin registering per-tick now surfaces in the diagnostic report.

**`resolveUserOn` last_seen amortization (P3-8).**
Every insert used to synchronously write `UPDATE vg_users SET last_seen`
even when the record was fresh. X6 tracks the last write timestamp per user
id and only re-writes when the drift exceeds `LAST_SEEN_DRIFT_MS=60_000`.
Off-tick, but visible as a small write burst at busy join times.

**Explosion pooling (P3-2).**
`Guardian.submitExplosion` used to allocate a fresh `ActionBuilder` per
submit; every other submit path uses the per-thread scratch builder via
`seed(...)`. X6 makes explosions consistent — ~40 B / allocation less on the
`ExplosionJoinWorker` thread on TNT-heavy shards.

**Rollback page probe (P3-6).**
`RollbackEngine.hasMoreRows` used to issue a separate `dao.query(filter,
offset, 1)` at every page-boundary to decide whether to throw
`RollbackLimitExceededException`. X6 requests `PAGE_SIZE+1` rows in the main
page fetch and uses the extra row's presence as the "has more" signal,
saving one JDBC round-trip per exhausted page (up to 20 saved on a paged
supplemental scan).

**Unfair Semaphore (P3-3).**
`StorageFactory` switches the read-side rate-limit `Semaphore` from fair
(FIFO) to unfair. Fair mode adds queueing latency on every acquire; lookup
contention is uncommon in practice and there is no operator-visible
fairness SLA. Cuts p99 lookup latency under contention.

**`NeoForgeEvents.WORKER` teardown (P3-1).**
`WORKER` was a `static final ExecutorService` that never terminated,
leaking a daemon thread across every in-JVM server restart (dev mode).
`reset()` now shuts it down with a 2 s `awaitTermination` guard and a
`shutdownNow()` fallback. Idempotent; `isShutdown()` gate keeps the call
safe on repeat invocation.

**Regression tests.**
Six new focused tests plus updates to two existing ones:
`GuardianConfigHikariTest`, `ConfigLoaderHikariBackfillTest`,
`DamageHistoryAmortizationTest`, `EventGateInternalHooksCapTest`,
`PerWorldConfigMtimeCacheTest`, `ExplosionJoinWorkerShutdownTest`; existing
`DamageHistoryTest.overflowEvictsOldest` and `RollbackEngineTest` page mocks
updated for the new stride and `+1` page-probe patterns. Total 716 tests
passing (was 699 pre-X6).

**Version bump.**
`gradle.properties` `mod_version=1.3.0` → `1.3.1`; `GuardianAPI.PLUGIN_VERSION`
→ `"1.3.1"`; `CHANGELOG.md` `## [1.3.1] - 2026-07-03` section listing every
X1-X9 deliverable. X6 is the version-bump owner because it merges LAST in
Wave X-β (touches `Guardian.java` that other waves may have edited).
