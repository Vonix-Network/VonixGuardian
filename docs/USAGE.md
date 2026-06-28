# VonixGuardian — Usage Guide

Operator's guide to `/vg`. Modelled on CoreProtect's `/co` UX with modded-Minecraft extensions.

## TL;DR

```
/vg inspect              ← toggle inspector
/vg near                 ← what happened around me (radius 5)
/vg lookup u:Steve t:1h  ← what did Steve do in the last hour
/vg rollback u:Steve t:1h r:50  ← undo Steve's last hour, 50-block radius from you
/vg undo                 ← oops, revert that rollback
```

## Filters

Everywhere a `<filters>` argument appears, the same parser is used. Filters compose with AND.

| Filter | Examples | Meaning |
|---|---|---|
| `u:<users>`  | `u:Steve`, `u:Steve,Alex`, `u:#mob:zombie`, `u:#natural` | Actor (player names or `#sentinel` tokens for non-player) |
| `t:<time>`   | `t:1h`, `t:30m`, `t:1d12h`, `t:2w` | Time window back from now (suffixes: `s/m/h/d/w`) |
| `r:<radius>` | `r:50`, `r:10`, `r:global` | Radius (blocks) from the executing player's position. `global` = unbounded |
| `a:<action>` | `a:block`, `a:+block`, `a:-container`, `a:explosion,kill` | Action filter (see Action types below). `+` includes only; `-` excludes |
| `i:<incl>`   | `i:minecraft:diamond_ore`, `i:create:andesite_alloy` | Include only these target IDs (block, item, entity) |
| `e:<excl>`   | `e:minecraft:leaves`, `e:minecraft:air` | Exclude these target IDs |
| `w:<world>`  | `w:overworld`, `w:the_nether` | World filter |
| `#preview`   | — | Don't commit (preview a rollback in glass blocks) |
| `#count`     | — | Don't return rows; return aggregate counts |
| `#verbose`   | — | Include raw NBT in output |
| `#silent`    | — | Don't broadcast to other staff |

### Action types

`a:` accepts these tokens (case-insensitive):

```
block_break     block_place     block_burn      block_ignite
block_form      block_fade      block_spread    block_dispense
piston_extend   piston_retract  bucket_empty    bucket_fill
leaves_decay    sign_change     container_in    container_out
item_drop       item_pickup     item_craft      entity_kill
entity_spawn    entity_change_block             explosion
explosion_block player_join     player_quit     player_chat
player_command  player_kick     player_death    player_respawn
```

You can also use the umbrellas: `block` (all block_*), `container`, `item`, `entity`, `player`, `world`.

### Sentinel tokens (modded griefing attribution)

When a non-player griefs, the `actor` column gets a `#sentinel` token rather than a fake username:

```
#mob:minecraft:zombie       ← natural zombie
#mob:minecraft:wither       ← wither (boss)
#mob:create:contraption     ← Create contraption
#natural                    ← fire spread, lava flow, leaf decay
#natural:raid               ← raider in active raid
#tnt                        ← unprimed TNT (no attacker hooked)
```

If our attribution chain caught the responsible player (see "Universal attribution" below), the row stores BOTH the sentinel (in `source_tag`) AND the player UUID (in `actor_uuid`). Lookups can filter on either side.

## Common workflows

### "Someone griefed my base"

```
/vg inspect
```
Left-click a destroyed block → see the rows for that exact position.
Right-click a chest → see container in/out at that position.

```
/vg near t:3d
```
What happened within 5 blocks of me in the last 3 days.

```
/vg lookup u:Alex t:3d a:block r:200
```
Did Alex break/place anything in the last 3 days within 200 blocks of me.

### "Roll back a known griefer"

```
/vg rollback u:Griefer t:24h r:global #preview
```
Place glass blocks where the rollback WOULD reverse — don't commit.

```
/vg rollback u:Griefer t:24h r:global
```
Commit the rollback.

```
/vg undo
```
Revert that rollback if you change your mind. (`undo` only undoes YOUR last rollback/restore.)

### "Find when a chest was last opened"

```
/vg lookup a:container i:#chest r:5
```
Container access within 5 blocks (stand on / next to the chest).

### "Catch a dragon mount griefer"

```
/vg lookup a:block_break u:#mob:dragonmounts:fire_dragon t:1h
```
Dragon broke blocks in the last hour. Then to find the rider:

```
/vg lookup #verbose a:block_break u:#mob:dragonmounts:fire_dragon t:1h
```
The `#verbose` flag dumps the `source_tag` AND `actor_uuid` columns. If our [universal attribution chain](https://github.com/Vonix-Network/VonixGuardian/blob/main/SHARED-CONTRACTS.md#attribution) caught the rider, you'll see the player UUID alongside `#mob:dragonmounts:fire_dragon`.

### "Mass purge old data"

```
/vg purge t:90d
```
Delete rows older than 90 days. Safety: minimum age is enforced by `purge.minAgeSecondsInGame` in config (default 30d); the console can purge down to `purge.minAgeSecondsConsole` (default 1d).

## Rollback semantics

A rollback is **transactional and reversible**:

1. Query the matching rows.
2. Compute the inverse for each row (block_break → block_place of the original BlockState; container_in → container_out; etc).
3. Apply inverses in REVERSE chronological order (latest first).
4. Stamp each undone row with a `rollback_batch_id` so it's excluded from future rollbacks but visible in lookups.
5. Insert a single row into `vg_rollback_batches` recording the operator + filter + time + affected count.

`/vg restore` undoes a rollback batch by replaying the FORWARD inverse. `/vg undo` is shorthand for "restore my most recent batch".

Rows logged DURING a rollback are themselves marked with the batch ID so they don't get caught in a redo loop.

## Status and health

```
/vg status
```

Returns:
- DB type + connection pool stats (active / idle / waiting)
- Queue depth + drop counter (if queue.max exceeded → drops; bump `queue.maxSize` if non-zero)
- Last write latency (p50, p99)
- Schema version
- Mod version + MC version + loader

```
/vg reload
```

Reloads `config/vonixguardian/config.json` from disk. Some changes (`database.type`, `queue.maxSize`) require restart; those are reported as `requires restart` and left at their loaded value.

## LuckPerms integration

If LuckPerms is present (auto-detected via reflection — no hard dep), `/vg` permission gates use LP nodes:

```
vonixguardian.command.inspect
vonixguardian.command.lookup
vonixguardian.command.rollback
vonixguardian.command.restore
vonixguardian.command.purge
vonixguardian.command.near
vonixguardian.command.undo
vonixguardian.command.status
vonixguardian.command.reload
vonixguardian.command.bypass      ← actions by holder are NOT logged
vonixguardian.command.viewothers  ← lookup/rollback can target other users without explicit `u:`
```

Without LP, permission level 3 (`op`) is required for all commands.

## Verified-live boot matrix (v1.0.0)

All 8 jars boot cleanly on a real server with SQLite schema initialised.
Three jars (NeoForge 1.21.1, Forge 1.20.1, Fabric 1.21.1) were re-verified against the
final v1.0.0 release artifacts downloaded from GitHub on 2026-06-28 — all green, SHA-256
matches `SHA256SUMS` published with the release.

| Loader | MC | Library packaging | Boot time | Status |
|---|---|---|---|---|
| NeoForge | 1.21.1 | JarInJar (sqlite-jdbc + slf4j-api nested) | 1.4s | ✅ |
| Fabric   | 1.21.1 | shade-unrelocated sqlite-jdbc, slf4j excluded | 0.9s | ✅ |
| Forge    | 1.20.1 | FG6 jarJar (sqlite-jdbc + slf4j-api nested) | 3.4s | ✅ |
| Fabric   | 1.20.1 | shade-unrelocated, slf4j excluded | ≤2s | ✅ |
| Forge    | 1.19.2 | FG6 jarJar | 8.3s | ✅ |
| Fabric   | 1.19.2 | shade-unrelocated | ≤2s | ✅ |
| Forge    | 1.18.2 | FG6 jarJar | 44s* | ✅ |
| Fabric   | 1.18.2 | shade-unrelocated | ≤2s | ✅ |

*First-boot world generation on 1.18.2 Forge takes longer; subsequent boots are sub-3-second.

Every jar verified to:
- Load with mod manifest visible in log (`VonixGuardian (<loader> <ver>) loading.`)
- Boot Guardian engine (`Booting VonixGuardian (db=sqlite, theme=aqua, queue.max=50000)`)
- Initialise SQLite schema (file at expected size with all 8 tables: `vg_users`, `vg_worlds`, `vg_actions` + 4 indices, `vg_rollback_batches`, `vg_rollback_batch_actions`, `vg_schema_version`)
- Register `/vg` command tree (deferred from `RegisterCommandsEvent` / `CommandRegistrationCallback`)
- Complete `bootstrap complete.` before MC `Done (Xs)!`
- Survive zero JPMS `ResolutionException`, zero `UnsatisfiedLinkError`, zero shaded-library leaks (outer-jar audit: `org/slf4j/*` = 0 on every jar)

## Troubleshooting

### "Server boots but I don't see the mod load line"

The `mods.toml` schema differs between Forge (`mandatory = true`) and NeoForge (`type = "required"`). If you're side-loading a custom build into an older Forge server and the mod silently doesn't load (no manifest line, no error), check `META-INF/mods.toml` inside the jar. v0.1.0+ ships the correct schema per loader.

### "ResolutionException: Module vonixguardian contains package org.sqlite"

You're running a pre-v0.1.0 (or pre-fix) NeoForge/Forge jar. v0.1.0 ships sqlite-jdbc as nested JarInJar so it cannot dup-package. Upgrade.

### "UnsatisfiedLinkError on first DB op"

Shaded-relocated sqlite-jdbc has broken JNI symbols. v0.1.0+ ships unrelocated (Fabric) or nested via JarInJar (NF/Forge) — both preserve the canonical `org.sqlite.core.NativeDB` class path that the `.so`/`.dll`/`.dylib` needs. Upgrade.

### "/vg command not recognised after first boot"

`RegisterCommandsEvent` fires before `Guardian.boot()` on a worker thread. v0.1.0+ uses a deferred-and-replay pattern — the dispatcher is captured at `RegisterCommandsEvent` time and the command tree is registered when Guardian boots a few hundred ms later. If you see this on v0.1.0+, file an issue with the loader's lifecycle log.

### "Queue dropped N events"

`queue.maxSize` (default 50000) exceeded. Either increase the limit in config, or your storage backend is too slow — switch to MySQL/PostgreSQL with HikariCP if you're sustaining > 10k events/s.

## See also

- [README.md](../README.md) — feature surface and build instructions
- [SHARED-CONTRACTS.md](../SHARED-CONTRACTS.md) — internal type contracts (developers)
- [CHANGELOG.md](../CHANGELOG.md) — release history
