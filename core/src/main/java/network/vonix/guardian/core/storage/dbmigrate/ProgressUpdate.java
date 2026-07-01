package network.vonix.guardian.core.storage.dbmigrate;

/**
 * Progress emitted by {@link BackendMigrationJob} while a table copy is in flight.
 *
 * @param table       destination table name (e.g. {@code vg_actions})
 * @param rowsCopied  rows written to the destination so far
 * @param rowsTotal   total rows in the source table (fixed at the start of the table copy)
 * @param elapsedMs   wall-clock milliseconds since the job started
 */
public record ProgressUpdate(String table, long rowsCopied, long rowsTotal, long elapsedMs) {}
