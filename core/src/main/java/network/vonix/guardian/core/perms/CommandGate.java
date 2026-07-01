package network.vonix.guardian.core.perms;

import java.util.UUID;

/**
 * Loader-agnostic gate for {@link PermissionNode} checks.
 *
 * <p>Encapsulates the standard pattern used by every {@code /vg} handler:
 *
 * <pre>{@code
 * if (!CommandGate.check(g.perms(), player.getUUID(), PermissionNode.LOOKUP)) {
 *     deny();
 *     return 0;
 * }
 * }</pre>
 *
 * <p>For non-player sources (console, RCON, command block) pass {@code null}
 * for the UUID and supply the caller-side op level via {@link #checkConsole};
 * console always gets to {@code opLevel = 4} on Vanilla, so by default consoles
 * pass every check. Loaders that need finer-grained console gating can call
 * {@link #check(PermissionResolver, UUID, PermissionNode)} with a synthetic UUID
 * mapped to a lower op level.</p>
 *
 * @since 1.1.7 (W3-B7)
 */
public final class CommandGate {

    private CommandGate() {
        // utility
    }

    /**
     * Player-source check.
     *
     * <p>Delegates entirely to {@link PermissionResolver#has(UUID, String)}, which
     * already implements the LuckPerms-first + op-level fallback order documented
     * on that class.</p>
     *
     * @param resolver live resolver (must not be {@code null})
     * @param uuid     player UUID; may be {@code null} for console (always granted)
     * @param node     the node to check (must not be {@code null})
     * @return {@code true} iff the source has {@code node}
     */
    public static boolean check(PermissionResolver resolver, UUID uuid, PermissionNode node) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        if (uuid == null) {
            // Console / non-player source: op-level 4 passes every default.
            return true;
        }
        return resolver.has(uuid, node.node());
    }

    /**
     * Explicit console/RCON check: grant iff {@code consoleOpLevel >= node.defaultOpLevel()}.
     *
     * <p>Useful for loaders that expose a distinct "server console" op level (e.g. RCON).</p>
     */
    public static boolean checkConsole(int consoleOpLevel, PermissionNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        return consoleOpLevel >= node.defaultOpLevel();
    }
}
