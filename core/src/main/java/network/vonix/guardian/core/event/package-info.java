/**
 * Loader→core event submission API.
 *
 * <p>The loader modules (fabric / forge / neoforge per MC version) hold an
 * {@link network.vonix.guardian.core.event.EventSubmitter} reference handed
 * to them by the {@code Guardian} facade and call its {@code submitXxx}
 * methods from MC event handlers. The facade's implementation runs each
 * incoming {@link network.vonix.guardian.core.action.Action} through an
 * {@link network.vonix.guardian.core.event.EventGate} (per-type toggles +
 * config blacklists) before forwarding to the async write queue and the
 * JSON-lines audit log.</p>
 *
 * <p>{@link network.vonix.guardian.core.event.Sentinel} freezes the
 * non-player actor-name strings (e.g. {@code "#creeper"}) defined in
 * SHARED-CONTRACTS § 8.</p>
 */
package network.vonix.guardian.core.event;
