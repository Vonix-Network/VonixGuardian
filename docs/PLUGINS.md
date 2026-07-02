# Writing a VonixGuardian Ecosystem Plugin

VonixGuardian is a Minecraft-agnostic Java engine wrapped by thin
Fabric/Forge/NeoForge loader jars. Third-party mods integrate with it as a
**soft dependency**: compile against the published `vonixguardian-core`
artifact, and everything falls back gracefully when the plugin is absent.

This guide walks the pattern end-to-end. For the full API reference (all
`submitXxx(...)` helpers, threading rules, `QueryFilter`) see
[API.md](API.md).

---

## 1. Add the dependency

VonixGuardian Core is a plain Java 17 library. It has zero Minecraft
compile-time deps and can be pulled in without polluting your mod's
classpath with storage backends.

### From Maven Central (once published)

```groovy
repositories {
    mavenCentral()
}

dependencies {
    // compileOnly: the loader jar (fabric/forge/neoforge) already ships
    // a shaded+relocated copy of core at runtime. transitive=false keeps
    // sqlite/hikaricp/gson off your compile classpath.
    compileOnly('network.vonix.guardian:vonixguardian-core:1.2.0') { transitive = false }
}
```

### From GitHub Packages (mirror)

```groovy
repositories {
    maven {
        name = 'VonixGuardianGitHubPackages'
        url  = uri('https://maven.pkg.github.com/Vonix-Network/VonixGuardian')
        credentials {
            username = System.getenv('GITHUB_ACTOR')
            password = System.getenv('GITHUB_TOKEN') // PAT with read:packages
        }
    }
}
```

**Do not shade `vonixguardian-core` into your mod jar.** The end user's
loader jar already provides (and class-relocates) core. Shipping a second
copy will collide with the loader's classes and break both mods.

---

## 2. Declare VonixGuardian as an *optional* dependency

### Fabric — `fabric.mod.json`

```json
{
  "id":       "mymod",
  "depends":  { "fabricloader": ">=0.15.0" },
  "suggests": { "vonixguardian": "*" }
}
```

### Forge / NeoForge — `mods.toml`

```toml
[[dependencies.mymod]]
    modId        = "vonixguardian"
    mandatory    = false        # soft-dep — mod loads without VG
    versionRange = "[1.2,)"
    ordering     = "AFTER"      # ensure VG boots before mymod's server-start
    side         = "SERVER"
```

---

## 3. Obtain the `Guardian` singleton at runtime (reflection)

Even with the compileOnly dependency in place, you should look the loader
class up reflectively so a missing VonixGuardian jar becomes a soft-fail
rather than a `NoClassDefFoundError` at load time.

```java
package com.example.mymod.compat;

import java.lang.reflect.Method;

public final class GuardianBridge {

    private static final Object GUARDIAN;   // network.vonix.guardian.core.Guardian
    public  static final boolean PRESENT;

    static {
        Object g = null;
        try {
            // Pick the loader accessor matching your platform:
            //   network.vonix.guardian.fabric.VonixGuardianFabric#guardian()
            //   network.vonix.guardian.forge.VonixGuardianForge#guardian()
            //   network.vonix.guardian.neoforge.VonixGuardianNeoForge#guardian()
            Class<?> loader = Class.forName(
                "network.vonix.guardian.neoforge.VonixGuardianNeoForge");
            g = loader.getMethod("guardian").invoke(null);
        } catch (Throwable ignored) { /* VG not installed — that's OK */ }
        GUARDIAN = g;
        PRESENT  = (g != null);
    }

    public static Object guardian() { return GUARDIAN; }
}
```

---

## 4. Three integration patterns

The rest of this guide shows the three canonical ways an ecosystem plugin
plugs into VonixGuardian: **subscribing to the event stream**,
**registering a permission node**, and **submitting an `Action`**.

### 4.1 Subscribe to `EventHook.PRE_LOG`

`EventHook` is VonixGuardian's tri-state filter interface (`ACCEPT` /
`DENY` / `PASS`). Hooks are consulted after the built-in category toggles
and static blacklists have passed, but before the action is enqueued —
i.e. the "pre-log" phase.

Use case: a Dynmap plugin wants to draw a marker for every rollback-worthy
block break in a claim, but does NOT want to influence whether the action
gets logged. Return `PASS` so VonixGuardian's default decision (accept)
stands.

```java
import network.vonix.guardian.core.event.EventHook;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;

EventHook dynmapMarker = (Action a) -> {
    if (a.type() == ActionType.BLOCK_BREAK) {
        DynmapBridge.drawMarker(a.worldId(), a.x(), a.y(), a.z(),
                                a.actorName(), a.targetId());
    }
    return EventHook.Decision.PASS;   // never veto, never short-circuit
};

// Register at server-start, after Guardian.boot(...) has returned:
guardian.eventGate().addHook(dynmapMarker);
```

Hooks run on the hot path of every gated event. They MUST be pure and
thread-safe; do all heavy I/O off-thread (submit to your own executor,
never block the caller).

### 4.2 Register a `PermissionNode` for your own subcommand

`PermissionNode` is a closed enum in v1.2.0 — you don't register a *new*
node at runtime, you **reuse an existing one** whose default op-level and
LuckPerms wildcard behaviour matches your feature. For a "read-only
lookup" integration this is almost always `PermissionNode.LOOKUP`; for a
mutation command it is `PermissionNode.ROLLBACK`.

```java
import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.perms.PermissionResolver;

boolean allowed = PermissionResolver.get()
        .has(playerUuid, PermissionNode.LOOKUP.node());
if (!allowed) {
    source.sendFailure(Component.literal("No permission"));
    return;
}
```

This automatically honours the operator's LuckPerms config — including the
`vonixguardian.*` wildcard — with the op-level fallback matching whatever
the operator configured under `permissions.perNodeOpLevels`.

> Need a genuinely new node (e.g. for a per-integration audit category)?
> Open a PR adding the constant; see the contribution rubric in
> `docs/MODDED-ATTRIBUTION.md`. Runtime node registration is not on the
> roadmap — the closed enum keeps LuckPerms and op-level fallback
> deterministic across servers.

### 4.3 Submit an `ActionRecord`

VonixGuardian's write side is `EventSubmitter`. `Guardian` implements it,
so a single reference does both jobs. Each `submitXxx(...)` helper builds
one `Action` (the on-disk record) and hands it to the async writer queue.
Calls are non-blocking and safe from the main server thread.

Use case: a Discord relay wants to funnel `/vg chat` events back into a
Discord channel, but also wants the reverse — Discord messages should
appear in the in-game audit log as `submitChat` entries so they land in
the same rotating JSON-lines file everything else does.

```java
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;

Guardian g = (Guardian) GuardianBridge.guardian();
if (g == null) return;                    // VG absent — soft-fail

EventSubmitter s = g;                     // Guardian implements EventSubmitter
s.submitChat(
    /*uuid  */ null,                      // no in-game UUID — Discord relay
    /*name  */ "[Discord] " + discordUser,
    /*world */ "minecraft:overworld",
    /*msg   */ messageBody
);
```

Every `submitXxx(...)` variant is listed in [API.md § 3](API.md#3-eventsubmitter-api).

---

## 5. Worked examples of ecosystem plugins

These are integration blueprints — each is deliberately small so you can
copy the shape into a real mod.

### 5.1 Dynmap markers for rollback events

```java
guardian.eventGate().addHook(a -> {
    switch (a.type()) {
        case BLOCK_BREAK, BLOCK_PLACE, EXPLOSION ->
            DynmapBridge.marker(a.worldId(), a.x(), a.y(), a.z(),
                                a.actorName(), a.type().name(), a.targetId());
        default -> { /* ignore */ }
    }
    return EventHook.Decision.PASS;
});
```

Combined with `guardian.dao().query(...)` on a Dynmap web endpoint, you
can serve time-sliced heatmaps of griefing directly from the same audit
DB the plugin uses for rollback.

### 5.2 Discord relay

```java
// Outbound: mirror every chat/command event to Discord.
guardian.eventGate().addHook(a -> {
    if (a.type() == ActionType.CHAT || a.type() == ActionType.COMMAND) {
        DiscordBridge.post("#" + a.worldId(),
                           "<" + a.actorName() + "> " + a.targetId());
    }
    return EventHook.Decision.PASS;
});

// Inbound: relay Discord messages back into VG's audit log so they
// survive rollback windows and land in the JSON-lines file.
DiscordBridge.onMessage((user, text, channel) ->
    guardian.submitChat(null, "[Discord] " + user,
                        channel, text));
```

### 5.3 Per-region protection (WorldGuard-style)

```java
// Deny logging (and thus rollback-eligibility) inside sanctuary regions
// so that scripted seasonal events don't clutter the audit log.
guardian.eventGate().addHook(a -> {
    if (RegionService.isInside(a.worldId(), a.x(), a.y(), a.z(), "sanctuary")) {
        return EventHook.Decision.DENY;
    }
    return EventHook.Decision.PASS;
});
```

Or the inverse — force-accept inside a claim regardless of the built-in
category toggles, so land-owners can rebuild after PvP raids:

```java
guardian.eventGate().addHook(a -> {
    if (ClaimService.isClaimed(a.worldId(), a.x(), a.y(), a.z())) {
        return EventHook.Decision.ACCEPT;
    }
    return EventHook.Decision.PASS;
});
```

---

## 6. Checklist

- [ ] Depend on `network.vonix.guardian:vonixguardian-core` as
      `compileOnly`, `transitive = false`.
- [ ] Declare VonixGuardian as an *optional* dependency in your mod
      manifest (`suggests` on Fabric, `mandatory = false` on Forge/NeoForge).
- [ ] Look up the `Guardian` singleton reflectively; fall back cleanly
      when absent.
- [ ] Add every `EventHook` at server-start, after `Guardian.boot(...)`
      has returned.
- [ ] Reuse an existing `PermissionNode` unless you have a very strong
      reason to open a PR for a new one.
- [ ] Never block inside `EventHook#test`; never shade
      `vonixguardian-core` into your jar.

Questions? Open an issue at
<https://github.com/Vonix-Network/VonixGuardian/issues>.
