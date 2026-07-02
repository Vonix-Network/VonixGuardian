# v1.2.0 One-Big-Wave Plan

**Baseline:** `ac78644` (v1.1.8 tip)
**Target:** `v1.2.0` — production-ready CoreProtect parity
**Style:** ONE fan-out wave (WeedMeister standing preference).

## What subagents own (disjoint file trees, ZERO shared write surface)

Every subagent writes ONLY to files in their `FILES YOU OWN` list. Cross-cutting concerns (schema bumps, `EventGate` wiring) are pre-wired on main-thread BEFORE dispatch.

### Wave A — 15 parallel subagents

| ID | Task | Files owned |
|----|------|-------------|
| **W4-01** | Comparison doc refresh (v1.1.5 → v1.1.8 state) | `docs/comparison/00-summary.md`, `docs/comparison/01-commands.md`, `docs/comparison/02-actions.md`, `docs/comparison/03-storage.md`, `docs/comparison/04-config.md`, `docs/comparison/05-perms.md`, `docs/comparison/06-api.md`, `docs/comparison/07-modded.md`, `docs/comparison/08-attribution.md`, `docs/comparison/09-loader.md`, `docs/comparison/10-interop.md` — ONE agent writes all fragments |
| **W4-02** | Command wiring audit — trace every `/vg` subcommand from `Commands.literal` through handler to core → assert wired | `docs/COMMAND-AUDIT-1.2.0.md` (docs-only report; MAY file follow-up bugs, MUST NOT edit source) |
| **W4-03** | Fabric event wiring parity (BLOCK_BREAK using pre-existing `PlayerBlockBreakEvents`, LivingDamageEvents, item toss via Fabric API where possible — NO mixins yet) | `mc-1.18.2/fabric/…/FabricEvents.java`, `mc-1.19.2/fabric/…/FabricEvents.java`, `mc-1.20.1/fabric/…/FabricEvents.java`, `mc-1.21.1/fabric/…/FabricEvents.java` |
| **W4-04** | Fabric mixin infrastructure — `vg.mixins.json` per cell + Mixin plugin gradle wiring + shared `AbstractBlockPlaceMixin` in `mc-XXX/fabric/src/main/java/…/mixin/` | `mc-*/fabric/src/main/resources/vg.mixins.json` (4 files), `mc-*/fabric/build.gradle` (4 files), `mc-*/fabric/src/main/java/…/mixin/*.java` (new package) |
| **W4-05** | Forge/NeoForge mixin infrastructure — parallel to W4-04 for world-events + NeoForge 1.21.1 bucket | `mc-*/forge/src/main/resources/META-INF/vg.mixins.json` (3 files), `mc-1.21.1/neoforge/src/main/resources/META-INF/vg.mixins.json`, `mc-*/forge/build.gradle` (3 files), `mc-1.21.1/neoforge/build.gradle`, `mc-*/{forge,neoforge}/src/main/java/…/mixin/*.java` |
| **W4-06** | World-events mixins (Forge/NeoForge cells): `FireBlock#tick`, `IceBlock#tick`, `SpreadingSnowyDirtBlock#tick`, `LeavesBlock#randomTick`, `DispenserBlock#dispenseFrom` — inject `submitBurn/Ignite/Fade/Form/Spread/Dispense/LeavesDecay` calls | `mc-*/forge/src/main/java/.../mixin/FireBlockMixin.java` etc. (owned by this task, not W4-05); W4-05 makes mixin plugin available |
| **W4-07** | NeoForge 1.21.1 bucket mixin — `BucketItem#use` + `MilkBucketItem#finishUsingItem` | `mc-1.21.1/neoforge/src/main/java/.../mixin/BucketItemMixin.java`, `mc-1.21.1/neoforge/src/main/java/.../mixin/MilkBucketItemMixin.java` |
| **W4-08** | WorldEdit-modded soft-dep bridge (`r:#worldedit`) — `WorldEditBridge.java` core + `WorldEditSelectionRequest` marker on `QueryFilter` + lookup executor resolves at query time. Reflection-only; NO import of `com.sk89q.*`. | `core/src/main/java/network/vonix/guardian/core/worldedit/WorldEditBridge.java`, `core/src/main/java/network/vonix/guardian/core/query/QueryFilter.java` (marker field), `core/src/test/java/…/worldedit/WorldEditBridgeTest.java` |
| **W4-09** | PreLogEvent native bus adapters — Forge/NeoForge/Fabric cell-side wrappers that fire a native cancellable event on top of `PreLogDispatcher.setNative(...)` | `mc-*/forge/src/main/java/…/bridge/ForgePreLogBridge.java` (3 files), `mc-1.21.1/neoforge/src/main/java/…/bridge/NeoForgePreLogBridge.java`, `mc-*/fabric/src/main/java/…/bridge/FabricPreLogBridge.java` (4 files) — one per cell |
| **W4-10** | `/vg teleport` — teleport to any lookup result row (permission `vonixguardian.command.teleport`, opLevel 3) | `core/…/rollback/TeleportRequest.java`, `mc-*/*/…/GuardianCommands.java` `Teleport.run` block (8 cells; text-replicate) |
| **W4-11** | `/vg give` — reissue an item from a lookup result (permission gated, default deny) — Note: modded-safe implementation, use registry-based item lookup | `core/…/api/GiveRequest.java`, 8 cells' `GuardianCommands.java` `Give.run` |
| **W4-12** | i18n stub — English-only extraction into `core/…/i18n/Messages.java` + `en_us.properties` in resources. Every hardcoded chat/log line uses `Messages.get("key")` fallback pattern. | `core/…/i18n/Messages.java`, `core/src/main/resources/lang/en_us.properties`, `core/src/main/resources/lang/README.md` (translator instructions) — cells NOT touched this wave |
| **W4-13** | `/vg config` inspector — `/vg config get <key>` / `/vg config set <key> <value>` for hot-swap-safe fields (matches CP's runtime config surface) | `core/…/config/ConfigCommandHandler.java`, 8 cells' `GuardianCommands.java` `Config.get/set` blocks |
| **W4-14** | Ecosystem plugin discoverability — `docs/PLUGINS.md` (how to build a soft-dep against VG's API v1) + Maven Central publish config (extends W3-B14's GH Packages) | `docs/PLUGINS.md`, `core/build.gradle` (publishing block extension), NEW: `docs/API.md` update |
| **W4-15** | Final verify + release scaffolding — dry-run `:core:build` on integration branch, produce a `docs/history/NIGHTSHIFT-v1.2.0.md` from current `NIGHTSHIFT.md`, prep an empty `NIGHTSHIFT.md` for v1.3.0 | `docs/history/NIGHTSHIFT-v1.2.0.md`, `NIGHTSHIFT.md` (rewrite) |

## Pre-wire (main thread, before dispatch)

Nothing shared surfaces to pre-wire that doesn't already exist — v1.1.7 pre-wire (`EventHook`, `PreLogEvent`, `PermissionNode`) covers everything. But I DO need to:

1. Bump `mod_version` to `1.2.0` in `gradle.properties` on the integration branch
2. Bump `GuardianAPI.PLUGIN_VERSION = "1.2.0"`
3. Create integration branch `integration/v1.2.0` from `v1.1.8`
4. Push it so subagents can fork from a stable ref

## Rules for EVERY subagent

- Own worktree `/tmp/vg-w4-<XX>-wt`
- Base tag `integration/v1.2.0` (post-pre-wire SHA — I'll give the exact SHA in each context)
- Build: `./gradlew -PbuildProfile=coreonly :core:build` MUST pass
- For cells: `./gradlew -PbuildProfile=forgeonly :mc-1.20.1:forge:build -x test` MUST pass (or `:mc-1.21.1:neoforge:build` for NeoForge-touching tasks)
- Fabric tasks: NO local aggregate build — CI will validate
- CHANGELOG entries go to per-task file `changelog-fragments/W4-<XX>.md` (parent consolidates at integration time)
- Push branch, no PR

## Post-wave (main thread)

1. Merge all 15 branches into `integration/v1.2.0` in order: `01, 04, 05` (infra first) → `02, 06, 07, 08, 09, 10, 11, 12, 13, 14` (features) → `15` (release scaffolding)
2. Full `:core:build` + all 4 `-PbuildProfile=forgeonly` cell builds green
3. Push tag `v1.2.0` — CI will publish jars
4. `gh release download v1.2.0` for fleet deploy
