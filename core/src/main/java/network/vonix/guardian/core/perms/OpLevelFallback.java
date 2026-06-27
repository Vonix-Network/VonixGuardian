package network.vonix.guardian.core.perms;

import java.util.UUID;

/**
 * Loader-supplied function returning the vanilla op-level for a player UUID.
 *
 * <p>Implementations typically wrap {@code MinecraftServer.getPlayerList().getOps()} on whichever
 * mapping the loader exposes. Return {@code 0} for non-ops or unknown players.
 */
@FunctionalInterface
public interface OpLevelFallback {

    /**
     * @param uuid player UUID (never {@code null})
     * @return op level in {@code [0, 4]}; {@code 0} when the player is not opped
     */
    int getOpLevel(UUID uuid);
}
