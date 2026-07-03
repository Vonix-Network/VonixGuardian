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

## X7 — TNT-prime attribution chain (P1 CoreProtect-parity gap G-CP-2)

**Problem.** `ExplosionEvent.Detonate` exposes only the direct source entity.
For `PrimedTnt`, that's the TNT itself — the actor chain (right-click F&S,
dispenser F&S, redstone charge, fire spread) is lost, and every TNT explosion
attributes to sentinel `#tnt` with no player scope. CoreProtect closes the
gap via `TNTPrimeListener` / `TNTPrimeUtil.getFireUser`; VonixGuardian on
NeoForge/Fabric had no equivalent.

**Fix.** Two new mixins per cell + a shared core-side 5-minute-TTL memory:

- `TntBlockMixin` — HEAD-injects on the private static
  `TntBlock.explode(Level, BlockPos, LivingEntity)`. Every prime path in
  vanilla funnels through this method (fire spread, redstone, dispenser F&S,
  player right-click F&S, wasExploded from chained TNT, projectile hit). When
  the igniter is a Player, records into `TntPrimeMemory` keyed by
  `(worldId, blockPos)`.
- `PrimedTntEntityMixin` — TAIL-injects on the
  `PrimedTnt(<init>(Level, DDD, LivingEntity))` constructor as belt-and-braces
  for modded TNT variants that spawn `PrimedTnt` directly, skipping the
  `TntBlock.explode` path.
- `TntPrimeMemory` — `ConcurrentHashMap<(worldId, packedPos), PrimeRecord>`
  with 5-minute default TTL (accommodates modded fuse extenders up to that
  bound), amortised eviction on every put/miss (`SWEEP_STRIDE=32`), hard cap
  at 8k entries. Record carries actor UUID/name, `AttributionKind`, and a
  `sourceTagHint` string for future fire-chain wiring.
- `UniversalAttribution.resolveTntPrime(...)` / `.consumeTntPrime(...)` —
  loader-agnostic core helper the detonate handler consults BEFORE running
  the normal resolver chain. On hit, returns a fully-populated
  `Attribution` (kind preserved from the record, `entitySentinel="#tnt"` so
  `/vg lookup #tnt` continues to surface the row — CoreProtect parity).

**Wiring.**
- `Guardian.tntPrimeMemory()` accessor exposes the shared instance.
- `Guardian.shutdown()` clears the memory (deterministic teardown).
- NeoForge cell: `onExplosionDetonate` in `NeoForgeEvents` calls
  `UniversalAttribution.resolveTntPrime(...)` first when
  `source instanceof PrimedTnt`; falls back to
  `NeoForgeBootstrap.resolver.resolve(...)` on miss. Same shape on Forge
  cells (`ForgeEvents.onExplosionDetonate`) with the per-version source
  getter (`getSourceMob` on 1.18.2, `getExploder` on 1.19.2,
  `getDirectSourceEntity` on 1.20.1). Fabric cells wire the same logic in
  `FabricMixinBridge.explosion(...)` (which the `ExplosionMixin` calls).
- `NeoForgeMixinBridge.recordTntPrimePlayer(...)` /
  `FabricMixinBridge.recordTntPrimePlayer(...)` — new bridge entrypoints
  the mixins call. Forge cells inline the record call since there's no
  ForgeMixinBridge (matches the existing pattern of `vg$submitBurn` etc).

**Mixin JSON registration.** All 4 fabric cells + neoforge 1.21.1 register
both mixins in `vg.mixins.json` / `vg-neoforge.mixins.json`. Forge cells
have no mixin JSON in this repo — mixins are present but dormant (matches
pre-existing FireBlockMixin/etc pattern).

**MC-version signature stability.** `TntBlock.explode(Level, BlockPos,
LivingEntity)` and `PrimedTnt.<init>(Level, DDD, LivingEntity)` are
byte-stable from 1.18.2 through 1.21.1 (verified via javap on
`server-*-srg.jar`). `require = 0` on both `@Inject` calls so missing
targets (e.g. Sinytra Connector remap edge cases) degrade gracefully
instead of crashing boot.

**Hot-path cost.**
- `TntBlockMixin.vg$onTntPrime` — 1 instanceof + 1 `ConcurrentHashMap.put`
  per TNT prime. Amortized eviction adds at most 32 entrySet iterations per
  put. TNT prime is not a high-frequency event (worst-case chain reaction
  = O(fuse-count/tick) which is still O(hundreds)).
- Detonate side — 1 `instanceof PrimedTnt` + 1 `ConcurrentHashMap.remove`
  per explosion when the source is PrimedTnt. Miss is O(1), hit is O(1) +
  the `Attribution` constructor (record allocation). Net cost is well
  under the existing per-explosion overhead of the affected-list capture.

**Regression coverage.** `TntPrimeMemoryTest` — 13 tests, all four scenarios
(player F&S, redstone null-igniter, dispenser round-trip, fire round-trip)
plus memory semantics (TTL expiry, consume-removes, peek-preserves,
position-exact-match, world-scoping, over-cap eviction, null-input safety,
clear, and CoreProtect-parity sentinel preservation).

**Followups (out of X7 scope).**
- Fire-chain memory — extend `TntPrimeMemory` (or a sibling) to record who
  lit fire at pos, so `TntBlockMixin` can look up an adjacent fire's actor
  when igniter is null. That closes the dispenser F&S + spreading-fire
  attribution paths. Requires either adding to X-owned FireBlockMixin (X-β
  wave file, off-limits this task) or a new `FireIgnitionMixin`. Track
  for v1.3.2.
- Redstone-chain walking — track the last player to activate the button /
  pressure plate / lever driving a redstone circuit. Broader change;
  probably not worth the complexity vs the false-positive risk on
  clock-driven farms.
- Modded TNT variants that extend `TntBlock` but override `explode(...)`
  bypass the block-side hook. `PrimedTntEntityMixin` catches the modded
  ones that hit vanilla `PrimedTnt.<init>(Level,DDD,LivingEntity)`; the
  remainder need per-mod plumbing (documented in `docs/COREPROTECT-COMPARISON.md`).
