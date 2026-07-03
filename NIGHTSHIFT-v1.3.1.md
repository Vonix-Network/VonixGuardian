# NIGHTSHIFT-v1.3.1.md — parity + perf close-out

Base commit: `99bb9e9` (v1.3.0 integration HEAD). Target: v1.3.1.

## Scope

Close v1.3.0's audit findings:
- 3 P0 parity gaps (entity-block-change coverage, NBT fidelity, fluid-flow attribution)
- 7 P1 parity gaps (TNT-prime, hopper/portal producers, /fill //setblock mixins, auto-purge daemon, WorldEdit bridge, decimal/range time)
- 3 P1 perf items (HikariCP prep-stmt cache, DamageHistory eviction, W5 supplemental spatial bound)
- Perf P2/P3 polish (shutdown ordering, reload atomicity, misc)

## Waves

| Wave | Title | Priority | Files owned | Depends |
|---|---|---|---|---|
| X1 | NBT fidelity schema V5 + producers | P0 | Schema.java, MigrationV5, Action.java, ActionBuilder.java, Guardian.seed, 8 WorldMutator cells | — (schema atomic) |
| X2 | Entity-caused block-change dedicated mixins | P0 | EnderDragonMixin, RavagerMixin, SnowGolemMixin, FallingBlockEntityMixin, LightningBoltMixin, SilverfishMixin (8-cell recipe), EntitySentinel additions | — |
| X3 | Fluid-flow attribution producer | P0 | LiquidBlockMixin (8-cell), Sources.FLUID + #fluid sentinel, ActionType.FLUID_FLOW, RollbackEngine admit path | — |
| X4 | Hopper + Portal producers + /fill //setblock command mixins | P1 | HopperBlockEntityMixin, PortalShapeMixin, FillCommandMixin, SetBlockCommandMixin (8-cell), submitHopperPush/Pull producer wiring | — |
| X5 | Auto-purge daemon + query parser polish | P1 | AutoPurgeScheduler.java, GuardianConfig.retention, QueryParser (decimal + range time + WE bridge), GuardianSuggestions align | — |
| X6 | HikariCP tuning + DAO polish + shutdown ordering + reload atomicity + P2/P3 perf polish | P1/P2/P3 | MysqlDao, PostgresDao, StorageFactory, GuardianConfig.hikari, DamageHistory eviction, EntityBlockChangeCoalescer eviction, Guardian.shutdown ordering, Guardian.reloadConfig atomicity, JsonLinesLogFile toggle, PerWorldConfigStore mtime cache, EventGate internalHooks cap, NeoForgeEvents.WORKER shutdown, Guardian.submitExplosion pooling, RollbackEngine hasMoreRows PAGE_SIZE+1, resolveUserOn last_seen amortization | — |
| X7 | TNT-prime attribution | P1 | TntBlockMixin + PrimedTntEntityMixin (8 cells), Attribution resolver extension | — |
| X8 | W5 supplemental spatial bounding fix | P1 (perf) | RollbackEngine.supplementExplosions | — |
| X9 | Container coverage widening + BaseContainerBlockEntityMixin | P2 | BaseContainerBlockEntityMixin (8 cells), ContainerMixin widening | — |

## Parallelization

All 9 waves are file-disjoint EXCEPT:
- X1 touches Guardian.seed / Action model — X4/X7 producers likely need `seed()` calls that must match X1's new signature. **Solution: X1 goes first solo (30-45 min); rest parallel behind it.**
- X6 touches Guardian.java (shutdown + reload atomicity). X1 also touches Guardian.seed. **Solution: X1 signals when Guardian.java is stable, X6 rebases onto X1.**

Realistic wave shape:
- **Wave X-α**: X1 alone (schema + NBT bones)
- **Wave X-β**: X2, X3, X4, X5, X7, X8, X9 in parallel (7 subagents)
- **Wave X-γ**: X6 last (touches Guardian.java that others may have edited)

## Definition of done per wave

Same as v1.3.0:
1. Code changes.
2. JUnit regression tests.
3. Benchmark for perf-critical items.
4. Append to docs/PERF-NOTES-1.3.1.md (new file).
5. Use vg_wave_status tracker: init/start/complete/heartbeat.
6. Do NOT commit until all tasks tracked complete.
7. `:core:build` must pass.

## Version bumps

`mod_version` 1.3.0 → 1.3.1, `PLUGIN_VERSION` → `"1.3.1"`, `CHANGELOG.md` `## [1.3.1]` section. Owner: X6 (touches Guardian.java last).

## Post-v1.3.1

- Fresh audit run against merged HEAD
- If clean: cut release, single fleet deploy, embed to public channel
- If P0/P1 surface: v1.3.2 loop
