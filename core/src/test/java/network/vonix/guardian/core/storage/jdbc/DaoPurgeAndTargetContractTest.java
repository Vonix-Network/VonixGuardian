package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-correctness contracts for {@link AbstractJdbcDao}:
 * owner-table reclamation on purge, honest {@code markRolledBack} framing, the
 * {@code target} width guard, and a genuinely hot-swappable result cap.
 */
class DaoPurgeAndTargetContractTest {

    private static final long T0 = 1_700_000_000_000L;

    private SqliteDao dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    private static Action action(UUID actor, String name, String world, long ts, String target) {
        return new Action(-1L, ts, ActionType.BLOCK_PLACE, actor, name, world,
            0, 64, 0, target, null, 1, false, null);
    }

    // ---------------------------------------------------------------- purge

    @Test
    void purge_reclaims_owner_rows_left_unreferenced() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        UUID bob = UUID.nameUUIDFromBytes("Bob".getBytes());

        // Alice is resolved but never logs an action: her vg_users row is a
        // pre-existing orphan, exactly the residue every past purge left behind.
        dao.resolveUser(alice, "Alice");
        assertThat(dao.countOrphanOwners())
            .as("a resolved-but-unreferenced user is an orphan")
            .isEqualTo(1);

        dao.insertBatch(List.of(
            action(bob, "Bob", "minecraft:the_nether", T0, "minecraft:netherrack")));

        long deleted = dao.purge(QueryFilter.empty());

        assertThat(deleted).isEqualTo(1);
        // The purge must reclaim Alice's stranded user row and Bob's now-dangling
        // owner rows in the same operation. Before this fix both survived forever.
        assertThat(dao.countOrphanOwners()).isZero();
    }

    @Test
    void purge_keeps_owner_rows_still_referenced_by_a_surviving_action() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        // Two actions for Alice; purge one, Alice's row must stay.
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, "minecraft:stone"),
            action(alice, "Alice", "minecraft:overworld", T0 + 100, "minecraft:dirt")
        ));

        long deleted = dao.purge(QueryFilter.builder().untilMillis(T0).build());

        assertThat(deleted).isEqualTo(1);
        assertThat(dao.countOrphanOwners())
            .as("Alice still owns the surviving action")
            .isZero();

        // And she must remain resolvable without being re-inserted under a new id.
        int idAfter = dao.resolveUser(alice, "Alice");
        assertThat(idAfter).isPositive();
        assertThat(dao.query(QueryFilter.empty(), 0, 10)).hasSize(1);
    }

    @Test
    void own_world_keys_survive_a_purge_that_still_references_them() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, "minecraft:stone"),
            action(alice, "Alice", "minecraft:overworld", T0 + 100, "minecraft:dirt")
        ));

        dao.purge(QueryFilter.builder().untilMillis(T0).build());

        // The surviving row still joins to vg_worlds, so a lookup must see it.
        assertThat(dao.query(QueryFilter.empty(), 0, 10))
            .singleElement()
            .satisfies(a -> assertThat(a.worldId()).isEqualTo("minecraft:overworld"));
    }

    @Test
    void auto_purge_reclaims_orphans_at_the_end_of_the_retention_horizon() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        UUID bob = UUID.nameUUIDFromBytes("Bob".getBytes());
        dao.insertBatch(List.of(
            // Alice survives the horizon; Bob does not.
            action(alice, "Alice", "minecraft:overworld", T0, "minecraft:stone"),
            action(alice, "Alice", "minecraft:overworld", T0 + 100_000L, "minecraft:dirt"),
            action(bob, "Bob", "minecraft:the_nether", T0 + 1, "minecraft:netherrack")
        ));

        // chunkLimit larger than the eligible set -> short chunk -> final round,
        // which is where the sweep belongs.
        long removed = dao.purgeOlderThan(T0 + 1, 50);

        assertThat(removed).isEqualTo(2);
        // Bob's owner rows are now unreferenced and must be gone; Alice keeps
        // hers because her second action survives.
        assertThat(dao.countOrphanOwners()).isZero();
        assertThat(dao.query(QueryFilter.empty(), 0, 10))
            .singleElement()
            .satisfies(a -> assertThat(a.actorName()).isEqualTo("Alice"));
    }

    @Test
    void auto_purge_mid_horizon_round_defers_the_sweep_but_leaves_consistent_state()
            throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        List<Action> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(action(alice, "Alice", "minecraft:overworld", T0 + i, "minecraft:stone"));
        }
        dao.insertBatch(batch);

        // A full chunk cannot be the last round, so no sweep runs here.
        assertThat(dao.purgeOlderThan(T0 + 100, 3)).isEqualTo(3);

        // Owner row is still referenced by the seven survivors — no orphans.
        assertThat(dao.countOrphanOwners()).isZero();
    }

    // ------------------------------------------------------- markRolledBack

    @Test
    void mark_rolled_back_reports_the_real_update_count() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, "minecraft:stone"),
            action(alice, "Alice", "minecraft:overworld", T0 + 1, "minecraft:dirt")
        ));
        List<Long> ids = dao.query(QueryFilter.empty(), 0, 10).stream()
            .map(Action::id).toList();

        assertThat(dao.markRolledBack(ids, true)).isEqualTo(2);
        assertThat(dao.query(QueryFilter.empty(), 0, 10)).allMatch(Action::rolledBack);
    }

    @Test
    void mark_rolled_back_of_a_vanished_id_fails_closed() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, "minecraft:stone")));
        List<Long> ids = dao.query(QueryFilter.empty(), 0, 10).stream()
            .map(Action::id).toList();
        dao.purge(QueryFilter.empty());

        assertThatThrownBy(() -> dao.markRolledBack(ids, true))
            .as("a stamp that matched nothing must not read as success")
            .isInstanceOf(java.sql.SQLException.class)
            .hasMessageContaining("none of those rows exist");
    }

    @Test
    void mark_rolled_back_twice_is_idempotent_not_a_failure() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, "minecraft:stone")));
        List<Long> ids = dao.query(QueryFilter.empty(), 0, 10).stream()
            .map(Action::id).toList();

        assertThat(dao.markRolledBack(ids, true)).isEqualTo(1);
        // The rows are still present and already carry this value. Whether the
        // driver reports "matched" or "changed" here is backend-specific, so the
        // assertion is the invariant that matters: no failure, state still right.
        dao.markRolledBack(ids, true);
        assertThat(dao.query(QueryFilter.empty(), 0, 10))
            .singleElement()
            .satisfies(a -> assertThat(a.rolledBack()).isTrue());
    }

    @Test
    void mark_rolled_back_of_one_missing_id_still_stamps_the_live_one() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, "minecraft:stone"),
            action(alice, "Alice", "minecraft:overworld", T0 + 1, "minecraft:dirt")));
        List<Long> ids = dao.query(QueryFilter.empty(), 0, 10).stream()
            .map(Action::id).toList();
        // Newest first: the T0 row is the older one and is what the purge removes.
        long missing = ids.get(1);
        long kept = ids.get(0);
        dao.purge(QueryFilter.builder().untilMillis(T0).build());

        // One id is gone, one is live: partial success, no throw.
        int updated = dao.markRolledBack(List.of(missing, kept), true);

        assertThat(updated).isEqualTo(1);
        assertThat(dao.query(QueryFilter.empty(), 0, 10))
            .singleElement()
            .satisfies(a -> assertThat(a.rolledBack()).isTrue());
    }

    // ------------------------------------------------------------ target cap

    @Test
    void oversized_target_is_truncated_deterministically_with_a_stated_marker()
            throws Exception {
        String oversized = "x".repeat(AbstractJdbcDao.TARGET_MAX_CHARS + 500);
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, oversized)));

        Action back = dao.query(QueryFilter.empty(), 0, 10).get(0);

        assertThat(back.targetId())
            .hasSize(AbstractJdbcDao.TARGET_MAX_CHARS)
            .endsWith(AbstractJdbcDao.TARGET_TRUNCATION_MARKER)
            .startsWith("xxx");
    }

    @Test
    void truncation_drops_a_half_written_affected_list_entry_whole() {
        // An EXPLOSION target is "x:y:z=blockId,x:y:z=blockId,...". Cutting it at
        // a fixed offset would leave "10:64:-3=minecraft:sto" — which parses as a
        // real block id and would make a rollback place the wrong block.
        StringBuilder list = new StringBuilder();
        while (list.length() < AbstractJdbcDao.TARGET_MAX_CHARS + 2_000) {
            list.append("10:64:-3=minecraft:stone,");
        }

        String stored = AbstractJdbcDao.admitTarget(list.toString());

        assertThat(stored).hasSizeLessThanOrEqualTo(AbstractJdbcDao.TARGET_MAX_CHARS);
        assertThat(stored).endsWith(AbstractJdbcDao.TARGET_TRUNCATION_MARKER);
        String body = stored.substring(0,
            stored.length() - AbstractJdbcDao.TARGET_TRUNCATION_MARKER.length());
        assertThat(body)
            .as("the retained prefix must end on an entry boundary")
            .endsWith(",");
        // Every retained entry is complete: no dangling "=minecraft:sto".
        for (String entry : body.split(",")) {
            if (entry.isEmpty()) continue;
            assertThat(entry).contains("=");
            assertThat(entry.substring(entry.indexOf('=') + 1))
                .isEqualTo("minecraft:stone");
        }
    }

    @Test
    void truncation_keeps_free_text_intact_when_no_nearby_comma_exists() {
        // A long chat body whose only comma is far from the cut point must be a
        // plain truncation — backing up to that comma would discard most of it.
        String chat = "a".repeat(AbstractJdbcDao.TARGET_MAX_CHARS + 1_000)
            + ",and then some more text";

        String stored = AbstractJdbcDao.admitTarget(chat);

        assertThat(stored).hasSize(AbstractJdbcDao.TARGET_MAX_CHARS);
        assertThat(stored).endsWith(AbstractJdbcDao.TARGET_TRUNCATION_MARKER);
    }

    @Test
    void target_at_exactly_the_column_width_is_stored_verbatim() throws Exception {
        String exact = "y".repeat(AbstractJdbcDao.TARGET_MAX_CHARS);
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        dao.insertBatch(List.of(
            action(alice, "Alice", "minecraft:overworld", T0, exact)));

        assertThat(dao.query(QueryFilter.empty(), 0, 10).get(0).targetId())
            .isEqualTo(exact);
    }

    @Test
    void target_one_past_the_column_width_is_cut_and_marked() {
        String onePast = "z".repeat(AbstractJdbcDao.TARGET_MAX_CHARS + 1);

        String stored = AbstractJdbcDao.admitTarget(onePast);

        assertThat(stored).hasSize(AbstractJdbcDao.TARGET_MAX_CHARS);
        assertThat(stored).endsWith(AbstractJdbcDao.TARGET_TRUNCATION_MARKER);
        assertThat(stored).isNotEqualTo(onePast);
    }

    @Test
    void truncation_never_splits_a_surrogate_pair() {
        int cutPoint = AbstractJdbcDao.TARGET_MAX_CHARS
            - AbstractJdbcDao.TARGET_TRUNCATION_MARKER.length();
        StringBuilder sb = new StringBuilder();
        sb.append("a".repeat(cutPoint - 1));
        sb.append("\uD83D\uDE00");              // one supplementary char
        sb.append("b".repeat(AbstractJdbcDao.TARGET_MAX_CHARS));

        String stored = AbstractJdbcDao.admitTarget(sb.toString());

        assertThat(stored.length()).isLessThanOrEqualTo(AbstractJdbcDao.TARGET_MAX_CHARS);
        // The cut must not leave a dangling high surrogate:
        // Character.isHighSurrogate(char) is the char overload.
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length()
            - AbstractJdbcDao.TARGET_TRUNCATION_MARKER.length() - 1))).isFalse();
        assertThat(Character.isSurrogate(stored.charAt(stored.length()
            - AbstractJdbcDao.TARGET_TRUNCATION_MARKER.length() - 1))).isFalse();
    }

    @Test
    void null_target_becomes_the_empty_string_that_the_not_null_column_requires() {
        assertThat(AbstractJdbcDao.admitTarget(null)).isEmpty();
    }

    // ------------------------------------------------------------ live cap

    @Test
    void result_cap_is_read_live_so_a_reload_takes_effect_without_restart()
            throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        List<Action> batch = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            batch.add(action(alice, "Alice", "minecraft:overworld", T0 + i, "minecraft:stone"));
        }
        dao.insertBatch(batch);

        // Uncapped initially: a 40-row request returns all 30.
        assertThat(dao.query(QueryFilter.empty(), 0, 40)).hasSize(30);

        int applied = dao.applyLookupLimits(5);
        assertThat(applied).isEqualTo(5);
        assertThat(dao.maxResultRows()).isEqualTo(5);

        // The cap must bite immediately, not at the next server boot.
        assertThat(dao.query(QueryFilter.empty(), 0, 40)).hasSize(5);
        assertThat(dao.queryPage(QueryFilter.empty(), 0, 40).truncated()).isTrue();
    }

    @Test
    void raising_the_live_cap_takes_effect_too() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        List<Action> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(action(alice, "Alice", "minecraft:overworld", T0 + i, "minecraft:stone"));
        }
        dao.insertBatch(batch);

        dao.applyLookupLimits(3);
        assertThat(dao.query(QueryFilter.empty(), 0, 40)).hasSize(3);

        dao.applyLookupLimits(15);
        assertThat(dao.query(QueryFilter.empty(), 0, 40)).hasSize(15);
    }

    @Test
    void a_zero_live_cap_means_uncapped() throws Exception {
        UUID alice = UUID.nameUUIDFromBytes("Alice".getBytes());
        List<Action> batch = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            batch.add(action(alice, "Alice", "minecraft:overworld", T0 + i, "minecraft:stone"));
        }
        dao.insertBatch(batch);

        dao.applyLookupLimits(2);
        assertThat(dao.query(QueryFilter.empty(), 0, 40)).hasSize(2);

        assertThat(dao.applyLookupLimits(0)).isZero();
        assertThat(dao.query(QueryFilter.empty(), 0, 40)).hasSize(12);
    }
}
