# C4 — Hytale platform cell (candidate worker brief)

You are a bounded candidate worker. Parent = Hermes (Venix), owns acceptance. You implement ONLY `hytale/src/main/java/network/vonix/guardian/hytale/platform/**` in `C:\Users\Pargon\DEV\vonixguardian-hytale`.

## Read first
1. `docs/hytale/HYTALE-PORT-CONTRACT.md` (pin, verified API facts, gates, output contract).
2. `hytale/src/main/java/network/vonix/guardian/hytale/spi/{GuardianServices,PlayerDirectory}.java`.
3. `core/src/main/java/network/vonix/guardian/core/config/ConfigLoader.java`, `core/.../perms/OpLevelFallback.java`, `core/.../Guardian.java` (`boot(...)` signature + `MARKER`).
4. Reference: `mc-26.3/fabric/src/main/java/network/vonix/guardian/mc/v26_3/fabric/{FabricBootstrap,FabricOpLookup}.java`.

## Mission
Platform plumbing the bootstrap needs — no business logic:
- `platform/HytaleConfigLoader` — resolve the plugin data directory (`PluginBase#getDataDirectory()`), load `GuardianConfig` via core `ConfigLoader` from `<dataDir>/config.json`, create the directory tree, and return a clear failure when the file is unreadable (never silently default to a lossy config without logging it).
- `platform/HytaleOpLookup implements core.perms.OpLevelFallback` — decide operator level from Hytale permission data (`PlayerRef#hasPermission`, `PermissionsModule`) without inventing an op-level concept the platform lacks; document the mapping.
- `platform/HytalePlayerDirectory implements spi.PlayerDirectory` — `PlayerRef` lookups, offline name resolution via the server's player records if exposed; empty `Optional` when unknown. No caching that outlives a session.
- `platform/HytaleSlf4jBridge` — an `org.slf4j.spi.SLF4JServiceProvider` + `ILoggerFactory` implementation that routes SLF4J log calls into `HytaleLogger` (`getLogger().at(java.util.logging.Level.X).log(...)`), including a `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` resource under `hytale/src/main/resources/`. This is how `core/`'s logging reaches the server console.
- `platform/HytaleExecutors` — writer `ThreadFactory` (daemon, named `VonixGuardian-Writer`) and a server-thread `Executor` built from `World#execute` with world affinity (the engine expects main-thread landing).
- `platform/HytaleServices` — the concrete `GuardianServices` holder (immutable after construction, `isRunning` flag).

## Rules
- No dependency on capture/command/world packages (they depend on you, not the reverse) — compile-check your package alone and keep it that way.
- Never log or persist credentials. Config values stay out of logs (names/paths only).
- Do not modify `core/`, `spi/`, `build.gradle`, or `manifest.json`.
- Do not run `gradle`/`gradlew` (parent-owned).
- Verify signatures before use: `"C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/javap" -p -cp "C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar" com.hypixel.hytale.server.core.universe.PlayerRef` (also `...permissions.PermissionsModule`, `...plugin.PluginBase`, `...logger.HytaleLogger`).

## Gate you must run
```bash
cd /c/Users/Pargon/DEV/vonixguardian-hytale
bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/platform
```
`COMPILE_GATE=PASS` required.

## Deliverable
`docs/hytale/results/platform.md` in contract §6 format. State explicitly whether the SLF4J bridge is proven to load (it is a service-loader resource — if you cannot prove discovery without a running server, say `GAPS=service-loading unverified`).
