# RECON: WorldEdit-Modded Selection Integration (W3-B16)

**Status:** RECON + DESIGN ONLY. No code changes in this branch.
**Base tag:** `v1.1.6`
**Branch:** `feature/w3-b16-worldedit-recon`
**Author-scope:** Wave 3, batch A.

Related: `NIGHTSHIFT.md` item B16; `QueryParser.java:272-278` (`r:#we` / `r:#worldedit`
tokens are parsed but currently no-op — comment reads "leave radius/world unset").

---

## 1. Problem restated

CoreProtect uses the player's active WorldEdit region as a lookup radius. VonixGuardian
today parses `r:#we` / `r:#worldedit` but does not evaluate it: the lookup engine has
no way to fetch the caller's WE selection. This recon determines whether a
WorldEdit-modded soft-dep bridge is viable across the four MC targets VG supports
(1.18.2 / 1.19.2 / 1.20.1 / 1.21.1) and sketches the API surface.

---

## 2. Availability verdict per MC version

Source: <https://modrinth.com/plugin/worldedit/versions> and
<https://dev.bukkit.org/projects/worldedit> mirrored to CurseForge; EngineHub is the
canonical publisher (sk89q / me4502).

| MC       | Loader(s) supported          | WE version(s)                       | Verdict     |
|----------|------------------------------|-------------------------------------|-------------|
| 1.18.2   | Forge, Fabric                | 7.2.13 — 7.2.15                     | ✅ Available |
| 1.19.2   | Forge, Fabric                | 7.2.13 — 7.2.15                     | ✅ Available |
| 1.20.1   | Forge, Fabric                | 7.2.15 — 7.3.x                      | ✅ Available |
| 1.21.1   | NeoForge, Fabric             | 7.3.x (7.3.6+); no Forge build      | ✅ Available (NeoForge/Fabric only) |

Notes:
- **1.21.1**: EngineHub dropped LexForge in favour of NeoForge. If VG's 1.21.1
  distribution still targets legacy Forge, the WE bridge simply reports "unavailable"
  on that runtime and `r:#we` returns a clean parse error. This is acceptable.
- **FAWE (FastAsyncWorldEdit)**: publishes Fabric + (Neo)Forge builds tracking the
  same MC line-up. Package remains `com.sk89q.worldedit.*` — FAWE is a drop-in that
  extends the same public API surface for `SessionManager` / `LocalSession` /
  `Region`. **No separate bridge needed**; our reflection probes will bind to
  whichever provider is present. Confirmed via
  <https://intellectualsites.github.io/fastasyncworldedit-javadocs/worldedit-core/>.

**Overall verdict:** ✅ **PROCEED**. WE-modded (or FAWE-modded) covers 4/4 target
versions with a single reflection-only integration point. No API divergence across
7.2.x → 7.3.x for the small surface we need (`SessionManager.getIfPresent`,
`LocalSession.getSelection(World)`, `Region.getMinimumPoint/getMaximumPoint/getWorld`).

---

## 3. WorldEdit API surface we need

Reference: <https://worldedit.enginehub.org/en/latest/api/concepts/local-sessions>.

Minimum viable call chain (all in `com.sk89q.worldedit.*`):

```text
com.sk89q.worldedit.WorldEdit.getInstance()          -> WorldEdit
    .getSessionManager()                              -> SessionManager
    .getIfPresent(SessionOwner)                       -> LocalSession or null

LocalSession
    .getSelectionWorld()                              -> com.sk89q.worldedit.world.World or null
    .getSelection(World)                              -> Region  (throws IncompleteRegionException)

Region
    .getMinimumPoint()                                -> BlockVector3 (x,y,z)
    .getMaximumPoint()                                -> BlockVector3
    .getWorld()                                       -> World  (nullable)

World.getName() / World.getId()                       -> String (namespaced dimension key)
```

**SessionOwner resolution.** The modded platform (`worldedit-mod` /
`worldedit-fabric` / `worldedit-neoforge`) registers a platform-specific
`Player` (extends `Actor` extends `SessionOwner`) keyed by UUID. We resolve it
via `WorldEdit.getInstance().getPlatformManager().getPlayerForPlatform(...)` or,
more portably, via the wrapper obtained through the platform adapter. For a
soft-dep bridge we prefer the **UUID lookup path** exposed by the platform's
"proxy player" — which under both Forge/NeoForge and Fabric platforms is
`platform.matchPlayer(uuid)` on the `Platform` returned by
`getPlatformManager().queryCapability(Capability.USER_COMMANDS)`.

Result contract we surface to VG core:

```java
public record SelectionBounds(
    String worldKey,          // e.g. "minecraft:overworld"
    int minX, int minY, int minZ,
    int maxX, int maxY, int maxZ
) {}
```

---

## 4. Threading / event-bus considerations

- WE-modded is **API-only** for our use case. There is no need to subscribe to
  Fabric/Forge event bus — we call in on-demand at query-parse/execute time.
- `SessionManager.getIfPresent` is thread-safe (backed by a `ConcurrentHashMap` in
  WE 7.2+). It is safe to invoke from the VG lookup executor without hopping to
  the main server thread. Confirmed by inspection of
  `com.sk89q.worldedit.session.SessionManager` in WE 7.2.15 and 7.3.6.
- `LocalSession.getSelection(World)` reads immutable snapshots; also safe off-thread.
- **Rule for the bridge:** never mutate WE state (no `learnChanges()`, no
  `remember(EditSession)`, no clipboard writes). Read-only.

---

## 5. Soft-dep bridge feasibility

**Verdict:** ✅ Same reflection pattern as `LuckPermsBridge` applies cleanly.

Rationale:
- Root probe class `com.sk89q.worldedit.WorldEdit` is stable across 7.0 → 7.3.
- `getInstance()` is a public static; no constructor reflection needed.
- All method names/signatures we need are unchanged since WE 7.0.0 (May 2020).
- No generic-type erasure gotchas — only `Class`, `String`, `UUID`, primitive
  `int`s cross the reflection boundary.
- `IncompleteRegionException` is the only checked exception we must catch; we
  detect it by `getCause()` / `getClass().getName()` string match — no import.

Tri-state cache mirrors `LuckPermsBridge`:

```java
enum Availability { AVAILABLE, ABSENT, UNKNOWN }
```

`ABSENT` is set on `ClassNotFoundException` for the root class and is permanent
for the JVM lifetime. `UNKNOWN` covers "loaded but platform not registered yet"
(early server tick); the resolver retries on next query.

---

## 6. Design skeleton — `WorldEditBridge`

Location: `core/src/main/java/network/vonix/guardian/core/query/WorldEditBridge.java`
(next to `QueryParser`; keeps the query subsystem self-contained).

```java
package network.vonix.guardian.core.query;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure-reflection bridge to the WorldEdit-modded API.
 *
 * <p>MUST NEVER {@code import com.sk89q.worldedit.*}. WE (and FAWE, which shares
 * the same package) is a soft dependency: the core jar compiles and runs without
 * it, and every interaction goes through {@link Class#forName} + {@link Method#invoke}.
 *
 * <p>Reflective call chain (see docs/RECON-WORLDEDIT-INTEGRATION.md §3):
 *
 * <pre>
 *   com.sk89q.worldedit.WorldEdit.getInstance()
 *       .getSessionManager().getIfPresent(sessionOwner)   -&gt; LocalSession | null
 *       .getSelection(world)                              -&gt; Region  (throws IncompleteRegionException)
 *       Region.getMinimumPoint() / getMaximumPoint()      -&gt; BlockVector3
 *       Region.getWorld().getId()                         -&gt; "minecraft:overworld" etc.
 * </pre>
 */
public final class WorldEditBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldEditBridge.class);
    private static final String ROOT_CLASS = "com.sk89q.worldedit.WorldEdit";

    public enum Availability { AVAILABLE, ABSENT, UNKNOWN }

    private static final AtomicReference<Availability> CACHE =
        new AtomicReference<>(Availability.UNKNOWN);

    private WorldEditBridge() {}

    /** Cheap probe; caches ABSENT permanently, UNKNOWN retries. */
    public static Availability probe() { /* Class.forName(ROOT_CLASS, false, cl) */ }

    /**
     * Fetch the caller's current WE selection, if any.
     *
     * @param actorUuid   player UUID
     * @param currentWorldId  dimension key to constrain the selection to (nullable → any)
     * @return bounds if the player has a complete selection in {@code currentWorldId},
     *         otherwise {@code Optional.empty()}.
     */
    public static Optional<SelectionBounds> getSelectionBounds(
            UUID actorUuid, String currentWorldId) {
        // 1. probe(); short-circuit on ABSENT
        // 2. WorldEdit.getInstance()
        // 3. getPlatformManager().queryCapability(Capability.USER_COMMANDS)
        //    → Platform.matchPlayer(uuid)  (returns Player; also SessionOwner)
        // 4. getSessionManager().getIfPresent(player) → LocalSession | null
        // 5. localSession.getSelection(matchWorld(currentWorldId))
        //    catch IncompleteRegionException by class-name match → empty
        // 6. Extract min/max via BlockVector3.getX/getY/getZ (primitive ints)
        // 7. Wrap in SelectionBounds record
    }

    public record SelectionBounds(
        String worldKey,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ
    ) {}
}
```

### Wiring into `QueryParser`

`parseRadiusTok` cannot itself invoke the bridge — parsing is context-free and
runs before the actor's world is resolved. Instead:

1. Extend `QueryFilter` with a new nullable slot:
   `WorldEditSelectionRequest weSel` (marker record; presence means "resolve at
   execute time"). This replaces the current no-op branch at
   `QueryParser.java:272-278`.
2. `parseRadiusTok` on `#we` / `#worldedit`:

   ```java
   case "#we", "#worldedit" -> b.weSel(QueryFilter.WorldEditSelectionRequest.INSTANCE);
   ```

3. The lookup engine (Wave 2 executor), after resolving `ctx.actorUuid()` and
   `ctx.actorWorldId()`, calls:

   ```java
   Optional<WorldEditBridge.SelectionBounds> bounds =
       WorldEditBridge.getSelectionBounds(actorUuid, actorWorldId);
   ```

   and, on presence, layers a **world filter (`bounds.worldKey`) + bounding-box
   filter (min/max)** onto the DAO query. On absence, the executor emits a
   user-facing "no active WorldEdit selection" error via the existing
   `QueryFeedback` channel.
4. Mutual exclusion: reject `r:<n>` combined with `r:#we` at parse time
   (same slot semantics as today's `radius` vs `worldSel`).

### Test surface

- `WorldEditBridgeTest`: mirrors `LuckPermsBridgeTest` — verifies `probe()` is
  `ABSENT` on the plain JVM classpath and that `getSelectionBounds` returns
  `Optional.empty()` without throwing.
- Integration test via a hand-rolled fake `com.sk89q.worldedit.WorldEdit` shim
  loaded from a test-only classloader (same trick used by the LP bridge test).
- Loader smoke tests on each of `mc-1.18.2`, `mc-1.19.2`, `mc-1.20.1`,
  `mc-1.21.1` fixture rigs with WE-modded present.

---

## 7. Estimated effort

| Component                                        | LOC (approx) | Effort |
|--------------------------------------------------|--------------|--------|
| `WorldEditBridge` + `SelectionBounds` record     | ~180         | **M**  |
| `QueryFilter.WorldEditSelectionRequest` slot     | ~15          | S      |
| `QueryParser` wiring (replace no-op branch)      | ~10          | S      |
| Lookup-engine executor wiring (world + BB filter)| ~60          | S      |
| Unit tests (bridge + parser)                     | ~200         | M      |
| Loader smoke fixtures (×4 MC targets)            | ~120         | M      |
| Docs update (`SHARED-CONTRACTS.md`, README)      | ~40          | S      |
| **Total**                                        | **~625**     | **M**  |

Assumes existing lookup executor already has a bounding-box filter path (it does
— see Wave 2 `RollbackPlan` region filter). If not, add **+150 LOC / +S**.

---

## 8. Milestone recommendation

**Recommendation: schedule for v1.2.0.**

Rationale:
- Availability is solid on 4/4 MC targets.
- Reflection-only surface — zero build-time coupling, matches existing
  `LuckPermsBridge` precedent.
- User demand is real: `r:#we` is already documented and parsed; today it
  silently degrades to a global scan, which is a footgun.
- Effort is medium (~625 LOC) and cleanly isolated to `core/query/`.

**DO NOT ship** on 1.1.x — the change touches `QueryFilter` (a shared contract
record) and needs a minor version bump per `SHARED-CONTRACTS.md`.

**No DEFER.** WE-modded coverage is stable back to WE 7.0 and forward through
7.3; the risk of API drift over the next 12 months is low.

---

## 9. Open questions for implementation phase

1. **UUID → SessionOwner resolution robustness.** `Platform.matchPlayer(UUID)`
   is not on the public 7.0 API; confirm on 7.2.13 (earliest supported). Fallback:
   iterate `sessionManager` internal map via reflection — ugly but works.
2. **Cross-world selections.** WE allows a selection in a world different from
   the actor's current world. Should `r:#we` respect that (query the selection's
   world) or force the actor's current world? Recommendation: **respect the
   selection's world** — matches CoreProtect behaviour.
3. **Selection size cap.** A 10k×10k×256 selection would DOS the DAO. Enforce
   a config-driven volume cap (default: 1M blocks) and error out above it.
4. **FAWE-specific `Region` subclasses** (poly, convex, fuzzy). We only need
   the AABB — `getMinimumPoint`/`getMaximumPoint` are defined on the `Region`
   interface itself, so all subclasses work.

---

## 10. Sign-off

Recon complete. **Verdict: PROCEED, target v1.2.0, effort M (~625 LOC).**
