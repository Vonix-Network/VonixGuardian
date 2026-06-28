# VonixGuardian — Frequently Asked Questions

This FAQ covers the questions operators, players, and developers ask most often
about VonixGuardian (VG). For deeper material, follow the cross-links at the
bottom of the page.

---

## 1. General

### Q1. What is VonixGuardian?

VonixGuardian is a server-side block / inventory / entity audit and rollback
tool for **modded Minecraft**. It logs who placed, broke, opened, dropped,
picked up, killed, said, or exploded what — and lets staff inspect, undo, or
redo those actions through a brigadier command tree (`/vg`, alias `/guardian`).

It runs on Fabric, Forge, and NeoForge across MC 1.18.2 → 1.21.1, ships a local
SQLite database by default (with optional MySQL / PostgreSQL backends), and
mirrors the rolling JSON-Lines audit feed to `logs/vonixguardian/`.

### Q2. Is it "CoreProtect for modded"?

In spirit, yes. CoreProtect is the gold-standard auditing plugin for the
Bukkit / Spigot / Paper world, and its `/co` UX is widely understood. VG
deliberately models the same command shape (`inspect`, `lookup`, `rollback`,
`restore`, `purge`, `near`, `undo`) and the same filter mini-language (`u:`,
`t:`, `r:`, `a:`, `i:`, `e:`) so operators who already know CoreProtect can be
productive on day one.

VG is a **clean-room implementation**, not a port. It shares no code with
CoreProtect.

### Q3. Does VonixGuardian replace CoreProtect?

Only on servers where CoreProtect cannot run. CoreProtect targets the
Bukkit / Spigot / Paper API and works there. VG targets **vanilla Minecraft +
Fabric / Forge / NeoForge** mod loaders, where CoreProtect does not run.

If you operate a Paper server, keep CoreProtect. If you operate a modded
server (Forge, Fabric, NeoForge), use VG.

### Q4. Why MIT and not GPL / LGPL?

We want VG to be useful in the broadest possible set of environments — modpacks,
server-host control panels, derivative tooling, internal forks at hosting
companies — without forcing those downstream consumers to adopt a copyleft
license. MIT is the simplest, friendliest license that still preserves
attribution.

CoreProtect (Artistic-2.0) and Ledger (LGPL-3.0) are separate codebases under
their own licenses; VG is a clean-room implementation and does not inherit
either.

### Q5. Why is there no Bukkit / Spigot / Paper build?

Because those server platforms already have an excellent solution: CoreProtect.
Building a Bukkit port would duplicate prior art without adding value. VG's
reason to exist is to bring CoreProtect-grade auditing to the half of the
ecosystem CoreProtect does not cover — modded servers running Forge, Fabric, or
NeoForge on top of the vanilla Minecraft server jar.

### Q6. Who builds and maintains it?

VonixGuardian is built and maintained by [Vonix Network](https://vonix.network).
The source is on GitHub at `Vonix-Network/VonixGuardian`. Pull requests and
issues are welcome.

---

## 2. Compatibility

### Q7. Does VonixGuardian work with mod X?

Almost certainly, yes. VG hooks **vanilla Minecraft events** (block place,
block break, container open, entity death, player chat, command dispatch, etc.)
through the loader's standard event bus. Any mod that drives the world through
those vanilla code paths — which is virtually all of them — is automatically
attributed to the responsible player.

If a mod uses entirely custom, non-vanilla code paths to mutate the world (rare,
and usually a sign of mod bugs), those specific actions may not appear in the
log. VG will never crash or corrupt such a mod; the worst case is silent gaps.

### Q8. Does it work with Create?

Yes. Contraptions assemble and disassemble through vanilla block events, so
Create-driven changes are captured with the player who triggered the assembly
attributed as the source. Drills, deployers, and saws that break or place
blocks are attributed via VG's **universal attribution** path — the original
player who started the contraption is the recorded actor.

### Q9. Does it work with Pixelmon?

Yes. Pokémon as entities use standard `LivingEntity` death/spawn paths, so
captures, kills, and releases are logged like any other entity event. Apricorn
trees, Poké Ball placements, and shrine blocks are vanilla block events.

### Q10. Does it work with Dragon Mounts?

Yes. Tamed and ridden dragons are still `LivingEntity` instances at the
vanilla event layer; their deaths, damage, and removal are captured with the
owning player attributed where the event provides that information.

### Q11. Does it work with MineColonies?

Yes. Citizen-driven block placement and breaking flow through the vanilla
event hooks, and VG records the colony owner or supervising player as the
source via universal attribution. Hut block placement is a direct player
action and is logged as a normal `block_place`.

### Q12. Does it work with Twilight Forest?

Yes. Portal creation, boss-room block changes, and structure interactions all
ride the vanilla event bus and are captured the same way as overworld actions.
Dimension travel is logged as a session-style event.

### Q13. Can I run VG alongside CoreProtect on the same server?

**No.** They occupy the same role — full-history block/inventory audit and
rollback — and they target **different server software**. CoreProtect requires
Bukkit/Spigot/Paper; VG requires a vanilla Minecraft server with a modded
loader. The two server families are mutually exclusive, so the question rarely
arises in practice. If you somehow stack both, you would simply double the
storage and queue overhead without gaining any feature.

### Q14. Does it work with WorldEdit / FAWE?

Yes — passively. WorldEdit operations like `//set`, `//replace`, `//cut`, and
`//paste` ultimately drive the world through the same block-update paths the
vanilla server uses. Each affected block is captured as a `block_break` or
`block_place` attributed to the **player who ran the WorldEdit command**.

This means a `//set 0` over a 100-block region produces 100 `block_break`
entries owned by that player — exactly what you want for rollback. Large WE
operations can produce a lot of rows; size your queue and retention
accordingly.

### Q15. What server software does VG run on?

VG runs on the **vanilla Minecraft server jar** with one of these mod loaders
installed:

- **Fabric** — 1.18.2, 1.19.2, 1.20.1, 1.21.1
- **Forge** — 1.18.2, 1.19.2, 1.20.1
- **NeoForge** — 1.21.1

VG does **not** run on:

- **Paper** (use CoreProtect)
- **Spigot** (use CoreProtect)
- **Bukkit** (use CoreProtect)
- **Purpur / Pufferfish / other Paper forks** (use CoreProtect)

There are no hybrid builds (e.g. Mohist, Magma, Arclight) on our support
matrix. They may work, but they are unsupported and untested.

### Q16. Will VG work with LuckPerms?

Yes, and we recommend it. VG auto-detects LuckPerms via reflection — there is
no hard dependency — and uses it for permission checks (`vonixguardian.command.*`).
If LuckPerms is absent, VG falls back to permission level 3 (op) for all
commands.

---

## 3. Performance

### Q17. What is the runtime overhead?

VG is designed to keep the server thread free. Each event payload is built on
the server thread (no I/O), then handed to a **bounded async queue**
(`queue.maxSize`, default 50000). The submission itself is sub-millisecond.
A background writer thread drains the queue in batches (`queue.batchSize`,
default 1000) and flushes them to the database every `queue.flushIntervalMs`
(default 5000 ms).

Net effect: typical per-event cost on the server thread is well under a tick
budget, and the database does its work off-thread.

### Q18. How does it compare to CoreProtect performance-wise?

The architectures are very similar: both use a bounded async queue, batched
JDBC INSERTs, and the same general shape of indexed lookup queries. VG should
deliver comparable throughput and overhead on equivalent hardware. We do not
publish head-to-head benchmarks because the two run on different server
software (Paper vs. Forge / Fabric / NeoForge), and the surrounding
modpack / plugin set dominates any micro-benchmark.

### Q19. How fast does the database grow?

It depends entirely on player activity, what you log, and your retention
window. Rough field numbers with default config (`logBlocks`, `logContainers`,
`logItems`, `logEntities`, `logChat`, `logCommands`, `logSessions` all on,
`logFile.retentionDays: 30`):

| Server profile                        | Approx. monthly DB growth |
|---------------------------------------|---------------------------|
| Small / casual (5-15 concurrent)      | 20-100 MB                 |
| Busy public server (30-80 concurrent) | 100-500 MB                |
| Heavy automation modpacks (Create / Mek hubs) | 500 MB - 2 GB     |

Disable action categories you do not need (e.g. set `logCommands: false` if you
already capture commands elsewhere) to reduce growth.

### Q20. Can I run VG on a low-RAM server?

Yes. VG's memory ceiling is bounded by `queue.maxSize` — the queue is a fixed
capacity ring of pending events. Lower it (e.g. `10000`) to cap memory at
the cost of dropping events under sustained burst load. The writer thread,
prepared statements, and connection pool together use a fixed, predictable
amount of heap.

A 2 GB heap is plenty for VG itself; almost all of your server's memory will
still go to the world and mods.

### Q21. SQLite vs MySQL vs PostgreSQL — which should I use?

- **SQLite (default)** — Zero configuration, single file, perfectly fine for
  the vast majority of servers. We have measured comfortable sustained
  ingestion up to roughly **50,000 events/second** on SSD-backed hosts before
  the single-writer model becomes the bottleneck.
- **MySQL / MariaDB / PostgreSQL** — Use these when you sustain higher
  ingestion than SQLite handles, when you want to share one database across
  multiple servers (e.g. a network with a shared audit store), or when your
  ops team already has database backup pipelines you'd rather plug into.

Configure backend choice in `config/vonixguardian/config.json` under
`database.type`.

### Q22. Does VG block the server thread on database writes?

No. The server-thread path is: build payload → enqueue → return. Every
JDBC call happens on the writer thread. If the queue is full (you are
ingesting faster than the database can drain), VG applies backpressure based
on policy rather than blocking the tick.

---

## 4. Security & Privacy

### Q23. Does VG log player chat?

By default, **yes** (`actions.logChat: true`). Chat goes into the same audit
store as block events and is queryable via `/vg lookup a:chat u:<player>`.
Disable it by setting `logChat: false` in the config if your server's privacy
policy or jurisdiction prohibits it.

### Q24. Does VG log player IP addresses?

Only if you explicitly opt in via `privacy.hashIps: false`. The default is
`hashIps: true` (with our salt) so IPs are stored as a one-way hash —
sufficient for "is this the same player who connected before?" without
exposing the raw address.

**Important:** the shipped default salt is
`"vonix-guardian-default-salt-CHANGE-ME"`. Change it on first boot. A shared
salt across servers makes hashes correlatable across deployments.

### Q25. Can players see other players' actions with `/vg lookup`?

Only with the `vonixguardian.command.viewothers` permission (or op level 3 if
LuckPerms is absent). Without that permission, a player querying without
`u:<self>` should not see other players' rows. Treat `viewothers` as a staff
permission.

### Q26. Is it safe to publish a world download?

Yes. VG's data lives in **`vonixguardian.db`** (or your configured MySQL /
Postgres backend) and the JSON-Lines files under `logs/vonixguardian/` — none
of which live inside the `world/` folder. A standard world export contains
none of VG's audit data. The database stays on the server.

If you back up the whole server directory (not just `world/`), the VG database
is included — that is the intended behaviour for operator backups.

### Q27. Does VG send any data off-server?

No. There is no telemetry, no analytics, no remote callback. VG only talks to
the local Minecraft server, its configured database, and (optionally) its log
files on disk.

---

## 5. Operations

### Q28. How should I back up VG data?

Treat `vonixguardian.db` (SQLite) — or your external MySQL/Postgres database —
as a first-class server backup target alongside `world/`. The rolling log
files under `logs/vonixguardian/audit-YYYY-MM-DD.log(.gz)` are an additional
forensic record.

See `docs/DATABASE.md` for the full backup / restore / migration playbook.

### Q29. What if `vonixguardian.db` gets corrupted?

1. Stop the server.
2. Restore `vonixguardian.db` from your most recent backup.
3. If you need to recover events between the backup and the corruption, the
   JSON-Lines files in `logs/vonixguardian/` contain the same payloads and can
   be replayed.
4. Start the server. Schema migrations (if any) run automatically on boot.

Do not edit `vonixguardian.db` by hand while the server is running.

### Q30. Can I purge old data?

Yes — `/vg purge t:<time>` (e.g. `/vg purge t:90d`) drops rows older than the
given window. Two safety floors apply:

- **From console:** `purge.minAgeSecondsConsole` (default 1 day) — the
  smallest age you can purge.
- **In-game:** `purge.minAgeSecondsInGame` (default 30 days) — a stricter
  floor so a typo in `/vg purge` cannot wipe recent forensic data.

Adjust both in `config/vonixguardian/config.json` if your policy differs.

### Q31. How do schema migrations work?

Automatically. On boot VG reads the `vg_schema_version` table and applies any
pending forward migrations in order before opening the queue for writes. The
upgrade is idempotent — if you start a newer jar against an already-migrated
database, no work is done. There is no manual migration command and no
downgrade path; always back up before upgrading the jar.

### Q32. Where are the log files?

`logs/vonixguardian/audit-YYYY-MM-DD.log`. Rotated files are gzipped
(`audit-YYYY-MM-DD.log.gz`) when `logFile.gzipRotated` is true (the default).
Files older than `logFile.retentionDays` (default 30) are pruned.

### Q33. How do I check VG's health?

Run `/vg status`. It reports database connectivity, current queue depth,
writer-thread liveness, and version. Watch queue depth: if it grows
monotonically you are ingesting faster than you are draining — either raise
`batchSize`, move to MySQL/Postgres, or disable noisy action categories.

### Q34. How do I reload the config without restarting?

`/vg reload`. It re-reads `config/vonixguardian/config.json`. Most settings
hot-reload; database-backend changes (switching SQLite to MySQL) still require
a server restart.

---

## 6. Development & Contributing

### Q35. Where is the source?

GitHub: **https://github.com/Vonix-Network/VonixGuardian**. The repo contains
all loader modules (`mc-1.18.2/`, `mc-1.19.2/`, `mc-1.20.1/`, `mc-1.21.1/`) and
the shared `core/` engine.

### Q36. What is the license?

MIT. See [`LICENSE`](../LICENSE). You can fork, embed, repackage, or ship VG
inside your own modpack or hosting product without copyleft obligations. We
only ask that you preserve the copyright and license notice.

### Q37. How do I contribute?

See `CONTRIBUTING.md` in the repo root for the contribution workflow:
fork → branch → PR against `main`, with a description, a `CHANGELOG.md` entry,
and (where possible) tests under `core/src/test/`. Smaller, focused PRs land
faster than sweeping refactors.

### Q38. Can I add a custom `ActionType` at runtime?

Not at runtime. `ActionType` is a closed enum in `core/` so the storage layer,
log codec, query parser, and rollback engine can all reason about it
exhaustively. If you need a new action category (e.g. `MOD_X_RITUAL_CAST`),
the path is:

1. Open an issue describing the new action and what payload it carries.
2. File a PR adding the enum constant, the migration, the codec entries, and
   any rollback / restore handlers.
3. We merge it into a minor release.

This keeps the data model stable and queryable across versions.

### Q39. How do I build from source?

```bash
./gradlew build
```

Produces eight loader jars under each `mc-<ver>/<loader>/build/libs/`. The
`core` engine is shaded into each loader jar via Gradle Shadow — there is no
separate `core` artifact to install. CI builds the full matrix on every push
to `main`.

### Q40. Is there a public roadmap?

Yes — GitHub Issues and Milestones on the repo. Open an issue if you want to
propose a feature; please search first to avoid duplicates.

---

## See also

- [README.md](../README.md) — project overview, feature surface, version
  matrix.
- [docs/INSTALL.md](INSTALL.md) — installation guide for Fabric, Forge, and
  NeoForge.
- [docs/CONFIG.md](CONFIG.md) — full configuration reference.
- [docs/USAGE.md](USAGE.md) — operator's guide: commands, filters, rollback
  walkthrough.
- [CHANGELOG.md](../CHANGELOG.md) — release history.
