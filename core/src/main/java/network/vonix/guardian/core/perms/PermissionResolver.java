package network.vonix.guardian.core.perms;

import java.util.Objects;
import java.util.UUID;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.LuckPermsBridge.TristateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic permission resolver.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>If {@link GuardianConfig.Permissions#useLuckPerms()} is true <em>and</em> the LuckPerms
 *       bridge is available, delegate to {@link LuckPermsBridge#checkPermission(UUID, String)}. A
 *       {@link TristateResult#TRUE TRUE}/{@link TristateResult#FALSE FALSE} short-circuits.
 *   <li>Otherwise, or on {@link TristateResult#UNDEFINED UNDEFINED}, consult the loader-supplied
 *       {@link OpLevelFallback}: grant iff {@code opLevel >= defaultOpLevel}.
 * </ol>
 *
 * <p>LP availability is cached as a tri-state via a {@code Boolean} field ({@code null} unprobed,
 * {@code TRUE} available, {@code FALSE} permanently unavailable). {@link NoClassDefFoundError} and
 * {@link ClassNotFoundException} cache {@code FALSE} permanently. {@link IllegalStateException}
 * (LP loaded but not yet registered) does <em>not</em> cache — the next call re-probes.
 *
 * <p>Thread-safe: all mutable state is guarded by {@code this}.
 */
public final class PermissionResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionResolver.class);

    private final GuardianConfig.Permissions cfg;
    private final OpLevelFallback fallback;

    /** {@code null} = unprobed, {@code TRUE} = available, {@code FALSE} = permanently unavailable. */
    private volatile Boolean lpAvailable;

    public PermissionResolver(GuardianConfig.Permissions cfg, OpLevelFallback fallback) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * @return {@code true} iff the player has the node, per LuckPerms (if available) with op-level
     *     fallback
     */
    public boolean has(UUID uuid, String node) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(node, "node");

        if (cfg.useLuckPerms() && isLuckPermsAvailable()) {
            TristateResult result;
            try {
                result = LuckPermsBridge.checkPermission(uuid, node);
            } catch (ClassNotFoundException e) {
                markUnavailable("LuckPerms class disappeared after probe");
                result = TristateResult.UNDEFINED;
            } catch (NoClassDefFoundError e) {
                markUnavailable("LuckPerms link-time class missing");
                result = TristateResult.UNDEFINED;
            } catch (IllegalStateException e) {
                // Transient — do NOT cache; re-probe next call.
                lpAvailable = null;
                LOGGER.debug("LuckPerms not yet registered; falling back to op-level for {}", uuid);
                result = TristateResult.UNDEFINED;
            }
            if (result == TristateResult.TRUE) {
                return true;
            }
            if (result == TristateResult.FALSE) {
                return false;
            }
            // UNDEFINED: fall through to op-level check.
        }
        return checkOpLevel(uuid);
    }

    private boolean checkOpLevel(UUID uuid) {
        try {
            int level = fallback.getOpLevel(uuid);
            return level >= cfg.defaultOpLevel();
        } catch (RuntimeException e) {
            LOGGER.warn("OpLevelFallback threw for {}; denying", uuid, e);
            return false;
        }
    }

    /**
     * Probe (and cache) LuckPerms availability. Synchronised so concurrent first calls share a
     * single probe.
     */
    private boolean isLuckPermsAvailable() {
        Boolean cached = lpAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (lpAvailable != null) {
                return lpAvailable;
            }
            try {
                boolean present = LuckPermsBridge.isAvailable();
                lpAvailable = present;
                if (present) {
                    LOGGER.info("LuckPerms detected on classpath — using it for permission checks");
                } else {
                    LOGGER.info(
                            "LuckPerms not on classpath — falling back to op-level (>= {})",
                            cfg.defaultOpLevel());
                }
                return present;
            } catch (NoClassDefFoundError e) {
                markUnavailable("LuckPerms probe NoClassDefFoundError: " + e.getMessage());
                return false;
            }
        }
    }

    private synchronized void markUnavailable(String reason) {
        if (!Boolean.FALSE.equals(lpAvailable)) {
            LOGGER.warn("Disabling LuckPerms bridge permanently: {}", reason);
        }
        lpAvailable = Boolean.FALSE;
    }

    // ---- test hooks (package-private) ----

    /** Visible for testing: current cached LP availability tri-state. */
    Boolean lpAvailabilityCacheForTest() {
        return lpAvailable;
    }

    /** Visible for testing: reset cache so the next call re-probes. */
    void resetLpAvailabilityCacheForTest() {
        this.lpAvailable = null;
    }
}
