# VonixGuardian vs CoreProtect — Complete Difference Matrix

Snapshot: **VG @ `073a233`** on branch `integration/v1.2.0` (2026-07-02) — includes v1.1.6 through the first v1.2.0 integration wave.
CP reference: `docs.coreprotect.net` v23.x stable + v24 Patreon (auto-purge, migrate-db, CP-Fabric-Edition Patreon preview MC 26.1.2).

Legend: ✅ full parity · 🟡 partial · 🟥 stub / broken / dead / advertised-but-unwired · ❌ missing · ❓ unverifiable

---

## v1.2.0 refresh summary

This section supersedes stale v1.1.5 findings below when they conflict. The older matrix remains as detailed historical context until the whole file is regenerated row-by-row.

### Claims that flipped from missing/broken to implemented

- ✅ `/vg reload` now calls `Guardian.reloadConfig(...)` and reports hot-swapped/restart-required/error lists.
- ✅ `/vg undo` now executes the inverse rollback/restore plan for non-legacy entries instead of only popping history.
- ✅ `/vg migrate-db <sqlite|mysql|postgresql> CONFIRM` exists and routes through `MigrateDbCommand`; console-only guard is enforced.
- ✅ Auto-purge support landed in core in the v1.1.7 wave.
- ✅ Per-world overrides, `blacklist.txt`, granular child permissions, per-node op fallback, decimal/range time parsing, PreLogDispatcher, and sign schema v3→v4 all landed in v1.1.7.
- ✅ `target` width/schema migration issue was addressed after the v1.1.5 audit.
- ✅ `GuardianSuggestions.ACTIONS` now uses canonical `ActionTokens.ALL`, removing the parser/suggestion mismatch.
- ✅ Hanging place/break submit paths now exist on Forge/NeoForge and Fabric native paths.
- ✅ Fabric native event parity improved in v1.2.0: hanging place/break and async inspector left-click path were added to all four Fabric cells.
- ✅ PreLogEvent native bridges now exist on all 8 loader cells (Forge event bus, NeoForge cancellable event, Fabric array-backed event).
- ✅ Maven/publishing ecosystem work exists: `docs/PLUGINS.md`, `docs/API.md` Javadoc/index extension, and Maven Central publishing config in `core/build.gradle`.
- ✅ English i18n foundation exists: `Messages`, `lang/en_us.properties`, translator README, and QueryParser error extraction.

### Remaining CoreProtect gaps as of this snapshot

1. ❌ `/vg teleport` / `/vg tp` — not registered. Needs a per-player numbered lookup-result cache.
2. ❌ `/vg give` — not registered. Needs lookup-result cache plus loader-specific item reconstruction/give facade.
3. 🟡 Fabric still lacks true native coverage for several event families that Fabric API does not expose directly: real block place, mob block change, explosion detonate, piston pre, container open/close, bucket fill/empty, item drop/pickup/craft, sign edit, fire/ice/spread/dispense/leaves. The v1.2.0 Fabric mixin infrastructure is wired, but implementation mixins are not present yet.
4. 🟡 Forge/NeoForge world-event mixin infrastructure is wired, but no actual FireBlock/IceBlock/SpreadingSnowyDirtBlock/LeavesBlock/DispenserBlock mixin classes are present yet.
5. 🟡 NeoForge 1.21.1 bucket mixin infrastructure is wired, but BucketItem/MilkBucketItem mixin implementation is not present yet.
6. ❌ `r:#worldedit` still has no actual WorldEdit selection bridge.
7. 🟡 Language support is English-only; CP has broad translated bundles.
8. 🟡 Third-party ecosystem is documented but still nascent; CP has mature external integrations.

### Command audit pointer

See [`COMMAND-AUDIT-1.2.0.md`](./COMMAND-AUDIT-1.2.0.md) for the current `/vg` registration table: 16 wired command/alias entries, 0 stubs, 2 missing CoreProtect command surfaces.

---

## Field-report deltas since v1.1.5 (2026-07-01 nightshift)

Refresh pass triggered by the wiring-audit follow-up (`docs/WAVE-AUDIT-1.1.5.md`) and the CP-Berk field report at 08:05. Since the initial 07:45 write-up, the following claims in the tables below have flipped or gained new severity:

- 🚨 **NEW CRITICAL — `target VARCHAR(192)` is undersized (A11).** Chat, command, sign, and explosion submits all funnel through `Guardian.java:296-310` → `ActionBuilder.java:170` → `vg_actions.target VARCHAR(192) NOT NULL` (`Schema.java:180`). Any chat line > 192 UTF-8 bytes, any command with long arg list, any pasted sign, and any multi-entity-victim explosion serialization is silently truncated (SQLite) or hard-rejected (MySQL strict mode). CP's equivalent `message`/`data` columns are `TEXT`. See new "Schema comparison" subsection under §3.
- 🟥 **CONFIRMED — burn/ignite/fade/form/spread/dispense/leaves_decay handlers unwired (A8).** The wiring auditor's grep now has a matching bug ticket. `EventSubmitter` declares the API, config toggle `logWorldEvents=true` implies it's on by default, but **no** `@SubscribeEvent` handler in any of the 4 Forge/NeoForge cells fires those submits. The parity-table rows for `Burn`, `Ignite`, `Fade`, `Form`, `Spread`, `Dispense`, `LeavesDecay` in §2.6 are all 🟥, not ✅.
- 🟥 **CONFIRMED — HANGING_PLACE/BREAK rollback wired but no submit path (A10).** `RollbackEngine` explicitly handles both directions with per-type WARN/refuse, yet nowhere in the 4 cells is `submitHangingPlace` / `submitHangingBreak` called. The audit-only rows never materialize.
- 🟥 **CONFIRMED — NeoForge 1.21.1 `BUCKET_EMPTY`/`FILL` unwired (A9).** `FillBucketEvent` was removed upstream. TODO at `NeoForgeEvents.java:636`. CHANGELOG v1.0.4's "0% → 100% Forge-family coverage" is inaccurate for 1.21.1.
- 🚨 **CRITICAL still open — `RollbackPlan.isRollbackable` silent-default (A1).** Not new tonight but re-confirmed. 14 of 19 CHANGELOG-v1.0.3 rollback handlers remain dead code.

These map to Wave-2 subagent slots W2-01 (A1), W2-05 (A8/A9/A10 — this task), and W2-06 (A11) in `NIGHTSHIFT.md`. Everything below is the surgical delta from the 07:45 baseline; the rest of the doc is otherwise unchanged.

---

## Executive summary

**~85% of CP's user-visible command + filter + hashtag surface is bit-for-bit parity** on VG, verified line-by-line in `QueryParser.java` and `GuardianCommands.java`. VG's engine goes beyond CP in 5 concrete places (modded loaders, PostgreSQL, universal attribution chain, producer-side flood defense, 39-entry action taxonomy).

But the wave-1 audit surfaced **real bugs the CHANGELOG marketed as fixed** — most importantly the CRITICAL `RollbackPlan.isRollbackable` silent-default that swallows 14 of the 19 rollback handlers CHANGELOG v1.0.3 shipped. Details in the audit doc.

### Ahead of CP (real gains)

- 8 GA jars across Forge / NeoForge / Fabric × MC 1.18.2 → 1.21.1. CP ships zero GA modded jars; CP-Fabric is Patreon-only dev preview.
- **PostgreSQL** as a first-class backend (`Schema.Dialect.POSTGRES`).
- **Universal attribution chain** — `TamableAnimal → OwnableEntity → Projectile shooter → passenger → nearest recent interactor` with dual `actor_uuid` + `source_tag` columns and 19-entry `#sentinel` vocabulary. CP has nothing analogous.
- **Producer-side `EntityBlockChangeCoalescer`** (v1.1.4) for the modded `LivingDestroyBlockEvent` prospective-query flood (200k+/sec on HTTYD dragons). CP doesn't need it — Bukkit fires on genuine change only.
- **`VanillaGrieferSet`** (v1.1.5) ports CP's hardcoded `EntityChangeBlockListener` whitelist character-for-character (11 vanilla types) plus an admin-configurable `entityChangeAllowlist` for modded opt-in.
- 39-entry `ActionType` taxonomy (vs CP's ~20) covering hopper push/pull, structure grow, portal create, hanging place/break, entity interact, chunk populate.
- `viewothers` permission node — filter-scoping fallback so ops can be denied cross-user lookups.
- Rolling JSON-Lines side-channel audit log (`logs/vonixguardian/audit-YYYY-MM-DD.log`, gzip-rotated). CP has no equivalent.
- Fail-fast config validator with 17+ invariants (single aggregate `IllegalStateException` listing every problem).
- MIT license, full source on GitHub, no Patreon paywall.

### Behind CP (real gaps)

1. **`/vg reload` is a stub** (`GuardianCommands.java:540-541`) — literally prints "not implemented yet — restart the server". Every config field is currently restart-required in practice.
2. **`/vg undo` is a history-pop, not a world revert** (`GuardianCommands.java:457-470`) — semantic mismatch with CP's contract that `undo` reverses the last rollback/restore.
3. **`GuardianSuggestions.ACTIONS` L62-75 advertises 12 tokens the parser rejects** — `+kill`, `+chat`, `+command`, `+click`, `+sign`, `+username` and their `-` counterparts. Tab-complete → `QueryParseException("unknown action")` at execution time.
4. **`r:#worldedit` / `r:#we` accepted as no-op** (`QueryParser.java:272-278`) with in-code comment "leave radius/world unset". Silently returns global scope. No WorldEdit selection integration exists.
5. **`#optimize` accepted at parse layer but no-op at storage layer** — zero grep hits in `core/…/storage` for the flag.
6. **No `/co migrate-db` equivalent** (SQLite ↔ MySQL migration). Real gap.
7. **No auto-purge daemon** — CP has `auto-purge: 180d` + `auto-purge-time: 03:30`. VG requires cron.
8. **No per-world config overrides** (`world_the_nether.yml` shadowing root). VG's `worldBlacklist` is drop-all-events, not per-world knob overrides.
9. **No user/entity/command blacklist file** — CP's `blacklist.txt` supports `Notch`, `#tnt`, `/help`, `minecraft:creeper`, `minecraft:shears@#dispenser` composite filters. VG's blacklists are 3 exact-match JSON arrays (`worldBlacklist`, `blockBlacklist`, `sourceBlacklist`).
10. **No child permissions** — CP has 12 `coreprotect.lookup.<category>` negative-perm gates. VG has 1 `command.lookup` node. Op-level fallback grants **all** VG nodes with no granular alternative when LP is absent.
11. **No decimal / range time syntax** — CP accepts `t:2.50h` and `t:1h-2h`; VG accepts combined (`t:1d12h`) only.
12. **Sign edits via mixin** — CHANGELOG v1.0.4 P0 list flags front/back + dye + waxed (CP v24 columns) for the deferred 1.0.5 mixin wave. VG persists a single joined-lines string.
13. **Fabric BLOCK_PLACE + LivingDestroyBlock + explosion + piston + item toss/pickup/craft + sign edit — all deferred behind mixin wave.** Fabric currently piggybacks on `UseBlockCallback` (fires on right-click use, not real place). CHANGELOG v1.0.4 L145 / L589. This is 4 loader cells × 7 event families still missing.
14. **NeoForge 1.21.1 `BUCKET_EMPTY`/`BUCKET_FILL` unwired** — `FillBucketEvent` was removed upstream in NeoForge 1.21+. TODO at `NeoForgeEvents.java:636-638`. CHANGELOG v1.0.4 says "0% → 100% Forge-family coverage" but 1.21.1 is actually 0% on this pair. **[A9, re-confirmed 2026-07-01]**
15. **`EventSubmitter` declares `submitBurn`/`Ignite`/`Fade`/`Form`/`Spread`/`Dispense`/`LeavesDecay` — zero matching `@SubscribeEvent` handlers on any loader.** The entire vanilla world-events family is unwired. Config toggle `logWorldEvents = true` is a lie for these. See audit doc. **[A8, confirmed with matching W2-05 bug ticket 2026-07-01]**
16. **No PreLogEvent / no cancellability** (grep 0 hits for `PreLogEvent`, `Cancellable`, `GuardianEvent` in `core/`) — third-party mods cannot intercept or cancel a log the way CP allows via `CoreProtectPreLogEvent`.
17. **No published maven artifact** — `core/build.gradle:10` applies `maven-publish` but `publishing {}` block has 0 grep hits in the repo. Plugin loaded, never configured. GitHub Releases only.
18. **No `hasPlaced` / `hasRemoved` / `queueLookup` / `APIVersion()` / `testAPI()`** — CP API v12 helpers third-party plugins rely on.
19. **No per-family typed result classes** — CP has 9 typed result types (`BlockResult`, `ContainerResult`, `InventoryResult`, `ItemResult`, `MessageResult`, `SessionResult`, `SignResult`, `UsernameResult`, `ParseResult`) all implementing `CoreProtectResult`. VG has single `Action` sum-type + `ActionType` discriminator. Not wrong, just different — but does mean CP's API examples don't port 1:1.
20. **No language support** — CP has 100+ ISO codes via Google Translate. VG is English-only.
21. **Zero third-party integration ecosystem** — CP has ~20 documented integrations (WorldEdit, LightUp, Lumen, XRayHunter, ShadowTrace, Movecraft-CP, M0-CoreCord Discord bridge, Axiom Paper, BlocksHub, DesirePaths, Watson, CP Lookup Web UI, etc.). VG has zero.
22. **No `/co teleport` or `/co give`** — CP has both (permission-gated).
23. **Default IP-hashing salt** ships as literal placeholder; validator only WARNs. Trivially attackable.

### Deliberately not implementing (design choices, not gaps)

- **Bukkit/Spigot/Paper build** — CP owns that space; VG's reason to exist is the loaders CP doesn't cover.
- **Networking API** (`coreprotect.networking`) — out of scope for v1.x.
- **Hytale** — CP has a dedicated Hytale build; VG does not target.

---

## 1. Command surface (verified line-by-line)

### 1.1 Root command + aliases

| | CP | VG |
|---|---|---|
| Primary | `/co` | `/vg` (and `/guardian`) |
| Aliases | `/core`, `/coreprotect` (default off) | `/co`, `/guardian` (redirected to same Brigadier tree in v1.1.0) |
| Perm gate | `coreprotect.co` default true, `.core` and `.coreprotect` default false | `vonixguardian.command.use` (single root gate). `/co` alias has **no separate perm gate** on VG. |

### 1.2 Subcommand parity

| Subcommand | CP | VG | Notes |
|---|---|---|---|
| `help` | ✅ | ✅ | CP-style listing |
| `inspect` (`i`) | ✅ | ✅ | Left/right click semantics identical |
| `lookup` (`l`) | ✅ | ✅ | Same paginator `<page>` / `<page>:<perPage>` |
| `rollback` (`rb`) | ✅ | ✅ | Default `r:10` when positioned caller omits radius; console `r:<n>` forbidden |
| `restore` (`rs`) | ✅ | ✅ | |
| `purge` | ✅ | ✅ | Console ≥ 24h, in-game ≥ 30d (v1.1.0 CRITICAL fix — enforced in `PurgeEngine`) |
| `reload` | ✅ (fully reloads) | 🟥 stub (`GuardianCommands.java:540-541`) | Prints "not implemented yet — restart the server". Every config field is currently restart-required. |
| `status` | ✅ | ✅ | Both show version + queue state |
| `consumer pause/resume` | ✅ | ✅ + `toggle` | VG adds `toggle` |
| `migrate-db` | ✅ (Patreon 23+) | ❌ Not implemented | Real gap |
| `near` | ✅ (`r:5`) | ✅ (`r:5 t:1h`) | VG adds time default; CP has none on near |
| `undo` | ✅ (reverses last rollback/restore) | 🟥 history-pop only (`GuardianCommands.java:457-470`) | Never reverts world state — semantic mismatch |
| `teleport` | ✅ | ❌ | Not implemented |
| `give` | ✅ (default deny) | ❌ | Not implemented |

### 1.3 Filter tokens (verified in `QueryParser.java`)

**`u:` — user.** Both accept single, comma-separated multi, and `#sentinel` tokens. CP: `Notch`, `Notch,Intelli`, `#fire,#tnt,#creeper,#explosion`. VG: same plus structured `#mob:<ns>:<path>` sentinels (`EntitySentinel.java:19`).

**`t:` — time.**
| | CP | VG |
|---|---|---|
| Combined | ✅ `t:2w,5d,7h,2m,10s`, `t:5d2h` | ✅ (`QueryParser.java` — combined form) |
| Decimal | ✅ `t:2.50h` | 🟥 not implemented |
| Range | ✅ `t:1h-2h` | 🟥 not implemented |
| Suffixes | `s/m/h/d/w` | `s/m/h/d/w` |

**`r:` — radius.**
| | CP | VG |
|---|---|---|
| Numeric | ✅ | ✅ (console callers rejected — `QueryParser.java:312-314`) |
| `#global` | ✅ | ✅ |
| `#world_<key>` | ✅ | ✅ (passthrough at `QueryParser.java:286-293`) |
| `#worldedit` / `#we` | ✅ (reads real WE selection) | 🟥 accepted as no-op with in-code comment (`QueryParser.java:272-278`) — no WorldEdit-modded exists to bridge to. Silently returns global scope. |
| `#nether` / `#overworld` / `#end` | ❌ | ✅ mapped to canonical `minecraft:the_nether` / `minecraft:overworld` / `minecraft:the_end` (v1.1.0 HIGH fix — `QueryParser.java:282-284`) |

**`a:` — action.** Umbrellas + `+`/`-` sign variants. CP set (verbatim from snapshot §1): `block`, `+block`, `-block`, `chat`, `click`, `command`, `container`, `+container`, `-container`, `inventory`, `+inventory`, `-inventory`, `item`, `+item`, `-item`, `kill`, `session`, `+session` (alias `login`), `-session` (alias `logout`), `sign`, `username`. All 21 CP tokens present in VG's `ActionType.BY_TOKEN`. **Plus 21 VG-only expansion tokens** — see § 2.

**`i:` / `e:` — include / exclude.** Both accept comma-separated namespaced IDs (`minecraft:diamond_ore`, `create:andesite_alloy`). VG: exact-match only, no globs. CP v23+/v24 semantics same (also no globs).

### 1.4 Hashtag flags

| Flag | CP | VG | Notes |
|---|---|---|---|
| `#preview` | ✅ | ✅ | |
| `#count` | ✅ | ✅ | |
| `#verbose` | ✅ | ✅ | VG's `#verbose` also dumps `source_tag` + `actor_uuid` (modded attribution) |
| `#silent` | ✅ | ✅ | |
| `#optimize` | ✅ (MySQL purge only, 2.15+) | 🟡 parsed (`QueryParser.java:439`) but DAOs are all no-op — dead flag |

### 1.5 Actor sentinels

CP: `#fire`, `#tnt`, `#creeper`, `#explosion` (v24 adds `#dispenser` for filters).
VG: all 4 CP sentinels + structured `#mob:<ns>:<path>` family + `#natural`, `#natural:raid`, `#tnt:priming_actor` for attribution-chain results.

### 1.6 Tab completion coverage

Verified across all 8 loader cells (`GuardianCommands.java` @ 580-585 lines each, diff = 17 lines modulo package name / MC-version quirks).

🟥 **`GuardianSuggestions.ACTIONS` L62-75 advertises 12 tokens the parser rejects.** Tab-completion offers `+kill`, `-kill`, `+chat`, `-chat`, `+command`, `-command`, `+click`, `-click`, `+sign`, `-sign`, `+username`, `-username` — none of these exist in `ActionType.BY_TOKEN` (strict `HashMap.get`, `ActionType.java:180-184`). Users tab-completing → `QueryParseException("unknown action")` at execution. Genuine v1.1.0 miss (see audit doc for suggested fix).

---

## 2. Recorded action taxonomy

VG has **39 `ActionType` constants**, ids 1..39 dense. CP has ~20 documented (block/container/item/kill/chat/click/command/inventory/session/sign/username, plus v23+/v24 additions).

### 2.1 Overlap (16 actions both audit)

`BLOCK_PLACE`, `BLOCK_BREAK`, `CONTAINER_DEPOSIT`/`WITHDRAW`, `ITEM_DROP`/`PICKUP`, `INVENTORY_DEPOSIT`/`WITHDRAW`, `ITEM_CRAFT`, `ENTITY_KILL`, `CHAT`, `COMMAND`, `SIGN`, `SESSION_JOIN`/`LEAVE`, `USERNAME_CHANGE`, `CLICK`.

### 2.2 VG-only actions (23 modded/vanilla-griefing expansions)

`EXPLOSION` (as atomic row), `ENTITY_CHANGE_BLOCK`, `BURN`, `IGNITE`, `FADE`, `FORM`, `SPREAD`, `DISPENSE`, `PISTON_EXTEND`/`RETRACT`, `BUCKET_EMPTY`/`FILL`, `LEAVES_DECAY`, `HOPPER_PUSH`/`PULL`, `ENTITY_SPAWN`, `ENTITY_INTERACT`, `HANGING_PLACE`/`BREAK`, `STRUCTURE_GROW`, `PORTAL_CREATE`, `CHUNK_POPULATE`.

### 2.3 CP-only surface

- Sign v24 columns: `getColor`, `getColorSecondary`, `isFront`, `isFrontGlowing`, `isBackGlowing`, `isWaxed`. VG's `submitSign` only takes `joinedLines` (single string, `EventSubmitter.java:189-190`). Schema (`Schema.java:184`) only shows `source_tag VARCHAR(64)` extension. **Deferred P0** in CHANGELOG v1.0.4.
- `CoreProtectPreLogEvent` — public cancellable pre-log hook. VG has an internal `EventGate` filter but no public bus other mods can intercept.
- `blacklist.txt` composite filters (`id@user`).

### 2.4 Rollback-vs-record parity matrix

11 action types are **explicitly refused** by `RollbackEngine.applyInverse/applyForward` with per-type WARN messages (v1.0.3 fix removed the silent default branch): `DISPENSE`, `PISTON_EXTEND`, `PISTON_RETRACT`, `INVENTORY_DEPOSIT`, `INVENTORY_WITHDRAW`, `ITEM_CRAFT`, `ENTITY_SPAWN`, `ENTITY_INTERACT`, `CHUNK_POPULATE`, `CLICK`, plus `CHAT`/`COMMAND`/`SIGN`/`SESSION_*`/`USERNAME_CHANGE` (non-mutating audit-only). Verified in `RollbackEngine.java:311-329`.

Asymmetric: `HANGING_PLACE` rollback refused (no `WorldMutator.removeEntity`), forward reapplies. `HANGING_BREAK` rollback respawns, restore refused. Both flagged as TODO in CHANGELOG v1.0.3.

**🚨 CRITICAL BUG: `RollbackPlan.isRollbackable` (line 96-108) silently returns `false` for 14 of the 19 expansion types.** The plan filters them out at line 60-63 before the engine ever sees them, making all the case arms in `RollbackEngine.applyInverse` for `BURN`, `IGNITE`, `FADE`, `LEAVES_DECAY`, `BUCKET_FILL`, `FORM`, `SPREAD`, `BUCKET_EMPTY`, `STRUCTURE_GROW`, `PORTAL_CREATE`, `ENTITY_CHANGE_BLOCK`, `HOPPER_PUSH`, `HOPPER_PULL`, `HANGING_BREAK` **dead code**. CHANGELOG v1.0.3 marketed "19 new rollback handlers" — 14 of them cannot execute. See [`WAVE-AUDIT-1.1.5.md`](./WAVE-AUDIT-1.1.5.md#critical-1) for the fix.

### 2.5 Producer-side filtering (VG only)

- **`EntityBlockChangeCoalescer`** (v1.1.4): dedup by `(actor, world, x, y, z)` in a 500ms LRU-bounded window. Fires against Forge's `LivingDestroyBlockEvent` which is a **prospective-query** event (fires on every mob tick per block-in-collision-box, 200k+/sec per HTTYD dragon). CP-invisible: Bukkit's `EntityChangeBlockEvent` fires on genuine change only.
- **`VanillaGrieferSet`** (v1.1.5): CP's hardcoded `EntityChangeBlockListener` whitelist ported character-for-character (Enderman / EnderDragon / Wither / Ravager / Silverfish / Turtle / Fox / Zombie / FallingBlock / WindCharge / BreezeWindCharge). Admin allowlist `entityChangeAllowlist` for modded opt-in + `entityChangeLogAllEntities` escape hatch. Filters BEFORE attribution + coalescer.

### 2.6 Event-hook surface per loader

Full table in `/tmp/vg-vs-cp/wave1-02-actions.md § 7`. Summary:

| Event family | Forge 1.18.2 / 1.19.2 / 1.20.1 / NeoForge 1.21.1 | Fabric (all 4 MC) |
|---|---|---|
| Block place | ✅ `BlockEvent.EntityPlaceEvent` | 🟥 `UseBlockCallback` only (right-click use, NOT real place) — TODO(v0.2.0) mixin |
| Block break | ✅ | 🟥 (deferred, mixin) |
| Living destroy block | ✅ (with v1.1.5 whitelist gate) | 🟥 |
| Explosion detonate | ✅ | 🟥 |
| Piston pre | ✅ | 🟥 |
| Fill bucket | ✅ 1.18.2 / 1.19.2 / 1.20.1; **🟥 NeoForge 1.21.1** (event removed upstream, TODO L636-638) | 🟥 |
| Item toss / pickup / craft | ✅ | 🟥 |
| Container open / close | ✅ | 🟥 |
| Chat (HIGHEST + receiveCanceled) | ✅ verified in all 4 cells | ✅ |
| Sign change | 🟥 **all Forge/NeoForge** — CHANGELOG v1.0.4 P0, mixin wave | 🟥 |
| Hanging place / break | 🟥 refuse-rollback wired; **no submit path via grep** — audit-only rows may never populate. **[A10, confirmed 2026-07-01]** | 🟥 |
| **Burn / Ignite / Fade / Form / Spread / Dispense / LeavesDecay** | 🟥 **declared in `EventSubmitter` but zero matching `@SubscribeEvent` handlers** — the whole vanilla world-events family is unwired. **[A8, confirmed 2026-07-01]** | 🟥 |

The last row is a genuine bug — `EventSubmitter` promises the API surface, config toggle `logWorldEvents = true` implies it's on by default, but no listener ever fires the event. See audit doc.

---

## 3. Storage, schema, purge, queue

### 3.1 Backend matrix

| Backend | CP | VG |
|---|---|---|
| SQLite | ✅ default | ✅ default (`Schema.Dialect.SQLITE`) |
| MySQL | ✅ | ✅ (`mysql-connector-j` 8.4.0 JIJ'd since v1.1.1) |
| MariaDB | ✅ (via MySQL driver) | ✅ (same driver; v1.1.2 fixed the `CREATE INDEX IF NOT EXISTS` MariaDB-vs-MySQL dialect drift) |
| **PostgreSQL** | ❌ | ✅ (`postgresql` 42.7.4 JIJ'd since v1.1.1) — **VG uniqueness** |
| `/co migrate-db` (SQLite ↔ MySQL) | ✅ Patreon 23+ | ❌ Not implemented — real gap |
| Auto-purge daemon | ✅ Patreon 24+ (`auto-purge: 180d`, `auto-purge-time: 03:30`) | ❌ Not implemented — real gap |

### 3.2 Driver packaging (VG modded runtime)

- **Forge / NeoForge**: JarInJar (`jarJar` block per cell). Preserves JNI symbol path `Java_org_sqlite_core_NativeDB_*` (v1.0.0 fix).
- **Fabric**: Shadow with `exclude 'module-info.class'` + `META-INF/versions/*/module-info.class` (v1.0.1 hotfix for Sinytra Connector JPMS layer collision).
- `org.sqlite` never relocated.

### 3.3 Schema

VG has 5 tables + 5 indices (`Schema.java`), schema version `v2`. `stampVersion` rewritten in v1.1.2 to use MySQL-portable derived-table INSERT.

Column set on the main `vg_actions` table: `id`, `ts`, `world_id`, `x`, `y`, `z`, `action_id`, `actor_uuid`, `actor_name`, `source_tag`, `target_id`, `target_meta`. Indices on `(world_id, x, z, y, ts)` for hot-path queries, plus `(actor_uuid, ts DESC)`, `(action_id, ts DESC)`.

CP schema: not public in v24 docs beyond the API-exposed columns; treat as ❓.

### 3.3.1 Schema comparison — column widths (A11)

CP's `CP-DOCS-SNAPSHOT.md` and `/tmp/vg-vs-cp/wave1-03-storage-perf.md § 3.3` confirm that **CP does not publish column-level DDL** — full width is unknowable from the public docs alone. But the CP API's `String[]` result shape (snapshot §6) and the `MessageResult` / `SignResult` payloads (unbounded UTF-8 strings) strongly imply CP uses `TEXT` (SQLite / MySQL) — not a bounded `VARCHAR`. Any long chat line, pasted sign, or multi-arg command survives in CP.

VG's equivalent column is `vg_actions.target VARCHAR(192) NOT NULL` (`Schema.java:180`, verified in `wave1-03 § 3.1 line 56`). Every chat, command, sign, and explosion submit funnels through it via `ActionBuilder.java:170`.

| Source event | Payload size in the wild | VG column | Verdict |
|---|---|---|---|
| Chat (`CHAT`) | up to server chat cap, typically 256+ chars | `target VARCHAR(192)` | 🚨 truncated / rejected |
| Command (`COMMAND`) | `/execute` chains, JSON args → 500+ chars common | `target VARCHAR(192)` | 🚨 truncated / rejected |
| Sign (`SIGN`) | 4 lines joined; front+back+dye+waxed (v24) → 300+ chars | `target VARCHAR(192)` | 🚨 truncated / rejected |
| Explosion serialized victim list | N entity type strings joined | `target VARCHAR(192)` | 🚨 truncated / rejected |
| Block target namespaced ID (e.g. `minecraft:stone`) | ≤ 96 chars | `target VARCHAR(192)` | ✅ fits |

**Assessment.** VG's `VARCHAR(192)` is undersized vs the (assumed) CP `TEXT` reference for the 4 non-block action families above. Behavior differs by backend:
- SQLite: type-affinity is advisory — silently accepts the oversized string.
- MySQL / MariaDB (strict mode, default since 5.7): rejects the INSERT → row lost, WARN logged.
- PostgreSQL: hard-rejects with `22001 string_data_right_truncation` → row lost.

**Fix path** (Wave-2 W2-06 in `NIGHTSHIFT.md`): schema-version bump v2 → v3 with `ALTER TABLE vg_actions MODIFY target VARCHAR(4096)` (or `TEXT` on all dialects). Round-trip regression test for a 512-char chat submit.

### 3.4 Purge

- Safety floors identical (console 24h / in-game 30d).
- **VG enforces in-engine** at `PurgeEngine.java:53-56` — defense-in-depth vs CP's command-layer enforcement.
- World filter (`r:#world_<key>`) — both.
- Block filter (`i:stone,dirt` on purge) — CP v23+, VG same.
- `#optimize` — CP: real MySQL `OPTIMIZE TABLE`; VG: 🟡 accepted at parse but no-op in storage layer.

### 3.5 Queue architecture

CP: consumer queue with `pause` / `resume`.

VG: `BatchedAsyncWriteQueue` — bounded ring buffer, time-budgeted `poll()` (Kafka `linger.ms` pattern, v1.0.4 fix — before that flush only triggered on batch-full or `poll()` timeout, so a steady arrival rate kept the batch growing forever and `/vg lookup` returned stale data until restart). Rate-limited WARN, tick-never-blocked. `maxSize` default 50 000. `/vg status` exposes depth. Producer-side histogram diagnostic (v1.1.3-diag).

VG-only:
- **`EntityBlockChangeCoalescer`** — see § 2.5.
- Rollback-batch crash-recovery tables (undo/restore restarts survive server crashes).
- `/vg consumer toggle` (CP has pause/resume only).

### 3.6 MySQL dialect quirks VG had to fix

- `CREATE INDEX IF NOT EXISTS` MySQL 8 rejects; MariaDB accepts. Fix: bare `CREATE INDEX` + swallow error 1061 (v1.1.2).
- `SELECT ? WHERE NOT EXISTS` needs a `FROM`. Fix: derived-table form (v1.1.2).
- CP: unknowable — closed source.

---

## 4. Configuration model

| Feature | CP | VG |
|---|---|---|
| Format | YAML flat (`config.yml`) | JSON, 9-section carved (`config/vonixguardian/config.json`) |
| Validation | soft per-key defaulting | fail-fast aggregate `IllegalStateException` listing every problem |
| Per-world overrides | ✅ `world_the_end.yml` / `world_nether.yml` shadow-config | 🟥 not implemented — `worldBlacklist` is drop-all only |
| `blacklist.txt` (users, commands, blocks, entities, `id@user`) | ✅ (v23+ blocks, v24+ entities + filters) | 🟥 3 exact-match JSON arrays (`worldBlacklist`, `blockBlacklist`, `sourceBlacklist`) — no user, no entity, no commands, no globs, no composites. VG-only strength: `sourceBlacklist` (drop by `sourceTag`) |
| Language | ✅ 100+ ISO codes via Google Translate | 🟥 English-only |
| Live reload | ✅ (`/co reload` reads file) | 🟥 stub in 1.0.0 — every field is currently restart-required |
| Category toggles | ✅ | ✅ 11 booleans (`logBlocks`, `logContainers`, `logItems`, `logEntities`, `logExplosions`, `logChat`, `logCommands`, `logSessions`, `logSigns`, `logInteractions`, `logWorldEvents`) — note `logWorldEvents=true` is a lie today (see § 2.6 last row) |
| IP hashing | ❓ not in snapshot | ✅ `privacy.hashIps` + `privacy.salt` (default salt is trivially weak; validator only WARNs) |
| Config sub-blocks (VG only) | | `queue.maxSize/flushIntervalMs/batchSize`, `lookup.defaultPageSize/maxRadius/maxResultRows/maxConcurrent`, `purge.minAgeSecondsConsole/minAgeSecondsInGame`, `logFile.enabled/directory/gzipRotated/retentionDays` |
| Migration on load | ❓ not documented | ✅ `ConfigLoader.migrateForwardCompat` backfills 1.1.4 coalescer defaults + 1.1.5 allowlist to sensible values for pre-1.1.4/1.1.5 configs. Treats `0` as "unset"; negative to actually disable (deliberate footgun-prevention) |

---

## 5. Permissions

| | CP | VG |
|---|---|---|
| Base nodes | 13: `.*`, `.inspect`, `.lookup`, `.rollback`, `.restore`, `.teleport`, `.help`, `.purge`, `.reload`, `.status`, `.consumer`, `.give` (default false), `.networking` | 10: `.command.use`, `.command.inspect`, `.lookup`, `.rollback`, `.restore`, `.purge`, `.near`, `.undo`, `.status`, `.reload` |
| **Child nodes (negative-perm gates)** | **12** — `.lookup.block/chat/click/command/container/inventory/item/kill/near/session/sign/username` | 🟥 **0** — real gap |
| Command-handler nodes | 3 (`.co` default true, `.core` / `.coreprotect` default false) | 1 (`.command.use`) |
| `bypass` (suppress logging for holder) | ✅ | ✅ `vonixguardian.command.bypass` |
| `viewothers` (filter-scoping) | ❌ | ✅ `vonixguardian.command.viewothers` — VG-uniqueness |
| Teleport / give / networking | ✅ | ❌ (VG doesn't ship these subcommands) |
| Backend | Bukkit permissions (Vault / LP / PEX chain) | LuckPerms via **pure-reflection** soft-dep (`LuckPermsBridge` never imports `net.luckperms.*`), cached tri-state (`null` unprobed / `TRUE` / `FALSE`). Op-level fallback with `defaultOpLevel=3`. `IllegalStateException` deliberately not cached (transient LP-not-yet-registered case). |

**⚠ Op-level fallback anti-symmetry.** When LP is absent, VG's op-level check grants **all** nodes to any op ≥ `defaultOpLevel`. No granular fallback. CP has 12 child perms usable exactly for this granular-deny case. On a modded server without LP, VG has no way to give a moderator lookup-only.

---

## 6. Public Java API + third-party ecosystem

### 6.1 Method inventory

CP API v12 (~30 methods, per snapshot §6): `performLookup/Rollback/Restore`, `blockLookup(Block, LookupOptions)`, `containerLookup`, `itemLookup`, `inventoryLookup`, `chatLookup`, `commandLookup`, `signLookup`, `sessionLookup`, `usernameLookup`, `queueLookup(Block)`, `parseResult`, `logChat`, `logCommand`, `logPlacement(x2)`, `logRemoval(x2)`, `logContainerTransaction`, `logInteraction`, `hasPlaced`, `hasRemoved`, `performPurge`, `isEnabled`, `testAPI`, `APIVersion`.

VG (35 typed `submit*` methods on `EventSubmitter` — broader write side; single `dao().query(QueryFilter, offset, limit)` funnel on read side).

Gaps in VG relative to CP:
- `PreLogEvent` / cancellability — 🟥 grep 0 hits for `PreLogEvent`, `Cancellable`, `GuardianEvent` in `core/`
- `APIVersion()` / `testAPI()` — 🟥 missing
- `hasPlaced` / `hasRemoved` — 🟥 must count via blocking DAO
- `queueLookup` — 🟥 internal `BatchedAsyncWriteQueue` not exposed
- Per-family typed result types (9 of them) — 🟥 VG has single `Action` sum-type + `ActionType` discriminator
- Deliberately excluded from API: `performRollback/Restore/Purge` (per `docs/API.md:217`) — VG's public API is intentionally read + write-log, not rollback

Gains vs CP: blanket thread-safety on all writes; non-blocking `submit`; native typed `Action` records (no `parseResult` decode); UUID-first actor identity; producer-side coalescer.

### 6.2 Distribution

- CP: `maven.playpro.com`, group `net.coreprotect`, version 24.0.
- VG: **🟥 no published maven artifact.** `core/build.gradle:10` applies `maven-publish` but `publishing {}` block has 0 grep hits in the whole repo. Plugin loaded, never configured. GitHub Releases only.

### 6.3 Third-party ecosystem

CP has ~20 documented integrations: WorldEdit, CoreProtect-Anti-Xray, CoreProtect TNT, CoreProtect Time-Lapse (CPTL), LightUp, Lumen, FRTrustSystem, Movecraft-CoreProtect, M0-CoreCord (Discord bridge), XRayHunter, ShadowTrace (server + client), WildInspect, ExplosionProtector, Axiom Paper Plugin, BlocksHub, SpitSTIK, XRay Informer, DesirePaths, Watson, CoreProtect Lookup Web UI.

VG: 🟥 **zero third-party integrations.** Ecosystem is nascent.

---

## 7. Modded-runtime engineering (CP-invisible)

Every VG hotfix through v1.1.5 addressed a problem CP will never see because CP targets Bukkit's classloader model and event contracts, not vanilla MC + Forge/NeoForge/Fabric.

| Version | Symptom | Fix | CP-affected? |
|---|---|---|---|
| v1.0.0 | JNI relocation trap on shaded SQLite | JarInJar the driver; JNI symbol path preserved | ❌ (Bukkit differs) |
| v1.0.1 | Silent-boot Gson MR-jar `module-info.class` colliding with Sinytra Connector JPMS layer | Shadow exclude both top-level and `META-INF/versions/*/module-info.class` | ❌ |
| v1.0.1 | `MinecraftServer.getServerDirectory()` return-type drift under Sinytra Connector | Reflective `resolveServerDir` helper (accepts `File` or `Path`) | ❌ |
| v1.0.1 | `RegisterCommandsEvent` firing on `Worker-Main-*` before `Guardian.boot()` | Deferred-and-replay dispatcher pattern | ❌ (Bukkit registers via PluginManager) |
| v1.0.2 | `EntityType.getType()` SRG-vs-Mojmap flood on deep-modded packs | `MethodHandles.publicLookup()` chain + rate-limited WARN | ❌ |
| v1.0.3 | Silent `default -> false` in `RollbackEngine.applyInverse` | Explicit refusals per type | ❌ |
| v1.0.4 | `BatchedAsyncWriteQueue.runWorker()` flush-trigger bug — steady-state kept batch growing forever, `/vg lookup` returned stale until restart | Kafka `linger.ms` time-budgeted poll | ❌ |
| v1.0.4 | Cross-dimension lookup scoping (default world not folded) | `QueryParser.parse` folds caller's world when source is a player and `w:` absent | ❌ |
| v1.0.4 | `PISTON_RETRACT` never emitted (binary else branch on `PistonMoveType`) | Explicit `else if (RETRACT)` | ❌ |
| v1.0.4 | `ENTITY_SPAWN` flood from chunk-load reanimation | `ev.loadedFromDisk()` gate; surviving rows tag `sourceTag=spawn:join` | ❌ |
| v1.0.4 | `BUCKET_FILL`/`EMPTY` 0% Forge coverage | New `FillBucketEvent` handler | ❌ |
| v1.0.4 | Chat cancelled-events dropped by anti-spam mods | `@SubscribeEvent(priority=HIGHEST, receiveCanceled=true)` in all Forge/NeoForge cells | ❌ |
| v1.1.0 | `/vg purge` bypass of `PurgeEngine` safety floor (dao().purge() called directly) | Route through `purgeEngine().purge(filter, minAgeSeconds)` with source-based floor | ❌ |
| v1.1.0 | `r:#nether`/`#overworld`/`#end` stored as bare tokens instead of canonical `minecraft:the_nether` — silently returned zero rows | Canonicalize at parse time | ❌ |
| v1.1.0 | Tab-completion missed 12 tokens the parser accepts | New `GuardianSuggestions` | ❌ (but see § 1.6 — 12 tokens now advertised that the parser rejects — regression) |
| v1.1.1 | JDBC drivers not on classpath by default (Paper ships them; Forge/NeoForge don't) | JIJ `mysql-connector-j` + `postgresql` | ❌ |
| v1.1.2 | `CREATE INDEX IF NOT EXISTS` MySQL 8 rejects | Split DDL + swallow MySQL 1061 | Would affect CP if they hit MySQL 8 the same way; ❓ |
| v1.1.4 | HTTYD dragon 200k/sec `LivingDestroyBlockEvent` prospective-event flood | `EntityBlockChangeCoalescer` producer-side dedup | ❌ (Bukkit event fires on genuine change only) |
| v1.1.5 | Modded mob-griefing over-recording | `VanillaGrieferSet` whitelist port from CP + admin `entityChangeAllowlist` | ❌ |

Known unfixed: Sinytra Connector `Commands.literal()` remap breaks `/vg` command tree registration; VG's engine still functions (v1.0.1 known issue, no fix as of v1.1.5).

---

## 8. Attribution model divergence

VG's attribution chain (unique, no CP equivalent):

```
LivingEntity that changed the block
├── getControllingPassenger() → if player, actor_uuid = them
├── TamableAnimal → getOwnerUUID() → actor_uuid = them
├── OwnableEntity (Create contraptions, Dragon Mounts) → getOwnerUUID() → actor_uuid
├── Projectile → getOwner() recursion (arrow shot by tamed dragon = credit rider)
└── nearest recent interactor from AttributionResolver's LRU cache
```

Row schema: dual `actor_uuid` (nullable) + `source_tag` (`#mob:dragonmounts:fire_dragon`, `#natural`, `#tnt`, etc.). Lookups can filter on either side. `/vg lookup #verbose` dumps both columns.

**19-entry `#sentinel` vocabulary** in `Sentinel.java` for non-player attribution — vastly richer than CP's ~5 documented sentinels.

VG's `Attribution.resolver` — LRU cache keyed by entity ID → most-recent-interactor UUID + timestamp. Populated by `PlayerInteractEvent`, `AttackEntityEvent`, mount/dismount. Used as last-resort attribution when the ownership chain is empty.

---

## 9. Loader-glue architecture

```
core/                            Pure Java, no MC deps, 380 unit tests, 100% covered
                                 (JDBC, queue, log, query parser, rollback, config,
                                 attribution, filter, sentinels)
mc-<ver>/common/                 Per-MC shared surface (NBT codecs, command tree,
                                 event payload models). Mojmap.
mc-<ver>/{fabric,forge,neoforge}/    30-50 LOC glue — event subscriptions, mod entry
                                     point, permission bridge, world mutator impl.
buildSrc/                        Gradle convention plugins.
gradle/libs.versions.toml        Single version catalog.
```

No architectury runtime — `core` is plain Java and loader modules import it directly. Smaller jars, no abstraction tax. Pattern matches LuckPerms / FastBackups / Ledger.

**8 jars** in the CI matrix (Fabric×4 + Forge×3 + NeoForge×1).

---

## 10. Interop matrix

| Server type | Recommended | Why |
|---|---|---|
| Paper / Spigot / Purpur (vanilla Bukkit API) | **CP** | Native platform, mature, ~20 integrations |
| Fabric (any of 1.18.2/1.19.2/1.20.1/1.21.1) | **VG** (Fabric jar has known block-place gap deferred to mixin wave; use CP-Fabric Patreon dev preview only if MC target matches its 26.1.2 aim) | VG has GA cover; CP-Fabric is dev-only |
| Forge 1.18.2 / 1.19.2 / 1.20.1 | **VG** | CP has no Forge target |
| NeoForge 1.21.1 | **VG** | CP has no NeoForge target |
| Sinytra Connector (Forge+Fabric hybrid) | **VG** (NeoForge jar; command tree registration blocked by Connector's `Commands.literal()` remap until mixin wave — engine works, `/vg` doesn't) | Explicit VG hardening in v1.0.1; no CP equivalent |
| Mohist / Arclight (Forge+Bukkit hybrid) | **CP** likely (they emulate Bukkit) | VG doesn't target Bukkit |
| Hytale | **CP** (dedicated Hytale build) | VG doesn't target |

---

## References

- Wave 1 subagent reports: `/tmp/vg-vs-cp/wave1-01-commands.md`, `wave1-02-actions.md`, `wave1-03-storage-perf.md`, `wave1-04-config-perms.md`, `wave1-05-api-ecosystem.md`, `wave1-06-modded-runtime.md`
- Wave 2 adversarial wiring audit: `/tmp/vg-vs-cp/wiring-audit-report.md` (→ [`WAVE-AUDIT-1.1.5.md`](./WAVE-AUDIT-1.1.5.md))
- Frozen CP docs snapshot: `/tmp/vg-vs-cp/CP-DOCS-SNAPSHOT.md`
- CP live docs (may drift): https://docs.coreprotect.net/
