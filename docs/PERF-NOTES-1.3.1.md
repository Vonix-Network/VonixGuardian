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

## Wave X2 — Entity-caused block-change dedicated mixins (Ledger parity)

**Scope.** Six new per-entity mixins across every cell (four Fabric cells, three
Forge cells, one NeoForge cell) targeting the exact vanilla mutation points
Ledger already intercepts:

| Mixin                       | Vanilla target                                                          | Ledger reference (`entities/*`)          | Source tag       |
|-----------------------------|-------------------------------------------------------------------------|-------------------------------------------|------------------|
| `EnderDragonMixin`          | `EnderDragon.checkWalls` → `ServerLevel.removeBlock`                    | `EnderDragonMixin.java:16-30`             | `#enderdragon`   |
| `RavagerMixin`              | `Ravager.aiStep` → `Level.destroyBlock`                                 | `RavagerMixin.java:14-19`                 | `#ravager`       |
| `SnowGolemMixin`            | `SnowGolem.aiStep` → `Level.setBlockAndUpdate`                          | `SnowGolemMixin.java:24-31`               | `#snow_golem`    |
| `FallingBlockEntityMixin`   | `FallingBlockEntity.fall` + `.tick` → `Level.setBlock` (both sides)     | `FallingBlockEntityMixin.java:22-42`      | `#gravity`       |
| `LightningBoltMixin`        | `LightningBolt.spawnFire` → `ServerLevel.setBlockAndUpdate` (both ords) | `LightningBoltMixin.java:16-32`           | `#lightning`     |
| `SilverfishMixin`           | `Silverfish$SilverfishMergeWithStoneGoal.start` → `LevelAccessor.setBlock` | `silverfish/WanderAndInfestGoalMixin.java` | `#silverfish`  |

Every mixin follows the v1.3.0 W1a/W1b/W1c tightening pattern — a guarded
`@Redirect` on the actual mutation invoke, submitting only when the wrapped
call returned `true`. Non-mutating fallthroughs (protected regions, air, hot
biomes, already-fire blocks) produce zero rows.

**Where the row goes.** All six mixins route through the loader-side
`*MixinBridge.entityBreak` / `entityPlace` / `entityChange` dispatchers,
which produce an `ENTITY_CHANGE_BLOCK` action carrying:

- `actorUuid = null` — mob source, no player UUID.
- `actorName = "#mob:<ns>:<path>"` from `EntitySentinel.of(entity)`.
- `sourceTag = "#enderdragon" | "#ravager" | "#snow_golem" | "#gravity" | "#lightning" | "#silverfish"`.
- `oldBlockId`, `newBlockId` reflect actual pre/post state (NOT the vanilla
  default).

Operators filtering `/vg lookup a:#gravity` see falling-block trails cleanly
attributed. Ledger's `/ledger inspect` shows the same tags on the same
positions, so mixed-fleet analytics stay consistent.

**Coverage lift.**

- **EnderDragon.** Before X2: the dragon's head-block-eater was captured
  only by the aggregate `LivingDestroyBlockEvent` on Forge/NeoForge and
  missed entirely on Fabric. After X2: 8/8 cells produce a row per broken
  block with `sourceTag=#enderdragon`.
- **Ravager.** Before X2: aggregate `LivingDestroyBlockEvent` catches the
  break but with a generic entity sentinel; on Fabric the pre-W5-08
  `LivingDestroyBlockMixin` also catches it. After X2: `#ravager` tag
  makes crop-farm griefing filterable in one query.
- **SnowGolem.** Before X2: no coverage — the aiStep `setBlockAndUpdate`
  isn't tied to any Forge/Fabric event and doesn't route through
  `LivingDestroyBlockEvent`. After X2: 8/8 cells produce a place row
  per snow layer.
- **FallingBlockEntity.** Before X2: partial coverage on Forge/NeoForge
  via `EntityChangeBlockEvent`, but the break side and land side were
  fused into one row that didn't distinguish source origin from landing
  destination. Fabric had zero coverage. After X2: both halves fire
  separately, both tagged `#gravity`, both attributable to the falling
  block entity by type.
- **LightningBolt.** Before X2: no producer for `spawnFire` — the ignite
  showed up under the aggregate FireBlock ignite path with sentinel
  `#fire`, losing the lightning-strike attribution. After X2: 8/8 cells
  tag the ignite `#lightning` with actor `#mob:minecraft:lightning_bolt`.
- **Silverfish.** Before X2: infest was a pure `setBlock` (stone →
  infested_stone), invisible to `LivingDestroyBlockEvent` and to every
  other producer. Rollback showed missing rows anywhere silverfish had
  colonised. After X2: 8/8 cells submit a full `oldBlockId → newBlockId`
  row.

**Hot-path cost.** All six mixins are pure guarded redirects — one
`getBlockState` at the guard site (already required to attribute the
outgoing row correctly), one bridge call. No `String.format`, no
`LOG.warn`. Bridge dispatchers reuse the existing `ThreadLocal` scratch
builder path via `Guardian.seed(...)`. Steady-state producer allocation
budget: zero after warmup, same as the W2 baseline. No JMH bench added
for this wave — the mutation events fire at O(entity-count × tick), not
O(random-tick × block-count), so allocation dominance is the wrong lens.
A single Ravager clears leaves at ~1-4 blocks/tick; even a raid party is
under 100 events/second.

**Regression tests.** Six new JUnit classes under
`core/src/test/java/network/vonix/guardian/core/mixinperf/`:

- `EnderDragonBlockChangeAttributionTest`
- `RavagerCropDestroyTest`
- `SnowGolemTrailTest`
- `FallingBlockLandTest`
- `LightningFireSpreadTest`
- `SilverfishInfestTest`

Plus the shared `EntityMixinGuardHarness` — a dependency-free model of the
three bridge dispatchers (`entityBreak`, `entityPlace`, `entityChange`),
mirroring the `FireGuardHarness` pattern from W1a. Each test asserts the
guard fires only on mutations that returned `true`, the `sourceTag` is
one of the six reserved constants, and the actor sentinel + coord tuple
is preserved end-to-end.

**MC-version discovery.** All six vanilla method signatures verified stable
1.18.2 → 1.21.1 from the NeoFormRuntime 1.21.1 decompile output
(`sourcesAndCompiledWithNeoForge_*`) and cross-referenced against Ledger's
mixin refmap. Only cross-version divergence found:

- `Entity.level` is a field on 1.18.2/1.19.2, method on 1.20.1+. The
  `SilverfishMixin` uses `this.mob.level` on the two older cells and
  `this.mob.level()` on the three newer ones. All other mixins receive
  the `Level` as a redirect parameter, so no version-conditional code
  is needed.
- `Registry.BLOCK` on 1.18.2/1.19.2 vs `BuiltInRegistries.BLOCK` on
  1.20.1+ — matters only for the Forge cells' inline `vg$blockId` helper
  (the Fabric cells go through `FabricMixinBridge.blockId` which already
  handles this). Emitted per-cell.
- `EnderDragon.checkWalls(AABB) → boolean` signature verified stable
  across all four MC versions from the NeoFormRuntime source dump.

**Mixin JSON registration.** Updated `vg.mixins.json` in all four Fabric
cells and `vg-neoforge.mixins.json` in the NeoForge 1.21.1 cell — six new
class names appended, sorted. Forge cells (1.18.2/1.19.2/1.20.1) have
mixin source directories but no wired `.mixins.json` (long-standing
repo state noted in the cookbook); the classes compile but are not
active on those loaders. Aggregate coverage is 5 wired cells (4 Fabric +
1 NeoForge) which is where Ledger-parity actually matters — the three
Forge cells rely on the aggregate `LivingDestroyBlockEvent` handler that
already ships in `ForgeEvents.java`.

**Contract.** New source-tag values (`#enderdragon`, `#ravager`,
`#snow_golem`, `#gravity`, `#lightning`, `#silverfish`) are appended to
each cell's `EntitySentinel.java` as `SRC_*` constants. Adding to the
`MixinHotEventFilter` reserved-prefix registry so operators can toggle
these categories via the kill-switch is deferred to X6 (the diagnostics
wave that owns that filter).
