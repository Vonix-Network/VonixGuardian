package network.vonix.guardian.core.event;

/**
 * Bridge between the platform-agnostic core and each cell's native event bus.
 *
 * <p>Cells (fabric-cell, forge-cell, neoforge-cell) provide an implementation
 * that wraps {@link PreLogEvent} in a native-bus event type and fires it on
 * the platform's event bus (Bukkit/Forge/NeoForge/Fabric). If any listener
 * cancels the wrapper, the impl must propagate that back onto the passed
 * {@link PreLogEvent} via {@link PreLogEvent#setCancelled(boolean, String)}
 * before returning it.</p>
 *
 * <p>The default impl is a no-op pass-through — safe when no cell is attached
 * (e.g. core-only unit tests, headless embedding). Cells install their impl
 * once at boot via {@link #setNative(PreLogDispatcher)} <em>before</em>
 * {@code Guardian.boot()} runs.</p>
 *
 * <p>This class is intentionally lightweight: it is called from the hot path
 * of {@link EventGate#shouldLog} via {@link PreLogEventHook}. Implementations
 * MUST be thread-safe and MUST NOT block.</p>
 *
 * @since 1.1.7 (W3-B11)
 */
@FunctionalInterface
public interface PreLogDispatcher {

    /**
     * Fire the event on the native bus (if any) and return it. Implementations
     * must propagate any listener-side cancellation onto the returned event.
     *
     * @param evt the event to fire; never {@code null}
     * @return the same event instance, potentially mutated by listeners
     */
    PreLogEvent fireAndReturn(PreLogEvent evt);

    /** Default no-op impl used when no cell has registered a native bus. */
    PreLogDispatcher NOOP = evt -> evt;

    /** Volatile so cell boot writes are visible to core hot-path readers. */
    final class Holder {
        private Holder() {}
        static volatile PreLogDispatcher CURRENT = NOOP;
    }

    /**
     * Install the cell-side native-bus adapter. Called once at boot per JVM.
     *
     * @param impl non-null bridge; pass {@link #NOOP} to detach
     */
    static void setNative(PreLogDispatcher impl) {
        if (impl == null) {
            throw new NullPointerException("impl");
        }
        Holder.CURRENT = impl;
    }

    /** @return the currently installed bridge (or {@link #NOOP} if none). */
    static PreLogDispatcher current() {
        return Holder.CURRENT;
    }
}
