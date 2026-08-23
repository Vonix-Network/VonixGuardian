package network.vonix.guardian.core.perms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.mockito.ArgumentCaptor;

/**
 * W3-B7: verifies {@link LookupPermissionFilter} drops rows whose child node
 * is not granted, while keeping "fall-open" rows (MESSAGE-family under ROLLBACK,
 * WORLD-family under LOOKUP, etc.).
 */
class LookupPermissionFilterTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /**
     * Build a PermissionResolver with a fixed op-level fallback. Because LP is absent
     * in tests, {@code opLevel} is the sole signal that controls every {@code has()}
     * outcome. Per-child selective testing is achieved by mixing this "grant-all" (op=4)
     * or "deny-all" (op=0) resolver with rows whose {@code childForAction} dispatches to
     * different nodes — the pure childForAction dispatch is exhaustively covered in
     * {@link PermissionNodeChildTest}.
     */
    private static PermissionResolver fakeResolver(int opLevel) {
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 2, java.util.Map.of());
        return new PermissionResolver(cfg, uuid -> opLevel);
    }

    private static Action a(long id, ActionType t) {
        return new Action(id, System.currentTimeMillis(), t, USER, "user",
                "minecraft:overworld", 0, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    // --- We use the package-private test hook: since PermissionResolver is final, we
    //     verify the filter with a real resolver where fallback grants everything, and
    //     then a resolver where fallback denies everything. Per-child selective grants
    //     are validated by exercising fall-open vs strict paths on rows of different
    //     ActionTypes, since childForAction is pure.

    @Test
    void nullResolverOrFamilyOrRowsRejected() {
        assertThatThrownBy(() -> LookupPermissionFilter.filter(null, USER, PermissionNode.LOOKUP, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        PermissionResolver r = fakeResolver(4);
        assertThatThrownBy(() -> LookupPermissionFilter.filter(r, USER, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LookupPermissionFilter.filter(r, USER, PermissionNode.LOOKUP, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consoleBypass_returnsAllRows() {
        PermissionResolver r = fakeResolver(4);
        List<Action> rows = Arrays.asList(a(1, ActionType.CHAT), a(2, ActionType.BLOCK_PLACE));
        List<Action> out = LookupPermissionFilter.filter(r, null, PermissionNode.LOOKUP, rows);
        assertThat(out).hasSize(2);
    }

    @Test
    void resolverGrantsEverything_allRowsSurvive() {
        // Fallback returns opLevel 4 → passes every default. All child nodes granted.
        PermissionResolver r = fakeResolver(4);
        List<Action> rows = Arrays.asList(
                a(1, ActionType.BLOCK_PLACE),
                a(2, ActionType.CHAT),
                a(3, ActionType.COMMAND),
                a(4, ActionType.CONTAINER_DEPOSIT),
                a(5, ActionType.ITEM_DROP),
                a(6, ActionType.ENTITY_KILL),
                a(7, ActionType.SESSION_JOIN),
                a(8, ActionType.SIGN),
                a(9, ActionType.EXPLOSION));
        List<Action> out = LookupPermissionFilter.filter(r, USER, PermissionNode.LOOKUP, rows);
        assertThat(out).hasSize(9);
    }

    @Test
    void resolverDeniesAll_onlyFallOpenRowsSurvive() {
        // Fallback returns 0 → denies every child. Only rows whose childForAction returns the
        // family itself (fall-open) survive.
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 2, java.util.Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 0);

        List<Action> rows = Arrays.asList(
                a(1, ActionType.BLOCK_PLACE),      // childForAction → LOOKUP_BLOCK → denied
                a(2, ActionType.EXPLOSION),        // WORLD → fall-open → survives
                a(3, ActionType.CLICK));           // INTERACT → fall-open → survives
        List<Action> out = LookupPermissionFilter.filter(r, USER, PermissionNode.LOOKUP, rows);

        assertThat(out).extracting(Action::id).containsExactly(2L, 3L);
    }

    @Test
    void opLevelTwoLookupKeepsBlockButHidesChatAndCommandChildRows() {
        // Regression for the legacy string path: LOOKUP itself and LOOKUP_BLOCK
        // default to op level 2, but LOOKUP_CHAT / LOOKUP_COMMAND default to 3.
        // The filter must therefore pass the child PermissionNode object through
        // the resolver, not convert it to a string and fall back to defaultOpLevel=2.
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 2, java.util.Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 2);

        List<Action> rows = Arrays.asList(
                a(1, ActionType.BLOCK_PLACE),
                a(2, ActionType.CHAT),
                a(3, ActionType.COMMAND));
        List<Action> out = LookupPermissionFilter.filter(r, USER, PermissionNode.LOOKUP, rows);

        assertThat(out).extracting(Action::id).containsExactly(1L);
    }

    @Test
    void rollbackFamily_messageRows_fallOpen() {
        // CHAT under ROLLBACK family: childForAction returns ROLLBACK (no rollback-of-chat
        // scoping in CP). Even when resolver denies, message rows survive under ROLLBACK.
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 2, java.util.Map.of());
        PermissionResolver r = new PermissionResolver(cfg, uuid -> 0);

        List<Action> rows = Arrays.asList(
                a(1, ActionType.CHAT),
                a(2, ActionType.BLOCK_PLACE));   // → ROLLBACK_BLOCK → denied
        List<Action> out = LookupPermissionFilter.filter(r, USER, PermissionNode.ROLLBACK, rows);
        assertThat(out).extracting(Action::id).containsExactly(1L);
    }

    @Test
    void emptyRowsInEmptyRowsOut() {
        PermissionResolver r = fakeResolver(4);
        List<Action> out = LookupPermissionFilter.filter(r, USER, PermissionNode.LOOKUP, new ArrayList<>());
        assertThat(out).isEmpty();
    }

    @Test
    void countVisibleCountsOnlyPermittedChildBucketsWithoutFetchingRows() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.count(any(QueryFilter.class))).thenAnswer(invocation -> {
            QueryFilter f = invocation.getArgument(0);
            return f.actions().stream().anyMatch(a -> a.type() == ActionType.BLOCK_PLACE) ? 4L : 99L;
        });
        QueryFilter filter = new QueryFilter(
                List.of(), null, null, null, null, null, null, null,
                List.of(
                        new QueryFilter.ActionSelect(ActionType.BLOCK_PLACE, QueryFilter.ActionSelect.Sign.ANY),
                        new QueryFilter.ActionSelect(ActionType.CHAT, QueryFilter.ActionSelect.Sign.ANY),
                        new QueryFilter.ActionSelect(ActionType.COMMAND, QueryFilter.ActionSelect.Sign.ANY)),
                List.of(), List.of(), null, true, false, false, false, false, null, Set.of());

        long visible = LookupPermissionFilter.countVisible(
                dao, resolver, USER, PermissionNode.LOOKUP, filter);

        assertThat(visible).isEqualTo(4L);
        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao, times(1)).count(captor.capture());
        verify(dao, never()).query(any(), anyInt(), anyInt());
        verify(dao, never()).queryPage(any(), anyInt(), anyInt());
        verify(dao, never()).queryPageForDisplay(any(), anyInt(), anyInt());
        assertThat(captor.getValue().actions())
                .extracting(QueryFilter.ActionSelect::type)
                .containsExactly(ActionType.BLOCK_PLACE);
    }

    @Test
    void countVisibleUnrestrictedGrantAllIssuesOneUnscopedCount() throws Exception {
        PermissionResolver resolver = fakeResolver(4);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.count(any(QueryFilter.class))).thenReturn(42L);

        long visible = LookupPermissionFilter.countVisible(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty());

        assertThat(visible).isEqualTo(42L);
        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao, times(1)).count(captor.capture());
        assertThat(captor.getValue().actions()).isEmpty();
    }

    @Test
    void countVisibleUnrestrictedOp2IssuesOneCombinedCountExcludingDeniedChildren() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.count(any(QueryFilter.class))).thenReturn(17L);

        long visible = LookupPermissionFilter.countVisible(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty());

        assertThat(visible).isEqualTo(17L);
        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao, times(1)).count(captor.capture());
        Set<ActionType> counted = new HashSet<>();
        for (QueryFilter.ActionSelect select : captor.getValue().actions()) {
            counted.add(select.type());
        }
        assertThat(counted)
                .doesNotContain(ActionType.CHAT, ActionType.COMMAND)
                .contains(ActionType.BLOCK_PLACE, ActionType.SIGN, ActionType.EXPLOSION, ActionType.CLICK);
        assertThat(counted).hasSize(ActionType.values().length - 2);
    }

    @Test
    void countVisibleReturnsZeroWithoutDaoWhenNoTypesPermitted() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        QueryFilter chatOnly = QueryFilter.builder()
                .addAction(new QueryFilter.ActionSelect(ActionType.CHAT, QueryFilter.ActionSelect.Sign.ANY))
                .addAction(new QueryFilter.ActionSelect(ActionType.COMMAND, QueryFilter.ActionSelect.Sign.ANY))
                .build();

        long visible = LookupPermissionFilter.countVisible(
                dao, resolver, USER, PermissionNode.LOOKUP, chatOnly);

        assertThat(visible).isZero();
        verify(dao, never()).count(any(QueryFilter.class));
    }

    @Test
    void countVisibleConsoleUsesSingleUnscopedCount() throws Exception {
        PermissionResolver resolver = fakeResolver(0);
        GuardianDao dao = mock(GuardianDao.class);
        QueryFilter filter = QueryFilter.empty();
        when(dao.count(filter)).thenReturn(9L);

        long visible = LookupPermissionFilter.countVisible(
                dao, resolver, null, PermissionNode.LOOKUP, filter);

        assertThat(visible).isEqualTo(9L);
        verify(dao, times(1)).count(filter);
    }

    @Test
    void countVisibleCombinedSqliteCountMatchesPermittedRowsAndHitsDaoOnce() throws Exception {
        SqliteDao real = new SqliteDao("jdbc:sqlite::memory:");
        real.init();
        try {
            List<Action> rows = List.of(
                    a(1, ActionType.BLOCK_PLACE),
                    a(2, ActionType.BLOCK_BREAK),
                    a(3, ActionType.CHAT),
                    a(4, ActionType.COMMAND),
                    a(5, ActionType.EXPLOSION),
                    a(6, ActionType.SIGN));
            assertThat(real.insertBatch(rows)).isEqualTo(6);
            GuardianDao dao = spy(real);

            long visible = LookupPermissionFilter.countVisible(
                    dao, fakeResolver(2), USER, PermissionNode.LOOKUP, QueryFilter.empty());

            assertThat(visible).isEqualTo(4L);
            verify(dao, times(1)).count(any(QueryFilter.class));
            verify(dao, never()).query(any(), anyInt(), anyInt());
        } finally {
            real.close();
        }
    }

    @Test
    void filterCachesChildPermissionProbesAcrossRowsOfTheSameType() {
        AtomicInteger probes = new AtomicInteger();
        GuardianConfig.Permissions cfg = new GuardianConfig.Permissions(false, 2, java.util.Map.of());
        PermissionResolver resolver = new PermissionResolver(cfg, uuid -> {
            probes.incrementAndGet();
            return 2;
        });
        List<Action> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rows.add(a(i, ActionType.BLOCK_PLACE));
            rows.add(a(100 + i, ActionType.CHAT));
            rows.add(a(200 + i, ActionType.EXPLOSION));
        }

        List<Action> out = LookupPermissionFilter.filter(resolver, USER, PermissionNode.LOOKUP, rows);

        assertThat(out).extracting(Action::type)
                .containsOnly(ActionType.BLOCK_PLACE, ActionType.EXPLOSION);
        assertThat(out).hasSize(16);
        // One probe per distinct child (LOOKUP_BLOCK, LOOKUP_CHAT). Fall-open
        // EXPLOSION rows do not consult the resolver.
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    void countVisiblePreservesExplicitActionSignsWhenDroppingDeniedTypes() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.count(any(QueryFilter.class))).thenReturn(3L);
        QueryFilter filter = QueryFilter.builder()
                .addAction(new QueryFilter.ActionSelect(ActionType.BLOCK_PLACE, QueryFilter.ActionSelect.Sign.PLACE_ONLY))
                .addAction(new QueryFilter.ActionSelect(ActionType.CHAT, QueryFilter.ActionSelect.Sign.ANY))
                .build();

        long visible = LookupPermissionFilter.countVisible(
                dao, resolver, USER, PermissionNode.LOOKUP, filter);

        assertThat(visible).isEqualTo(3L);
        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao, times(1)).count(captor.capture());
        assertThat(captor.getValue().actions())
                .containsExactly(new QueryFilter.ActionSelect(
                        ActionType.BLOCK_PLACE, QueryFilter.ActionSelect.Sign.PLACE_ONLY));
    }

    @Test
    void visiblePageCanPrefetchOneExtraRowWithoutCounting() throws Exception {
        PermissionResolver resolver = fakeResolver(4);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(0), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(a(60, ActionType.BLOCK_PLACE), a(61, ActionType.BLOCK_BREAK)), false));

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty(), 1, 1, true);

        assertThat(page.rows()).extracting(Action::id).containsExactly(60L);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.complete()).isTrue();
        verify(dao).queryPageForDisplay(
                any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(2));
        verify(dao, never()).count(any(QueryFilter.class));
    }

    @Test
    void visiblePageSkipsDeniedRowsBeforeFillingVisiblePage() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        Action denied = a(10, ActionType.CHAT);
        Action allowed = a(11, ActionType.BLOCK_PLACE);
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(0), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(denied, allowed), false));
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(2), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(), false));

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty(), 1, 1);

        assertThat(page.rows()).extracting(Action::id).containsExactly(11L);
        assertThat(page.rawRowsScanned()).isEqualTo(2);
        verify(dao, never()).queryPage(any(), anyInt(), anyInt());
    }

    @Test
    void visiblePageReportsIncompleteWhenRawScanCapCannotProvePage() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        Action denied = a(12, ActionType.CHAT);
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.anyInt(), any(Integer.class)))
                .thenAnswer(invocation -> {
                    int limit = invocation.getArgument(2);
                    return new GuardianDao.QueryPage(java.util.Collections.nCopies(limit, denied), false);
                });

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty(), 1, 1);

        assertThat(page.complete()).isFalse();
        assertThat(page.rows()).isEmpty();
        assertThat(page.rawRowsScanned()).isEqualTo(100_000);
    }

    @Test
    void visiblePageFailsClosedWhenDaoReportsTruncatedPage() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        Action allowed = a(13, ActionType.BLOCK_PLACE);
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(0), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(allowed), true));

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty(), 1, 1);

        assertThat(page.complete()).isFalse();
        assertThat(page.rows()).isEmpty();
        assertThat(page.rawRowsScanned()).isEqualTo(1);
    }

    @Test
    void visiblePageDoesNotQueryWhenNoTypesPermitted() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        QueryFilter chatOnly = QueryFilter.builder()
                .addAction(new QueryFilter.ActionSelect(ActionType.CHAT, QueryFilter.ActionSelect.Sign.ANY))
                .build();

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, chatOnly, 1, 7);

        assertThat(page.complete()).isTrue();
        assertThat(page.rows()).isEmpty();
        assertThat(page.rawRowsScanned()).isZero();
        verify(dao, never()).queryPage(any(), anyInt(), anyInt());
        verify(dao, never()).queryPageForDisplay(any(), anyInt(), anyInt());
    }

    @Test
    void visiblePageUnrestrictedOp2RestrictsDaoFilterToPermittedTypes() throws Exception {
        PermissionResolver resolver = fakeResolver(2);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(0), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(a(20, ActionType.BLOCK_PLACE)), false));
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(1), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(), false));

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty(), 1, 1);

        assertThat(page.rows()).extracting(Action::id).containsExactly(20L);
        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao).queryPageForDisplay(captor.capture(), org.mockito.ArgumentMatchers.eq(0), any(Integer.class));
        verify(dao, never()).queryPage(any(), anyInt(), anyInt());
        Set<ActionType> requested = new HashSet<>();
        for (QueryFilter.ActionSelect select : captor.getValue().actions()) {
            requested.add(select.type());
        }
        assertThat(requested)
                .doesNotContain(ActionType.CHAT, ActionType.COMMAND)
                .contains(ActionType.BLOCK_PLACE, ActionType.SIGN);
    }

    @Test
    void visiblePageFirstPageFetchesOnlyTheVisibleWindow() throws Exception {
        PermissionResolver resolver = fakeResolver(4);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(0), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(a(30, ActionType.BLOCK_PLACE)), false));
        when(dao.queryPageForDisplay(any(QueryFilter.class), org.mockito.ArgumentMatchers.eq(1), any(Integer.class)))
                .thenReturn(new GuardianDao.QueryPage(List.of(), false));

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty(), 1, 7);

        assertThat(page.rows()).extracting(Action::id).containsExactly(30L);
        verify(dao).queryPageForDisplay(
                any(QueryFilter.class),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(7));
    }

    @Test
    void visiblePageLaterPageStillScansFromZeroWithBoundedPrefetch() throws Exception {
        PermissionResolver resolver = fakeResolver(4);
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.queryPageForDisplay(any(QueryFilter.class), anyInt(), any(Integer.class)))
                .thenAnswer(invocation -> {
                    int offset = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    List<Action> rows = new ArrayList<>(limit);
                    for (int i = 0; i < limit; i++) {
                        rows.add(a(offset + i + 1L, ActionType.BLOCK_PLACE));
                    }
                    return new GuardianDao.QueryPage(rows, false);
                });

        LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                dao, resolver, USER, PermissionNode.LOOKUP, QueryFilter.empty(), 40, 7);

        assertThat(page.complete()).isTrue();
        assertThat(page.rows()).extracting(Action::id).containsExactly(274L, 275L, 276L, 277L, 278L, 279L, 280L);
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(dao).queryPageForDisplay(
                any(QueryFilter.class),
                org.mockito.ArgumentMatchers.eq(0),
                limitCaptor.capture());
        // skip 273 + take 7 = 280, capped at RAW_PAGE_SIZE 256
        assertThat(limitCaptor.getValue()).isEqualTo(256);
        verify(dao).queryPageForDisplay(
                any(QueryFilter.class),
                org.mockito.ArgumentMatchers.eq(256),
                org.mockito.ArgumentMatchers.eq(24));
    }

    @Test
    void visiblePageDisplayPathDoesNotMaterializeNbtPayloads() throws Exception {
        SqliteDao real = new SqliteDao("jdbc:sqlite::memory:");
        real.init();
        try {
            byte[] nbt = new byte[512];
            java.util.Arrays.fill(nbt, (byte) 9);
            Action stored = new Action(
                    1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE, USER, "user",
                    "minecraft:overworld", 0, 64, 0, "minecraft:chest", null, 1, false, null,
                    null, null, null, "facing=north", null, nbt, null, null);
            assertThat(real.insertBatch(List.of(stored))).isEqualTo(1);

            List<Action> full = real.query(QueryFilter.empty(), 0, 1);
            assertThat(full).hasSize(1);
            assertThat(full.get(0).blockEntityNbt()).containsExactly(nbt);
            assertThat(full.get(0).oldBlockState()).isEqualTo("facing=north");

            LookupPermissionFilter.VisiblePage page = LookupPermissionFilter.visiblePage(
                    real, fakeResolver(4), USER, PermissionNode.LOOKUP, QueryFilter.empty(), 1, 1);

            assertThat(page.complete()).isTrue();
            assertThat(page.rows()).hasSize(1);
            assertThat(page.rows().get(0).id()).isEqualTo(full.get(0).id());
            assertThat(page.rows().get(0).type()).isEqualTo(ActionType.BLOCK_PLACE);
            assertThat(page.rows().get(0).targetId()).isEqualTo("minecraft:chest");
            assertThat(page.rows().get(0).blockEntityNbt()).isNull();
            assertThat(page.rows().get(0).oldBlockState()).isNull();
        } finally {
            real.close();
        }
    }
}
