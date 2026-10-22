# C3 — Hytale command surface cell (candidate worker brief)

You are a bounded candidate worker. Parent = Hermes (Venix), owns acceptance. You implement ONLY `hytale/src/main/java/network/vonix/guardian/hytale/command/**` in `C:\Users\Pargon\DEV\vonixguardian-hytale`.

## Read first
1. `docs/hytale/HYTALE-PORT-CONTRACT.md` (pin, verified API facts, gates, output contract).
2. `hytale/src/main/java/network/vonix/guardian/hytale/spi/GuardianServices.java` — the only runtime handle you get.
3. Core engines: `core/.../query/{QueryParser,InspectorLookup,QueryFilter}.java`, `core/.../rollback/{RollbackEngine,RollbackOptions,RollbackResult,RollbackPreviewStore,UndoStack}.java`, `core/.../rollback/PurgeEngine.java`.
4. Reference: `mc-26.3/common/src/main/java/network/vonix/guardian/mc/v26_3/common/{GuardianCommands,Inspector,LookupFormatter,ChatRenderer}.java`.

## Mission
The `/guardian` command tree on Hytale's command system:
- `GuardianCommand` (root, name `guardian`, aliases `vg`, `guardian`) with subcommands: `status`, `inspect`, `lookup`, `rollback`, `restore`, `undo`, `purge`, `reload`, `help`.
- Argument handling via `com.hypixel.hytale.server.core.command.system.arguments.system.*`; output via `com.hypixel.hytale.server.core.Message`; sender via `CommandContext#senderAsPlayerRef()` / `CommandSender`.
- Permission gate: `GuardianServices.players().hasPermission(uuid, node)` plus the plugin base permission from `getManifest()`/`getBasePermission()` as passed in — every mutating subcommand (`rollback`, `restore`, `purge`, `reload`) must fail closed when the check is unavailable.
- Confirmation for destructive subcommands: require an explicit `--confirm` argument; never auto-execute.
- `CommandRegistration` — one `register(GuardianServices services, CommandRegistry registry)` entry point for the bootstrap.

## Rules
- Never log through System.out; take an SLF4J `Logger` in the constructor.
- Every rollback/restore/purge must report per-engine results honestly (mutated / skipped / failed counts), never "success" on a partial.
- Do not implement world mutation or event capture. Do not touch `core/`, `spi/`, or any other cell's package.
- Do not run `gradle`/`gradlew` (parent-owned).
- Verify signatures before use: `"C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/javap" -p -cp "C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar" com.hypixel.hytale.server.core.command.system.AbstractCommand` (also `CommandContext`, `CommandRegistry`, `Message`, `...arguments.system.RequiredArg`).

## Gate you must run
```bash
cd /c/Users/Pargon/DEV/vonixguardian-hytale
bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/command
```
`COMPILE_GATE=PASS` required.

## Deliverable
`docs/hytale/results/command.md` in contract §6 format, including the exact command-by-command permission matrix and anything you had to leave unverified.
