# PERF-NOTES-1.3.5.md — BB1 rollback thread through ConfigLoader.migrateForwardCompat

**Wave:** BB1 (single-wave release)
**Base:** `4328561` (v1.3.4 integration HEAD)
**Branch:** `feature/v1.3.5-bb1-configloader-rollback`
**Date:** 2026-07-03

## Scope

Close the last known v1.3.4 round-5 parity audit P1: silent revert of
operator-set `rollback.explosionSupplementalReach` on any pre-X8
forward-compat migration path.

## Defect analysis

`core/src/main/java/network/vonix/guardian/core/config/ConfigLoader.java`
holds six `new GuardianConfig(...)` sites inside `migrateForwardCompat`:

| Site | Branch | Trigger |
|---|---|---|
| :137 | purge.autoPurgeTime backfill | pre-W3-B4 config |
| :150 | permissions.perNodeOpLevels backfill | pre-W3-B8 config |
| :159 | language backfill | pre-W5-06 config |
| :173 | storage backfill | pre-v1.3.1 X1 config |
| :192 | database.hikari backfill | pre-v1.3.1 X6 config |
| :273 | actions W5-07 CP-parity rewrite (terminal `return`) | pre-W5-07 config or backfill-needed actions |

All six sites passed 11 args (…, `work.storage()`, `work.theme()`,
`work.language()`) — one arg short of the canonical 12-arg
`GuardianConfig(database, queue, logFile, actions, permissions, lookup,
privacy, purge, storage, rollback, theme, language)` order. That routed
every call through the pre-X8 back-compat constructor at
`GuardianConfig.java:66`, which unconditionally sets
`rollback = Rollback.defaults()`.

Consequence: an operator who set
`rollback.explosionSupplementalReach=64` on a modded server (dialled up
for pack-specific mega-explosives) would have their value silently
reverted to 16 on the first server startup whose config passed through
any pre-X8 backfill branch — the exact operator population most likely
to have knob overrides worth preserving.

No NPE, no crash: the compact canonical ctor at `GuardianConfig.java:57`
catches `rollback == null` and substitutes defaults on the way in. That
is *also* why the defect survived round-4's "backfill-safe" audit —
the code executes cleanly, it just erases operator intent.

## Fix

At each of the six sites, insert the 10th positional arg as
`work.rollback() == null ? GuardianConfig.Rollback.defaults() : work.rollback()`.
The null-check mirrors the existing storage/language null-guards on the
same call sites and defends against any future call path that somehow
delivers a null rollback before the compact ctor's backfill would fire.

Diff shape:

```diff
             work = new GuardianConfig(
                 work.database(), work.queue(), work.logFile(), work.actions(),
                 work.permissions(), work.lookup(), work.privacy(), newPurge,
                 work.storage() == null ? GuardianConfig.Storage.defaults() : work.storage(),
+                work.rollback() == null ? GuardianConfig.Rollback.defaults() : work.rollback(),
                 work.theme(), work.language() == null ? "en_us" : work.language()
             );
```

The 12-arg canonical ctor is now the target of every call — the
pre-X8 shim at `GuardianConfig.java:66` is no longer reached from
`ConfigLoader`.

## Perf posture

Zero-cost on the hot path. `migrateForwardCompat` runs exactly once per
`ConfigLoader.load` (config load is a boot/reload event, not a
per-tick path). Each of the six sites adds one getter read + one
null-check + one ternary — none of which allocate. `Rollback.defaults()`
allocates a single small record with one int field, only when
`work.rollback()` is null (never happens post-compact-ctor except on
paths that construct fresh `work` values without threading rollback —
which no path now does).

## Regression tests

Two new test files under
`core/src/test/java/network/vonix/guardian/core/config/`:

1. **`PreX8ConfigPreservesRollbackTest`** — two tests:
   - `pre_w5_07_rewrite_preserves_custom_rollback_reach` exercises the
     terminal ctor at `ConfigLoader:273` (W5-07 rewrite branch) with a
     config that has all 13 CP-parity toggles false + rollback
     `explosionSupplementalReach=64`. Post-load: reach must remain 64.
   - `pre_w3_b4_purge_backfill_preserves_custom_rollback_reach`
     exercises the first ctor at `ConfigLoader:137` (purge backfill
     branch) with `autoPurgeTime` absent + rollback
     `explosionSupplementalReach=32`. Post-load: reach must remain 32.

2. **`FullyPreX8ConfigMigratesRollbackDefaultsTest`** — two tests:
   - `pre_x8_config_no_rollback_section_uses_defaults_after_migration`
     — genuinely-pre-X8 config with no `rollback` JSON key + pre-W3-B4
     autoPurgeTime absence. Migration must not throw; post-load
     rollback == `Rollback.defaults()` (reach=16).
   - `pre_x8_config_no_backfill_needed_stays_at_defaults` — post-X8
     but rollback-omitted config, no backfill branches fire. Compact
     ctor handles it cleanly at `GuardianConfig:57`; post-load reach=16.

## Non-canonical top-level `GuardianConfig` sites in the wider codebase

Grep of every `new GuardianConfig(` outside `ConfigLoader` sub-record
ctors:

| File | Line | Arity | Verdict |
|---|---|---|---|
| `core/…/config/ConfigLoader.java` | 137, 150, 159, 173, 192, 273 | now 12-arg | **FIXED by BB1** |
| `core/…/config/GuardianConfig.java` (`defaults()`) | 606 | 12-arg | canonical, ok |
| `core/…/Guardian.java` (Y3 reload merge) | 527 | 12-arg | canonical, ok (Y3 fix) |
| `core/…/storage/dbmigrate/MigrateDbCommand.java` | 94 | **9-arg** (pre-X1/pre-X8) | **latent same-defect-class** — see below |
| `core/…/config/GuardianConfigHikariTest.java` | 38, 55, 71 | 12-arg | test-only, ok |
| 8 loader cell `GuardianCommands.java` | 3 sites each × 8 cells | 12-arg | canonical, ok (Z1 + AA1) |

### Report: `MigrateDbCommand.java:94` (out of scope for BB1)

`MigrateDbCommand.materialiseDest` constructs a wrapper
`GuardianConfig` for the destination backend using the pre-X1 9-arg
back-compat ctor at `GuardianConfig.java:89`:

```java
GuardianConfig destWrapper = new GuardianConfig(
    destCfg,
    g.config().queue(), g.config().logFile(), g.config().actions(),
    g.config().permissions(), g.config().lookup(), g.config().privacy(),
    g.config().purge(), g.config().theme());
```

This is a **latent same-defect-class site**. The wrapper is short-lived
(built → `StorageFactory.open(destWrapper)` → `destDao.init()` → close),
and none of the fields it drops (`storage`, `rollback`, `language`)
influence the migration copy semantics. So the site is **not a live
runtime defect today**. But it is architecturally identical to the six
sites BB1 just fixed:

- adds a widening trap if `MigrateDbCommand`'s dependency surface ever
  starts reading `destWrapper.storage()` / `.rollback()` (e.g. if
  MysqlDao/PostgresDao start consulting `storage.persistNbt` at
  `init()` time — plausible for future NBT-aware backend tuning),
- fails the same "canonical everywhere" invariant that Z1 + AA1 + BB1
  are pushing toward.

**Recommendation (not fixed by BB1 — reported only):** in a future v1.3.6
or v1.4.0 cleanup wave, widen `MigrateDbCommand.java:94` to the canonical
12-arg form threading `g.config().storage()`, `g.config().rollback()`,
`g.config().language()`. Zero behaviour change today, one less trap
tomorrow. Same fix pattern as BB1.

### Report: `GuardianConfigHikariTest.java` (test-only)

Three test-only ctor sites, all already 12-arg canonical. Not a defect
even if they used a shim — test files are permitted to use back-compat
ctors so the shims themselves stay exercised. No action.

## Build gate

- `./gradlew -PbuildProfile=coreonly :core:build` — must pass with 4
  new tests green.
- `./gradlew -PbuildProfile=forgeonly :mc-1.18.2:forge:build
  :mc-1.19.2:forge:build :mc-1.20.1:forge:build
  :mc-1.21.1:neoforge:build -x test` — must pass on all 4 loader cells.

## Round-6 audit posture

BB1 closes the last known parity defect from the round-5 audit. The
`MigrateDbCommand.java:94` latent site is documented above; it is not a
release blocker for v1.3.5. If round-6 comes back clean, VG v1.3.5 is
release-ready.
