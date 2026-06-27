/**
 * Rollback / restore / undo engine.
 *
 * <p>The {@link network.vonix.guardian.core.rollback.RollbackEngine} consumes
 * {@link network.vonix.guardian.core.query.QueryFilter} queries, builds a
 * deduplicated {@link network.vonix.guardian.core.rollback.RollbackPlan}, and
 * dispatches world mutations to the server thread via a caller-supplied
 * {@link java.util.concurrent.Executor}. The actual world mutations are
 * performed through the loader-supplied
 * {@link network.vonix.guardian.core.rollback.WorldMutator} contract — core
 * code never touches Minecraft types directly.</p>
 *
 * <p>{@link network.vonix.guardian.core.rollback.UndoStack} retains the last
 * {@code N} {@link network.vonix.guardian.core.rollback.RollbackResult}s per
 * actor for the {@code /vg undo} command.</p>
 */
package network.vonix.guardian.core.rollback;
