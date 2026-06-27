# Changelog

All notable changes to **VonixGuardian** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2026-06-27

First public release. CoreProtect 1:1 feature surface with universal modded-entity griefing attribution. **8 drop-in jars** covering the four most-deployed modded Minecraft versions × {Fabric, Forge/NeoForge}.

### Coverage matrix

| MC version | Fabric | Forge | NeoForge |
|-----------:|:------:|:-----:|:--------:|
| 1.21.1     |   ✅   |   —   |    ✅    |
| 1.20.1     |   ✅   |   ✅  |    —     |
| 1.19.2     |   ✅   |   ✅  |    —     |
| 1.18.2     |   ✅ * |   ✅  |    —     |

\* Fabric 1.18.2: chat capture deferred to v0.2.0 (no `ServerMessageEvents.CHAT_MESSAGE` in fabric-api 0.77 — needs a Mixin).

### Added

#### Core engine (`core/`)
- **39 action types** matching the full CoreProtect listener surface plus the modded griefing path:
  - Block: `BLOCK_PLACE`, `BLOCK_BREAK`, `BURN`, `IGNITE`, `FADE`, `FORM`, `SPREAD`, `DISPENSE`, `PISTON_EXTEND`, `PISTON_RETRACT`, `BUCKET_EMPTY`, `BUCKET_FILL`, `LEAVES_DECAY`, `ENTITY_CHANGE_BLOCK`
  - Container: `CONTAINER_DEPOSIT/WITHDRAW`, `INVENTORY_DEPOSIT/WITHDRAW`, `HOPPER_PUSH/PULL`
  - Item: `ITEM_DROP`, `ITEM_PICKUP`, `ITEM_CRAFT`
  - Entity: `ENTITY_KILL`, `ENTITY_SPAWN`, `ENTITY_INTERACT`, `HANGING_PLACE/BREAK`
  - World: `EXPLOSION`, `STRUCTURE_GROW`, `PORTAL_CREATE`, `CHUNK_POPULATE`
  - Message: `CHAT`, `COMMAND`, `SIGN`
  - Session: `SESSION_JOIN/LEAVE`, `USERNAME_CHANGE`
  - Interact: `CLICK`
- **`/vg lookup` filter mini-language** parser supporting CoreProtect `u:` / `t:` / `r:` / `a:` / `i:` / `e:` / `#preview` / `#count` / `#verbose` / `#silent`, with family expansion (`a:block`, `a:container`, etc.).
- **SQLite, MySQL, and PostgreSQL storage backends** behind a single `GuardianDao` interface. HikariCP pooling; SQLite serialized via `ReentrantLock`. Schema v2 with `vg_actions` + `vg_users` + `vg_worlds` + `vg_rollback_batches` + `vg_rollback_batch_actions`.
- **Server-thread-friendly async write queue** (`BatchedAsyncWriteQueue`): bounded `ArrayBlockingQueue`, batch INSERTs, 3× retry on sink failure with 250ms backoff, marker-logged drops.
- **Rolling JSON-Lines audit log file** at `logs/vonixguardian/audit-YYYY-MM-DD.log.jsonl` with daily gzip rotation and configurable retention.
- **Rollback engine** with position-dedup, newest-first ordering, SQL-side rolledBack filtering, batched main-thread dispatch (max 1000 per tick), and crash-recovery via `vg_rollback_batches` audit table.
- **`/vg purge`** with configurable minimum-age guard (CoreProtect parity: 24h from console / 30d in-game by default).
- **Permission resolver** with reflective LuckPerms bridge (zero hard dependency) and tri-state availability cache; op-level fallback if LP absent.
- **`/vg lookup` rate limit** via fair semaphore (configurable `maxConcurrent`); result-row cap (configurable `maxResultRows`, default 100,000).
- **IP hashing** (SHA-256, salted, 64-bit prefix) for `SESSION_JOIN` events; default disabled.
- **Universal modded-entity attribution** (`core/attribution`):
  - `AttributionResolver` interface walks a 6-step universal chain (rider → tameable owner → ownable owner → projectile recurse → recent damage → NBT scan → natural classification).
  - `DamageHistory` ring buffer (20s window, 10k entry cap, LRU eviction) for "berserk mob" indirect attribution.
  - `Attribution` record encodes the responsible-party UUID + kind + entity sentinel + chain hops.
- **Themed chat** (7 built-in palettes): aqua (default), blue, gold, green, purple, red, white.
- **354 unit + integration tests** (JUnit 5 + AssertJ + Mockito) including a 1000-action SQLite integration test, semaphore-cap test, and crash-recovery batch test.

#### Loader modules

8 loader jars implementing the universal event surface using only first-class loader events on every MC version × loader pair:

- **NeoForge 1.21.1**: full event coverage including `LivingDestroyBlockEvent`, the universal modded griefing path.
- **Forge 1.20.1 / 1.19.2 / 1.18.2**: same coverage; per-MC API drift handled (event package rename, Registry vs BuiltInRegistries, Component.literal vs TextComponent, getControllingPassenger return type, etc.).
- **Fabric 1.21.1 / 1.20.1 / 1.19.2**: player-driven event coverage via fabric-api callbacks (`PlayerBlockBreakEvents`, `UseBlockCallback`, `AttackBlockCallback`, `ServerLivingEntityEvents`, `ServerEntityEvents`, `ServerPlayConnectionEvents`, `ServerMessageEvents`, `CommandRegistrationCallback`).
- **Fabric 1.18.2**: same except chat/command capture (no `ServerMessageEvents` until 1.19) and `ALLOW_DAMAGE` indirect-attribution feed (no event) — flagged for v0.2.0.

Each loader implements:
- `WorldMutator` for rollback world mutations (setBlock / give-or-drop / removeFromContainer / respawnEntity).
- `OpLevelFallback` (op-level resolution via the server's player list).
- `AttributionResolver` (the universal chain using vanilla `Entity` / `TamableAnimal` / `OwnableEntity` / `Projectile` interfaces that all modded entities inherit).
- Brigadier command tree wiring for `/vg` (alias `/guardian`) with all subcommands.
- Inspector mode left-click cancel and per-player toggle state.

Each loader jar shades:
- The pure-Java `core` engine
- `sqlite-jdbc 3.46.1.0` (default DB driver)
- `HikariCP 5.1.0`
- `Gson 2.10.1`

with package relocation under `network.vonix.guardian.shadow.*` to avoid mod-pack conflicts.

#### Build infrastructure

- **Profile-based build**: `-PbuildProfile=core|mc1211|mc1201|mc1192|mc1182|all` (default `all`) so any subset of jars can be built without configuring unrelated loader plugins.
- **Gradle version catalog** (`gradle/libs.versions.toml`) with every dependency pinned.
- **GitHub Actions matrix workflows**: `build.yml` runs every push (full test + 8-jar matrix); `release.yml` runs on tag push (builds + SHA-256 checksums + creates the GH release with all 8 jars attached).

### Known limitations (planned for v0.2.0)

- Fabric versions 1.18.2 / 1.19.2 / 1.20.1 / 1.21.1: `LivingDestroyBlock` (modded mob griefing), piston, leaves decay, neighbor notify, explosion-detonate affected-block list, item toss/pickup/craft, and sign change events all require Mixins on Fabric (fabric-api exposes no first-class hooks). Player-driven events are fully covered today; non-player block changes by mobs are partially deferred.
- Fabric 1.18.2 only: chat + command capture also deferred to v0.2.0 (no `ServerMessageEvents.CHAT_MESSAGE` in fabric-api 0.77 — needs a Mixin into `ServerGamePacketListenerImpl#handleChat`).
- Metrics export (Prometheus): no scrape endpoint in v0.1.0.
- WorldEdit selection (`r:#we` / `r:#worldedit`): the filter parser accepts the token but the loader-side WE region lookup is not wired yet.
- MySQL and PostgreSQL backends: code paths land but are documented as **beta** — SQLite is the fully tested backend for v0.1.0.

### Architecture

- Pure-Java engine in `core/` with zero Minecraft dependencies (10,465 LOC).
- Per-MC `common/` packages with MC-version-specific Mojmap code (the brigadier commands, chat rendering, source tagging, attribution helpers).
- Thin loader modules (~30-50 LOC each for the entrypoint + event-routing classes; rest is shared `common/`).
- No `architectury` runtime dependency; the engine is a regular shaded library.

### License

MIT. See [LICENSE](LICENSE). Inspired by CoreProtect (Artistic-2.0; clean-room implementation, no source copied) and Ledger (LGPL-3; same).

[0.1.0]: https://github.com/Vonix-Network/VonixGuardian/releases/tag/v0.1.0
