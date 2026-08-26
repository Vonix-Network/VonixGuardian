# Threaded Horizons

Minecraft **1.18.2** Forge candidate `0.1.0-alpha.1` (mod ID `threadedhorizons`).

This is an independent MIT-licensed derivative of public concurrent chunk-management work. It is not an official RelativityMC, Yatopia, C2ME, or Fabric release. Runtime metadata, packages, resource IDs, and the archive name use the Threaded Horizons identity only. Exact source commits and copyright notices are in `NOTICE.md` and `LICENSE`.

No public homepage, issue tracker, CurseForge project, or download URL is claimed for this candidate.

## Supported target

| Item | Value |
| --- | --- |
| Minecraft | 1.18.2 |
| Loader | Forge |
| Forge | 40.3.11 (`1.18.2-40.3.11`) |
| Java | 17 |
| Mappings | ForgeGradle official mappings |
| Artifact | `threaded-horizons-mc1.18.2-0.1.0-alpha.1.jar` |

## Proven in this candidate

Local Java 17 tests cover the optional region-storage state machine (generations, tombstones, failure propagation, close admission, 100000-op last-write-wins contention), vanilla RegionFile save/reopen cycles, process-kill reopen of flushed region files, lock/scheduler contracts, official 40.3.11 mixin target presence, and mapped-class overwrite differentials (`docs/overwrite-differential.md`). Disposable dedicated-server live gates, when executed, write under `evidence/logs/sol006-gaps-001`. Those gates are candidate evidence only.

## Experimental or config-gated

- Optional `ioSystem.replaceImpl` storage backend (off by default)
- Threaded worldgen (host-dependent default)
- Enhanced autosave
- No-tick view distance
- Async scheduling (off by default)
- Global biome cache (hard-disabled)

## Intentionally excluded

- Java incubator-vector acceleration (source trees are absent)
- Fabric companion mixins for BetterEnd, BetterNether, Charm, Terra, and The Bumblezone
- bStats
- 1.18.2 Beardifier ThreadLocal rewrite (Beardifier is constructed per chunk)

## What is packaged

The candidate ports the 1.18.2 functional tree into a Forge source set, including worldgen locking, chunk I/O mixins, lighting, scheduling, no-tick view distance, thread-safety fixes, math/allocation optimizations, commands, and fail-closed companion hooks. Packaging a class is not the same as proving the family.

This candidate is an alpha. Back up worlds. It is not production-ready and does not claim 1:1 C2ME parity or zero data loss.

## Install

1. Install Java 17 and Minecraft Forge **40.3.11** for 1.18.2.
2. Copy `build/libs/threaded-horizons-mc1.18.2-0.1.0-alpha.1.jar` into the instance `mods/` folder.
3. Start the client or dedicated server once so `config/threadedhorizons.toml` is created.
4. Back up worlds before using an alpha candidate.

## Build from source

```shell
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew clean test build --no-daemon
```

The primary reobfuscated JAR is `build/libs/threaded-horizons-mc1.18.2-0.1.0-alpha.1.jar`.

## Configuration

See `docs/configuration.md`. Config path: `config/threadedhorizons.toml`.

Commands: `/threadedhorizons` and `/th` (`notick`, `status`, development `debug mobcaps`).

## Known limitations

See `docs/limitations.md`.

## License

MIT. See `LICENSE` and `NOTICE.md`.
