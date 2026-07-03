# VonixGuardian v1.3.6 — Perf Notes (CC2 sweep)

Round-6 review of v1.3.5 surfaced 7 server-thread P1 findings and 15 P2
findings. This document captures the analysis and fixes shipped in v1.3.6
by CC2. CC1 shipped in parallel: canonicalisation of `GuardianConfig` shim
constructors across the 24 test files and `@Deprecated` on the shim ctors
themselves. This dossier covers only CC2 territory (server-thread perf).

---

## P1 findings

### P1-1 — hopper deque O(1) removal

**Where:** `mc-1.18.2`, `mc-1.19.2`, `mc-1.20.1` cells,
`ForgeEvents.java`, `HOPPER_POS_BY_LEVEL` field + `z3Register` /
`z3Unregister` / `onLevelTickHopperSampler` / `onChunkUnloadDropHoppers`.

**Symptom:** `ConcurrentLinkedDeque<Long>.remove(Object)` is O(n). Every
hopper break on a hopper-farm shard (2 000+ hoppers per dimension) paid
O(hoppers) on the server tick. `ChunkEvent.Unload` bulk drain iterated
BEs then did an O(n) `Deque.remove` per hopper — O(unloaded_hoppers × n)
per unload.

**Fix:** replaced with `LinkedHashSet<Long>` guarded by a per-level
`Object` lock (`HOPPER_LOCK_BY_LEVEL`).
- `remove(Object)` is O(1).
- Iteration retains insertion order for the round-robin sampler.
- The lock is uncontended in practice because all hopper events dispatch
  on the server thread; the guard is defensive against off-thread mixin
  injections.

Sampler drain now takes a bounded snapshot (`HOPPER_SAMPLE_PER_TICK = 20`)
under lock, then invokes `sampleHopperOne` outside the lock and
re-enqueues the survivors. Sampler work per tick is at most 20 hoppers,
so the lock is held for microseconds.

### P1-3 / P1-4 — `/fill` and `/setblock` `BlockPos` allocation storm

**Where:** three Forge cells, `ForgeEvents.onCommandFillSetblock`, both
pre-state and deferred post-state passes.

**Symptom:** each cell allocated `new BlockPos(x, y, z)` per iteration.
At the `FILL_MAX_REGION_BLOCKS = 32,768` cap, one `/fill` command
allocated up to 65,536 short-lived `BlockPos` objects — thrown away
immediately after `Level.getBlockState`.

**Fix:** reuse a `BlockPos.MutableBlockPos` scratch across the loop.
`level.getBlockState(scratch.set(x, y, z))` returns the same
`BlockState` without wrapping the position. Scratch allocations: 2 per
`/fill` invocation (pre + deferred).

### P1-5 — `CommandEvent` server-side gate

**Where:** all four loader cells (three Forge + NeoForge),
`ForgeEvents.onCommand` and `ForgeEvents.onCommandFillSetblock`.

**Symptom:** on integrated (singleplayer) servers, `CommandEvent` fires
on both the client and server logical sides. Without a gate, the
handler ran twice per command; the client-side dispatch has
`src.getLevel() == null` so `WorldKey.of(...)` would NPE and be
swallowed by our try/catch — silently dropping the audit row that
matters.

**Fix:** two-clause gate at the top of both handlers:
```java
if (src == null || src.getLevel() == null) return;
if (src.getServer() == null || !src.getServer().isSameThread()) return;
```
This eliminates the duplicate execution path AND guards against
off-thread mixin-injected command sources (Sinytra Connector has been
observed doing this on 1.21.1 NeoForge).

### P1-6 — `/vg config set` off-thread IO

**Where:** all eight loader cells, `GuardianCommands.Config.set`.

**Symptom:** the handler ran `ConfigLoader.save(path, next)` (blocking
YAML write) and `g.reloadConfig(path)` (re-parse + rebuild config-
derived state — up to ~50 ms on cold cache) inline on the server
thread. A slow disk (spinning rust, NFS, encrypted-swap host, or
another mod holding a filesystem lock) would freeze the tick until the
write returned.

**Fix:** wrap the IO in `WORKER.submit(...)`. Result reporting hops back
to the server thread via `server.execute(...)` so the theme/chat
pipeline (which touches Level state) stays single-threaded. Validation
(`next.validate()`) stays on the server thread because it's pure CPU
and needs to return synchronously to report validation failures without
misleading the operator.

The `WORKER` executor already exists in every cell for `/vg lookup`,
`/vg rollback`, `/vg migratedb`, etc., so this reuses the same 2-thread
pool — no new resource footprint.

---

## P2 findings

### P2-7 — `NATURAL_BLOCK_CACHE` key collision risk

**Where:** three Forge cells, `naturalCacheKey`.

**Symptom:** the pre-1.3.6 key packed `worldId.hashCode()` (32 bits) into
the top bits of a `long`, XOR-mixed with `x/y/z` (26/12/26 bits). But
`(wh << 32) ^ (x << 38) ^ (y << 26) ^ z` shifts `x` by 38 which
partially overlaps `wh` bits — collision risk when worldId hashes shared
low-order bits with high-order X coordinates (routinely hit at spawn on
servers with mod-added dimensions).

**Fix:** replaced with a `private record NaturalKey(String worldId, int
x, int y, int z)`. Record's auto-generated equals/hashCode makes
collisions structurally impossible. Record allocation is trivially
cheap vs the (rare) cache miss cost, and NeighborNotifyEvent is not
sub-tick hot.

### P2-8 — `FluidSourceMemory.lookup` TTL sweep amortized

**Where:** `core/attribution/FluidSourceMemory.java`.

**Symptom:** `lookup` on the fluid-tick hot path did an inline
`iterator.remove` + O(n) `Deque.remove(Object)` per expired entry.
When multiple entries TTL-expired inside a single call, the lookup
degenerated to O(n²).

**Fix:** amortize the TTL sweep inside `recordBucketEmpty` via a
bounded `SWEEP_STRIDE`-sized walk of `insertOrder`. `lookup` still
skips expired entries in place (correctness preserved), but no longer
pays the O(n) `Deque.remove` — orphaned `insertOrder` keys are
detected on the next `sweepExpiredAmortized` pass (byPos.get(key) ==
null) and dropped O(1) apiece.

### P2-9 — `TntPrimeMemory` volatile `sweepCursor` CME race

**Where:** `core/attribution/TntPrimeMemory.java`, `maybeEvict`.

**Symptom:** the amortized-sweep `Iterator` was shared across threads
via a `volatile` field. `volatile` provides publication happens-before
but does NOT make an Iterator thread-safe — `expectedModCount` state
is internal and going stale mid-sweep produces
`ConcurrentModificationException`. Two concurrent `record()` callers
(server thread + off-thread mixin injection) racing `maybeEvict`
would crash.

**Fix:** dropped `volatile`; cursor is now guarded by a dedicated
`sweepLock`. Lock is held only during eviction (max `SWEEP_STRIDE = 32`
entries), never during `record`/`consume`/`peek`. Also caught
`ConcurrentModificationException` from `it.next()` defensively — a
`putIfAbsent` on `ConcurrentHashMap` can invalidate a fail-safe
iterator's `expectedModCount` view; on catch we drop the cursor and
retry on the next tick (safe under-sweep).

New regression: `TntPrimeMemoryConcurrentSweepTest` — 8 threads ×
5,000 record/consume operations must survive without CME.

### P2-10 — `DamageHistory.evictOldest` PQ audit

**Where:** `core/attribution/DamageHistory.java`.

**Symptom:** allocation of `PriorityQueue<Map.Entry<UUID, Long>>` per
sweep.

**Audit outcome:** the PQ is allocated at most once per
`EVICT_STRIDE = 64` events, so amortized cost is
`64 × ~40 B / 64 events ≈ 40 B / event` — trivially below GC noise.
Left implementation in place; added an audit comment recording the
analysis so a future revisitor doesn't waste time re-deriving it.

### P2-11 — `PLUGIN_VERSION` in boot log

**Where:** `core/Guardian.java:186`.

**Fix:** LOG line now interpolates `GuardianAPI.PLUGIN_VERSION`
alongside `db.type`, `theme`, `queue.max`. Support triage against the
mod's console banner immediately reveals the shipped version — a
non-perf fix but essential for the release quality bar.

---

## Version-bump ownership

CC2 is the version-bump owner for v1.3.6:

- `gradle.properties`: `mod_version 1.3.5 → 1.3.6`.
- `core/api/GuardianAPI.java`: `PLUGIN_VERSION "1.3.5" → "1.3.6"`.
- `CHANGELOG.md`: `## [1.3.6]` section covering CC1 (test-shim canonical)
  + CC2 (perf sweep).
- This document (`docs/PERF-NOTES-1.3.6.md`).

## Non-scope for CC2

CC1 owns:
- All 24 test files listed in the CC1 prompt (test-shim canonicalisation).
- `@Deprecated` annotations on `GuardianConfig` sub-record shim ctors.

CC2 did not touch either.

## Verification

- `./gradlew -PbuildProfile=coreonly :core:build` — passes (912 tests).
- `./gradlew -PbuildProfile=forgeonly :mc-1.18.2:forge:build
  :mc-1.19.2:forge:build :mc-1.20.1:forge:build
  :mc-1.21.1:neoforge:build -x test` — passes on all four loader cells.
- New regression tests: `FluidSourceMemoryLookupHotPathTest`,
  `TntPrimeMemoryConcurrentSweepTest`.
