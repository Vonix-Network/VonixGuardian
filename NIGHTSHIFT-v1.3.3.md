# NIGHTSHIFT-v1.3.3.md — round-3 defect close-out

Base commit: `7e2d4b1` (v1.3.2 integration HEAD). Target: v1.3.3.

## Scope

Close v1.3.2's audit findings:
- 1 P0 (P0-A): `/vg config set` uses 9-arg lossy `GuardianConfig` ctor across all 8 cells
- 3 P1 (P1-A/B/C): Forge cells' natural-block + hopper + fill/setblock action types have zero coverage (15 dormant mixin files)
- 2 P2 (G-Y3-1, G-Y3-2): reload swap order + AutoPurgeScheduler reschedule race

## Waves

| Wave | Title | Priority | Files owned |
|---|---|---|---|
| Z1 | Fix /vg config set canonical ctor + version bump 1.3.3 | P0 | 8 × common/GuardianCommands.java (withValue + withActions), gradle.properties, GuardianAPI, CHANGELOG |
| Z2 | Forge natural-block event-bus fallback + orphan cleanup | P1 | 3 × ForgeEvents.java (Fire/Ice/Leaves/Spread/Dispense event handlers); delete 15 dormant mixin files |
| Z3 | Forge hopper + fill/setblock event-bus fallback + orphan cleanup | P1 | 3 × ForgeEvents.java (Hopper tick, CommandEvent for /fill //setblock); delete remaining dormant mixin files |
| Z4 | Reload swap order + AutoPurgeScheduler finally guard | P2 | Guardian.java (reload gate-before-config swap), AutoPurgeScheduler.java (synchronize finally scheduleAt) |

## Parallelization

Z1 owns 8 cells' GuardianCommands.java — no conflict with Z2/Z3/Z4.
Z2 + Z3 both edit ForgeEvents.java — MUST serialize (Z2 first, Z3 rebase).
Z4 owns Guardian.java + AutoPurgeScheduler.java — no conflict with Z1/Z2/Z3.

Wave shape:
- **Z-α (parallel)**: Z1 + Z2 + Z4 fire together (Z3 waits behind Z2 due to ForgeEvents.java overlap).
- **Z-β**: Z3 fires after Z2 merges.

## Version bump

`mod_version` 1.3.2 → 1.3.3. Owner: Z1.

## Post-v1.3.3

- Round-4 audit
- If clean → cut v1.3.3 release, single fleet deploy, embed
- If P0/P1 surface → v1.3.4 loop
