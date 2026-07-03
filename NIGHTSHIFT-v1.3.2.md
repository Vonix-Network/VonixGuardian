# NIGHTSHIFT-v1.3.2.md — v1.3.1 defect close-out

Base commit: `343153f` (v1.3.1 integration HEAD). Target: v1.3.2.

## Scope

Close v1.3.1's audit findings:
- 4 P0 defects (NBT dead surface, forge mixin dormancy for X3/X2/X4/X7, reload constructor bug)
- 4 P1 items (X8 dead knob, TntPrimeMemory O(n), FluidSourceMemory O(n), PrimeRecord API waste)
- P2 items (RollbackEngine setter, AutoPurgeScheduler apply-config, restart-list additions)
- P3 items (HopperBlockEntityMixin slot cache, orphan Forge mixin file cleanup)

## Waves

| Wave | Title | Priority | Files owned |
|---|---|---|---|
| Y1 | NBT surface activation | P0 | RollbackEngine (NBT branch), 8 WorldMutator impls (override NBT overloads), all 8 Events cells (producer NBT capture) |
| Y2 | Forge cell parity via event bus | P0 | 3 ForgeEvents.java (BlockFromToEvent + tnt-prime capture + entity-change via LivingEvent) |
| Y3 | Reload+boot config plumbing | P0 | Guardian.reloadConfig canonical ctor, RollbackEngine setter/volatile, AutoPurgeScheduler.applyConfig, hot/restart list |
| Y4 | X8 wire-through | P1 | Guardian.boot line 222 + pin test |
| Y5 | Attribution memory amortization | P1 | TntPrimeMemory.hardEvict amortize, FluidSourceMemory.lookup fast-path when empty |
| Y6 | HopperBlockEntityMixin slot cache | P3 | 8 cells' HopperBlockEntityMixin |
| Y7 | FluidSourceMemory shutdown clear + PrimeRecord API cleanup + queue interrupt | P2 | Guardian.shutdown, TntPrimeMemory API, BatchedAsyncWriteQueue.setPaused |
| Y8 | Orphan Forge mixin file decision | P3 | Deleted (they compile but never fire; superseded by Y2's event-bus approach) |

## Parallelization

Y3 owns Guardian.java's reload path exclusively. Y1 also touches Guardian.java for producer wiring but the seams are in separate methods (submit path vs reload path). Careful serialization:

- **Wave Y-α (solo, first)**: Y1 (NBT surface — largest, unblocks parity claim)
- **Wave Y-β (parallel)**: Y2 (forge event-bus), Y4 (X8 wire), Y5 (memory amortize), Y6 (hopper cache), Y7 (hygiene)
- **Wave Y-γ (solo, last, merge fold)**: Y3 (reload plumbing — sees Y1's NBT + Y5's memory changes)
- Y8 folds into Y2 as cleanup task

## Version bump

`mod_version` 1.3.1 → 1.3.2. Owner: Y3.

## Definition of done per wave

1. Code + tests + benchmark (where applicable).
2. vg_wave_status tracker init/start/complete/heartbeat.
3. Append to docs/PERF-NOTES-1.3.2.md.
4. `:core:build` + `:mc-1.21.1:neoforge:build` must pass.
5. Y1/Y2 additionally must pass all 4 loader cell builds.

## Post-v1.3.2

- Fresh round-3 audit
- If clean → cut release, single fleet deploy, embed
- If P0/P1 surface → v1.3.3 loop
