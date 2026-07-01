package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A single, versioned, dialect-aware schema migration step.
 *
 * <p>Migrations are ordered by {@link #fromVersion()} and applied by
 * {@link MigrationRunner} strictly in order. Each migration is expected to be
 * <b>idempotent from the runner's perspective</b>: the runner will only invoke
 * {@link #apply(Connection, Schema.Dialect)} when the recorded schema version in
 * {@code vg_schema_version} is exactly {@link #fromVersion()}, but the migration
 * itself should still tolerate being re-run against a partially-migrated database
 * where possible (see {@link V3WidenActionTarget} for an example).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #fromVersion()} + 1 must equal {@link #toVersion()} (single-step).</li>
 *   <li>{@link #apply(Connection, Schema.Dialect)} must not commit or roll back —
 *       the runner manages transaction boundaries so it can stamp the version
 *       table atomically with the DDL where the dialect supports it.</li>
 * </ul>
 */
public interface Migration {

    /** Schema version this migration upgrades <i>from</i>. */
    int fromVersion();

    /** Schema version this migration upgrades <i>to</i>. Must equal {@code fromVersion() + 1}. */
    int toVersion();

    /**
     * Apply the migration against the given connection using the given dialect.
     *
     * <p>Implementations must not call {@link Connection#commit()} or
     * {@link Connection#rollback()}. Any {@link SQLException} propagates to the
     * runner, which will roll the transaction back and abort the upgrade.
     */
    void apply(Connection c, Schema.Dialect dialect) throws SQLException;

    /** Short human-readable label for logs. */
    default String label() {
        return getClass().getSimpleName() + " (v" + fromVersion() + "→v" + toVersion() + ")";
    }
}
