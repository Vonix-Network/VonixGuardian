# VonixGuardian — Architecture Deep-Dive

> **Audience:** contributors hacking on the engine, loader glue, or storage
> backends. For end-user usage see `USAGE.md`; for the wire-level contracts the
> engine guarantees see `SHARED-CONTRACTS.md`.
>
> **TL;DR.** A pure-Java audit engine (`core/`) is sandwiched between
> per-MC-version model code (`mc-<ver>/common/`) and razor-thin loader glue
> (`mc-<ver>/{fabric,forge,neoforge}/`). All persistence goes through one
> bounded async queue. The MC server thread never blocks on I/O.

---

## 1. High-Level Diagram

VonixGuardian is built as three concentric layers. The innermost layer is the
audit engine; the outermost layer is the per-loader entry point. Each ring
**only depends inward** — `core/` knows nothing about Minecraft, and
`mc-<ver>/common/` knows nothing about Fabric/Forge/NeoForge.

```
┌──────────────────────────────────────────────────────────────────────────┐
│  mc-<ver>/fabric/    mc-<ver>/forge/    mc-<ver>/neoforge/               │
│  ──────────────────  ──────────────────  ────────────────────             │
│   • mod entry        • mod entry         • mod entry                     │
│   • event subs       • event subs        • event subs                    │
│   • perms bridge     • perms bridge      • perms bridge                  │
│   • cmd registrar    • cmd registrar     • cmd registrar                 │
│   ~30–50 LOC each — "wiring only"                                        │
│                            │                                             │
│                            ▼  (consumes)                                 │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  mc-<ver>/common/   (per-MC-version shared code, mojmap)           │  │
│  │   • NBT codecs (ItemStack ↔ SNBT, BlockState ↔ blob)               │  │
│  │   • Brigadier command tree (built from core's CommandSpec)         │  │
│  │   • Event payload models (block, container, entity, chat, ...)    │  │
│  │   • Mojmap mappings, MC-version-locked APIs                        │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                            │                                             │
│                            ▼  (consumes)                                 │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │  core/   (pure Java engine, ZERO Minecraft deps)                   │  │
│  │   action · config · event · queue · storage · query · rollback     │  │
│  │   logfile · perms · theme · command · attribution                  │  │
│  │   Compiles standalone against JDK 17 + SLF4J + HikariCP + JDBC.    │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

**Why three layers?**

| Layer            | Purpose                                                                 | Recompiled when…                |
| ---------------- | ----------------------------------------------------------------------- | ------------------------------- |
| `core/`          | Engine logic. Pure Java. Unit-testable without Minecraft.               | …engine logic changes.          |
| `mc-<ver>/common`| Translates between Minecraft types (this MC version) and core's models. | …new MC version is added.       |
| `mc-<ver>/<ldr>` | Loader-specific entry point: registers events, perms, commands.         | …a new loader is supported.     |

The `core/` jar is **byte-identical** across every MC-version × loader combo.
Adding a new MC version costs one `common/` module plus three thin glue
modules. Adding a new loader costs three glue modules (one per supported MC
version) and zero engine changes.

```
core.jar  (1 build)  ──┬──► mc-1.20.1/common.jar  ─┬─► fabric-1.20.1.jar
                       │                           ├─► forge-1.20.1.jar
                       │                           └─► neoforge-1.20.1.jar
                       ├──► mc-1.21.1/common.jar  ─┬─► fabric-1.21.1.jar
                       │                           ├─► forge-1.21.1.jar
                       │                           └─► neoforge-1.21.1.jar
                       └──► mc-1.x.x/common.jar   ─┴─► ... etc
```

See `LIBRARY-PACKAGING.md` for the JarInJar / shade strategy that ships
`core.jar` + transitive deps inside each loader jar.

---

## 2. Event Flow — One Block Break, End to End

A single dirt-block break travels through eight stages from the MC event bus
to disk. Each stage is replaceable; each is unit-tested in isolation.

```
   ┌───────────────────────────┐
   │  1. Loader event fires    │   Fabric: PlayerBlockBreakEvents.AFTER
   │  (loader-native types)    │   Forge:  BlockEvent.BreakEvent
   │                           │   NeoForge: BlockEvent.BreakEvent
   └────────────┬──────────────┘
                │  loader glue (~5 LOC per event)
                ▼
   ┌───────────────────────────┐
   │  2. Loader glue           │   Extracts (player, level, pos, state)
   │     translates → payload  │   from loader types; never reaches core.
   └────────────┬──────────────┘
                │
                ▼
   ┌───────────────────────────┐
   │  3. mc-common payload     │   NBT codecs serialize BlockState → blob,
   │     model assembled       │   resolves "minecraft:dirt", dim key, etc.
   └────────────┬──────────────┘
                │  call into core via EventSubmitter
                ▼
   ┌───────────────────────────┐
   │  4. EventSubmitter facade │   submitBlockBreak(actor, world, x,y,z,
   │     (core)                │     target, amount, sourceTag, nbt)
   └────────────┬──────────────┘
                │  EventGate filters (per-type toggle, blacklists)
                ▼
   ┌───────────────────────────┐
   │  5. AsyncWriteQueue       │   Bounded ring (configurable capacity),
   │     (bounded, lock-free)  │   non-blocking offer.
   │                           │   Overflow → drop + counter + warn.
   └────────────┬──────────────┘
                │  batch writer thread polls
                ▼
   ┌───────────────────────────┐
   │  6. Batch writer thread   │   Drains N records or T millis,
   │                           │   whichever comes first.
   └────────────┬──────────────┘
                │  fan-out (parallel)
        ┌───────┴──────────┐
        ▼                  ▼
┌───────────────┐  ┌───────────────────┐
│ 7a. JDBC DAO  │  │ 7b. JSON-Lines    │
│ batch INSERT  │  │     log file      │
│ (PreparedStmt)│  │  (rolling, gzip)  │
└───────┬───────┘  └───────────────────┘
        ▼
┌───────────────┐
│ 8. SQLite /   │
│    MySQL /    │
│    Postgres   │
└───────────────┘
```

**Key properties.**

- **Stage 4** is the only API surface loaders touch on the write path.
  `EventSubmitter` is an interface — loaders can be unit-tested against a
  recording fake without booting storage.
- **Stage 5 → 6** is the **only thread boundary on the write path.** Stages
  1–4 run on whatever thread the loader event fires on (almost always the
  server tick thread). Stages 6–8 run on the dedicated writer thread.
- **Stage 7a/7b run sequentially within stage 6**, not concurrently. The
  log file is written *first* (cheap, append-only) so that if the JDBC batch
  later fails, you still have a forensic record. SHARED-CONTRACTS § 6 calls
  this the "forensic-first" invariant.

---

## 3. Core Engine Internals

`core/` is partitioned into eleven cohesive packages. Each is a single
"chapter" of the engine. No package depends upward; the dependency graph is
a DAG rooted at `Guardian` (the facade).

```
        ┌───────────────────────────────────────┐
        │              Guardian                 │  (facade, AutoCloseable)
        └───────────────────────────────────────┘
            │     │     │     │     │     │
   ┌────────┘     │     │     │     │     └────────┐
   ▼              ▼     ▼     ▼     ▼              ▼
event/         queue/  storage/ rollback/ perms/  theme/
  │              │       │        │
  ▼              │       ▼        │
EventGate        │   query/       ▼
                 │   logfile/   action/
                 ▼
              BatchSink
```

### 3.1 `action/` — what events exist

- `ActionType` enum: **39 stable action types** (block break, block place,
  container in/out, sign edit, chat, command, login, death, kill, …).
  Every constant carries `id` (DB key), `token` (CLI alias),
  `category` (family for `a:<family>` expansion), and `sign`
  (place/break/neutral).
- `Action` record + `ActionBuilder`: canonical immutable representation of
  one auditable event. This is the unit of work everywhere downstream of
  stage 4.

### 3.2 `config/` — `guardian.toml`

- `GuardianConfig` record + `ConfigLoader`. Defaults are baked into the
  record; TOML overrides on top.
- `IpHasher` (BLAKE2b + per-install salt) — never logs raw IPs.

### 3.3 `event/` — the submission surface

- `EventSubmitter` interface (the only API loaders see for writes).
- `EventGate` — runs first inside `submit(Action)`; consults per-type toggles
  and blacklists from config before anything hits the queue.
- `Sentinel` — string constants for synthetic actors (`#creeper`, `#tnt`,
  `#fire`, `#piston`, …) when `actorUuid` is `null`.
- Event payload record is **8 fields**: `actor`, `world`, `x`, `y`, `z`,
  `target`, `amount`, `sourceTag` (+ optional NBT blob via overload).

### 3.4 `query/` — the lookup mini-language

- `QueryFilter` record: structured representation of a lookup
  (`time/player/action/world/radius/contains/...`).
- `QueryParser`: parses the `/vg lookup` mini-language (e.g.
  `p:Steve a:-block t:7d r:50`) into a `QueryFilter`.
- `QueryCompiler` (lives in `storage/`): compiles a `QueryFilter` into a
  **parameterized** `PreparedStatement`. **All identifiers are whitelisted**
  against a fixed set of column names; user input only ever flows through
  `?` placeholders. SQL injection is structurally impossible.

### 3.5 `queue/` — the async write path

- `AsyncWriteQueue` interface, `BatchedAsyncWriteQueue` impl.
- Bounded capacity (config: `queue.capacity`, default 64k).
- Backpressure mode is configurable: `drop_oldest` | `drop_newest` |
  `block_brief` (with a tiny bounded wait).
- Drops are counted (`overflow_drops` metric) and a single throttled WARN is
  logged per minute. The server thread never blocks more than the brief
  bounded wait.
- `BatchSink` is the consumer-side interface — the DAO is one possible sink,
  the JSON-lines log is another.

### 3.6 `logfile/` — forensic JSON-Lines

- `JsonLinesLogFile` — append-only, one JSON object per line.
- `LogRotator` — daily rotation, previous-day file gzipped, retention
  honored from config. Rotation is atomic-rename based; readers tailing
  the file see no corruption.

### 3.7 `storage/` — JDBC DAOs

- `GuardianDao` interface: `insertBatch`, `lookup(QueryFilter)`,
  `findUndoBatch`, `markRolledBack`, etc.
- `AbstractJdbcDao` — shared SQL skeleton.
- `SqliteDao` — **single-writer** discipline via `ReentrantLock`. SQLite
  can't tolerate concurrent writers; the lock guarantees only the batch
  writer thread holds the connection during a write.
- `MysqlDao`, `PostgresDao` — both backed by **HikariCP** pools; safe
  multi-reader, single-writer-thread access.
- `StorageFactory` — picks impl from config (`storage.driver`).
- `Schema` — DDL + migrations. Action `id`s in `ActionType` are
  **stable DB keys** — never renumber without a migration.

### 3.8 `rollback/` — reversible mutations

- `RollbackEngine` — plans + executes rollbacks.
- `RollbackPlan` — immutable list of mutations to apply, with a unique
  **batch ID** stamped onto every action row so the rollback itself is
  reversible (re-rollback = redo).
- `UndoStack` — keeps the most recent N batch IDs per actor for `/vg undo`.
- `WorldMutator` — **the only interface that touches Minecraft from core.**
  Loaders supply an impl (it lives in `mc-<ver>/common/`); core calls
  `setBlock`, `restoreContainer`, `respawnEntity`, etc.
- `PurgeEngine` — long-running prune of old rows, run in a background
  thread, never on the writer.

### 3.9 `perms/` — permissions

- `PermissionResolver` interface.
- `LuckPermsBridge` — **all reflection, never imports a LuckPerms class.**
  If LP isn't present, this resolver simply reports "not applicable" and
  the chain falls through.
- `OpLevelFallback` — vanilla op-level check, always available. Loader
  supplies a `(UUID → opLevel)` lookup function.

### 3.10 `theme/` — operator-facing strings

- `Theme` + `ThemeRegistry` — every operator-facing string (message keys,
  colors, hover/click components) flows through here so a server admin can
  re-theme without forking.

### 3.11 `command/` — the brigadier-shaped tree (sans brigadier)

- `CommandSpec`, `SubcommandSpec`, `ArgumentSpec` — pure Java records that
  describe the command tree (`/vg lookup`, `/vg rollback`, `/vg undo`,
  `/vg purge`, `/vg reload`, …).
- `mc-<ver>/common/` walks this tree and **builds the real Brigadier
  `LiteralArgumentBuilder`** against the version's API. The tree itself —
  including help text, permission nodes, and argument types — lives once,
  in core.

---

## 4. Loader-Glue Contract

Loader modules are intentionally tiny. The full contract is enumerated in
`SHARED-LOADER-CONTRACTS.md`; here is the responsibility matrix.

| Responsibility                       | Fabric | Forge | NeoForge | Notes                                                         |
| ------------------------------------ | :----: | :---: | :------: | ------------------------------------------------------------- |
| Mod manifest (`fabric.mod.json` etc) |   ✓    |   ✓   |    ✓     | Per-loader file format.                                       |
| Server-start hook → `Guardian.boot`  |   ✓    |   ✓   |    ✓     | One Guardian instance per server lifetime.                    |
| Event subscriptions                  |   ✓    |   ✓   |    ✓     | One handler per loader event; calls `EventSubmitter.submit*`. |
| Permission bridge (`UUID→opLevel`)   |   ✓    |   ✓   |    ✓     | Passed to `OpLevelFallback`.                                  |
| Main-thread `Executor`               |   ✓    |   ✓   |    ✓     | For posting rollback mutations back to the tick thread.       |
| Command registration                 |   ✓    |   ✓   |    ✓     | Walk `CommandSpec` → build Brigadier tree.                    |
| JarInJar / shade `core.jar` + deps   |   ✓    |   ✓   |    ✓     | Strategy per `LIBRARY-PACKAGING.md`.                          |

### 4.1 Size budget

Each loader module is targeted at **~30–50 lines of hand-written Java**, split
across a `Bootstrap` class (lifecycle) and an `Events` class (event handlers).
Anything bigger is a smell — that logic belongs in `mc-<ver>/common/`.

### 4.2 Command registration timing (Forge / NeoForge)

Fabric fires `CommandRegistrationCallback` exactly once at server start, after
`Guardian` is ready, and there is nothing to do. Forge and NeoForge fire
`RegisterCommandsEvent` *before* the server start hook that builds the
`Guardian`. The loader glue uses the **deferred-and-replay pattern**:

```
RegisterCommandsEvent fires
  └─► If Guardian == null:
        store CommandDispatcher in a pending slot
        return (commands not yet registered)
      Else:
        build Brigadier tree from g.commandSpec() and register now.

ServerStartingEvent fires
  └─► Guardian.boot(...)
      If pending dispatcher exists, replay registration onto it.
```

This guarantees the tree is registered exactly once, regardless of event order.

### 4.3 JarInJar / shade strategy

See `LIBRARY-PACKAGING.md` for full detail. Summary:

- **Fabric**: JarInJar (`nestedJars` in `fabric.mod.json`) for `core.jar`,
  HikariCP, JDBC drivers, Caffeine.
- **Forge / NeoForge**: shade `core.jar` and runtime deps into the loader
  jar with a relocation prefix to avoid colliding with other mods'
  vendored copies.

---

## 5. Threading Model

```
┌──────────────────────────────────────────────────────────────────────┐
│              MC server tick thread (the hot one)                     │
│                                                                      │
│   loader event ──► glue ──► EventSubmitter.submit(action)            │
│                                       │                              │
│                                       ▼                              │
│                              EventGate.allow?                        │
│                                       │                              │
│                                       ▼                              │
│                              queue.offer(action) ◄─── NEVER BLOCKS   │
│                                       │           (or bounded wait)  │
└───────────────────────────────────────┼──────────────────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│           Guardian-writer thread (single, daemon)                    │
│                                                                      │
│   poll batch (size N or time T)                                      │
│       │                                                              │
│       ├──► JsonLinesLogFile.appendAll(batch)  ← forensic-first       │
│       │                                                              │
│       └──► GuardianDao.insertBatch(batch)     ← may block on I/O     │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│           Guardian-lookup thread pool (small, daemon)                │
│                                                                      │
│   /vg lookup ──► async dispatch ──► QueryCompiler ──► DAO.lookup     │
│                                                          │           │
│                  results ◄──── deliver via mainThread Executor       │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│           Guardian-rollback thread (single, daemon)                  │
│                                                                      │
│   /vg rollback ──► plan ──► iterate mutations ──┐                    │
│                                                 ▼                    │
│                          mainThreadExecutor.execute(mutator::apply)  │
│                          (mutations land on the tick thread, batched)│
└──────────────────────────────────────────────────────────────────────┘
```

### Hard invariants

1. **The server tick thread never blocks on disk or network.** Writes go
   through the async queue; reads (`/vg lookup`) dispatch onto the lookup
   pool and deliver results via the main-thread `Executor`.
2. **All MC world mutations happen on the tick thread.** Rollback runs the
   *planning* off-thread, but every `WorldMutator.setBlock` call is posted
   back via `mainThreadExecutor` and applied in bounded chunks per tick
   (configurable `rollback.mutationsPerTick`).
3. **One writer thread per Guardian.** No fan-out into multiple writer
   threads — that would defeat SQLite's single-writer requirement and offer
   no real win on MySQL/Postgres at the volumes we target.
4. **Queue overflow is a counted, throttled warning.** Drops increment
   `overflow_drops` and emit one WARN per minute. Audit data is precious
   but the server's health is non-negotiable: dropping is the correct
   failure mode.

---

## 6. Modded Griefing Attribution — Quick Tour

Modpacks ship hostile-mob clones, summon-on-hit weapons, faked TNT, and
chunk-clearing rituals that vanilla logging would attribute to the wrong
actor (or to nobody). VonixGuardian threads a **damage-history chain**
through the engine so that, e.g., an Iron Spider summoned by Steve that
explodes 30 seconds later is correctly attributed to Steve.

The chain is:

```
   Player A hits/summons Entity X
         │
         ▼  DamageHistory record stored: X.lastDamager = A, ts
         │
   Entity X explodes / breaks blocks
         │
         ▼  AttributionResolver walks X.lastDamager chain
         │
   Action row written with:
     actorUuid = A,  sourceTag = "modded:<entity>:<chain-depth>"
```

The full algorithm — chain depth caps, time windows, entity-vs-projectile
disambiguation, transitive summon trees — lives in
`docs/MODDED-ATTRIBUTION.md`. The engine code is in
`core/src/main/java/network/vonix/guardian/core/attribution/`
(`Attribution`, `AttributionKind`, `AttributionResolver`, `DamageHistory`).

---

## 7. Soft-Dependency Pattern

Optional integrations (LuckPerms today; potentially others tomorrow) follow
**one strict pattern**:

1. **Never `import` an optional dependency's class.** Not in core, not in
   mc-common, not in loader glue.
2. **All access is via reflection** behind a small bridge class.
3. **The bridge probes for presence** at construction time
   (`Class.forName("net.luckperms.api.LuckPermsProvider")`); if absent, it
   becomes a no-op resolver and the chain falls through to the fallback
   (e.g. `OpLevelFallback`).
4. **The bridge wraps all reflective calls** in a single try/catch that
   logs at WARN once and then degrades to no-op — a misbehaving optional
   dep must never take down the audit pipeline.

Concrete example, `perms/LuckPermsBridge`:

```
PermissionResolver lpResolver = LuckPermsBridge.tryCreate();
         │
         ▼
   ┌──────────────────────────────────────────┐
   │ tryCreate():                             │
   │   try {                                  │
   │     Class.forName(LP_PROVIDER_FQN);      │
   │     return new LuckPermsBridge(...);     │
   │   } catch (ClassNotFoundException) {     │
   │     return PermissionResolver.NOOP;      │
   │   }                                      │
   └──────────────────────────────────────────┘
```

This pattern extends to **any** future optional integration: economy mods,
claim mods, chat mods, metrics exporters. **Never hard-import a mod
class.** A hard import means our jar fails to load wherever that mod is
absent — unacceptable for a logging utility that must be present
*everywhere*.

The same rule applies inside loader glue: glue may import the loader's
event types (`net.minecraftforge.event.level.BlockEvent`, etc.) but
**never** another mod's types. If a future feature needs another mod's
data, it goes through a reflective bridge in `core/` (or `mc-common/` if
MC-version-specific).

---

## See also

- [docs/MODDED-ATTRIBUTION.md](MODDED-ATTRIBUTION.md) — full damage-history /
  attribution algorithm.
- [docs/DEVELOPMENT.md](DEVELOPMENT.md) — local build, test, debug workflows.
- [SHARED-CONTRACTS.md](../SHARED-CONTRACTS.md) — internal contracts the
  engine guarantees to loader code (action ids, queue semantics, log format,
  query mini-language).
- [SHARED-LOADER-CONTRACTS.md](../SHARED-LOADER-CONTRACTS.md) — what each
  loader module must provide to the engine (events, perms, executor,
  command registrar).
- [LIBRARY-PACKAGING.md](../LIBRARY-PACKAGING.md) — JarInJar vs shade
  packaging strategy per loader.
