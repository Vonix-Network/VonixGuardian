# Design: Modded-Entity Griefing Coverage + CoreProtect Logging Parity

Status: PROPOSED (no code changed yet)
Author: automated review, 2026-07-11
Scope: `core` + 8 loader cells (mc-1.18.2/{fabric,forge}, mc-1.19.2/{fabric,forge}, mc-1.20.1/{fabric,forge}, mc-1.21.1/{fabric,neoforge})
Trigger: Owner report — (a) VG appears to log more vanilla events than CoreProtect (player joins cited); (b) a How-to-Train-Your-Dragon (HTTYD) dragon destroyed blocks + set fire, but only the **fire** logged and the fire was **non-rollbackable** because the source block-break was never recorded.

---

## 1. Executive summary

The investigation found that **the machinery the owner is asking for already exists and is tested end-to-end**. This is not a "build a universal hook" task — the universal hook, the universal attribution resolver, and `ENTITY_CHANGE_BLOCK` rollback are all present. The dragon bug is caused by a **deliberate fail-closed gate** (`VanillaGrieferSet`) plus an **un-attributed, un-paired fire capture** (`FireBlockMixin`). The fix is three targeted changes, not an architecture rewrite.

**Do not "log all entities."** The code carries a hard, measured constraint: on Forge/NeoForge, `LivingDestroyBlockEvent` is a *prospective permission-check* event that fires **200,000+ times/sec per flying dragon** on Berk/HTTYD packs and produced **5M+ queued actions in minutes** → `AsyncWriteQueue full` floods. The gate is fail-closed *for a reason*. Any fix must preserve flood safety.

---

## 2. What already exists (verified in source)

| Layer | File | Status |
|---|---|---|
| Universal capture (any LivingEntity breaks block) | `NeoForgeEvents.onLivingDestroyBlock` (line ~257) | ✅ present, gated |
| Universal attribution (rider/owner/projectile/damage/NBT via vanilla interfaces) | `NeoForgeAttributionResolver.resolveInner` | ✅ present — a **tamed** modded dragon resolves to its owner for free |
| `ENTITY_CHANGE_BLOCK` action type (id 26) | `core/action/ActionType.java` | ✅ present |
| `ENTITY_CHANGE_BLOCK` rollback → `setBlock` | `core/rollback/RollbackEngine.java` (case ~704) | ✅ present + tested (`RollbackExpansionParityTest`) |
| Flood mitigation coalescer (default 500ms / 8192 tracked) | `core/queue/EntityBlockChangeCoalescer.java` | ✅ present, config-driven |
| Per-vanilla-entity mixins (dragon/ravager/snowgolem…) | `mixin/*Mixin.java` | ✅ present — **supplements** for paths that bypass the Forge event (e.g. dragon `checkWalls`→`removeBlock`) |
| CP-parity per-event toggle table (13 flags) | `GuardianConfig.defaults()` (line ~654) | ✅ present, CP-aligned defaults |

**Conclusion:** the "general best-practice entity-grief logging + rollback" the owner wants is already the design. It is simply gated off for modded entities by default.

---

## 3. Root cause — the dragon bug

### 3a. Why the block breaks vanished
`VanillaGrieferSet.shouldRecord(entityKey, allowlist, logAll)` (core/filter) is **fail-closed**:
- `entityChangeLogAllEntities` default = `false` (config comment: *"DO NOT flip this"*)
- `entityChangeAllowlist` default = `[]`
- Hardcoded `DEFAULT_ALLOWLIST` = ~9 **vanilla** keys (`minecraft:ender_dragon`, `ravager`, `wither`, `enderman`, `silverfish`, `turtle`, `fox`, `zombie`, `falling_block`).

A HTTYD dragon (e.g. `isleofberk:night_fury`) matches none of these → `shouldRecord` returns `false` → the `ENTITY_CHANGE_BLOCK` row is dropped **before** the queue. The universal resolver never even runs. That is the missing block-break.

### 3b. Why the fire logged anyway (and was non-rollbackable)
`FireBlockMixin` submits `IGNITE`/`BURN` via `NeoForgeMixinBridge.fireIgnite/fireBurn` and is **not** entity-gated — it fires on genuine fire state changes regardless of igniter. So the dragon's fire produced an `IGNITE` row, but with:
- no entity attribution (fire capture doesn't carry the causing entity), and
- **no paired source block-break** (that was gated out in 3a).

Rollback of a lone `IGNITE` clears the fire block only (verified: `RollbackEngineTest.rollbackIgniteClearsTheCreatedFireBlock`). With no `BLOCK_BREAK`/`ENTITY_CHANGE_BLOCK` sibling, there is nothing to restore — the destroyed terrain stays gone. That is the "non-rollbackable" symptom: it wasn't that the fire couldn't be undone, it's that the *destruction under/around it* was never recorded to undo.

---

## 4. CoreProtect parity — findings

The owner's "we log more than CoreProtect (joins)" concern is **largely already addressed**:

- **Session join/leave IS CoreProtect parity.** CP records logins (`/co l u:<player>` surfaces session data). VG's `SESSION_JOIN`/`SESSION_LEAVE`/`USERNAME_CHANGE` (ids 12–14) mirror it 1:1, asserted by `CoreProtectFidelityTest`. This is *not* over-logging.
- **Noisy world events already ship CP-aligned defaults** — `GuardianConfig.defaults()` carries a 13-flag CP-parity table: `logWaterFlow`/`logLavaFlow` default **OFF** (per-tick, expensive — matches CP), tree/mushroom/vine/sculk growth + portals default **ON** (matches CP), fire-extinguish/campfire-start ON.

**Open evidence gap:** if the owner is seeing join/verbosity spam specifically, it is most likely **log-file verbosity** (`logs/vonixguardian` sink) rather than the audit DB action set. Need a `/vg lookup` (or log tail) sample to target the exact rows before changing any default. **No parity defaults will be changed without that sample.**

---

## 5. Proposed changes (3 targeted, flood-safe)

### C1 — Namespace/wildcard allowlist + admin command (opt-in modded coverage without the flood)
- Extend `VanillaGrieferSet.shouldRecord` to accept wildcard entries: exact `isleofberk:night_fury`, namespace `isleofberk:*`, or bare namespace `isleofberk`.
- Add `/vg entitylog add <key|ns:*>` / `remove` / `list` writing to `actions.entityChangeAllowlist` (live config reload path already exists — `GuardianReloadTest`).
- **Flood safety preserved:** the existing `EntityBlockChangeCoalescer` (500ms/8192) absorbs the prospective-event storm; whitelisting a whole dragon mod is safe because duplicate per-tick queries collapse in the coalescer window. Document that `logAllEntities` remains the unsafe escape hatch.

### C2 — Orphan-fire fix (never log an unrollbackable standalone fire)
- When entity-caused `IGNITE`/`BURN` originates from a *filtered* entity, either (a) suppress the orphan fire row too (symmetry with the gated break), or (b) attribute + pair it so rollback restores block AND clears fire together. Recommendation: **(b)** when the entity is allowlisted, **(a)** when it isn't — so a whitelisted dragon's fire is fully rollbackable and a non-whitelisted mob produces no misleading orphan.
- Requires threading the causing entity into the fire-capture bridge (currently `fireIgnite(level,pos,state)` has no actor).

### C3 — Parity defaults: HOLD pending owner's `/vg lookup` sample
- No change until the specific noisy rows are identified. Then adjust only those flags/log-sink verbosity, keeping the CP-parity table intact.

---

## 6. Per-cell change list (8 cells, strict parity)

Each loader cell needs the mirror of C1/C2:
- `core`: `VanillaGrieferSet` wildcard matching + tests; `/vg entitylog` command wiring in shared command layer.
- Per cell `*Events.java`: no change to the universal hook itself (gate lives in core).
- Per cell fire bridge (`NeoForgeMixinBridge.fireIgnite`/Fabric equivalents ×8): add causing-entity parameter for C2. **This is the widest touch** — 8 bridge signatures + 8 `FireBlockMixin` call sites. Older cells (1.18.2/1.19.2) use `Registry.*` API — verify sentinel resolution there.
- Cross-version safety: 1.21.1 API differences already handled elsewhere; re-verify `LivingDestroyBlockEvent`/`LivingDamageEvent.Pre` shape per MC version.

---

## 7. Test plan
- `VanillaGrieferSetTest`: add wildcard/namespace cases (`isleofberk:*` matches `isleofberk:night_fury`, does not match `minecraft:zombie`).
- New: allowlisted-dragon break → `ENTITY_CHANGE_BLOCK` recorded + rollback restores block.
- New: allowlisted-dragon fire → paired restore (block back + fire cleared).
- New: non-allowlisted mob fire → no orphan `IGNITE` row.
- Regression: prospective-event flood stays coalesced (assert coalescer collapses N same-cell queries in window).
- Full 8-cell build `-PbuildProfile=all` + existing `CoreProtectFidelityTest` stays green.

---

## 8. Open questions for owner
1. `/vg lookup` or log sample showing the join/over-logging you mean (blocks C3).
2. The exact HTTYD entity namespace (`isleofberk`? `dragonmounts`? other) — for the wildcard test fixture.
3. C2 preference: suppress orphan fire from non-whitelisted mobs, or always attribute+pair? (Recommendation: hybrid per §5-C2.)
