package network.vonix.threadedhorizons.client;

/**
 * Client-only bootstrap. Invoked from the common mod constructor only after a Dist.CLIENT check
 * so this class is never initialized on a dedicated server.
 */
public final class ThreadedHorizonsClient {
    private ThreadedHorizonsClient() {
    }

    public static void init() {
        // Client mixins and option hooks are registered via the client mixin config.
    }
}
