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

## See also

- [`docs/MODDED-ATTRIBUTION.md`](MODDED-ATTRIBUTION.md) — sentinel naming,
  modded-mob attribution rules, and the PR rubric for new `ActionType`s.
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — high-level component diagram
  (loader → facade → gate → queue → DAO → log file).
- [`SHARED-CONTRACTS.md`](../SHARED-CONTRACTS.md) — canonical wire shapes
  for `Action`, `Sentinel`, parameter conventions, and the gate/queue/DAO
  contracts that this API is built on top of.
