# NIGHTSHIFT-v1.3.4.md — round-4 P1 close-out

Base commit: `8c8b28f` (v1.3.3 integration HEAD). Target: v1.3.4.

## Scope

Close v1.3.3's round-4 perf audit P1 defect **at the general class**, not just the reported instance:

- Round-4 flagged `LogFile(4-arg)` at line 842 in each cell — drops `forceSyncOnFlush`.
- On deeper inspection: `Actions(18-arg)` at lines 851-864 in each cell also drops 13 W1 fields.
- Likely more sub-record boundaries with the same pattern. Full canonical audit needed.

## Root cause pattern

Each Z-wave fixed the OUTER `GuardianConfig(9-arg)` → `GuardianConfig(12-arg)` canonical shift. But sub-record boundaries widened over the release history:
- `LogFile` widened 4→5 args in v1.3.0 W4 (`forceSyncOnFlush`)
- `Actions` widened 19→32 args across v1.3.0-v1.3.2 (13 new W1 kill-switch flags)
- Others may have widened too (Storage/Rollback are 1-arg since v1.3.1 X1/X8 — likely fine)

Every `/vg config set` case that reconstructs a sub-record must use the canonical ctor threading the widened fields via getter calls.

## Waves

Single wave AA1 — batch-fix ALL sub-record boundaries across all 8 cells.

| Wave | Title | Priority | Files |
|---|---|---|---|
| AA1 | Sub-record canonical ctor sweep + version bump 1.3.4 | P1 | 8 × common/GuardianCommands.java; add regression tests for every sub-record field |

## Definition of done

1. Grep every `new GuardianConfig.X(` call in all 8 GuardianCommands.java.
2. Verify each matches canonical arg count from GuardianConfig.java record defs.
3. Fix any non-canonical calls.
4. Add regression test asserting each sub-record's widened field survives `/vg config set` round-trip.
5. Version bump 1.3.3 → 1.3.4 + CHANGELOG entry.
6. All 4 loader cell builds pass.

## Post-v1.3.4

- Round-5 audit
- If clean → release, deploy, embed
- If P0/P1 surface → v1.3.5 loop (accept the possibility)
