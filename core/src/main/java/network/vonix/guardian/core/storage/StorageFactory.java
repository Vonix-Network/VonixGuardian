package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.storage.jdbc.MysqlDao;
import network.vonix.guardian.core.storage.jdbc.PostgresDao;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;

/**
 * Selects a {@link GuardianDao} implementation by inspecting
 * {@link GuardianConfig.Database#type()} (one of {@code sqlite}, {@code mysql},
 * {@code postgresql}).
 *
 * <p>The factory does NOT call {@link GuardianDao#init()} — the caller (typically
 * {@code Guardian}) does so after construction.
 */
public final class StorageFactory {

    private StorageFactory() {}

    /** Open the configured DAO. Does not initialize the schema. */
    public static GuardianDao open(GuardianConfig config) {
        if (config == null || config.database() == null || config.database().type() == null) {
            throw new IllegalArgumentException("GuardianConfig.database.type is required");
        }
        String type = config.database().type().toLowerCase();
        return switch (type) {
            case "sqlite"     -> new SqliteDao(config.database());
            case "mysql"      -> new MysqlDao(config.database());
            case "postgres", "postgresql" -> new PostgresDao(config.database());
            default -> throw new IllegalArgumentException("Unknown database type: " + type);
        };
    }
}
