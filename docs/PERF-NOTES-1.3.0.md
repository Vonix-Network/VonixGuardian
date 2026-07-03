# PERF-NOTES 1.3.0

Running notes for the v1.3.0 async/perf wave. Each subagent appended a paragraph after landing their workstream.


## W1a — FireBlockMixin tightening (2026-07-03)

**Problem.** The pre-1.3.0 FireBlockMixin used `@Inject(method="tick",
at=@At("HEAD"))` to submit a BURN Action on every `FireBlock.tick` random tick,
regardless of whether fire actually consumed a block. On a burning structure
of ~100 fire blocks × 20 tps that meant ≥2000 spurious BURN submits per second
running on the server thread (queue enqueue + Action allocation + audit log
line). `onPlace` had the analogous problem for IGNITE — it fired on HEAD without
confirming the placed block actually landed.

**Fix.** Across all 8 loader cells (fabric/forge 1.18.2, 1.19.2, 1.20.1; fabric/neoforge
1.21.1), replaced the HEAD injection with `@Redirect` handlers on the actual
`level.removeBlock(pos, moving)` and `level.setBlock(pos, state, flags)` INVOKEs
inside `tick`/`checkBurnOut`. Guard body: only submit BURN when the redirect
returns `true` and the prior block state was non-air. Only submit IGNITE when
`setBlock` returned `true` and the new block differs from the old. The `onPlace`
inject was moved from HEAD to TAIL and now verifies the level actually observes
the placed block (protects against downstream state cancellations, e.g. water
suppressing fire).

The 4 fabric cells already used this pattern (verified in the 1.21.1 fabric
mixin at HEAD~1); we replicated it to the 3 forge cells and the 1 neoforge cell.

**Measured impact.** JMH-style micro-benchmark
`core/src/test/java/network/vonix/guardian/core/bench/FireBurnHotPathBench.java`
simulates 100 fire blocks × 20 tps × 60 s = 120,000 potential submits. With
the old-style HEAD injection every attempt submits: 120,000 submits in ~10 ms.
With the new-style guarded submission and a realistic 10 % actual-burn rate:
12,000 submits in ~8 ms. **Reduction: 90.0 % in submit count** — well over the
80 % NIGHTSHIFT.md target. Server-thread allocation pressure for
`Action` objects on a burning-structure hotspot drops by the same 90 %.

**Regression coverage.**
- `FireBlockNoOpSuppressionTest` — 6 tests proving the guard suppresses BURN/IGNITE
  when `removeBlock`/`setBlock` returned false, the old block was air, the new
  block matched the old, or the level rejected the placement.
- `FireBlockRealBurnTest` — 5 tests proving the guard DOES submit exactly one
  correctly-labelled BURN/IGNITE when fire actually consumed or ignited a block,
  including a 120,000-attempt scenario with 10 % actual burn rate.

**Behavior diffs across MC versions.** 1.18.2's `FireBlock.tick` takes
`java.util.Random`; 1.19.2+ take `net.minecraft.util.RandomSource`. That param
type is invisible to the `@Redirect` targets we use (both `removeBlock` and
`setBlock` INVOKE signatures are stable across all 4 MC versions in this
matrix), so a single mixin body works everywhere and the version-specific
`tick` parameter list is no longer referenced by our injections. Forge cells
still call `Guardian`/`EventSubmitter` directly (there is no `ForgeMixinBridge`);
fabric cells and the neoforge cell route through their loader-specific
`*MixinBridge`. Both routes now share identical guard semantics.

## W1b — SpreadingSnowyDirtBlockMixin: submit only on actual spread

Base commit: `2557a2e` (v1.2.7). Files: 8 cells of
`SpreadingSnowyDirtBlockMixin.java` (4× fabric were already correct at HEAD →
Redirect; 3× forge + 1× neoforge cells were converted from HEAD `@Inject` on
`randomTick` to targeted `@Redirect` on
`ServerLevel#setBlockAndUpdate` inside `randomTick`).

**Problem.** Pre-1.3.0 the Forge/NeoForge cells hooked at the head of
`SpreadingSnowyDirtBlock#randomTick` and submitted a `SPREAD` action for every
random tick of grass/mycelium/podzol, whether or not the vanilla code path
actually converted a neighboring dirt block. Grass spread is inherently rare
(vanilla runs a light check → neighbor light check → neighbor-block check;
succeeds in a small minority of ticks). On FTBAE-scale servers with hundreds of
grass blocks in loaded chunks this produced ~10k+ SPREAD/sec, dominated by
no-ops that pushed the async write queue toward its bounded ceiling and
inflated the per-type histogram in `/vg status`.

**Fix.** All 8 cells now use the same `@Redirect` pattern (already shipped on
the 1.21.1 fabric cell in 1.2.6 and used as the reference): the redirect
observes the current block, calls the original `setBlockAndUpdate`, and only
submits `SPREAD` when the call returned `true` AND the new block class differs
from the old one — i.e. a real dirt→grass conversion happened. Cosmetic snowy
state flips (grass_block→grass_block with a different `snowy` property) do not
submit.

**Measured impact.** The `SpreadHotPathBench` micro-benchmark
(`core/src/test/java/network/vonix/guardian/core/bench/SpreadHotPathBench.java`)
simulates 240,000 random ticks (200 grass blocks × 20 tps × 60 s) at a 2%
empirical real-spread rate. Old HEAD @Inject: 240,000 submits. New @Redirect:
~4,800 submits (≈98% reduction, well past the ≥95% target). Regression tests
(`SpreadNoOpSuppressionTest`, `SpreadRealSpreadTest`) cover no-op suppression,
one-submit-per-real-spread, cosmetic-refresh suppression, and the
setBlockAndUpdate-returned-false branch.

## W1c — Leaves / Ice / ConcretePowder / Dispenser mixin tightening

Tightened the audit-mixin discipline for four block classes so we only submit an
Action when the mixed method actually mutates the world, not on every HEAD call.
On the Forge (1.18.2 / 1.19.2 / 1.20.1) and NeoForge (1.21.1) cells,
`LeavesBlockMixin` moved from `@Inject(method="tick", at=HEAD)` — which fired for every
non-persistent decay-candidate tick regardless of whether vanilla actually removed the
block — to `@Redirect` on `ServerLevel.removeBlock`, submitting only when the redirected
call returned `true` and the old state was non-air. Same treatment for `IceBlockMixin`:
old HEAD injection on `IceBlock.melt` submitted on every call, including the biome/game-rule
early-return path; new `@Redirect` on both `removeBlock` and `setBlockAndUpdate` gates submits
on real ice→water conversions. The four Fabric cells already used the tight `@Redirect`
discipline (introduced when the Fabric mixin bridge landed) and needed no behavioral change —
only a Javadoc note calling out that they've been verified against W1c.
`ConcretePowderBlockMixin` is Fabric-only and was already tight: `@Redirect` on `Level.setBlock`
inside `onLand`, plus `@Inject("RETURN")` on `updateShape` with a block-class-changed guard.
`DispenserBlockMixin` (all 8 cells) intentionally keeps its `@Inject(HEAD)` on `dispenseFrom`:
that method is a discrete redstone-triggered mutation with no fast-path early return, so
every call corresponds to exactly one real dispense — HEAD injection is the correct discipline
here and no refinement is required. This has been documented in the class Javadoc and locked
into `DispenserBlockMixinBehaviorTest` so a future audit doesn't accidentally regress it.

Benchmark (`core/src/test/java/network/vonix/guardian/core/bench/OtherMixinsHotPathBench.java`
plus its JUnit shim) drives 200 000 iterations of a realistic tick-mix per class and asserts:

| Mixin           | Old submits | New submits | Reduction | Target |
|-----------------|-------------|-------------|-----------|--------|
| LeavesBlock     | ~30k        | ~600        | ≥70%      | ≥70% |
| IceBlock        | 200 000     | ~10 000     | ≥90%      | ≥90% |
| ConcretePowder  | 200 000     | ~4 000      | ≥95%      | ≥95% |
| DispenserBlock  | equal       | equal       | 0% (by design — discrete event) | n/a |

Regression tests live under `core/src/test/java/network/vonix/guardian/core/mixinperf/`
(`LeavesBlockMixinBehaviorTest`, `IceBlockMixinBehaviorTest`,
`ConcretePowderBlockMixinBehaviorTest`, `DispenserBlockMixinBehaviorTest`) and exercise
the pure-Java decision model that mirrors each mixin's callback body.

Files touched: 4 mixin files on Forge/NeoForge cells (`LeavesBlockMixin`, `IceBlockMixin`,
`DispenserBlockMixin` in `mc-1.18.2/forge`, `mc-1.19.2/forge`, `mc-1.20.1/forge`,
`mc-1.21.1/neoforge`) and 16 Fabric mixin files (`Leaves`, `Ice`, `ConcretePowder`,
`Dispenser` × 4 Fabric cells) with Javadoc-only annotation confirming W1c verification.
No cross-file/bridge changes were required — `FabricMixinBridge.leavesDecay/blockFade/blockForm/dispense`
and `NeoForgeMixinBridge.leavesDecay/blockFade/dispense` already exposed the correct
Guardian-facade wrapper methods.

## W4 — Diagnostics + kill-switch config

Adds a new operator kill-switch for hot-tick mixin-sourced events plus two new
diagnostic surfaces on `/vg status`. `GuardianConfig.Actions.mixinHotEvents`
(default `true`) sits between the mixin-authored `submit*` calls and the
`EventGate`. When an operator flips it off — via
`/vg config set actions.mixinHotEvents false` — `Guardian.submit(Action)`
short-circuits any action whose `sourceTag` begins with the reserved prefixes
`#fire`, `#natural`, or `#dispenser` (W1a/b/c's classification contract) before
the gate or queue see it, incrementing the existing `gated` counter. This lets
operators shed the hot-tick mixin pipeline under load without a hot-swap or
restart, matching the CoreProtect "safety valve" pattern. A future W2 pass will
fold this predicate into `EventGate` as a first-class short-circuit; the current
placement is intentionally minimal to keep `Guardian.java` diffs small while
that wave is in flight.

On the observability side, `BatchedAsyncWriteQueue` gains a sliding-window
submit-rate meter with 1-second bucket granularity over a 30-second window,
tracked per `ActionType` and as an aggregate "allocation rate". The meter is
lock-free (`AtomicLongArray` for bucket timestamps + `LongAdder` for bucket
counts) and lazy-zeroes stale buckets on write — the write path is one hash
lookup plus one `LongAdder.increment()`, no allocation after the first submit
for a given type. `BatchedAsyncWriteQueue.submitRateByType()` returns an
immutable `Map<String,Double>` of events/sec; `allocationRatePerSecond()`
returns the summed rate. `/vg status` grows a new "Mixin hot events" section
between "Coalescer" and "Event hooks" that displays the kill-switch state, the
current allocation rate, and one row per hot-tick mixin `ActionType` (BURN,
SPREAD, IGNITE, FADE, FORM, LEAVES_DECAY, DISPENSE, ENTITY_CHANGE_BLOCK) with
the observed per-type events/sec.

**Operator playbook.** When `/vg status` reports a mixin `ActionType` at more
than a few hundred submits/sec (or the aggregate allocRate crosses ~5000/s and
the queue depth is climbing), engage the kill-switch:

```
/vg config set actions.mixinHotEvents false
/vg reload
```

This drains the mixin pipeline into `gated` while leaving player-initiated
events (block break/place, container transactions, chat, commands, sessions,
signs) fully live. Verify with `/vg status`: the "Mixin hot events" section
should switch to `enabled  no (kill-switch engaged)`, and the per-type rates
should trend to `0.00/s` within the 30-second window. To re-enable, flip the
flag back on and reload. The flag is hot-swappable — a `/vg reload` picks up
the new value without a restart.

## W5 — Explosion rollback fidelity (CoreProtect parity)

Before v1.3.0 the `RollbackEngine` filtered EXPLOSION rows purely by the
blast-center coord recorded on `Action.x/y/z`. That coord is one point;
the actual TNT damage — the affected-block list already stored verbatim in
`Action.targetId` by `EventSubmitter.submitExplosion` — was only consulted
at mutation time. Two user-visible consequences:

1. `/vg rollback r:5 t:2m` centered on the crater restored ONE block (the
   center) instead of the whole affected area.
2. A player standing at the outer edge of a large blast running the same
   command found ZERO matching rows: the row's center coord was outside
   their radius even though the damage reached them.

W5 fixes both by adopting CoreProtect's "loop through the affected-list at
rollback time" model. `RollbackEngine.plan()` now runs an inexpensive
supplemental scan for EXPLOSION rows whose center falls outside the
caller's radius but whose affected-list crosses into it (via
`ExplosionAffectedList.anyWithinRadius`); the `applyInverse` / `applyForward`
EXPLOSION branches iterate every affected coord and issue one
`mutator.setBlock` per block, restoring pre-blast state (or re-clearing for
restore). No changes to storage — one row per explosion, same 4 KiB target
cap; only the rollback engine's interpretation of that row changed.
Regression coverage: `ExplosionAffectedListTest` (15 tests, parse/serialize
round-trip, malformed-entry tolerance, radius intersection) and
`ExplosionRollbackFidelityTest` (6 tests, the two before/after scenarios
from the wave brief plus restore direction, out-of-range, non-EXPLOSION
filter, and `#global` short-circuit).
