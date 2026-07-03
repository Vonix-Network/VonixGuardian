# NIGHTSHIFT-v1.3.5.md — round-5 P1 close-out

Base commit: `4328561` (v1.3.4 integration HEAD). Target: v1.3.5.

## Scope

Close v1.3.4's round-5 parity audit P1 defect:

- ConfigLoader.migrateForwardCompat has 6 top-level `new GuardianConfig(...)` sites using the pre-X8 11-arg back-compat ctor. All 6 silently drop `rollback` back to `Rollback.defaults()`.
- Perf audit stopped at "backfill-safe" but didn't check whether rollback was among the args. Real defect.
- Fix: thread `work.rollback() == null ? Rollback.defaults() : work.rollback()` in all 6 sites.

## Waves

Single wave BB1 — thread `rollback` in ConfigLoader's 6 migration ctor calls.

| Wave | Title | Priority | Files |
|---|---|---|---|
| BB1 | ConfigLoader.migrateForwardCompat rollback thread + version bump 1.3.5 | P1 | core/src/main/java/.../ConfigLoader.java (6 sites) + regression test + version bump + CHANGELOG + docs/PERF-NOTES-1.3.5.md; also correct docs/PERF-NOTES-1.3.4.md's incorrect "ConfigLoader canonical" claim |

## Definition of done

1. All 6 ConfigLoader ctor sites thread `rollback()`.
2. Regression test asserting a pre-X8 on-disk config with operator-set `rollback.explosionSupplementalReach=64` survives migration.
3. Correct PERF-NOTES-1.3.4.md's false claim.
4. Version bump 1.3.4 → 1.3.5.
5. `:core:build` + 4 loader cell builds pass.

## Post-v1.3.5

- Round-6 audit
- If clean → RELEASE, single fleet deploy, embed
- If P0/P1 surface → v1.3.6 loop
