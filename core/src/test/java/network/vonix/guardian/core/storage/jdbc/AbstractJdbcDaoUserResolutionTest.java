package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractJdbcDaoUserResolutionTest {

    private SqliteDao dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
    }

    @AfterEach
    void tearDown() {
        dao.close();
    }

    @Test
    void malformedNamesResolveToUnknownWithoutCollidingWithHistoricalNullRow() throws Exception {
        UUID historicalUuid = UUID.randomUUID();
        UUID validUuid = UUID.randomUUID();
        int historicalId = insertUser(historicalUuid, "null");

        assertThat(dao.insertBatch(List.of(action(validUuid, "Alice", 100L)))).isEqualTo(1);
        int validId = dao.resolveUser(validUuid, "NULL");
        assertThat(validId).isNotEqualTo(historicalId);

        Action malformed = action(null, "null", 200L);
        assertThat(dao.insertBatch(List.of(malformed))).isEqualTo(1);
        assertThat(dao.insertBatch(List.of(action(null, " NULL ", 300L)))).isEqualTo(1);
        assertThat(dao.insertBatch(List.of(action(null, "  \t", 400L)))).isEqualTo(1);
        assertThat(dao.insertBatch(List.of(action(null, null, 500L)))).isEqualTo(1);

        int unknownId = dao.resolveUser(null, "null");
        assertThat(dao.resolveUser(null, " NULL ")).isEqualTo(unknownId);
        assertThat(dao.resolveUser(null, " \t\n ")).isEqualTo(unknownId);
        assertThat(dao.resolveUser(null, null)).isEqualTo(unknownId);

        List<Action> actions = dao.query(QueryFilter.empty(), 0, 10);
        assertThat(actions).hasSize(5);
        assertThat(actions).extracting(Action::actorName)
                .containsExactlyInAnyOrder("Alice", "#unknown", "#unknown", "#unknown", "#unknown");

        List<UserRow> users = readUsers();
        assertThat(users).hasSize(3);
        assertThat(users).extracting(UserRow::name)
                .containsExactlyInAnyOrder("null", "Alice", "#unknown");
        assertThat(users).filteredOn(user -> user.name().equals("null"))
                .singleElement()
                .satisfies(user -> assertThat(user.uuid()).isEqualTo(historicalUuid.toString()));
        assertThat(users).filteredOn(user -> user.name().equals("Alice"))
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.id()).isEqualTo(validId);
                    assertThat(user.uuid()).isEqualTo(validUuid.toString());
                });
        assertThat(users).filteredOn(user -> user.name().equals("#unknown"))
                .singleElement()
                .satisfies(user -> assertThat(user.uuid()).isNull());
    }

    @Test
    void uniqueNameRaceIsReconciledByOneBoundedReread() throws Exception {
        try (Connection actual = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            network.vonix.guardian.core.storage.Schema.createTables(actual,
                    network.vonix.guardian.core.storage.Schema.Dialect.SQLITE);
            AtomicInteger insertAttempts = new AtomicInteger();
            RaceDao raceDao = new RaceDao(racingConnection(actual, insertAttempts));

            int resolvedId = raceDao.resolveUser(null, "#race");
            assertThat(raceDao.resolveUser(null, "#race")).isEqualTo(resolvedId);
            assertThat(insertAttempts).hasValue(1);

            try (PreparedStatement ps = actual.prepareStatement(
                    "SELECT COUNT(*), MIN(name) FROM vg_users WHERE name = '#race'" );
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.getString(2)).isEqualTo("#race");
            }
        }
    }

    @Test
    void uuidBearingMalformedNameNeverReusesAnonymousSentinel() throws Exception {
        int anonymousId = dao.resolveUser(null, null);
        UUID uuid = UUID.randomUUID();

        int uuidId = dao.resolveUser(uuid, "NULL");
        assertThat(uuidId).isNotEqualTo(anonymousId);
        assertThat(dao.resolveUser(uuid, "Alice")).isEqualTo(uuidId);

        List<UserRow> users = readUsers();
        assertThat(users).hasSize(2);
        assertThat(users).filteredOn(user -> user.id() == anonymousId)
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.uuid()).isNull();
                    assertThat(user.name()).isEqualTo("#unknown");
                });
        assertThat(users).filteredOn(user -> user.id() == uuidId)
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.uuid()).isEqualTo(uuid.toString());
                    assertThat(user.name()).isEqualTo("#unknown:" + uuid);
                });
    }

    @Test
    void validNameCollisionUsesStableUuidAliasAndDoesNotCreateRowsRepeatedly() throws Exception {
        UUID historicalUuid = UUID.randomUUID();
        UUID currentUuid = UUID.randomUUID();
        int historicalId = insertUser(historicalUuid, "ItemFrame");

        int currentId = dao.resolveUser(currentUuid, "ItemFrame");
        assertThat(currentId).isNotEqualTo(historicalId);
        assertThat(dao.resolveUser(currentUuid, "ItemFrame")).isEqualTo(currentId);

        List<UserRow> users = readUsers();
        assertThat(users).hasSize(2);
        assertThat(users).filteredOn(user -> user.id() == historicalId)
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.uuid()).isEqualTo(historicalUuid.toString());
                    assertThat(user.name()).isEqualTo("ItemFrame");
                });
        assertThat(users).filteredOn(user -> user.id() == currentId)
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.uuid()).isEqualTo(currentUuid.toString());
                    assertThat(user.name()).startsWith("ItemFrame#");
                    assertThat(user.name()).endsWith(currentUuid.toString());
                });
    }

    @Test
    void uuidlessValidNameCollisionUsesAnonymousAliasWithoutBorrowingUuidIdentity() throws Exception {
        UUID historicalUuid = UUID.randomUUID();
        int historicalId = insertUser(historicalUuid, "ItemFrame");

        int anonymousId = dao.resolveUser(null, "ItemFrame");
        assertThat(anonymousId).isNotEqualTo(historicalId);
        assertThat(dao.resolveUser(null, "ItemFrame")).isEqualTo(anonymousId);

        List<UserRow> users = readUsers();
        assertThat(users).hasSize(2);
        assertThat(users).filteredOn(user -> user.id() == historicalId)
                .singleElement()
                .satisfies(user -> assertThat(user.uuid()).isEqualTo(historicalUuid.toString()));
        assertThat(users).filteredOn(user -> user.id() == anonymousId)
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.uuid()).isNull();
                    assertThat(user.name()).startsWith("#anonymous:");
                });
    }

    @Test
    void duplicateNameRecoveryRollsBackToSavepointBeforePostgresStyleReread() throws Exception {
        try (Connection actual = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            network.vonix.guardian.core.storage.Schema.createTables(actual,
                    network.vonix.guardian.core.storage.Schema.Dialect.SQLITE);
            actual.setAutoCommit(false);
            UUID historicalUuid = UUID.randomUUID();
            UUID currentUuid = UUID.randomUUID();
            int historicalId;
            try (PreparedStatement ps = actual.prepareStatement(
                    "INSERT INTO vg_users(uuid, name, first_seen, last_seen) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, historicalUuid.toString());
                ps.setString(2, "ItemFrame");
                ps.setLong(3, 1L);
                ps.setLong(4, 1L);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    historicalId = keys.getInt(1);
                }
            }
            actual.commit();

            AtomicInteger savepointRollbacks = new AtomicInteger();
            RaceDao raceDao = new RaceDao(postgresAbortConnection(actual, savepointRollbacks));
            int currentId = raceDao.resolveUser(currentUuid, "ItemFrame");
            actual.commit();

            assertThat(currentId).isNotEqualTo(historicalId);
            assertThat(savepointRollbacks).hasValue(1);
            try (PreparedStatement ps = actual.prepareStatement(
                    "SELECT uuid, name FROM vg_users WHERE id = ?")) {
                ps.setInt(1, currentId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo(currentUuid.toString());
                    assertThat(rs.getString(2)).endsWith(currentUuid.toString());
                }
            }
        }
    }

    @Test
    void uniqueClassifierDoesNotTreatOtherIntegrityFailuresAsDuplicateRaces() {
        assertThat(AbstractJdbcDao.isUniqueConstraintViolation(
                new SQLException("FOREIGN KEY constraint failed", "23000", 1452))).isFalse();
        assertThat(AbstractJdbcDao.isUniqueConstraintViolation(
                new SQLException("UNIQUE constraint failed: vg_users.name", "23000", 19))).isTrue();
        assertThat(AbstractJdbcDao.isUniqueConstraintViolation(
                new SQLException("duplicate entry 'null' for key 'name'", "23000", 1062))).isTrue();
        assertThat(AbstractJdbcDao.isUniqueConstraintViolation(
                new SQLException("duplicate key", "23505", 0))).isTrue();
    }

    private int insertUser(UUID uuid, String name) throws Exception {
        return dao.withRawConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO vg_users(uuid, name, first_seen, last_seen) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, 1L);
                ps.setLong(4, 1L);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    return keys.getInt(1);
                }
            }
        });
    }

    private List<UserRow> readUsers() throws Exception {
        return dao.withRawConnection(connection -> {
            List<UserRow> users = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, uuid, name FROM vg_users ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    users.add(new UserRow(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
            return users;
        });
    }

    private static Action action(UUID uuid, String name, long timestamp) {
        return new Action(-1L, timestamp, ActionType.BLOCK_PLACE, uuid, name,
                "minecraft:overworld", 0, 64, 0, "minecraft:stone", null,
                1, false, null);
    }

    private static Connection racingConnection(Connection actual, AtomicInteger insertAttempts) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String sql
                            && sql.startsWith("INSERT INTO vg_users")) {
                        PreparedStatement delegate = (PreparedStatement) invoke(actual, method, args);
                        return racingStatement(delegate, actual, insertAttempts);
                    }
                    return invoke(actual, method, args);
                });
    }

    private static PreparedStatement racingStatement(PreparedStatement delegate, Connection actual,
                                                      AtomicInteger insertAttempts) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName())
                            && (args == null || args.length == 0)
                            && insertAttempts.getAndIncrement() == 0) {
                        try (PreparedStatement competitor = actual.prepareStatement(
                                "INSERT INTO vg_users(uuid, name, first_seen, last_seen) VALUES (?,?,?,?)")) {
                            competitor.setNull(1, Types.VARCHAR);
                            competitor.setString(2, "#race");
                            competitor.setLong(3, 1L);
                            competitor.setLong(4, 1L);
                            competitor.executeUpdate();
                        }
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Connection postgresAbortConnection(Connection actual, AtomicInteger savepointRollbacks) {
        AtomicInteger aborted = new AtomicInteger();
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("rollback".equals(methodName) && args != null && args.length == 1
                            && args[0] instanceof Savepoint) {
                        Object result = invoke(actual, method, args);
                        aborted.set(0);
                        savepointRollbacks.incrementAndGet();
                        return result;
                    }
                    if ("prepareStatement".equals(methodName) && aborted.get() != 0) {
                        throw new SQLException("current transaction is aborted, commands ignored until end of transaction block",
                                "25P02");
                    }
                    if ("prepareStatement".equals(methodName) && args != null && args.length > 0
                            && args[0] instanceof String sql && sql.startsWith("INSERT INTO vg_users")) {
                        PreparedStatement delegate = (PreparedStatement) invoke(actual, method, args);
                        return abortingStatement(delegate, aborted);
                    }
                    return invoke(actual, method, args);
                });
    }

    private static PreparedStatement abortingStatement(PreparedStatement delegate, AtomicInteger aborted) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    try {
                        return invoke(delegate, method, args);
                    } catch (SQLException ex) {
                        if (AbstractJdbcDao.isUniqueConstraintViolation(ex)) {
                            aborted.set(1);
                        }
                        throw ex;
                    }
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    private static final class RaceDao extends AbstractJdbcDao {
        private final Connection connection;

        private RaceDao(Connection connection) {
            this.connection = connection;
        }

        @Override
        protected Connection borrow() {
            return connection;
        }

        @Override
        protected void release(Connection connection) {
        }

        @Override
        protected network.vonix.guardian.core.storage.Schema.Dialect dialect() {
            return network.vonix.guardian.core.storage.Schema.Dialect.SQLITE;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private record UserRow(int id, String uuid, String name) {
    }
}
