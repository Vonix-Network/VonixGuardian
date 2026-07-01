package network.vonix.guardian.core.perms;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *       {@link OpLevelFallback}: grant iff {@code opLevel >= requiredLevel}.
 * </ol>
 *
 * <p>W3-B8: the {@link #has(UUID, PermissionNode)} overload consults {@link
 * #perNodeOpLevel(PermissionNode)}, which honors an optional per-node override map in
 * {@link GuardianConfig.Permissions#perNodeOpLevels()}. The legacy string-based
 * {@link #has(UUID, String)} still uses the coarse {@link GuardianConfig.Permissions#defaultOpLevel()}
 * for the fallback path, and emits a throttled WARN when the string matches a known
 * {@link PermissionNode#node()} — nudging callers to migrate.
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

    /** Set of legacy-string nodes we've already WARN-nudged, so we do it at most once per node. */
    private final Set<String> legacyStringWarned = ConcurrentHashMap.newKeySet();

    /** Cached view of the set of known node strings (populated on demand). */
    private volatile Set<String> knownNodeStringsCache;

    public PermissionResolver(GuardianConfig.Permissions cfg, OpLevelFallback fallback) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * Legacy string-based check. Kept for backward compatibility; prefer
     * {@link #has(UUID, PermissionNode)}.
     *
     * <p>When {@code node} matches a known {@link PermissionNode#node()} value, a WARN is logged
     * once per node string, asking the caller to migrate to the enum-based overload.
     *
     * @return {@code true} iff the player has the node, per LuckPerms (if available) with the
     *     coarse {@link GuardianConfig.Permissions#defaultOpLevel()} fallback
     */
    public boolean has(UUID uuid, String node) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(node, "node");

        maybeWarnLegacyString(node);

        TristateResult lp = checkLp(uuid, node);
        if (lp == TristateResult.TRUE) return true;
        if (lp == TristateResult.FALSE) return false;
        return checkOpLevel(uuid, cfg.defaultOpLevel());
    }

    /**
     * Enum-based check (W3-B8). The op-level fallback threshold is
     * {@link #perNodeOpLevel(PermissionNode)}, which honors config overrides.
     *
     * @return {@code true} iff the player has the node
     */
    public boolean has(UUID uuid, PermissionNode node) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(node, "node");

        TristateResult lp = checkLp(uuid, node.node());
        if (lp == TristateResult.TRUE) return true;
        if (lp == TristateResult.FALSE) return false;
        return checkOpLevel(uuid, perNodeOpLevel(node));
    }

    /**
     * @return the effective op-level fallback threshold for {@code node} after applying any
     *     config override in {@link GuardianConfig.Permissions#perNodeOpLevels()}. Falls back to
     *     {@link PermissionNode#defaultOpLevel()} when no override is present or the override
     *     value is out of {@code [0,4]}.
     */
    public int perNodeOpLevel(PermissionNode node) {
        Objects.requireNonNull(node, "node");
        Map<String, Integer> overrides = cfg.perNodeOpLevelsOrEmpty();
        Integer v = overrides.get(node.node());
        if (v == null) return node.defaultOpLevel();
        int iv = v;
        if (iv < 0 || iv > 4) {
            LOGGER.warn(
                    "perNodeOpLevels[{}]={} is out of [0,4]; using default {}",
                    node.node(), iv, node.defaultOpLevel());
            return node.defaultOpLevel();
        }
        return iv;
    }

    /**
     * Run the LuckPerms tri-state check if LP is enabled and available. Returns
     * {@link TristateResult#UNDEFINED} if LP is disabled, absent, or returns UNDEFINED.
     */
    private TristateResult checkLp(UUID uuid, String nodeString) {
        if (!cfg.useLuckPerms() || !isLuckPermsAvailable()) {
            return TristateResult.UNDEFINED;
        }
        try {
            return LuckPermsBridge.checkPermission(uuid, nodeString);
        } catch (ClassNotFoundException e) {
            markUnavailable("LuckPerms class disappeared after probe");
            return TristateResult.UNDEFINED;
        } catch (NoClassDefFoundError e) {
            markUnavailable("LuckPerms link-time class missing");
            return TristateResult.UNDEFINED;
        } catch (IllegalStateException e) {
            // Transient — do NOT cache; re-probe next call.
            lpAvailable = null;
            LOGGER.debug("LuckPerms not yet registered; falling back to op-level for {}", uuid);
            return TristateResult.UNDEFINED;
        }
    }

    private boolean checkOpLevel(UUID uuid, int required) {
        try {
            int level = fallback.getOpLevel(uuid);
            return level >= required;
        } catch (RuntimeException e) {
            LOGGER.warn("OpLevelFallback threw for {}; denying", uuid, e);
            return false;
        }
    }

    private void maybeWarnLegacyString(String node) {
        Set<String> known = knownNodeStringsCache;
        if (known == null) {
            Set<String> built = new HashSet<>();
            for (PermissionNode n : PermissionNode.values()) {
                built.add(n.node());
            }
            knownNodeStringsCache = built;
            known = built;
        }
        if (known.contains(node) && legacyStringWarned.add(node)) {
            LOGGER.warn(
                "PermissionResolver.has(UUID, String) called with known node \"{}\"; " +
                "callers should migrate to has(UUID, PermissionNode) to honor per-node op-level overrides",
                node);
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
