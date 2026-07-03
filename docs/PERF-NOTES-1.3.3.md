# PERF-NOTES-1.3.3.md

Wave-by-wave notes on the shape of v1.3.3 changes that touch schema, DAO, or
hot-path allocation. Owners: each wave appends their own paragraph at wave
close; do not rewrite prior sections.

## Z1 — /vg config set canonical ctor (2026-07-03)

**Wave scope.** The round-3 parity audit's P0-A finding: every `/vg config
set <key> <value>` in all 8 loader cells rebuilt `GuardianConfig` via the
9-arg backward-compat constructor. That overload defaults `storage`,
`rollback` and `language` to `defaults()`, so setting any unrelated key
(theme, actions.logBlocks, purge.autoPurgeTime, …) silently reverted every
widened section to defaults on disk and in memory. The escalation from P2
to P0 in round-3 came from Y1's NBT surface activation: post-Y1,
`storage.persistNbt=true` was the toggle that turned on the whole NBT
fidelity pipeline, and any `/vg config set` command turned it back off.

**Hot-path cost.** Zero. The change is a widened parameter list on a
command-time (not tick-time) helper; the write path is unchanged for
callers who already used the canonical ctor. The rebuild happens once per
`/vg config set` invocation, followed by validate + save + reload. Reload
is the expensive step and Z1 does not touch it — Y3's optimizations stand.

**Allocation profile.** The refactor adds two field reads per rebuild
(`c.storage()`, `c.rollback()`) and drops nothing. Each `withValue` case
allocates one `GuardianConfig` record (12 fields, pure references, ~64
bytes on HotSpot). The three new cases (`storage.persistNbt`,
`rollback.explosionSupplementalReach`, `language`) each allocate one
additional wrapper (`GuardianConfig.Storage(bool)` or
`GuardianConfig.Rollback(int)`) — same shape as the other per-section
sub-record allocations already there for `LogFile`, `Lookup`, `Privacy`,
`Purge`.

**Testing surface.** `ConfigSetPreservesSectionsTest` (core-side, 6 cases)
mirrors the exact per-cell 12-arg pattern and pins:
- `withTheme(c, "dark")` preserves storage.persistNbt / rollback reach / language
- `withActions(c, muted)` preserves the same three sections
- round-trip save+load through `ConfigLoader` preserves persistNbt
- `withStoragePersistNbt(c, true)` end-to-end through `Guardian.reloadConfig`
  actually enables the `Guardian.persistNbt()` config gate and reports
  `storage.persistNbt` in `ReloadResult.hotSwapped()` (Y3 wiring)
- language and rollback-reach also survive unrelated `set theme` calls

The core-side test cannot invoke the private per-cell helper, but the
canonical 12-arg form it mirrors is exactly what the 8 fixed cells now
produce. `ReloadPreservesSectionsTest` (Y3) catches any regression at
reload time; this suite catches any regression at set time.

**Standing rule reinforced (from vg-config-widening-reload-and-boot-plumbing.md).**
Widening `GuardianConfig` has THREE silent-drop sites, not two:
`Guardian.reloadConfig` merged builder, `Guardian.boot()` engine ctor, and
the per-cell `/vg config set` write path (`withValue` + `withActions` in
all 8 cells). Y3 fixed the first two for X1 + X8; Z1 fixes the third for
X1 + X8 + language. Every future widening MUST verify all three or write a
justification.
