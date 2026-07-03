# PERF-NOTES-1.3.3.md

Wave-by-wave notes on the shape of v1.3.3 changes that touch schema, DAO,
event bus wiring, or hot-path allocation. Owners: each wave appends their
own paragraph at wave close; do not rewrite prior sections.

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
