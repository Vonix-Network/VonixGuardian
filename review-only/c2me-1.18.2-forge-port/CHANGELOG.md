# Changelog

## 0.1.0-alpha.1

- First Threaded Horizons candidate for Minecraft 1.18.2 / Forge 40.3.11 / Java 17.
- Ports the public 1.18.2 functional tree into a Forge source set.
- Runtime identity is `threadedhorizons` / `network.vonix.threadedhorizons` / `threaded-horizons`.
- Original 256×256 icon `threadedhorizons.png`.
- Optional replaceImpl storage uses per-position generations, exact store futures, tombstones, and vanilla RegionFile framing.
- Chunk load fails closed for present-but-unreadable data. Dirty generations stay until store completion.
- Vector acceleration and Fabric companion mixins are intentional exclusions.
- No public project URL, issue tracker, or CurseForge ID.
- Alpha only: back up worlds. No 1:1 parity or production-ready claim.
- No-tick `tickChunks` hooks target official `getTickingChunk()` and `getAllEntities()` only. A `getTickingChunkFuture` redirect is not registered because that invoke is not in `tickChunks` on Forge 40.3.11.
- Removed unused empty access mixins (`IAquiferSamplerFluidLevel`, `IStructureWeightSampler`, `IChunkGenerator`, `INbtCompound`, `IRegionFile`). Region I/O uses public `RegionFile` stream APIs.
- Failsafe `SinglePoolElement` processor-null hook redirects official `getSettings` `Holder.value()`, not `place`.
- `SimplifiedAtomicSimpleRandom` constructor now calls `setSeed`, matching official `LegacyRandomSource` mixing.
- `/threadedhorizons status` reports executor queue depths for disposable load gates.
- Mapped-class overwrite differentials live in `OfficialOverwriteDifferentialTest` and `docs/overwrite-differential.md`.
