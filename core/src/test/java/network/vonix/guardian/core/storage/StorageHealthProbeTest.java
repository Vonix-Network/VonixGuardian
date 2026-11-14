package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /vg status} reports storage health. Before 2.3.0 that check was
 * {@code SELECT 1}, which answers "is there a socket" — not "can I write an audit
 * row". These tests pin the four failures an operator actually hits.
 */
class StorageHealthProbeTest {

    private static SqliteDao daoFor(Path db, Boolean updateSchema) {
        GuardianConfig.Database cfg = new GuardianConfig.Database(
            "sqlite", db.toString(), null, null, null,
            null, GuardianConfig.Hikari.defaults(), false, updateSchema);
        return new SqliteDao(cfg);
    }

    @Test
    void healthyOnAFreshWritableDatabase(@TempDir Path dir) throws Exception {
        SqliteDao dao = daoFor(dir.resolve("vg.db"), null);
        try {
            dao.init();
            assertTrue(dao.isHealthy(), "a freshly initialised database must report healthy");
        } finally {
            dao.close();
        }
    }

    /**
     * The failure {@code SELECT 1} cannot see: the schema exists but is stamped at a
     * version this jar does not write. Inserts fail at runtime, so status must not
     * report OK.
     */
    @Test
    void unhealthyWhenSchemaIsNotAtTheExpectedVersion(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("vg-old.db");
        SqliteDao dao = daoFor(db, null);
        try {
            dao.init();
            assertTrue(dao.isHealthy());
        } finally {
            dao.close();
        }

        // Roll the stamp back the way an older jar would have left it.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE vg_schema_version SET version = 1");
        }

        SqliteDao reopened = daoFor(db, null);
        try {
            assertFalse(reopened.isHealthy(),
                "a catalog stamped at an older version cannot accept this jar's writes");
        } finally {
            reopened.close();
        }
    }

    /**
     * A read-only database answers {@code SELECT 1} perfectly and fails every insert.
     * This is the case that most often reaches production: a permissions change, or a
     * filesystem that went read-only.
     */
    @Test
    void unhealthyWhenDatabaseIsReadOnly(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("vg-ro.db");
        SqliteDao dao = daoFor(db, null);
        try {
            dao.init();
        } finally {
            dao.close();
        }

        Path dbFile = Files.exists(db) ? db : dir.resolve("vg-ro.db");
        Files.setPosixFilePermissions(dbFile, PosixFilePermissions.fromString("r--r--r--"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            SqliteDao reopened = daoFor(db, null);
            try {
                assertFalse(reopened.isHealthy(),
                    "a read-only database must not report healthy — writes will fail");
            } finally {
                reopened.close();
            }
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(dbFile, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    /**
     * {@code updateSchema=false} must not migrate. An operator pinning a schema wants
     * the boot to stop, not the catalog to move underneath their backup tooling.
     */
    @Test
    void disablingSchemaUpdatesRefusesToMigrate(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("vg-pinned.db");
        SqliteDao dao = daoFor(db, null);
        try {
            dao.init();
        } finally {
            dao.close();
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE vg_schema_version SET version = 1");
        }

        SqliteDao pinned = daoFor(db, Boolean.FALSE);
        try {
            // With migration disallowed the catalog stays at v1 and the boot fails
            // closed rather than silently upgrading.
            SQLException thrown = null;
            try {
                pinned.init();
            } catch (SQLException expected) {
                thrown = expected;
            }
            assertTrue(thrown != null,
                "boot must fail when the catalog is behind and updateSchema is false");
        } finally {
            pinned.close();
        }
    }

    /** The default when the key is absent must allow migration, as every prior release did. */
    @Test
    void absentUpdateSchemaKeyAllowsMigration() {
        GuardianConfig.Database absent = new GuardianConfig.Database(
            "sqlite", "x.db", null, null, null, null,
            GuardianConfig.Hikari.defaults(), false, null);
        assertTrue(absent.updateSchemaEnabled(),
            "an existing install with no updateSchema key must keep migrating");

        GuardianConfig.Database explicitOff = absent.withUpdateSchema(Boolean.FALSE);
        assertFalse(explicitOff.updateSchemaEnabled(),
            "explicit false must be honoured");
    }
}
