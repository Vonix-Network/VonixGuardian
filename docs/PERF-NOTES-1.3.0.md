# PERF-NOTES-1.3.0

Wave-by-wave perf/correctness notes for the v1.3.0 async/perf pass. Each
paragraph is a self-contained delta added by a single wave; the wave owner
appends here at close.

## W5 — Explosion rollback fidelity (CoreProtect parity)

Before v1.3.0 the `RollbackEngine` filtered EXPLOSION rows purely by the
blast-center coord recorded on `Action.x/y/z`. That coord is one point;
the actual TNT damage — the affected-block list already stored verbatim in
`Action.targetId` by `EventSubmitter.submitExplosion` — was only consulted
at mutation time. Two user-visible consequences:

1. `/vg rollback r:5 t:2m` centered on the crater restored ONE block (the
   center) instead of the whole affected area.
2. A player standing at the outer edge of a large blast running the same
   command found ZERO matching rows: the row's center coord was outside
   their radius even though the damage reached them.

W5 fixes both by adopting CoreProtect's "loop through the affected-list at
rollback time" model. `RollbackEngine.plan()` now runs an inexpensive
supplemental scan for EXPLOSION rows whose center falls outside the
caller's radius but whose affected-list crosses into it (via
`ExplosionAffectedList.anyWithinRadius`); the `applyInverse` / `applyForward`
EXPLOSION branches iterate every affected coord and issue one
`mutator.setBlock` per block, restoring pre-blast state (or re-clearing for
restore). No changes to storage — one row per explosion, same 4 KiB target
cap; only the rollback engine's interpretation of that row changed.
Regression coverage: `ExplosionAffectedListTest` (15 tests, parse/serialize
round-trip, malformed-entry tolerance, radius intersection) and
`ExplosionRollbackFidelityTest` (6 tests, the two before/after scenarios
from the wave brief plus restore direction, out-of-range, non-EXPLOSION
filter, and `#global` short-circuit).
