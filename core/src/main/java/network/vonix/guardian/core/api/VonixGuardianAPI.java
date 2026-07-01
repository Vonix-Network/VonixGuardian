/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

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
}
