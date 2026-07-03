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

## X3 — Fluid-flow attribution producer + bucket traceback

**Ships:** `ActionType.FLUID_FLOW` (id 40, token `fluid`, category `BLOCK`,
sign `PLACE`); new `Sentinel.FLUID` (`"#fluid"`); new
`network.vonix.guardian.core.attribution.Sources` class carrying `Sources.FLUID`
for source classification; new `FluidSourceMemory` with 2-min TTL / 8-block
Manhattan bucket-empty → fluid-spread traceback; `RollbackEngine` admits the
new type through the `FORM, SPREAD, BUCKET_EMPTY, STRUCTURE_GROW,
PORTAL_CREATE, FLUID_FLOW` group (clears to `minecraft:air` on rollback,
re-applies the flowing fluid block on restore); new
`EventSubmitter.submitFluidFlow` default overload with Guardian override;
`MixinHotEventFilter` recognises the `#fluid` mixin-hot-events prefix so the
operator kill-switch can short-circuit the fluid-tick storm; new
`LiquidBlockMixin` in all 8 cells targeting `FlowingFluid#spreadTo`.

**Rationale — CoreProtect parity.** The v1.3.0 audit
(`docs/COREPROTECT-COMPARISON.md` G-CP-1) flagged fluid-flow attribution as
the last remaining P0 parity gap. CoreProtect's `BlockFromToListener` queues
a `queueBlockPlace` for every water/lava spread respecting the
`WATER_FLOW`/`LAVA_FLOW` config toggles and preserves waterlogged source
handling by rewriting the source type to `Material.WATER`. Without that,
`/vg rollback` cannot undo a griefer's lava bucket after the lava has
already flooded a base — the buckets themselves rollback fine but the
downstream spread cells stay lit until manual repair.

**Producer shape (all 8 cells).** `LiquidBlockMixin` @Injects at
`FlowingFluid#spreadTo` HEAD. The `spreadTo(LevelAccessor, BlockPos, BlockState,
Direction, FluidState)` signature has been stable since 1.13 — same descriptor
across 1.18.2 → 1.21.1, no per-cell drift. The mixin calls the per-cell
bridge (`FabricMixinBridge.fluidFlow` on Fabric cells,
`NeoForgeMixinBridge.fluidFlow` on the NeoForge cell, inline
`vg$submitFluidFlow` helper on Forge cells since Forge cells have no wired
mixin JSON in this repo but keep source parity for a future wave).

**Traceback pattern.** The bridge does two things:

1. On `BucketItem#use` (empty branch) the loader's existing bucket-empty
   producer path calls `FluidSourceMemory.recordBucketEmpty(worldId, x, y, z,
   player, name, System.currentTimeMillis())` in addition to the existing
   `submitBucketEmpty(...)`. This is the CoreProtect equivalent of
   `Lookup.whoPlacedCache(block)` — a positioned cache that maps a source
   position to the recent placer's identity.
2. On `FlowingFluid#spreadTo` the `fluidFlow` bridge method calls
   `FluidSourceMemory.lookup(worldId, destX, destY, destZ, now)` for the
   destination cell. If a live record exists within the 8-block Manhattan
   window and the 2-min TTL, the resulting `FLUID_FLOW` row carries the
   emptying player's UUID + name. Otherwise the row carries `Sentinel.FLUID`
   (`"#fluid"`) as `actorName` with `actorUuid = null`.

The 8-block radius is generous to CoreProtect's water-spreads-7-per-source /
lava-spreads-3-per-source model — it lets a bucket that was thrown one
block over from its intended spot still resolve. Manhattan (rather than
Euclidean) keeps the check to three abs+adds; every fluid-tick pays this
cost so allocation matters.

**Kill-switch coverage.** Every producer submits with
`sourceTag = MixinHotEventFilter.PREFIX_FLUID + ":" + kind` where `kind` is
`"water"` or `"lava"`. That means an operator setting
`actions.mixinHotEvents=false` in a fluid-tick emergency short-circuits every
fluid submission at `Guardian.submit(Action)` before the queue sees it,
exactly like the W1a/b/c `#fire` / `#natural` / `#dispenser` prefixes.
Diagnostic counters continue to surface the drop rate in `/vg status`.

**Memory footprint.** `FluidSourceMemory` is a `HashMap<Key, Record>` +
`ArrayDeque<Key>` FIFO capped at 4096 entries with opportunistic TTL
eviction on read. Steady-state on a busy server: a few dozen live keys.
Under a bucket-spam griefing raid: capped at 4096 by the ring buffer, ~40 B
per entry ⇒ under 200 KiB peak. Zero allocation in the hot fluid-tick path
(the lookup only reads; TTL eviction happens under the lock).

**Rollback semantics.** `RollbackEngine.applyRollback` for `FLUID_FLOW`
clears the destination cell to `minecraft:air` (identical to `FORM`,
`SPREAD`, `BUCKET_EMPTY`, etc.). `applyForward` (restore) re-applies the
flowing fluid block id + meta from the row. `RollbackEngine.isRollbackable`
returns `true` for `FLUID_FLOW`; `RollbackPlan.build` admits it into the
plan; `RollbackExpansionParityTest` covers the new entry alongside the
existing expansion actions.

**Test coverage.**
- `FluidSourceMemoryTest` (9 tests) — radius / TTL / cross-world / eviction
  / overwrite / constructor validation / 2-min-exactly assertion.
- `BucketSpillTracebackTest` (5 tests) — end-to-end attribution chain with
  a bucket empty → 30-second-later spread stitching back to the emptying
  player; TTL fallback to `#fluid`; radius fallback to `#fluid`; distinct
  `#fluid:water` / `#fluid:lava` sourceTag suffixes; mixin-hot-events
  filter recognition of the fluid prefix.
- `FluidFlowRollbackTest` (4 tests) — engine `isRollbackable`, plan
  admission, rollback clears to air, restore re-applies fluid.
- `RollbackExpansionParityTest` updated to include `FLUID_FLOW` in the
  supported list.
- `ActionTypeTest.idsAreUniqueContiguousAndExpanded` bumped to 40.

**Followups.**
- The `LIQUID_TRACKING` / `WATER_FLOW` / `LAVA_FLOW` CoreProtect config
  keys have no VG equivalent yet — v1.3.2 X-follow could add three
  boolean toggles under `actions` if operators ask. Today, if an operator
  wants to disable fluid-flow logging entirely they set
  `actions.mixinHotEvents=false` or blacklist the sourceTag prefix.
- Forge cells (`mc-1.18.2/forge`, `mc-1.19.2/forge`, `mc-1.20.1/forge`)
  ship the `LiquidBlockMixin` source file for parity but no runtime mixin
  JSON. Wiring is expected to arrive with the same wave that wires the
  other Forge mixins (currently deferred to a later v1.3.x maintenance
  wave). The bucket-empty seed side already works via
  `ForgeEvents.onFillBucket`, so a partial rollout captures at least the
  actor side even without the spread producer.
- CoreProtect suppresses `#water` / `#lava` re-fires in a flood-tick loop
  via `flowDuplicateCache`; VG's coalescer does not fold successive
  fluid-flow rows at the same coord within a short window. Track as an
  X6 (`vg-post-wave-perf-async-audit-pattern`) follow-up if drop rate on
  the fluid stream shows up in production `/vg status`.
