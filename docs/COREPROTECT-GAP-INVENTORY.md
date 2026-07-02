# CoreProtect 1:1 Gap Inventory (v24.0)

Source: `/root/staging/coreprotect-ref/CoreProtect` (v24.0).
Target: VonixGuardian on integration/v1.2.0 (commit b88118b).
Method: exhaustive source scan, not docs.

## Legend
✅ done · 🟡 partial · 🟥 unwired-but-declared · ❌ missing · ➖ intentionally-different (design)

---

## 1. Command surface — COMPLETE ✅

Every `/co` subcommand from `CommandHandler.java` mapped:

| CP | VG | Status |
|---|---|---|
| /co help | /vg help | ✅ |
| /co inspect (i, inspector) | /vg inspect, /vg i | ✅ |
| /co lookup (l, page) | /vg lookup, /vg l | ✅ pagination + child perms |
| /co rollback (rb, ro) | /vg rollback, /vg rb | ✅ real inverse plan |
| /co restore (rs, re) | /vg restore, /vg rs | ✅ |
| /co purge | /vg purge | ✅ safety floors + world/block filters |
| /co reload | /vg reload | ✅ hot-swap safe subsections |
| /co status (stats, version) | /vg status | ✅ CP-parity multi-section renderer |
| /co consumer | /vg consumer pause/resume/toggle | ✅ +toggle |
| /co migrate-db | /vg migrate-db | ✅ console-only + CONFIRM |
| /co near | /vg near | ✅ r:5 t:1h |
| /co undo | /vg undo | ✅ real world revert |
| /co teleport (tp) | /vg teleport, /vg tp | ✅ CP source-parity |
| /co give | /vg give | ✅ CP source-parity |
| /co apply | ⚠ CP-internal preview commit — folded into `#preview` semantics |
| /co cancel | ⚠ CP-internal preview cancel — folded into `#preview` semantics |

**Missing:** none. Apply/cancel are CP internals for the preview commit/rollback confirm cycle; VG's `#preview` model is different by design (previews return counts, actual mutation is a separate re-issue).

## 2. Permission nodes — COMPLETE ✅

All CP `plugin.yml` nodes covered:

| CP node | VG node | Notes |
|---|---|---|
| coreprotect.co | vonixguardian.command.use | root gate |
| coreprotect.rollback | vonixguardian.command.rollback | ✅ |
| coreprotect.restore | vonixguardian.command.restore | ✅ |
| coreprotect.inspect | vonixguardian.command.inspect | ✅ |
| coreprotect.help | (root only) | ✅ |
| coreprotect.purge | vonixguardian.command.purge | ✅ |
| coreprotect.lookup | vonixguardian.command.lookup | ✅ |
| coreprotect.lookup.near | vonixguardian.command.near | ✅ |
| coreprotect.lookup.chat/command/session/username/block/sign/click/container/inventory/item/kill | vonixguardian.lookup.chat/command/session/username/block/sign/container/item/kill + item + session in enum | ✅ (child perms enforced by LookupPermissionFilter) |
| coreprotect.teleport | vonixguardian.command.teleport | ✅ |
| coreprotect.reload | vonixguardian.command.reload | ✅ |
| coreprotect.status | vonixguardian.command.status | ✅ |
| coreprotect.consumer | vonixguardian.command.consumer | ✅ |
| coreprotect.networking | — | ➖ CP networking API is Bukkit-plugin-channel; VG does not target Bukkit |
| coreprotect.give | vonixguardian.command.give | ✅ |

## 3. Filter tokens (u:/t:/r:/a:/i:/e:) — COMPLETE ✅

VG parses every CP filter token verified in `QueryParser.java`. Decimal time (`t:2.50h`) and range (`t:1h-2h`) landed in W3.

## 4. Hashtag flags

| CP | VG | Status |
|---|---|---|
| #preview | ✅ | wired |
| #count | ✅ | wired |
| #verbose | ✅ | wired |
| #silent | ✅ | wired |
| #optimize | 🟡 | parsed, but no-op in storage layer (MySQL OPTIMIZE TABLE) |

**Fix path:** Wire `#optimize` into MySQL purge path to run `OPTIMIZE TABLE vg_actions` after successful purge.

## 5. Action-family taxonomy

VG has 39 ActionType constants — SUPERSET of CP's ~26. Every CP action mapped. VG-only expansions kept (STRUCTURE_GROW, PORTAL_CREATE, CHUNK_POPULATE, ENTITY_INTERACT, HOPPER_PUSH/PULL, ITEM_CRAFT, ENTITY_CHANGE_BLOCK, HANGING_PLACE/BREAK).

## 6. Event listener coverage — CP has 60 listener classes

CP listener inventory (from `net/coreprotect/listener/**`):

### Block listeners (16)
`BlockBreak`, `BlockBurn`, `BlockDispense`, `BlockExplode`, `BlockFade`, `BlockFertilize`, `BlockForm`, `BlockFromTo` (water/lava flow), `BlockIgnite`, `BlockPiston`, `BlockPlace`, `BlockSpread`, `CampfireStart`, `TNTPrime` (+ Util).

### Entity listeners (14)
`CreatureSpawn`, `EntityBlockForm`, `EntityChangeBlock`, `EntityDamageByBlock`, `EntityDamageByEntity`, `EntityDeath`, `EntityExplode`, `EntityInteract`, `EntityPickupItem`, `EntitySpawn`, `EntityTransform`, `HangingBreak`, `HangingBreakByEntity`, `HangingPlace`.

### Player listeners (22)
`ArmorStandManipulate`, `CraftItem`, `FoodLevelChange`, `HopperPull`, `HopperPush`, `InventoryChange`, `InventoryClick`, `PlayerBucketEmpty`, `PlayerBucketFill`, `PlayerChat`, `PlayerCommand`, `PlayerDeath`, `PlayerDropItem`, `PlayerInteract`, `PlayerInteractEntity`, `PlayerItemBreak`, `PlayerJoin`, `PlayerPickupArrow`, `PlayerQuit`, `PlayerTakeLecternBook`, `ProjectileLaunch`, `SignChange`, + 4 inspectors (BlockInspector, ContainerInspector, InteractionInspector, SignInspector).

### World listeners (4)
`ChunkPopulate`, `LeavesDecay`, `PortalCreate`, `StructureGrow`.

### VG current coverage per cell (submit* callsite count)

| Cell | submit* wires |
|---|---|
| mc-1.18.2/forge | 1 |
| mc-1.19.2/forge | 1 |
| mc-1.20.1/forge | 1 |
| mc-1.21.1/neoforge | 0 |
| mc-1.18.2/fabric | 0 |
| mc-1.19.2/fabric | 0 |
| mc-1.20.1/fabric | 0 |
| mc-1.21.1/fabric | 0 |

**Fix path:** All 8 cells need world-event mixin implementations. Forge/NeoForge get FireBlock/IceBlock/SpreadingSnowyDirtBlock/LeavesBlock/DispenserBlock/BucketItem mixins. Fabric gets BlockPlace/LivingEntity/Explosion/Piston/ContainerOpen/Close/BucketFill/Empty/ItemToss/ItemPickup/CraftItem/SignChange mixins. Infrastructure (`vg.mixins.json`) already exists.

## 7. Public Java API — CoreProtectAPI v12

CP API surface (from `CoreProtectAPI.java`):

| Method | VG | Notes |
|---|---|---|
| APIVersion() | 🟡 | GuardianAPI has pluginVersion but no int APIVersion |
| testAPI() | ❌ | missing |
| isEnabled() | ✅ | GuardianAPI.isEnabled |
| performLookup / performPartialLookup | ✅ | dao().query with filter+offset+limit |
| performRollback / performRestore | ➖ | NOT exposed in public API by design (docs/API.md:217) |
| performPurge | ✅ | purgeEngine().purge |
| blockLookup(Block, int) / (Block, LookupOptions) | 🟡 | InspectorLookup.atPos exists, needs typed API method |
| containerLookup, itemLookup, inventoryLookup, sessionLookup, usernameLookup, chatLookup, commandLookup, signLookup | ❌ | need per-family typed API methods returning typed results |
| queueLookup(Block) | ❌ | queue introspection not exposed |
| hasPlaced(user, Block, time, offset) | ❌ | missing |
| hasRemoved(user, Block, time, offset) | ❌ | missing |
| logChat / logCommand / logInteraction / logContainerTransaction / logPlacement / logRemoval | 🟡 | EventSubmitter exposes internal submit*; need public logging façade |
| parseResult(String[]) | ❌ | CP returns String[] rows; VG returns typed Action — bridge needed |

**Fix path:** Add typed result classes (`BlockResult`, `ContainerResult`, `ItemResult`, `InventoryResult`, `SessionResult`, `UsernameResult`, `MessageResult`, `SignResult`, `ParseResult`) as adapters on `Action`. Add `hasPlaced/hasRemoved/queueLookup/APIVersion/testAPI/log*` methods to `GuardianAPI`.

## 8. Storage engine — SUPERSET ✅

CP: SQLite + MySQL/MariaDB.  
VG: SQLite + MySQL/MariaDB + **PostgreSQL** (extra).

## 9. Config surface

CP has ~55 config keys (`Config.java`). VG has ~40. Missing hot-swap-safe VG equivalents for:

- CP `LANGUAGE` — VG hardcoded en_us  
- CP `HOPPER_FILTER_META` — meta-aware hopper dedup  
- CP `DUPLICATE_SUPPRESSION` — global dupe collapse  
- CP `EXCLUDE_TNT` — tnt actor exclusion (VG has sourceBlacklist)  
- CP `LOG_CANCELLED_CHAT` — log cancelled chat events  
- CP `HOVER_EVENTS` — chat hover tooltips  
- CP `NATURAL_BREAK` — log natural block breaks separately  
- CP `SKIP_GENERIC_DATA` — skip block-data serialisation for common blocks  
- CP `ROLLBACK_ITEMS` / `ROLLBACK_ENTITIES` — separate toggles for rollback of items vs entities  
- CP `TREE_GROWTH`, `MUSHROOM_GROWTH`, `VINE_GROWTH`, `SCULK_SPREAD`, `PORTALS`, `WATER_FLOW`, `LAVA_FLOW`, `LIQUID_TRACKING`, `FIRE_EXTINGUISH`, `CAMPFIRE`, `NETWORK_DEBUG`, `WORLDEDIT` — mostly per-event toggles VG lumps into `logWorldEvents`  
- CP `CHECK_UPDATES`, `ERROR_REPORTING`, `DONATION_KEY`, `API_ENABLED`, `VERBOSE` — meta/telemetry

**Fix path:** Extend `GuardianConfig.Actions` with granular per-event toggles (13 additions), add `Config.language`, add hover-event toggle, add hopper meta flag.

## 10. i18n language bundles

CP ships **14** language files: en, de, es, fr, ja, ko, pl, ru, tr, tt, uk, vi, zh-cn, zh-tw.  
VG ships **1** (en_us).

**Fix path:** Port all 14 CP language YAML bundles into `core/src/main/resources/lang/*.properties`, wire language selection via `config.language`.

## 11. WorldEdit integration

CP: soft-dep, `r:#worldedit` reads real selection.  
VG: `r:#worldedit` parsed but no-op.

**Fix path:** Reflection-only WorldEdit selection bridge in `core/query/WorldEditRegionResolver.java`.

## 12. Third-party ecosystem

CP has ~20 documented integrations (LightUp, Lumen, M0-CoreCord, XRayHunter, Movecraft-CP, BlocksHub, etc.).  
VG: 0 shipped integrations. `docs/PLUGINS.md` guide exists.

**Fix path:** Not a functional gap — ecosystem grows post-1.0. Publishing config is ready.

## 13. Runtime target divergence (BY DESIGN)

CP is Bukkit/Spigot/Paper. VG is Forge/NeoForge/Fabric. This IS the reason VG exists.

VG-only strengths:
- Modded loaders (CP has none GA)
- PostgreSQL backend
- Universal attribution chain (owner → passenger → projectile shooter → LRU interactor)
- Producer-side coalescer (HTTYD dragon flood defense)
- VanillaGrieferSet whitelist (CP-parity ported char-for-char)
- Sign v24 schema (v3→v4 migration)
- PreLogEvent bridges on all 8 loaders
- Rolling JSON-Lines audit log
- Fail-fast config validator

---

## Blocker summary for "true 1:1"

Sorted by user-visible impact:

1. **World-event mixins on Forge/NeoForge** — burn/ignite/fade/form/spread/dispense/leaves_decay unwired
2. **Fabric event mixins** — block-place, mob-block-change, explosion, piston, container-open/close, bucket, item-drop/pickup/craft, sign-change unwired
3. **NeoForge 1.21.1 bucket mixin** — FillBucketEvent removed upstream
4. **CoreProtectAPI-parity public API** — typed result classes + hasPlaced/hasRemoved/queueLookup/log* facade
5. **13 additional per-event config toggles** — granular parity with CP's Config.java
6. **`#optimize` flag** — wire to MySQL OPTIMIZE TABLE
7. **WorldEdit selection bridge** — replace no-op with reflection
8. **i18n: 14 language bundles** — port from CP's `lang/*.yml`
