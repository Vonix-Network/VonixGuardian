# REPAIR-1 — close the production-readiness backlog for the Hytale port

You are a bounded candidate worker. Parent = Hermes (Venix) owns acceptance, the build, and deployment.
You implement repairs in `hytale/src/main/java/network/vonix/guardian/hytale/**` and must not attempt release.

## Read first, in this order
1. `docs/hytale/results/PRODUCTION-READINESS.md` — the backlog. **This is your work list.** Every item has
   `file:line`; verify each against the current working tree before touching it (a parallel editor moved
   some lines while you were dispatched).
2. `docs/hytale/HYTALE-PORT-CONTRACT.md` — pin, gates, output contract.
3. `docs/hytale/results/menu.md` — a parallel cell added `menu/`; it is **not** yours.

## Scope — fix, in this priority order
- **P0-1** (data loss): block-entity/container contents are dropped by rollback because capture never stores
  component bytes and container deltas. Minimum acceptable repair: refuse the destructive write path when a
  position owns a block entity and no component payload was captured, and surface that refusal in the command
  result. Better: capture the component payload on break/place. Do not advertise container audit unless it is
  actually captured.
- **P1-1**: mutations must land on the world that owns the action (`worldId` → `World` → `execute`), never a
  single default-world executor. Never wait for world B's queue while inside world A's `execute`.
- **P1-2**: `WriteVerifier.clean()` must be false when `unverifiable > 0` or `unfixed > 0`; the pass must run
  against the world owning each position; `clearToAir` must record its intent; state comparison must include
  filler/component data or the claim of convergence must be withdrawn.
- **P1-3**: `SetBlockSettings.PERFORM_BLOCK_UPDATE` does **not** notify clients (it calls `updateBlockArea` →
  `setTicking`). Do not leave a comment or a flag that pretends it does. Either find and prove the real
  client-visible update path, or remove the flag and record the residual honestly.
- **P1-4**: a failing mutation must log world, x, y, z, target id **and which branch returned false**, and the
  open-batch id must reach the command reply.
- **P1-5**: `inspect` mode is advertised but capture never consults it — wire it or delete the subcommand and
  the help line.
- **P1-6**: help text claims `#preview` stores a plan that a later invocation applies. It does not. Fix the text.
- **P1-7**: break rotation is discarded when the live section already shows a different id. Take rotation from
  the event/snapshot before the break commits, not after.
- **P2-1..P2-6, P3-1..P3-3**: as enumerated in the report.

## Hard constraints
- Do NOT edit: `core/**`, `hytale/src/main/java/network/vonix/guardian/hytale/spi/**`,
  `hytale/src/main/java/network/vonix/guardian/hytale/menu/**`,
  `hytale/src/main/java/network/vonix/guardian/hytale/VonixGuardianPlugin.java`,
  `hytale/build.gradle`, `manifest.json`, `settings.gradle`, `docs/hytale/results/*` other than your own file.
  The parent wires the menu and owns the bootstrap, manifest and version story.
- Do not run `gradle`/`gradlew`, do not deploy, do not commit, do not push.
- Per-package gate after each package you touch:
  `bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/<pkg>`
  must print `COMPILE_GATE=PASS`.
- Do not invent Hytale APIs. Verify with
  `"C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/javap" -p -cp C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar <fqcn>`
  and cite the signature in your result.
- If a repair would make behaviour worse or cannot be proven, leave the code and record it as
  `DEFERRED=<reason>` rather than shipping a guess.

## Deliverable
`docs/hytale/results/REPAIR-1.md` in the contract §6 format plus a table:
`item | status(FIXED/DEFERRED/PARTIAL) | file:line | what changed | how it was verified`.
Write it early and update as you go — a partial on-disk record beats a perfect report that never lands.
Budget: up to 110 turns.
