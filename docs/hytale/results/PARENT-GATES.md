# Hytale port — parent gate record

Candidate: `hytale/build/libs/vonixguardian-hytale-2.2.1.jar`
**Active candidate sha256: `90e0da3cce691c88df0615fb5302cc01bd94aebfe800df4b4514434a06bb978f`** (successor: includes the world-cell rotation-merge repair). The pre-repair jar (`f8f1237` tree) is superseded and its gates do not transfer.
Base pin: `81a4bdb` + port commits `fd2608b`, `5cc47f8`, `10a2de8`, `f8f1237` (+ successor pass)
Toolchain: JDK 25.0.4.1, Gradle 9.2.0, `com.hypixel.hytale:Server:0.7.4`

## Gate results

| Gate | Result | Evidence |
|---|---|---|
| Per-cell compile (C1 capture) | PASS | `COMPILE_GATE=PASS classes=12` (worker + parent re-run) |
| Per-cell compile (C2 world) | PASS | `COMPILE_GATE=PASS classes=11` (parent re-run; worker exited at turn cap before writing its result file) |
| Per-cell compile (C3 command) | PASS | `COMPILE_GATE=PASS classes=26` |
| Per-cell compile (C4 platform) | PASS | `COMPILE_GATE=PASS classes=16` |
| Full build + packaging | **BUILD_VERIFIED** | `gradle :hytale:build -PbuildProfile=hytale` → BUILD SUCCESSFUL, 525 classes, 14.97 MB jar |
| Jar layout | PASS | `manifest.json` at jar root with correct `Version` expansion; `VonixGuardianPlugin.class`, all four cells, `spi/`, `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` present; `slf4j`, HikariCP, sqlite, `core/` bundled |
| Manifest parse by a real Hytale server | **MANIFEST_PARSE_VERIFIED** | Real server (`com.hypixel.hytale.Main`, server jar 0.7.4) booted `--bare --auth-mode offline`; log: `[PluginManager] Loading pending plugins from directory: mods` → `[PluginManager] - Vonix:VonixGuardian from path vonixguardian-hytale-2.2.1.jar` |
| Plugin instantiation / `setup()`+`start()` | **NOT VERIFIED** | Server shut down before enabling: `[HytaleServer] Shutting down... 7 client.disconnection.shutdownReason.missingAssets.failedToLoad`. Requires a licensed `HytaleAssets` directory. |
| Block I/O runtime round-trip | NOT VERIFIED | Compile-only; needs a live world |
| Command surface at runtime | NOT VERIFIED | Same |

## How the boot attempt was run (reproducible)

```bash
cd C:/Users/Pargon/DEV/_hytale-deps
mkdir -p loadtest/mods && cp .../vonixguardian-hytale-2.2.1.jar loadtest/mods/
cd loadtest
"C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/java" \
  -jar ../Server-0.7.4.jar --bare --auth-mode offline --assets ../srv-probe/HytaleAssets 2>&1 | tee server.log
```
`cdn.hytale.com/HytaleServer.jar` and `cdn.hytale.com/Assets.zip` both return **404** — assets cannot be fetched publicly; they ship with a licensed game install.

## Honest state

- The plugin's identity, manifest, packaging and bytecode are accepted by a real server's plugin loader.
- No server-side code of ours has executed yet. Every behavioural claim (capture, rollback fidelity, permissions) is compile-verified only.

## Next gate (blocked on owner)

Copy `<Hytale install>/HytaleAssets` (or the `--assets` directory the launcher provisions) onto a machine I can reach, then re-run the boot above **without** `--bare`. That converts `MANIFEST_PARSE_VERIFIED` → `PLUGIN_LOAD_VERIFIED` and lets `/guardian status` be exercised for real.

## Live boot — PLUGIN_LOAD_VERIFIED (2026-10-22 23:01, real 0.7.4 server, licensed assets)

Server: launcher-installed `HytaleServer.jar` 0.7.4 (`c9581a2`), `--assets <launcher Assets>` (extracted),
`--auth-mode offline`, `--bind 0.0.0.0:5520`, `--allow-op`, `--boot-command "guardian status"`.
Root: `C:\Users\Pargon\DEV\hytale-testserver`. Plugin jar sha256 `469a328842e9f8d020435fd9c41eb4e62d40bc51d2cb5525d9382514531ae81e`.

```
[PluginManager] - Vonix:VonixGuardian from path vonixguardian-hytale-2.2.1.jar
[VonixGuardianPlugin] setup: data dir = mods\Vonix_VonixGuardian
[Guardian] Booting VonixGuardian v2.2.1 (db=sqlite, theme=aqua, queue.max=50000)
[Guardian] VonixGuardian online.
[VonixGuardianPlugin] started: config=mods\Vonix_VonixGuardian\config.json
[PluginManager] Enabled plugin Vonix:VonixGuardian
[CommandManager] Console executed command: guardian status
[HytaleServer] Booted! [Multiplayer] took 1min 32sec
```
`/guardian status` output: version 2.2.1 · storage sqlite · schema v9 · health OK · writer consumer running · depth 0/50000.
`[capture] Hytale capture listening for player break/place and environment break`.

**Defect found and fixed at the load gate**: `No suitable driver found for jdbc:sqlite` — `DriverManager`
only service-discovers on the system classloader, so a plugin-classpath driver is invisible. Fixed by
explicit `Class.forName(driver, true, <plugin loader>)` before `Guardian.boot` (same rule the Minecraft
lanes already encode in `NeoForgeBootstrap.ensureDatabaseDriver`). This is exactly the class of defect a
compile/package gate cannot catch, and why the boot gate is mandatory.

Still unverified after this boot: block/inventory capture rows (needs a player action), rollback/restore
fidelity, per-player permission enforcement (console bypasses it).
