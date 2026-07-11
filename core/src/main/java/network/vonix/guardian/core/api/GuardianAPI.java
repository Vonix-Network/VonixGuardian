/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Default {@link VonixGuardianAPI} implementation backed by a live
 * {@link Guardian} instance.
 *
 * <p>Constructed once by {@link Guardian#api()} (latched behind an
 * {@link java.util.concurrent.atomic.AtomicReference}); consumer mods should
 * NOT instantiate directly — obtain via {@code Guardian.api()} or reflection
 * (see {@code docs/API.md}).
 *
 * <p>Every method:
 * <ol>
 *   <li>Builds a {@link QueryFilter} scoping the request.</li>
 *   <li>For {@code hasPlaced} / {@code hasRemoved}, calls the fast-path DAO
 *       helper {@link GuardianDao#hasActionsInWindow}.</li>
 *   <li>For lookups, calls {@link GuardianDao#query} and maps
 *       {@link Action} rows to the appropriate typed result record.</li>
 * </ol>
 *
 * <p>Wraps checked exceptions from the DAO in {@link RuntimeException} — the
 * public API surface is intentionally exception-free at method signature
 * level to match CoreProtect's contract.
 *
 * @since 1.1.7 (W3-B12+B13)
 */
public final class GuardianAPI implements VonixGuardianAPI {

    /** Current API major. Bump on breaking change. */
    public static final int API_VERSION = 1;

    /** Plugin display version — mirrors {@code gradle.properties#mod_version}. */
    public static final String PLUGIN_VERSION = "1.3.10";

    private final Guardian guardian;

    /**
     * @param guardian live Guardian instance; MUST NOT be {@code null}
     */
    public GuardianAPI(Guardian guardian) {
        this.guardian = Objects.requireNonNull(guardian, "guardian");
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public String pluginVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public boolean testAPI() {
        return true;
    }

    // -------------------------------------------------------------- hasPlaced / hasRemoved

    @Override
    public boolean hasPlaced(UUID user, String worldId, int x, int y, int z, long withinSeconds) {
        return hasAny(user, worldId, x, y, z, new ActionType[]{ActionType.BLOCK_PLACE}, withinSeconds);
    }

    @Override
    public boolean hasRemoved(UUID user, String worldId, int x, int y, int z, long withinSeconds) {
        return hasAny(user, worldId, x, y, z, new ActionType[]{ActionType.BLOCK_BREAK}, withinSeconds);
    }

    private boolean hasAny(UUID user, String worldId, int x, int y, int z,
                           ActionType[] types, long withinSeconds) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(worldId, "worldId");
        long windowMillis = Math.max(0L, withinSeconds) * 1000L;
        try {
            return guardian.dao().hasActionsInWindow(user, worldId, x, y, z, types, windowMillis);
        } catch (Exception e) {
            throw new RuntimeException("VonixGuardianAPI DAO probe failed", e);
        }
    }

    // -------------------------------------------------------------- blockLookup

    @Override
    public List<BlockLookupResult> blockLookup(String worldId, int x, int y, int z, long withinSeconds) {
        QueryFilter f = coordFilter(worldId, x, y, z, withinSeconds,
                categoryTypes(ActionType.Category.BLOCK));
        List<Action> rows = runQuery(f);
        List<BlockLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            out.add(new BlockLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), a.worldId(),
                    a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                    stripSign(a.type().token()),
                    a.rolledBack(), a.sourceTag(),
                    a.oldBlockState(), a.newBlockState(), a.blockEntityNbt()));
        }
        return out;
    }

    // -------------------------------------------------------------- containerLookup

    @Override
    public List<ContainerLookupResult> containerLookup(String worldId, int x, int y, int z, long withinSeconds) {
        QueryFilter f = coordFilter(worldId, x, y, z, withinSeconds,
                categoryTypes(ActionType.Category.CONTAINER));
        List<Action> rows = runQuery(f);
        List<ContainerLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            int delta = a.amount();
            if (a.type().sign() == ActionType.Sign.BREAK) {
                delta = -delta;
            }
            out.add(new ContainerLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), a.worldId(),
                    a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                    delta, a.rolledBack(), a.sourceTag(), a.itemNbt()));
        }
        return out;
    }

    // -------------------------------------------------------------- chatLookup / commandLookup

    @Override
    public List<MessageLookupResult> chatLookup(UUID user, long withinSeconds, int limit) {
        return messageLookup(user, withinSeconds, limit, ActionType.CHAT);
    }

    @Override
    public List<MessageLookupResult> commandLookup(UUID user, long withinSeconds, int limit) {
        return messageLookup(user, withinSeconds, limit, ActionType.COMMAND);
    }

    private List<MessageLookupResult> messageLookup(UUID user, long withinSeconds, int limit, ActionType kind) {
        Objects.requireNonNull(user, "user");
        Long since = withinSeconds > 0 ? System.currentTimeMillis() - withinSeconds * 1000L : null;
        QueryFilter f = QueryFilter.builder()
                .addUser(new QueryFilter.UserSel(user, null, false))
                .sinceMillis(since)
                .addAction(new QueryFilter.ActionSelect(kind, QueryFilter.ActionSelect.Sign.ANY))
                .build();
        int effLimit = limit > 0
                ? limit
                : guardian.config().lookup().defaultPageSize();
        List<Action> rows;
        try {
            rows = guardian.dao().query(f, 0, effLimit);
        } catch (Exception e) {
            throw new RuntimeException("VonixGuardianAPI message lookup failed", e);
        }
        List<MessageLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            out.add(new MessageLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), a.worldId(),
                    a.targetId(), a.type().token()));
        }
        return out;
    }

    // -------------------------------------------------------------- v1.2.0 user-scoped lookups

    @Override
    public List<ItemLookupResult> itemLookup(UUID user, long withinSeconds, int limit) {
        List<Action> rows = userLookup(user, withinSeconds, limit,
                ActionType.ITEM_DROP, ActionType.ITEM_PICKUP, ActionType.ITEM_CRAFT);
        List<ItemLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            out.add(new ItemLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), a.worldId(),
                    a.targetId(), a.targetMeta(), a.amount(),
                    stripSign(a.type().token()), a.rolledBack(), a.sourceTag(), a.itemNbt()));
        }
        return out;
    }

    @Override
    public List<InventoryLookupResult> inventoryLookup(UUID user, long withinSeconds, int limit) {
        List<Action> rows = userLookup(user, withinSeconds, limit,
                ActionType.INVENTORY_DEPOSIT, ActionType.INVENTORY_WITHDRAW);
        List<InventoryLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            out.add(new InventoryLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), a.worldId(),
                    a.targetId(), a.targetMeta(), a.amount(),
                    stripSign(a.type().token()), a.rolledBack(), a.sourceTag()));
        }
        return out;
    }

    @Override
    public List<SessionLookupResult> sessionLookup(UUID user, long withinSeconds, int limit) {
        List<Action> rows = userLookup(user, withinSeconds, limit,
                ActionType.SESSION_JOIN, ActionType.SESSION_LEAVE);
        List<SessionLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            String direction = a.type() == ActionType.SESSION_JOIN ? "join" : "leave";
            out.add(new SessionLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), a.worldId(),
                    stripSign(a.type().token()), direction,
                    a.targetId() != null ? a.targetId() : ""));
        }
        return out;
    }

    @Override
    public List<UsernameLookupResult> usernameLookup(UUID user, long withinSeconds, int limit) {
        List<Action> rows = userLookup(user, withinSeconds, limit, ActionType.USERNAME_CHANGE);
        List<UsernameLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            String prev = "?";
            String target = a.targetId();
            if (target != null) {
                int idx = target.indexOf(" -> ");
                if (idx >= 0) {
                    prev = target.substring(0, idx);
                }
            }
            out.add(new UsernameLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), prev));
        }
        return out;
    }

    @Override
    public List<SignLookupResult> signLookup(String worldId, int x, int y, int z, long withinSeconds) {
        QueryFilter f = coordFilter(worldId, x, y, z, withinSeconds, List.of(ActionType.SIGN));
        List<Action> rows = runQuery(f);
        List<SignLookupResult> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            out.add(new SignLookupResult(
                    a.timestamp(), a.actorUuid(), a.actorName(), a.worldId(),
                    a.x(), a.y(), a.z(),
                    a.targetId() != null ? a.targetId() : "",
                    a.sourceTag(), a.signSide(), a.signDyeColor(), a.signWaxed()));
        }
        return out;
    }

    @Override
    public List<Action> queueLookup(String worldId, int x, int y, int z) {
        Objects.requireNonNull(worldId, "worldId");
        // Filter the async queue's in-memory snapshot by (worldId, x, y, z).
        // pendingSnapshot() is a copy — safe to iterate without draining the
        // queue. Rows that have already flushed to storage are visible through
        // the normal DAO lookups; this method is intentionally scoped to the
        // still-buffered tail.
        List<Action> pending = guardian.queue().pendingSnapshot();
        if (pending.isEmpty()) return List.of();
        List<Action> out = new ArrayList<>();
        for (Action a : pending) {
            if (a == null) continue;
            if (!worldId.equals(a.worldId())) continue;
            if (a.x() != x || a.y() != y || a.z() != z) continue;
            out.add(a);
        }
        return out;
    }

    private List<Action> userLookup(UUID user, long withinSeconds, int limit, ActionType... kinds) {
        Objects.requireNonNull(user, "user");
        Long since = withinSeconds > 0 ? System.currentTimeMillis() - withinSeconds * 1000L : null;
        QueryFilter.Builder b = QueryFilter.builder()
                .addUser(new QueryFilter.UserSel(user, null, false))
                .sinceMillis(since);
        for (ActionType t : kinds) {
            b.addAction(new QueryFilter.ActionSelect(t, QueryFilter.ActionSelect.Sign.ANY));
        }
        int effLimit = limit > 0
                ? limit
                : guardian.config().lookup().defaultPageSize();
        try {
            return guardian.dao().query(b.build(), 0, effLimit);
        } catch (Exception e) {
            throw new RuntimeException("VonixGuardianAPI user-scoped lookup failed", e);
        }
    }

    // -------------------------------------------------------------- v1.2.0 direct logging

    @Override
    public boolean logChat(UUID user, String actorName, String worldId, String message) {
        Objects.requireNonNull(worldId, "worldId");
        return submitDirect(ActionType.CHAT, user, actorName, worldId, 0, 0, 0, message, null, 1, null);
    }

    @Override
    public boolean logCommand(UUID user, String actorName, String worldId, String command) {
        Objects.requireNonNull(worldId, "worldId");
        return submitDirect(ActionType.COMMAND, user, actorName, worldId, 0, 0, 0, command, null, 1, null);
    }

    @Override
    public boolean logInteraction(UUID user, String actorName, String worldId, int x, int y, int z) {
        Objects.requireNonNull(worldId, "worldId");
        return submitDirect(ActionType.CLICK, user, actorName, worldId, x, y, z, "", null, 1, null);
    }

    @Override
    public boolean logPlacement(UUID user, String actorName, String worldId,
                                int x, int y, int z, String blockId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(blockId, "blockId");
        return submitDirect(ActionType.BLOCK_PLACE, user, actorName, worldId, x, y, z, blockId, null, 1, null);
    }

    @Override
    public boolean logRemoval(UUID user, String actorName, String worldId,
                              int x, int y, int z, String blockId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(blockId, "blockId");
        return submitDirect(ActionType.BLOCK_BREAK, user, actorName, worldId, x, y, z, blockId, null, 1, null);
    }

    private boolean submitDirect(ActionType type, UUID user, String actorName, String worldId,
                                 int x, int y, int z, String targetId, String targetMeta,
                                 int amount, String sourceTag) {
        Action a = new ActionBuilder()
                .type(type)
                .actorUuid(user)
                .actorName(actorName)
                .worldId(worldId)
                .position(x, y, z)
                .targetId(targetId)
                .targetMeta(targetMeta)
                .amount(Math.max(1, amount))
                .sourceTag(sourceTag)
                .build();
        return guardian.submitAccepted(a);
    }

    // -------------------------------------------------------------- shared helpers

    private QueryFilter coordFilter(String worldId, int x, int y, int z, long withinSeconds,
                                    List<ActionType> types) {
        Objects.requireNonNull(worldId, "worldId");
        Long since = withinSeconds > 0 ? System.currentTimeMillis() - withinSeconds * 1000L : null;
        QueryFilter.Builder b = QueryFilter.builder()
                .worldSel(new QueryFilter.WorldSel(worldId, false))
                .radius(0)
                .center(x, y, z)
                .sinceMillis(since);
        for (ActionType t : types) {
            b.addAction(new QueryFilter.ActionSelect(t, QueryFilter.ActionSelect.Sign.ANY));
        }
        return b.build();
    }

    private List<Action> runQuery(QueryFilter f) {
        try {
            int limit = guardian.config().lookup().maxResultRows();
            if (limit <= 0) {
                limit = guardian.config().lookup().defaultPageSize();
            }
            return guardian.dao().query(f, 0, limit);
        } catch (Exception e) {
            throw new RuntimeException("VonixGuardianAPI lookup failed", e);
        }
    }

    private static List<ActionType> categoryTypes(ActionType.Category cat) {
        List<ActionType> out = new ArrayList<>();
        for (ActionType t : ActionType.values()) {
            if (t.category() == cat) {
                out.add(t);
            }
        }
        return out;
    }

    /** Strip leading {@code +} / {@code -} from a token (e.g. {@code "+block"} → {@code "block"}). */
    private static String stripSign(String token) {
        if (token == null || token.isEmpty()) return token;
        char c = token.charAt(0);
        return (c == '+' || c == '-') ? token.substring(1) : token;
    }

    // Silence "unused import" for EnumSet if the compiler ever complains
    @SuppressWarnings("unused")
    private static final EnumSet<ActionType.Category> ALL_CATS = EnumSet.allOf(ActionType.Category.class);
}
