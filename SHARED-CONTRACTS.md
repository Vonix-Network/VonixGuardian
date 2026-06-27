# VonixGuardian — Shared Contracts

**This document is the single source of truth for cross-module type names, method signatures, table schemas, JSON shapes, command surfaces, and event payloads.** Every subagent dispatched on this build MUST quote from this file verbatim — do NOT invent field names, NOT paraphrase, NOT improvise.

If a contract changes, the parent edits this file BEFORE dispatching the next wave. Subagents never edit this file.

Version: **v0.1.0**
Status: **frozen for v0.1.0** (changes require parent-only edit + wave re-dispatch).

---

## 1. Package layout

```
network.vonix.guardian
├── core/                    (everything in `core/` module)
│   ├── Guardian             ← top-level facade (parent will write; subagents reference)
│   ├── action/              ← ActionType enum + Action record + ActionBuilder
│   ├── storage/             ← DAO interface, SQLite/MySQL/Postgres impls, schema, queries
│   ├── queue/               ← AsyncWriteQueue + BatchFlusher
│   ├── logfile/             ← JsonLinesLogFile + Rotator
│   ├── config/              ← GuardianConfig (Gson-serializable)
│   ├── query/               ← QueryFilter + QueryParser (the /vg lookup mini-language)
│   ├── rollback/            ← RollbackEngine + RestoreEngine + UndoStack
│   ├── perms/               ← PermissionResolver + LuckPermsBridge (reflection)
│   ├── theme/               ← Theme + ThemeRegistry (chat colour palettes)
│   └── command/             ← CommandSpec + ArgumentSpec (loader-agnostic command tree)
│
└── mc.<ver>/                (one tree per MC version, in mc-<ver>/common/src/main/java)
    ├── common/              ← MC-version-specific bridges (NBT codecs, brigadier wiring)
    ├── fabric/              ← Fabric loader entrypoint + event subs
    ├── forge/               ← Forge loader entrypoint + event subs
    └── neoforge/            ← NeoForge loader entrypoint + event subs (1.21.1 only)
```

---

## 2. Core types

### 2.1 ActionType (enum)

> **Locked for v0.1.0. Stable integer IDs MUST NOT be reordered.** This list is now 1:1 with the CoreProtect listener surface plus the modded-entity griefing path (`ENTITY_CHANGE_BLOCK`). Adding new types appends to the end with the next free integer.

```java
package network.vonix.guardian.core.action;

public enum ActionType {
    // --- core block events (1-2) ---
    BLOCK_PLACE             ( 1, "+block",      Category.BLOCK,     Sign.PLACE),
    BLOCK_BREAK             ( 2, "-block",      Category.BLOCK,     Sign.BREAK),
    // --- player container surface (3-4) ---
    CONTAINER_DEPOSIT       ( 3, "+container",  Category.CONTAINER, Sign.PLACE),
    CONTAINER_WITHDRAW      ( 4, "-container",  Category.CONTAINER, Sign.BREAK),
    // --- item flow (5-6) ---
    ITEM_DROP               ( 5, "-item",       Category.ITEM,      Sign.BREAK),
    ITEM_PICKUP             ( 6, "+item",       Category.ITEM,      Sign.PLACE),
    // --- entity surface (7) ---
    ENTITY_KILL             ( 7, "kill",        Category.ENTITY,    Sign.NEUTRAL),
    // --- environment (8) ---
    EXPLOSION               ( 8, "explosion",   Category.WORLD,     Sign.BREAK),
    // --- communication (9-11) ---
    CHAT                    ( 9, "chat",        Category.MESSAGE,   Sign.NEUTRAL),
    COMMAND                 (10, "command",     Category.MESSAGE,   Sign.NEUTRAL),
    SIGN                    (11, "sign",        Category.MESSAGE,   Sign.NEUTRAL),
    // --- session (12-14) ---
    SESSION_JOIN            (12, "+session",    Category.SESSION,   Sign.PLACE),
    SESSION_LEAVE           (13, "-session",    Category.SESSION,   Sign.BREAK),
    USERNAME_CHANGE         (14, "username",    Category.SESSION,   Sign.NEUTRAL),
    // --- v0.1.0 expansion: vanilla griefing surface (15-26) ---
    BURN                    (15, "burn",        Category.BLOCK,     Sign.BREAK),
    IGNITE                  (16, "ignite",      Category.BLOCK,     Sign.PLACE),
    FADE                    (17, "fade",        Category.BLOCK,     Sign.BREAK),
    FORM                    (18, "form",        Category.BLOCK,     Sign.PLACE),
    SPREAD                  (19, "spread",      Category.BLOCK,     Sign.PLACE),
    DISPENSE                (20, "dispense",    Category.BLOCK,     Sign.NEUTRAL),
    PISTON_EXTEND           (21, "+piston",     Category.BLOCK,     Sign.PLACE),
    PISTON_RETRACT          (22, "-piston",     Category.BLOCK,     Sign.BREAK),
    BUCKET_EMPTY            (23, "+bucket",     Category.BLOCK,     Sign.PLACE),
    BUCKET_FILL             (24, "-bucket",     Category.BLOCK,     Sign.BREAK),
    LEAVES_DECAY            (25, "decay",       Category.BLOCK,     Sign.BREAK),
    /** Mob/dragon/ravager-driven block change. THE modded griefing path. */
    ENTITY_CHANGE_BLOCK     (26, "entityblock", Category.BLOCK,     Sign.NEUTRAL),
    // --- v0.1.0 expansion: player inventory + crafting (27-31) ---
    INVENTORY_DEPOSIT       (27, "+inventory",  Category.CONTAINER, Sign.PLACE),
    INVENTORY_WITHDRAW      (28, "-inventory",  Category.CONTAINER, Sign.BREAK),
    HOPPER_PUSH             (29, "+hopper",     Category.CONTAINER, Sign.PLACE),
    HOPPER_PULL             (30, "-hopper",     Category.CONTAINER, Sign.BREAK),
    ITEM_CRAFT              (31, "craft",       Category.ITEM,      Sign.NEUTRAL),
    // --- v0.1.0 expansion: entities (32-35) ---
    ENTITY_SPAWN            (32, "spawn",       Category.ENTITY,    Sign.PLACE),
    ENTITY_INTERACT         (33, "einteract",   Category.ENTITY,    Sign.NEUTRAL),
    HANGING_PLACE           (34, "+hanging",    Category.ENTITY,    Sign.PLACE),
    HANGING_BREAK           (35, "-hanging",    Category.ENTITY,    Sign.BREAK),
    // --- v0.1.0 expansion: world events (36-38) ---
    STRUCTURE_GROW          (36, "grow",        Category.WORLD,     Sign.PLACE),
    PORTAL_CREATE           (37, "portal",      Category.WORLD,     Sign.PLACE),
    CHUNK_POPULATE          (38, "populate",    Category.WORLD,     Sign.PLACE),
    // --- v0.1.0 expansion: generic interaction (39) ---
    CLICK                   (39, "click",       Category.INTERACT,  Sign.NEUTRAL);

    public enum Category { BLOCK, CONTAINER, ITEM, ENTITY, WORLD, MESSAGE, SESSION, INTERACT }
    public enum Sign     { PLACE, BREAK, NEUTRAL }

    // standard ctor + getters
    public int      id();
    public String   token();
    public Category category();
    public Sign     sign();

    public static ActionType byId(int id);          // throws IllegalArgumentException on miss
    public static ActionType byToken(String token); // throws IllegalArgumentException on miss

    /** Returns all ActionTypes in a Category — used by /vg lookup `a:block` family expansion. */
    public static java.util.Set<ActionType> family(Category cat);
}
```

### 2.2 Action (record)

```java
package network.vonix.guardian.core.action;

import java.util.UUID;

/**
 * Immutable record of a single logged event. All fields are nullable EXCEPT
 * timestamp + type + worldId.
 *
 * @param id          DB-assigned row id; -1 before insertion
 * @param timestamp   epoch millis (UTC)
 * @param type        action type
 * @param actorUuid   player UUID if known; null for non-player sources (creeper, lava, etc.)
 * @param actorName   resolved name at time of event ("Notch") or a sentinel string — see § 8 for the canonical sentinel list (e.g. "#creeper", "#tnt", "#lava", "#fall", "#unknown")
 * @param worldId     world / dimension key as string ("minecraft:overworld", "minecraft:the_nether", ...)
 * @param x,y,z       block coords; for non-positional events use 0,0,0 and isPositional()=false
 * @param targetId    string ID of the affected thing — block id ("minecraft:stone"), entity type ("minecraft:zombie"), item id, or message body for chat/command/sign
 * @param targetMeta  optional NBT/state snapshot as compact JSON string; null if N/A
 * @param amount      stack count for item/container events; 1 for block events; 0 if N/A
 * @param rolledBack  true if this action has been undone by a rollback
 * @param sourceTag   optional source classifier ("explosion:tnt", "death:fall", "drop:death") or null
 */
public record Action(
    long id,
    long timestamp,
    ActionType type,
    UUID actorUuid,
    String actorName,
    String worldId,
    int x, int y, int z,
    String targetId,
    String targetMeta,
    int amount,
    boolean rolledBack,
    String sourceTag
) {
    public boolean isPositional() {
        return switch (type) {
            case CHAT, COMMAND, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE -> false;
            default -> true;
        };
    }
}
```

### 2.3 ActionBuilder

Standard fluent builder. Default values:
- `id = -1L`
- `timestamp = System.currentTimeMillis()`
- `amount = 1`
- `rolledBack = false`
- `actorName = "#unknown"` if not set AND `actorUuid == null`

---

## 3. Storage layer

### 3.1 SQL schema (canonical — do NOT diverge per backend; use ANSI-compatible types)

```sql
CREATE TABLE IF NOT EXISTS vg_users (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,    -- SQLite; SERIAL on Postgres; BIGINT AUTO_INCREMENT on MySQL
    uuid         CHAR(36)     NULL,                    -- nullable for synthetic sources (#creeper)
    name         VARCHAR(64)  NOT NULL,
    first_seen   BIGINT       NOT NULL,
    last_seen    BIGINT       NOT NULL,
    UNIQUE(uuid),
    UNIQUE(name)                                       -- enforced loosely; rename via /vg lookup a:username
);

CREATE TABLE IF NOT EXISTS vg_worlds (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    world_key    VARCHAR(96)  NOT NULL UNIQUE          -- "minecraft:overworld" — column renamed from `key` to dodge MySQL reserved word
);

CREATE TABLE IF NOT EXISTS vg_actions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts           BIGINT       NOT NULL,
    type         SMALLINT     NOT NULL,                -- ActionType.id
    user_id      INTEGER      NOT NULL,                -- FK vg_users.id
    world_id     INTEGER      NOT NULL,                -- FK vg_worlds.id
    x            INTEGER      NOT NULL,
    y            INTEGER      NOT NULL,
    z            INTEGER      NOT NULL,
    target       VARCHAR(192) NOT NULL,                -- block id, entity type, item id, or message
    meta         TEXT         NULL,                    -- JSON
    amount       INTEGER      NOT NULL DEFAULT 1,
    rolled_back  TINYINT      NOT NULL DEFAULT 0,
    source_tag   VARCHAR(64)  NULL
);

CREATE INDEX IF NOT EXISTS vg_actions_pos    ON vg_actions(world_id, x, z, y, ts);
CREATE INDEX IF NOT EXISTS vg_actions_user_t ON vg_actions(user_id, ts);
CREATE INDEX IF NOT EXISTS vg_actions_type_t ON vg_actions(type, ts);
CREATE INDEX IF NOT EXISTS vg_actions_ts     ON vg_actions(ts);
```

> Each backend impl substitutes its own auto-increment dialect at table-create time. All other DDL is identical.

### 3.2 DAO interface

```java
package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.query.QueryFilter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface GuardianDao extends AutoCloseable {

    /** Initialize schema, run migrations if needed. Blocking. */
    void init() throws Exception;

    /** Insert a batch on the calling thread (driven by AsyncWriteQueue from a worker). */
    int insertBatch(List<Action> batch) throws Exception;

    /** Synchronous query — runs on calling thread (caller is a worker, not the server thread). */
    List<Action> query(QueryFilter filter, int offset, int limit) throws Exception;

    /** Row count for the same filter — used by /vg lookup #count. */
    long count(QueryFilter filter) throws Exception;

    /** Mark a set of action IDs as rolled-back (used by RollbackEngine). */
    int markRolledBack(List<Long> ids, boolean rolledBack) throws Exception;

    /** Delete records matching filter (purge). Returns rows deleted. */
    long purge(QueryFilter filter) throws Exception;

    /** Resolve / insert user, return user_id. */
    int resolveUser(UUID uuid, String name) throws Exception;

    /** Resolve / insert world, return world_id. */
    int resolveWorld(String key) throws Exception;

    /** Health check. */
    boolean isHealthy();

    @Override void close();
}
```

### 3.3 Backend selection (config)

`GuardianConfig.database.type` ∈ `{"sqlite", "mysql", "postgresql"}`. Factory in `StorageFactory.open(GuardianConfig)` returns the appropriate impl. MySQL/Postgres impls are `compileOnly` in `core/` so the runtime jar can omit drivers it doesn't need (the loader jar shades only the selected driver via Gradle Shadow — TODO post-Wave 1).

For v0.1.0, ship SQLite as the only fully-tested backend; MySQL/Postgres impls land but are documented as **beta**.

---

## 4. QueryFilter + parser

### 4.1 QueryFilter (record)

```java
package network.vonix.guardian.core.query;

import java.util.List;
import java.util.UUID;

public record QueryFilter(
    List<UserSel> users,                  // u:Notch,Intelli  OR  u:#creeper,#tnt
    Long sinceMillis,                     // t:1h -> System.currentTimeMillis() - 3_600_000
    Long untilMillis,                     // t:1h-2h upper bound
    Integer radius,                       // r:10  (null = default 10; -1 = #global)
    WorldSel worldSel,                    // r:#world_nether OR null
    Integer centerX, Integer centerY, Integer centerZ,
    List<ActionSelect> actions,           // a:block, a:+container, etc.
    List<String> include,                 // i:stone,oak_log
    List<String> exclude,                 // e:tnt,dirt
    Boolean rolledBack,                   // SQL-side filter. null = either; TRUE = only rolled back; FALSE = only not rolled back
    boolean countOnly,                    // #count
    boolean preview,                      // #preview
    boolean verbose,                      // #verbose
    boolean silent                        // #silent
) {
    public record UserSel(UUID uuid, String name, boolean isSentinel) {}
    public record WorldSel(String worldKey, boolean global) {}
    public record ActionSelect(network.vonix.guardian.core.action.ActionType type, Sign sign) {
        public enum Sign { ANY, PLACE_ONLY, BREAK_ONLY }
    }
}
```

### 4.2 Grammar

```
filter      := token (S token)*
token       := userTok | timeTok | radiusTok | actionTok | includeTok | excludeTok | hashTok
userTok     := "u:" ident ("," ident)*
timeTok     := "t:" duration ("-" duration)?     ; duration = (\d+(?:\.\d+)?)(w|d|h|m|s)+
radiusTok   := "r:" (\d+ | "#global" | "#world_<key>" | "#we" | "#worldedit")
actionTok   := "a:" sign? actionName             ; sign in {+,-}, actionName ∈ token column of ActionType
includeTok  := "i:" ident ("," ident)*
excludeTok  := "e:" ident ("," ident)*
hashTok     := "#" ("preview" | "count" | "verbose" | "silent")
ident       := player name | "#sentinel" (creeper/tnt/fire/lava/water/piston/fall/unknown/explosion)
```

Default radius if no `r:` is given: **10**. Default time: no bound. Default action: ALL.

---

## 5. AsyncWriteQueue contract

```java
package network.vonix.guardian.core.queue;

public interface AsyncWriteQueue {
    /** Non-blocking. Drops with WARN log if queue at maxSize. */
    void submit(network.vonix.guardian.core.action.Action a);

    /** Force flush of pending batch. Called on server stop. */
    void drainAndFlush(long timeoutMs);

    int depth();      // for /vg status

    /** Total dropped due to backpressure. */
    long dropped();
}
```

Implementation: bounded `ArrayBlockingQueue<Action>` (`config.queue.maxSize`); single worker thread polling with timeout `config.queue.flushIntervalMs`; flushes when batch reaches `config.queue.batchSize` OR interval elapses. On shutdown, `drainAndFlush` empties the queue with a 30s budget then aborts with WARN.

---

## 6. JsonLinesLogFile contract

Writes one JSON object per line, file per UTC day at `config.logFile.directory/audit-YYYY-MM-DD.log.jsonl`. Rotated at UTC midnight, gzipped if `config.logFile.gzipRotated`. Retention enforced lazily on rotation.

```json
{"ts":1735200000000,"type":"BLOCK_BREAK","actor":{"uuid":"...","name":"Notch"},"world":"minecraft:overworld","pos":[123,64,-456],"target":"minecraft:diamond_ore","amount":1,"source":null,"meta":null}
```

The log file is **append-only** and **independent of the DB**. Use case: forensics when the DB is corrupted.

---

## 7. Command tree (loader-agnostic spec)

```java
package network.vonix.guardian.core.command;

/**
 * Each MC version's :common module translates this into brigadier
 * (1.18.2 uses an older brigadier surface; 1.21.1 uses the modern one).
 */
public final class CommandSpec {
    // Top-level: /vg with alias /guardian
    public static final String ROOT = "vg";
    public static final String ALIAS = "guardian";

    // Subcommands
    public static final String INSPECT  = "inspect";
    public static final String LOOKUP   = "lookup";
    public static final String ROLLBACK = "rollback";
    public static final String RESTORE  = "restore";
    public static final String PURGE    = "purge";
    public static final String NEAR     = "near";
    public static final String UNDO     = "undo";
    public static final String STATUS   = "status";
    public static final String RELOAD   = "reload";
    public static final String HELP     = "help";
}
```

Permission node format: `vonixguardian.command.<sub>` (e.g. `vonixguardian.command.rollback`).

---

## 8. Loader-side event payloads (the bridge)

What the loader modules (fabric/forge/neoforge) feed into `Guardian.submit(Action)`:

| MC event | ActionType | actor resolution | targetId |
|---|---|---|---|
| BlockBreakEvent       | BLOCK_BREAK       | player UUID or `#explosion:<cause>` / `#piston` / `#fluid:<id>` | block registry id |
| BlockPlaceEvent       | BLOCK_PLACE       | player UUID or `#piston` | block registry id |
| ContainerSlotChange   | CONTAINER_DEPOSIT/WITHDRAW (sign by delta) | player UUID | item registry id (`amount`=delta) |
| ItemTossEvent         | ITEM_DROP         | player UUID | item registry id |
| ItemPickupEvent       | ITEM_PICKUP       | player UUID | item registry id |
| LivingDeathEvent      | ENTITY_KILL       | damage source actor (player UUID or sentinel) | entity type registry id |
| ExplosionDetonate     | EXPLOSION         | source entity sentinel | comma-joined affected-block list (truncated to 4KB) |
| ServerChatEvent       | CHAT              | player UUID | message body |
| CommandEvent          | COMMAND           | player UUID | full command including leading slash |
| SignChangeEvent       | SIGN              | player UUID | joined lines (`\n` separator) |
| PlayerJoinEvent       | SESSION_JOIN      | player UUID | client IP (hashed if `config.privacy.hashIps`) |
| PlayerQuitEvent       | SESSION_LEAVE     | player UUID | reason |
| PlayerProfileChange   | USERNAME_CHANGE   | player UUID | `oldName -> newName` |

Sentinel string list (frozen): `#creeper`, `#tnt`, `#end_crystal`, `#wither_skull`, `#fireball`, `#bed`, `#respawn_anchor`, `#fire`, `#lava`, `#water`, `#piston`, `#fall`, `#drown`, `#suffocate`, `#magic`, `#zombie`, `#skeleton`, `#enderman`, `#explosion`, `#unknown`.

---

## 9. ConfigSpec (JSON shape)

See `README.md` § Configuration. Field types:

```java
public record GuardianConfig(
    Database database,
    Queue queue,
    LogFile logFile,
    Actions actions,
    Permissions permissions,
    Lookup lookup,
    Privacy privacy,
    Purge purge,
    String theme
) {
    public record Database(String type, String file,
                           String jdbcUrl, String user, String password) {}
    public record Queue(int maxSize, long flushIntervalMs, int batchSize) {}
    public record LogFile(boolean enabled, String directory,
                          boolean gzipRotated, int retentionDays) {}
    public record Actions(boolean logBlocks, boolean logContainers, boolean logItems,
                          boolean logEntities, boolean logExplosions, boolean logChat,
                          boolean logCommands, boolean logSessions, boolean logSigns,
                          boolean logInteractions, boolean logWorldEvents,
                          List<String> worldBlacklist, List<String> blockBlacklist,
                          List<String> sourceBlacklist) {}
    public record Permissions(boolean useLuckPerms, int defaultOpLevel) {}
    public record Lookup(int defaultPageSize, int maxRadius, int maxResultRows, int maxConcurrent) {}
    /** IP hashing for SESSION_JOIN. salt must be >= 16 chars when hashIps is true. */
    public record Privacy(boolean hashIps, String salt) {}
    /** Minimum age in seconds for /vg purge. CoreProtect parity: 86400 from console, 2592000 in-game. */
    public record Purge(long minAgeSecondsConsole, long minAgeSecondsInGame) {}
}
```

Defaults match `README.md` plus:
* `actions.logInteractions = true`, `actions.logWorldEvents = true`
* `lookup.maxResultRows = 100_000`, `lookup.maxConcurrent = 4`
* `privacy.hashIps = false`, `privacy.salt = "vonix-guardian-default-salt-CHANGE-ME"` (validator WARN if default in production)
* `purge.minAgeSecondsConsole = 86_400`, `purge.minAgeSecondsInGame = 2_592_000`

Gson, pretty-printed, comments stripped at load (lenient parsing).

---

## 10. Module conventions (for ALL subagents)

- Java 17 source/target.
- Package: `network.vonix.guardian.core.<subpackage>`.
- All public types have Javadoc.
- All public methods that can throw IO/SQL declare it.
- No statics for state — only constants. State lives in `Guardian` (parent will write).
- No System.out — use `org.slf4j.Logger LOG = LoggerFactory.getLogger(MyClass.class)`.
- No `@Nullable` annotations from non-stdlib packages — use Javadoc `@return null if ...`.
- Tests in `src/test/java` mirroring main package. JUnit 5 + AssertJ + Mockito.
- Every public class has at least one happy-path test.
- Indentation: 4 spaces. Line length: 120.
- No `var` for fields; `var` OK in local scope when type is on RHS.

---

## 11. What subagents must NOT touch

- `core/build.gradle` (parent owns)
- `settings.gradle` (parent owns)
- `gradle/libs.versions.toml` (parent owns)
- `network.vonix.guardian.core.Guardian` facade (parent writes after Wave 1 lands)
- Any file outside their assigned package
- The shared contracts in this file (parent owns)

If a subagent thinks the contract is wrong, it **logs the issue in its summary** and proceeds with the contract as written. Parent decides whether to amend.
