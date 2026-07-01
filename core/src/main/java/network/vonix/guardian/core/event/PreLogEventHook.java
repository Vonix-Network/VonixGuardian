package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;

/**
 * Terminal {@link EventHook} that fans out to whatever native event bus the
 * active cell has attached via {@link PreLogDispatcher#setNative}. Matches
 * CoreProtect's {@code CoreProtectPreLogEvent} contract: any listener may
 * veto persistence by cancelling the event.
 *
 * <p>Registered LAST in the hook chain so that cheaper built-in hooks
 * (per-world config overrides — W3-B5, blacklist.txt — W3-B6) get first
 * crack before we pay the price of a native bus dispatch.</p>
 *
 * <p>Returns {@link Decision#DENY} if the dispatched event comes back
 * cancelled, {@link Decision#PASS} otherwise. Never returns
 * {@link Decision#ACCEPT} — the hook only vetoes, it never forces logging.</p>
 *
 * @since 1.1.7 (W3-B11)
 */
public final class PreLogEventHook implements EventHook {

    @Override
    public Decision test(Action a) {
        PreLogEvent evt = new PreLogEvent(a);
        PreLogEvent out = PreLogDispatcher.current().fireAndReturn(evt);
        // Defensive: an ill-behaved impl might return null — treat as PASS.
        if (out == null) {
            return Decision.PASS;
        }
        return out.isCancelled() ? Decision.DENY : Decision.PASS;
    }
}
