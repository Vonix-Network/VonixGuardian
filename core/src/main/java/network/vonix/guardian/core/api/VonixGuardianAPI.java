/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import network.vonix.guardian.core.action.Action;

import java.util.List;
import java.util.UUID;

/**
 * Public, version-stable Java API surface for VonixGuardian.
 *
 * <p>This interface is the third-party integration point. It mirrors the
 * CoreProtect API contract documented in
 * {@code docs/COREPROTECT-COMPARISON.md} while returning VG-native typed
 * result records instead of {@code List<String[]>}.
 *
 * <p><b>Obtaining an instance.</b> Because the {@code vonixguardian-core} jar
 * is not a hard dependency of consumer mods, use a reflection soft-dep at
 * boot time (see the {@code LuckPermsBridge} pattern in
 * {@code core/src/main/java/network/vonix/guardian/core/perms/LuckPermsBridge.java}
 * and the worked example in {@code docs/API.md} § "Public Java API (v1)").
 * VG itself exposes the singleton via {@code Guardian#api()}.
 *
 * <p><b>Threading.</b> Every method here performs a blocking DAO query. Do
 * not call from the server thread; hop to your executor first
 * (Fabric/Forge/NeoForge all supply one). Return values are immutable and
 * safe to hand back to the server thread.
 *
 * <p><b>Versioning.</b> {@link #apiVersion()} follows semver — a bump means
 * a breaking change to existing method signatures or record shapes. New
 * methods MAY be added within a major without a bump. Callers should
 * compare {@code apiVersion()} at startup and refuse to load on mismatch.
 *
 * @since 1.1.7 (Wave-3 B12+B13)
 */
public interface VonixGuardianAPI {

    /**
     * @return the API major version this VG build implements. Callers should
     *         gate feature use on this — a bump signals a breaking change.
     *         Current: {@code 1}.
     */
    int apiVersion();

    /**
     * @return the plugin's {@code mod_version} string (e.g. {@code "1.1.7"}).
     *         Human-readable; do not parse for feature gating (use
     *         {@link #apiVersion()} for that).
     */
    String pluginVersion();

    /**
     * Wiring smoke-test. Callers invoke this once at startup after obtaining
     * the handle to confirm reflection succeeded. Always returns {@code true}
     * on a healthy VG instance; a {@code NoSuchMethodError} or
     * {@code ClassCastException} from reflection is the real failure mode.
     *
     * @return {@code true}
     */
    boolean testAPI();

    /**
     * @param user          player UUID (non-null)
     * @param worldId       world / dimension key (e.g. {@code "minecraft:overworld"})
     * @param x             block X
     * @param y             block Y
     * @param z             block Z
     * @param withinSeconds temporal window (seconds); {@code now - withinSeconds*1000}
     *                      is the inclusive lower bound
     * @return {@code true} iff {@code user} placed a block at exactly
     *         {@code (worldId, x, y, z)} within the last {@code withinSeconds}
     *         seconds (any {@link network.vonix.guardian.core.action.ActionType#BLOCK_PLACE}
     *         row)
     */
    boolean hasPlaced(UUID user, String worldId, int x, int y, int z, long withinSeconds);

    /**
     * @param user          player UUID (non-null)
     * @param worldId       world / dimension key
     * @param x             block X
     * @param y             block Y
     * @param z             block Z
     * @param withinSeconds temporal window (seconds)
     * @return {@code true} iff {@code user} broke a block at exactly
     *         {@code (worldId, x, y, z)} within the last {@code withinSeconds}
     *         seconds (any {@link network.vonix.guardian.core.action.ActionType#BLOCK_BREAK}
     *         row)
     */
    boolean hasRemoved(UUID user, String worldId, int x, int y, int z, long withinSeconds);

    /**
     * Coordinate-scoped block-family lookup.
     *
     * @param worldId       world / dimension key
     * @param x             block X
     * @param y             block Y
     * @param z             block Z
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @return every block-family event at that exact coordinate within the
     *         window, newest first. May be empty; never {@code null}.
     */
    List<BlockLookupResult> blockLookup(String worldId, int x, int y, int z, long withinSeconds);

    /**
     * Coordinate-scoped container-family lookup.
     *
     * @param worldId       world / dimension key
     * @param x             container X
     * @param y             container Y
     * @param z             container Z
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @return every container-family transaction at that exact coordinate
     *         within the window, newest first. May be empty; never {@code null}.
     */
    List<ContainerLookupResult> containerLookup(String worldId, int x, int y, int z, long withinSeconds);

    /**
     * User-scoped chat lookup.
     *
     * @param user          player UUID
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @param limit         hard cap on results ({@code 0} or negative = "engine default"
     *                      from {@code config.lookup().defaultPageSize()})
     * @return the user's chat entries within the window, newest first
     */
    List<MessageLookupResult> chatLookup(UUID user, long withinSeconds, int limit);

    /**
     * User-scoped command lookup.
     *
     * @param user          player UUID
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @param limit         hard cap on results ({@code 0} or negative = "engine default")
     * @return the user's command entries within the window, newest first
     */
    List<MessageLookupResult> commandLookup(UUID user, long withinSeconds, int limit);

    // ---------------------------------------------------------------- v1.2.0 CP-parity lookups

    /**
     * User-scoped item-family lookup — covers {@code ITEM_DROP},
     * {@code ITEM_PICKUP} and {@code ITEM_CRAFT}.
     *
     * @param user          player UUID
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @param limit         hard cap on results ({@code 0} or negative = engine default)
     * @return the user's item-family events within the window, newest first.
     *         May be empty; never {@code null}.
     * @since 1.2.0
     */
    List<ItemLookupResult> itemLookup(UUID user, long withinSeconds, int limit);

    /**
     * User-scoped player-inventory-family lookup — covers
     * {@code INVENTORY_DEPOSIT} and {@code INVENTORY_WITHDRAW}.
     *
     * @param user          player UUID
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @param limit         hard cap on results ({@code 0} or negative = engine default)
     * @return the user's inventory transactions within the window, newest first.
     *         May be empty; never {@code null}.
     * @since 1.2.0
     */
    List<InventoryLookupResult> inventoryLookup(UUID user, long withinSeconds, int limit);

    /**
     * User-scoped session-family lookup — covers {@code SESSION_JOIN} and
     * {@code SESSION_LEAVE}.
     *
     * @param user          player UUID
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @param limit         hard cap on results ({@code 0} or negative = engine default)
     * @return the user's session join/leave events within the window, newest first.
     *         May be empty; never {@code null}.
     * @since 1.2.0
     */
    List<SessionLookupResult> sessionLookup(UUID user, long withinSeconds, int limit);

    /**
     * User-scoped username-change lookup.
     *
     * @param user          player UUID
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @param limit         hard cap on results ({@code 0} or negative = engine default)
     * @return the user's recorded username changes within the window, newest first.
     *         May be empty; never {@code null}.
     * @since 1.2.0
     */
    List<UsernameLookupResult> usernameLookup(UUID user, long withinSeconds, int limit);

    /**
     * Coordinate-scoped sign-edit lookup.
     *
     * @param worldId       world / dimension key
     * @param x             sign X
     * @param y             sign Y
     * @param z             sign Z
     * @param withinSeconds temporal window (seconds); {@code 0} = unbounded
     * @return every sign edit at that exact coordinate within the window,
     *         newest first. May be empty; never {@code null}.
     * @since 1.2.0
     */
    List<SignLookupResult> signLookup(String worldId, int x, int y, int z, long withinSeconds);

    /**
     * Introspection helper: return the actions currently sitting in the
     * async write queue for the given coordinate (i.e. submitted but not yet
     * flushed to the DAO).
     *
     * <p>Intended for debugging tools and hot-path assertions; the underlying
     * queue is not required to expose an efficient snapshot, so this method
     * MAY return an empty list even if items are actually queued. Do not
     * rely on it for correctness.
     *
     * @param worldId world / dimension key
     * @param x       block X
     * @param y       block Y
     * @param z       block Z
     * @return currently-pending actions at the coord; may be empty
     * @since 1.2.0
     */
    List<Action> queueLookup(String worldId, int x, int y, int z);

    // ---------------------------------------------------------------- v1.2.0 CP-parity direct logging

    /**
     * Direct-log a chat event through the standard {@code EventSubmitter}
     * pipeline. Passes through the same gate/queue as native VG capture.
     *
     * @param user      player UUID (may be {@code null} for console/system origins)
     * @param actorName resolved name at send time (may be {@code null} — a sentinel is used)
     * @param worldId   world the sender was in
     * @param message   full message body
     * @return {@code true} if the event was accepted for enqueue,
     *         {@code false} if gated or rejected by the queue
     * @since 1.2.0
     */
    boolean logChat(UUID user, String actorName, String worldId, String message);

    /**
     * Direct-log a command event through the standard {@code EventSubmitter}
     * pipeline.
     *
     * @param user      player UUID (may be {@code null} for console/system origins)
     * @param actorName resolved name at send time
     * @param worldId   world the sender was in
     * @param command   full command body (including leading {@code /})
     * @return {@code true} if the event was accepted for enqueue
     * @since 1.2.0
     */
    boolean logCommand(UUID user, String actorName, String worldId, String command);

    /**
     * Direct-log a generic click / interaction event at a coordinate.
     * Recorded as {@link network.vonix.guardian.core.action.ActionType#CLICK}.
     *
     * @param user      player UUID
     * @param actorName resolved name at event time
     * @param worldId   world / dimension key
     * @param x         block X
     * @param y         block Y
     * @param z         block Z
     * @return {@code true} if the event was accepted for enqueue
     * @since 1.2.0
     */
    boolean logInteraction(UUID user, String actorName, String worldId, int x, int y, int z);

    /**
     * Direct-log a block placement. Recorded as
     * {@link network.vonix.guardian.core.action.ActionType#BLOCK_PLACE}.
     *
     * @param user      player UUID (may be {@code null} for non-player sources)
     * @param actorName resolved name / sentinel
     * @param worldId   world / dimension key
     * @param x         block X
     * @param y         block Y
     * @param z         block Z
     * @param blockId   block registry id (e.g. {@code "minecraft:stone"})
     * @return {@code true} if the event was accepted for enqueue
     * @since 1.2.0
     */
    boolean logPlacement(UUID user, String actorName, String worldId,
                         int x, int y, int z, String blockId);

    /**
     * Direct-log a block removal. Recorded as
     * {@link network.vonix.guardian.core.action.ActionType#BLOCK_BREAK}.
     *
     * @param user      player UUID (may be {@code null} for non-player sources)
     * @param actorName resolved name / sentinel
     * @param worldId   world / dimension key
     * @param x         block X
     * @param y         block Y
     * @param z         block Z
     * @param blockId   block registry id
     * @return {@code true} if the event was accepted for enqueue
     * @since 1.2.0
     */
    boolean logRemoval(UUID user, String actorName, String worldId,
                       int x, int y, int z, String blockId);
}
