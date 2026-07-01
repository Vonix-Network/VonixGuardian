package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;

/**
 * Pluggable filter/veto hook consulted by {@link EventGate#shouldLog(Action)}
 * <em>after</em> the built-in category toggles and static blacklists have
 * passed, but <em>before</em> the action is enqueued.
 *
 * <p>Each hook returns a tri-state {@link Decision}:</p>
 * <ul>
 *   <li>{@link Decision#ACCEPT} &mdash; short-circuit: bypass remaining hooks and log.</li>
 *   <li>{@link Decision#DENY} &mdash; short-circuit: drop the action silently.</li>
 *   <li>{@link Decision#PASS} &mdash; no opinion; continue to the next hook (or default-accept if no hook opinionated).</li>
 * </ul>
 *
 * <p>Hooks MUST be pure and thread-safe. They are consulted on the hot path of
 * every event that survives static filtering; heavy work (I/O, reflection, sync
 * with the render thread) is strictly forbidden here &mdash; do that at
 * <em>configure</em> time and cache into thread-safe collections.</p>
 *
 * <p>Registration order matters: the first hook to return a non-{@code PASS}
 * decision wins. Register the cheapest hooks first (per-world routers, static
 * blacklists) and the expensive ones (event bus fan-out) last.</p>
 *
 * @since 1.1.7 (W3 pre-wire)
 */
@FunctionalInterface
public interface EventHook {

    /** Tri-state hook decision. */
    enum Decision {
        /** Log the action; skip remaining hooks. */
        ACCEPT,
        /** Drop the action silently; skip remaining hooks. */
        DENY,
        /** No opinion; fall through to next hook. */
        PASS
    }

    /**
     * @param a the candidate action; guaranteed non-null and already
     *          past the built-in category / blacklist checks
     * @return the hook's decision
     */
    Decision test(Action a);
}
