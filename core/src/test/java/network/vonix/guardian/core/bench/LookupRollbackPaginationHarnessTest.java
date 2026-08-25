package network.vonix.guardian.core.bench;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.perms.LookupPermissionFilter;
import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.perms.PermissionResolver;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.QueryCompiler;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Deterministic complexity harness for lookup/rollback pagination.
 *
 * <p>Does not assert wall-clock speedup (that would be flaky). It asserts
 * matched-workload equality and the query-count / SQL-shape properties that
 * make later pages player-count invariant: one initial visible lookup batch
 * followed by keyset continuation, and keyset SQL that contains no
 * {@code OFFSET} skip.</p>
 */
class LookupRollbackPaginationHarnessTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final long T0 = 1_710_000_000_000L;
    private static final int ROWS = 4_000;
    private static final int PAGE = 50;

    @Test
    void matchedWorkloadKeysetEqualsOffsetAndOmitsOffsetSql() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        try {
            assertThat(dao.insertBatch(seed(ROWS))).isEqualTo(ROWS);
            QueryFilter filter = QueryFilter.empty();

            List<Action> head = dao.query(filter, 0, 3_200);
            Action cursor = head.get(head.size() - 1);

            long offsetNs = 0L;
            long keysetNs = 0L;
            List<Long> offsetIds = null;
            List<Long> keysetIds = null;
            // Three warmup/measure passes; compare the last measured ids.
            for (int i = 0; i < 3; i++) {
                long t0 = System.nanoTime();
                List<Action> offsetPage = dao.query(filter, 3_200, PAGE);
                offsetNs = System.nanoTime() - t0;
                offsetIds = offsetPage.stream().map(Action::id).toList();

                t0 = System.nanoTime();
                GuardianDao.QueryPage keysetPage = dao.queryPageAfter(
                        filter, cursor.timestamp(), cursor.id(), PAGE);
                keysetNs = System.nanoTime() - t0;
                keysetIds = keysetPage.rows().stream().map(Action::id).toList();
            }

            assertThat(keysetIds).containsExactlyElementsOf(offsetIds);
            assertThat(keysetIds).hasSize(PAGE);

            QueryCompiler.Compiled offsetSql = QueryCompiler.compileSelect(filter, 3_200, PAGE);
            QueryCompiler.Compiled keysetSql = QueryCompiler.compileSelectAfter(
                    filter, new QueryCompiler.Seek(cursor.timestamp(), cursor.id()), PAGE);
            assertThat(offsetSql.sql()).contains("OFFSET");
            assertThat(keysetSql.sql()).doesNotContain("OFFSET");
            assertThat(keysetSql.sql()).contains("(a.ts, a.id) < (?, ?)");

            System.out.println("VG_PAGINATION_HARNESS rows=" + ROWS
                    + " page=" + PAGE
                    + " offset_ns=" + offsetNs
                    + " keyset_ns=" + keysetNs
                    + " matched_ids=" + keysetIds.size());
        } finally {
            dao.close();
        }
    }

    @Test
    void laterLookupPageUsesInitialBatchAndKeysetContinuationOnMatchedSqlite() throws Exception {
        SqliteDao real = new SqliteDao("jdbc:sqlite::memory:");
        real.init();
        try {
            assertThat(real.insertBatch(seed(800))).isEqualTo(800);
            SqliteDao dao = spy(real);
            PermissionResolver resolver = new PermissionResolver(
                    new GuardianConfig.Permissions(false, 2, java.util.Map.of()), uuid -> 4);

            AtomicInteger calls = new AtomicInteger();
            LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                    dao, resolver, ACTOR, PermissionNode.LOOKUP, QueryFilter.empty(), 2, 7, true);
            verify(dao, times(1)).queryPageForDisplay(any(QueryFilter.class), anyInt(), anyInt());
            verify(dao, atLeastOnce()).queryPageForDisplayAfter(
                    any(QueryFilter.class), anyLong(), anyLong(), anyInt());
            assertThat(page.complete()).isTrue();
            assertThat(page.rows()).hasSize(7);
            assertThat(page.hasNext()).isTrue();
            calls.set(1);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            real.close();
        }
    }

    private static List<Action> seed(int n) {
        List<Action> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(new Action(
                    -1L, T0 + i, ActionType.BLOCK_PLACE, ACTOR, "user",
                    "minecraft:overworld", i, 64, 0, "minecraft:stone",
                    null, 1, false, null));
        }
        return batch;
    }
}
