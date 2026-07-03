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
