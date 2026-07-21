# Improvement Suggestion Request — VonixGuardian

## Required output file

Write your full response to this exact repo-root file:

```text
Improvement Suggestions Response.md
```

Do **not** only reply in chat/stdout. The maintainer will review that file afterward. If you need scratch notes, keep them under `/tmp` and put only the curated final report in `Improvement Suggestions Response.md`.

## Assignment

You are an external senior code-review agent auditing **VonixGuardian**, a server-side audit/rollback mod for modded Minecraft. Your job is to produce a prioritized improvement report, not to implement changes unless separately instructed.

Focus on high-leverage, evidence-backed suggestions that would move the project closer to “production-grade / CoreProtect-parity for modded servers”:

- correctness and data-loss risks
- rollback/restore fidelity
- cross-loader and cross-version parity
- server-thread safety and tick-freeze risks
- hot-path allocation/performance problems
- database schema/migration/queue reliability
- operator UX, diagnostics, docs, and maintainability
- test gaps where a real regression could ship

## Hard safety boundaries

- Do **not** touch live servers or live player accounts.
- Do **not** run commands that mutate production Minecraft state (`op`, `deop`, LuckPerms edits, ban/give/tp, etc.).
- Do **not** search for or print secrets/tokens/API keys/private certs. Treat config files and env as potentially secret-bearing.
- Do **not** deploy, publish, tag, or push.
- For this assignment, do **not** edit source code. Write the review report only.

## Repository context

Repository root:

```text
/root/DEV/VonixGuardian
```

Current observed state at request creation:

```text
branch: main...origin/main
head: 934783d
latest commit: 934783d 2026-07-03 20:57:23 +0000 release(1.3.7): merge v1.3.0-integration → main
version: 1.3.7
untracked: MAIN
```

Version check that works without configuring every loader cell:

```bash
./gradlew -PbuildProfile=coreonly -q printVersion
# observed: 1.3.7
```

## Project shape

Gradle multi-project mod with a pure-Java engine plus loader/version cells:

- `core` — loader-agnostic engine, storage, queue, query parser, rollback, config, permissions, public API, diagnostics, tests.
- `mc-1.21.1/neoforge`
- `mc-1.21.1/fabric`
- `mc-1.20.1/forge`
- `mc-1.20.1/fabric`
- `mc-1.19.2/forge`
- `mc-1.19.2/fabric`
- `mc-1.18.2/forge`
- `mc-1.18.2/fabric`

Build profiles are selected in `settings.gradle`:

- `coreonly` / legacy `core` — only `:core`
- `mc1211` — 1.21.1 NeoForge + Fabric
- `mc1201`, `mc1192`, `mc1182` — individual version pairs
- `forgeonly` — Forge/NeoForge cells only
- `all` — every cell; can expose Fabric Loom classloader conflicts in this checkout

Approximate source inventory observed when this request was written:

- `core/src/main/java`: 111 Java files
- `core/src/test/java`: 137 Java files
- loader `src/main/java` under `mc-*`: 299 Java files
- Java LOC: about 83k
- docs markdown LOC: about 12k
- docs under `docs/`: 29 markdown files

## Important files to inspect first

Read these before making broad claims:

```text
AGENTS.md
README.md
SHARED-CONTRACTS.md
CHANGELOG.md
docs/ARCHITECTURE.md
docs/CONFIG.md
docs/DATABASE.md
docs/DEVELOPMENT.md
docs/USAGE.md
docs/PERMISSIONS.md
docs/API.md
docs/MODDED-ATTRIBUTION.md
docs/COREPROTECT-GAP-INVENTORY.md
docs/COREPROTECT-COMPARISON.md
docs/PERF-NOTES-1.3.3.md
docs/PERF-NOTES-1.3.4.md
docs/PERF-NOTES-1.3.5.md
docs/PERF-NOTES-1.3.6.md
```

Some older docs are historical and intentionally stale in parts. Prefer current code + `CHANGELOG.md` over old comparison tables when they conflict. For example, old docs may still describe `/vg reload`, WorldEdit radius handling, PreLogEvent, Fabric mixins, or action suggestions as missing even though current code appears to have moved on.

## Current architecture map

### Core boot/runtime

Key files:

```text
core/src/main/java/network/vonix/guardian/core/Guardian.java
core/src/main/java/network/vonix/guardian/core/config/GuardianConfig.java
core/src/main/java/network/vonix/guardian/core/config/ConfigLoader.java
core/src/main/java/network/vonix/guardian/core/queue/BatchedAsyncWriteQueue.java
core/src/main/java/network/vonix/guardian/core/event/EventGate.java
core/src/main/java/network/vonix/guardian/core/event/PreLogEvent.java
core/src/main/java/network/vonix/guardian/core/event/PreLogDispatcher.java
core/src/main/java/network/vonix/guardian/core/diagnostics/GuardianStatus.java
```

`Guardian.boot(...)` wires config, DAO, async write queue, JSONL side log, event gate, permissions, rollback/purge/undo engines, per-world overrides, blacklist hooks, mixin-hot-event filter, and auto-purge. Config reload is hot-swap aware and uses `Guardian.CONFIG_MUTATION_LOCK`; loader cells also route some config operations off the server thread.

### Storage

Key files:

```text
core/src/main/java/network/vonix/guardian/core/storage/Schema.java
core/src/main/java/network/vonix/guardian/core/storage/GuardianDao.java
core/src/main/java/network/vonix/guardian/core/storage/StorageFactory.java
core/src/main/java/network/vonix/guardian/core/storage/jdbc/AbstractJdbcDao.java
core/src/main/java/network/vonix/guardian/core/storage/jdbc/SqliteDao.java
core/src/main/java/network/vonix/guardian/core/storage/jdbc/MysqlDao.java
core/src/main/java/network/vonix/guardian/core/storage/jdbc/PostgresDao.java
core/src/main/java/network/vonix/guardian/core/storage/migration/*
core/src/main/java/network/vonix/guardian/core/storage/dbmigrate/*
```

Storage supports SQLite, MySQL/MariaDB, and PostgreSQL. Current schema is `Schema.CURRENT_VERSION = 5`, including rollback batch audit tables, widened `target`, sign metadata, and optional NBT fidelity columns (`old_block_state`, `new_block_state`, `block_entity_nbt`, `item_nbt`, `entity_nbt`).

### Query / command semantics

Key files:

```text
core/src/main/java/network/vonix/guardian/core/query/QueryParser.java
core/src/main/java/network/vonix/guardian/core/query/QueryFilter.java
core/src/main/java/network/vonix/guardian/core/query/QueryCompiler.java
core/src/main/java/network/vonix/guardian/core/query/WorldEditRegionResolver.java
core/src/main/java/network/vonix/guardian/core/action/ActionType.java
core/src/main/java/network/vonix/guardian/core/action/ActionTokens.java
core/src/main/java/network/vonix/guardian/core/command/CommandSpec.java
mc-*/**/common/GuardianCommands.java
mc-*/**/common/GuardianSuggestions.java
```

`QueryParser` supports CoreProtect-style `u:`, `t:`, `r:`, `a:`, include/exclude, and hashtag flags. `r:#we` / `r:#worldedit` currently routes through a reflection-only `WorldEditRegionResolver`; verify behavior before claiming it is missing. `ActionTokens.java` exists in current code and should be checked before claiming suggestion/parser mismatch.

### Rollback / restore / purge

Key files:

```text
core/src/main/java/network/vonix/guardian/core/rollback/RollbackEngine.java
core/src/main/java/network/vonix/guardian/core/rollback/RollbackPlan.java
core/src/main/java/network/vonix/guardian/core/rollback/WorldMutator.java
core/src/main/java/network/vonix/guardian/core/rollback/PurgeEngine.java
core/src/main/java/network/vonix/guardian/core/rollback/UndoStack.java
```

Rollback includes explicit time-filter guards, preview mode, rollback batch audit, NBT-aware block/entity/container restoration paths, explosion affected-list handling, supplemental explosion spatial scanning, and undo/restore tests. This is a critical audit area: suggestions should be specific and testable.

### Loader cells and mixins

Key files/patterns:

```text
mc-*/**/common/GuardianCommands.java
mc-*/**/common/NbtAttributionScanner.java
mc-*/**/*AttributionResolver.java
mc-*/**/*WorldMutator.java
mc-*/**/*Events.java
mc-*/**/*MixinBridge.java
mc-*/**/src/main/resources/*.mixins.json
mc-*/**/src/main/java/**/mixin/*.java
```

Current resource scan found Fabric mixin configs for 1.18.2/1.19.2/1.20.1/1.21.1 and a NeoForge 1.21.1 mixin config. Forge 1.18.2/1.19.2/1.20.1 recently moved several hot natural-block/hopper/fill paths to event-bus fallbacks and deleted orphan mixins; see `CHANGELOG.md` and `docs/PERF-NOTES-1.3.3.md` / `docs/PERF-NOTES-1.3.6.md`.

### Public API and ecosystem

Key files:

```text
core/src/main/java/network/vonix/guardian/core/api/VonixGuardianAPI.java
core/src/main/java/network/vonix/guardian/core/api/GuardianAPI.java
docs/API.md
docs/PLUGINS.md
```

Current `GuardianAPI.PLUGIN_VERSION` is manually set to `"1.3.7"`; consider maintainability/version-source-of-truth risk if relevant.

## Verification already run for this request

These commands were run while preparing this request:

```bash
./gradlew -PbuildProfile=coreonly :core:test --console=plain
# observed: BUILD SUCCESSFUL in 1s, 4 tasks up-to-date
```

Existing XML test reports at the time showed:

```text
test_xml_files=126
tests=916 failures=0 errors=0 skipped=0
largest suites included QueryParserTest, ActionTypeExpansionTest, EventGateTest,
ConfigLoaderTest, CoreProtectFidelityTest, RollbackEngineTest.
```

1.21.1 NeoForge build check:

```bash
./gradlew -PbuildProfile=mc1211 :mc-1.21.1:neoforge:build -x test --console=plain
# observed: BUILD SUCCESSFUL in 3s, tasks up-to-date
```

Version check:

```bash
./gradlew -PbuildProfile=coreonly -q printVersion
# observed: 1.3.7
```

Observed build pitfall:

```bash
./gradlew -q printVersion
# observed failure while configuring all/default profile:
# fabric-loom BuildSharedServiceManager$Inject cannot be cast to BuildSharedServiceManager
# location: mc-1.20.1/fabric/build.gradle line 8
```

Do not over-index on this without reproducing with a clean Gradle daemon/cache, but it is worth considering as a build-system improvement because CI avoids the issue by building matrix modules/profiles separately.

## Review expectations

Write `Improvement Suggestions Response.md` with this structure:

```markdown
# Improvement Suggestions Response

## Executive summary
- 5-10 bullets, sorted by impact.

## Top recommendations
| Priority | Recommendation | Evidence | Risk if ignored | Suggested fix | Verification |
|---|---|---|---|---|---|

## P0/P1 correctness and data-loss risks
...

## Cross-loader / cross-version parity risks
...

## Performance / server-thread safety risks
...

## Storage / migration / queue reliability risks
...

## Maintainability / docs / UX improvements
...

## Tests to add
...

## Things checked and intentionally not flagged
...

## Verification performed
- Commands run, exact outcomes, and any blocked commands.

## Suggested implementation order
1. ...
```

For each recommendation:

- Include exact file paths and line/function anchors when possible.
- Distinguish **confirmed bug** from **risk** from **nice-to-have**.
- Do not cite old docs as truth if current code contradicts them; call out doc staleness separately.
- Prefer patch-shaped recommendations: “change X in file Y, add test Z, verify with command Q”.
- Prefer reproducible evidence over broad architectural opinions.
- If you find no P0/P1 issues, say so explicitly and spend the report on P2/P3 improvements.

## Suggested audit checklist

Prioritize these checks:

1. **Reload/config mutation safety**
   - Check `Guardian.CONFIG_MUTATION_LOCK`, `reloadConfig`, `reloadConfigUnlocked`, and all 8 `GuardianCommands.Config.set` copies.
   - Look for lost updates, server-thread callbacks after source/player invalidation, and worker-pool lifecycle leaks.

2. **Loader-cell duplication drift**
   - Diff the 8 `common/GuardianCommands.java` and `GuardianSuggestions.java` files.
   - Flag semantic drift that should be generated/shared.
   - Confirm fixes land across all relevant cells.

3. **Mixin registration parity**
   - For each mixin Java file, verify it appears in the relevant `*.mixins.json` and is server-safe.
   - For each advertised action family, identify at least one producer path per loader/version or explicitly document gaps.

4. **Rollback fidelity**
   - Inspect `RollbackPlan` and `RollbackEngine` for action types that are recorded but not rollbackable.
   - Check NBT fidelity behavior when `storage.persistNbt` is false/true.
   - Check explosion affected-list chunking/truncation and supplemental scan bounds.

5. **Database correctness**
   - Review schema migration idempotency across SQLite/MySQL/Postgres.
   - Review Hikari config, connection lifecycle, batching, transaction boundaries, and failure behavior.
   - Check `target VARCHAR(4096)` is still enough for worst-case serialized explosion/sign/chat payloads; if not, propose TEXT/BLOB migration.

6. **Hot-path performance**
   - Check event producers and mixin bridge paths for allocations, locks, reflection, blocking IO, world scans, unbounded maps/queues.
   - Pay special attention to fire/spread/liquid/hopper/explosion/entity-block-change paths.

7. **Build/release process**
   - Review profile behavior and the observed default-profile Fabric Loom conflict.
   - Check CI matrix coverage and whether a local “safe full verification” wrapper script should exist.
   - Check `GuardianAPI.PLUGIN_VERSION` vs Gradle version source-of-truth.

8. **Docs consistency**
   - Identify stale docs that could mislead future agents/operators.
   - Suggest doc updates only where current code clearly contradicts docs.

## Commands worth running

Use focused commands first:

```bash
./gradlew -PbuildProfile=coreonly :core:test --console=plain
./gradlew -PbuildProfile=coreonly -q printVersion
./gradlew -PbuildProfile=mc1211 :mc-1.21.1:neoforge:build -x test --console=plain
./gradlew -PbuildProfile=mc1211 :mc-1.21.1:fabric:build -x test --console=plain
```

Then, if time allows, run matrix-style module builds matching CI:

```bash
./gradlew :mc-1.21.1:neoforge:build -PbuildProfile=mc1211 --no-daemon
./gradlew :mc-1.21.1:fabric:build   -PbuildProfile=mc1211 --no-daemon
./gradlew :mc-1.20.1:forge:build    -PbuildProfile=mc1201 --no-daemon
./gradlew :mc-1.20.1:fabric:build   -PbuildProfile=mc1201 --no-daemon
./gradlew :mc-1.19.2:forge:build    -PbuildProfile=mc1192 --no-daemon
./gradlew :mc-1.19.2:fabric:build   -PbuildProfile=mc1192 --no-daemon
./gradlew :mc-1.18.2:forge:build    -PbuildProfile=mc1182 --no-daemon
./gradlew :mc-1.18.2:fabric:build   -PbuildProfile=mc1182 --no-daemon
```

If a command fails, include the exact failure and whether it blocks the recommendation.

## Final reminder

Your deliverable is **only** the file:

```text
Improvement Suggestions Response.md
```

Make it concise enough for a maintainer to act on, but specific enough that each recommendation can become an issue or PR without another archaeology pass.
