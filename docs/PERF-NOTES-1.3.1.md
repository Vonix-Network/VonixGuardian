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

---

## X4 — Hopper + Portal + /fill + /setblock producers

**Ledger-parity closure of G-Led-4 / G-Led-5.** Four new mixin sets, each
present in all eight cells (4 Fabric + 3 Forge + 1 NeoForge):

- `HopperBlockEntityMixin` — `@Inject(RETURN)` on `ejectItems` (push) and
  `suckInItems` (pull). Reads the first non-empty slot as a witness stack and
  submits `HOPPER_PUSH` / `HOPPER_PULL` via the new bridge dispatchers. All
  hooks carry `require = 0` — mapping / signature drift silently no-ops.
  Attribution: `Sentinel.HOPPER` (`#hopper`). Ledger's
  `TransportItemsBetweenContainersMixin` covers the copper-golem AI path;
  we ship the vanilla-hopper equivalent here.
- `PortalShapeMixin` — `@Redirect` on `LevelAccessor.setBlock` inside
  `createPortalBlocks`. Only fires when the write succeeds. Emits
  `BLOCK_PLACE` with `Sentinel.PORTAL` (`#portal`) as both actor name and
  source tag; `RollbackEngine` already admits `PORTAL_CREATE`-shaped rows for
  world-event rollback (see `RollbackEngine.java:575,643,708`).
  `ActionType.PORTAL_CREATE` (id=37) exists since v0.1.0 but had no producer
  — this fixes the "enum + rollback wired to nothing" gap.
- `FillCommandMixin` + `SetBlockCommandMixin` — `@Redirect` on the shared
  `BlockInput.place(ServerLevel, BlockPos, int)` INVOKE. Per successful
  place, emits BOTH a `BLOCK_BREAK` (of the pre-fill state, when not air)
  and a `BLOCK_PLACE` (of the post-fill state), scoped with source tag
  `cmd:fill` / `cmd:setblock`. Attribution: the command source's player
  when the source is a player, else `Sentinel.COMMAND` (`#command`).

**Server-thread bounded work discipline.** Unlike explosions (which build a
single joined-affected-list string per detonation), `/fill` emits per-position
atomic Actions with fixed-size payloads. The existing `BatchedAsyncWriteQueue`
handles the I/O offload; no `ExplosionJoinWorker`-style batching is required.
Verified via `X4ProducerParityTest.oversizedFill_5000_positions_all_rows_land`
which pins that 10,000 rows land on the recording submitter without drop.

**New Sentinels.** `Sentinel.PORTAL`, `Sentinel.COMMAND`, `Sentinel.HOPPER`
added to the frozen registry (contract bump: additive only). Full 20 → 23
entries; existing entries unchanged.

**Bridge additions.**
- `FabricMixinBridge` (× 4): `hopperPush`, `hopperPull`, `portalCreate`,
  `commandBlockBreak`, `commandBlockPlace`.
- `NeoForgeMixinBridge`: same five dispatchers.
- `ForgeMixinBridge` (× 3): **new class** — the three Forge cells previously
  had no shared bridge (their sibling mixins resolved Guardian inline). X4
  introduces this consolidation because the five X4 dispatchers share more
  surface than a per-mixin inline resolver justifies. Existing Forge mixins
  (Fire/Ice/Leaves/etc.) are left untouched.

**Mixins.json registration.** All 4 Fabric configs + the NeoForge config
gain the four new mixin names. Forge cells continue to rely on classpath
scanning (no mixin.json in this repo).

**Regression tests.** `core/src/test/java/network/vonix/guardian/core/event/X4ProducerParityTest.java`
covers:
- Sentinel `PORTAL` / `COMMAND` / `HOPPER` presence and frozen membership.
- Portal-frame `BLOCK_PLACE` shape (`Sentinel.PORTAL` in actor + tag).
- `/fill` per-pos paired `BLOCK_BREAK` + `BLOCK_PLACE` with player
  attribution.
- `/setblock` with non-player source (`Sentinel.COMMAND` attribution).
- Hopper push / pull with `Sentinel.HOPPER`.
- Oversized 5,000-position `/fill` smoke — 10,000 rows land, no drop.

**Build verification.** `:core:build` + `:core:test` clean.
`compileJava` clean on all 8 cells (`mc-{1.18.2,1.19.2,1.20.1}:forge`,
`mc-1.21.1:neoforge`, all 4 `mc-*:fabric`).

**Followups (not this wave).**
- Hopper mixin captures a per-tick "witness stack" rather than exact deltas
  between source/dest inventories; a follow-up wave (X9 candidate) can
  differentiate exact moved-slot amounts via `@Inject` on
  `HopperBlockEntity.tryMoveItems` inner path.
- `TransportItemsBetweenContainersMixin` (Ledger G-Led-6, copper golem AI)
  is not shipped by X4 — it only exists on 1.21+ and requires additional
  attribution plumbing. Track for post-1.3.1.
