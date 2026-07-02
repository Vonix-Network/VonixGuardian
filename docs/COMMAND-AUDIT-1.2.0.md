# VonixGuardian Command Wiring Audit — v1.2.0

Snapshot: `integration/v1.2.0` at `073a233` after the first v1.2.0 integration wave.

Scope: `/vg` commands registered in the eight per-cell `GuardianCommands.java` files. These files are structurally mirrored; line references use `mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java` as the representative.

Status counts: **16 wired**, **0 stub**, **2 missing CoreProtect command surfaces**.

| Subcommand | Registration line(s) | Permission node | Handler | Core path | Status |
|---|---:|---|---|---|---|
| `vg` root | 75-77 | `vonixguardian.command.use` | `register` | Brigadier root gate | **WIRED** |
| `inspect` / `i` | 79-84 | `vonixguardian.command.inspect` | `Inspect.toggle` | `Inspector.toggle(player UUID)` | **WIRED** |
| `lookup` / `l` | 86-95 | `vonixguardian.command.lookup` | `Lookup.run` / `runWithFilter` | `QueryParser.parse` → `dao.count/query` → `LookupFormatter.page`; child-perm filter | **WIRED** |
| `rollback` / `rb` | 97-106 | `vonixguardian.command.rollback` | `Rollback.run` | `QueryParser.parse` → default radius → `rollbackEngine.rollback` → `undoStack.push` | **WIRED** |
| `restore` / `rs` | 108-117 | `vonixguardian.command.restore` | `Restore.run` | `QueryParser.parse` → default radius → `rollbackEngine.restore` → `undoStack.push` | **WIRED** |
| `purge` | 119-123 | `vonixguardian.command.purge` | `Purge.run` | `purgeEngine.purge` with CP safety floors | **WIRED** |
| `undo` | 125-127 | `vonixguardian.command.rollback` | `Undo.run` | `undoStack.pop` → inverse `rollbackEngine.plan` → `execute` | **WIRED** |
| `near` | 129-131 | `vonixguardian.command.near` | `Near.run` | `Lookup.runWithFilter("r:5 t:1h")` | **WIRED** |
| `consumer pause/resume/toggle` | 133-138 | `vonixguardian.command.consumer` | `Consumer.*` | `queue.isPaused/depth/setPaused` | **WIRED** |
| `status` | 140-142 | `vonixguardian.command.status` | `Status.run` | `GuardianStatus.render(g)` | **WIRED** |
| `reload` | 144-146 | `vonixguardian.command.reload` | `Reload.run` | `guardian.reloadConfig(configPath)` → hotSwapped/requiresRestart/errors | **WIRED** |
| `migrate-db` | 148-153 | `vonixguardian.command.migrate-db` | `MigrateDb.run` | `MigrateDbCommand.run(g,target,confirmed,lineSink)`; console-only; CONFIRM guard | **WIRED** |
| `config get/set` | before `migrate-db` in v1.2.0 current | `vonixguardian.command.config` | `Config.get` / `Config.set` | reads/writes hot-swap-safe keys, persists via `ConfigLoader.save`, then applies through `Guardian.reloadConfig` | **WIRED** |
| `help` | 155 | root only | `Help.run` | static help text | **WIRED** |
| `co` alias | 159 | inherits `/vg` root | redirect | Brigadier redirect to `/vg` | **WIRED** |
| `guardian` alias | 160 | inherits `/vg` root | redirect | Brigadier redirect to `/vg` | **WIRED** |
| `teleport` / `tp` | not registered | missing: `vonixguardian.command.teleport` | missing | no per-player numbered lookup-result cache yet | **MISSING** |
| `give` | not registered | missing: `vonixguardian.command.give` | missing | no result cache or loader-specific item reconstruction/give facade yet | **MISSING** |

## Findings

### Wired and functional

The v1.2.0 command tree is substantially more complete than the stale v1.1.5 comparison doc suggested:

- `/vg reload` is no longer a placeholder; it calls `Guardian.reloadConfig(g.configPath())` and reports hot-swapped keys, restart-required keys, and errors.
- `/vg undo` is no longer a history-pop only; it executes the inverse rollback/restore plan for the captured original filter. Legacy entries without `originalFilter` are safely downgraded to history-only behavior.
- `/vg migrate-db` exists, is console-only, and requires the `CONFIRM` token before mutation.
- `/vg status` uses the core diagnostics renderer (`GuardianStatus.render`) and is the canonical live observability surface.

### Missing vs CoreProtect

The remaining user-visible command gaps against CoreProtect are:

1. `/vg teleport <resultId>` / `/vg tp <resultId>` — not registered. The current lookup pipeline renders rows to chat but does not persist a per-player numbered result cache that teleport can target.
2. `/vg give <resultId>` — not registered. This needs a result cache plus an 8-cell item materialization/give facade because item registries/NBT are Minecraft-version-specific.
3. `/vg config get/set` is now wired for hot-swap-safe keys only. Restart-required keys remain intentionally read-only through this command surface.

### Permission-node drift

`core/.../PermissionNode.java` contains canonical enum nodes like `LOOKUP`, `ROLLBACK`, `MIGRATE_DB`, etc., but the cell command files still call `hasPerm(source, "string.node", g)` directly. The string nodes are not all identical to the enum strings:

- Cell root uses `vonixguardian.command.use`; enum root is `vonixguardian.base`.
- Cell migrate-db uses `vonixguardian.command.migrate-db`; enum is `vonixguardian.migrate_db`.

This does not necessarily break runtime if the legacy string permissions are intentional, but it means W3-B7's enum registry is not the single source of truth for command registration yet. A future cleanup should migrate command `requires(...)` gates to `PermissionNode` or add explicit compatibility aliases.

## Audit conclusion

v1.2.0 is command-complete for the commands already exposed by `/vg help`, but **not CoreProtect-complete** until teleport, give, and key-level config are implemented. Those are not safe one-line additions: teleport/give require a persistent lookup-result selection model, and give requires loader-specific item stack reconstruction.
