/**
 * Versioned, dialect-aware schema-migration harness for the VonixGuardian
 * storage layer.
 *
 * <p>The bootstrap flow in {@link network.vonix.guardian.core.storage.jdbc.AbstractJdbcDao#init()}
 * calls {@link network.vonix.guardian.core.storage.Schema#createTables} first
 * (which brings a fresh DB up to the current version via {@code CREATE TABLE IF NOT EXISTS}),
 * then hands the same connection to {@link network.vonix.guardian.core.storage.migration.MigrationRunner}
 * to apply any pending in-place ALTERs that older, already-populated databases need.
 *
 * <p>See the {@code A11-BATCH-DEGRADATION-AUDIT.md} report and the
 * {@link network.vonix.guardian.core.storage.migration.V3WidenActionTarget} javadoc
 * for the incident that motivated this harness.
 */
package network.vonix.guardian.core.storage.migration;
