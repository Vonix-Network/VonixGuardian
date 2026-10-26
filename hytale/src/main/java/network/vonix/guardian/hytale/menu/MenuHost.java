/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.menu;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.command.system.CommandOwner;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.concurrent.BoundedGenerationExecutor;
import network.vonix.guardian.hytale.spi.GuardianServices;
import network.vonix.guardian.hytale.spi.PlayerDirectory;
import network.vonix.guardian.hytale.spi.WorldBlockAccess;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Shared menu services. One host is reused for the process so repeated
 * registration does not start another worker pool. The pool is created on
 * the first engine job, not at registration.
 */
final class MenuHost {

    static final String USE_NODE = "vonixguardian.command.use";
    static final String VIEW_OTHERS_NODE = "vonixguardian.command.viewothers";

    private final GuardianServices services;
    private final Logger logger;
    private final CommandOwner owner;
    private final MenuQuery query = new MenuQuery();
    private volatile BoundedGenerationExecutor workers;

    MenuHost(GuardianServices services, Logger logger, CommandOwner owner) {
        this.services = services;
        this.logger = logger;
        this.owner = owner;
    }

    Logger logger() {
        return logger;
    }

    GuardianServices services() {
        return services;
    }

    MenuQuery query() {
        return query;
    }

    Guardian guardian() {
        try {
            if (!services.isRunning()) {
                return null;
            }
            return services.guardian();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Guardian runtime unavailable", ex);
            return null;
        }
    }

    /**
     * @return a denial sentence, or {@code null} when the player may proceed
     */
    String gate(UUID uuid, String node) {
        if (uuid == null) {
            return "Permission check is unavailable. Refusing.";
        }
        String base = basePermission();
        if (base == null || base.isBlank()) {
            return "Plugin base permission was not passed in. Refusing.";
        }
        PlayerDirectory players = directory();
        if (players == null) {
            return "Permission check is unavailable. Refusing.";
        }
        Boolean baseHeld = holds(players, uuid, base);
        if (baseHeld == null) {
            return "Permission check failed. Refusing.";
        }
        if (!baseHeld) {
            return "You do not have the plugin base permission (" + base + ").";
        }
        if (node == null || node.isBlank()) {
            return "Permission node is missing. Refusing.";
        }
        Boolean nodeHeld = holds(players, uuid, node);
        if (nodeHeld == null) {
            return "Permission check failed. Refusing.";
        }
        if (!nodeHeld) {
            return "You do not have permission " + node + ".";
        }
        return null;
    }

    boolean allows(UUID uuid, String node) {
        return gate(uuid, node) == null;
    }

    /**
     * Snapshot taken on the world thread. {@code worldIdOf} runs here, not
     * on the menu worker.
     */
    Actor capture(PlayerRef playerRef) {
        if (playerRef == null) {
            return Actor.unknown();
        }
        UUID uuid = null;
        String name = "";
        try {
            uuid = playerRef.getUuid();
            String username = playerRef.getUsername();
            name = username == null ? "" : username;
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Menu player identity unavailable", ex);
        }
        Integer x = null;
        Integer y = null;
        Integer z = null;
        String worldId = null;
        boolean worldFailed = false;
        try {
            Transform transform = playerRef.getTransform();
            if (transform != null && transform.getPosition() != null) {
                Vector3d pos = transform.getPosition();
                x = (int) Math.floor(pos.x);
                y = (int) Math.floor(pos.y);
                z = (int) Math.floor(pos.z);
            }
            UUID worldUuid = playerRef.getWorldUuid();
            WorldBlockAccess worlds = services.world();
            if (worlds != null && worldUuid != null) {
                worldId = worlds.worldIdOf(worldUuid);
            }
        } catch (RuntimeException ex) {
            worldFailed = true;
            logger.warn(Guardian.MARKER, "Menu player position or world id unavailable", ex);
        }
        return new Actor(uuid, name, x, y, z, worldId, worldFailed);
    }

    boolean execute(Runnable task) {
        try {
            workers().execute(task);
            return true;
        } catch (RejectedExecutionException ex) {
            logger.warn(Guardian.MARKER, "Menu worker queue saturated", ex);
            return false;
        }
    }

    /**
     * Hop a UI publish onto the player's world. The runnable is not run on
     * the worker when the world executor is missing.
     */
    void hop(World world, Runnable task) {
        if (world == null || task == null) {
            logger.warn(Guardian.MARKER, "Menu update dropped: player world is unavailable");
            return;
        }
        try {
            world.execute(task);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Menu update failed to hop to the player world", ex);
        }
    }

    private BoundedGenerationExecutor workers() {
        BoundedGenerationExecutor current = workers;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (workers == null) {
                workers = new BoundedGenerationExecutor("VonixGuardian-HytaleMenu", 64, 2);
            }
            return workers;
        }
    }

    private String basePermission() {
        if (!(owner instanceof PluginBase plugin)) {
            return null;
        }
        try {
            return plugin.getBasePermission();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Plugin base permission unavailable", ex);
            return null;
        }
    }

    private PlayerDirectory directory() {
        try {
            return services.players();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Player directory unavailable", ex);
            return null;
        }
    }

    private Boolean holds(PlayerDirectory players, UUID uuid, String node) {
        try {
            return players.hasPermission(uuid, node);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Permission check failed for " + node, ex);
            return null;
        }
    }

    record Actor(UUID uuid, String name,
                 Integer x, Integer y, Integer z,
                 String worldId, boolean worldLookupFailed) {

        static Actor unknown() {
            return new Actor(null, "", null, null, null, null, true);
        }

        boolean player() {
            return true;
        }
    }
}
