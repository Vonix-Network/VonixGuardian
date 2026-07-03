package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W5 (v1.3.0) — regression tests for the "explosion rollback fidelity" fix.
 *
 * <p>Before this wave, the {@link RollbackEngine} filtered rows purely by the
 * blast-center coord recorded on the EXPLOSION row, so:</p>
 * <ol>
 *   <li>A rollback centered on the same point as the blast produced ONE
 *       mutation (the center block), not the full affected radius.</li>
 *   <li>A rollback centered a few blocks off (say the player standing at the
 *       edge of the damage) matched ZERO rows even though affected blocks
 *       reached them.</li>
 * </ol>
 *
 * <p>The fix mirrors CoreProtect: the engine parses the affected-list stored
 * in {@link Action#targetId()} at plan time and (a) admits explosions whose
 * center is outside the filter radius but whose damage reaches into it and
 * (b) restores every affected block, not just the center.</p>
 */
class ExplosionRollbackFidelityTest {

    private GuardianDao dao;
    private RecordingMutator mutator;
    private Executor sync;
    private RollbackEngine engine;

    @BeforeEach
    void setUp() {
        dao = mock(GuardianDao.class);
        mutator = new RecordingMutator();
        sync = Runnable::run;
        engine = new RollbackEngine(dao, mutator, sync);
    }

    /**
     * TNT blast at (100,64,0) affecting the center plus three neighbours;
     * player is standing on top of the crater and runs {@code /vg rollback r:5 t:2m}.
     * Correct behavior: 4 setBlock mutations (center + 3 affected).
     */
    @Test
    void rollbackCenteredOnBlast_restoresAllAffectedBlocks() throws Exception {
        String affected = "100:64:0=minecraft:stone,"
                        + "105:64:0=minecraft:dirt,"
                        + "95:64:0=minecraft:cobblestone,"
                        + "100:69:0=minecraft:oak_planks";
        Action blast = explosion(1L, 100, 64, 0, affected);

        // Primary paged query returns the blast (center inside radius).
        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of(blast))
            .thenReturn(List.of());          // supplemental scan returns 0 (already covered)
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(100, 64, 0)
            .build();

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.dispatchedSteps()).isEqualTo(1);       // 1 EXPLOSION planned step
        // 4 real setBlock calls out to the world mutator — one per affected coord.
        assertThat(mutator.setBlockCalls).containsExactly(
            "w|100|64|0|minecraft:stone",
            "w|105|64|0|minecraft:dirt",
            "w|95|64|0|minecraft:cobblestone",
            "w|100|69|0|minecraft:oak_planks"
        );
    }

    /**
     * TNT blast at (120,64,0) affecting blocks stretching back to (108,64,0);
     * player stands at (108,64,0) and runs {@code /vg rollback r:5 t:2m}.
     * Before the fix: 0 mutations (blast center is 12 blocks outside r:5).
     * After the fix: 1 mutation — the (108,64,0) affected block that IS in
     * range gets restored.
     */
    @Test
    void rollbackAtPlayer_admitsExplosionWhoseAffectedListReachesIntoRadius() throws Exception {
        // Blast at (120,64,0) with affected coords stretching all the way to (108,64,0)
        String affected = "120:64:0=minecraft:stone,"
                        + "115:64:0=minecraft:sand,"
                        + "108:64:0=minecraft:oak_planks";
        Action blast = explosion(42L, 120, 64, 0, affected);

        // Primary paged query: 0 rows (center at x=120 is outside x∈[103..113]).
        // Supplemental EXPLOSION query: returns the blast (spatial predicate dropped).
        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of())          // primary paged scan
            .thenReturn(List.of(blast))     // supplemental scan page 1
            .thenReturn(List.of());         // supplemental scan page 2 (empty tail)
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(108, 64, 0)
            .build();

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.dispatchedSteps())
            .as("blast admitted because affected-list reaches into r:5 radius")
            .isEqualTo(1);
        // All 3 affected coords get their pre-blast state restored — the
        // engine does not sub-filter which coords to restore, only whether
        // to admit the whole row.
        assertThat(mutator.setBlockCalls).containsExactly(
            "w|120|64|0|minecraft:stone",
            "w|115|64|0|minecraft:sand",
            "w|108|64|0|minecraft:oak_planks"
        );
    }

    /**
     * A blast well outside the caller's radius whose affected-list stays
     * outside too — must NOT be admitted.
     */
    @Test
    void rollbackFarAway_ignoresExplosionWhoseAffectedListMisses() throws Exception {
        String affected = "1000:64:0=minecraft:stone,1005:64:0=minecraft:dirt";
        Action farBlast = explosion(7L, 1000, 64, 0, affected);

        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of())          // primary paged scan — center out of radius
            .thenReturn(List.of(farBlast))  // supplemental scan sees it
            .thenReturn(List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(0, 64, 0)
            .build();

        RollbackResult r = engine.rollback(filter, false);
        assertThat(r.dispatchedSteps()).isZero();
        assertThat(mutator.setBlockCalls).isEmpty();
    }

    /**
     * Restore direction: {@code /vg restore} should re-clear (set air) every
     * affected coord, not just the center — parity with the rollback path.
     */
    @Test
    void restoreClearsAllAffectedBlocks() throws Exception {
        String affected = "100:64:0=minecraft:stone,"
                        + "105:64:0=minecraft:dirt,"
                        + "100:65:0=minecraft:oak_planks";
        Action blast = explosion(9L, 100, 64, 0, affected);
        blast = new Action(
            blast.id(), blast.timestamp(), blast.type(),
            blast.actorUuid(), blast.actorName(),
            blast.worldId(), blast.x(), blast.y(), blast.z(),
            blast.targetId(), blast.targetMeta(), blast.amount(),
            /* rolledBack */ true,            // restore-eligible row
            blast.sourceTag(), blast.signSide(), blast.signDyeColor(), blast.signWaxed()
        );

        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of(blast))
            .thenReturn(List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(100, 64, 0)
            .build();

        RollbackResult r = engine.restore(filter, false);

        assertThat(r.dispatchedSteps()).isEqualTo(1);
        assertThat(mutator.setBlockCalls).containsExactly(
            "w|100|64|0|minecraft:air",
            "w|105|64|0|minecraft:air",
            "w|100|65|0|minecraft:air"
        );
    }

    /**
     * When the caller's action filter includes a non-EXPLOSION type (e.g.
     * {@code a:block}), the supplemental EXPLOSION scan must not fire. This
     * guards against surprising ops who intentionally excluded explosions,
     * and prevents an extra DAO round-trip we didn't need.
     */
    @Test
    void nonExplosionActionFilter_skipsSupplementalScan() throws Exception {
        // Primary paged query returns nothing (the SQL predicate excludes
        // EXPLOSION rows). Supplemental scan MUST NOT run — if it does,
        // the second stubbed answer (a blast we don't want) would surface.
        String affected = "108:64:0=minecraft:oak_planks";
        Action blast = explosion(99L, 120, 64, 0, affected);
        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of())          // primary — 0 rows
            .thenReturn(List.of(blast));    // would-be supplemental — must not be consumed
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(5)
            .center(108, 64, 0)
            .addAction(new QueryFilter.ActionSelect(ActionType.BLOCK_BREAK,
                QueryFilter.ActionSelect.Sign.ANY))
            .build();

        RollbackResult r = engine.rollback(filter, false);
        assertThat(r.dispatchedSteps()).isZero();
        assertThat(mutator.setBlockCalls).isEmpty();
        // Only one dao.query invocation — no supplemental scan.
        org.mockito.Mockito.verify(dao, org.mockito.Mockito.times(1))
            .query(any(), anyInt(), anyInt());
    }

    /**
     * A #global rollback (radius=-1) must not spend cycles on the supplemental
     * scan — the primary scan already spans all coords.
     */
    @Test
    void globalRadius_skipsSupplementalScan() throws Exception {
        String affected = "1:2:3=minecraft:stone";
        Action blast = explosion(3L, 1, 2, 3, affected);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(blast), List.of());
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 120_000L)
            .radius(-1)                 // #global
            .worldSel(new QueryFilter.WorldSel(null, true))
            .build();

        RollbackResult r = engine.rollback(filter, false);
        assertThat(r.dispatchedSteps()).isEqualTo(1);
        assertThat(mutator.setBlockCalls).containsExactly("w|1|2|3|minecraft:stone");
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
            "explosion:tnt" // sourceTag
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
