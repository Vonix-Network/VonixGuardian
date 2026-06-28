# Migration Guide — VonixGuardian v1.0.0

This document covers operational migrations: switching storage backends, upgrading
the plugin, and what is (and isn't) supported when moving data into or out of
VonixGuardian.

> **v1.0.0 is the first stable release.** There are no prior public versions to
> migrate *from*. The sections below describe forward migrations (backend
> switches, future upgrades) and explicitly call out what v1.0.0 does **not** do
> so operators don't wait for features that aren't shipping.

---

## 1. Switching storage backends (SQLite ↔ MySQL ↔ PostgreSQL)

VonixGuardian supports three JDBC backends behind a single DAO layer: SQLite
(default), MySQL/MariaDB, and PostgreSQL. Switching is a configuration change —
the schema is auto-initialised on the first boot against the new backend.

### Procedure

1. **Stop the server.**
2. **Back up the existing database** (see [§5](#5-backup-before-migration)).
3. Edit `config/vonixguardian/config.yml` and change the `storage.type` key.
4. Fill in the connection details for the new backend (see examples below).
5. **Start the server.** On first boot VonixGuardian will:
   - Connect to the configured backend.
   - Detect that `vg_schema_version` does not exist.
   - Create all tables (`vg_users`, `vg_worlds`, `vg_actions`,
     `vg_rollback_batches`, `vg_rollback_batch_actions`) and stamp
     `vg_schema_version` with the current baseline.

### Example configuration

**SQLite** (default, zero-config):

```yaml
storage:
  type: sqlite
  sqlite:
    file: vonixguardian.db   # relative to the plugin data folder
```

**MySQL / MariaDB**:

```yaml
storage:
  type: mysql
  mysql:
    jdbc_url: "jdbc:mysql://db.example.net:3306/vonixguardian?useSSL=true&serverTimezone=UTC&characterEncoding=utf8"
    username: vonixguardian
    password: "********"
    pool:
      maximum_pool_size: 10
      minimum_idle: 2
      connection_timeout_ms: 30000
      idle_timeout_ms: 600000
      max_lifetime_ms: 1800000
```

**PostgreSQL**:

```yaml
storage:
  type: postgres
  postgres:
    jdbc_url: "jdbc:postgresql://db.example.net:5432/vonixguardian?sslmode=require"
    username: vonixguardian
    password: "********"
    pool:
      maximum_pool_size: 10
      minimum_idle: 2
      connection_timeout_ms: 30000
      idle_timeout_ms: 600000
      max_lifetime_ms: 1800000
```

### HikariCP pool tuning

All non-SQLite backends use HikariCP. The defaults shown above are a sensible
starting point for a single Minecraft server. Tune them as follows:

- `maximum_pool_size` — upper bound on concurrent JDBC connections. For a
  typical 50–200 player server, `10` is plenty. Raise if you see contention on
  the DAO; lower if your DB has tight `max_connections`.
- `minimum_idle` — keeps a warm pool to absorb burst writes (combat logs,
  WorldEdit operations).
- `connection_timeout_ms` — how long a caller waits for a connection before
  failing.
- `idle_timeout_ms` / `max_lifetime_ms` — recycle idle and aged connections;
  defaults track HikariCP guidance.

See `docs/CONFIG.md` for the full key reference.

### ⚠️ Known limitation — no automatic data migration in v1.0.0

**v1.0.0 does not copy data when you change `storage.type`.** Switching from
SQLite to MySQL (or any other combination) gives you a fresh, empty schema on
the new backend. Existing rows in the old backend are left untouched but are
**not** read by the new one.

If you need to preserve history across a switch you must export and import
manually using your database's native tooling, for example:

- SQLite → SQL dump: `sqlite3 vonixguardian.db .dump > export.sql`
- MySQL load: `mysql vonixguardian < export.sql` (after syntax adjustments —
  SQLite and MySQL dialects differ on identifiers, autoincrement, and BLOB
  literals).
- PostgreSQL: similar; `pg_restore` / `psql -f`.

Automated cross-backend export/import is on the roadmap for a later v1.x
release. No ETA.

---

## 2. Importing from CoreProtect or Ledger

**Not supported in v1.0.0.**

VonixGuardian is a clean-room implementation with its own schema
(`vg_*` tables) that is not compatible with CoreProtect's `co_*` tables or
Ledger's tables. There is no import tool, no shim, and no plan to support
in-place reuse of those databases in this release.

If you are migrating from CoreProtect or Ledger you have two options:

1. **Run both plugins in parallel for a cutover window.** Keep the old plugin
   installed read-only (or its database accessible) so you can answer historical
   lookups against it, while VonixGuardian starts logging fresh from cutover.
2. **Accept a clean break** — VonixGuardian begins with empty history.

A CoreProtect/Ledger importer is tracked as a future v1.x feature. **No ETA.**
Do not block your deployment waiting for it.

---

## 3. Version upgrade procedure

Upgrading VonixGuardian itself is intentionally boring:

1. Stop the server (or use your normal maintenance window).
2. Take a backup ([§5](#5-backup-before-migration)).
3. Remove the old `VonixGuardian-*.jar` from `mods/`.
4. Drop the new `VonixGuardian-*.jar` into `mods/`.
5. Start the server.

On boot VonixGuardian reads the `vg_schema_version` table. If the recorded
version is lower than the version embedded in the jar, the additive DDL for the
intermediate versions is applied automatically and the row in
`vg_schema_version` is updated. If the versions match, nothing happens.

**v1.0.0 ships the baseline schema (version 1.)** Future point releases that
require schema changes will ship additive `CREATE TABLE IF NOT EXISTS` /
`CREATE INDEX IF NOT EXISTS` migrations; destructive migrations (column drops,
type changes) will be called out explicitly in `CHANGELOG.md` and will require
a backup before upgrade.

---

## 4. Downgrade

**Downgrade is not supported.**

The SQLite database file (and the MySQL/PostgreSQL schemas) are forward-only
after any schema upgrade. A jar from an older version will refuse to start
against a database stamped with a newer `vg_schema_version` rather than risk
silently truncating columns it doesn't understand.

If you need to roll back a VonixGuardian version:

1. Stop the server.
2. Restore the pre-upgrade database backup.
3. Replace the jar with the older version.
4. Start the server.

This is why **taking a backup before every upgrade is mandatory**, not
optional.

---

## 5. Backup before migration

Always back up the active database before any of the operations above —
backend switch, jar upgrade, or downgrade restore.

The procedure (native dump for MySQL/Postgres, file copy with the server
stopped for SQLite, plus retention guidance) is documented in
[`docs/DATABASE.md` § Backup & Restore](DATABASE.md#backup--restore).

---

## See also

- [`docs/DATABASE.md`](DATABASE.md) — schema reference, backup & restore
  procedure, dialect notes.
- [`docs/CONFIG.md`](CONFIG.md) — full configuration key reference, including
  every `storage.*` and HikariCP pool key.
