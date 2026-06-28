# VonixGuardian — Configuration Reference

**Version:** 1.0.0
**File:** `config/vonixguardian/config.json` (auto-written on first boot)
**Source of truth:** `core/src/main/java/network/vonix/guardian/core/config/GuardianConfig.java`

This document enumerates every field accepted by the config loader, with type,
default, valid range, semantics, and whether changes take effect via `/vg reload`
or require a full server restart.

> **Reload status in 1.0.0.** `/vg reload` is wired as a command but the
> server-side handler is a placeholder — it ACKs without re-reading the file.
> Treat **every** field in this release as **restart-required** until the
> reload handler is implemented in a later release. The `Reload` column below
> describes the *intended* behavior once the handler lands.

---

## 1. Top-level shape

```jsonc
{
  "database":    { ... },   // §2  storage backend
  "queue":       { ... },   // §3  async writer queue
  "logFile":     { ... },   // §4  JSON-Lines audit file
  "actions":     { ... },   // §5  per-category toggles + blacklists
  "permissions": { ... },   // §6  permission resolution
  "lookup":      { ... },   // §7  /vg lookup UX
  "privacy":     { ... },   // §8  IP hashing
  "purge":       { ... },   // §9  /vg purge safety floors
  "theme":       "aqua"     // §10 chat theme key
}
```

Validation is fail-fast: a single `IllegalStateException` is thrown listing
*every* problem detected, the plugin then aborts startup.

---

## 2. `database` — storage backend

| Field      | Type   | Default            | Required for     | Notes                                          |
|------------|--------|--------------------|------------------|------------------------------------------------|
| `type`     | string | `"sqlite"`         | always           | One of `sqlite`, `mysql`, `postgresql`.        |
| `file`     | string | `"vonixguardian.db"` | `sqlite`       | Path relative to server data dir.              |
| `jdbcUrl`  | string | `null`             | `mysql`, `postgresql` | Full JDBC URL, e.g. `jdbc:mysql://host/db`. |
| `user`     | string | `null`             | optional         | DB user (ignored for SQLite).                  |
| `password` | string | `null`             | optional         | DB password (ignored for SQLite).              |

**Validation**
- `type` ∈ `{sqlite, mysql, postgresql}`.
- If `type == "sqlite"` → `file` must be non-blank.
- If `type ∈ {mysql, postgresql}` → `jdbcUrl` must be non-blank.

**Backend selection**

| Backend      | When to use                                  | Caveats                                          |
|--------------|----------------------------------------------|--------------------------------------------------|
| `sqlite`     | Single server, ≤ ~5 M actions/day            | File lock; do not point at a network drive.      |
| `mysql`      | Network of servers sharing one ledger        | MariaDB 10.5+ also works on the MySQL driver.    |
| `postgresql` | Large fleets, analytics-heavy use            | Use a dedicated DB user with `CREATE TABLE`.     |

**Reload:** ❌ Restart — connection pool is built at boot.

---

## 3. `queue` — async writer queue

| Field             | Type   | Default | Valid range          | Controls                                                                 |
|-------------------|--------|---------|----------------------|--------------------------------------------------------------------------|
| `maxSize`         | int    | `50000` | `> 0`                | Max queued actions before backpressure kicks in.                          |
| `flushIntervalMs` | long   | `5000`  | `> 0`                | Polling interval / forced-flush age in milliseconds.                      |
| `batchSize`       | int    | `1000`  | `> 0` and `≤ maxSize`| Preferred rows per JDBC batch.                                            |

**Behavior under pressure**

1. Producers (game-thread event handlers) enqueue without blocking.
2. The writer thread drains up to `batchSize` rows per tick, or whatever has
   accumulated when `flushIntervalMs` elapses since last flush — whichever
   comes first.
3. When `queue.size() >= maxSize`, new enqueues are **dropped** and a `WARN`
   is logged (rate-limited). The server tick is **never** blocked.
4. `/vg status` exposes current queue depth so you can size `maxSize` against
   sustained event rate.

**Tuning rule of thumb**

| Throughput target          | `maxSize` | `batchSize` | `flushIntervalMs` |
|----------------------------|-----------|-------------|-------------------|
| Small (≤ 50 players)       | 50 000    | 1 000       | 5 000             |
| Medium (50–200 players)    | 200 000   | 2 500       | 2 500             |
| Large (200+ / minigames)   | 500 000   | 5 000       | 1 000             |

**Reload:** ❌ Restart — writer thread + ring buffer are sized at boot.

---

## 4. `logFile` — JSON-Lines audit file

| Field           | Type    | Default              | Valid range | Controls                                                |
|-----------------|---------|----------------------|-------------|---------------------------------------------------------|
| `enabled`       | boolean | `true`               | —           | Master switch for the rolling JSONL writer.             |
| `directory`     | string  | `"logs/vonixguardian"` | non-blank when enabled | Output directory (relative or absolute).      |
| `gzipRotated`   | boolean | `true`               | —           | Gzip yesterday's file at midnight rotation.             |
| `retentionDays` | int     | `30`                 | `>= 0`      | Keep horizon; `0` = **keep forever**.                   |

**File naming.** `audit-YYYY-MM-DD.log` while open; renamed to
`audit-YYYY-MM-DD.log.gz` after rotation if `gzipRotated` is true.

**Rotation.** Triggered at local-midnight; the previous day's file is closed
atomically before the gzip step.

**Retention.** Files older than `retentionDays` days are removed during the
rotation sweep. `0` disables the sweep entirely — useful when an external
backup tool owns log lifecycle.

**Reload:** ❌ Restart — file handle and rotation timer are bound at boot.

---

## 5. `actions` — per-category toggles + blacklists

### 5.1 Category toggles

All booleans, all default `true`. Disabling a category prevents the matching
events from being enqueued at the source — there is **no** post-filter cost.

| Field             | Disables                                                       |
|-------------------|----------------------------------------------------------------|
| `logBlocks`       | block place / break                                            |
| `logContainers`   | container open + slot transactions                             |
| `logItems`        | item drop / pickup                                             |
| `logEntities`     | entity kills                                                   |
| `logExplosions`   | explosion events (TNT, creeper, end crystal, ghast, etc.)      |
| `logChat`         | chat messages                                                  |
| `logCommands`     | command execution                                              |
| `logSessions`     | join / quit (`SESSION_JOIN` / `SESSION_QUIT`)                  |
| `logSigns`        | sign edits                                                     |
| `logInteractions` | buttons, levers, doors, pressure plates, trapdoors, etc.       |
| `logWorldEvents`  | non-player world events (fire spread, leaf decay, ice melt…)   |

### 5.2 Blacklists

Each blacklist is a JSON array of strings. `null` elements are rejected by
the validator. An absent (or `null`) array is treated as empty.

| Field             | Element format                              | Example                       | Matched against                                  |
|-------------------|---------------------------------------------|-------------------------------|--------------------------------------------------|
| `worldBlacklist`  | World identifier (namespaced key)            | `minecraft:overworld`         | `Level.dimension().location().toString()`        |
| `blockBlacklist`  | Block ID (namespaced)                        | `minecraft:air`               | `BuiltInRegistries.BLOCK.getKey(block)`          |
| `sourceBlacklist` | `sourceTag` of the action                    | `explosion:tnt`               | `Action.sourceTag()` exact match                 |

**Glob support.** v1.0.0 uses **exact, case-sensitive string equality** for
all three lists. Wildcards / globs are **not** parsed — `minecraft:*_log` is
treated as a literal id and will never match. Globbing is tracked for a
future minor release.

**Default `blockBlacklist`** ships with `minecraft:air` to suppress the
torrent of air-replacement noise from physics updates and bucket-fills.

**Reload:** ❌ Restart — listener wiring inspects toggles at registration.

---

## 6. `permissions` — permission resolution

| Field            | Type    | Default | Valid range | Controls                                                              |
|------------------|---------|---------|-------------|-----------------------------------------------------------------------|
| `useLuckPerms`   | boolean | `true`  | —           | Try to bridge LuckPerms via reflection (no hard dep).                 |
| `defaultOpLevel` | int     | `3`     | `[0, 4]`    | Fallback op level required when LP is absent or returns no decision.  |

**LuckPerms detection.** When `useLuckPerms = true`, the permission service
calls `LuckPermsProvider.get()` reflectively at first permission check; if
the class isn't on the classpath or the call throws, it caches the failure
and silently falls back to the vanilla op-level check using `defaultOpLevel`.
A single INFO line at startup records which path was chosen.

**Op-level mapping (vanilla):** `0` = none, `1` = bypass spawn-protection,
`2` = single-player cheats / gamemode, `3` = ban/kick/op, `4` = stop/save-off.

**Reload:** ❌ Restart — service binding chosen at boot.

---

## 7. `lookup` — `/vg lookup` UX & backpressure

| Field             | Type | Default  | Valid range          | Controls                                                              |
|-------------------|------|----------|----------------------|-----------------------------------------------------------------------|
| `defaultPageSize` | int  | `7`      | `[1, 50]`            | Rows per page when the user omits an explicit page size.              |
| `maxRadius`       | int  | `10000`  | `>= 1`               | Hard cap on the `r:<n>` filter (blocks).                              |
| `maxResultRows`   | int  | `100000` | `[100, 10000000]`    | Absolute cap on rows materialised per single query.                   |
| `maxConcurrent`   | int  | `4`      | `[1, 64]`            | Max simultaneous lookup queries serviced by the executor.             |

**Backpressure semantics.**

- When `maxConcurrent` is saturated, the next caller's command thread is
  rejected with a yellow `[VonixGuardian] lookup busy — try again in a moment`
  reply. The caller can re-issue; **no** queries are queued.
- When a query would exceed `maxResultRows`, execution short-circuits at the
  cap with a footer `(truncated at N rows — narrow your filter)` so paging
  remains coherent.
- `maxRadius` is enforced at filter-parse time; a violating `r:50000` is
  rejected client-side before the DB is touched.

**Reload:** ❌ Restart — executor + paging buffers are sized at boot.

---

## 8. `privacy` — IP hashing

| Field      | Type    | Default                                  | Valid range                          | Controls                                                  |
|------------|---------|------------------------------------------|--------------------------------------|-----------------------------------------------------------|
| `hashIps`  | boolean | `false`                                  | —                                    | When `true`, IP and hostname are hashed on `SESSION_JOIN`. |
| `salt`     | string  | `"vonix-guardian-default-salt-CHANGE-ME"` | length `>= 16` when `hashIps` is true | HMAC-style salt prefix used by `IpHasher`.                |

**⚠ Security warning — default salt.**

The shipped `salt` value `vonix-guardian-default-salt-CHANGE-ME` is a
**placeholder**. If you enable `hashIps = true` and forget to rotate the
salt:

- The validator logs a `WARN` at every startup.
- Hashes are stable across **every** VonixGuardian deployment that ships
  with the same default — i.e. an attacker who knows the default can mount
  a rainbow-table attack on `/24` or `/16` ranges trivially.

**Recommended salt:** ≥ 32 random bytes, base64-encoded. Generate with
`openssl rand -base64 32`. Treat it as a secret on par with your DB password.

**Effect of `hashIps = true`:**
- `SESSION_JOIN.ip`        → `sha256(salt || ip)` (hex)
- `SESSION_JOIN.hostname`  → `sha256(salt || hostname)` (hex)
- All other action types are unaffected.

**Reload:** ❌ Restart — IP hasher singleton is bound at boot.

---

## 9. `purge` — `/vg purge` safety floors

| Field                   | Type | Default      | Valid range  | Controls                                                          |
|-------------------------|------|--------------|--------------|-------------------------------------------------------------------|
| `minAgeSecondsConsole`  | long | `86400`      | `>= 60`      | Minimum age (sec) when purge is invoked from the **server console**. |
| `minAgeSecondsInGame`   | long | `2592000`    | `>= 3600`    | Minimum age (sec) when purge is invoked **in-game**.              |

**Defaults in human units:** 1 day (console) / 30 days (in-game).

**Safety rationale.** `/vg purge` is destructive and irreversible. Two
separate floors exist because:

1. **In-game callers** are gated harder (≥ 1 hour, default 30 days) because
   a fat-fingered command from a player op can wipe an evidence trail before
   anyone notices. The 30-day default is calibrated to outlast the typical
   ban-appeal window.
2. **Console callers** are gated lower (≥ 60 s, default 1 day) because
   automated maintenance scripts run from cron need to be able to trim
   recent rows; the 60 s floor still prevents `purge t:0` from instantly
   nuking the table.

The validator refuses to load a config that goes below either floor. There
is **no** kill-switch to bypass the floors at runtime — change the config
and restart.

**Reload:** ❌ Restart — floors are read once at boot by the purge command.

---

## 10. `theme` — chat theme

| Field   | Type   | Default  | Valid values                                            |
|---------|--------|----------|---------------------------------------------------------|
| `theme` | string | `"aqua"` | `aqua`, `blue`, `gold`, `green`, `purple`, `red`, `white` |

Controls the accent color used for `/vg` chat output (prefix, page headers,
clickable elements). Unknown values fail validation.

**Reload:** ❌ Restart — theme is bound to the command renderer at boot.

---

## 11. Canonical `config.json` (fully commented)

This is the file emitted on first boot, annotated. Strip the `//` comments
before saving — `config.json` is parsed as **strict JSON**, not JSON5.

```jsonc
{
  // §2 — Storage backend. Pick one of sqlite / mysql / postgresql.
  "database": {
    "type": "sqlite",                 // sqlite | mysql | postgresql
    "file": "vonixguardian.db",       // sqlite only; relative to server dir
    "jdbcUrl": null,                  // required for mysql / postgresql
    "user": null,                     // optional
    "password": null                  // optional
  },

  // §3 — Async writer queue. See "Behavior under pressure".
  "queue": {
    "maxSize": 50000,                 // drops with WARN above this
    "flushIntervalMs": 5000,          // forced-flush age (ms)
    "batchSize": 1000                 // rows per JDBC batch; <= maxSize
  },

  // §4 — Rolling JSON-Lines audit file.
  "logFile": {
    "enabled": true,
    "directory": "logs/vonixguardian",
    "gzipRotated": true,              // gzip yesterday's file on rotation
    "retentionDays": 30               // 0 = keep forever
  },

  // §5 — Per-category toggles + blacklists. Exact-match strings only (no globs).
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
    "worldBlacklist": [],             // e.g. ["minecraft:the_nether"]
    "blockBlacklist": ["minecraft:air"],
    "sourceBlacklist": []             // e.g. ["explosion:tnt"]
  },

  // §6 — Permission resolution.
  "permissions": {
    "useLuckPerms": true,             // soft-bridged via reflection
    "defaultOpLevel": 3               // fallback when LP absent; [0,4]
  },

  // §7 — /vg lookup UX.
  "lookup": {
    "defaultPageSize": 7,             // [1, 50]
    "maxRadius": 10000,               // blocks; >= 1
    "maxResultRows": 100000,          // [100, 10_000_000]
    "maxConcurrent": 4                // [1, 64]; over-saturation = busy reply
  },

  // §8 — IP hashing. CHANGE THE SALT before enabling hashIps in production.
  "privacy": {
    "hashIps": false,
    "salt": "vonix-guardian-default-salt-CHANGE-ME"
  },

  // §9 — /vg purge safety floors. Cannot be lowered below the validator minima.
  "purge": {
    "minAgeSecondsConsole": 86400,    // 1 day;  floor 60      s
    "minAgeSecondsInGame": 2592000    // 30 days; floor 3600    s
  },

  // §10 — Chat theme.
  "theme": "aqua"
}
```

---

## 12. Reload vs. restart matrix

In **v1.0.0** every field is effectively restart-required because `/vg
reload` is a placeholder. The table records the *intended* policy for when
the reload handler is implemented.

| Section       | Field(s)                                               | Intended    | Today (1.0.0) |
|---------------|--------------------------------------------------------|-------------|---------------|
| `database`    | all                                                    | Restart     | Restart       |
| `queue`       | `maxSize`, `flushIntervalMs`, `batchSize`              | Restart     | Restart       |
| `logFile`     | `enabled`, `directory`                                 | Restart     | Restart       |
| `logFile`     | `gzipRotated`, `retentionDays`                         | Reload      | Restart       |
| `actions`     | all category toggles                                   | Restart     | Restart       |
| `actions`     | `worldBlacklist`, `blockBlacklist`, `sourceBlacklist`  | Reload      | Restart       |
| `permissions` | `useLuckPerms`                                         | Restart     | Restart       |
| `permissions` | `defaultOpLevel`                                       | Reload      | Restart       |
| `lookup`      | `defaultPageSize`                                      | Reload      | Restart       |
| `lookup`      | `maxRadius`, `maxResultRows`                           | Reload      | Restart       |
| `lookup`      | `maxConcurrent`                                        | Restart     | Restart       |
| `privacy`     | `hashIps`, `salt`                                      | Restart     | Restart       |
| `purge`       | `minAgeSecondsConsole`, `minAgeSecondsInGame`          | Reload      | Restart       |
| `theme`       | `theme`                                                | Reload      | Restart       |

---

## See also

- [README.md](../README.md) — project overview, install, command index.
- [docs/USAGE.md](USAGE.md) — operator's guide: command reference, filters, rollback walkthrough, troubleshooting.
- [docs/PERMISSIONS.md](PERMISSIONS.md) — full permission node reference and LuckPerms bridge details.
