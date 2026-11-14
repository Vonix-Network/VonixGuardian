package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.Schema;
import network.vonix.guardian.core.storage.jdbc.AbstractJdbcDao;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Purge must not leave the identity tables to grow forever.
 *
 * <p>{@code vg_users} and {@code vg_worlds} are populated on demand from
 * {@code resolveUserOn} / {@code resolveWorldOn} while inserting actions, and
 * nothing has ever deleted from them. A purge therefore stranded one row per
 * player and per world that was only referenced by the rows it removed.
 *
 * <h3>Why one case asserts "kept"</h3>
 * <p>The JDBC DAO short-circuits {@code resolveUser} / {@code resolveWorld}
 * through in-memory caches. Deleting a row whose id is still cached would make
 * the next insert write an id with no row, and {@code QueryCompiler} inner-joins
 * both tables — so those actions would be invisible to every lookup. Reclaim is
 * therefore gated on {@link PurgeEngine.ResolvedIdCacheControl}; these tests pin
 * both halves of that gate, including the refusal.
 */
class PurgeOrphanReclaimTest {

    private static final long DAY_MS = 86_400_000L;
    private static final long MIN_AGE = 30L * 86_400L;
    private static final String OLD_WORLD = "minecraft:overworld";

    // ------------------------------------------------------------------ reclaim

    @Test
    void purgeReclaimsUsersAndWorldsThatNoActionReferencesAnyMore(@TempDir Path tmp) throws Exception {
        try (ReclaimableDao dao = new ReclaimableDao(tmp.resolve("guardian.db").toString())) {
            dao.init();
            long now = System.currentTimeMillis();
            UUID gone = UUID.nameUUIDFromBytes("gone".getBytes());
            UUID stays = UUID.nameUUIDFromBytes("stays".getBytes());
            String deadWorld = "minecraft:the_end";

            dao.insertBatch(List.of(
                action(now - 40L * DAY_MS, gone, "GonePlayer", deadWorld, "old"),
                action(now - DAY_MS, stays, "Stays", OLD_WORLD, "recent")
            ));
            assertThat(dao.countUsers()).isEqualTo(2);
            assertThat(dao.countWorlds()).isEqualTo(2);

            PurgeEngine.PurgeResult result =
                new PurgeEngine(dao).purge(olderThan(now - 30L * DAY_MS), MIN_AGE);

            assertThat(result.deletedCount()).isEqualTo(1L);
            // The load-bearing assertion: the purged action's player and world
            // are gone from the identity tables, and the still-referenced ones
            // are untouched.
            assertThat(dao.userNames()).containsExactly("Stays");
            assertThat(dao.worldKeys()).containsExactly(OLD_WORLD);
            assertThat(result.orphans().usersReclaimed()).isEqualTo(1L);
            assertThat(result.orphans().worldsReclaimed()).isEqualTo(1L);
            assertThat(result.orphans().usersKept()).isZero();
            assertThat(result.orphans().worldsKept()).isZero();
            assertThat(result.orphans().keptReason()).isNull();
            // Invalidate before the delete (prove control) and after (catch a
            // resolve that raced the delete).
            assertThat(dao.invalidations()).isEqualTo(2);
        }
    }

    @Test
    void referencedIdentityRowsSurviveEvenWhenTheActionIsOld(@TempDir Path tmp) throws Exception {
        try (ReclaimableDao dao = new ReclaimableDao(tmp.resolve("guardian.db").toString())) {
            dao.init();
            long now = System.currentTimeMillis();
            UUID player = UUID.nameUUIDFromBytes("busy".getBytes());
            // Two actions for one player: an old one that the purge removes and
            // a recent one it keeps. The user row is still referenced, so it
            // must not be reclaimed.
            dao.insertBatch(List.of(
                action(now - 40L * DAY_MS, player, "Busy", OLD_WORLD, "old"),
                action(now - DAY_MS, player, "Busy", OLD_WORLD, "recent")
            ));

            PurgeEngine.PurgeResult result =
                new PurgeEngine(dao).purge(olderThan(now - 30L * DAY_MS), MIN_AGE);

            assertThat(result.deletedCount()).isEqualTo(1L);
            assertThat(dao.userNames()).containsExactly("Busy");
            assertThat(dao.worldKeys()).containsExactly(OLD_WORLD);
            assertThat(result.orphans().usersReclaimed()).isZero();
            assertThat(result.orphans().worldsReclaimed()).isZero();
        }
    }

    @Test
    void reclaimIsRefusedWhenTheDaoCannotInvalidateItsResolvedIdCaches(@TempDir Path tmp) throws Exception {
        // SqliteDao resolves through AbstractJdbcDao's caches and does not
        // implement ResolvedIdCacheControl, so deleting here would leave cached
        // ids pointing at no row. The purge must say so instead of doing it.
        try (SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("guardian.db"))) {
            dao.init();
            long now = System.currentTimeMillis();
            UUID gone = UUID.nameUUIDFromBytes("gone".getBytes());
            dao.insertBatch(List.of(action(now - 40L * DAY_MS, gone, "GonePlayer", OLD_WORLD, "old")));
            int usersBefore = countRows(dao, "vg_users");
            assertThat(usersBefore).isEqualTo(1);

            PurgeEngine.PurgeResult result =
                new PurgeEngine(dao).purge(olderThan(now - 30L * DAY_MS), MIN_AGE);

            assertThat(result.deletedCount()).isEqualTo(1L);
            // Not even counted: the gate refuses before the first statement, so
            // the report cannot read as "clean database".
            assertThat(result.orphans().scanned()).isFalse();
            assertThat(result.orphans().usersBefore()).isEqualTo(PurgeEngine.ORPHAN_COUNT_UNKNOWN);
            assertThat(result.orphans().usersReclaimed()).isZero();
            assertThat(result.orphans().anyKept()).isTrue();
            assertThat(result.orphans().keptReason()).contains("cannot invalidate");
            // The row is still there — refusing means refusing, not half-doing.
            assertThat(countRows(dao, "vg_users")).isEqualTo(usersBefore);
            // A refusal must not fail the purge, and it must be visible.
            assertThat(result.orphans().lines())
                .anySatisfy(line -> assertThat(line)
                    .contains("unused player row")
                    .contains("remain")
                    .contains("cannot invalidate"));
        }
    }

    @Test
    void aFailedReclaimDoesNotFailThePurge(@TempDir Path tmp) throws Exception {
        try (BrokenRawAccessDao dao = new BrokenRawAccessDao(tmp.resolve("guardian.db").toString())) {
            dao.init();
            long now = System.currentTimeMillis();
            dao.insertBatch(List.of(action(now - 40L * DAY_MS, UUID.randomUUID(), "Any", OLD_WORLD, "old")));

            PurgeEngine.PurgeResult result =
                new PurgeEngine(dao).purge(olderThan(now - 30L * DAY_MS), MIN_AGE);

            assertThat(result.deletedCount()).isEqualTo(1L);
            assertThat(result.matchedCount()).isEqualTo(1L);
            assertThat(result.orphans().scanned()).isTrue();
            assertThat(result.orphans().usersBefore()).isEqualTo(PurgeEngine.ORPHAN_COUNT_UNKNOWN);
            assertThat(result.orphans().keptReason()).contains("orphan reclaim failed");
        }
    }

    @Test
    void dryRunDoesNotReclaimAnything(@TempDir Path tmp) throws Exception {
        try (ReclaimableDao dao = new ReclaimableDao(tmp.resolve("guardian.db").toString())) {
            dao.init();
            long now = System.currentTimeMillis();
            dao.insertBatch(List.of(action(now - 40L * DAY_MS, UUID.randomUUID(), "Gone", OLD_WORLD, "old")));

            QueryFilter count = QueryFilter.builder()
                .sinceMillis(now - 30L * DAY_MS)
                .countOnly(true)
                .build();
            PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(count, MIN_AGE);

            assertThat(result.dryRun()).isTrue();
            assertThat(dao.countUsers()).isEqualTo(1);
            assertThat(result.orphans().scanned()).isFalse();
            assertThat(dao.invalidations()).isZero();
        }
    }

    @Test
    void reclaimCountsAreNeverReportedAsZeroWhenTheyAreUnknown() {
        PurgeEngine.OrphanReport unknown =
            new PurgeEngine.OrphanReport(true, PurgeEngine.ORPHAN_COUNT_UNKNOWN,
                PurgeEngine.ORPHAN_COUNT_UNKNOWN, 0L, 0L, "backend could not count");

        assertThat(unknown.usersKept()).isEqualTo(PurgeEngine.ORPHAN_COUNT_UNKNOWN);
        assertThat(unknown.worldsKept()).isEqualTo(PurgeEngine.ORPHAN_COUNT_UNKNOWN);
        assertThat(unknown.anyKept()).isTrue();
        assertThat(unknown.reclaimedAny()).isFalse();
        assertThat(unknown.lines()).allSatisfy(line -> assertThat(line).contains("unknown number"));
    }

    @Test
    void operatorTextIsSilentWhenThereIsNothingToReport() {
        assertThat(PurgeEngine.OrphanReport.notApplicable().lines()).isEmpty();
        assertThat(new PurgeEngine.OrphanReport(true, 0L, 0L, 0L, 0L, null).lines()).isEmpty();
        assertThat(new PurgeEngine.OrphanReport(true, 2L, 1L, 2L, 1L, null).lines())
            .containsExactly("[VonixGuardian] Purge reclaimed 2 unused player rows "
                + "and 1 unused world row.");
        // Singular/plural must not read as "1 unused player rows".
        assertThat(new PurgeEngine.OrphanReport(true, 1L, 0L, 1L, 0L, null).lines())
            .containsExactly("[VonixGuardian] Purge reclaimed 1 unused player row "
                + "and 0 unused world rows.");
    }

    // ------------------------------------------------------------------ fixtures

    private static QueryFilter olderThan(long sinceMillis) {
        return QueryFilter.builder().sinceMillis(sinceMillis).build();
    }

    private static Action action(long ts, UUID actor, String actorName, String world, String tag) {
        return new Action(-1L, ts, ActionType.BLOCK_PLACE, actor, actorName, world,
            0, 64, 0, "minecraft:stone", null, 1, false, tag);
    }

    /** Independent of the engine's own SQL: read the count straight off the DB. */
    private static int countRows(SqliteDao dao, String table) throws SQLException {
        return dao.withRawConnection(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /**
     * SQLite-backed DAO that declares it can drop its resolved-id caches, which
     * is the condition {@link PurgeEngine#reclaimOrphansAfterDelete()} requires
     * before deleting an identity row.
     *
     * <p>{@link AbstractJdbcDao} keeps those caches private and does not
     * implement the interface, so this test double records the invalidation
     * calls instead of clearing them. What it proves is the contract the engine
     * depends on: the engine invalidates before and after the delete, and it
     * refuses to delete when the DAO makes no such promise. The caches the
     * engine must actually clear belong to {@code AbstractJdbcDao}; wiring that
     * is the one remaining production step for this defect.
     */
    private static final class ReclaimableDao extends AbstractJdbcDao
            implements PurgeEngine.ResolvedIdCacheControl {

        private final String url;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Connection connection;
        private int invalidations;

        ReclaimableDao(String file) {
            this.url = "jdbc:sqlite:" + file;
        }

        @Override protected Connection borrow() throws SQLException {
            lock.lock();
            try {
                if (connection == null || connection.isClosed()) {
                    connection = DriverManager.getConnection(url);
                }
                return connection;
            } catch (SQLException ex) {
                lock.unlock();
                throw ex;
            }
        }

        @Override protected void release(Connection c) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        @Override protected Schema.Dialect dialect() { return Schema.Dialect.SQLITE; }
        @Override public boolean isHealthy() { return true; }

        @Override
        public void invalidateResolvedIdCaches() {
            invalidations++;
        }

        int invalidations() {
            return invalidations;
        }

        int countUsers() throws SQLException {
            return count("SELECT COUNT(*) FROM vg_users");
        }

        int countWorlds() throws SQLException {
            return count("SELECT COUNT(*) FROM vg_worlds");
        }

        List<String> userNames() throws SQLException {
            return strings("SELECT name FROM vg_users ORDER BY name");
        }

        List<String> worldKeys() throws SQLException {
            return strings("SELECT world_key FROM vg_worlds ORDER BY world_key");
        }

        private int count(String sql) throws SQLException {
            return withRawConnection(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
        }

        private List<String> strings(String sql) throws SQLException {
            return withRawConnection(c -> {
                List<String> out = new ArrayList<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                return out;
            });
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (connection != null) {
                    try { connection.close(); } catch (SQLException ignored) {}
                    connection = null;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /** SQLite-backed DAO whose raw JDBC access fails, to prove purge survives it. */
    private static final class BrokenRawAccessDao extends AbstractJdbcDao
            implements PurgeEngine.ResolvedIdCacheControl {

        private final String url;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Connection connection;

        BrokenRawAccessDao(String file) {
            this.url = "jdbc:sqlite:" + file;
        }

        @Override protected Connection borrow() throws SQLException {
            lock.lock();
            try {
                if (connection == null || connection.isClosed()) {
                    connection = DriverManager.getConnection(url);
                }
                return connection;
            } catch (SQLException ex) {
                lock.unlock();
                throw ex;
            }
        }

        @Override protected void release(Connection c) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        @Override protected Schema.Dialect dialect() { return Schema.Dialect.SQLITE; }
        @Override public boolean isHealthy() { return true; }

        @Override
        public void invalidateResolvedIdCaches() { /* nothing cached in the fake */ }

        @Override
        public <T> T withRawConnection(network.vonix.guardian.core.storage.dbmigrate.RawJdbcAccess.SqlAction<T> action)
                throws SQLException {
            throw new SQLException("raw access unavailable in this backend");
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (connection != null) {
                    try { connection.close(); } catch (SQLException ignored) {}
                    connection = null;
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
