# PERF-NOTES-1.3.0.md

Per-wave performance notes for VonixGuardian 1.3.0. Each subagent appends a
paragraph summarising its change and measured impact.

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
