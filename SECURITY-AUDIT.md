# VonixGuardian — Security & Quality Audit (core/)

## 1. Audit metadata

| Field | Value |
|---|---|
| Date | 2026-06-27 (UTC) |
| Commit SHA | `02a863f219aadf6c7be49c814af099a9e56000c8` |
| Branch / status | working tree as of audit time |
| Main `.java` files in `core/src/main/java` | **49** |
| Test `.java` files in `core/src/test/java` | **15** |
| Total LOC (main sources) | **5,451** |
| `@Test` methods | **179** |
| `./gradlew :core:test --no-daemon` result | **BUILD FAILED — `:core:compileJava` does not compile.** No tests executed. See § 5 / § 12 for the showstopper. |

> The 179 `@Test`-method count comes from a static grep, not from a green Gradle run. The test SUITE cannot be exercised until the compile error below is fixed.

---

## 2. Threat model

**Asset:** the audit ledger (`vg_actions`) and the JSON-lines append-only forensic log. These exist *because* the MC server may be exploited; they must remain trustworthy when everything else has gone wrong.

**Protected properties (in priority order):**

1. **Audit integrity.** Rows in `vg_actions` reflect what happened, with stable enum-id semantics, immune to malformed input and modded class-loading surprises. The JSON-lines file is the second, independent copy.
2. **Rollback correctness.** A `/vg rollback` does not destroy world state irrecoverably; restore is the symmetric inverse.
3. **No exfiltration of operator credentials or PII** beyond what the server already exposes. Player UUIDs are not secrets; raw IPs are toggle-gated and hashed by default? — see § 9 (it is opt-IN, not opt-out, per `Privacy.hashIps=false` default).
4. **No remote attack surface.** The mod jar exposes no network listener, no RMI, no JMX endpoint, no HTTP server. The only external trust boundaries are (a) the SQL backend's JDBC URL/creds in `config.json`, and (b) command-line tokens typed into `/vg` by an in-game player who already has command access.

**Out of scope:**
- DDoS / amplification against the underlying Minecraft server.
- The network layer (this jar opens no sockets).
- Compromise of the JDBC backend itself.
- Operator who already has filesystem access to `config.json`.

**Implicit trust boundary:** anything reaching `Guardian.submit(...)` is trusted (loader has already vetted that this is a real server event). `/vg lookup` filter strings are untrusted — they are typed by a player with command permission and must survive adversarial input safely. This is the principal attacker model exercised in § 3.

---

## 3. SQL injection / query-layer review

**Files reviewed:** `core/src/main/java/network/vonix/guardian/core/storage/QueryCompiler.java`, `core/src/main/java/network/vonix/guardian/core/storage/jdbc/AbstractJdbcDao.java`, `core/src/main/java/network/vonix/guardian/core/query/QueryParser.java`.

### Findings

- **No user input is string-concatenated into SQL.** Every user-controlled value (`u:`, `t:`, `r:`, `i:`, `e:`, action ids) flows to `binds.add(...)` and out through `setInt/setLong/setShort/setString` in `AbstractJdbcDao.bind()` (`AbstractJdbcDao.java:341-351`).
- **The only string-concatenated SQL fragments are static literals** (`SELECT_PROJECTION`, `FROM_JOIN`, the placeholder lists). The whitelist `COLUMN_WHITELIST` (`QueryCompiler.java:25-29`) is declared but is **never actually consulted at runtime** — it is documentation, not enforcement. That's acceptable because no code path currently splices a column name from user input; if a future change does, the whitelist must be wired in explicitly. Flagged as a low-priority hygiene item.
- **`vg_actions` `markRolledBack` UPDATE** builds an `id IN (?,?,...)` clause from `ids.size()`, and binds each id with `setLong` (`AbstractJdbcDao.java:181-198`). Safe.

### Radius bounding-box overflow — **CONCERN**

`QueryCompiler.appendWhere` does:

```java
binds.add(f.centerX() - r);
binds.add(f.centerX() + r);
```
(`QueryCompiler.java:118-126`)

`r` is a parsed `int`. `QueryParser.parseRadiusTok` accepts any value from `Integer.parseInt(...)` ≥ 0 (`QueryParser.java:281-296`), so `r` can legally be `Integer.MAX_VALUE`. `centerX() + r` then silently overflows to a negative value and `centerX() - r` overflows to a positive one, so the predicate `a.x BETWEEN low AND high` becomes nonsense (low > high → empty result; or the inverted pair leaks past intended bounds, depending on backend).

**No clamping is applied in either layer.** `GuardianConfig.Lookup.maxRadius` exists (`SHARED-CONTRACTS.md` § 9, defaults to a finite value) but is not consulted by either `QueryParser` or `QueryCompiler`. The contract for default is "10" but the user can override.

Impact: low security (no injection), but the predicate can be silently misbehaved, and on Postgres a `WHERE a.x BETWEEN <overflowed> AND <overflowed>` may execute an inefficient seq scan.

**Fix before v1.0:** clamp `r` in `QueryParser` to `cfg.lookup.maxRadius`, OR cast to `long` in `QueryCompiler` and bind as `long`, OR reject parse if `centerX ± r` would overflow.

### IN-clause list-size limits — **CONCERN**

`QueryParser` imposes **no upper bound** on the number of comma-separated entries for `u:`, `i:`, `e:`, or `a:`. The whole input is parsed into `b.addUser(...)`, `b.addInclude(...)` etc. without size checks (`QueryParser.java:125-159`, `336-352`). A `u:a1,a2,a3,...,aN` with N very large is reflected into `placeholders(n)` (`QueryCompiler.java:160-167`) and into the bind list.

Two real-world risks:
1. JDBC drivers cap parameter counts (MySQL 65535, Postgres 65535, SQLite ~999 by default). A 10k-name `u:` token causes a driver-level reject rather than a clean parse error.
2. Memory: each token expands to a `String` per entry plus a placeholder `?` per entry. No DoS in practice on a single command, but the contract spec implies a max.

**Fix before v1.0:** enforce a per-token cap (e.g. 64) in `QueryParser.parseUserTok` / `parseIdentList`, raising `QueryParseException`. Also a single-pass cap on total tokens.

### Verdict

**CONCERN.** No SQL injection — every value is bound. But two unbounded inputs (radius `r` and list sizes) need clamping. Neither is exploitable for data exfiltration; both are exploitable for "make the query do something unintended."

---

## 4. Permission resolver review

**Files reviewed:** `core/src/main/java/network/vonix/guardian/core/perms/PermissionResolver.java`, `LuckPermsBridge.java`, `OpLevelFallback.java`.

- **No `import net.luckperms.*` anywhere.** Confirmed by grep across `core/src/main/java`: zero matches for `^import (net\.luckperms|net\.minecraft|net\.minecraftforge|net\.fabricmc|net\.neoforged)`. The bridge uses only the constant `"net.luckperms.api.LuckPermsProvider"` as a string (`LuckPermsBridge.java:43`), loaded via `Class.forName(..., loader)`.
- **NoClassDefFoundError / ClassNotFoundException → cached permanent FALSE.** `PermissionResolver.has()` `catch (NoClassDefFoundError)` → `markUnavailable(...)` which sets `lpAvailable = Boolean.FALSE` and logs once (`PermissionResolver.java:60-62, 121-126`). Correct.
- **IllegalStateException does NOT cache.** `PermissionResolver.java:63-67` explicitly sets `lpAvailable = null` (re-probe next call). Correct.
- **On fallback failure → DENY.** `checkOpLevel` returns `false` on `RuntimeException` from `OpLevelFallback.getOpLevel(...)` (`PermissionResolver.java:81-87`). Good. If LP returns `UNDEFINED`, we fall to op-level; if op-level fallback throws, we deny. **No path returns `true` from an exception.** Confirmed.
- **LP API method chain.** `LuckPermsProvider.get()` → `LuckPerms.getUserManager()` → `UserManager.getUser(UUID)` → `User.getCachedData()` → `CachedDataManager.getPermissionData()` → `CachedPermissionData.checkPermission(String)` → `Tristate`. This chain has been stable in the public LP API since 5.0 (LP 5.x landed mid-2019 with this exact contract). Across MC 1.18.2 → 1.21.1 nothing here has been renamed. Verified with `LuckPermsBridge.java:86-128`.
- **Tristate enum mapping** uses `((Enum<?>) tristate).name()` (`LuckPermsBridge.java:155-174`), then falls back to `toString()` on `ClassCastException`. Robust against shaded copies.
- **`mapTristate` treats `UNDEFINED` as the catch-all default**, so an unknown future name does not accidentally become `TRUE`. Good fail-closed posture.

### Verdict

**PASS.** Reflection contract is correct, fail-closed, and stable across the supported LP versions. One stylistic note: `LuckPermsBridge.checkPermission` catches `NoClassDefFoundError` via `InvocationTargetException.getCause()` but doesn't catch a raw `NoClassDefFoundError` thrown by the *outer* `getMethod` calls — in practice these are pre-validated by `Class.forName(...,true,...)` which would have already thrown, so the gap is theoretical.

---

## 5. Crash-avoidance review (modded griefing) — **FAIL: compilation broken**

**Files reviewed:** `core/src/main/java/network/vonix/guardian/core/event/EventGate.java`, `event/Sentinel.java`, `action/ActionType.java`.

### Showstopper

`ActionType` was extended in v0.1.0 to 39 entries (`ActionType.java:31-81`), including the new modded-griefing path `ENTITY_CHANGE_BLOCK(26)` and 24 other vanilla-griefing additions. **`EventGate.typeEnabled` (`EventGate.java:80-92`) only switches on the original 14 entries.** The compiler rejects this as a non-exhaustive switch expression:

```
EventGate.java:81: error: the switch expression does not cover all possible input values
        return switch (t) {
```

The entire `:core:compileJava` task fails. No production code links; **no test in the project has been executed at this commit.** This blocks every other section's runtime verification.

### Per-task checklist (against current code)

- **Sentinel coverage of modded griefing.** `Sentinel.ALL` carries the 20 frozen strings from `SHARED-CONTRACTS.md` § 8 (`Sentinel.java:19-65`). The task description references a `#mob:*` family-form sentinel for unloaded mod entities — **this is NOT in the contract, NOT in `Sentinel.ALL`, NOT in the parser**. Either the contract is the source of truth (and `#mob:*` belongs out-of-scope for v0.1.0), or the contract must be amended. Today there is no `#mob:`-prefix handling anywhere. Flagged for parent decision.
- **EventGate null defensiveness.** `shouldLog(null)` returns false (`EventGate.java:59-62`). It dereferences `a.type()`, `a.worldId()`, `a.targetId()`, `a.sourceTag()`. The `Action` record contract says `timestamp + type + worldId` are non-null; the others may be null. `worldBlacklist.contains(a.worldId())` would NPE if `worldId` were null but the contract forbids it. `sourceTag()` is null-guarded explicitly. `targetId()` is dereferenced inside `blockBlacklist.contains(...)` without a null-check — this would NPE for a BLOCK_PLACE/BLOCK_BREAK with a null target; the loader contract requires non-null target for these but no defensive guard exists. Low risk, but a hostile/buggy modded loader path could blow up the queue worker thread. **Recommend** adding `a.targetId() != null &&` to line `EventGate.java:70-71`.
- **Blacklists are O(1) HashSet.** `EventGate.freeze` materialises each list into a `HashSet<>` (`EventGate.java:50-52`). Confirmed O(1) lookup, not O(n) scan.

### Third-party mod imports

Grep result for `^import (net\.luckperms|net\.minecraft|net\.minecraftforge|net\.fabricmc|net\.neoforged)` across `core/src/main` returned **zero** matches. No third-party loader or mod classes are referenced by name in core/. LuckPerms remains the only soft-dep and is purely reflective. **PASS** on that specific contract.

### Verdict

**FAIL** — the engine does not compile because `EventGate.typeEnabled()` does not handle the 25 new `ActionType` entries that the contract froze for v0.1.0. This is the single highest-priority must-fix in the repo. Until then, the engine cannot ship.

---

## 6. Concurrency review

**Files reviewed:** `BatchedAsyncWriteQueue.java`, `RollbackEngine.java`, `JsonLinesLogFile.java`, `PermissionResolver.java`, `UndoStack.java`, `AbstractJdbcDao.java`, `SqliteDao.java`.

### Shared mutable state

| Class | State | Guard |
|---|---|---|
| `BatchedAsyncWriteQueue` | `queue` (ArrayBlockingQueue), `dropped`/`permanentlyDropped`/`lastDropLogNs` (AtomicLong), `shutdown`/`closed` (volatile booleans), `worker` (final) | Lock-free; queue is thread-safe; volatile/atomic on the rest. |
| `RollbackEngine` | None (stateless after construction). | — |
| `JsonLinesLogFile` | `writer`, `channel`, `fileLock`, `currentDate` | `ReentrantLock lock` held for every public method. |
| `PermissionResolver` | `lpAvailable` (volatile Boolean) | `synchronized(this)` for write, double-checked read. |
| `UndoStack` | `stacks` Map | every method `synchronized`. |
| `SqliteDao` | `connection` (volatile) | `ReentrantLock lock`. |
| `AbstractJdbcDao` | `userIdByUuid/Name`, `worldIdByKey` (ConcurrentHashMap) | thread-safe map. |

### Worker daemon-ness — **PASS**

`BatchedAsyncWriteQueue` ctor sets `this.worker.setDaemon(true)` unconditionally (`BatchedAsyncWriteQueue.java:79`), overriding whatever the loader-supplied `ThreadFactory` returned. The JVM will not be held open by this thread. Good.

### Shutdown races

- `BatchedAsyncWriteQueue.drainAndFlush(...)` short-circuits if `closed`, sets `shutdown=true`, interrupts the worker, joins with deadline, then drains anything left to the sink (`BatchedAsyncWriteQueue.java:97-128`). `submit(...)` after shutdown still calls `queue.offer(...)` — a producer racing past the shutdown signal will land its action in the queue *after* the final drain executed, where it is silently lost. The comment in `submit` acknowledges this (line 86-89). Acceptable trade-off; documented.
- `JsonLinesLogFile.close()` is idempotent in effect because `closeCurrent()` null-checks each field (`JsonLinesLogFile.java:159-189`). Calling it twice is safe.
- `SqliteDao.close()` sets `connection = null` after closing; a second `close()` is a no-op. Safe.
- `Guardian.close()` (`Guardian.java:305-324`) flushes queue → log → DAO in order, swallowing exceptions per step. Correct ordering: producers (queue) must finish before consumers (DAO) close.

### PermissionResolver double-checked locking — **PASS**

Reads `lpAvailable` via the field (volatile), early returns if non-null. Otherwise enters `synchronized` block and re-checks. Standard idiom. Safe under the Java memory model.

### Race risks identified

1. **`PermissionResolver.markUnavailable` is `synchronized`** (`PermissionResolver.java:121-126`) but is called from inside `has()` *without* holding `this`. That is by design — the synchronized method acquires the monitor. Fine.
2. **`JsonLinesLogFile` rotate at UTC midnight while writes are concurrent.** Rotation is only triggered inside `append(...)` under the same lock. Safe.
3. **`BatchedAsyncWriteQueue.runWorker` final drain block** (lines 184-190) runs after the `while` loop exits. If `drainAndFlush` interrupts the worker mid-batch, the catch block flushes the partial batch then breaks; the final drain then pulls anything else. Coverage looks complete.

### Verdict

**PASS** for the implemented classes. Daemon flag correct. No deadlock pattern found (no class takes more than one lock; the queue lock is independent of the file lock).

---

## 7. Resource lifecycle review

### AutoCloseable implementations

- `Guardian` (`Guardian.java:55`) — idempotent: subsequent `close()` calls would re-flush an empty queue (returns early via `closed` flag in the queue) and re-close idempotent log/DAO. Acceptable.
- `BatchedAsyncWriteQueue` — `close()` → `drainAndFlush(30_000L)`; the latter has a `closed` guard. Idempotent.
- `JsonLinesLogFile` — `close()` → `closeCurrent()` which is null-safe on each field. Idempotent.
- `GuardianDao` (interface) — `SqliteDao` nulls the connection, `PostgresDao` / `MysqlDao` check `ds.isClosed()` (`PostgresDao.java:59-62`). Idempotent.

### JDBC try-with-resources

- `AbstractJdbcDao.insertBatch` (`AbstractJdbcDao.java:67-117`) wraps `PreparedStatement` in try-with-resources. **`Connection` is acquired via `borrow()` and released in `finally { release(c); }`** — appropriate for SQLite's locked-connection pattern and for Hikari's pooled-connection pattern. The Connection itself is not in a try-with-resources block, but `release(...)` does the right thing per impl.
- Every `ResultSet` is in a try-with-resources block. Confirmed for `query`, `count`, `resolveUserOn`, `resolveWorldOn`.
- One small thing: `Hikari-backed DAOs` `release(c)` calls `c.close()` (returning to pool). Net effect identical. Safe.

### JsonLinesLogFile flush on close

`closeCurrent()` calls `writer.flush()` before `writer.close()` (`JsonLinesLogFile.java:159-172`) and then forces the FileChannel via `flush()` semantics… actually `closeCurrent()` calls flush on the writer but NOT `channel.force(false)`. `BufferedWriter.close()` flushes the underlying stream, and the OutputStream-on-Channel close should flush too, but no explicit `fsync` happens on close. The public `flush()` method DOES `channel.force(false)` (`doFlush()` lines 101-113). For a hard crash between last `append` and `close`, last batch may be lost from OS page cache.

**Recommendation:** call `doFlush()` from `closeCurrent()` before closing the writer. Minor durability improvement; tagged as a low-priority hygiene item.

### Verdict

**PASS.** Lifecycle is clean. One durability nit (no `fsync` on close path).

---

## 8. Rollback correctness review

**Files reviewed:** `RollbackEngine.java`, `RollbackPlan.java`, `RollbackResult.java`.

- **Ordering newest-first.** `RollbackPlan.build` sorts descending by timestamp, tiebreak descending id (`RollbackPlan.java:50-53`). Correct.
- **Position dedup.** Uses `PosKey(worldId, x, y, z)` HashSet; the **first** sighting (newest, post-sort) wins; subsequent older events at the same slot are dropped (`RollbackPlan.java:57-72`). Correct.
- **Non-positional types are skipped, not deduped** (`RollbackPlan.java:60-63, 96-106`). Correct — `Action.isPositional()` returns false for CHAT/COMMAND/SESSION_*/USERNAME_CHANGE.
- **Preview mode.** `RollbackEngine.execute(...)` returns early on `preview` BEFORE `dispatchBatches` or `dao.markRolledBack` (`RollbackEngine.java:142-144`). World untouched, DB untouched. Correct.
- **Restore symmetry.**
  - `BLOCK_PLACE` forward = `setBlock(targetId, targetMeta)`; inverse = `setBlock(AIR)`. (`RollbackEngine.java:218-219, 247-248`). Symmetric.
  - `BLOCK_BREAK` forward = `setBlock(AIR)`; inverse = `setBlock(targetId, targetMeta)`. Symmetric.
  - `CONTAINER_DEPOSIT` inverse = `removeFromContainer`; forward = `giveOrDrop`. Symmetric.
  - `CONTAINER_WITHDRAW` inverse = `giveOrDrop`; forward = `removeFromContainer`. Symmetric.
  - `ITEM_DROP`/`ITEM_PICKUP` — symmetry roughly mirrored. Acceptable (best-effort by design).
  - `ENTITY_KILL` — inverse = `respawnEntity`; forward = no-op with a debug log. Asymmetric but the comment explains; the "re-kill the respawn" semantics is not meaningful. Defensible.
  - `EXPLOSION` — inverse rebuilds blocks from the embedded list; forward clears them. Symmetric via the same parser.
- **`vg_actions.rolled_back` marking.** `dao.markRolledBack(affectedIds, true)` after dispatching (RB) or `false` (restore), inside the same transaction context of the DAO (`RollbackEngine.java:150-157`). The action of *dispatching* the mutation runs async on the main-thread executor; the DB flag flips immediately to record intent. This is correct given the contract ("DB state reflects intent, not landing").
- **Crash-recovery batches table.** The task asks whether a `batches` table exists for crash recovery. **There is no `vg_batches` table.** The only persistence is the `vg_actions` table itself, written transactionally per `insertBatch` (`AbstractJdbcDao.java:74-110`: `setAutoCommit(false)` + `commit()` / `rollback()`). After `kill -9` mid-batch, the partial transaction rolls back; queued-but-unwritten Actions in memory are lost. The JSON-lines log file is the parallel forensic copy. **There is no per-batch durability log on disk by design.** Consistent with the contract but tagged in § 11 as a gap before v1.0.

### Verdict

**PASS** on correctness of the implemented logic. ENTITY_KILL restore is intentionally a no-op. No formal crash-recovery journal exists — see § 11.

---

## 9. Logging review

### Marker coverage — **CONCERN**

Counts via grep (`LOG\.(info|warn|error|debug)\(` across `core/src/main`):

| Class | Statements | All carry MARKER? |
|---|---|---|
| `BatchedAsyncWriteQueue` | 7 | yes |
| `Guardian` | 7 | yes |
| `JsonLinesLogFile` | 6 | yes (`LogRotator.MARKER`) |
| `LogRotator` | 3 | yes |
| `RollbackEngine` | **8** | **NO — none carry a marker** |
| `ConfigLoader` | 2 | **NO** |
| `GuardianConfig` | 1 | **NO** |
| `PermissionResolver` | 5 | **NO** |
| `LuckPermsBridge` | 3 | **NO** |

So 19 of 42 log statements omit a marker. The contract in § 10 of `SHARED-CONTRACTS.md` does not explicitly mandate markers, but the convention in `Guardian.MARKER`/`LogRotator.MARKER`/`BatchedAsyncWriteQueue.MARKER` clearly intends an engine-wide marker prefix. Recommend a `Guardian.MARKER` (already declared `public static`) be applied to the bare statements. Low risk; cosmetic.

### PII at INFO/WARN

- Player UUIDs appear in `PermissionResolver.java:66, 85` at debug/warn. UUIDs are not PII per the threat model (§ 2.3).
- IPs/hostnames are handled by `IpHasher` and only entered into the SESSION_JOIN `targetId` field — they never reach the SLF4J logger. Confirmed: no `LOG.*` line interpolates an IP.
- The audit log (`JsonLinesLogFile`) is the only place raw IP can land, gated by `Privacy.hashIps` (default **false**, i.e. raw IP). That is explicitly an operator opt-in to PII, with a runtime warning when the default salt is still in place (`GuardianConfig.java:331`). Acceptable but **the default of `hashIps=false` is the wrong default for a privacy-conscious shipping product** — flagged in § 11.

### Stack traces

Spot-checked: `BatchedAsyncWriteQueue.java:111` `LOG.warn(..., ie)` passes throwable to WARN. `Guardian.java:310-322` passes throwables at WARN. `PermissionResolver.java:85` passes throwable at WARN. `LuckPermsBridge.java:139, 142, 146` log at DEBUG. **No exception trace is buried at TRACE/DEBUG-only.** PASS.

### Rate-limited queue-full WARN

`BatchedAsyncWriteQueue.maybeLogDrop` uses an `AtomicLong lastDropLogNs` and a 1-second window (`DROP_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1)`); the CAS pattern ensures at most one WARN per second across all racing producers (`BatchedAsyncWriteQueue.java:221-231`). Correct.

### Verdict

**CONCERN (marker hygiene).** Behaviour is correct; convention is inconsistent.

---

## 10. Test coverage review

**Note:** the suite does not compile (§ 5), so the figures below are static counts only.

| Package | Source classes (public) | Tests files | `@Test` methods | Tests / class |
|---|---|---|---|---|
| `action` | 3 (`Action`, `ActionBuilder`, `ActionType`) | 2 | 19 | 6.3 |
| `config` | 4 (`ConfigLoader`, `GuardianConfig`, `IpHasher`, package-info) | 2 | 16 | 4.0+ |
| `event` | 4 (`EventGate`, `EventSubmitter`, `Sentinel`, pkg) | 1 | 10 | 2.5 |
| `logfile` | 2 (`JsonLinesLogFile`, `LogRotator`) | 1 | **2** | **1.0** ← weak |
| `perms` | 3 (`PermissionResolver`, `LuckPermsBridge`, `OpLevelFallback`) | 2 | 14 | 4.7 |
| `query` | 3 (`QueryFilter`, `QueryParser`, `QueryParseException`) | 1 | 53 | 17.7 |
| `queue` | 4 (`AsyncWriteQueue`, `BatchedAsyncWriteQueue`, `BatchSink`, pkg) | 1 | 5 | 1.25 ← weak |
| `rollback` | 6 (`RollbackEngine`, `RollbackPlan`, `RollbackResult`, `UndoStack`, `WorldMutator`, pkg) | 2 | 25 | 4.2 |
| `storage` | 7 (`GuardianDao`, `QueryCompiler`, `Schema`, `StorageFactory`, three JDBC impls) | 2 | 17 | 2.4 |
| `theme` | 2 (`Theme`, `ThemeRegistry`) | 1 | 6 | 3.0 |
| `command` | 3 (`CommandSpec`, `SubcommandSpec`, `ArgumentSpec`) | 1 | 12 | 4.0 |
| `Guardian` (top-level) | 1 | 0 | 0 | **0** ← no facade test |

### Heuristic findings

- **Weak coverage packages (<2 tests/class):** `logfile` (2 tests for 2 classes including the gnarly rotator), `queue` (5 tests for the worker + retry + drop counters — the single most concurrency-sensitive class in the codebase), `Guardian` facade has no test at all.
- **`Thread.sleep` in tests:** one occurrence, `BatchedAsyncWriteQueueTest.java:138` — used *inside the mock sink* to deliberately stall a worker thread for retry-backoff measurement, not as a wait-for-completion mechanism. The wait-for-completion sites use `CountDownLatch` correctly (5 occurrences). Acceptable usage of `sleep`.
- **`@Disabled` tests:** zero. Good.
- **`QueryParserTest` is huge** (53 `@Test` methods) — that's an appropriately paranoid level for a hand-written parser.

### Verdict

**CONCERN.** `queue/` and `logfile/` need more tests, and the `Guardian` facade has zero. The compile failure means **none of the 179 tests actually run today.**

---

## 11. Known gaps / TODOs

Honest list of what is NOT in v0.1.0 and ought to be addressed before v1.0:

1. **Compilation. The build is broken** (§ 5). This dwarfs everything else.
2. **`EventGate.typeEnabled` does not handle the 25 new ActionTypes** added in the v0.1.0 contract bump (BURN, IGNITE, FADE, … through CLICK and the modded `ENTITY_CHANGE_BLOCK`).
3. **Radius is not clamped** against `GuardianConfig.Lookup.maxRadius` (§ 3). Overflow possible.
4. **Per-token list size is not capped** in `QueryParser` (§ 3).
5. **`#mob:*` sentinel family** referenced in the audit brief is NOT in the contract OR the parser. Either the contract needs amending or the brief is wrong; today the modded-mob sentinel surface is the existing 20 frozen strings only.
6. **Metrics export.** No Prometheus / micrometer / scrape endpoint. `/vg status` is the only visibility; no exported counter or gauge. AtomicLongs (`submitted`, `gated`, `dropped`, `permanentlyDropped`) are accessible only through internal getters.
7. **LP-API version compatibility matrix.** No CI matrix against multiple LuckPerms versions; we trust the reflection to be future-proof. Recommend a tiny smoke fixture against LP 5.0.x, 5.3.x, 5.4.x.
8. **No native backup/restore of `vg_actions.db`.** SQLite backup API not exposed. The JSON-lines log is the only second copy and it is not a structured replacement.
9. **No crash-recovery journal (`vg_batches` table or similar).** A `kill -9` mid-batch loses everything queued in memory; the JSON-lines log lessens the impact but does not preserve schema. See § 8.
10. **WorldEdit integration (`r:#we` / `r:#worldedit`)** is parsed but ignored (`QueryParser.java:256-262`). The loader side must wire selection lookup; today the token is a silent no-op.
11. **`EXPLOSION` target field `"x:y:z=id|meta"` scaling.** The contract caps the field at 4 KiB (`SHARED-CONTRACTS.md` § 8 — "comma-joined affected-block list (truncated to 4KB)"). A vanilla TNT explosion fits trivially; a 10000-block creeper-cluster blast does not. At 13 bytes/entry minimum (`x:y:z=` plus a 6-char id and a comma), the 4 KiB limit caps around ~300 block entries. **Document this hard limit** in user docs; consider lifting to 64 KiB or moving the explosion entry list to a sidecar table (`vg_explosion_blocks`) for v1.0.
12. **No `fsync` on JsonLinesLogFile.close()** — durability nit (§ 7).
13. **Marker hygiene** — 19/42 log statements lack the engine marker (§ 9).
14. **`Privacy.hashIps=false` default** — ships plain-text IPs by default. Privacy-conscious shops want the opposite default.
15. **Whitelist enforcement** in `QueryCompiler.COLUMN_WHITELIST` — declared, not consulted. Add a static guard if a future change ever splices a column name.

---

## 12. Verdict

| Section | Status |
|---|---|
| 1. Audit metadata | Green |
| 2. Threat model | Green |
| 3. SQL / query compiler | **Yellow** — no injection; radius and list sizes unbounded |
| 4. Permission resolver | Green |
| 5. Crash-avoidance / EventGate | **Red — engine does not compile** |
| 6. Concurrency | Green |
| 7. Resource lifecycle | Green |
| 8. Rollback correctness | Green (no crash-recovery journal — green per contract, see § 11) |
| 9. Logging | Yellow — marker hygiene |
| 10. Test coverage | Yellow — 179 tests written, 0 executing today; `queue`/`logfile`/`Guardian` weak |
| 11. Known gaps | (informational) |

### Top 3 must-fix before v1.0

1. **Fix `EventGate.typeEnabled` to cover all 39 ActionTypes** (`EventGate.java:80-92`). Without this, `:core:compileJava` fails and the entire engine is dead in the water. This is non-negotiable.
2. **Clamp the radius and cap list sizes in `QueryParser`** (`QueryParser.java:281-296` and `parseUserTok`/`parseIdentList`). Pass `GuardianConfig.Lookup.maxRadius` into the parser; reject `>maxRadius`; cap any comma list at e.g. 64 entries. Closes the only two unbounded user-input vectors in the codebase.
3. **Get the test suite green and add facade + queue + logfile coverage.** After (1), run `./gradlew :core:test`; raise `BatchedAsyncWriteQueueTest` and `JsonLinesLogFileTest` from 5/2 to ≥10 each; add a `GuardianTest` exercising boot/close round-trip with an in-memory SQLite DAO. Until tests run, every claim above stays unverified at runtime.
