# NIGHTSHIFT.md — v1.3.0 async/perf wave

Base commit: `2557a2e` (v1.2.7). Target: v1.3.0.

## Scope

Address ALL findings from the 2026-07-03 async/perf audit:

- 3× P0 (real perf regressions on server thread)
- 4× P1 (measurable improvement not user-visible today)
- 3× P2 (minor cleanups)

Plus 2 workstreams for architecture:
- W4 — diagnostics + kill-switch config (new `actions.mixinHotEvents`)
- W5 — explosion rollback fidelity (Option A: rollback engine expands affected-list)

**Standing perf rules (WeedMeister 2026-07-03):**
- No shortcuts. Full codebase to best possible state.
- Zero allocation on server-thread hot paths where feasible.
- Fail-closed under load — bounded work per tick.
- Every P0/P1 gets a regression test.
- Benchmark harness before + after for mixin waves.
- CoreProtect semantics when applicable.

## Task table

| Wave | ID | Title | Files owned | Depends |
|---|---|---|---|---|
| A | W1a | FireBlockMixin tighten HEAD→RETURN + submit-on-actual-burn | `mc-*/*/src/main/java/**/mixin/FireBlockMixin.java` (8 cells) + `NeoForgeMixinBridge.java` / `FabricMixinBridge.java` (4 files) | — |
| A | W1b | SpreadingSnowyDirtBlockMixin tighten to actual spread | `mc-*/*/src/main/java/**/mixin/SpreadingSnowyDirtBlockMixin.java` (8 cells) + bridges | — |
| A | W1c | LeavesBlock/IceBlock/ConcretePowder/Dispenser mixin tighten (same pattern; smaller impact) | `mc-*/*/src/main/java/**/mixin/{LeavesBlockMixin,IceBlockMixin,ConcretePowderBlockMixin,DispenserBlockMixin}.java` | — |
| A | W4 | Diagnostics + kill-switch config | `core/config/GuardianConfig.java` (+ Actions record widening — 5-step recipe), `core/config/ConfigLoader.java`, `core/diagnostics/GuardianStatus.java`, `core/queue/BatchedAsyncWriteQueue.java` | — |
| A | W5 | Explosion rollback fidelity (Option A: expand affected-list on rollback) | `core/rollback/RollbackEngine.java` (explosion handling only), `core/action/Action.java` (parse helper), regression test | — |
| B | W2 | Server-thread allocation cuts | `core/Guardian.java` (submit + seed), `core/queue/BatchedAsyncWriteQueue.java` (pre-populate CHMs), `mc-1.21.1/neoforge/**/NeoForgeEvents.java` (SPAWN_LIMIT de-box) | A |
| B | W3 | Explosion chunking + EventGate fast-path | `mc-*/*/src/main/java/**/{NeoForge,Forge,Fabric}Events.java` (explosion off-thread), `core/Guardian.java` (submit fast-path), `core/event/EventGate.java` | B/W2 |

## Integration order

Wave A (parallel, 5 subagents): W1a, W1b, W1c, W4, W5.
Wave B (serial after A): W2, then W3.

Wave A subagents write to isolated file sets. W2 and W3 both touch `Guardian.java` → strict serial.

## Per-wave definition of done

Each subagent must:
1. Deliver the code changes.
2. Add JUnit regression tests in `core/src/test/java/**/`.
3. Add a JMH-style micro-benchmark harness (Java `main`-runnable, no JMH dep) in `core/src/test/java/network/vonix/guardian/core/bench/` verifying before/after allocation OR wall-time reduction.
4. Update `docs/PERF-NOTES-1.3.0.md` (new file, append) with a one-paragraph summary of the change + measured impact.
5. `./gradlew -PbuildProfile=coreonly :core:build` MUST pass.
6. For cell-touching waves also: `./gradlew -PbuildProfile=mc1211 :mc-1.21.1:neoforge:build -x test` MUST pass.

## Version bumps

`gradle.properties` `mod_version` → `1.3.0`.
`core/src/main/java/network/vonix/guardian/core/api/GuardianAPI.java` `PLUGIN_VERSION` → `"1.3.0"`.
`CHANGELOG.md` new `## [1.3.0] - 2026-07-03` under empty `## [Unreleased]`. Consolidated at wave close.

## Deferred (post-1.3.0)

- P1 (thread-local ActionBuilder pool → Valhalla value-class) requires benchmarks first; scoped for 1.3.1.
- W3 EventGate off-thread refactor is intentionally minimal (fast-path bypass only); full move to worker thread deferred.
