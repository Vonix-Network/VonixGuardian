package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;

/**
 * Fired inside {@link EventGate#shouldLog(Action)} immediately after built-in
 * category / blacklist filtering has passed but before {@link EventHook}
 * consultation.
 *
 * <p>This is the extension point third-party mods and plugins use to veto
 * VonixGuardian records &mdash; either to save DB space (skip trivial events),
 * to satisfy privacy policies (drop chat containing certain patterns), or to
 * hand off to a competing audit system.</p>
 *
 * <p>Semantics match Bukkit's <code>Cancellable</code>: the platform cell
 * (fabric-cell, forge-cell, neoforge-cell) fires this on its native event bus.
 * If any listener flips {@link #setCancelled(boolean)} to {@code true}, the
 * action is dropped silently. The core listens for the cell-side outcome via
 * a functional {@link EventHook} registered at boot.</p>
 *
 * <p>This class is intentionally platform-agnostic &mdash; it does not extend
 * Bukkit's <code>Event</code>, Fabric's <code>Event</code>, or Forge's
 * <code>Event</code>. Each cell wraps this in a native-bus adapter.</p>
 *
 * @since 1.1.7 (W3 pre-wire, wired fully in W3-B11)
 */
public final class PreLogEvent {

    private final Action action;
    private boolean cancelled;
    private String cancelReason;

    /**
     * @param action the candidate action; must not be {@code null}
     */
    public PreLogEvent(Action action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        this.action = action;
    }

    /** @return the action under consideration; never {@code null} */
    public Action action() {
        return action;
    }

    /** @return {@code true} if any listener has vetoed persistence */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * @param cancelled set to {@code true} to drop this action silently
     */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * @param cancelled cancellation state
     * @param reason    optional short human-readable reason (for debug logs);
     *                  may be {@code null}
     */
    public void setCancelled(boolean cancelled, String reason) {
        this.cancelled = cancelled;
        this.cancelReason = reason;
    }

    /** @return the last reason set via {@link #setCancelled(boolean, String)} or {@code null} */
    public String cancelReason() {
        return cancelReason;
    }
}
