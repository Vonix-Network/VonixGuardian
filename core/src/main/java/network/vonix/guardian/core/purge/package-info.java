/**
 * Background auto-purge daemon (W3-B4) — CoreProtect Patreon 24+ parity.
 *
 * <p>See {@link network.vonix.guardian.core.purge.AutoPurgeScheduler} for the
 * daily runner. This package is intentionally kept separate from
 * {@code core.rollback} because the manual {@code /vg purge} entry point
 * ({@code PurgeEngine}) has different call-site semantics (synchronous, blocks
 * on the worker thread that dispatched the command) than the scheduler
 * (asynchronous, chunked, mutex-guarded).
 *
 * @since 1.1.7
 */
package network.vonix.guardian.core.purge;
