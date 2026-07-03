# PERF-NOTES-1.3.2.md

Wave-by-wave notes on the shape of v1.3.2 changes that touch schema, DAO, or
hot-path allocation. Owners: each wave appends their own paragraph at wave
close; do not rewrite prior sections.

## Y1 — NBT surface activation (2026-07-03)

**Wave scope.** X1 in v1.3.1 shipped the schema (v5) + submitter overloads +
WorldMutator interface for NBT fidelity, but zero producers or impls wired
the API — the round-2 audit's biggest P0. Y1 wires the full end-to-end path:
`RollbackEngine` branches on `Action.hasNbt()` and routes NBT-carrying rows
through the `WorldMutator` NBT overloads. All 8 loader-cell `WorldMutator`
impls override `setBlock`, `giveOrDrop`, `respawnEntity` NBT variants and
decode via `NbtIo.read` + `BlockEntity.load(...)` / `ItemStack.of(...)` /
`EntityType.loadEntityRecursive(...)`. All 8 loader-cell producers capture
NBT on the server thread during `BreakEvent` (block-entity NBT +
block-state), player `ItemToss` (item NBT), player container close (item NBT
per slot delta), and `LivingDeath` (entity NBT via `saveAsPassenger`).

**Config gate discipline.** Every producer path is gated by
`config.storage.persistNbt` (default `false`). When the toggle is off the
producer skips the NBT capture entirely — no `saveWithoutMetadata`, no
`ItemStack.save`, no `saveAsPassenger`, no allocation. When on, the NBT is
encoded via `NbtIo.write(CompoundTag, DataOutputStream)` to a byte[] on the
server thread, then hands the byte[] off through the async write queue.
Absolute per-payload cap: 512 KiB (`NbtCapture.MAX_NBT_BYTES`); anything
larger yields `null` and falls back to the legacy overload.

**Server-thread safety.** All NBT capture calls (`saveCustomOnly` /
`saveWithoutMetadata` / `ItemStack.save` / `Entity.saveAsPassenger`) run on
the server tick thread inside the event handler — the events that call them
(`BlockEvent.BreakEvent`, `LivingDeathEvent`, `ItemTossEvent`,
`PlayerContainerEvent.Close`) fire synchronously on tick, so touching
`BlockEntity` / `ItemStack` / `Entity` state is safe. The decoded byte[]
travels through the async write queue like any other row.

The `NbtIo.read` side runs on the rollback executor (`mainThreadExecutor`
passed to `RollbackEngine`), so `WorldMutator.setBlock(..., nbt)` and
friends touch `ServerLevel` / `BlockEntity` / `EntityType.loadEntityRecursive`
on the correct thread by construction.

**Log-and-fallback semantics.** Every WorldMutator NBT override wraps its
decode + apply in a `try/catch(Throwable)`; on decode failure or block/entity
registry-lookup failure it logs at DEBUG and delegates to the legacy
non-NBT overload. Never throws. Regression test
`NbtDecodeFailureFallbackTest` pins both the loader-side pattern (a mutator
that catches its own NBT failure and delegates) AND the engine-side pattern
(`RollbackEngine.applyBatch` catches `RuntimeException` from a mutator and
moves on to the next row).

**Byte[] identity on the hot path.** Producers pass captured NBT byte[]
straight into `ActionBuilder.blockEntityNbt(...)`; the builder stores the
reference on the `Action` record and the engine passes the same reference
into the WorldMutator NBT overload. No copy on any hop. Regression test
`NbtRollbackRoundTripTest` uses `.isSameAs(...)` (reference equality) rather
than `.isEqualTo(...)` to lock this invariant against silent drift into
defensive copies.

**Cell-specific API drift.**
- **1.21.1 (fabric + neoforge):** `BlockEntity.saveCustomOnly(HolderLookup.Provider)` /
  `.loadWithComponents(tag, registries)`; `ItemStack.save(registries)` returns
  `Tag` (cast to `CompoundTag`); `ItemStack.parseOptional(registries, tag)`
  on the decode side.
- **1.20.1 (forge + fabric):** `BlockEntity.saveWithoutMetadata()` /
  `.load(CompoundTag)`; `ItemStack.save(CompoundTag)` / `ItemStack.of(tag)`.
  `BuiltInRegistries.BLOCK` etc.
- **1.19.2 (forge + fabric):** same as 1.20.1 but uses legacy `Registry.BLOCK`
  static fields.
- **1.18.2 (forge + fabric):** same as 1.19.2.
- All 8 cells: `EntityType.loadEntityRecursive(tag, level, x -> { x.moveTo(...); return x; })`
  for the entity decode; `entity.saveAsPassenger(tag)` for the encode.

**Producer surface, per cell:**
- **Forge cells (1.18.2/1.19.2/1.20.1):** all 4 producers live in
  `ForgeEvents.java` (`onBlockBreak`, `onLivingDeath`, `onItemToss`,
  `onContainerClose`).
- **NeoForge 1.21.1:** same 4 producers in `NeoForgeEvents.java`.
- **Fabric cells:** `onBlockBreakAfter` and `onAfterDeath` live in
  `FabricEvents.java`; `itemDrop` and `containerClose` live in
  `FabricMixinBridge.java` (fabric uses mixins for those two).

**Zero-alloc hot path when disabled.** With `persistNbt=false` (default),
the producer path is:

```java
if (persistNbt()) {  // cached-boolean short-circuit
    ...capture + call NBT overload
} else {
    ...call legacy overload
}
```

The `persistNbt()` helper is a two-lookup chain
(`config().storage().persistNbt()`) — no allocation, no lock. On the engine
side, `Action.hasNbt()` short-circuits on the OR of five null checks; when
all five are null the engine goes straight to the legacy overload (no NBT
lambda captured, no boxing, no extra branch).

**Tests added:**
- `NbtRollbackRoundTripTest` (7 cases): waterlogged fence, chest with BE
  NBT, named+enchanted item, tamed dog, plus the row-without-NBT-lands-on-legacy
  invariant.
- `NbtDecodeFailureFallbackTest` (2 cases): engine caught + continued;
  loader-side delegate-to-legacy pattern.
- `PersistNbtToggleTest` (2 cases): toggle-off drops payload; toggle-on
  preserves it (byte[] identity through Guardian facade).

**Build tails:** `./gradlew -PbuildProfile=coreonly :core:build` passes
(300+ tests, ~19s). All 4 loader profile builds pass compile:
`:mc-1.21.1:neoforge:compileJava`, `:mc-1.21.1:fabric:compileJava`,
`:mc-1.20.1:{forge,fabric}:compileJava`,
`:mc-1.19.2:{forge,fabric}:compileJava`,
`:mc-1.18.2:{forge,fabric}:compileJava`.

**Non-regression:** the engine `applyInverse` and `applyForward` switches
retain their EXHAUSTIVE-per-ActionType shape (no `default` branch), so
future ActionType additions still trip the compile-time coverage check per
the "extend ActionType" recipe.

**Owned files (Y1):**
- Core: `RollbackEngine.java` (applyInverse/applyForward NBT branches).
- All 8 cell `WorldMutator.java` (3 NBT override methods + `decodeNbt` helper
  each — 8 files).
- All 8 cell `*Events.java` and 4 fabric `FabricMixinBridge.java` (persistNbt
  helper + 4 producer wraps each).
- 8 new per-cell `NbtCapture.java` helpers (1.21.1 flavor vs pre-1.21 flavor).
- 3 new regression tests under `core/src/test/…/rollback/`.

**Did NOT touch:** `Guardian.java` (Y3 owns reload plumbing),
`GuardianConfig.java` (X1 shipped `Storage.persistNbt` already), `Schema.java`
(X1 finalized v5), `EventGate.java`, mixins, `ActionBuilder.java` (X1 already
added NBT setters).

## Y5 — Attribution memory amortization (2026-07-03)

**Wave scope.** Round-2 audit P1-3 and P1-4 — two attribution-side caches
introduced in v1.3.1 that put O(n) or lock-cycle work on the server-thread hot
path. Y5 amortizes both without changing behavior.

**P1-3 — `TntPrimeMemory.hardEvict` amortization.** X7 in v1.3.1 shipped
`TntPrimeMemory` for the TNT-prime → detonate attribution chain. Under
sustained over-cap pressure (a modded server priming thousands of TNT per
minute) every `record()` above the cap paid a full `entrySet().removeIf(...)`
plus an unbounded arbitrary-drop loop on the server tick. Y5 gates the real
sweep behind a `hardEvictCounter % HARD_EVICT_STRIDE` check (`STRIDE = 64`) —
mirrors the `DamageHistory.EVICT_STRIDE` pattern from X6 — and caps the
second-pass arbitrary-drop loop at `HARD_EVICT_ARBITRARY_CAP = 128`
iterations. Steady-state size can transiently overshoot the cap by up to
`STRIDE + ARBITRARY_CAP` entries (~192 entries × ~150 B = ~28 KiB) before the
next sweep catches up — trivially cheaper than 63 wasted O(n) scans on the
tick. `clear()` also resets the amortization counter so a reload starts fresh.

**P1-4 — `FluidSourceMemory.lookup` empty fast-path.** X3 in v1.3.1 shipped
`FluidSourceMemory` for bucket-empty → fluid-spread traceback. Every fluid
tick calls `lookup()`; on a non-griefed server the map is empty forever and
each tick was paying a `ReentrantLock.lock()`/`unlock()` cycle plus map
iterator alloc for nothing. Y5 adds a `volatile int size` field — written
inside the lock in `recordBucketEmpty()` and inside the lock in `lookup()`'s
TTL-eviction branch, read outside the lock at the top of `lookup()` — and
short-circuits the whole method when size is zero. On the happy path (no
bucket-empty ever recorded) `lookup()` now does one volatile read + null
return, sub-ns amortized. The public `size()` accessor becomes lock-free by
the same mechanism.

**Amortization bounds.**
- `TntPrimeMemory.record` worst-case amortized cost per call is now
  `O(HARD_EVICT_ARBITRARY_CAP / HARD_EVICT_STRIDE)` = `O(2)` scan steps
  regardless of `maxEntries`. Prior surface: `O(maxEntries)` per over-cap
  insert.
- `FluidSourceMemory.lookup` on empty is now one volatile read (no lock, no
  alloc); prior surface: one `ReentrantLock` cycle + one map-iterator alloc
  per call.

**Tests + benchmark added:**
- `TntPrimeMemoryHardEvictAmortizedTest`: 6 cases — stride constant, arbitrary
  cap constant, under-cap-never-sweeps, over-cap-fires-exactly-once at STRIDE,
  20× cap insert storm stays bounded and amortizes, half-TTL pass still evicts
  stale entries, `clear()` resets the amortization counter.
- `FluidSourceMemoryEmptyFastPathTest`: 6 cases — fresh instance reports size
  0 without lock, empty lookup returns null without lock acquisition,
  record → lookup still hits, volatile size tracks byPos across TTL eviction,
  volatile size tracks byPos across over-cap eviction, reflective check that
  the `size` field carries the `volatile` modifier.
- `BenchAttributionMemoriesHotPath`: micro-bench harness plus 3 CI-visible
  `@Test`s enforcing a `< 1000 ns/op` CI ceiling (target `< 100 ns/op`
  amortized; loose 10× ceiling absorbs shared-runner jitter). Also asserts
  the `ReentrantLock` was never acquired after 10k empty `lookup()` calls.

**Owned files (Y5):**
- `core/src/main/java/network/vonix/guardian/core/attribution/TntPrimeMemory.java`
  (hardEvict amortize + arbitrary-loop cap + `clear()` counter reset).
- `core/src/main/java/network/vonix/guardian/core/attribution/FluidSourceMemory.java`
  (volatile size + empty fast-path + lock-free `size()`).
- `core/src/test/java/network/vonix/guardian/core/attribution/TntPrimeMemoryHardEvictAmortizedTest.java`
  (new).
- `core/src/test/java/network/vonix/guardian/core/attribution/FluidSourceMemoryEmptyFastPathTest.java`
  (new).
- `core/src/test/java/network/vonix/guardian/core/bench/BenchAttributionMemoriesHotPath.java`
  (new).

**Did NOT touch:** any Y1/Y2/Y3 files, `Guardian.java`, `RollbackEngine.java`,
`WorldMutator*.java`, cell events, cell mixins, config. Behavior is unchanged:
`record()` and `lookup()` return identical results to X7/X3 — only the cost
profile changes.

**Build tail:** `./gradlew -PbuildProfile=coreonly :core:build` passes.
## Y2 — Forge cell parity via event bus (2026-07-03)

**Wave scope.** Round-2 audit lines 30-31 (P0-2/P0-3): the X2/X3/X4/X7 mixins
for the three Forge cells (1.18.2 / 1.19.2 / 1.20.1) existed as source files
under `mc-*/forge/**/mixin/` but no `vg.mixins.json` / `mods.toml
[[mixins]]` block ever wired them. Coverage-illusion. Y2 closes the gap by
routing the same event surface through Forge's public event bus, avoiding
per-cell SRG-descriptor validation on three separate Forge mapping vintages.

**Handlers added to each `ForgeEvents.java`.**

- `onNeighborNotifyFluid(BlockEvent.NeighborNotifyEvent)` — X3 fluid-flow
  producer. Fires when a `LiquidBlock` notifies its neighbours; if any
  notified side is itself a fluid we emit `submitFluidFlow` with
  attribution resolved through `FluidSourceMemory` (2-min /
  8-block window from the bucket-empty seed on `onFillBucket`).
- `onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent)` — X3 cobble /
  obsidian / basalt generator producer.
- `onTntRightClickPrime(PlayerInteractEvent.RightClickBlock)` — X7 TNT
  player-prime capture (flint & steel on TNT block).
- `onProjectileImpactTnt(ProjectileImpactEvent)` — X7 TNT projectile-prime
  capture (burning arrow into TNT), resolves projectile owner as the
  actor.
- `onPrimedTntJoin(EntityJoinLevelEvent)` — X7 belt-and-braces for modded
  TNT variants that spawn `PrimedTnt` directly and set `owner` at
  construction.
- `onPortalSpawn(BlockEvent.PortalSpawnEvent)` — X4 nether-portal-frame
  place row (`Sentinel.PORTAL`).
- `onEntityStruckByLightning(EntityStruckByLightningEvent)` — X2 lightning
  fire attribution. Because the fire is spawned AFTER the event fires we
  defer the 3x3 column scan by one server tick via
  `serverLevel.getServer().execute(...)` and emit place rows for any new
  `minecraft:fire` blocks.
- `onFallingBlockJoin(EntityJoinLevelEvent)` — X2 gravity block-origin
  capture. The corresponding land-place is already covered by
  `BlockEvent.EntityPlaceEvent` firing with the `FallingBlockEntity` as
  the placer.
- `onLivingTick(LivingEvent.LivingTickEvent)` — X2 dragon /
  silverfish / snow-golem block-change tick surface. Sampled every 8
  ticks per entity with a bounded 64-entity tracked set and per-entity
  snapshot capped at 512 positions to keep steady-state cost O(1) per
  tick.

**Version differences (three cells, one design).**

- 1.18.2 uses `EntityJoinWorldEvent` / `LivingEvent.LivingUpdateEvent` /
  `ev.getWorld()` / `net.minecraftforge.event.world.BlockEvent` /
  `PlayerInteractEvent.getPlayer()` / `net.minecraft.core.Registry`.
- 1.19.2 uses `EntityJoinLevelEvent` / `LivingEvent.LivingTickEvent` /
  `ev.getLevel()` / `net.minecraftforge.event.level.BlockEvent` / entity
  `.level` field access (not method) /
  `net.minecraft.core.Registry`.
- 1.20.1 matches 1.19.2 except `BuiltInRegistries` replaced the legacy
  `Registry` class.

**Dormant mixin cleanup (Y8 sub-task).** Deleted 27 files (9 per cell,
across 3 cells): `EnderDragonMixin`, `SnowGolemMixin`,
`FallingBlockEntityMixin`, `LightningBoltMixin`, `SilverfishMixin`
(X2 — replaced by `onLivingTick` / `onFallingBlockJoin` /
`onEntityStruckByLightning`); `LiquidBlockMixin` (X3 — replaced by
`onNeighborNotifyFluid`); `PortalShapeMixin` (X4 — replaced by
`onPortalSpawn`); `TntBlockMixin`, `PrimedTntEntityMixin` (X7 — replaced
by `onTntRightClickPrime` / `onProjectileImpactTnt` / `onPrimedTntJoin`).
Kept: `HopperBlockEntityMixin` (Y6's slot-cache surface),
`FillCommandMixin` / `SetBlockCommandMixin` (X4 command-tracing, not
Y2 scope), plus `FireBlockMixin` / `IceBlockMixin` / `LeavesBlockMixin` /
`RavagerMixin` / `SpreadingSnowyDirtBlockMixin` /
`DispenserBlockMixin` (mixin-scope items outside Y2). Also removed
`ForgeMixinBridge.portalCreate()` — the sole caller (`PortalShapeMixin`)
is gone and `onPortalSpawn` inlines the submit.

**Hot-path cost.** `onNeighborNotifyFluid` runs on every neighbour-notify
tick but instance-checks `LiquidBlock` before doing any allocation — the
early-out is one virtual-call. `onLivingTick` uses a power-of-two mask
(`tick & 7`) to sample every 8th tick per living entity and holds a
bounded 64-entity map, per-entity snapshot capped at 512 positions.
`onFallingBlockJoin` and `onPrimedTntJoin` early-out on `!(e instanceof
...)` before touching Guardian state. `onEntityStruckByLightning` runs
a 9-block scan once per strike (rare event, deferred to the tick
executor).

**Tests.** `core/src/test/java/network/vonix/guardian/core/forgeevent/
ForgeEventBusParityTest` — 10 regressions covering each replaced mixin:
fluid attribution (X3, 2 tests), TNT prime (X7, 3 tests — right-click,
projectile, PrimedTnt-join), portal spawn (X4), dragon / silverfish /
falling-block / lightning (X2, 4 tests). Ran alongside the full
`:core:test` suite; the sole unrelated failure
(`GuardianAPIExtendedTest.queueLookup_filters_pending_queue_snapshot`)
was verified failing on the base commit `12b1423` before any Y2
changes.
