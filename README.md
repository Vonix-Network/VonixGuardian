# VonixGuardian

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.18.2%20%7C%201.19.2%20%7C%201.20.1%20%7C%201.21.1-green.svg)]()
[![Loaders](https://img.shields.io/badge/loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)]()
[![Build](https://github.com/Vonix-Network/VonixGuardian/actions/workflows/build.yml/badge.svg)](https://github.com/Vonix-Network/VonixGuardian/actions/workflows/build.yml)

**A blazing-fast server-side block/inventory/entity audit & rollback tool for modded Minecraft.**

Drop-in CoreProtect-grade auditing for the modern modded ecosystem — Fabric, Forge, and NeoForge — across MC 1.18.2 → 1.21.1. Local SQLite database by default, optional MySQL/Postgres backend, rolling JSON log file, server-thread-friendly async queue, brigadier command tree modelled on CoreProtect's `/co` UX.

> Built and maintained by [Vonix Network](https://vonix.network).

## Feature surface (v1.2.4)

- **Logged actions** (39 action types): block place / break, container transactions, item drop / pickup / craft, entity kill, explosions, sessions (join / leave), chat, commands, sign edits (front / back / dye / waxed on 1.20+), player interactions, world events (burn, ignite, fade, form, spread, dispense, leaves decay, piston extend/retract, buckets), hopper push/pull, structure grow, portal create, hanging place/break, username changes.

- **Commands** (`/vg`, aliases `/co`, `/guardian`) — CoreProtect 1:1 parity:
  - `/vg help` — CoreProtect-style command summary.
  - `/vg inspect` (alias `/vg i`) — toggle the inspector; left/right-click any block to see history.
  - `/vg lookup <filters>` (alias `/vg l`) — paginated query with full CP filter syntax.
  - `/vg rollback <filters>` (alias `/vg rb`) — undo actions in scope (default radius 10).
  - `/vg restore <filters>` (alias `/vg rs`) — redo a rollback or apply a forward-restore.
  - `/vg purge <filters>` — delete old rows (safety floors: console ≥ 24h, in-game ≥ 30d).
  - `/vg near` — quick lookup `r:5 t:1h` around you.
  - `/vg undo` — reverse the last rollback / restore (real world revert, not history-pop).
  - `/vg consumer pause | resume | toggle` — manage the writer queue.
  - `/vg status` — full multi-section observability surface (version, storage, queue, coalescer, hooks, permissions, API — mirrors `/co status`).
  - `/vg reload` — re-read `config/vonixguardian/config.json`, hot-swap safe subsections.
  - `/vg config get <key>` / `/vg config set <key> <value>` — key-level hot-swap for whitelisted config keys.
  - `/vg migrate-db <sqlite | mysql | postgresql> CONFIRM` — console-only backend copy.
  - `/vg teleport <world> <x> [y] <z>` (alias `/vg tp`) — CoreProtect-parity admin teleport.
  - `/vg give <itemId> [amount]` — CoreProtect-parity give.

- **Filter mini-language** (per `docs/COREPROTECT-COMPARISON.md`):
  - Users: `u:Notch`, `u:Notch,Intelli`, `u:#fire,#tnt,#creeper,#explosion`, plus `#mob:<ns>:<path>`.
  - Time: `t:2w5d7h`, `t:2.50h`, `t:1h-2h`.
  - Radius: `r:10`, `r:#global`, `r:#world_the_nether`, `r:#worldedit` / `r:#we`, `r:#nether` / `r:#overworld` / `r:#end`.
  - Actions: `a:block`, `a:+block`, `a:-block`, `a:container`, `a:kill`, `a:chat`, `a:command`, `a:sign`, `a:session`, `a:username`, `a:click`, `a:inventory`, `a:item`, plus 21 VG-only expansion tokens (burn, ignite, fade, form, spread, dispense, piston, bucket, decay, hanging, spawn, einteract, hopper, craft, grow, portal, populate, entityblock).
  - Includes / excludes: `i:stone,dirt`, `e:minecraft:tnt`.
  - Hash flags: `#preview`, `#count`, `#verbose`, `#silent`, `#optimize`.

- **Storage**: SQLite (default, zero-config), MySQL, MariaDB, **PostgreSQL** (VG uniqueness). Schema migrations are dialect-aware and idempotent. `/vg migrate-db` copies between backends live.

- **Log file**: rolling JSON-Lines at `logs/vonixguardian/audit-YYYY-MM-DD.log` (gzipped after rotation, configurable retention).

- **Permissions**: LuckPerms-aware (`vonixguardian.command.*` + child perms `vonixguardian.lookup.block/container/item/kill/chat/command/sign/session`); permission-level fallback if LP absent (with per-node op-level overrides in config).

- **Public Java API**: `network.vonix.guardian.core.api.VonixGuardianAPI` — typed result classes (`BlockLookupResult`, `ContainerLookupResult`, `ItemLookupResult`, `InventoryLookupResult`, `SessionLookupResult`, `UsernameLookupResult`, `MessageLookupResult`, `SignLookupResult`) + `hasPlaced`, `hasRemoved`, `queueLookup`, `logChat`, `logCommand`, `logPlacement`, `logRemoval`. See `docs/API.md`.

- **Extensibility**: soft-dep pattern via reflection. Third-party mods can subscribe to `PreLogEvent` on any of the 8 loader cells to cancel or annotate audit entries. See `docs/PLUGINS.md`.

- **i18n**: 14 language bundles ported from CoreProtect (en, de, es, fr, ja, ko, pl, ru, tr, tt, uk, vi, zh-cn, zh-tw). Select via `config.language`.

- **Performance**: bounded async writer queue with time-budgeted poll, batch INSERTs, prepared statements, indexed by `(world, x, z, y, time)`. Producer-side coalescer against modded event storms (HTTYD dragon 200k+/sec `LivingDestroyBlockEvent` floods). Auto-purge daemon (CP Patreon 24+ parity).

- **Diagnostics**: `/vg status` is the canonical observability surface. Rate-limited WARN on drops. Rolling audit log side-channel.

- **Compat**: zero-touch with WorldEdit (soft-dep for `r:#worldedit`), FTB Chunks, FTB Teams, WorldBorder, Prometheus exporters, LuckPerms. Mixin-based coverage for events that Forge/Fabric APIs cannot expose natively (block burn/ignite/fade/form/spread/dispense/leaves-decay/bucket).

## Version matrix

| MC Version | Fabric | Forge | NeoForge |
|-----------:|:------:|:-----:|:--------:|
| 1.21.1     |   ✅   |   —   |    ✅    |
| 1.20.1     |   ✅   |   ✅  |    —     |
| 1.19.2     |   ✅   |   ✅  |    —     |
| 1.18.2     |   ✅   |   ✅  |    —     |

(NeoForge did not exist before 1.20.4; the 1.21.1 jar replaces Forge on that version per the loader split.)

## Architecture

```
core/                          Pure-Java engine (JDBC, queue, log, query parser,
                               rollback engine, config). Zero MC deps. 100% unit-tested.

mc-<ver>/common/               Per-MC shared surface (NBT codecs, command tree,
                               event payload models). Mojmap.

mc-<ver>/{fabric,forge,neoforge}/  Thin loader glue (event subscriptions, mod entry,
                                   permission bridges). ~30-50 LOC per loader.

buildSrc/                      Gradle convention plugins.
gradle/libs.versions.toml      Single version catalog.
```

No architectury runtime — `core` is plain Java and loader modules import it directly. Smaller jars, no abstraction tax. Pattern matches LuckPerms / FastBackups / Ledger.

## Build

```bash
./gradlew build
```

Produces 8 jars under `<module>/build/libs/`. Each jar bundles `core` via Gradle Shadow.

CI builds the full 8-jar matrix on every push to `main` and uploads them to the GitHub Release on tag push.

## Configuration

First boot writes `config/vonixguardian/config.json`:

```json
{
  "database": {
    "type": "sqlite",
    "file": "vonixguardian.db",
    "_or": "mysql / postgresql with jdbc_url, user, password"
  },
  "queue": {
    "maxSize": 50000,
    "flushIntervalMs": 5000,
    "batchSize": 1000
  },
  "logFile": {
    "enabled": true,
    "directory": "logs/vonixguardian",
    "gzipRotated": true,
    "retentionDays": 30
  },
  "actions": {
    "logBlocks": true,
    "logContainers": true,
    "logItems": true,
    "logEntities": true,
    "logExplosions": true,
    "logChat": true,
    "logCommands": true,
    "logSessions": true,
    "logSigns": true,
    "logInteractions": true,
    "logWorldEvents": true,
    "worldBlacklist": [],
    "blockBlacklist": ["minecraft:air"],
    "sourceBlacklist": []
  },
  "permissions": { "useLuckPerms": true, "defaultOpLevel": 3 },
  "lookup": { "defaultPageSize": 7, "maxRadius": 10000, "maxResultRows": 100000, "maxConcurrent": 4 },
  "privacy": { "hashIps": false, "salt": "vonix-guardian-default-salt-CHANGE-ME" },
  "purge": { "minAgeSecondsConsole": 86400, "minAgeSecondsInGame": 2592000 },
  "theme": "aqua"
}
```

## Permissions

Every command is gated by `vonixguardian.command.<cmd>` (e.g. `vonixguardian.command.rollback`). LuckPerms is auto-detected via reflection — no hard dependency. Without LP, permission level 3 (`op`) is required.

## License

MIT. See [LICENSE](LICENSE). Inspired by CoreProtect (Artistic-2.0, separate codebase) and Ledger (LGPL-3, separate codebase) — VonixGuardian is a clean-room implementation.

## Documentation

**Operator guides**
- [docs/INSTALL.md](docs/INSTALL.md) — install procedure, prerequisites, SHA-256 verification, first boot.
- [docs/USAGE.md](docs/USAGE.md) — command reference, filter syntax, rollback walkthrough, troubleshooting.
- [docs/CONFIG.md](docs/CONFIG.md) — full `config.json` reference: every field with type, default, valid values, reload-vs-restart matrix.
- [docs/PERMISSIONS.md](docs/PERMISSIONS.md) — LuckPerms nodes, op-level fallback, `bypass` + `viewothers` semantics.
- [docs/DATABASE.md](docs/DATABASE.md) — schema (ER diagram), backup/restore per backend, direct SQL examples.
- [docs/MIGRATION.md](docs/MIGRATION.md) — switching storage backends, version upgrades, downgrade safety.
- [docs/FAQ.md](docs/FAQ.md) — compatibility (Create, Pixelmon, Dragon Mounts, WorldEdit, …), performance, security, ops.

**Developer reference**
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — engine layering, event flow, threading model, soft-dep pattern.
- [docs/API.md](docs/API.md) — public Java API for third-party mods (soft-dep reflection pattern, `EventSubmitter`).
- [docs/MODDED-ATTRIBUTION.md](docs/MODDED-ATTRIBUTION.md) — universal griefing-attribution chain (the differentiating feature).
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — environment setup, IDE config, adding an `ActionType`, CI overview.
- [SHARED-CONTRACTS.md](SHARED-CONTRACTS.md) — internal type contracts.
- [SHARED-LOADER-CONTRACTS.md](SHARED-LOADER-CONTRACTS.md) — loader-glue contracts.
- [LIBRARY-PACKAGING.md](LIBRARY-PACKAGING.md) — shade-unrelocated vs JarInJar strategy per loader.

**Project**
- [CHANGELOG.md](CHANGELOG.md) — release history (Keep a Changelog).
- [CONTRIBUTING.md](CONTRIBUTING.md) — contribution workflow, commit conventions, DCO.
- [SECURITY.md](SECURITY.md) — public security disclosure policy.

## Credits

- **Vonix Network** team — design, implementation, ops.
- **Hermes Agent** by Nous Research — autonomous build assistant.
- **CoreProtect** by Intelli and the PlayPro team — the UX gold standard this project emulates.
- **Ledger** by QuiltServerTools — modded-Minecraft logging prior art.
