# PERF-NOTES-1.3.4.md — round-4 P1 close-out

Round-4 audit note archived alongside v1.3.4. This release ships a single
wave (**AA1**) whose scope is a defect-class sweep rather than any new
runtime behaviour, so the perf posture on the hot paths is unchanged from
v1.3.3.

## AA1 — sub-record canonical ctor sweep

### The defect

Round-4 perf audit (round-4 subagent-summary-1) flagged
`LogFile(4-arg)` at line ~842 of each cell's `GuardianCommands.java`.
Follow-up parent grep found a much larger, matching surface at
lines ~851-864: `Actions(18-arg)` on every `actions.*` case-arm except
the one added in v1.3.0 W4 (`actions.mixinHotEvents`).

Both boundaries are backward-compat shim ctors:

- `LogFile(4-arg)` defaults `forceSyncOnFlush` back to the pre-X6 value
  `true`, dropping any operator override.
- `Actions(18-arg)` defaults all 13 W1 CP-parity kill-switches
  (`logNaturalBreaks`, `logTreeGrowth`, `logMushroomGrowth`,
  `logVineGrowth`, `logSculkSpread`, `logPortals`, `logWaterFlow`,
  `logLavaFlow`, `logFireExtinguish`, `logCampfireStart`,
  `logHopperMetaFilter`, `logDuplicateSuppression`, `logCancelledChat`)
  back to their CP-parity out-of-box values, then also drops the W4
  `mixinHotEvents` flag (defaulted back to `true`).

The observable symptom: an operator who sets `actions.logWaterFlow=true`
(off by default because it's expensive), then later flips
`actions.logBlocks=false`, silently loses `logWaterFlow`. It also
means the W4 mixin-hot kill-switch that operators toggle **off** under
overload load gets silently toggled back **on** the next time they run
any other `/vg config set actions.*` command — actively re-drowning the
queue they were trying to protect.

Same class of bug as v1.3.3 Z1 (widened outer `GuardianConfig` boundary)
but at the sub-record boundary. Z1 didn't grep here.

### The fix (mechanics)

Every `new GuardianConfig.LogFile(...)` and `new GuardianConfig.Actions(...)`
call in every cell now uses the canonical arity: `LogFile(5-arg)` threads
`lf.forceSyncOnFlush()`, `Actions(32-arg)` threads all 13 W1 fields
via `a.logNaturalBreaks()` … `a.logCancelledChat()` plus `a.mixinHotEvents()`.

Fix distribution — **8 loader cells × (1 LogFile + 14 Actions arms) = 120
sites**:

- 8 `LogFile(4→5)` — `case "logFile.enabled"` arm in every cell.
- 112 `Actions(18→32)` — 14 `actions.*` arms per cell (the
  `actions.mixinHotEvents` arm was already canonical from W4 and left
  untouched by the sweep).

### Perf posture

Zero-cost fix on the hot path. Each canonical ctor invocation adds
exactly the same number of getter reads as the fields it now threads
through — reads that already run when the loaded config is validated
elsewhere. No new allocations, no new locks, no new synchronization.

`/vg config set` is not a hot-tick path (operator-triggered, once per
change), so even if the change were expensive it would be irrelevant.
It isn't.

### Non-canonical sites NOT fixed by AA1

The round-4 audit verified that `Guardian.java` (Z4) was already
canonical. AA1 confirmed this and did not touch it.

> **Retraction (v1.3.5 BB1 — 2026-07-03):** the original text of this
> section also claimed `ConfigLoader.java` (Y3) was verified canonical.
> That claim was **incorrect**. `ConfigLoader.migrateForwardCompat` had
> six `new GuardianConfig(...)` sites (lines 137, 150, 159, 173, 192,
> 273) using the pre-X8 11-arg back-compat ctor, silently resetting
> `rollback` to `Rollback.defaults()` whenever any forward-compat
> migration path fired. The round-4 audit's "backfill-safe" check did
> not verify that `rollback` was among the args threaded through. This
> is the same defect class as Z1 (outer 9→12) and AA1 (sub-record
> 4→5 / 18→32) at a boundary neither wave covered. The defect was
> caught by the round-5 parity audit and closed by v1.3.5 BB1 — see
> `PERF-NOTES-1.3.5.md`.

Grep in the wider codebase turned up additional legacy-shim call sites,
none of which are defects but all recorded here for future reference:

- `core/src/main/java/network/vonix/guardian/core/storage/dbmigrate/MigrateDbCommand.java:92`
  uses `Database(6-arg)` (canonical 7). This site materialises the
  destination backend descriptor from the `MigrationTarget` block for
  `/vg migrate-db`; the 6-arg shim explicitly nulls `migrationTarget`
  (correct — the destination has no cascade), and the 7th arg
  (`Hikari`) is filled in via `Hikari.defaults()`. Migration is a
  one-shot operator command with its own Hikari sizing already; leaving
  the shim in place is intentional and safe. Flagged for a future
  cleanup pass (out of scope for AA1).
- Every core test under `core/src/test/java/…` uses one or more legacy
  shims (`Database(5-arg)`, `LogFile(4-arg)`, `Actions(18-arg)`,
  `Actions(31-arg)`, `Permissions(2-arg)`). These are intentional —
  the shims exist so tests written before each widening keep compiling
  without an atomic monorepo signature migration, and each shim
  documents its default-choice per javadoc. Test suites round-trip
  through save/load and their own `withXxx` helpers, so a shim-dropped
  field is exercised at the level the test cares about. AA1 does not
  touch test call sites.
- `core/src/test/java/…/event/EventGateFastPathTest.java:27` uses
  `Actions(31-arg)` — the pre-W4 shim. Same story.

### Regression tests

`core/src/test/java/network/vonix/guardian/core/ConfigSetPreservesSubRecordFieldsTest.java`
mirrors the exact fixed cell code (`withLogFileEnabled`, `withActionsLogBlocks`)
and runs three suites:

1. `configSetLogFileEnabledPreservesForceSyncOnFlush` — flips
   `logFile.enabled=true` on a fixture with `forceSyncOnFlush=false`
   and asserts the field survives.
2. `configSetActionsLogBlocksPreservesAllW1Fields` — flips
   `actions.logBlocks=true` on a fixture with every W1 field flipped
   away from its CP default and asserts all 14 fields (13 W1 +
   mixinHotEvents) survive.
3. `everySubRecordFieldSurvivesUnrelatedSet` — parameterised over 18
   fields, verifying survival after both the LogFile and Actions
   set arms run.

If a future refactor regresses any cell to a shorter shim, the tests
in this suite fail before the cell code builds against the widened
record definition. The 18-arg → 32-arg / 4-arg → 5-arg mismatch is
observable at the pure-Java level regardless of loader.

## Post-v1.3.4

Round-5 audit follows. If clean → release / deploy / embed. If any P0
or P1 surfaces → v1.3.5 loop (explicitly acknowledged as possible).
