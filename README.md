# VonixGuardian

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.18.2%20%7C%201.19.2%20%7C%201.20.1%20%7C%201.21.1-green.svg)]()
[![Loaders](https://img.shields.io/badge/loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-orange.svg)]()
[![Build](https://github.com/Vonix-Network/VonixGuardian/actions/workflows/build.yml/badge.svg)](https://github.com/Vonix-Network/VonixGuardian/actions/workflows/build.yml)

**A blazing-fast server-side block/inventory/entity audit & rollback tool for modded Minecraft.**

Drop-in CoreProtect-grade auditing for the modern modded ecosystem — Fabric, Forge, and NeoForge — across MC 1.18.2 → 1.21.1. Local SQLite database by default, optional MySQL/Postgres backend, rolling JSON log file, server-thread-friendly async queue, brigadier command tree modelled on CoreProtect's `/co` UX.

> Built and maintained by [Vonix Network](https://vonix.network).

## Feature surface (v0.1.0)

- **Logged actions**: block place / break, container transactions (chest/hopper/etc), item drop/pickup, entity kill (mob + player), explosions (TNT, creeper, end-crystal, custom), sessions (login/logout), chat, commands, signs.
- **Commands** (`/vg`, alias `/guardian`):
  - `/vg inspect` — toggle the inspector; left/right-click any block to see history at that position.
  - `/vg lookup <filters>` — paginated query (filters: `u:<user>` `t:<time>` `r:<radius>` `a:<action>` `i:<include>` `e:<exclude>` `#preview` `#count` `#verbose` `#silent`).
  - `/vg rollback <filters>` — undo actions in scope.
  - `/vg restore <filters>` — redo a rollback (or apply a forward-restore).
  - `/vg purge t:<time>` — drop old data.
  - `/vg near` — radius-5 lookup at your feet.
  - `/vg undo` — revert your last rollback / restore.
  - `/vg status` — DB health, queue depth, version.
  - `/vg reload` — reload `config/vonixguardian/config.json`.
- **Storage**: SQLite (default, zero-config), MySQL, PostgreSQL — selected via config.
- **Log file**: rolling JSON-Lines at `logs/vonixguardian/audit-YYYY-MM-DD.log` (gzipped after rotation).
- **Permissions**: LuckPerms-aware (`vonixguardian.command.<cmd>`); permission-level fallback if LP absent.
- **Performance**: bounded async writer queue, batch INSERTs, prepared statements, indexed by `(world, x, z, y, time)` for hot-path queries.
- **Compat**: zero-touch with worldedit, worldborder, ftbchunks, ftbteams, prometheus exporters. No mixin into vanilla world-gen — only event hooks.

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
