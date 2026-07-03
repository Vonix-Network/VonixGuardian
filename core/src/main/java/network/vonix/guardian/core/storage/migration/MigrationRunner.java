package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ordered, idempotent applier of {@link Migration} steps.
 *
 * <p>Reads the highest version currently recorded in {@code vg_schema_version}
 * (assumed to have been created by {@link Schema#createTables(Connection, Schema.Dialect)}
 * for fresh installs), then walks the registered migration chain forward until
 * the target version is reached.
 *
 * <p>The runner is <b>safe to invoke on every boot</b>: if the DB is already at
 * or beyond the target version, it is a pure read of {@code vg_schema_version}
 * and returns immediately.
 *
 * <h2>Transaction model</h2>
 * Each individual migration is applied inside its own explicit transaction with
 * {@code autoCommit=false}. On success the version stamp is inserted and the
 * transaction commits; on failure the transaction rolls back and the runner
 * throws, leaving the DB at its previous version.
 *
 * <p><b>MySQL caveat:</b> MySQL implicitly commits DDL statements. That means a
 * v2→v3 {@link V3WidenActionTarget#apply} will already have committed the ALTER
 * before we get to the version stamp; if the JVM dies between the two, the
 * schema is v3 but the version table still says v2. On the next boot the runner
 * will re-attempt the ALTER, which MySQL treats as a metadata-only no-op on an
 * already-widened column — the version stamp then completes and we're
 * consistent. This is acceptable because our migrations are designed to be
 * safely re-runnable in that window.
 */
public final class MigrationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);

    private final List<Migration> migrations;

    public MigrationRunner(List<Migration> migrations) {
        List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(Migration::fromVersion));
        // Sanity: enforce contiguous fromVersion → toVersion = fromVersion + 1.
        for (Migration m : sorted) {
            if (m.toVersion() != m.fromVersion() + 1) {
                throw new IllegalArgumentException(
                    "Migration " + m.label() + " must be single-step (from+1 == to)");
            }
        }
        this.migrations = List.copyOf(sorted);
    }

    /** The default migration chain: register new migrations here as the schema grows. */
    public static MigrationRunner defaults() {
        return new MigrationRunner(List.of(
            new V3WidenActionTarget(),
            new V4SignMetadata(),
            new V5NbtFidelity()
        ));
    }

    /**
     * Advance the schema up to (and including) {@code targetVersion}. If the DB
     * is already at or beyond the target, this is a no-op after the version
     * read.
     */
    public void migrate(Connection c, Schema.Dialect dialect, int targetVersion) throws SQLException {
        int current = readVersion(c);
        if (current >= targetVersion) {
            return;
        }
        LOG.info("Guardian schema at v{}, target v{}; applying {} migration(s)",
                current, targetVersion, targetVersion - current);
        for (Migration m : migrations) {
            if (m.fromVersion() < current) continue;
            if (m.toVersion() > targetVersion) break;
            if (m.fromVersion() != current) {
                // gap in chain
                throw new SQLException(
                    "Migration chain gap: at v" + current + " but next migration is " + m.label());
            }
            applyOne(c, dialect, m);
            current = m.toVersion();
        }
        if (current < targetVersion) {
            throw new SQLException(
                "Schema stuck at v" + current + " (wanted v" + targetVersion
              + "); no migration registered to advance further");
        }
    }

    /** Convenience: migrate to {@link Schema#CURRENT_VERSION}. */
    public void migrateToCurrent(Connection c, Schema.Dialect dialect) throws SQLException {
        migrate(c, dialect, Schema.CURRENT_VERSION);
    }

    private void applyOne(Connection c, Schema.Dialect dialect, Migration m) throws SQLException {
        LOG.info("Applying schema migration: {}", m.label());
        boolean prevAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            m.apply(c, dialect);
            Schema.stampVersion(c, m.toVersion());
            c.commit();
        } catch (SQLException ex) {
            try { c.rollback(); } catch (SQLException ignored) {}
            throw new SQLException(
                "Migration " + m.label() + " failed: " + ex.getMessage(), ex);
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
        }
    }

    /**
     * Read the highest version present in {@code vg_schema_version}. Returns
     * {@code 0} if the table is empty (should not happen after
     * {@link Schema#createTables} but we tolerate it), or {@code 1} if the
     * table does not exist at all (very old pre-v2 install — Schema.createTables
     * will have re-created it and stamped v{@link Schema#CURRENT_VERSION}, so in
     * practice we won't hit that branch).
     */
    static int readVersion(Connection c) throws SQLException {
        // Ensure the vg_schema_version table exists — if a fresh install just ran
        // Schema.createTables it will; if a very old install pre-dates the version
        // table, create it and treat as v1.
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS vg_schema_version ("
                     + "version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(version) FROM vg_schema_version");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int v = rs.getInt(1);
                if (rs.wasNull()) return 0;
                return v;
            }
            return 0;
        }
    }
}
