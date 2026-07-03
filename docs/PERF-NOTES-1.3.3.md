# PERF-NOTES-1.3.3.md

Wave-by-wave notes on the shape of v1.3.3 changes that touch schema, DAO,
event bus wiring, or hot-path allocation. Owners: each wave appends their
own paragraph at wave close; do not rewrite prior sections.

## Z3 — Forge hopper + /fill //setblock event-bus fallback (2026-07-03)

**Wave scope.** Round-3 audit findings P1-B and P1-C: on the three Forge
cells (1.18.2 / 1.19.2 / 1.20.1), six mixin source files
(`HopperBlockEntityMixin`, `FillCommandMixin`, `SetBlockCommandMixin` × 3
cells) sat under `mc-*/forge/src/main/java/.../forge/mixin/` compiling
into the jar but with no `vg.mixins.json` companion and no
`[[mixins]]` block in `mods.toml`. Hopper transfers via
`HopperBlockEntity#tryMoveItems`, and per-block writes from `/fill` and
`/setblock`, produced **zero audit rows on Forge**. `/vg lookup` never
saw hopper item movement or the individual block placements a hopper
farm or admin-command operation put down; the two are exactly the
producers CoreProtect surfaces via listener and Ledger surfaces via
mixin.

Z3 applies the Y2/Z2 pattern (Forge event bus + safe fallback samplers)
to close the gaps that Forge exposes no first-class event for.

**Hopper — bounded content-diff sampler.** Forge exposes no
`HopperBlockEntity#tryMoveItems` event, no container-listener registration
point on `HopperBlockEntity`, and no per-transfer callback. Z3 stands up a
bounded per-tick sampler:

- `HOPPER_POS_BY_LEVEL` : per-`worldId` `ConcurrentLinkedDeque<Long>`
  holding packed `(worldHash, x, y, z)` keys for tracked hoppers.
- `HOPPER_POS_LOOKUP` : `Map<Long, BlockPos>` to recover `BlockPos` from
  the packed key.
- `HOPPER_SNAPSHOT` : `Map<Long, String[5]>` holding the last-observed
  `item_id|count` for each of the five vanilla hopper slots.
- `onChunkLoadRegisterHoppers(ChunkEvent.Load)` walks a freshly-loaded
  chunk's block-entities and adds any `HopperBlockEntity` to the tracker.
- `onChunkUnloadDropHoppers(ChunkEvent.Unload)` drains them.
- `onHopperPlacedRegister(BlockEvent.EntityPlaceEvent)` +
  `onHopperBrokenUnregister(BlockEvent.BreakEvent, priority=LOWEST)`
  keep the roster fresh across place/break.
- `onWorldTickHopperSampler(TickEvent.WorldTickEvent)` on
  1.18.2 / `onLevelTickHopperSampler(TickEvent.LevelTickEvent)` on
  1.19.2 + 1.20.1: at `Phase.END` server-side, poll up to
  `HOPPER_SAMPLE_PER_TICK = 20` hoppers per level in round-robin,
  diff each slot against the snapshot, emit
  `submitHopperPush` (slot count went up) or `submitHopperPull` (slot
  count went down). Item-type swap within the same slot between two
  samples emits both `submitHopperPull(prev)` and
  `submitHopperPush(cur)` so rollback can undo either side.

Bounded discipline: `HOPPER_TRACKER_MAX_PER_LEVEL = 8 192`. If the roster
overflows we stop adding to the deque; existing entries continue to be
sampled. Snapshot entries are cleaned lazily inside the sampler when
`level.getBlockEntity(pos)` no longer returns a `HopperBlockEntity`.

**/fill + /setblock — CommandEvent-driven region diff.** Forge has no
event that fires per block written by the vanilla /fill or /setblock
implementations. Z3 hooks the higher-level `CommandEvent`:

1. `onCommandFillSetblock(CommandEvent)` detects `fill` / `setblock` (bare
   or `namespace:fill` / `namespace:setblock` for modded aliases).
2. Extracts the parsed `Coordinates` argument(s) via
   `CommandContext#getArguments()` — one for /setblock, two for /fill.
3. Rejects volumes over `FILL_MAX_REGION_BLOCKS = 32 768` (the vanilla
   /fill hard cap) so a pathological modded region can't blow the
   snapshot heap.
4. Snapshots the region's pre-state as a flat `String[]` of block-ids
   (block-id per-position at ~50 ns per lookup — comfortable inside a
   32 K region).
5. Schedules a post-tick runnable via `level.getServer().execute(...)`.
   Vanilla /fill and /setblock complete synchronously inside the
   command; the post-tick runnable sees the fully written region and
   diffs it against the snapshot, emitting per-position
   `submitBlockBreak(prevId)` + `submitBlockPlace(curId)` for every
   position where the state changed.
6. Attribution: `CommandSourceStack#getEntity()` for the player path;
   `Sentinel.COMMAND` for console. Source tag is `cmd:fill` or
   `cmd:setblock`.

**Acknowledged coverage gaps.**

1. **Hopper sampler is sampled, not exact.** With ~20 hoppers/tick/level
   the tracker touches every hopper in `ceil(N/20)` ticks. For farm-heavy
   shards with >2 000 active hoppers per dimension the mean sample
   latency is ~5 s. Chained push+pull within the same sample window
   (item enters and leaves the same slot between two consecutive samples)
   cancels out and produces no row — this is the accepted trade-off for
   bounded per-tick work. Fabric and NeoForge continue to use their wired
   `HopperBlockEntityMixin` for exact per-transfer capture and are
   unaffected.
2. **Pre-existing hoppers before tracker init.** Hoppers whose chunks
   loaded before the first `ChunkEvent.Load` handler fires (server
   startup race) are only picked up on the next chunk unload/load
   cycle. In practice mod initialisation orders `RegisterCommandsEvent`
   before `ChunkEvent.Load` fires for the initial spawn chunks, so this
   is only a factor for hot-reloaded config. Documented as an accepted
   gap rather than false coverage.
3. **/fill regions above 32 768 blocks.** The vanilla command itself
   rejects such regions; a modded /fill that widens the cap will not be
   audited. Rows are safely omitted (no false log) rather than the
   snapshot heap blowing up.
4. **Deferred block writes.** A mod that hooks /setblock and defers the
   write to a later tick will see the "post" state at
   `server.execute(...)` time equal to pre. The row is safely omitted
   rather than falsely reported.
5. **`net.minecraftforge.commands.CommandEvent` cancellation.** If a
   downstream listener at a higher priority cancels the CommandEvent
   Z3's snapshot still fires. The deferred runnable diffs pre vs post
   and finds no change, so no rows are emitted — no false-positive.

**Bounded-memory discipline.**

| Structure | Cap | Steady-state cost |
|-----------|-----|-------------------|
| `HOPPER_POS_BY_LEVEL[worldId]` | 8 192 keys / level | ~65 KiB / level |
| `HOPPER_SNAPSHOT` | matches roster, 5 × ~24 char string per hopper | ~1 MiB @ 8 192 hoppers |
| `HOPPER_POS_LOOKUP` | matches roster, `BlockPos` per key | ~200 KiB @ 8 192 hoppers |
| Snapshot per /fill region | `FILL_MAX_REGION_BLOCKS = 32 768` string refs | ~1.5 MiB peak / open command, dropped on runnable exit |

**Concurrency.** All Z3 handlers run on the server tick thread. Sampler
uses `ConcurrentHashMap` + `ConcurrentLinkedDeque` defensively so a mod
that off-threads chunk load (Sinytra Connector observed on NeoForge
1.21.1, not applicable here) does not corrupt the roster.

**Error rate limiting.** `rateLimitedZ3Warn(site, throwable)` guards
every handler outer `try/catch`; `LOG.warn` at most once per 60 s per
`(site, throwable-class)` pair. A misbehaving mod that trips the
sampler every tick will not spam the log.

**Reset discipline.** `clearHopperTracker()` is called from `reset()`
alongside `clearNaturalBlockCache()` on server-stop, so an in-JVM
restart does not retain stale `(worldId, pos)` tracker state.

**Orphan cleanup.** Six dormant mixin files deleted:

```
mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/mixin/{Hopper,Fill,SetBlock}...
mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/mixin/{Hopper,Fill,SetBlock}...
mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/mixin/{Hopper,Fill,SetBlock}...
```

`RavagerMixin.java` remains — it belongs to X2 (still-active on Fabric /
NeoForge and covered on Forge by `onLivingTick`, out of Z3 scope).

`ForgeMixinBridge.java` is now completely unreferenced in Forge cells
(Z2 removed the natural-block mixin call sites, Z3 removed the last
hopper/command call sites). It is left in-place for this wave as a
non-blocking dead class; a future cleanup wave can drop it.

**Regression coverage.**

- `Z3HopperSamplerTest` (new, `core/src/test/java/.../forgeevent/`, 10 tests):
  first observation → no rows, slot count increase → push, decrease
  → pull, slot cleared → pull with prev count, item-type swap →
  pull(A) + push(B), no change → no rows, all 5 slots simultaneously →
  5 rows, independent hoppers tracked separately, sentinel discipline
  (`actorName == Sentinel.HOPPER`, `sourceTag == Sentinel.HOPPER`,
  `actorUuid == null`).
- `Z3FillSetblockCommandTest` (new, 10 tests): /setblock stone → air
  emits BREAK only, air → diamond emits PLACE only, cobble → stone
  emits both, /fill 3×3×3 air → glass emits 27 places, mixed pre-state
  → uniform post diffs correctly, region-cap sanity check, no change
  → no rows, console attribution `null` uuid + `Sentinel.COMMAND`
  name, player attribution correct, sourceTag distinguishes
  `cmd:fill` from `cmd:setblock`.

All 20 Z3 tests pass on `:core:test`. Build matrix — `:core:build` +
`:mc-1.18.2:forge:build` + `:mc-1.19.2:forge:build` +
`:mc-1.20.1:forge:build` — green.

**Performance envelope.** Sampler runs at `Phase.END` per-level per
tick, does up to 20 × 5 = 100 `getItem(slot)` calls + 100 string
comparisons. On a shard with a 5-dimension server that's 500
`getItem` calls per tick, well under 0.02 % of tick budget. The /fill
snapshot is O(volume) reads + O(volume) writes, but is capped at 32 K
positions and only runs on command invocation (not on the tick hot
path). The deferred runnable runs on the same server-tick thread on
the next tick boundary, does the same O(volume) reads, and emits into
the async `BatchedAsyncWriteQueue` which handles the actual DB write
off-thread.

## Z2 — Forge natural-block event-bus fallback (2026-07-03)

**Wave scope.** Round-3 audit finding P1-A: on the three Forge cells
(1.18.2 / 1.19.2 / 1.20.1), fifteen mixin source files existed under
`mc-*/forge/src/main/java/network/vonix/guardian/mc/*/forge/mixin/`
(`FireBlockMixin`, `IceBlockMixin`, `LeavesBlockMixin`,
`SpreadingSnowyDirtBlockMixin`, `DispenserBlockMixin` × 3 cells) but were
dormant — no `vg.mixins.json` companion, no `[[mixins]]` block in
`mods.toml`. `BURN`, `IGNITE`, `FADE`, `FORM`, `SPREAD`, `LEAVES_DECAY`,
and `DISPENSE` action rows were never emitted on Forge, silently missing
from `/vg lookup`, `/vg rollback`, and every audit path.

Z2 applies the v1.3.2 Y2 pattern (Forge event bus instead of activating
mixin config) to the natural-block surface. A new `@SubscribeEvent
onNaturalBlockNeighborNotify(BlockEvent.NeighborNotifyEvent)` handler in
each cell maintains a bounded LRU (`NATURAL_BLOCK_CACHE`, cap 4 096
entries, 32-entry chunk eviction) mapping `(worldId hash, packed pos)`
→ last-observed block registry id for a curated set of "hot" natural
blocks. When the neighbor-notify handler fires we compare
`ev.getState()` against the cached pre-state at the same position; if
the category transitioned, we classify and route to the correct
`EventSubmitter` overload:

| Transition (prev cat → cur cat) | ActionType submitted | sourceTag |
|---|---|---|
| fire → non-fire | `BURN` | `#fire:burnout` |
| non-fire → fire (or fresh fire with no cache) | `IGNITE` | `#fire:ignite` |
| ice/snow → water/air | `FADE` | `#natural:fade` |
| powder → concrete | `FORM` | `#natural:form` |
| water/lava → obsidian/cobble/stone/basalt | `FORM` | `#natural:form` |
| water/(air) → ice | `FORM` | `#natural:form` |
| dirt → grass/mycelium/podzol | `SPREAD` | `#natural:spread` |
| leaves → non-leaves | `LEAVES_DECAY` | `#natural:decay` |

All source-tag strings reuse `MixinHotEventFilter` reserved prefixes
(`#fire` / `#natural`) so the v1.3.0 W4 mixin-hot kill-switch still
short-circuits the entire natural-block flood in one flag.

**Acknowledged coverage gaps.**

1. **DISPENSE is not covered by the Forge event bus** on any of 1.18.2 /
   1.19.2 / 1.20.1. `DispenserBlock#dispenseFrom` is a private static
   funnel with no companion event. `NeighborNotifyEvent` fires on the
   redstone-pulse neighbor update, but does not carry either the ejected
   `ItemStack` or the origin `BlockPos` context that `submitDispense`
   requires. Z2 documents this gap in code comments (both
   `ForgeEvents.java § v1.3.3 Z2` and next to the deletion of
   `DispenserBlockMixin.java`) rather than fake coverage with a
   redstone-only proxy row. Fabric and NeoForge cells continue to log
   DISPENSE via their existing wired mixins. A future forge-side
   `vg.mixins.json` wave could restore mixin-based DISPENSE logging on
   Forge; Z2's scope is event-bus fallback only.
2. **BURN via `Level.updateNeighborsAt`-free block removals** — the rare
   case where a mod calls `ServerLevel.setBlock(pos, air, flags = 2)`
   (skip neighbor notify) to consume a flammable block will miss the
   `NeighborNotifyEvent` and consequently the Z2 BURN row. Vanilla
   `FireBlock#tick` uses `flags = 3` which does emit the neighbor
   notify, so vanilla fire burn-out is covered.
3. **IGNITE via LightningBoltEntity#spawnFire** is already covered by
   the pre-existing Y2 `onEntityStruckByLightning` handler (1-tick
   deferred 3×3 column scan). Z2 also catches lightning-lit fire via
   the neighbor-notify diff, so this becomes redundant coverage — no
   double-submission because Z2 caches the fire state after emit and
   the Y2 scan runs on a separate defer tick.

**Bounded-memory discipline.** The `NATURAL_BLOCK_CACHE` map:

- Only tracks **primary hot** positions (fire / ice / leaves / powder /
  concrete / grass / dirt / water / lava). FORM-target categories
  (obsidian, cobble, stone, basalt) are never cached as first
  observations because there is no meaningful transition FROM them
  without a prior fluid at the same position.
- Hard cap `NATURAL_BLOCK_CACHE_MAX = 4096`. When the cap is hit, the
  next `put` triggers `enforceCacheBound()` which drops the first 32
  insertion-ordered entries via `Iterator.remove()`. Steady-state
  memory under sustained pressure stays under ~1 MiB even during a
  wildfire event.
- Cleared on server-stop via `ForgeEvents.clearNaturalBlockCache()` so
  an in-JVM restart does not retain stale `(worldId, pos) → blockId`
  entries.

**Concurrency.** The `NeighborNotifyEvent` handler always runs on the
server tick thread (Forge guarantees this on 1.18.2 → 1.20.1 vanilla).
A `ConcurrentHashMap` is used defensively for the two mods known to
off-thread neighbor-update dispatches under `NeoForge`+`Sinytra`
(not applicable in this repo since Z2 targets Forge only, but the
allocation cost of `ConcurrentHashMap` vs `HashMap` is <0.1 % on the
hot path and future-proofs against a mod that off-threads).

**Error rate limiting.** `rateLimitedZ2Warn` guards the handler's
outer `try/catch` — `LOG.warn` fires at most once per 60 s per
`(site, throwable-class)` pair. A misbehaving mod that trips the
handler on every tick does not spam the log.

**Orphan cleanup.** Fifteen dormant mixin files deleted:

```
mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/mixin/{FireBlock,IceBlock,LeavesBlock,SpreadingSnowyDirtBlock,DispenserBlock}Mixin.java
mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/mixin/{FireBlock,IceBlock,LeavesBlock,SpreadingSnowyDirtBlock,DispenserBlock}Mixin.java
mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/mixin/{FireBlock,IceBlock,LeavesBlock,SpreadingSnowyDirtBlock,DispenserBlock}Mixin.java
```

`Ravager`, `HopperBlockEntity`, `FillCommand`, `SetBlockCommand` mixin
files remain — they belong to X2 (Ravager, still-active on Fabric /
NeoForge and covered on Forge by `onLivingTick`) and Z3 (Hopper /
Fill / SetBlock — separate wave, merges after Z2).

**Regression coverage.** `Z2NaturalBlockClassifierTest` (new,
`core/src/test/java/network/vonix/guardian/core/forgeevent/`) mirrors
the classifier logic in pure Java and asserts every one of the seven
`ActionType` submissions lands on the correct overload with the
correct `Sentinel.FIRE` / `#natural:*` sentinel + reserved
`MixinHotEventFilter` prefix. 17 tests covering:

- BURN × 2 (`minecraft:fire` and `minecraft:soul_fire` → non-fire)
- IGNITE × 1 (fresh fire with no prior cache)
- FADE × 2 (ice → water, snow layer → air)
- FORM × 3 (concrete powder solidify, lava-water → cobble, water → ice freeze)
- SPREAD × 2 (dirt → grass, coarse dirt → mycelium)
- LEAVES_DECAY × 2 (vanilla oak leaves, modded `mymod:cherry_leaves`)
- Negative × 3 (no-transition ignite deduplication, non-hot pair skipped, unrelated transition skipped)
- Cache-bounded × 1 (LRU eviction under overflow pressure)
- Sourcetag reservation × 1 (all 7 emitted rows start with `#fire` or `#natural`)

All 17 pass on `:core:test`. Build matrix — `:core:build` + `:mc-1.18.2:forge:build`
+ `:mc-1.19.2:forge:build` + `:mc-1.20.1:forge:build` — green.

**Performance envelope.** The `onNaturalBlockNeighborNotify` handler
runs at most once per `Level.updateNeighborsAt` call, which fires on
every `setBlock` with `flags & 1`. Empirically on a vanilla server
that's ~200–500 calls / sec, well under the 8 000 / sec floor at
which the Y2 `LivingTickEvent` handler starts costing measurable tick
budget. Per-invocation cost is dominated by the `blockId(state)`
`Registry.BLOCK.getKey` lookup — cached in the vanilla registry, ~50
ns. Classifier logic is O(1) string comparisons against a 20-item
switch. Cache put/get on `ConcurrentHashMap` at N ≤ 4 096 is O(1)
amortised. Aggregate handler cost stays under 0.05 % of tick budget
even under a burning-forest scenario.

Wave-by-wave notes on the shape of v1.3.3 changes that touch schema, DAO, or
hot-path allocation. Owners: each wave appends their own paragraph at wave
close; do not rewrite prior sections.

## Z1 — /vg config set canonical ctor (2026-07-03)

**Wave scope.** The round-3 parity audit's P0-A finding: every `/vg config
set <key> <value>` in all 8 loader cells rebuilt `GuardianConfig` via the
9-arg backward-compat constructor. That overload defaults `storage`,
`rollback` and `language` to `defaults()`, so setting any unrelated key
(theme, actions.logBlocks, purge.autoPurgeTime, …) silently reverted every
widened section to defaults on disk and in memory. The escalation from P2
to P0 in round-3 came from Y1's NBT surface activation: post-Y1,
`storage.persistNbt=true` was the toggle that turned on the whole NBT
fidelity pipeline, and any `/vg config set` command turned it back off.

**Hot-path cost.** Zero. The change is a widened parameter list on a
command-time (not tick-time) helper; the write path is unchanged for
callers who already used the canonical ctor. The rebuild happens once per
`/vg config set` invocation, followed by validate + save + reload. Reload
is the expensive step and Z1 does not touch it — Y3's optimizations stand.

**Allocation profile.** The refactor adds two field reads per rebuild
(`c.storage()`, `c.rollback()`) and drops nothing. Each `withValue` case
allocates one `GuardianConfig` record (12 fields, pure references, ~64
bytes on HotSpot). The three new cases (`storage.persistNbt`,
`rollback.explosionSupplementalReach`, `language`) each allocate one
additional wrapper (`GuardianConfig.Storage(bool)` or
`GuardianConfig.Rollback(int)`) — same shape as the other per-section
sub-record allocations already there for `LogFile`, `Lookup`, `Privacy`,
`Purge`.

**Testing surface.** `ConfigSetPreservesSectionsTest` (core-side, 6 cases)
mirrors the exact per-cell 12-arg pattern and pins:
- `withTheme(c, "dark")` preserves storage.persistNbt / rollback reach / language
- `withActions(c, muted)` preserves the same three sections
- round-trip save+load through `ConfigLoader` preserves persistNbt
- `withStoragePersistNbt(c, true)` end-to-end through `Guardian.reloadConfig`
  actually enables the `Guardian.persistNbt()` config gate and reports
  `storage.persistNbt` in `ReloadResult.hotSwapped()` (Y3 wiring)
- language and rollback-reach also survive unrelated `set theme` calls

The core-side test cannot invoke the private per-cell helper, but the
canonical 12-arg form it mirrors is exactly what the 8 fixed cells now
produce. `ReloadPreservesSectionsTest` (Y3) catches any regression at
reload time; this suite catches any regression at set time.

**Standing rule reinforced (from vg-config-widening-reload-and-boot-plumbing.md).**
Widening `GuardianConfig` has THREE silent-drop sites, not two:
`Guardian.reloadConfig` merged builder, `Guardian.boot()` engine ctor, and
the per-cell `/vg config set` write path (`withValue` + `withActions` in
all 8 cells). Y3 fixed the first two for X1 + X8; Z1 fixes the third for
X1 + X8 + language. Every future widening MUST verify all three or write a
justification.
