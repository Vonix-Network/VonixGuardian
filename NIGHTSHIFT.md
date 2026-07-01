# NIGHTSHIFT — VonixGuardian CoreProtect 1:1 Port + Fixes

Authorized 2026-07-01 by WeedMeister via `$ultracode`: "fan out subagents to rebuild sections with industry grade best practices to match coreprotect as a 1:1 port or better considering its for modded."

**Baseline**: `v1.1.5-entity-filter` @ `d47b6c1` (has the CP-comparison + audit docs from wave-1).

**Target**: `v1.2.0` (`main` branch), consuming intermediate `v1.1.6` for the surgical fixes.

---

## Total gap inventory (from 7 wave-1 subagents + 1 wiring auditor)

### A. Surgical bugs — ship as v1.1.6 (parallel-safe, small)

| # | Bug | Severity | File |
|---|-----|----------|------|
| A1 | `RollbackPlan.isRollbackable` silent default swallows 14 handlers | 🚨 CRITICAL | `RollbackPlan.java:96-108` |
| A2 | Twin `RollbackEngine.isRollbackable` same bug (unused, drift-prone) | HIGH | `RollbackEngine.java:477-488` |
| A3 | `stripMobPrefix` duplicated verbatim in 4 sister cells | HIGH | 4 cells' events |
| A4 | Coalescer/whitelist ordering by convention not enforcement | HIGH | `Guardian.java:410-429` |
| A5 | `wind_charge`/`breeze_wind_charge` are `Projectile`, never match `LivingEntity` | MEDIUM | `VanillaGrieferSet.java:47-48` |
| A6 | Hot-path `EntitySentinel.of()` + `stripMobPrefix` allocations | MEDIUM | 4 cells' events |
| A7 | `GuardianSuggestions.ACTIONS` advertises 12 tokens the parser rejects → parse error on tab | HIGH | `GuardianSuggestions.java:62-75` |
| A8 | `EventSubmitter.submitBurn/Ignite/Fade/Form/Spread/Dispense/LeavesDecay` — zero handlers wired | HIGH | 4 cells' events |
| A9 | NeoForge 1.21.1 `BUCKET_EMPTY`/`FILL` unwired (upstream removed `FillBucketEvent`) | HIGH | `NeoForgeEvents.java:636` |
| A10 | `HANGING_PLACE`/`HANGING_BREAK` refuse-rollback wired but no submit path | HIGH | 4 cells' events |
| A11 | `target VARCHAR(192) NOT NULL` truncated by chat/command/sign/explosion submits (CP-Berk field report 2026-07-01 08:05) | 🚨 CRITICAL | `Schema.java:180`, `Guardian.java:296-310`, `ActionBuilder.java:170` |

### B. Real feature gaps — ship as v1.2.0 (multi-wave)

| # | Feature | Effort | Files |
|---|---------|--------|-------|
| B1 | `/vg reload` real handler (currently a stub) | S | `Reload.java` + `ConfigLoader` |
| B2 | `/vg undo` real world-revert via inverse (currently just pops history) | M | `Undo.java` + `RollbackEngine` |
| B3 | `/vg migrate-db` SQLite ↔ MySQL migration | L | new `MigrateDb.java` + DAO glue |
| B4 | Auto-purge daemon (`auto-purge: 180d`, `auto-purge-time: 03:30`) | M | new `AutoPurgeScheduler.java` |
| B5 | Per-world config overrides (`world_nether.json` shadow) | M | `ConfigLoader` extension |
| B6 | `blacklist.txt` — users / commands / blocks / entities / `id@user` composites | M | new `BlacklistFile.java` + `EventGate` wire |
| B7 | 12 child permission nodes (`vonixguardian.command.lookup.<category>`) | M | `CommandSpec` + `PermissionResolver` |
| B8 | Op-level granular fallback (currently all-or-nothing when LP absent) | S | `PermissionResolver` |
| B9 | `t:` decimal + range syntax (`t:2.5h`, `t:1h-2h`) | S | `QueryParser` |
| B10 | `#optimize` — real MySQL `OPTIMIZE TABLE` (currently no-op) | S | `MysqlDao` |
| B11 | `PreLogEvent` public cancellable — third-party mods can intercept | M | new event class + `EventSubmitter` gate |
| B12 | `hasPlaced` / `hasRemoved` / `queueLookup` / `APIVersion()` / `testAPI()` API methods | S | `Guardian` + `GuardianDao` |
| B13 | Per-family typed result classes (`BlockResult`, `ContainerResult`, …) | M | new `result/` package |
| B14 | Maven publish config (`publishing {}` block never written) | S | `core/build.gradle` |
| B15 | Sign v24 columns (front/back/dye/waxed) | M | `Schema` + `submitSign` sig + 4 cells |
| B16 | `r:#worldedit` / `r:#we` — real WorldEdit selection integration (recon: does WE-forge/FAWE exist and how do we bridge without a hard dep?) | recon+M | `QueryParser` + soft-dep bridge |

### C. Deferred to v1.2.1 (needs mixin wave; documented, not built now)

- C1: Fabric BLOCK_PLACE + LivingDestroyBlock + explosion + piston + item toss/pickup/craft + sign edit — all 4 Fabric cells need mixins. CHANGELOG v1.0.4 P0 backlog. Landing all 8 mod files with mixin conflicts is a whole engineering wave; safer to spec + design in v1.2.0 and ship in v1.2.1.
- C2: Port `VanillaGrieferSet` gate to Fabric — depends on C1 landing.
- C3: Language support (100+ ISO codes) — nice-to-have, not shipping v1.2.0.

### D. Out of scope forever

- D1: Bukkit/Spigot/Paper build — CP owns that space. Deliberate.
- D2: Networking API (`coreprotect.networking`) — out of scope for v1.x.
- D3: Hytale — CP has a dedicated build. Not our target.
- D4: Third-party integration ecosystem — external work, not code.

---

## Waves

Total: ~19 subagent tasks across 4 waves. Cap: 100 concurrent (Hermes config).

### Wave 2 — v1.1.6 surgical patches (5 parallel subagents)

Disjoint file ownership designed to avoid pitfall #7a (shared-registry writes):

| Subagent | Task | FILES YOU OWN | DO NOT TOUCH |
|---|---|---|---|
| W2-01 | A1: Fix `RollbackPlan.isRollbackable` + regression test | `core/…/rollback/RollbackPlan.java`, new `core/…/rollback/RollbackPlanTest.java` | `RollbackEngine.java`, anything else |
| W2-02 | A2 + LOW 2 doc: Delete twin `RollbackEngine.isRollbackable`, file GitHub issue reference for HANGING_PLACE TODO | `core/…/rollback/RollbackEngine.java` | `RollbackPlan.java` |
| W2-03 | A3+A4+A5+A6: Centralize `stripMobPrefix` to `VanillaGrieferSet`, add `EntitySentinel.registryKeyOf()`, move whitelist check into `Guardian.submitEntityChangeBlock`, remove `wind_charge`/`breeze_wind_charge` dead entries | `core/…/filter/VanillaGrieferSet.java`, `core/…/common/EntitySentinel.java` (probably; verify), `core/…/Guardian.java` **submitEntityChangeBlock method only** | 4 cells' events — parent cleans up between waves |
| W2-04 | A7: Fix `GuardianSuggestions.ACTIONS` → only advertise parser-accepted tokens; add regression test | 8 cells' `GuardianSuggestions.java`, new test | `QueryParser.java` |
| W2-05 | A8+A9+A10: Wire missing burn/ignite/fade/form/spread/dispense/leaves_decay handlers + HANGING_PLACE/BREAK submit paths + investigate NeoForge 1.21.1 bucket alternative (mixin recommendation ok, we won't ship the mixin here) | `mc-1.18.2/forge/…/ForgeEvents.java`, `mc-1.19.2/forge/…/ForgeEvents.java`, `mc-1.20.1/forge/…/ForgeEvents.java`, `mc-1.21.1/neoforge/…/NeoForgeEvents.java` | Anything in `core/` |
| W2-06 | A11: Widen `target` to `VARCHAR(4096)` via schema-version bump v1→v2 (`ALTER TABLE actions MODIFY target VARCHAR(4096) NOT NULL`), add regression test for a 512-char chat submit round-trip, AND audit `WriterQueue`/`JdbcWriter` retry-degradation policy for the "batch size=1 poison-row storm" WeedMeister flagged — recommend fix if amplification is real | `core/…/storage/Schema.java` (VERSION bump + column), new `core/…/storage/migration/V2WidenTarget.java` or equivalent, `core/…/storage/GuardianDao.java` (migration hook), new `core/…/storage/SchemaTargetWidthTest.java` | `Guardian.java`, cells' events, other `core/` subsystems |

**Between-wave parent cleanup (main thread, sequential):**
1. `git status --short` — rescue any uncommitted subagent work (pitfall #14)
2. Remove now-redundant whitelist call + local `stripMobPrefix` helper from the 4 cells' `onLivingDestroyBlock` (uses new centralized API from W2-03). Python read/replace/write across 4 cells.
3. `./gradlew :core:compileJava` (with correct JDK per pitfall #11)
4. Bump `mod_version` 1.1.5 → 1.1.6 in `gradle.properties`
5. Update `CHANGELOG.md` with v1.1.6 entry
6. Tag `v1.1.6`, push to origin

### Wave 3 — v1.2.0 additive features round A (7 parallel subagents)

Purely additive, no schema-versioning conflict. Each owns disjoint files.

| Subagent | Task | FILES YOU OWN |
|---|---|---|
| W3-01 | B1: `/vg reload` real handler | `Reload.java` in 8 cells (small), `ConfigLoader.reload()` |
| W3-02 | B2: `/vg undo` real world-revert (calls inverse via `RollbackEngine.rollback`/`restore` on the popped `RollbackResult.plan`) | `Undo.java` in 8 cells, `UndoStack.java`, `RollbackResult.java` (may need to persist filter) |
| W3-03 | B4: Auto-purge daemon | new `core/…/purge/AutoPurgeScheduler.java`, `Guardian.boot()` addition, `PurgeConfig` extension |
| W3-04 | B5: Per-world config overrides | `ConfigLoader.java` extension, doc entry |
| W3-05 | B6: `blacklist.txt` | new `core/…/blacklist/BlacklistFile.java` + `BlacklistMatcher.java`, `EventGate.java` wire |
| W3-06 | B9: `t:` decimal + range syntax | `QueryParser.parseTime()`, new `TimeRange` record, tests |
| W3-07 | B14: Maven publish config + B12 API methods (`hasPlaced`/`hasRemoved`/`queueLookup`/`APIVersion`/`testAPI`) | `core/build.gradle` (publishing block), `Guardian.java` (new methods), `GuardianDao.java` (new methods) |

**Between-wave parent cleanup:**
1. Rescue uncommitted work
2. Compile check `:core`
3. Commit each subagent's work with per-scope messages, push per commit (pitfall #5)

### Wave 4 — v1.2.0 additive features round B (5 parallel subagents; needs Wave 3 done)

Touches shared surfaces (permissions, event dispatch, schema).

| Subagent | Task | FILES YOU OWN |
|---|---|---|
| W4-01 | B3: `/vg migrate-db` | new `core/…/storage/DbMigration.java`, `MigrateDb.java` in 8 cells |
| W4-02 | B7+B8: 12 child perm nodes + granular op-level fallback | `CommandSpec.java`, `PermissionResolver.java`, 8 cells' `GuardianCommands.java` (permission-checks in lookup only) |
| W4-03 | B10: `#optimize` real MySQL `OPTIMIZE TABLE` | `MysqlDao.java`, `PurgeEngine.java` |
| W4-04 | B11: `PreLogEvent` public cancellable + soft-dep bridge doc | new `core/…/event/GuardianPreLogEvent.java`, `EventSubmitter.java` (add gate call), `docs/API.md` |
| W4-05 | B13: Per-family typed result classes | new `core/…/api/result/` package (`BlockResult`, `ContainerResult`, `InventoryResult`, `ItemResult`, `MessageResult`, `SessionResult`, `SignResult`, `UsernameResult`), `GuardianDao.java` typed lookup methods |

**Between-wave parent cleanup:**
1. Rescue + compile + commit + push
2. Land B15 (Sign v24 columns) on main thread — needs schema-version bump v2→v3 which is a single sequenced write (Schema.java + Migration + 4 cells' onSignChange)
3. Land B16 (WorldEdit recon) if `$ultracode` includes it — single main-thread investigation

### Wave 5 — release prep + docs (2 parallel subagents)

| Subagent | Task |
|---|---|
| W5-01 | Update `docs/USAGE.md`, `docs/CONFIG.md`, `docs/PERMISSIONS.md`, `docs/API.md`, `docs/FAQ.md`, `docs/COREPROTECT-COMPARISON.md` to reflect the new surface |
| W5-02 | Update `CHANGELOG.md` with v1.2.0 entry; bump version; update `README.md` version matrix |

**Final main-thread step:** merge `v1.1.5-entity-filter` → `main`, tag `v1.2.0`, push.

---

## Timeline expectation

Wave 2: ~10 min real time (5 subagents in parallel, each ~5-8 min)
Wave 3: ~15 min (7 subagents, some heavier)
Wave 4: ~15 min (5 subagents, some heavier)
Wave 5: ~5 min

Plus ~5 min per wave for main-thread verify + commit + push.

**Total: ~1 hour real time** for the whole build. Nightshift-appropriate.

---

## Discipline rules (from `delegating-parallel-builds` skill)

- Every subagent's `context` MUST include `FILES YOU OWN:` + `DO NOT TOUCH:` explicit blocks
- Every subagent MUST cite the CP-comparison doc + wiring-audit doc as authoritative context
- Every subagent's summary MUST be written to `/tmp/vg-vs-cp/waveN-XX-report.md` to keep parent context clean
- Subagent verification claims are HINTS, not truth — parent re-runs `./gradlew :core:compileJava` before every commit
- `git status --short` before every "wave complete" declaration — rescue any uncommitted deliverables
- Push after every commit (never batch pushes)
- If Fabric-loom classloader (pitfall #11) blocks a compile check, note it and continue — the code is what matters
