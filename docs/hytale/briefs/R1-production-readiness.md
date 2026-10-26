# REVIEW — Vonix Guardian Hytale port: production readiness + port functionality

You are an independent reviewer. **Read-only.** Do not edit source, do not run gradle, do not deploy.
Parent = Hermes (Venix) owns acceptance. Your verdict is evidence, not authority.

## Read first
1. `docs/hytale/HYTALE-PORT-CONTRACT.md` — pin, verified API facts, core SPI, gates.
2. `docs/hytale/results/PARENT-GATES.md` — what is actually proven vs unproven.
3. `docs/hytale/results/{capture,world,command,platform}.md` — per-cell evidence.
4. `hytale/src/main/java/network/vonix/guardian/hytale/**` — the port (~4.5k lines).
5. `core/src/main/java/network/vonix/guardian/core/**` — the shared engine (unchanged; 137 files).
6. Reference Minecraft lanes for parity: `mc-26.3/common/src/main/java/network/vonix/guardian/mc/v26_3/common/*` and `mc-26.3/fabric/.../*`.

## Live evidence you must account for (from a real 0.7.4 server)
```
RollbackEngine.ROLLBACK: batch id=1 left OPEN status=PARTIAL applied=1064 skipped=0 failed=1 compensated=0 repairRequired=0
Rollback result: preview=false success=false mutatedWorld=true affected=1064 applied=1064 skipped=0
Rollback verification: checked=1046 unverifiable=1046 mismatched=0 repaired=0 unfixed=0 dropped=0
```
Interpretation to verify or refute: (a) one mutation genuinely failed and left the batch PARTIAL; (b) the verifier's reads all returned null because it runs off the world thread, so its numbers are meaningless today; (c) after the client-notify regression was removed, rollback now restores in-game.

Server jar: `C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar` — inspect real signatures with
`"C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/javap" -p -cp <jar> <fqcn>`.
Do not trust comments or the brief over the jar.

## Deliverable — one durable report
Write `docs/hytale/results/PRODUCTION-READINESS.md` containing:

1. **Verdict**: `READY_FOR_1.0.0` | `NOT_READY` with the blocking list.
2. **Port functionality matrix**: for every Guardian feature (block break/place capture, environment/explosion capture, container/inventory deltas, entity kill, lookup/inspect, rollback, restore, undo, purge, permissions, config/per-world config, i18n/messages, database backends, log file, attribution) → `IMPLEMENTED+PROVEN` / `IMPLEMENTED+UNPROVEN` / `MISSING` / `NOT_APPLICABLE`, each with file:line evidence.
3. **Defect list**, ordered by severity: `P0` data-loss/corruption, `P1` incorrect audit or rollback, `P2` robustness/UX, `P3` polish. Each item: id, file:line, what is wrong, why it matters, concrete fix.
4. **Thread-safety audit**: every call into the engine from Hytale callbacks — which thread it runs on, whether `world.execute` is required, and where a future deadlock or off-thread read can occur (the verifier's 100% `unverifiable` is evidence this class of bug is live).
5. **Unproven claims**: anything the port asserts in comments/javadoc that the jar or the evidence does not support.
6. **Release checklist** for 1.0.0: exactly what must be true before publishing to CurseForge (gates, artifact naming, manifest fields, version, changelog, rollback safety).

Be specific and adversarial. A finding with no file:line is not a finding. If you believe something is fine, say so briefly and move on — do not pad.

Budget: up to 90 turns. Write the report as you go (never leave it to the last turn); a partial report on disk beats a perfect one that never lands.
