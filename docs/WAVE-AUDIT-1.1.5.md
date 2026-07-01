# Wave-1 Audit — VonixGuardian v1.1.5 wiring findings

Branch inspected: `v1.1.5-entity-filter` @ commit `5508b7c` (v1.1.5 committed by the sister agent; tree clean).
Method: read-only, static inspection across all 8 loader cells + `core/`. Compile check attempted but the aggregate project fails at Fabric-loom apply time (`BuildSharedServiceManager$Inject cannot be cast`) — the known JDK-vs-loom pitfall, not a code issue. Findings are source-derived.

Companion doc: [`COREPROTECT-COMPARISON.md`](./COREPROTECT-COMPARISON.md).

---

## Counts

| Severity | Count |
|---|---|
| CRITICAL | **1** |
| HIGH | 4 |
| MEDIUM | 3 |
| LOW | 2 |
| Verified clean | 11 |

## Coverage checklist

| Sec | Area | Status |
|-----|-----|--------|
| A | Sister-cell drift (v1.1.5 whitelist path) | ✓ |
| B | Enum-expansion exhaustiveness (Rollback, EventGate) | ✓ — **1 CRITICAL found** |
| C | EventSubmitter / Guardian facade coverage | ✓ (37/37 methods) |
| D | Rollback-vs-record parity | ✓ — bug covered by CRITICAL |
| E | Config record positional args + migration | ✓ clean |
| F | Timing (`EntityJoin`.loadedFromDisk gate, deferred commands) | ✓ clean |
| G | Chat capture priority in all 4 Forge/NeoForge cells | ✓ clean |
| H | LivingDestroyBlockEvent guard order | ✓ clean |
| I | Purge safety floor routing | ✓ (8/8 cells route through `purgeEngine()`) |
| J | Radius shortcut `#nether`/`#overworld`/`#end` mapping | ✓ clean |
| K | QueryParser default-world folding | ✓ clean |
| L | Chat cancelled-event pass-through | ✓ clean |
| M | `./gradlew :core:compileJava` | ✗ (fabric-loom classloader — pitfall #11) |

---

## CRITICAL 1 — `RollbackPlan.isRollbackable` silent default swallows 14 of 19 handlers CHANGELOG v1.0.3 shipped

**File**: `core/src/main/java/network/vonix/guardian/core/rollback/RollbackPlan.java:96-108`

**What**:
```java
static boolean isRollbackable(Action a) {
    return switch (a.type()) {
        case BLOCK_PLACE, BLOCK_BREAK,
             CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
             ITEM_DROP, ITEM_PICKUP,
             ENTITY_KILL, EXPLOSION -> true;
        case CHAT, COMMAND, SIGN,
             SESSION_JOIN, SESSION_LEAVE,
             USERNAME_CHANGE -> false;
        // v0.1.0 expansion (15-39) lands in a follow-up wave; until the loader bridges
        // wire mutators for them, treat as not-rollbackable so the engine refuses cleanly.
        default -> false;
    };
}
```

Every one of the 25 v0.1.0 expansion enums (BURN, IGNITE, FADE, FORM, SPREAD, DISPENSE, PISTON_EXTEND, PISTON_RETRACT, BUCKET_EMPTY, BUCKET_FILL, LEAVES_DECAY, ENTITY_CHANGE_BLOCK, INVENTORY_DEPOSIT, INVENTORY_WITHDRAW, HOPPER_PUSH, HOPPER_PULL, ITEM_CRAFT, ENTITY_SPAWN, ENTITY_INTERACT, HANGING_PLACE, HANGING_BREAK, STRUCTURE_GROW, PORTAL_CREATE, CHUNK_POPULATE, CLICK) falls through to `default -> false`. The comment on line 105 acknowledges this was intentional in v0.1.0 ("lands in a follow-up wave") **but the follow-up wave was v1.0.3** — which does handle `BURN`, `IGNITE`, `FADE`, `LEAVES_DECAY`, `BUCKET_FILL`, `FORM`, `SPREAD`, `BUCKET_EMPTY`, `STRUCTURE_GROW`, `PORTAL_CREATE`, `ENTITY_CHANGE_BLOCK`, `HOPPER_PUSH`, `HOPPER_PULL`, `HANGING_BREAK` in `RollbackEngine.applyInverse/applyForward`.

Because `RollbackPlan.build()` filters via `isRollbackable` BEFORE the engine ever sees a row (line 60-63), **every case arm in `RollbackEngine.applyInverse` for those 14 types is dead code**. Users issuing `/vg rollback` against a griefing dragon (which produces `ENTITY_CHANGE_BLOCK` rows) get 0 rows dispatched and a "0 action(s) planned" result — same for lava-caused `IGNITE`, fire spread, ravager `BURN`, tree `STRUCTURE_GROW`, portal ignites, or hoppers.

**Why it matters**: This is the single largest observable rollback gap. CHANGELOG v1.0.3 markets "19 new rollback handlers" but 14 of them cannot execute. Cell-level tests never caught it because there is no test in `core/src/test` that asserts `isRollbackable(BURN) == true` or dispatches a plan built from a `BURN` row.

**Suggested fix**: Replace the `default -> false` in `RollbackPlan` with an exhaustive listing:

```java
static boolean isRollbackable(Action a) {
    return switch (a.type()) {
        case BLOCK_PLACE, BLOCK_BREAK,
             CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
             ITEM_DROP, ITEM_PICKUP,
             ENTITY_KILL, EXPLOSION,
             ENTITY_CHANGE_BLOCK,
             BURN, IGNITE, FADE, LEAVES_DECAY, BUCKET_FILL,
             FORM, SPREAD, BUCKET_EMPTY, STRUCTURE_GROW, PORTAL_CREATE,
             HOPPER_PUSH, HOPPER_PULL,
             HANGING_BREAK -> true;
        case CHAT, COMMAND, SIGN,
             SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE,
             DISPENSE, PISTON_EXTEND, PISTON_RETRACT,
             INVENTORY_DEPOSIT, INVENTORY_WITHDRAW,
             ITEM_CRAFT, ENTITY_SPAWN, ENTITY_INTERACT,
             HANGING_PLACE, CHUNK_POPULATE, CLICK -> false;
    };
}
```

Also delete `RollbackEngine.isRollbackable(ActionType)` at lines 477-488 (same bug, currently unused — see HIGH 1) and consolidate to a single source of truth in `ActionType.isRollbackable()` or a `Rollbackability` utility class.

**Also add a regression test** in `core/src/test` that asserts `RollbackPlan.isRollbackable(a)` for each of the 14 handlers is `true`, and that `RollbackPlan.build(List.of(burnRow, igniteRow, spreadRow))` produces a non-empty `ordered` list.

---

## HIGH

### HIGH 1 — Twin `isRollbackable` in `RollbackEngine` has same silent-default bug

**File**: `core/src/main/java/network/vonix/guardian/core/rollback/RollbackEngine.java:477-488`

Same silent `default -> false` as CRITICAL 1. Currently unused by production paths (engine relies on `RollbackPlan.isRollbackable`), but keeping the twin around invites drift. CHANGELOG v1.0.3 explicitly moved to "explicit refusals" in `applyInverse/applyForward` precisely to eliminate this pattern — the leftover switch is a landmine.

**Fix**: Delete the method + its `@Test`; consolidate to `ActionType.isRollbackable()`.

### HIGH 2 — `stripMobPrefix` duplicated verbatim in 4 sister cells

**Files**:
- `mc-1.18.2/forge/…/ForgeEvents.java:225-228`
- `mc-1.19.2/forge/…/ForgeEvents.java:225-228`
- `mc-1.20.1/forge/…/ForgeEvents.java:210-213`
- `mc-1.21.1/neoforge/…/NeoForgeEvents.java:205-208`

Identical 3-line static method copy-pasted into all four cells. Pure string operation, zero MC-version dependency. Belongs in core alongside `VanillaGrieferSet`.

**Why it matters**: The next sentinel-format change (e.g. adding a version tag like `#mob:v2:namespace:path` for 1.22 registry-holder rewrites) will have to be applied in four places; a single missed cell = "modded dragons suddenly log again on 1.20.1 only." Classic sister-cell drift.

**Fix**: Move to `core.filter.VanillaGrieferSet`:
```java
public static String stripMobSentinel(String sentinel) {
    if (sentinel == null || !sentinel.startsWith("#mob:")) return null;
    return sentinel.substring(5);
}
```
Then in each cell: `String entityKey = VanillaGrieferSet.stripMobSentinel(EntitySentinel.of(e.getType()));`.

### HIGH 3 — v1.1.5 vanilla-griefer allowlist NOT ported to any Fabric cell

**Files**: `mc-1.{18.2,19.2,20.1,21.1}/fabric/…/FabricEvents.java` (`grep -l VanillaGrieferSet mc-*/fabric` → 0 hits)

CHANGELOG says "1.1.5 is Forge/NeoForge only", which matches `git show 5508b7c --stat` — no Fabric file touched. Fabric's block-place gap was already accepted per v1.0.4, so this is not a regression, but the flood defence is now asymmetric across loaders. If an operator with a mixed Fabric+Forge dragon-modpack fleet reports Berk-level floods on Fabric, the answer is not "raise the coalescer window" — it's "port the whitelist."

**Fix**: File a follow-up issue: "port `VanillaGrieferSet.shouldRecord()` gate to Fabric event path once Fabric block-place lands (v1.0.5+ mixin wave)." No code change in v1.1.5. Track alongside CHANGELOG v1.0.4 P0 backlog.

### HIGH 4 — Coalescer runs post-whitelist by convention, not enforcement

**File**: `core/src/main/java/network/vonix/guardian/core/Guardian.java:410-429`

The listener flow in each Forge cell does the whitelist check first, then calls `submitEntityChangeBlock` which then runs the coalescer. Order is correct **at the listener boundary today**. However, if any cell were to regress and call `submitEntityChangeBlock` from a code path that skips the whitelist (e.g. a future `onNeighborChanged`), coalescer state would be polluted by ambient-mob keys and eviction pressure would silently degrade the flood defence.

**Fix**: Move the whitelist check into `submitEntityChangeBlock` itself so it's enforced centrally, and add a Javadoc precondition. This also enables removing the four sister-cell copies of `stripMobPrefix` (HIGH 2) as a nice cascade.

---

## MEDIUM

### MEDIUM 1 — `wind_charge` / `breeze_wind_charge` in `DEFAULT_ALLOWLIST` are permanently dead

**File**: `core/src/main/java/network/vonix/guardian/core/filter/VanillaGrieferSet.java:47-48`

`WindCharge` and `BreezeWindCharge` are `Projectile` subclasses in 1.21+, not `LivingEntity`. `LivingDestroyBlockEvent.getEntity()` returns `LivingEntity`; a projectile can never be the argument. These two entries in `DEFAULT_ALLOWLIST` can never match.

No wrong behavior (dead entries are just noise), but the comment ("1.21+ wind physics") implies they *do* match, which will confuse the next maintainer chasing a "wind charge grief not logged" bug report.

**Fix**: Remove lines 47-48 and update the Javadoc block at 32-36 to say "hardcoded LivingEntity types only; projectiles are handled by `ExplosionEvent.Detonate`." If projectile-driven block change is desired, add a new listener + `WindChargeSet` in a follow-up.

### MEDIUM 2 — Hot-path string allocations on the whitelist reject path

**Files**:
- `mc-1.18.2/forge/…/ForgeEvents.java:203`
- `mc-1.19.2/forge/…/ForgeEvents.java:205`
- `mc-1.20.1/forge/…/ForgeEvents.java:190`
- `mc-1.21.1/neoforge/…/NeoForgeEvents.java:185`

Hot path per `LivingDestroyBlockEvent` firing (100k+/sec on modded packs pre-whitelist; the whole point of the whitelist is to be quick): `EntitySentinel.of(e.getType())` builds `"#mob:" + namespace + ":" + path`, then `stripMobPrefix` allocates a substring. Two throwaway strings per event just to check a `Set.contains`.

Post-fix worst-case (few k/sec), still 400 KB/sec of garbage on the reject path alone.

**Fix**: Add `EntitySentinel.registryKeyOf(EntityType<?>)` returning `"minecraft:ravager"` directly (no `#mob:` prefix, no substring dance), and have `VanillaGrieferSet.shouldRecord` accept that. Old `.of(...)` stays for sentinel-string use cases (attribution names).

### MEDIUM 3 — `RollbackEngine.run` planned-0 early-exit is unclear

**File**: `core/src/main/java/network/vonix/guardian/core/rollback/RollbackEngine.java:135`

`if (preview || planned == 0) return new RollbackResult(..., 0);` returns before `dao.openRollbackBatch`. Fine (no batch opened for zero mutations), but skips `dao.markRolledBack` too. For a `preview=false` restore of a set of rows that are all non-rollbackable, `dispatched` returns 0 and no DB flag change is attempted — correct — but the code path has two exit paths that look similar.

**Fix**: Add `// planned==0 → nothing to dispatch and nothing to mark; skip batch record` on line 135.

---

## LOW

### LOW 1 — `entityChangeLogAllEntities` boolean default depends on Gson primitive default

**File**: `core/src/main/java/network/vonix/guardian/core/config/ConfigLoader.java:113-114`

Javadoc claims `entityChangeLogAllEntities` has no backfill, but it's silently accepted as `false` by Gson for missing field. True — but only because primitive `boolean` fields in records default to `false` when Gson deserializes a missing key. Worth mentioning explicitly so a future refactor to `Boolean` doesn't silently regress this to `null` (which would NPE inside `VanillaGrieferSet.shouldRecord` because it passes a boxed primitive into an `if (logAllEntities)`).

**Fix**: Append "(depends on `boolean` primitive default; do not change the field type to `Boolean`)" to the Javadoc.

### LOW 2 — `HANGING_PLACE removeHangingAt` TODO open since v1.0.3

**File**: `core/src/main/java/network/vonix/guardian/core/rollback/RollbackEngine.java:323-325`

`// TODO: add WorldMutator.removeHangingAt(...) so this can be honoured.` CHANGELOG v1.0.3 shipped without it. v1.1.0 (adversarial CP-fidelity review) didn't fix it. Still a TODO in v1.1.5.

Low harm (best-effort refuse-with-warn), but should be tracked as an issue instead of a floating TODO with no ticket reference.

**Fix**: Change to `TODO(#issue-XX)` after filing.

---

## Verified clean

The audit inspected and confirmed the following subsystems have no wiring bugs:

1. **Sister-cell drift for the whitelist path** — the four Forge/NeoForge cells all use the identical guard order: `sub()/cfg() null → return; e null → return; whitelist → return; attribution → coalescer → submit`. MC-version differences are exactly the expected ones (`getEntityLiving()` in 1.18.2, `getEntity()` in 1.19+; `e.level` field in 1.18.2/1.19.2, `e.level()` method in 1.20+; `getWorld()` vs `getLevel()`). No functional drift.
2. **Chat capture `@SubscribeEvent(priority = HIGHEST, receiveCanceled = true)`** — confirmed identical on all four Forge/NeoForge cells: `ForgeEvents.java` line 408 (1.18.2), 408 (1.19.2), 393 (1.20.1); `NeoForgeEvents.java` line 388 (1.21.1).
3. **`loadedFromDisk()` guard on entity-join spawn recorder** — present in all four Forge/NeoForge cells (1.18.2:327, 1.19.2:327, 1.20.1:312, 1.21.1:309).
4. **`RollbackEngine.applyInverse`/`applyForward`** — exhaustive switch, all 39 `ActionType` constants either dispatched to `WorldMutator` or explicit `LOG.warn(...refusing to roll back...)`. No `default -> ...` arm remains. (Lines 268-332 and 337-401.) **But downstream of the CRITICAL 1 bug — see above; these explicit refusals for the expansion types are the only ones observable, since the true-rollbackables get filtered upstream and never reach here.**
5. **`EventGate.shouldLog` / `typeEnabled`** — `Category` switch is exhaustive (all 8 categories); nested `MESSAGE` switch has a benign `default -> true` for non-CHAT/COMMAND/SIGN message types (only USERNAME_CHANGE is in that category and *should* default-on since there is no `logUsernameChange` toggle). No silent-drop bug.
6. **`GuardianConfig.Actions` record positional layout matches `defaults()` and `migrateForwardCompat`** — record: 11 booleans, 3 lists, long, int, list, boolean (18 components). `defaults()` at `GuardianConfig.java:197-204` and `migrateForwardCompat` at `ConfigLoader.java:139-146` both pass 18 args in the same order. No positional drift.
7. **`ConfigLoader.migrateForwardCompat` allowlist backfill** — null-list detection correct; produces empty `ArrayList`, not `List.of()`, so callers that mutate won't hit `UnsupportedOperationException`. `logAllEntities` boolean primitive defaults to `false` correctly.
8. **`GuardianConfig.validate`** — `entityChangeAllowlist` null-element check is present at line 297. Missing checks for `entityBlockChangeCoalesceWindowMs < 0` and `entityBlockChangeMaxTracked < 0`, but `ConfigLoader.migrateForwardCompat` never produces negative values and Gson can only produce 0 or the parsed value — non-issue.
9. **`EventSubmitter` / `Guardian` facade coverage** — both have exactly 37 `submit*` methods (39 ActionTypes minus `SESSION_JOIN`/`LEAVE` which use their own submitters, minus `USERNAME_CHANGE` which is a session-side effect; `grep -c "^\s*void submit"` = 37 on `EventSubmitter.java`, matching `Guardian.java`).
10. **`/vg purge` routing** — all 8 loader cells route through `g.purgeEngine().purge(filter, minAgeSeconds)` at ~line 433/437/438, not `dao().purge(...)` directly. Source-based floor (console 24h / in-game 30d) enforced inside `PurgeEngine.purge`.
11. **Radius shortcut mapping** — `QueryParser.java:282-284` produces canonical `minecraft:the_nether` / `minecraft:overworld` / `minecraft:the_end` (not bare tokens). `#world_<key>` passthrough at line 286-293.
12. **`QueryFilter.withDefaultWorld`** — called by every player-issued `/vg lookup|near|rollback|restore` in all 8 cells (`grep -rn "withDefaultWorld"` = 8 cells × 4 command sites each).
13. **`ActionType` constants and ids** — 39 constants, ids 1..39 dense, no reordering since v0.1.0; token map, category map, id map built at class-load; no drift.

---

## Not inspected (out of scope for this audit)

- **`AbstractJdbcDao` MySQL/Postgres dialect subclasses** — CHANGELOG v1.1.2 fix, covered by the CP-comparison storage worker.
- **`GuardianSuggestions` tab-completion of all 39 `ActionType` tokens** — covered by the CP-comparison commands worker (found the 12-token advertising-vs-parser bug — see [`COREPROTECT-COMPARISON.md § 1.6`](./COREPROTECT-COMPARISON.md#16-tab-completion-coverage)).
- **`Help.run` completeness** — cosmetic; covered by commands worker.
- **`./gradlew :core:test`** — Fabric-loom classloader failure (JDK/loom mismatch, known pitfall) prevents any gradle-driven task. Would need `--project-dir core` with a detached `settings.gradle`, out of scope.

Also flagged by the CP-comparison workers (not part of this wiring audit but real bugs — surface them here for a single actionable list):

- 🟥 **`GuardianSuggestions.ACTIONS` L62-75 advertises 12 tokens the parser rejects** (`+kill`, `-kill`, `+chat`, `-chat`, `+command`, `-command`, `+click`, `-click`, `+sign`, `-sign`, `+username`, `-username`). Tab-complete → `QueryParseException("unknown action")` at execution. Add a test asserting `for each ACTIONS[i] : ActionType.byToken(ACTIONS[i]).isPresent()`.
- 🟥 **`EventSubmitter` declares `submitBurn`/`Ignite`/`Fade`/`Form`/`Spread`/`Dispense`/`LeavesDecay` — zero matching `@SubscribeEvent` handlers on any loader.** Config toggle `logWorldEvents=true` is a lie for these families. Wire the listeners or delete the submit methods from the public API.
- 🟥 **NeoForge 1.21.1 `BUCKET_EMPTY`/`FILL` unwired** (`FillBucketEvent` removed upstream; TODO at `NeoForgeEvents.java:636-638`). CHANGELOG v1.0.4 claim of "0% → 100% Forge-family coverage" is inaccurate for 1.21.1.
- 🟥 **`HANGING_PLACE`/`HANGING_BREAK` refuse-rollback wired but no submit path visible via grep** — audit-only rows may never populate.
- 🟥 **`/vg undo` is a history-pop, not a world revert** (`GuardianCommands.java:457-470`) — semantic mismatch with CP contract.
- 🟥 **`/vg reload` is a stub** (`GuardianCommands.java:540-541`) — every config field is currently restart-required.
- 🟥 **`r:#worldedit` / `r:#we` accepted as no-op** (`QueryParser.java:272-278`) — silently returns global scope with no WorldEdit integration.
- 🟥 **`#optimize` accepted at parser but no-op at storage layer** (0 grep hits in `core/…/storage`).
- 🟥 **`core/build.gradle:10` applies `maven-publish`** but no `publishing {}` block exists in the repo — plugin loaded, never configured.

---

## Recommended action order for the sister agent

1. **Fix CRITICAL 1 first** (`RollbackPlan.isRollbackable` — 20 lines + 1 regression test). This is the highest-value fix in the whole audit. Ship as v1.1.6 patch.
2. **Wire the missing world-events family** (`submitBurn`/`Ignite`/`Fade`/`Form`/`Spread`/`Dispense`/`LeavesDecay`) or delete the promise from `EventSubmitter`. Ship as part of v1.1.6.
3. **Fix `GuardianSuggestions.ACTIONS` advertising 12 rejected tokens** — either strip the invalid entries or teach `QueryParser` to accept them (they map to reasonable action tokens). Add a test asserting parser accepts everything the suggester offers. Ship as v1.1.6.
4. **Move `stripMobPrefix` to `VanillaGrieferSet` + centralize the whitelist check in `submitEntityChangeBlock`** (HIGH 2 + HIGH 4) — one refactor covers both. Ship as v1.1.6.
5. **Delete the twin `RollbackEngine.isRollbackable`** (HIGH 1) — cleanup, ship as v1.1.6.
6. **Wire NeoForge 1.21.1 bucket events** via mixin — needs the mixin wave (v1.0.5 milestone was renamed; likely v1.2.0). Real gap.
7. **Wire hanging place/break `submit*` paths** or delete refusal cases from `RollbackEngine` — same milestone as bucket.
8. **Land `/vg undo` real-revert semantics** — plan wave, not a patch.
9. **Land `/vg reload` handler** — plan wave.
10. **Configure `publishing {}` block** — ship a maven artifact. Cheap once done.

Items 1-5 are surgical patches — a v1.1.6 tag is reasonable in the same day.
Items 6-10 need real design + milestones.

---

## References

- Full comparison synthesis: [`COREPROTECT-COMPARISON.md`](./COREPROTECT-COMPARISON.md)
- Raw wave-1 subagent reports: `/tmp/vg-vs-cp/wave1-{01..06}-*.md`
- Raw wiring-audit report: `/tmp/vg-vs-cp/wiring-audit-report.md`
- Frozen CP docs snapshot: `/tmp/vg-vs-cp/CP-DOCS-SNAPSHOT.md`
