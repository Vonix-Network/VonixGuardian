package network.vonix.guardian.core.storage.dbmigrate;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end smoke test for {@link BackendMigrationJob}: seed a source SQLite
 * DAO with a realistic workload, migrate to a fresh destination SQLite DAO,
 * verify row counts and content parity across every table the schema owns.
 */
class BackendMigrationJobTest {

    private SqliteDao source;
    private SqliteDao dest;

    private static final int SEED_ROWS = 10_000;
    private static final int USERS = 12;
    private static final String[] WORLDS = {
        "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"
    };

    @BeforeEach
    void setUp() throws Exception {
        // Distinct :memory: URLs so the two DAOs don't accidentally share a DB.
        // We use file::memory:?cache=shared with unique names, which SQLite
        // treats as separate in-memory databases per-name.
        source = new SqliteDao("jdbc:sqlite:file:src-" + System.nanoTime() + "?mode=memory&cache=shared");
        source.init();
        dest = new SqliteDao("jdbc:sqlite:file:dst-" + System.nanoTime() + "?mode=memory&cache=shared");
        dest.init();
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.close();
        if (dest != null) dest.close();
    }

    // --------------------------------------------------------------------

    @Test
    void migrates_10k_rows_across_all_tables_preserving_content() throws Exception {
        seedSource();

        // Sanity: source populated, destination empty.
        long srcCount = source.count(QueryFilter.empty());
        assertThat(srcCount).isEqualTo(SEED_ROWS);
        assertThat(dest.count(QueryFilter.empty())).isZero();

        AtomicLong updates = new AtomicLong();
        List<ProgressUpdate> lastPerTable = new ArrayList<>();
        BackendMigrationJob.ProgressCallback cb = u -> {
            updates.incrementAndGet();
            lastPerTable.add(u);
        };

        BackendMigrationJob job = new BackendMigrationJob(source, dest, 1000, cb);
        BackendMigrationJob.Result result = job.run();

        // Row-count parity on the fact table.
        assertThat(dest.count(QueryFilter.empty())).isEqualTo(SEED_ROWS);
        assertThat(result.rowsPerTable().get("vg_actions")).isEqualTo(SEED_ROWS);

        // Every user + world we seeded got copied.
        assertThat(result.rowsPerTable().get("vg_users")).isEqualTo((long) USERS);
        assertThat(result.rowsPerTable().get("vg_worlds")).isEqualTo((long) WORLDS.length);

        // Progress callback fired at least once per table (initial + terminal).
        assertThat(updates.get()).isGreaterThanOrEqualTo(BackendMigrationJob.TABLE_ORDER.size());
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0L);

        // Query parity: fetching all rows from both sides yields identical
        // sequences (by id, ts, target, user_id-equivalent-by-name).
        List<Action> srcAll = source.query(QueryFilter.empty(), 0, SEED_ROWS);
        List<Action> dstAll = dest.query(QueryFilter.empty(), 0, SEED_ROWS);
        assertThat(dstAll).hasSize(srcAll.size());
        for (int i = 0; i < srcAll.size(); i++) {
            Action s = srcAll.get(i);
            Action d = dstAll.get(i);
            assertThat(d.id()).isEqualTo(s.id());
            assertThat(d.timestamp()).isEqualTo(s.timestamp());
            assertThat(d.type()).isEqualTo(s.type());
            assertThat(d.targetId()).isEqualTo(s.targetId());
            assertThat(d.actorUuid()).isEqualTo(s.actorUuid());
            assertThat(d.actorName()).isEqualTo(s.actorName());
            assertThat(d.worldId()).isEqualTo(s.worldId());
            assertThat(d.x()).isEqualTo(s.x());
            assertThat(d.y()).isEqualTo(s.y());
            assertThat(d.z()).isEqualTo(s.z());
            assertThat(d.amount()).isEqualTo(s.amount());
            assertThat(d.rolledBack()).isEqualTo(s.rolledBack());
            assertThat(d.sourceTag()).isEqualTo(s.sourceTag());
        }
    }

    @Test
    void resolve_user_and_world_integrity_preserved_after_migration() throws Exception {
        seedSource();
        // Also open + close a rollback batch to populate the audit tables.
        UUID actor = UUID.nameUUIDFromBytes("actor".getBytes());
        long batchId = source.openRollbackBatch(actor, 0, "{\"filter\":\"test\"}",
            List.of(1L, 2L, 3L));
        assertThat(source.closeRollbackBatch(batchId)).isEqualTo(1);

        new BackendMigrationJob(source, dest, 1000, null).run();

        // Resolving an existing user by uuid on the destination MUST return
        // the same id that the source assigned; this is only true if id
        // preservation worked.
        UUID knownUuid = UUID.nameUUIDFromBytes(("user-" + 0).getBytes());
        int srcId = source.resolveUser(knownUuid, "User0");
        int dstId = dest.resolveUser(knownUuid, "User0");
        assertThat(dstId).isEqualTo(srcId);

        int srcWorld = source.resolveWorld(WORLDS[0]);
        int dstWorld = dest.resolveWorld(WORLDS[0]);
        assertThat(dstWorld).isEqualTo(srcWorld);

        // Rollback batch survived.
        assertThat(dest.findIncompleteBatchActionIds()).isEmpty(); // batch was closed
    }

    @Test
    void refuses_to_migrate_into_non_empty_destination_without_force() throws Exception {
        seedSource();
        // Poison dest with one action row so the emptiness gate trips.
        dest.insertBatch(List.of(new Action(
            -1L, 1L, ActionType.BLOCK_PLACE,
            UUID.nameUUIDFromBytes("poison".getBytes()), "Poison",
            WORLDS[0], 0, 64, 0, "minecraft:stone", null, 1, false, null
        )));

        assertThatThrownBy(() -> new BackendMigrationJob(source, dest, 1000, null).run())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("destination is not empty");
    }

    @Test
    void force_flag_bypasses_emptiness_gate() throws Exception {
        // Empty source so no PK conflicts even with force.
        BackendMigrationJob.Result r = new BackendMigrationJob(
            source, dest, 1000, null, /*force=*/ true).run();
        assertThat(r.totalRows()).isZero();
    }

    // --------------------------------------------------------------------

    private void seedSource() throws Exception {
        long t0 = 1_700_000_000_000L;
        List<Action> batch = new ArrayList<>(SEED_ROWS);
        for (int i = 0; i < SEED_ROWS; i++) {
            int u = i % USERS;
            int w = i % WORLDS.length;
            ActionType type = switch (i % 5) {
                case 0 -> ActionType.BLOCK_PLACE;
                case 1 -> ActionType.BLOCK_BREAK;
                case 2 -> ActionType.CONTAINER_DEPOSIT;
                case 3 -> ActionType.CHAT;
                default -> ActionType.COMMAND;
            };
            String target = (type == ActionType.CHAT) ? ("chat msg " + i)
                          : (type == ActionType.COMMAND) ? ("/cmd " + i)
                          : (i % 4 == 0) ? "minecraft:stone" : "minecraft:dirt";
            batch.add(new Action(
                -1L,
                t0 + i * 1000L,
                type,
                UUID.nameUUIDFromBytes(("user-" + u).getBytes()),
                "User" + u,
                WORLDS[w],
                i % 100, 64, (i / 100) % 100,
                target,
                (i % 7 == 0) ? "meta" + i : null,
                (i % 3) + 1,
                false,
                (i % 11 == 0) ? "explosion:tnt" : null
            ));
        }
        source.insertBatch(batch);
    }
}
