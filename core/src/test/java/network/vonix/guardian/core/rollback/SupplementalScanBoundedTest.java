package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * X8 (v1.3.1) — regression tests for the "bounded W5 supplemental EXPLOSION
 * scan" fix.
 *
 * <p>Before X8 the supplemental scan dropped the DAO spatial predicate entirely
 * (radius/centerX/Y/Z were nulled) so it read every EXPLOSION row in the world
 * within the time window. On griefing-storm servers this blew the shared
 * scan budget before the interesting rows were reached, surfacing as
 * {@code RollbackLimitExceededException}.</p>
 *
 * <p>X8 keeps the spatial predicate but widens it by
 * {@link RollbackEngine#MAX_TNT_REACH} blocks (config-overridable via
 * {@link network.vonix.guardian.core.config.GuardianConfig.Rollback#explosionSupplementalReach}).
 * Row admission still uses
 * {@link ExplosionAffectedList#anyWithinRadius} so widening only relaxes the
 * pre-filter — it never over-admits.</p>
 */
class SupplementalScanBoundedTest {

    private GuardianDao dao;
    private RecordingMutator mutator;
    private Executor sync;

    @BeforeEach
    void setUp() {
        dao = mock(GuardianDao.class);
        mutator = new RecordingMutator();
        sync = Runnable::run;
    }

    /**
     * The supplemental scan MUST issue its DAO query with the caller's radius
     * widened by the configured reach (default {@link RollbackEngine#MAX_TNT_REACH}),
     * NOT with radius=null. This is the core X8 bound.
     */
    @Test
    void supplementalDaoQuery_carriesWidenedSpatialPredicate() throws Exception {
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync); // default reach = MAX_TNT_REACH
        // Primary paged scan returns nothing so we exit to the supplemental.
        // Supplemental returns nothing too — we only care about the filter shape.
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(100, 64, 0)
            .build();

        engine.rollback(filter, false);

        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao, times(2)).query(captor.capture(), anyInt(), anyInt());

        QueryFilter primary = captor.getAllValues().get(0);
        assertThat(primary.radius()).as("primary scan carries caller's radius").isEqualTo(5);

        QueryFilter supplemental = captor.getAllValues().get(1);
        assertThat(supplemental.radius())
            .as("supplemental scan widens radius by MAX_TNT_REACH — not null")
            .isEqualTo(5 + RollbackEngine.MAX_TNT_REACH);
        assertThat(supplemental.centerX()).isEqualTo(100);
        assertThat(supplemental.centerY()).isEqualTo(64);
        assertThat(supplemental.centerZ()).isEqualTo(0);
        assertThat(supplemental.actions())
            .as("supplemental restricts to EXPLOSION only")
            .hasSize(1);
        assertThat(supplemental.actions().get(0).type()).isEqualTo(ActionType.EXPLOSION);
    }

    /**
     * The reach is config-driven. A tighter override must show up in the DAO
     * predicate.
     */
    @Test
    void supplementalReach_isConfigDriven() throws Exception {
        RollbackEngine tight = new RollbackEngine(dao, mutator, sync, /* reach = */ 4);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(0, 64, 0)
            .build();

        tight.rollback(filter, false);

        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao, times(2)).query(captor.capture(), anyInt(), anyInt());
        assertThat(captor.getAllValues().get(1).radius())
            .as("supplemental radius = base radius + configured reach")
            .isEqualTo(5 + 4);
    }

    /**
     * A vanilla TNT blast at (150,64,0) whose affected-list reaches back to
     * (108,64,0) MUST still be admitted when the caller runs {@code r:5}
     * centered on (108,64,0). Under X8 the DAO pre-filter is
     * {@code r=5+16=21} at center (108,64,0), which covers x∈[87..129], so
     * the DAO WOULD miss (150,64,0). This test therefore exercises the
     * "vanilla reach" claim: as long as the blast center is within
     * MAX_TNT_REACH of the caller's box edge, X8 catches it — and vanilla
     * TNT cannot affect blocks farther than that from its center.
     */
    @Test
    void supplementalScanCatchesFarBlast_withinTntReach() throws Exception {
        // Blast center at (125, 64, 0) — 12 blocks outside r=5 box edge x=113,
        // but comfortably inside the widened box edge x=113+16=129. Its
        // affected-list reaches back to (108,64,0) which sits inside r=5.
        String affected = "125:64:0=minecraft:stone,"
                        + "118:64:0=minecraft:sand,"
                        + "108:64:0=minecraft:oak_planks";
        Action blast = explosion(42L, 125, 64, 0, affected);

        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of())           // primary — center at x=125 outside r=5 box
            .thenReturn(List.of(blast))      // supplemental — center inside r=21 box
            .thenReturn(List.of());          // supplemental tail
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);
        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(108, 64, 0)
            .build();

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.dispatchedSteps())
            .as("blast admitted because affected-list reaches into r:5 radius")
            .isEqualTo(1);
        assertThat(mutator.setBlockCalls).containsExactly(
            "w|125|64|0|minecraft:stone",
            "w|118|64|0|minecraft:sand",
            "w|108|64|0|minecraft:oak_planks"
        );
    }

    /**
     * A griefing storm of 200 EXPLOSION rows in the world within the time
     * window MUST NOT blow the scan budget when they are all far from the
     * caller's radius. Under the pre-X8 behavior the supplemental would read
     * all 200 rows; under X8 the DAO pre-filter drops them at query time so
     * the engine's {@code scanned} counter stays low.
     *
     * <p>We model this by returning zero supplemental rows (as the DAO's
     * bounded predicate would in prod) and asserting the plan completes
     * without a {@link RollbackLimitExceededException}.</p>
     */
    @Test
    void supplementalScanRespectsBudget_griefingStormDoesNotBlowIt() throws Exception {
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);

        // Primary returns nothing (rollback centered where no blast landed).
        // Supplemental returns nothing too — the widened DAO predicate has
        // already dropped the 200 far-away rows.
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        // Tight scan budget — 500 actions total. Pre-X8 with 200 far rows the
        // supplemental would burn 200 of these on rows that were then rejected
        // by anyWithinRadius. Under X8 zero are consumed.
        RollbackOptions tight = new RollbackOptions(
            /* pageSize */ 100,
            /* maxScannedActions */ 500,
            /* maxPlannedSteps */ 1000,
            null, null);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(0, 64, 0)
            .build();

        // Should not throw.
        RollbackResult r = engine.rollback(filter, false, null, tight);
        assertThat(r.dispatchedSteps()).isZero();
        assertThat(mutator.setBlockCalls).isEmpty();
        // Both scans ran but neither burned any budget.
        verify(dao, times(2)).query(any(), anyInt(), anyInt());
    }

    /**
     * A {@code #global} rollback (radius=-1) must skip the supplemental scan
     * entirely — X8 preserves this behavior; the widening code path is
     * radius-dependent and never touched on global scans.
     */
    @Test
    void globalRadius_stillSkipsSupplemental() throws Exception {
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);
        String affected = "1:2:3=minecraft:stone";
        Action blast = explosion(3L, 1, 2, 3, affected);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(blast), List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(-1)
            .worldSel(new QueryFilter.WorldSel(null, true))
            .build();

        RollbackResult r = engine.rollback(filter, false);
        assertThat(r.dispatchedSteps()).isEqualTo(1);
        // Only the primary scan ran; there is no supplemental for #global.
        verify(dao, times(1)).query(any(), anyInt(), anyInt());
    }

    /**
     * A blast whose center sits <em>outside</em> the widened box must not
     * even be returned by the DAO's supplemental query — modelled here by
     * ensuring the second dao.query stub is never fed a far-away row (we
     * simulate the DAO's bounded predicate by returning an empty page).
     * This is the "does not read >expected rows" bound.
     */
    @Test
    void supplementalScanDoesNotAdmitBlastsBeyondWidenedBox() throws Exception {
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(0, 64, 0)
            .build();

        RollbackResult r = engine.rollback(filter, false);

        // Supplemental radius = 5 + 16 = 21; asserting the DAO was asked for
        // that exact widened radius protects against a future regression that
        // silently drops the predicate again.
        ArgumentCaptor<QueryFilter> captor = ArgumentCaptor.forClass(QueryFilter.class);
        verify(dao, times(2)).query(captor.capture(), eq(0), anyInt());
        assertThat(captor.getAllValues().get(1).radius()).isEqualTo(21);
        assertThat(r.dispatchedSteps()).isZero();
    }

    // -- helpers -----------------------------------------------------------

    private static Action explosion(long id, int x, int y, int z, String affectedJoined) {
        return new Action(
            id,
            System.currentTimeMillis(),
            ActionType.EXPLOSION,
            UUID.randomUUID(),
            "#tnt",
            "w",
            x, y, z,
            affectedJoined,
            null,           // targetMeta
            1,              // amount
            false,          // rolledBack
            "explosion:tnt"
        );
    }

    private static final class RecordingMutator implements WorldMutator {
        final List<String> setBlockCalls = new ArrayList<>();

        @Override
        public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
            setBlockCalls.add(worldId + "|" + x + "|" + y + "|" + z + "|" + targetId);
        }

        @Override
        public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {}

        @Override
        public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {}

        @Override
        public void respawnEntity(String worldId, int x, int y, int z, String entityType, String nbt) {}
    }
}
