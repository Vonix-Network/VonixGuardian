# VonixGuardian Public API (v1.0.0)

Reference for **other mods** that want to integrate with VonixGuardian — either to
write custom audit entries into the same log, or to query the audit history that
VonixGuardian has already collected.

> Scope: this document describes the **stable** Java surface intended for
> third-party use. Anything not listed here is internal and may change without
> a deprecation cycle.

---

## 1. Overview

VonixGuardian is built around a single facade — `network.vonix.guardian.core.Guardian`
— which exposes two sides:

| Side  | Interface                                            | Purpose                                    |
|-------|------------------------------------------------------|--------------------------------------------|
| Write | `network.vonix.guardian.core.event.EventSubmitter`   | submit one audit entry (any action type)   |
| Read  | `network.vonix.guardian.core.storage.GuardianDao`    | query / count rows by `QueryFilter`        |

`Guardian` itself **implements `EventSubmitter`**, so a single `Guardian`
reference is enough to do both jobs.

The instance is built by the loader module during server start
(`Guardian.boot(...)`) and torn down at server stop (`Guardian.close()`).
Loader modules (`vonixguardian-fabric`, `vonixguardian-forge`,
`vonixguardian-neoforge`) hold the live reference and expose it as
**`<Loader>Mod.guardian()`** — a static accessor that returns the singleton
`Guardian` once boot has completed (or `null` before/after).

---

## 1a. Using in Gradle

VonixGuardian Core is published as a plain Java library — `network.vonix.guardian:vonixguardian-core`.
You do **not** need to depend on any loader jar to compile against the public API
(the interfaces above live in `core`).

### Maven coordinate

```
network.vonix.guardian:vonixguardian-core:1.1.7
```

### Consuming from Maven Local (after `./gradlew :core:publishToMavenLocal`)

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // compileOnly is recommended: at runtime the loader jar (fabric/forge/neoforge)
    // already ships a shaded copy of core. `transitive = false` avoids pulling
    // storage backends (sqlite/hikaricp/gson) onto your compile classpath —
    // those are runtime-provided by the loader.
    compileOnly('network.vonix.guardian:vonixguardian-core:1.1.7') { transitive = false }
}
```

### Consuming from GitHub Packages

```groovy
repositories {
    maven {
        name = 'VonixGuardianGitHubPackages'
        url = uri('https://maven.pkg.github.com/Vonix-Network/VonixGuardian')
        credentials {
            username = System.getenv('GITHUB_ACTOR')   // your GitHub username
            password = System.getenv('GITHUB_TOKEN')   // PAT with read:packages
        }
    }
}

dependencies {
    compileOnly('network.vonix.guardian:vonixguardian-core:1.1.7') { transitive = false }
}
```

> **Runtime note.** Do not shade `vonixguardian-core` into your own mod jar.
> The loader jar users already have installed provides (and relocates) core.
> Shading a second copy will collide with the loader's classes.

### Bootstrap example (Maven coord, no local jar)

Older revisions of this doc suggested `flatDir` pointing at a locally-built
`core-*.jar`. That is no longer needed — resolve `vonixguardian-core` via
`mavenLocal()` or GitHub Packages as shown above, then call the facade the
usual way:

```groovy
dependencies {
    compileOnly('network.vonix.guardian:vonixguardian-core:1.1.7') { transitive = false }
}
```

```java
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;

// Loader-side accessor (Fabric shown; use the matching class on Forge/NeoForge):
Guardian g = network.vonix.guardian.fabric.VonixGuardianFabric.guardian();
if (g != null) {
    EventSubmitter s = g; // Guardian implements EventSubmitter
    // s.submitEntityKill(...);
}
```

---

## 2. Soft-dependency pattern

**Do not hard-import VonixGuardian classes from your mod.** VonixGuardian is
optional; users may not have it installed. Use reflection with a try-catch
fallback so your mod still loads cleanly when VonixGuardian is absent.

### 2.1 Generic soft-dep helper (works on Fabric / Forge / NeoForge)

```java
package com.example.mymod.compat;

import java.lang.reflect.Method;
import java.util.UUID;

public final class GuardianBridge {
    private static final boolean PRESENT;
    private static final Object GUARDIAN;          // network.vonix.guardian.core.Guardian
    private static final Method  SUBMIT_KILL;      // submitEntityKill(...)

    static {
        boolean ok = false;
        Object instance = null;
        Method m = null;
        try {
            // Loader-side static accessor. Pick the one matching your platform.
            Class<?> loader = Class.forName("network.vonix.guardian.fabric.VonixGuardianFabric");
            instance = loader.getMethod("guardian").invoke(null);

            if (instance != null) {
                Class<?> sub = Class.forName("network.vonix.guardian.core.event.EventSubmitter");
                m = sub.getMethod("submitEntityKill",
                        UUID.class, String.class, String.class,
                        int.class, int.class, int.class,
                        String.class, String.class);
                ok = true;
            }
        } catch (Throwable ignored) {
            // VonixGuardian not installed or not booted yet — that's fine.
        }
        PRESENT     = ok;
        GUARDIAN    = instance;
        SUBMIT_KILL = m;
    }

    public static boolean present() { return PRESENT; }

    public static void recordKill(UUID killer, String killerName, String world,
                                  int x, int y, int z, String victimType, String tag) {
        if (!PRESENT) return;
        try {
            SUBMIT_KILL.invoke(GUARDIAN, killer, killerName, world, x, y, z, victimType, tag);
        } catch (Throwable t) {
            // never let an audit hook break the host event
        }
    }
}
```

### 2.2 Fabric — `fabric.mod.json`

```json
{
  "id": "mymod",
  "depends":   { "fabricloader": ">=0.15.0" },
  "suggests":  { "vonixguardian": "*" }
}
```

### 2.3 Forge / NeoForge — `mods.toml`

```toml
[[dependencies.mymod]]
    modId = "vonixguardian"
    mandatory = false        # soft-dep
    versionRange = "[1.0,)"
    ordering = "AFTER"
    side = "SERVER"
```

The `AFTER` ordering means VonixGuardian boots before your mod's server-start
handler runs, so `guardian()` is non-null when you look it up.

---

## 3. `EventSubmitter` API

All methods live on `network.vonix.guardian.core.event.EventSubmitter`. Common
parameter conventions (see SHARED-CONTRACTS § 8):

- `actorUuid` — player UUID; `null` for synthetic sources (mob, piston, …).
- `actorName` — resolved player name, **or** a `Sentinel` string when
  `actorUuid` is null (e.g. `Sentinel.CREEPER`, `Sentinel.PISTON`).
- `worldId` — dimension key, e.g. `"minecraft:overworld"`.
- `x/y/z` — block coords; pass `0,0,0` for non-positional events (chat, join, …).
- `targetId` — block id / entity-type / item id / message body.
- `amount` — stack size or container-delta magnitude.
- `sourceTag` — optional classifier like `"explosion:tnt"`; nullable.

### 3.1 Primary entry point

```java
void submit(Action a);
```
Gates the action through `EventGate`, then enqueues it onto the async writer.
Pass a fully-built `Action` (see `ActionBuilder`). Most integrators will use
the typed helpers below instead.

### 3.2 Block events

| Method                                                                                                                | Notes                              |
|-----------------------------------------------------------------------------------------------------------------------|------------------------------------|
| `submitBlockBreak(uuid, name, world, x, y, z, blockId, sourceTag)`                                                    | `BlockBreakEvent`                  |
| `submitBlockPlace(uuid, name, world, x, y, z, blockId, sourceTag)`                                                    | `BlockPlaceEvent`                  |
| `submitBurn   / submitIgnite / submitFade / submitForm / submitSpread`                                                | vanilla block-state mutations      |
| `submitDispense(uuid, name, world, x, y, z, itemId, sourceTag)`                                                       | dispenser fired                    |
| `submitPistonExtend / submitPistonRetract`                                                                            | piston movement                    |
| `submitBucketEmpty / submitBucketFill`                                                                                | player bucket usage                |
| `submitLeavesDecay(uuid, name, world, x, y, z, blockId, sourceTag)`                                                   | natural decay                      |
| `submitEntityChangeBlock(uuid, name, world, x, y, z, oldBlockId, newBlockId, sourceTag)`                              | **modded griefing path** (ravager, dragons, modded mobs) |

### 3.3 Container / item events

| Method                                                                                | Notes                                  |
|---------------------------------------------------------------------------------------|----------------------------------------|
| `submitContainerChange(uuid, name, world, x, y, z, itemId, delta, sourceTag)`         | sign of `delta` picks deposit/withdraw; `delta==0` is a no-op |
| `submitInventoryDeposit / submitInventoryWithdraw`                                    | player inventory slot delta            |
| `submitHopperPush / submitHopperPull`                                                 | hopper-driven item movement            |
| `submitItemDrop / submitItemPickup`                                                   | `ItemTossEvent` / `ItemPickupEvent`    |
| `submitItemCraft(uuid, name, world, x, y, z, itemId, amount, sourceTag)`              | `CraftItemEvent`                       |

### 3.4 Entity events

| Method                                                                                | Notes                                  |
|---------------------------------------------------------------------------------------|----------------------------------------|
| `submitEntityKill(uuid, name, world, x, y, z, entityType, sourceTag)`                 | `LivingDeathEvent`                     |
| `submitEntitySpawn(uuid, name, world, x, y, z, entityType, sourceTag)`                | `EntitySpawnEvent`                     |
| `submitEntityInteract(uuid, name, world, x, y, z, entityType, sourceTag)`             | `PlayerInteractEntityEvent`            |
| `submitHangingPlace / submitHangingBreak`                                             | item-frames, paintings                 |
| `submitExplosion(uuid, sentinelName, world, x, y, z, affectedJoined, sourceTag)`      | `affectedJoined` truncated to 4 KiB by loader |

### 3.5 Session / chat / world

| Method                                                                                | Notes                                  |
|---------------------------------------------------------------------------------------|----------------------------------------|
| `submitChat(uuid, name, world, message)`                                              | coords ignored (use `0,0,0`)           |
| `submitCommand(uuid, name, world, command)`                                           | full command incl. leading `/`         |
| `submitSign(uuid, name, world, x, y, z, joinedLines)`                                 | lines joined by `"\n"`                 |
| `submitSessionJoin(uuid, name, world, ipOrHash)`                                      | `ipOrHash` is hashed if `privacy.hashIps` is set |
| `submitSessionLeave(uuid, name, world, reason)`                                       |                                        |
| `submitUsernameChange(uuid, newName, world, oldName)`                                 | UUID stable across rename              |
| `submitStructureGrow(uuid, name, world, x, y, z, structureId, sourceTag)`             | tree/sapling/mushroom growth           |
| `submitClick(uuid, name, world, x, y, z, targetId, sourceTag)`                        | buttons, levers, doors, plates         |

### 3.6 Example call

```java
GuardianBridge.recordKill(
        player.getUUID(),
        player.getName().getString(),
        player.level().dimension().location().toString(),
        (int) player.getX(), (int) player.getY(), (int) player.getZ(),
        "pixelmon:charizard",
        "pixelmon:battle"
);
```

---

## 4. `Guardian` facade

```java
package network.vonix.guardian.core;

public final class Guardian implements AutoCloseable, EventSubmitter { ... }
```

| Method                              | Purpose                                                              |
|-------------------------------------|----------------------------------------------------------------------|
| `static Guardian boot(cfg, dataDir, mutator, opLookup, mainExec, tf)` | Build & start. Called by the loader, **not** by third-party mods.   |
| `EventSubmitter submitter()`        | Returns `this`; convenient when you only want the write side.        |
| `GuardianDao dao()`                 | Read side — pass a `QueryFilter` (see § 4.1).                        |
| `GuardianConfig config()`           | Live config snapshot (read-only).                                    |
| `RollbackEngine rollbackEngine()`   | Used by brigadier commands; not part of the third-party contract.    |
| `long submitted()` / `long gated()` | Counters for diagnostics.                                            |
| `void shutdown()` / `close()`       | Drain + close. Called by the loader, idempotent.                     |
| every `submitXxx(...)` from § 3     | Inherited from `EventSubmitter`.                                     |

### 4.1 Querying

```java
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.action.Action;

QueryFilter f = QueryFilter.builder()
        .actor(playerUuid)
        .type(ActionType.BLOCK_BREAK)
        .since(System.currentTimeMillis() - 3_600_000L)
        .build();

List<Action> rows = guardian.dao().query(f, /*offset*/ 0, /*limit*/ 100);
long total       = guardian.dao().count(f);
```

`dao().query(...)` blocks on a HikariCP connection — see § 7 on threading.

### 4.2 Obtaining the singleton from a third-party mod

There is **no** `Guardian.get()` static accessor in the core artifact. The
loader module is the canonical owner. Call its static accessor reflectively:

```java
Class<?> fabricLoader = Class.forName("network.vonix.guardian.fabric.VonixGuardianFabric");
Object   guardian     = fabricLoader.getMethod("guardian").invoke(null);
// or: network.vonix.guardian.forge.VonixGuardianForge#guardian()
// or: network.vonix.guardian.neoforge.VonixGuardianNeoForge#guardian()
```

If the returned value is non-null, boot has completed and every method on
§§ 3–4 is safe to call.

---

## 5. Custom `ActionType` — not supported in v1.0.0

`network.vonix.guardian.core.action.ActionType` is a **closed enum**. There is
no runtime registration API in v1.0.0. If your mod needs a category that does
not fit any existing constant:

1. Pick the closest existing type (e.g. `ENTITY_KILL` for battle outcomes,
   `CLICK` for generic interactions) and stuff the discriminator into
   `sourceTag` (e.g. `"pixelmon:battle:capture"`).
2. If that's still wrong, open a PR adding the enum constant + a matching
   `submitXxx` helper on `EventSubmitter`. See `docs/MODDED-ATTRIBUTION.md`
   for the contribution rubric.

This is intentional: a closed enum keeps the on-disk schema, JSON-lines
format, and rollback engine deterministic across servers.

---

## 6. Worked example — Pixelmon battle integration

The PixelmonBattle mod wants every wild-Pokémon defeat by a player to land in
the same audit log the rest of the server already uses.

```java
package com.example.pixelmonbattle.guardian;

import com.pixelmonmod.pixelmon.api.events.BeatWildPixelmonEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class GuardianListener {

    @SubscribeEvent
    public void onWildBeaten(BeatWildPixelmonEvent ev) {
        if (!GuardianBridge.present()) return;             // soft-dep guard

        var p   = ev.player;
        var pos = p.blockPosition();
        var dim = p.level().dimension().location().toString();

        GuardianBridge.recordKill(
                p.getUUID(),
                p.getName().getString(),
                dim,
                pos.getX(), pos.getY(), pos.getZ(),
                "pixelmon:" + ev.wildPokemon.getSpecies().getName().toLowerCase(),
                "pixelmon:battle:wild"
        );
    }
}
```

After the battle ends, one row lands in `guardian_actions`:

```
ts                    | actor_uuid                            | actor_name | action       | world              | x   | y  | z   | target                 | source_tag
----------------------+---------------------------------------+------------+--------------+--------------------+-----+----+-----+------------------------+----------------------
2026-06-28 14:22:07Z  | 3f0a…d7                               | Steve      | ENTITY_KILL  | minecraft:overworld | 142 | 64 | -88 | pixelmon:charizard     | pixelmon:battle:wild
```

…and a matching JSON-lines entry is appended to the rotating audit log file
(if `log-file.enabled = true`).

---

## 7. Threading

All public API methods are **thread-safe**. You can call them from:

- the main server thread (event handlers, command callbacks),
- async tick tasks / scheduled executors,
- network worker threads,
- backup / world-save threads.

Specifics:

- **`submitXxx(...)` is non-blocking.** It runs gate filtering inline, then
  hands the `Action` to a bounded `BatchedAsyncWriteQueue`. If the queue is
  full, the action is dropped (the dropped counter ticks up) — the call
  itself still returns immediately. Safe to call from the main thread on
  hot paths.
- **`dao().query(...)` / `dao().count(...)` blocks** while it acquires a
  HikariCP connection and runs SQL. **Do not call from the main server
  thread on hot paths.** Hand the call off to an async executor and post the
  result back if you need to render it in-game.
- The `Guardian` reference itself is published safely once `boot(...)`
  returns; reading the field from any thread is fine.

---

## Public Java API (v1)

Since **VonixGuardian 1.1.7 (Wave-3 B12+B13)** the plugin ships a
version-stable, third-party-friendly interface:
`network.vonix.guardian.core.api.VonixGuardianAPI`. This is the recommended
integration surface for consumer mods — it returns typed result records
instead of raw `Action` rows, hides SQL details, and follows semver on
`apiVersion()`.

### Maven coordinates

The core jar is published to Vonix Network's Maven (see B14 for
publishing details):

```groovy
repositories {
    maven { url = 'https://maven.vonix.network/releases' }
}

dependencies {
    // Compile against the API but do NOT bundle VG — soft-dep at runtime.
    compileOnly 'network.vonix.guardian:vonixguardian-core:1.1.7'
}
```

### Obtaining the API handle (reflection soft-dep pattern)

VG is a **soft** dependency for third-party mods — you should not fail to
load if VG is absent. Use reflection, mirroring the LuckPerms bridge
pattern VG itself uses internally (see
`core/src/main/java/network/vonix/guardian/core/perms/LuckPermsBridge.java`):

```java
import java.lang.reflect.Method;

public final class VgSoftDep {

    private static volatile Object apiHandle;   // held as Object; reflect all calls
    private static volatile Method  hasPlaced;
    private static volatile Method  blockLookup;

    public static void tryWire() {
        try {
            Class<?> guardianCls = Class.forName("network.vonix.guardian.core.Guardian");
            // Your loader stashes the singleton somewhere — e.g. VgBootstrap.INSTANCE:
            Object g = Class.forName("com.example.mymod.VgBootstrap")
                            .getField("INSTANCE").get(null);
            Method apiMethod = guardianCls.getMethod("api");
            Object api = apiMethod.invoke(g);

            Class<?> apiCls = Class.forName(
                "network.vonix.guardian.core.api.VonixGuardianAPI");
            Method testMethod = apiCls.getMethod("testAPI");
            if (!(Boolean) testMethod.invoke(api)) return;

            hasPlaced   = apiCls.getMethod("hasPlaced",
                java.util.UUID.class, String.class,
                int.class, int.class, int.class, long.class);
            blockLookup = apiCls.getMethod("blockLookup",
                String.class, int.class, int.class, int.class, long.class);
            apiHandle   = api;
        } catch (ReflectiveOperationException | ClassCastException e) {
            // VG absent or shape drifted — soft-fail.
            apiHandle = null;
        }
    }

    public static boolean hasPlaced(java.util.UUID u, String world,
                                    int x, int y, int z, long secs) {
        Object h = apiHandle;
        if (h == null || hasPlaced == null) return false;
        try {
            return (Boolean) hasPlaced.invoke(h, u, world, x, y, z, secs);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
```

### Example usage

```java
UUID player = ...;
if (VgSoftDep.hasPlaced(player, "minecraft:overworld", x, y, z, 60)) {
    // Player placed a block here in the last 60 seconds — allow their break
    // even if the world is protected.
}

// Coordinate history (block family)
Method bl = /* cached blockLookup Method */;
@SuppressWarnings("unchecked")
List<Object> results = (List<Object>) bl.invoke(apiHandle,
        "minecraft:overworld", x, y, z, 3600);
// Each element is a network.vonix.guardian.core.api.BlockLookupResult record;
// pull fields via record accessors or via reflection depending on how strict
// your soft-dep is.
```

### Method summary

| Method                                                                          | Purpose                                          |
|---------------------------------------------------------------------------------|--------------------------------------------------|
| `int apiVersion()`                                                              | API major (current: `1`). Bumps = breaking.      |
| `String pluginVersion()`                                                        | Human-readable mod version (`"1.1.7"`).          |
| `boolean testAPI()`                                                             | Wiring smoke-test; always `true` on healthy VG.  |
| `boolean hasPlaced(UUID, String, int, int, int, long)`                          | Did user place a block here in the last N sec?   |
| `boolean hasRemoved(UUID, String, int, int, int, long)`                         | Did user break a block here in the last N sec?   |
| `List<BlockLookupResult> blockLookup(String, int, int, int, long)`              | Every block-family event at that coord.          |
| `List<ContainerLookupResult> containerLookup(String, int, int, int, long)`      | Every container-family transaction at that coord.|
| `List<MessageLookupResult> chatLookup(UUID, long, int)`                         | User's chat entries within the window.           |
| `List<MessageLookupResult> commandLookup(UUID, long, int)`                      | User's command entries within the window.        |

### Result records

- **`BlockLookupResult`** — `time`, `actorUuid`, `actorName`, `worldId`,
  `x/y/z`, `blockId`, `targetMeta`, `action` (`"block"`, `"burn"`, …),
  `rolledBack`, `sourceTag`.
- **`ContainerLookupResult`** — `time`, `actorUuid`, `actorName`, `worldId`,
  `x/y/z`, `itemId`, `targetMeta`, **signed** `amountDelta` (positive =
  deposit, negative = withdraw), `rolledBack`, `sourceTag`.
- **`MessageLookupResult`** — `time`, `actorUuid`, `actorName`, `worldId`,
  `message`, `kind` (`"chat"` or `"command"`).

### Versioning contract

- **`apiVersion()`** follows semver — a bump signals a breaking change to
  existing method signatures or record shapes. New methods MAY be added
  within a major without a bump.
- Callers should compare `apiVersion()` at startup and refuse to load on
  mismatch, e.g.:
  ```java
  if (api.apiVersion() != EXPECTED_VG_API_MAJOR) {
      LOGGER.warn("VonixGuardian API v{} present, expected v{} — skipping integration",
                  api.apiVersion(), EXPECTED_VG_API_MAJOR);
      return;
  }
  ```

### Threading

All API methods run a blocking DAO query internally. Same rules as
`Guardian.dao()`: hop to an async executor before calling, then post the
result back to the server thread if you need to render it in-game.

---

## Javadoc

Every tagged release publishes a rendered Javadoc site via CI:

- **Latest release:** <https://vonix-network.github.io/VonixGuardian/javadoc/>
- **Per-version archive:** `https://vonix-network.github.io/VonixGuardian/javadoc/<version>/`

The Javadoc jar is also attached to each GitHub Release and published as
`vonixguardian-core-<version>-javadoc.jar` alongside the sources jar on
Maven Central / GitHub Packages.

## Public class index (v1.2.0)

Everything below is part of the stable third-party surface. Anything not
in this list is internal and may change without a deprecation cycle.

**Facade & event submission**
- `network.vonix.guardian.core.Guardian`
- `network.vonix.guardian.core.action.Action`
- `network.vonix.guardian.core.action.ActionBuilder`
- `network.vonix.guardian.core.action.ActionType`
- `network.vonix.guardian.core.event.EventSubmitter`
- `network.vonix.guardian.core.event.EventGate`
- `network.vonix.guardian.core.event.EventHook`
- `network.vonix.guardian.core.event.PerWorldEventHook`
- `network.vonix.guardian.core.event.BlacklistFileHook`
- `network.vonix.guardian.core.event.PreLogDispatcher`
- `network.vonix.guardian.core.event.PreLogEvent`
- `network.vonix.guardian.core.event.PreLogEventHook`
- `network.vonix.guardian.core.event.Sentinel`

**Typed API (v1)**
- `network.vonix.guardian.core.api.VonixGuardianAPI`
- `network.vonix.guardian.core.api.GuardianAPI`
- `network.vonix.guardian.core.api.BlockLookupResult`
- `network.vonix.guardian.core.api.ContainerLookupResult`
- `network.vonix.guardian.core.api.MessageLookupResult`

**Query & storage**
- `network.vonix.guardian.core.query.QueryFilter`
- `network.vonix.guardian.core.query.QueryParser`
- `network.vonix.guardian.core.query.QueryParseException`
- `network.vonix.guardian.core.query.ActionTokens`
- `network.vonix.guardian.core.query.InspectorLookup`
- `network.vonix.guardian.core.storage.GuardianDao`
- `network.vonix.guardian.core.storage.QueryCompiler`
- `network.vonix.guardian.core.storage.Schema`
- `network.vonix.guardian.core.storage.StorageFactory`

**Permissions**
- `network.vonix.guardian.core.perms.PermissionNode`
- `network.vonix.guardian.core.perms.PermissionResolver`
- `network.vonix.guardian.core.perms.CommandGate`
- `network.vonix.guardian.core.perms.LookupPermissionFilter`
- `network.vonix.guardian.core.perms.LuckPermsBridge`
- `network.vonix.guardian.core.perms.OpLevelFallback`

**Rollback**
- `network.vonix.guardian.core.rollback.RollbackEngine`
- `network.vonix.guardian.core.rollback.RollbackPlan`
- `network.vonix.guardian.core.rollback.RollbackResult`
- `network.vonix.guardian.core.rollback.UndoStack`
- `network.vonix.guardian.core.rollback.WorldMutator`
- `network.vonix.guardian.core.rollback.PurgeEngine`

**Attribution**
- `network.vonix.guardian.core.attribution.Attribution`
- `network.vonix.guardian.core.attribution.AttributionKind`
- `network.vonix.guardian.core.attribution.AttributionResolver`
- `network.vonix.guardian.core.attribution.DamageHistory`

**Config, diagnostics, theme**
- `network.vonix.guardian.core.config.GuardianConfig`
- `network.vonix.guardian.core.config.ConfigLoader`
- `network.vonix.guardian.core.config.PerWorldConfigStore`
- `network.vonix.guardian.core.config.IpHasher`
- `network.vonix.guardian.core.diagnostics.GuardianStatus`
- `network.vonix.guardian.core.theme.Theme`
- `network.vonix.guardian.core.theme.ThemeRegistry`
- `network.vonix.guardian.core.logfile.JsonLinesLogFile`
- `network.vonix.guardian.core.blacklist.BlacklistFile`
- `network.vonix.guardian.core.blacklist.BlacklistMatcher`
- `network.vonix.guardian.core.filter.VanillaGrieferSet`
- `network.vonix.guardian.core.command.CommandSpec`
- `network.vonix.guardian.core.command.SubcommandSpec`
- `network.vonix.guardian.core.command.ArgumentSpec`

For a step-by-step integration walkthrough (soft-dep pattern, `EventHook`
subscription, `PermissionNode` reuse, `Action` submission) see
[PLUGINS.md](PLUGINS.md).

## See also

- [`docs/PLUGINS.md`](PLUGINS.md) — ecosystem plugin author's guide.
- [`docs/MODDED-ATTRIBUTION.md`](MODDED-ATTRIBUTION.md) — sentinel naming,
  modded-mob attribution rules, and the PR rubric for new `ActionType`s.
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — high-level component diagram
  (loader → facade → gate → queue → DAO → log file).
- [`SHARED-CONTRACTS.md`](../SHARED-CONTRACTS.md) — canonical wire shapes
  for `Action`, `Sentinel`, parameter conventions, and the gate/queue/DAO
  contracts that this API is built on top of.
