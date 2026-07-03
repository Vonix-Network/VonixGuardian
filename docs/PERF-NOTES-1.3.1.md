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

## X9 — Container coverage widening (Ledger parity)

**Ships:** `BaseContainerBlockEntityMixin` + `LocationalInventory` marker
interface in the four fabric cells (mc-1.18.2, mc-1.19.2, mc-1.20.1,
mc-1.21.1); `ContainerMixin` scope widened from `@Mixin(ChestBlockEntity.class)`
to `@Mixin({ChestBlockEntity.class, BarrelBlockEntity.class,
ShulkerBoxBlockEntity.class})`; each cell's `vg.mixins.json` registers the new
mixin.

**Rationale — Ledger parity.** Ledger's `BaseContainerBlockEntityMixin`
(https://github.com/QuiltServerTools/Ledger, `src/main/java/.../mixin/
BaseContainerBlockEntityMixin.java` lines 12–22) makes every
`BaseContainerBlockEntity` implement `LocationalInventory`, giving slot-level
callers a way to recover the on-world position from any `Container`
reference. Before X9, VG fabric's `ContainerMixin` was intentionally scoped to
`ChestBlockEntity` only (the `NOTE(v1.2.6)` block explained this and
suggested slot-level `AbstractContainerMenuMixin` for the rest — a path that
was never actually wired). Result: barrels and shulker boxes on fabric got
zero open/close diff logging, breaking `/vg lookup` on barrel deposits and
`/vg rollback` on shulker box withdrawals. Forge/NeoForge cells were never
affected — `PlayerContainerEvent.Open` + `be instanceof Container` catches all
container-block-entities at the player-event layer.

**Why `@Mixin({Chest, Barrel, Shulker})` instead of
`@Mixin(BaseContainerBlockEntity.class)`?** `startOpen(Player)` and
`stopOpen(Player)` are declared on the `Container` interface as `default`
no-ops (see `Container.class` javap). `BaseContainerBlockEntity` does NOT
override them — only concrete subclasses (chest, barrel, shulker) do, and
only those three do. An `@Inject(method = "startOpen(...)")` against the
abstract base silently fails to bind (descriptor not present on the class).
The correct spongepowered pattern is to name the concrete subclasses that
override the target method.

**Not covered (intentional):** hoppers, dispensers, droppers, furnaces
(regular / smoker / blast), and brewing stands do not override
`startOpen` / `stopOpen`. Their contents are mutated at the slot level via
`AbstractContainerMenu#clicked` (which the forge/neoforge cells already
capture through the PlayerContainerEvent path) or via specialized transfer
mixins (hopper push/pull lands in X4). This matches Ledger's own coverage
model — Ledger's `BaseContainerBlockEntityMixin` is a marker interface for
slot-position lookups, not an open/close hook. Compound (double) chests are
covered because the mixin fires on each half-chest `BlockEntity`
independently; the forge/neoforge PlayerContainerEvent path already merges
them by pos-window at the bridge layer.

**Cell scope: 4 fabric cells only.** The 3 forge cells (mc-1.18.2/forge,
mc-1.19.2/forge, mc-1.20.1/forge) and the 1 neoforge cell (mc-1.21.1/neoforge)
use `PlayerContainerEvent.Open` / `PlayerContainerEvent.Close` handlers in
`ForgeEvents.java` / `NeoForgeEvents.java` that already gate on
`be instanceof Container` — i.e. they already catch every `BaseContainerBlockEntity`
subtype (barrels, shulkers, furnaces, brewing stands, hoppers via
right-click) with no code changes. Widening the fabric mixin brings fabric
to loader-parity, not the other way around.

**Hot-path allocation cost.** Zero delta from pre-X9. Open takes the same
snapshot copy (`getContainerSize()` bounded by `MAX_CONTAINER_SLOTS = 216`);
close does the same slot-by-slot diff. `LocationalInventory.vg$getLocation()`
returns the mixin's pre-existing `worldPosition` field — no allocation, no
copy.

**Ledger parity notes.** VG's marker method is named `vg$getLocation()` (with
the `vg$` prefix required by mixin conventions to avoid method-name clashes
with modded BEs that might already declare a `getLocation()`). Ledger uses
`getLocation()` without a prefix; VG's choice is intentional and matches the
`vg$` prefix used by every other injected VG mixin method (see
`FireBlockMixin.vg$submitBurn`, etc.).

**Regression tests.** `core/src/test/java/network/vonix/guardian/core/event/`:

- `ContainerOpenCloseDiffTest` — pure-Java model of the open/close diff
  pipeline, five scenarios: chest / barrel / shulker per-slot delta, compound
  chest independence, empty-delta skip.
- `FabricContainerMixinRegistrationTest` — verifies the mixin JSON in all
  four fabric cells registers both `ContainerMixin` and
  `BaseContainerBlockEntityMixin`, and that `ContainerMixin.java` names all
  three widened targets (guards against a silent revert to the pre-X9
  chest-only scope).

**Followups.** None open. Ledger's slot-level `SlotMixin` +
`AbstractContainerMenuMixin` remain a future parity target (per-click
attribution instead of per-open-close diff) but are a separate design and
not part of X9.
