package network.vonix.guardian.core.storage.dbmigrate;

import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Orchestrates a chunked, resumable-safe copy of every VonixGuardian table from
 * a source {@link GuardianDao} to a destination {@link GuardianDao}. This is
 * the {@code /vg migrate-db} data-copy pipeline — schema migrations
 * (v1&nbsp;&rarr;&nbsp;v2&nbsp;&rarr;&nbsp;v3) live in a separate package
 * ({@code core.storage.migration}) and are unrelated.
 *
 * <h2>Preconditions</h2>
 * <ul>
 *   <li>Both DAOs must be JDBC-backed (implement {@link RawJdbcAccess}).</li>
 *   <li>Both DAOs must already be initialised, so both databases are at
 *       {@link Schema#CURRENT_VERSION}.</li>
 *   <li>The destination must be empty unless {@code force=true} is passed —
 *       preserving destination ids means a rerun over a populated destination
 *       would blow up on primary-key conflicts.</li>
 * </ul>
 *
 * <h2>Order</h2>
 * Tables are copied in the order:
 * <ol>
 *   <li>{@code vg_schema_version} — version stamp first so subsequent
 *       DAO calls against the destination see the correct schema level.</li>
 *   <li>{@code vg_worlds} — referenced by {@code vg_actions.world_id}.</li>
 *   <li>{@code vg_users} — referenced by {@code vg_actions.user_id}.</li>
 *   <li>{@code vg_actions} — the fact table.</li>
 *   <li>{@code vg_rollback_batches} — audit parent.</li>
 *   <li>{@code vg_rollback_batch_actions} — audit join (composite PK).</li>
 * </ol>
 *
 * <p>Progress is emitted every {@link #PROGRESS_ROW_INTERVAL} rows OR every
 * {@link #PROGRESS_TIME_INTERVAL_MS} ms, whichever comes first — matching the
 * cadence CoreProtect's Patreon build uses for {@code /co migrate-db}.
 *
 * @since 1.2.0
 */
public final class BackendMigrationJob {

    private static final Logger LOG = LoggerFactory.getLogger(BackendMigrationJob.class);

    /** Emit a progress update at least every 1000 rows. */
    public static final long PROGRESS_ROW_INTERVAL = 1000L;
    /** Emit a progress update at least every 5 seconds. */
    public static final long PROGRESS_TIME_INTERVAL_MS = 5_000L;

    /** Canonical copy order — see class javadoc. */
    static final List<TableSpec> TABLE_ORDER = List.of(
        new TableSpec("vg_schema_version", "version"),
        new TableSpec("vg_worlds", "id"),
        new TableSpec("vg_users", "id"),
        new TableSpec("vg_actions", "id"),
        new TableSpec("vg_rollback_batches", "id"),
        new TableSpec("vg_rollback_batch_actions", "batch_id, action_id")
    );

    /** Callback for progress notifications. */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(ProgressUpdate update);
    }

    private final GuardianDao source;
    private final GuardianDao dest;
    private final int chunkSize;
    private final ProgressCallback cb;
    private final boolean force;

    /**
     * @param source     source DAO (already {@code init()}-ed, at v{@code CURRENT_VERSION})
     * @param dest       destination DAO (already {@code init()}-ed, at v{@code CURRENT_VERSION})
     * @param chunkSize  rows per chunk (typical: 1000–5000; larger is faster
     *                   but uses more heap in the JDBC driver)
     * @param cb         progress callback; may be {@code null} for silent runs
     */
    public BackendMigrationJob(GuardianDao source,
                               GuardianDao dest,
                               int chunkSize,
                               ProgressCallback cb) {
        this(source, dest, chunkSize, cb, false);
    }

    /**
     * @param force if {@code true}, do NOT refuse when the destination is
     *              non-empty; the operator has accepted responsibility for
     *              the PK-conflict risk (e.g. they truncated manually first).
     */
    public BackendMigrationJob(GuardianDao source,
                               GuardianDao dest,
                               int chunkSize,
                               ProgressCallback cb,
                               boolean force) {
        if (source == null) throw new IllegalArgumentException("source dao is null");
        if (dest == null) throw new IllegalArgumentException("dest dao is null");
        if (!(source instanceof RawJdbcAccess)) {
            throw new IllegalArgumentException("source dao does not expose RawJdbcAccess");
        }
        if (!(dest instanceof RawJdbcAccess)) {
            throw new IllegalArgumentException("dest dao does not expose RawJdbcAccess");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        this.source = source;
        this.dest = dest;
        this.chunkSize = chunkSize;
        this.cb = cb;
        this.force = force;
    }

    /**
     * Execute the migration.
     *
     * @return per-table row counts, in the order defined by {@link #TABLE_ORDER}
     * @throws SQLException on any JDBC error mid-copy; the destination is left
     *                      at the last successful chunk boundary
     * @throws IllegalStateException if the destination is non-empty and {@code force} is false,
     *                               or if either backend is not at the current schema version
     */
    public Result run() throws SQLException {
        RawJdbcAccess src = (RawJdbcAccess) source;
        RawJdbcAccess dst = (RawJdbcAccess) dest;

        // Schema version parity check — both sides must be at CURRENT_VERSION.
        int srcVer = src.withRawConnection(BackendMigrationJob::readVersionSafe);
        int dstVer = dst.withRawConnection(BackendMigrationJob::readVersionSafe);
        if (srcVer != Schema.CURRENT_VERSION) {
            throw new IllegalStateException("source schema is v" + srcVer
                + " (expected v" + Schema.CURRENT_VERSION + "); run source .init() first");
        }
        if (dstVer != Schema.CURRENT_VERSION) {
            throw new IllegalStateException("destination schema is v" + dstVer
                + " (expected v" + Schema.CURRENT_VERSION + "); run destination .init() first");
        }

        // Emptiness check on the DATA tables. vg_schema_version is expected to
        // hold the fresh-install stamps and does NOT count.
        if (!force) {
            long destRows = dst.withRawConnection(BackendMigrationJob::countAllDataRows);
            if (destRows > 0) {
                throw new IllegalStateException(
                    "destination is not empty (" + destRows + " row(s) across data tables); "
                    + "pass force=true to accept PK-conflict risk");
            }
        }

        // Dialect for post-copy identity reset.
        final Schema.Dialect destDialect = destDialect(dest);

        TableCopier copier = new TableCopier(chunkSize);
        Result result = new Result();
        long jobStart = System.currentTimeMillis();

        for (TableSpec spec : TABLE_ORDER) {
            // vg_schema_version already carries the fresh-install stamps. Copying
            // it would raise PK conflicts on a normally-init'd destination, so
            // we skip it — the destination is guaranteed to be at
            // CURRENT_VERSION already (checked above).
            if ("vg_schema_version".equals(spec.table())) {
                if (cb != null) {
                    cb.onProgress(new ProgressUpdate(spec.table(), 0L, 0L,
                        System.currentTimeMillis() - jobStart));
                }
                result.record(spec.table(), 0L);
                continue;
            }

            final long tableStart = System.currentTimeMillis();
            final long[] lastEmit = { tableStart };
            final long[] lastRowMark = { 0L };
            final long[] totalHolder = { 0L };

            long copied = src.withRawConnection(sc ->
                dst.withRawConnection(dc -> {
                    long total = countRows(sc, spec.table());
                    totalHolder[0] = total;
                    if (cb != null) {
                        cb.onProgress(new ProgressUpdate(spec.table(), 0L, total,
                            System.currentTimeMillis() - jobStart));
                    }
                    return copier.copy(sc, dc, destDialect, spec.table(), spec.orderBy(),
                        rowsCopied -> {
                            long now = System.currentTimeMillis();
                            boolean rowGate = (rowsCopied - lastRowMark[0]) >= PROGRESS_ROW_INTERVAL;
                            boolean timeGate = (now - lastEmit[0]) >= PROGRESS_TIME_INTERVAL_MS;
                            if (cb != null && (rowGate || timeGate)) {
                                cb.onProgress(new ProgressUpdate(spec.table(), rowsCopied, total,
                                    now - jobStart));
                                lastRowMark[0] = rowsCopied;
                                lastEmit[0] = now;
                            }
                        });
                }));

            // Final per-table stamp so operators see 100% for every table.
            if (cb != null) {
                cb.onProgress(new ProgressUpdate(spec.table(), copied, totalHolder[0],
                    System.currentTimeMillis() - jobStart));
            }
            result.record(spec.table(), copied);
            LOG.info("Table {} migrated: {} rows in {} ms", spec.table(), copied,
                System.currentTimeMillis() - tableStart);
        }
        result.finish(System.currentTimeMillis() - jobStart);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static long countRows(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static int readVersionSafe(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM vg_schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Count rows across every data-carrying table (excludes
     * {@code vg_schema_version} which is expected to be non-empty on a
     * freshly-init'd install).
     */
    private static long countAllDataRows(Connection c) throws SQLException {
        long total = 0L;
        for (TableSpec spec : TABLE_ORDER) {
            if ("vg_schema_version".equals(spec.table())) continue;
            total += countRows(c, spec.table());
        }
        return total;
    }

    private static Schema.Dialect destDialect(GuardianDao dao) {
        if (dao instanceof network.vonix.guardian.core.storage.jdbc.AbstractJdbcDao a) {
            return a.currentDialect();
        }
        // Fall back to SQLite semantics (auto-managed identity) if we can't tell.
        return Schema.Dialect.SQLITE;
    }

    /** Table + ORDER BY clause. */
    record TableSpec(String table, String orderBy) {}

    /** Terminal result of a successful {@link #run()}. */
    public static final class Result {
        private final java.util.LinkedHashMap<String, Long> perTable = new java.util.LinkedHashMap<>();
        private long elapsedMs = -1L;

        void record(String table, long rows) { perTable.put(table, rows); }
        void finish(long ms) { this.elapsedMs = ms; }

        /** Row counts per table, in copy order. */
        public java.util.Map<String, Long> rowsPerTable() {
            return java.util.Collections.unmodifiableMap(perTable);
        }
        /** Sum of all per-table row counts. */
        public long totalRows() {
            return perTable.values().stream().mapToLong(Long::longValue).sum();
        }
        /** Elapsed ms once {@link #run()} returned normally; -1 if not yet finished. */
        public long elapsedMs() { return elapsedMs; }
    }

}
