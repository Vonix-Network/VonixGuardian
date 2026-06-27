package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.storage.jdbc.MysqlDao;
import network.vonix.guardian.core.storage.jdbc.PostgresDao;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;

import java.util.concurrent.Semaphore;

/**
 * Selects a {@link GuardianDao} implementation by inspecting
 * {@link GuardianConfig.Database#type()} (one of {@code sqlite}, {@code mysql},
 * {@code postgresql}).
 *
 * <p>The factory does NOT call {@link GuardianDao#init()} — the caller (typically
 * {@code Guardian}) does so after construction.
 *
 * <p>Wires the read-side rate limit (semaphore sized at
 * {@code config.lookup().maxConcurrent()}) and the result-row cap
 * ({@code config.lookup().maxResultRows()}) into the DAO.
 */
public final class StorageFactory {

    private StorageFactory() {}

    /** Open the configured DAO. Does not initialize the schema. */
    public static GuardianDao open(GuardianConfig config) {
        if (config == null || config.database() == null || config.database().type() == null) {
            throw new IllegalArgumentException("GuardianConfig.database.type is required");
        }
        Semaphore lookupSemaphore = null;
        int maxResultRows = 0;
        if (config.lookup() != null) {
            int permits = Math.max(1, config.lookup().maxConcurrent());
            lookupSemaphore = new Semaphore(permits, true);
            maxResultRows = Math.max(0, config.lookup().maxResultRows());
        }
        String type = config.database().type().toLowerCase();
        return switch (type) {
            case "sqlite"     -> new SqliteDao(config.database(), lookupSemaphore, maxResultRows);
            case "mysql"      -> new MysqlDao(config.database(), lookupSemaphore, maxResultRows);
            case "postgres", "postgresql" -> new PostgresDao(config.database(), lookupSemaphore, maxResultRows);
            default -> throw new IllegalArgumentException("Unknown database type: " + type);
        };
    }
}
