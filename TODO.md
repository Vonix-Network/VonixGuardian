# VonixGuardian — TODO to reach 100% CoreProtect parity

**Status at v1.2.4 (2026-07-02):** ~93% CoreProtect-parity by feature surface. Command tree is 1:1, permission model is stronger, storage supports SQLite/MySQL/MariaDB/PostgreSQL, i18n has 14 locales, WorldEdit bridge works, `#optimize` executes real `OPTIMIZE TABLE` / `VACUUM`. The remaining ~7% is split between (A) event-coverage families that need mixins to see the events at all, (B) config-shape parity (`blacklist.txt`, per-world overrides), (C) polish (Fabric mixin infrastructure gaps, deferred audit types).

**Do NOT read this as "VG is broken" — VG at v1.2.4 is production-ready and CoreProtect-comparable for every server it's currently deployed on.** This TODO is the roadmap to being able to say **"exact 1:1 port"** without a single caveat.

## Source of truth

- `docs/COREPROTECT-COMPARISON.md` — the full snapshot comparison
- `docs/COREPROTECT-GAP-INVENTORY.md` — source-scanned gap inventory
- `docs/COMMAND-AUDIT-1.2.0.md` — per-command registration audit
- CoreProtect reference source: `/root/staging/coreprotect-ref/CoreProtect`

Whenever this TODO conflicts with those docs, THOSE are canonical — this file is a summary.

---

## Priority 0 — Correctness blockers

### 0.1 Wire the SpongePowered Mixin Gradle plugin end-to-end

**Why blocking:** v1.2.1's mixin config declaration crashed NeoForge 1.21.1 at bootstrap because the mixin plugin was never wired and no refmap was generated. v1.2.2 fixed this by REMOVING the mixin config, which means every mixin-dependent feature below is currently source-only and never runs.

- [ ] Add `org.spongepowered.mixin` Gradle plugin (or equivalent NeoForge-integrated variant) to each cell's `build.gradle`.
- [ ] Configure refmap output to `vg.refmap.json` on all 4 Forge/NeoForge cells and all 4 Fabric cells.
- [ ] Restore `[[mixins]]` declaration in `mods.toml` / `neoforge.mods.toml` and re-add `vg.mixins.json` config file.
- [ ] Test-boot on Sunlit (Forge 1.20.1) and BMC5 (NeoForge 1.21.1) — same jars, no boot crash, mixin classes ACTUALLY LOAD (verify via `[mixin/]: Mixing …` log lines matching our classes).
- [ ] Bump to v1.3.0 once mixin infrastructure is real.

**Affected features (unblocked once this lands):** every "🟥 mixin" item under P1 below.

### 0.2 Fabric-loom classloader bug on cross-version aggregate builds

**Symptom:** `./gradlew build` from repo root fails with fabric-loom classloader conflict on Fabric cells. Workaround since v1.2.0 has been per-cell `compileJava` in CI.

- [ ] Investigate whether the fix is a loom version bump, per-cell `includeBuild` isolation, or a `settings.gradle` restructure.
- [ ] Restore ability to run `./gradlew build` from repo root against ALL 8 cells in one invocation. Currently loom's classloader gets confused when 4 fabric-loom variants share a Gradle daemon.
- [ ] Document the resolution in `docs/DEVELOPMENT.md` and remove the `-x test` + per-module workarounds from CI.

---

## Priority 1 — Event-coverage families still unwired (mixin work)

These are the "declared in `EventSubmitter` but no `@SubscribeEvent` handler / no mixin actually fires them" gaps. Command surface, storage, rollback engine, permissions, etc. are all ready — they're just not receiving events for these families.

Requires P0.1 (mixin infra) to be actionable.

### 1.1 Forge / NeoForge world-event mixins (W5-01 revival)

Classes exist in source under `mc-{1.18.2,1.19.2,1.20.1}/forge/…/mixin/` and `mc-1.21.1/neoforge/…/mixin/` but are dead code without registration.

- [ ] `FireBlockMixin` — burn (`tick`) + ignite (`onPlace`). Requires refmap for `net.minecraft.world.level.block.FireBlock`.
- [ ] `IceBlockMixin` — fade (`melt`). Requires refmap for `IceBlock`.
- [ ] `SpreadingSnowyDirtBlockMixin` — grass / mycelium spread (`randomTick`).
- [ ] `LeavesBlockMixin` — persistent-false decay (`tick`).
- [ ] `DispenserBlockMixin` — dispense (`dispenseFrom`).
- [ ] Verify emitted rows show up under `a:burn / a:ignite / a:fade / a:spread / a:decay / a:dispense` lookup filters (all `ActionType` tokens already exist).

Feature-flag: `actions.logWorldEvents=true` (currently defaults on and does nothing).

### 1.2 NeoForge 1.21.1 bucket coverage (W5-02 revival)

NeoForge upstream removed `FillBucketEvent`. Classes exist in source but are dead without registration.

- [ ] `BucketItemMixin` — `BucketItem#use` HEAD (extract fluid + pos) + RETURN (branch fill/empty by result).
- [ ] `MilkBucketItemMixin` — `MilkBucketItem#finishUsingItem` (submit empty).
- [ ] Verify `a:bucket` lookup returns rows for water/lava fill and empty events.

### 1.3 Fabric event coverage via mixins (W5-08 revival)

Biggest gap in absolute terms: 10 mixin types × 4 Fabric cells = 40 mixin classes exist in source. All dead until P0.1.

**P0 (must have for parity):**
- [ ] `BlockPlaceMixin` — `BlockItem#place` (real place, currently only `UseBlockCallback` fires on right-click).
- [ ] `LivingDestroyBlockMixin` — `Level#destroyBlock` mob-caused (with `VanillaGrieferSet` allowlist gate applied post-mixin like Forge cells do).
- [ ] `ExplosionMixin` — `Explosion#finalizeExplosion`.
- [ ] `SignChangeMixin` — `ServerGamePacketListenerImpl#handleSignUpdate`.
- [ ] `BucketItemMixin` — mirror the NeoForge pattern from 1.2.

**P1 (round out parity):**
- [ ] `PistonMixin` — `PistonBaseBlock#moveBlocks`.
- [ ] `ContainerMixin` — `ChestBlockEntity#startOpen`/`#stopOpen`.
- [ ] `ItemTossMixin` — `Player#drop(ItemStack,boolean,boolean)`.
- [ ] `ItemPickupMixin` — `ItemEntity#playerTouch`.
- [ ] `CraftItemMixin` — `ResultSlot#onTake`.

**P2 (world-events on Fabric):**
- [ ] Fabric-side `FireBlockMixin`, `IceBlockMixin`, `SpreadingSnowyDirtBlockMixin`, `LeavesBlockMixin`, `DispenserBlockMixin`, `BlockFormMixin`, `BlockSpreadMixin` — Fabric API doesn't cover these natively at all.

### 1.4 Hanging place / break submission across all 8 cells (A10)

Rollback engine already handles both directions (with per-type WARN/refuse). Submit path is completely missing — audit-only rows never populate.

- [ ] Wire `submitHangingPlace` on `EntityJoinLevelEvent` (Forge) / `ServerEntityEvents.ENTITY_LOAD` (Fabric — already partially done in W4-03 for HANGING_PLACE but audit-verify).
- [ ] Wire `submitHangingBreak` for arrow / explosion / mob-caused break paths — currently only player-caused break is captured via `AttackEntityCallback` (Fabric) / `AttackEntityEvent` (Forge).
- [ ] Verify `a:hanging` lookup returns rows.

### 1.5 Sign edit two-sided (front/back/dye/waxed) — CP v24 columns

VG persists a single joined-lines string. CP v24 stores front-lines, back-lines, dye colour, waxed flag as separate columns.

- [ ] Extend `SignSchema` migration v4 → v5 to add `back_lines`, `dye_colour`, `waxed_flag` columns.
- [ ] Update `SignMetadataExtractor` on 1.20+ cells to write both sides.
- [ ] 1.18/1.19 cells: continue writing to `front_lines` only (single-sided signs pre-1.20).
- [ ] Extend `SignLookupResult` in the public API to expose the extra fields.

---

## Priority 2 — Config shape parity

### 2.1 `blacklist.txt` full syntax

CP supports `blacklist.txt` with users, commands, blocks, entities, `id@user` composites, wildcards, and globs. VG has 3 flat JSON arrays with no user/entity/command support and no globs.

- [ ] Design `blacklist.txt` parser matching CP's shape (see CP source `Config.java` blacklist loader).
- [ ] Support entry types: `user:<name>`, `command:<name>`, `block:<id>`, `entity:<id>`, `<user>@<block-id>` composite, `*` wildcards.
- [ ] Wire evaluator into `EventGate.shouldLog` alongside the existing `worldBlacklist` / `blockBlacklist` / `sourceBlacklist` arrays.
- [ ] Preserve `sourceBlacklist` (VG-only strength — drop by `sourceTag`) as an additional filter.

### 2.2 Per-world config overrides

CP supports `world_the_end.yml` / `world_nether.yml` shadow-configs. VG only has `worldBlacklist` (drop all logging for a world).

- [ ] Add `configs/vonixguardian/worlds/<dimension>.json` overlay pattern.
- [ ] Merge overlay on top of base config at boot per-world.
- [ ] Support overriding `actions.*` toggles + `purge.*` + `lookup.*` per world.
- [ ] `/vg reload` should re-read all world overlays.

### 2.3 CP-parity `#optimize` extension

Currently: MySQL runs `OPTIMIZE TABLE`, PostgreSQL runs `VACUUM ANALYZE`, SQLite runs `VACUUM`. CP's `#optimize` is MySQL-only. VG already exceeds parity — but should:

- [ ] Document the dialect matrix in `docs/CONFIG.md` so operators don't expect MySQL-only behaviour.
- [ ] Add a `--dry-run` flag to `#optimize` that reports estimated space savings without executing.

---

## Priority 3 — API polish (already 90% parity via v1.2.0 W5-03)

### 3.1 Queue introspection

`queueLookup(x,y,z)` currently returns an empty list with a TODO. Real implementation needs a `pendingSnapshot()` accessor on `BatchedAsyncWriteQueue`.

- [ ] Add `pendingSnapshot()` returning immutable copy of pending Action list (cap at N to avoid memory blow-up).
- [ ] Wire into `GuardianAPI.queueLookup`.
- [ ] Add unit test that `logChat` → `queueLookup` sees the pending row before the writer flushes.

### 3.2 `parseResult(String[])` compatibility layer

CP returns `String[]` rows from lookup APIs; VG returns typed `Action` records. Third-party plugins written against CP won't compile against VG.

- [ ] Add `parseResult(String[])` static method that adapts VG's typed records to CP's `String[]` shape (for source-level compat with mods designed for CP).
- [ ] Document this as a "prefer typed API but use `parseResult` if porting CP-based code" in `docs/API.md`.

---

## Priority 4 — Diagnostic / operational polish

### 4.1 Migration between forks

- [ ] `/vg import-from-coreprotect` — one-shot importer that reads `plugins/CoreProtect/*.db` and copies rows into `vg_actions`. Needed for servers migrating from CP → VG.

### 4.2 Prometheus / metrics endpoint

- [ ] Expose `/vg status` internals as `/metrics` on a configurable HTTP port. VG has all the counters — just needs a Prometheus format serializer.

### 4.3 Better tab-completion parity

- [ ] Audit `GuardianSuggestions.ACTIONS` — some legacy tokens were fixed in W5-04. Sweep for any remaining "tab-completes-to-invalid-token" case.

---

## Priority 5 — Nice-to-have (feature-parity but not blocking)

### 5.1 CP-parity language auto-translate

CP uses Google Translate to render error strings in 100+ ISO codes. VG has 14 hand-curated bundles.

- [ ] Consider adding an optional runtime Google Translate fallback for message keys not present in the selected bundle. Off by default; opt-in via `config.language.autoTranslate=true`.

### 5.2 Client-side inspector companion

- [ ] Optional client-side mod that draws an in-world outline around blocks with recent VG history. Similar to CP's `co-mod` client add-on. Requires a small client-only fork of core to render outlines.

### 5.3 Web dashboard

- [ ] Deferred to `vonix-ai-ops-web` — expose VG lookup / rollback via the Vonix admin dashboard rather than requiring in-game commands. Requires a websocket bridge on the Guardian API.

---

## Meta / process

- [ ] Once P0 + P1 lands, tag as **v1.3.0 — Mixin Wave** and cut a `docs/PARITY.md` snapshot showing all rows flip from 🟥 to ✅.
- [ ] Regenerate `docs/COREPROTECT-COMPARISON.md` row-by-row after v1.3.0 to remove all the "v1.2.0 flipped from" historical caveats.
- [ ] Consider archiving `docs/COREPROTECT-GAP-INVENTORY.md` post-v1.3.0 as `docs/history/GAP-INVENTORY-pre-1.3.0.md`.

## Not doing (deliberate design deltas)

These are documented divergences from CP that we're KEEPING:

- ➖ Preview model: VG's `#preview` is separate from actual mutation. CP has `/co apply` / `/co cancel` — VG requires re-issuing the command. Cleaner UX, no server-side pending-preview state to leak.
- ➖ `#nether` / `#overworld` / `#end` radius keywords — VG has these, CP doesn't.
- ➖ `sourceBlacklist` (VG-only) — drop rows by `sourceTag` metadata; no CP equivalent.
- ➖ `vonixguardian.command.viewothers` permission — CP has no filter-scoping node.
- ➖ PostgreSQL support — CP is SQLite/MySQL only.

These are VG's added value on top of CP.
