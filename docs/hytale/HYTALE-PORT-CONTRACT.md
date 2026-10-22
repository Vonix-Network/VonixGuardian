# VonixGuardian → Hytale port — authoritative contract

Status: **CONTRACT FROZEN** (candidate phase). Parent = Hermes (Venix). Workers implement only.

## 1. Pin

| Item | Value |
|---|---|
| Repo | `Vonix-Network/VonixGuardian` (private) |
| Base commit (port base) | `81a4bdb2f7582b12dc988e99b8b245b5c6c183b6` (`main`, version `2.2.1`) |
| Port tree (canonical) | `C:\Users\Pargon\DEV\vonixguardian-hytale` |
| New module | `hytale/` (module path `:hytale`) |
| Build profile | `-PbuildProfile=hytale` |
| Toolchain | **JDK 25** (`C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot`) |
| Gradle | **9.2.0** (`C:/Users/Pargon/DEV/_hytale-deps/tools/gradle-9.2.0/bin/gradle`). The repo wrapper (8.10.2) MUST NOT be used for this lane. |
| Hytale server API | `com.hypixel.hytale:Server:0.7.4` from `https://maven.hytale.com/release` (published: 0.5.10, 0.6.9, 0.7.2, 0.7.3, 0.7.4) |
| API jar on disk | `C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar` (112 MB, 38 439 classes, not obfuscated) |
| API signature dumps | `docs/hytale-api/*.txt` (`javap -p` output — read these, do not re-derive) |

Engine (`core/`) is shared and **unchanged**: 137 files, zero real `net.minecraft.*` imports, Java 17 bytecode.

## 2. Verified Hytale API facts (from the 0.7.4 jar, not from blogs)

Plugin surface — `com.hypixel.hytale.server.core.plugin`:
- `JavaPlugin(JavaPluginInit init)`; abstract `PluginType getType()` is already final in `JavaPlugin`.
- Lifecycle hooks: `protected void setup()`, `protected void start()`, `protected void shutdown()`. (`setup0/start0/shutdown0` are the final internal wrappers — never override those.)
- From `PluginBase`: `getLogger()` → `HytaleLogger`, `getDataDirectory()` → `Path`, `getEventRegistry()`, `getCommandRegistry()`, `getChunkStoreRegistry()`, `getEntityStoreRegistry()`, `getTaskRegistry()`, `getBasePermission()`, `getManifest()`, `getState()`.
- `HytaleLogger` extends Google flogger: use `getLogger().at(java.util.logging.Level.INFO).log("fmt %s", arg)`.

Manifest — `manifest.json` at jar root, keys decoded from `PluginManifest.CODEC` constants:
`Group`, `Name`, `Version`, `Description`, `Authors[] {Name,Email,Url}`, `Website`, `Main` (FQCN), `ServerVersion` (SemverRange; `"*"` = wildcard), `Dependencies`, `OptionalDependencies`, `LoadBefore`, `SubPlugins`, `DisabledByDefault`, `IncludesAssetPack`.
Authoritative reference manifest: `hytale/src/main/resources/manifest.json`.

Events — `com.hypixel.hytale.server.core.event.events.ecs`:
- `BreakBlockEvent` (`getTargetBlock(): org.joml.Vector3i`, `getBlockType(): BlockType`, `getItemInHand()`), `PlaceBlockEvent` (`getTargetBlock()`, `getRotation(): RotationTuple`), `DamageBlockEvent`, `EnvironmentBreakBlockEvent`, `UseBlockEvent`.
- All extend `com.hypixel.hytale.component.system.CancellableEcsEvent`.
- **Verified**: `getEventRegistry().register(BreakBlockEvent.class, consumer)` compiles and is the registered path used by the scaffold.

ECS — `com.hypixel.hytale.component`: `Store`, `ComponentType`, `Component`, `Ref`, `CommandBuffer`, `Holder`, `Resource`, `ArchetypeChunk`. System bases: `com.hypixel.hytale.component.system.{System, EntityEventSystem, EcsEvent, CancellableEcsEvent, RefSystem, HolderSystem}`.

World / blocks:
- `com.hypixel.hytale.server.core.universe.world.World`: `execute(Runnable)` (server-thread hop), `getChunkStore()`, `getChunkLighting()`. There is **no** `setBlock`/`getBlock` on `World` — block I/O lives in `com.hypixel.hytale.server.core.universe.world.chunk.*` (`WorldChunk`, `BlockChunk`, `BlockOperations`, `ChunkColumn`, `BlockComponentChunk`, `AbstractCachedAccessor`, `CachedAccessor`) plus `com.hypixel.hytale.math.util.ChunkUtil`. Cell C2 owns discovering the exact read/write/rotation/extra-data signatures from the jar.
- `BlockType`: `com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType` (dump: `docs/hytale-api/BlockType.txt`, 498 lines).
- `RotationTuple`: `...blocktype.config.RotationTuple`.

Players:
- `com.hypixel.hytale.server.core.universe.PlayerRef` implements `CommandSender` and `PermissionHolder`: `getUuid()`, `getUsername()`, `hasPermission(String)`, `senderAsPlayerRef()` on `CommandContext`.

Commands: `com.hypixel.hytale.server.core.command.system.{AbstractCommand, CommandContext, CommandRegistry, CommandSender}`; `CommandContext.sendMessage(com.hypixel.hytale.server.core.Message)`; arguments via `...command.system.arguments.system.{RequiredArg, OptionalArg, Argument}`.

Server jar bundles gson (`com/google/gson`, 212 classes). It does **not** bundle slf4j or HikariCP — those are bundled by `:hytale` (see `hytale/build.gradle`).

## 3. Core SPI every cell must obey (do NOT modify `core/`)

- `Guardian.boot(GuardianConfig config, Path dataDir, WorldMutator mutator, OpLevelFallback opLookup, Executor mainThreadExec, ThreadFactory tf)`.
- `Guardian` facade accessors: `config()`, `dao()`, `queue()`, `gate()`, `perms()`, `rollbackEngine()`, `purgeEngine()`, `undoStack()`, `theme()`, `autoPurgeScheduler()`, `perWorldStore()`, `blacklistHook()`, `submitter()`, `api()`, `submit(Action)`, `submitAccepted(Action)`, `awaitQueueDrained(long)`, `close()`.
- Capture helpers on `Guardian`: `submitBlockBreak`, `submitBlockPlace`, `submitContainerChange`, `submitItemDrop`, `submitItemPickup`, `submitEntityKill`, `submitExplosion`, `submitPortalCreate` (see `Guardian.java` 1029-1110 for exact parameter lists).
- Rollback write path: `network.vonix.guardian.core.rollback.WorldMutator` — implement `trySetBlock(...)`, `trySetBlock(..., blockState, blockEntityNbt)`, `tryGiveOrDrop(...)`, plus the clear-to-air path. All called on the server thread.
- Action construction: `network.vonix.guardian.core.action.ActionBuilder` (fluent: `type`, `actorUuid`, `actorName`, `worldId`, `position`, `targetId`, `targetMeta`, `amount`, `oldBlockState`, `newBlockState`, `blockEntityNbt`, `build()`).
- Reference implementations to mirror (read before writing): `mc-26.3/fabric/src/main/java/network/vonix/guardian/mc/v26_3/fabric/{FabricBootstrap,FabricEvents,FabricWorldMutator,FabricOpLookup}.java` and `mc-26.3/common/.../{GuardianCommands,Inspector,LookupFormatter,ChatRenderer,WorldKey,SourceTagger}.java`.

Port-local SPI (frozen, owned by the parent, in `hytale/src/main/java/network/vonix/guardian/hytale/spi/`): `GuardianServices`, `WorldBlockAccess`, `BlockSnapshot`, `PlayerDirectory`. Nobody edits `spi/` — if a signature is genuinely wrong, report `PREMISE_BROKEN` with evidence instead of changing it.

## 4. Cell ownership (disjoint paths — never write outside your cell)

| Cell | Package root (under `hytale/src/main/java/network/vonix/guardian/hytale/`) | Mission | May read |
|---|---|---|---|
| C1 capture | `capture/` | Hytale events → core `Action`s: player block break/place, environment break, damage/fire/explosion attribution where the API exposes a causer, entity kill, container use. Idempotent, no double-logging, respects `Guardian.gate()`. | `core/`, `mc-26.3/**`, `docs/hytale-api/` |
| C2 worldio | `world/` | Implement `spi/WorldBlockAccess` + core `WorldMutator` for Hytale: read `BlockType` id + rotation + component data at a position, write it back, chunk load/unload handling, world-id ↔ Hytale world UUID mapping, clear-to-air semantics. | same |
| C3 commands | `command/` | `/guardian` command tree (`status`, `inspect`, `lookup`, `rollback`, `restore`, `purge`, `undo`, `reload`, `help`) using only `GuardianServices` + core engines; permission gates via `PlayerDirectory`/`Guardian.getBasePermission()`; message formatting via `Message`. | same |
| C4 platform | `platform/` | Bootstrap plumbing: config load (`ConfigLoader`) into the Hytale data dir, `OpLevelFallback` impl from Hytale permissions, `PlayerDirectory` impl, SLF4J→`HytaleLogger` bridge/provider, writer `ThreadFactory`, server-thread `Executor` from `World#execute`. | same |
| C5 bootstrap | `VonixGuardianPlugin.java` (parent) | Lifecycle wiring of C1–C4, `Guardian.boot`, shutdown drain. Written by the parent after C1–C4 land. | all |

**Forbidden for every cell**: `core/**`, `settings.gradle`, `build.gradle` (root or `hytale/`), `hytale/src/main/resources/manifest.json`, `spi/**`, `mc-*/**`, `docs/**`, `.github/**`, git commits/pushes, any `gradle`/`gradlew` invocation, any network call, any deploy/publish.

## 5. Gates

1. **Per-cell compile gate** (workers run this, it writes only to their own output dir):
   ```bash
   bash scripts/hytale-compile-check.sh <your-package-dir> [...]
   ```
   It compiles against the real Hytale jar + the prebuilt bundled classpath. Zero errors required.
2. **Parent build gate** (Hermes only): `gradle :hytale:build -PbuildProfile=hytale` → fat jar + no test failures.
3. **Plugin-load gate**: `PLUGIN_LOAD_UNVERIFIED` until a licensed Hytale server install with `Assets.zip` exists on a host we control. Do not claim the plugin loads.

## 6. Output contract (every worker writes this to disk)

```text
STATUS=CANDIDATE_COMPLETE|BLOCKED|NEEDS_ESCALATION|FAILED
CANDIDATE=<changed paths + file count>
EVIDENCE=<compile command + result, API signatures you verified from the jar>
PREMISES=<OK | list of brief premises that did not hold, with the real signature>
GAPS=<what is not implemented/proven>
EFFECTS=NOT_PERFORMED
```

Write it to `docs/hytale/results/<cell>.md` (that path is the ONLY file outside your cell you may create).
