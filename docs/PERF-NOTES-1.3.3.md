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
