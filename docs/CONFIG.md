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
| `itemBlacklist`   | Item ID (namespaced key)                     | `minecraft:diamond`           | Item-bearing item/container/hopper actions      |
| `entityBlacklist` | Entity ID (namespaced key; optional `#mob:`) | `minecraft:zombie`            | Entity and hanging-entity actions               |

**Matching.** All lists use exact matching; wildcards / globs are not parsed.
Item and entity IDs are trimmed and compared case-insensitively. Entity IDs
also match symmetrically with or without the stored `#mob:` sentinel prefix.
Blank or malformed per-world entries are ignored for that override; malformed
root-config entries fail validation rather than broadening the filter.

**Per-world overrides.** Create partial JSON files under
`config/vonixguardian/worlds/`, using the world key with `:` replaced by `__`.
For example, `minecraft__the_end.json` may contain:

```json
{
  "itemBlacklist": ["minecraft:elytra"],
  "entityBlacklist": ["minecraft:enderman"]
}
```

Missing fields inherit from the root `actions` block. `/vg reload` rebuilds the
root and per-world gate snapshots atomically; historical rows are never
deleted or rewritten.

**Default `blockBlacklist`** ships with `minecraft:air` to suppress the
torrent of air-replacement noise from physics updates and bucket-fills.

**Reload:** ✅ Reload — category toggles and all blacklist snapshots are rebuilt atomically; listener wiring remains unchanged.

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
| `hashIps`  | boolean | `false`                                  | —                                    | When `true`, the `SESSION_JOIN` target is passed through `IpHasher` before it is stored. |
| `salt`     | string  | `"vonix-guardian-default-salt-CHANGE-ME"` | length `>= 16` when `hashIps` is true | Prefix mixed into the SHA-256 input by `IpHasher`. Ignored while `hashIps` is false. |

**Shipped default.** `hashIps` is **false**. `SESSION_JOIN` then stores the
raw remote address (`SocketAddress.toString()`) in `target` / `targetId`.
The JSON-lines audit file writes that same `target` string. It does not hash
on its own.

**Placeholder salt is a hard error, not a warning.** The shipped `salt`
value `vonix-guardian-default-salt-CHANGE-ME` is a placeholder. If
`hashIps` is `true` and the salt is still that placeholder,
`GuardianConfig.validate()` refuses to boot. It does not log a warning and
continue. The same refusal applies when the salt is null or shorter than 16
characters while hashing is on. Leave `hashIps` false to keep the raw
address, or set a salt of at least 16 characters before enabling hashing.

**Effect of `hashIps = true`:** `IpHasher.hash` returns `sha256:` plus the
first 16 hex characters of SHA-256 over the UTF-8 salt bytes followed by the
UTF-8 address bytes. That is a 64-bit prefix of the digest. It is not an
HMAC and it is not the full 256-bit digest. Only the `SESSION_JOIN` target
is rewritten. The JSON log stores that same rewritten value.

**Reload:** ❌ Restart — the hasher is read from the config that booted the
session capture.

---

## 9. `purge` — `/vg purge` safety floors

| Field                   | Type | Default      | Valid range  | Controls                                                          |
|-------------------------|------|--------------|--------------|-------------------------------------------------------------------|
| `minAgeSecondsConsole`  | long | `86400`      | `>= 60`      | Minimum age (sec) when purge is invoked from the **server console**. |
| `minAgeSecondsInGame`   | long | `2592000`    | `>= 3600`    | Minimum age (sec) when purge is invoked **in-game**.              |
| `autoPurgeSeconds`      | long | `0`          | `0` or `>= 2592000` | Retention horizon for the daily daemon. `0` means unbounded: the daemon does not run and existing rows are kept. |
| `autoPurgeTime`         | string | `"03:30"` | `HH:mm`    | Server-local time of day the daemon runs, when `autoPurgeSeconds` is not `0`. |

**Defaults in human units:** 1 day (console) / 30 days (in-game). The shipped
`autoPurgeSeconds` is the named constant
`GuardianConfig.DEFAULT_AUTO_PURGE_SECONDS` (`0`). `0` means unbounded
retention. `7776000` (90 days) is
`GuardianConfig.SUGGESTED_BUSY_SERVER_AUTO_PURGE_SECONDS`, a horizon a busy
server can set explicitly. It is not applied to new or existing configs.
Saving or reloading the config does not rewrite a file that already has a
value, and a missing key still deserialises as `0`.

When the **loaded** value is `0`, boot logs one warning
(`autoPurgeSeconds is 0 (unbounded retention...)`) and `/vg status` prints
an Auto-purge section that says the daemon is off and quotes the 90-day
suggestion. The warning follows the loaded value, so an operator who sets
`7776000` does not see it. `/vg reload` rebuilds the scheduler when
`autoPurgeSeconds` or `autoPurgeTime` changes.

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

**Reload:** the two age floors are read when a purge command runs. The
daemon schedule rebuilds on `/vg reload`.

---

## 9b. `rollback` — engine knobs

| Field | Type | Default | Controls |
|-------|------|---------|----------|
| `explosionSupplementalReach` | int | `16` | Extra blocks added to the supplemental explosion scan. |
| `mutationBatchSize` | int | `1000` | Mutations applied per tick during rollback and restore. |
| `requireConfirm` | boolean | `false` | When `true`, `/vg rollback` and `/vg restore` stop after the preview. `/vg apply` is the confirmation. |

**Shipped behaviour is unchanged.** `requireConfirm` defaults to `false`,
including when the key is absent from an existing `config.json`. At op level
3, rollback and restore still apply immediately. The permission and op-level
table is not changed.

**Recommended fleet setting:** `"rollback": { "requireConfirm": true }` (keep
the other two fields). Staff then see the preview and must run `/vg apply`.
Turn this on deliberately. `/vg reload` picks the new value up
(`rollback.requireConfirm` is in the hot-swap set).

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
    "sourceBlacklist": [],            // e.g. ["explosion:tnt"]
    "itemBlacklist": [],              // e.g. ["minecraft:diamond"]
    "entityBlacklist": []             // e.g. ["minecraft:zombie"]
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
    "minAgeSecondsInGame": 2592000,   // 30 days; floor 3600    s
    "autoPurgeSeconds": 0,            // 0 = unbounded; 7776000 = suggested 90-day horizon
    "autoPurgeTime": "03:30"
  },

  // rollback.requireConfirm defaults false. Recommended fleet setting is true.
  "rollback": {
    "explosionSupplementalReach": 16,
    "mutationBatchSize": 1000,
    "requireConfirm": false
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
| `actions`     | all blacklists                                        | Reload      | Restart       |
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
