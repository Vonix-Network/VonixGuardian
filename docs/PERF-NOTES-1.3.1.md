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
