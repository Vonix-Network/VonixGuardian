/**
 * Backend-migration (data copy) pipeline for {@code /vg migrate-db}.
 *
 * <p>This is <em>not</em> the schema-migration package. Schema migrations
 * (v1 → v2 → v3) live in {@link network.vonix.guardian.core.storage.migration};
 * this package copies row data verbatim between two initialised DAOs that
 * both already sit at {@link network.vonix.guardian.core.storage.Schema#CURRENT_VERSION}.
 */
package network.vonix.guardian.core.storage.dbmigrate;
