# Feature ledger

Statuses: `proven` (local tests exist), `experimental` (packaged and gated), `intentionally_excluded`.

| Family | Status | Evidence |
| --- | --- | --- |
| Optional replaceImpl storage | proven | `StorageStateMachineTest` (100000 last-write-wins ops), `VanillaRegionCycleTest`, `StorageCrashReopenTest`, `CrossFileConsistencyTest` |
| Official overwrite math/random | proven | `OfficialOverwriteDifferentialTest` vs mapped Forge 40.3.11; see `docs/overwrite-differential.md` |
| Async chunk load fail-closed | experimental | Mixin overwrite + unit types; live dedicated-server 100-cycle evidence is recorded under `evidence/logs/sol006-gaps-001` when that gate is executed |
| Dirty save generations | experimental | `ChunkMap.save` overwrite joins `IOWorker.store` |
| Worldgen locks / scheduler | proven | `LockAndSchedulerTest` |
| No-tick view distance | experimental | Official `tickChunks` redirects `getTickingChunk` and `getAllEntities` (javap-proven). The Fabric `getTickingChunkFuture` hook is an intentional exclusion: that invoke is in `isPositionTicking(long)`, not `tickChunks`. |
| Async scheduling | experimental | off by default; reads published `visibleChunkMap` |
| Global biome cache | intentionally_excluded | `useGlobalBiomeCache` is false; empty createBiomes mixin removed |
| Vector acceleration | intentionally_excluded | source trees absent |
| Fabric companions | intentionally_excluded | not compiled |
| Structure processor failsafe | experimental | Redirects official `SinglePoolElement.getSettings` `Holder.value()` (javap-proven). `place` has no such invoke. |
| Lithium-family NBT / chunk-access | proven | Skip `MixinNbtCompound*` when `lithium` or `canary` is present (`LithiumFamilyNbtMixinSkipTest`). Early `FMLLoader.getLoadingModList()` detection. Canary `getChunkOffThread` is the same blocking path as Lithium. |
| Stronghold / structure warmup | proven | Latch `hasGeneratedPositions` only after `generatePositions()` returns (`StrongholdGenerationSafetyTest`). Async warmup logs companion NPEs and retries. |
