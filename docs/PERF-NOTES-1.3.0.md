# PERF-NOTES-1.3.0

Perf/allocation notes for the v1.3.0 async/perf wave. One paragraph per subagent-owned
change. Wave A subagents append; the wave lead consolidates before release.

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
