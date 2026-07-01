# VonixGuardian vs CoreProtect — Complete Difference Matrix

Snapshot: VG `v1.1.5-entity-filter` HEAD (2026-07-01) — including the in-flight `VanillaGrieferSet` work the other agent is landing on `v1.1.5-entity-filter`.
Reference: CoreProtect docs @ `docs.coreprotect.net` (v23.x + v24 Patreon features).

TL;DR: VG is a **clean-room, CoreProtect-shaped audit/rollback tool for the modded loaders CP does not target** (Forge / NeoForge / Fabric on MC 1.18.2–1.21.1). The command surface, filter mini-language and hashtag flags are 1:1 with CP. The engine, storage, packaging, permission model, per-world config, blacklist, database migration, TNT/creeper attribution, API contract, and third-party integration ecosystem all diverge.

---

## 1. Platform & runtime

| Dimension | CoreProtect (23.x / 24 Patreon) | VonixGuardian (1.1.5) |
|---|---|---|
| Target platform | Bukkit / Spigot / Paper (+ Fabric build + Hytale build) | Fabric, Forge, NeoForge — vanilla MC server |
| MC versions | Whatever the Paper/Bukkit build targets (single track) | 1.18.2, 1.19.2, 1.20.1, 1.21.1 (8 jars in matrix) |
| Loader coverage matrix | N/A (Bukkit) | Fabric×4, Forge×3 (18.2/19.2/20.1), NeoForge×1 (21.1) |
| Language | Java | Java |
| License | Artistic-2.0 | MIT |
| Source model | Closed-source binaries (public docs); paid Patreon builds | Fully open source, GitHub public repo |
| Distribution | SpigotMC / bukkit.org / patreon | GitHub Releases + planned CurseForge/Modrinth |

---

## 2. Command surface

**Root command.** CP uses `/co` (+ `/core`, `/coreprotect`). VG uses `/vg` (primary) with `/co` and `/guardian` as first-class aliases resolved into the same Brigadier tree — CP muscle memory works verbatim on VG.

**Subcommand parity (1:1 as of VG 1.1.0):**

| Subcommand | CP | VG | Notes |
|---|---|---|---|
| `help` | ✅ | ✅ | |
| `inspect` (`i`) | ✅ | ✅ | Toggle; left/right-click block for history |
| `lookup` (`l`) | ✅ | ✅ | Same paginator `<page>` / `<page>:<perPage>` |
| `rollback` (`rb`) | ✅ | ✅ | Default `r:10` when a positioned caller omits radius (matches CP) |
| `restore` (`rs`) | ✅ | ✅ | |
| `purge` | ✅ | ✅ | CP: console ≥ 24h, in-game ≥ 30d. VG: same defaults, enforced in `PurgeEngine` |
| `reload` | ✅ | ✅ | CP fully reloads config; **VG 1.0.0 reload is currently a stub** — most keys are restart-required until a later release lands the handler. This is a real gap. |
| `status` | ✅ | ✅ | Both show version + queue state |
| `consumer` (`pause`/`resume`) | ✅ | ✅ | VG adds `toggle` |
| `migrate-db` | ✅ (**Patreon-only 23.0+**) | ❌ | Not implemented on VG. See §5 |
| `near` | ✅ (`r:5` shortcut) | ✅ (`r:5 t:1h`) | VG defaults to 1h window as well; CP has no time default on `near` |
| `undo` | ✅ | ✅ | Reverses your own last rollback/restore |
| `teleport` | ✅ | ❌ | CP has `/co teleport` (perm gate exists); VG does not |
| `give` | ✅ (default deny) | ❌ | CP restore-give helper; VG does not implement |

**Filter tokens: 1:1 parity.**

`u:` `t:` `r:` `a:` `i:` `e:` — every token, every combining form. VG adds `w:<world>` as a first-class filter (CP folds worlds into `r:#world_<key>`; VG accepts both).

**User actor sentinels: 1:1 parity.** `u:#fire`, `u:#tnt`, `u:#creeper`, `u:#explosion` — all supported.

**Time syntax.** CP supports combined+decimal (`t:2w,5d,7h`, `t:2.5h`) and **ranges** (`t:1h-2h` for "between one and two hours ago"). VG accepts combined durations (`t:1d12h`) but does **not** implement CP's decimal (`t:2.5h`) or range (`t:1h-2h`) syntax — small parity gap.

**Hashtag flags:**

| Flag | CP | VG |
|---|---|---|
| `#preview` | ✅ | ✅ |
| `#count` | ✅ | ✅ |
| `#verbose` | ✅ | ✅ (VG's `#verbose` also dumps `source_tag` + `actor_uuid` — modded attribution) |
| `#silent` | ✅ | ✅ |
| `#optimize` (MySQL only) | ✅ | ✅ (parsed as of 1.1.0; SQLite/PG paths are no-op) |
| `#worldedit` / `#we` on `r:` | ✅ (WE selection as radius) | ⚠ Parsed as a tab-completion suggestion but **no WorldEdit selection integration ships** (there is no WorldEdit-for-modded to bridge to on Forge/Fabric out of the box). Falls back to unbounded scope. |

**Action tokens:** identical set. `a:block`, `a:+block`, `a:-block`, `a:chat`, `a:click`, `a:command`, `a:container`, `a:+container`, `a:-container`, `a:inventory`, `a:+inventory`, `a:-inventory`, `a:item`, `a:+item`, `a:-item`, `a:kill`, `a:session`, `a:+session` (alias `a:login`), `a:-session` (alias `a:logout`), `a:sign`, `a:username`. VG also exposes umbrella tokens `a:block`, `a:container`, `a:item`, `a:entity`, `a:player`, `a:world`.

**Radius shortcuts:** `r:#global`, `r:#world_<key>`, `r:#worldedit`/`r:#we`, `r:#nether`, `r:#overworld`, `r:#end` — all identical.

---

## 3. Recorded action taxonomy

CP audits ~20 action categories through its Bukkit event surface. VG audits **39 action types** (`ActionType.java`), a superset driven by the modded event surface (Forge `LivingDestroyBlockEvent`, `BlockEvent.FluidPlaceBlockEvent`, `FillBucketEvent`, `ExplosionEvent.Detonate`, `PistonEvent.Pre`, hopper events, hanging events, `EntityJoinLevelEvent`, portal + structure events, etc).

Diff:

**Both cover:** block place/break, container in/out, item drop/pickup, entity kill, chat, commands, signs, sessions (join/quit), username changes, click, inventory in/out, explosions, burn/ignite/fade/form/spread, dispense, piston extend/retract, bucket empty/fill, leaves decay.

**VG-only / modded-specific:**

| Action | Category | Why VG has it |
|---|---|---|
| `ENTITY_CHANGE_BLOCK` | BLOCK | Modded mobs (dragons, ravagers, Create contraptions) change blocks by AI/physics. |
| `HOPPER_PUSH` / `HOPPER_PULL` | CONTAINER | Automation-heavy modpacks want per-transaction attribution. |
| `ITEM_CRAFT` | ITEM | JEI-driven grief traces (crafted a wither skull → find who). |
| `ENTITY_SPAWN` | ENTITY | With `sourceTag = "spawn:join"` filter to exclude chunk-load reanimation flood (v1.0.4 fix). |
| `ENTITY_INTERACT` | ENTITY | Mount/dismount, tame, breed, ownership transfer. |
| `HANGING_PLACE` / `HANGING_BREAK` | ENTITY | Item frames, paintings, GlowFrames, etc. |
| `STRUCTURE_GROW` | WORLD | Sapling → tree, mushroom, etc. Rollback treats as lossy (removes result, doesn't reseed). |
| `PORTAL_CREATE` | WORLD | Nether/end portal generation. |
| `CHUNK_POPULATE` | WORLD | Refused for rollback (would require worldgen replay). |

**CP-only / partial VG coverage:**

- **Sign edits via mixin** — CP captures sign-edit events cleanly; VG 1.0.4 flags `SIGN edits via mixin` as a P0 gap for the 1.0.5 mixin wave. Text is captured on save, but front/back and dye-color signs from 1.20+ need mixin work.
- **Fabric BLOCK_PLACE parity** — VG 1.0.4 P0 list still shows Fabric block-place doesn't fully match Forge coverage; needs mixin.
- **Deeper `ENTITY_CHANGE_BLOCK` beyond `LivingDestroyBlockEvent`** — Fluid/piston/state changes CP catches via Bukkit are still queued behind the 1.0.5 mixin wave.

---

## 4. Modded-attribution feature (VG-only)

This is the differentiating feature and does not exist in CP:

- **Universal attribution chain.** `TamableAnimal → OwnableEntity → Projectile shooter → passenger → nearest recent interactor` — VG walks all of these to attribute a mob-driven change back to the responsible player when possible. Row stores both a `#sentinel` (`#mob:iceandfire:fire_dragon`) and `actor_uuid` for the rider.
- **`#sentinel` tokens for non-player actors** (`#mob:<key>`, `#natural`, `#natural:raid`, `#tnt`, etc.) — VG-exclusive vocabulary.
- **`docs/MODDED-ATTRIBUTION.md`** documents Dragon Mounts / Create / wither cases end to end.
- Row schema carries `source_tag` and `actor_uuid` in parallel — CP has neither in its schema.

CP's `EntityChangeBlockListener` in Bukkit has a **hardcoded** ~11-class vanilla-mob whitelist (Enderman, EnderDragon, Wither, Ravager, Silverfish, Turtle, Fox, Zombie, FallingBlock, WindCharge, BreezeWindCharge). VG 1.1.5 **ports that exact list** as `VanillaGrieferSet.DEFAULT_ALLOWLIST` and stacks an admin-configurable `entityChangeAllowlist` on top. That's the in-flight work the other agent is landing right now (see §11).

---

## 5. Storage backends

| Backend | CP | VG |
|---|---|---|
| SQLite | ✅ (default) | ✅ (default; JIJ'd sqlite-jdbc, JNI symbol path preserved) |
| MySQL | ✅ | ✅ (JIJ'd `mysql-connector-j` 8.4.0 as of 1.1.1) |
| MariaDB | ✅ (via MySQL driver) | ✅ (same driver; VG 1.1.2 fixed `CREATE INDEX IF NOT EXISTS` MariaDB-vs-MySQL dialect drift) |
| PostgreSQL | ❌ | ✅ (JIJ'd `postgresql` 42.7.4) |
| DB migration tool | ✅ **`/co migrate-db`** — Patreon-only, SQLite↔MySQL | ❌ Not implemented. Real gap. |
| Auto-purge daemon | ✅ **Patreon-only** — `auto-purge: 180d`, `auto-purge-time: 03:30`, background chunked purger | ❌ Not implemented. Real gap. Operator must cron `/vg purge`. |

Purge safety floors: identical (`console ≥ 24h`, `in-game ≥ 30d`). VG enforces them source-side inside `PurgeEngine` (v1.1.0 CRITICAL fix); CP enforces at command layer.

---

## 6. Configuration model

| Feature | CP | VG |
|---|---|---|
| Config format | YAML (`config.yml`) | **JSON** (`config/vonixguardian/config.json`) |
| Per-world config overrides | ✅ (`world_the_end.yml`, `world_nether.yml` shadow the root) | ❌ Not implemented. **Real gap.** VG has `worldBlacklist` (drop-all-events for the world) but no per-world knob overrides. |
| Category toggles | ✅ | ✅ (`logBlocks`, `logContainers`, `logItems`, `logEntities`, `logExplosions`, `logChat`, `logCommands`, `logSessions`, `logSigns`, `logInteractions`, `logWorldEvents`) |
| Blacklist file | ✅ (`blacklist.txt`, granular: `user`, `id@user`, `block`, `entity`, `item@container`) | ⚠ Partial — `worldBlacklist`, `blockBlacklist`, `sourceBlacklist` in JSON. **No user blacklist. No `id@user` composite. No `entity` blacklist. Exact-match only (no globs).** Real gap. |
| Live reload | ✅ (`/co reload`) | ⚠ Command exists but **handler is a stub in 1.0.0**; treat every field as restart-required. |
| IP hashing / privacy | ✅ (implicit) | ✅ (`privacy.hashIps`, `privacy.salt`, HMAC-style, ships with placeholder salt + validator WARN) |
| Config validation | soft (per-key defaulting) | fail-fast, one exception listing every problem |

---

## 7. Permissions

| Feature | CP | VG |
|---|---|---|
| Node prefix | `coreprotect.*` | `vonixguardian.*` |
| Base command nodes | 11 (`inspect`, `lookup`, `rollback`, `restore`, `teleport`, `help`, `purge`, `reload`, `status`, `consumer`, `give`, `networking`) | 10 (matches minus `teleport`, `give`, `networking`) |
| Command-handler nodes | 3 (`coreprotect.co`, `.core`, `.coreprotect`) | 1 (`vonixguardian.command.use`) — root gate |
| Child permissions | ✅ 11 lookup child perms (`lookup.block`, `lookup.chat`, `lookup.click`, `lookup.command`, `lookup.container`, `lookup.inventory`, `lookup.item`, `lookup.kill`, `lookup.near`, `lookup.session`, `lookup.sign`, `lookup.username`) — negative perms for granular lookup gating | ❌ Not implemented. Real gap — VG has one `command.lookup` node, no per-category negative perms |
| Networking API perm | `coreprotect.networking` — required to use the CP networking API | ❌ N/A — no networking API in VG |
| `bypass` node (suppresses logging for holder) | ✅ | ✅ `vonixguardian.command.bypass` |
| `viewothers` node | ❌ | ✅ `vonixguardian.command.viewothers` — VG scopes lookups to the caller unless this is granted |
| Backend | Bukkit permissions (Vault / LP / PEX) | LuckPerms via reflection (**soft dep**, cached tri-state); op-level fallback with configurable `defaultOpLevel` (default `3`) |

---

## 8. API / integration

| Feature | CP | VG |
|---|---|---|
| Public Java API | ✅ **API version 12**, versioned, `maven.playpro.com` maven repo, stable across 24.x | ✅ v1.0.0 documented in `docs/API.md` — `Guardian` facade implementing `EventSubmitter`, `GuardianDao` for reads, soft-dep reflection pattern documented |
| Maven repo | ✅ published | ❌ Not yet published to a maven repo — GitHub Releases only |
| Custom-log entries from other plugins/mods | ✅ | ✅ (via `EventSubmitter.submit*`) |
| Networking API | ✅ (`coreprotect.networking` perm) — remote lookups | ❌ Not implemented |
| Third-party ecosystem | ✅ ~15+ documented integrations: WorldEdit, CoreProtect-Anti-Xray, CoreProtect TNT, Time-Lapse (CPTL), LightUp, Lumen, FRTrustSystem, Movecraft-CoreProtect, M0-CoreCord (Discord bridge), XRayHunter, ShadowTrace, WildInspect, ExplosionProtector, Axiom Paper Plugin, BlocksHub, SpitSTIK, DesirePaths, Watson, CP Lookup Web UI | ❌ Zero third-party integrations — VG is new; no ecosystem yet |

---

## 9. Performance & queue engineering

| Aspect | CP | VG |
|---|---|---|
| Async writer | ✅ (consumer queue, `pause`/`resume`) | ✅ `BatchedAsyncWriteQueue` — bounded ring buffer, time-budgeted `poll()` (Kafka `linger.ms` pattern, fixed in v1.0.4) |
| Backpressure policy | drop + warn | **drop + rate-limited WARN, tick never blocked**. `maxSize` default 50 000; sized against event rate. `/vg status` exposes depth |
| Batching | prepared-statement batch | prepared statement batch, `batchSize` configurable |
| Query indices | `(user, time)`, `(x,z,y,time)` | `(world, x, z, y, time)` + user + time-desc indices |
| Producer-side coalescing | ❌ (CP relies on Bukkit `EntityChangeBlockEvent` firing on state change only) | ✅ **`EntityBlockChangeCoalescer`** (v1.1.4) — dedup by `(actor, world, x, y, z)` within a 500ms window because Forge `LivingDestroyBlockEvent` is a *prospective query* event that fires on every mob tick per block-in-collision-box (200k/sec per HTTYD dragon). CP's Bukkit source doesn't need this because the Bukkit event is genuine-change-only. |
| Chat capture priority | (Bukkit `HIGHEST` monitor) | ✅ Forge/NeoForge listener registered `EventPriority.HIGHEST` + `receiveCanceled=true` (v1.0.4) — logs cancelled chat too, matches CP contract |

---

## 10. Deployment & failure modes VG had to solve that CP never sees

These are all consequences of running on Forge/NeoForge instead of Bukkit — CP's target has none of them:

- **JNI relocation trap on shaded SQLite** (VG 1.0.0 fix — JarInJar the driver instead of shading, so JNI symbol path is preserved). Not relevant to CP: Bukkit doesn't shade SQLite the same way.
- **`module-info.class` JPMS leak breaking Sinytra Connector boot** (VG 1.0.1 hotfix). CP-invisible.
- **`MinecraftServer.getServerDirectory()` return-type drift** from Connector remap (VG 1.0.1 hotfix — reflective resolve). CP-invisible.
- **`EntityType.getType()` SRG-vs-Mojmap resolution flood on deep-modded packs** (VG 1.0.2 — `MethodHandles.publicLookup()` chain + rate-limited WARN). CP-invisible.
- **NeoForge `RegisterCommandsEvent` firing on `Worker-Main-*` before `Guardian.boot()`** (VG 1.0.0 fix — deferred-and-replay dispatcher). CP registers commands via `PluginManager`, no timing race.
- **JDBC drivers not on classpath by default** (VG 1.1.1 — JIJ `mysql-connector-j` + `postgresql`). Paper ships them; VG had to.
- **`CREATE INDEX IF NOT EXISTS` MySQL-vs-MariaDB dialect drift** (VG 1.1.2 — split DDL, swallow MySQL 1061). CP-invisible.
- **`Sinytra Connector Commands.literal()` remap** (VG 1.0.1 known issue — command tree fails to register on Connector, engine still functions). No CP equivalent.

---

## 11. In-flight on `v1.1.5-entity-filter` (the branch the other agent is working)

Uncommitted changes on the current branch as of 2026-07-01 15:xx UTC:

- **New file `core/src/main/java/network/vonix/guardian/core/filter/VanillaGrieferSet.java`** — ports CP's hardcoded `EntityChangeBlockListener` whitelist verbatim: `minecraft:{enderman, ender_dragon, wither, ravager, silverfish, turtle, fox, zombie, falling_block, wind_charge, breeze_wind_charge}`.
- **`GuardianConfig.Actions`** grows two fields: `entityChangeAllowlist: List<String>` (admin opt-in for modded mob keys like `iceandfire:fire_dragon`) and `entityChangeLogAllEntities: boolean` (escape hatch — restores pre-1.1.5 flood behavior).
- **`ConfigLoader.migrateForwardCompat`** now backfills `entityChangeAllowlist = []` on pre-1.1.5 configs and logs the fill-in.
- **`ForgeEvents.onLivingDestroyBlock`** in `mc-1.18.2/forge`, `mc-1.19.2/forge`, `mc-1.20.1/forge`, `mc-1.21.1/neoforge` — all four cells now gate the listener on `VanillaGrieferSet.shouldRecord(entityKey, allowlist, logAll)` **before** any attribution work. Non-vanilla entities exit the handler without touching the queue or the attribution resolver.
- `mod_version` bumped 1.1.4 → 1.1.5 in `gradle.properties`.

**Net effect vs CP:** VG will now match CP's whitelist semantics *exactly* for the vanilla case, while additionally exposing an admin allowlist for modded packs that want to record specific modded griefers (something CP cannot express because it doesn't run on Forge).

**Note vs the 1.1.4 coalescer:** 1.1.4's `EntityBlockChangeCoalescer` (time+coord dedup) and 1.1.5's `VanillaGrieferSet` (entity-class whitelist) are complementary — the whitelist eliminates modded floods at the source (the CP approach), the coalescer clamps the surviving vanilla-mob load. Both stay in.

---

## 12. Summary: where VG is behind, at, or ahead of CP

**Ahead of CP** (things CP simply doesn't do):
- Forge / NeoForge / Fabric coverage across 4 MC versions
- PostgreSQL backend
- Universal modded attribution chain (`TamableAnimal → OwnableEntity → Projectile → passenger`)
- `#sentinel` actor vocabulary + `actor_uuid` beside `source_tag` in the schema
- Producer-side `EntityBlockChangeCoalescer` for the modded prospective-event flood
- 39-entry `ActionType` taxonomy (hopper push/pull, structure grow, portal create, hanging place/break, entity interact, chunk populate)
- Admin-configurable modded-entity allowlist (v1.1.5)
- `viewothers` permission node
- Fail-fast config validator

**At parity with CP** (1:1):
- `/vg` command tree + `/co`, `/guardian` aliases → identical subcommand set (minus `teleport`, `give`, `migrate-db`)
- Filter mini-language (`u:` `t:` `r:` `a:` `i:` `e:` `w:`)
- Hashtag flags (`#preview`, `#count`, `#verbose`, `#silent`, `#optimize`)
- Actor sentinels (`u:#tnt` `u:#creeper` `u:#fire` `u:#explosion`)
- Purge safety floors (24h console / 30d in-game)
- Async writer + `consumer pause/resume`
- LuckPerms integration (via soft-dep reflection instead of Vault)
- SQLite + MySQL + MariaDB backends
- Vanilla mob-griefing whitelist (Enderman / Ender Dragon / Wither / Ravager / Silverfish / Turtle / Fox / Zombie / FallingBlock / WindCharge / BreezeWindCharge — after the 1.1.5 landing)
- `+session`/`-session` action aliases (`a:login`, `a:logout`)

**Behind CP** (real gaps to close):
1. **`/vg reload` is a stub** — 1.0.0 handler doesn't re-read the file; treat every field as restart-required. CP does full live reload.
2. **No database migration tool** — CP has `/co migrate-db` (Patreon-only, SQLite↔MySQL). VG has none.
3. **No auto-purge daemon** — CP has `auto-purge: 180d` + `auto-purge-time: 03:30` (Patreon-only). VG requires cron.
4. **No per-world config overrides** — CP allows `world_the_nether.yml` to shadow root `config.yml` per-world. VG only offers `worldBlacklist`.
5. **No user/entity blacklist file** — CP has a rich `blacklist.txt` (`Notch`, `#tnt`, `/help`, `minecraft:stone`, `minecraft:creeper`, `minecraft:shears@#dispenser`). VG only has `worldBlacklist`, `blockBlacklist`, `sourceBlacklist` (exact-match, no globs, no user/entity variants).
6. **No child permissions** — CP has 11 `coreprotect.lookup.<category>` negative-perm gates. VG has one `command.lookup` node.
7. **No `#worldedit` selection integration** — parses the token but no live WE-selection bridge (no WE on modded Forge/Fabric to bridge to).
8. **No decimal/range time syntax** — CP accepts `t:2.5h` and `t:1h-2h`. VG accepts combined (`t:1d12h`) only.
9. **Sign edits via mixin** — VG 1.0.4 P0 list still flagging front/back + dye-color signs from 1.20+ for the 1.0.5 mixin wave.
10. **Fabric `BLOCK_PLACE` parity** — VG 1.0.4 P0 list: full Fabric block-place coverage still queued behind mixin wave.
11. **No published maven repo** — third parties integrate via GitHub Release jars, no coordinate for `implementation` yet.
12. **No third-party integration ecosystem** — CP has ~15 documented plugin integrations, VG has zero.
13. **No `/co teleport` or `/co give`** — CP has both (permission-gated), VG has neither.

**Deliberate not-implementing** (design choices, not gaps):
- Bukkit / Paper build (CP owns that space; VG's reason to exist is the loaders CP doesn't cover).
- Networking API (`coreprotect.networking`) — out of scope for v1.x.

---

## 13. Interop matrix (which server should run which?)

| Server type | Recommended tool | Why |
|---|---|---|
| Paper / Spigot / Purpur (vanilla API) | **CoreProtect** | Native platform, larger ecosystem, mature |
| Fabric | **VonixGuardian** | CP-Fabric exists but VG covers 1.18.2→1.21.1 uniformly + modded attribution |
| Forge (1.18.2 / 1.19.2 / 1.20.1) | **VonixGuardian** | CP does not target Forge |
| NeoForge 1.21.1 | **VonixGuardian** | CP does not target NeoForge |
| Sinytra Connector (Forge+Fabric hybrid) | **VonixGuardian** (with the caveat that command tree registration is blocked by Connector's `Commands.literal()` remap until VG 1.0.2's mixin wave — engine still functions) | CP still targets Bukkit; VG has explicit Connector hardening |
| Hytale | **CoreProtect** (has a dedicated Hytale build) | VG does not target Hytale |
