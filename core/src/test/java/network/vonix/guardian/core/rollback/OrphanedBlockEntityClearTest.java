package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2.2.0 OE1 regression: every rollback inverse that removes or replaces a block
 * must go through the block-entity-clearing path.
 *
 * <p>Defect (observed live on a 1.18.2 Forge modpack): a rollback clearing a
 * position used {@code trySetBlock(..., AIR, null)}. Vanilla
 * {@code Level.setBlock(pos, state, UPDATE_ALL)} only removes a block entity as
 * a side effect of a {@code BlockEntity}-owning block-state change, so a stale
 * block entity survived the clear, was re-serialised to the region file, and on
 * the next chunk load reconstructed its recorded id ({@code minecraft:bed} /
 * {@code minecraft:banner}) against an unrelated block state, throwing
 * {@code ClassCastException} and aborting the block entity — 142 console errors
 * from a single affected area, plus permanent cosmetic loss of the bed/banner
 * variant data.
 *
 * <p>The engine-side contract is what the executable part of this test pins: no
 * removing inverse may call the plain {@code trySetBlock(..., air, null)} path,
 * and a failed clear must surface as a failure rather than being counted as
 * applied. The loader cells provide the world-side implementation, and the
 * source-scan tests prove each shipped cell overrides it.
 */
class OrphanedBlockEntityClearTest {

    private static final String AIR = "minecraft:air";

    /** Every shipped loader cell that must provide the clearing implementation. */
    private static final List<String> LOADER_MUTATORS = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/FabricWorldMutator.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/FabricWorldMutator.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/FabricWorldMutator.java",
            "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricWorldMutator.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeWorldMutator.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/ForgeWorldMutator.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/ForgeWorldMutator.java",
            "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/NeoForgeWorldMutator.java",
            "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/NeoForgeWorldMutator.java",
            "mc-26.2/fabric/src/main/java/network/vonix/guardian/mc/v26_2/fabric/FabricWorldMutator.java",
            "mc-26.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_2/neoforge/NeoForgeWorldMutator.java",
            "mc-26.3/fabric/src/main/java/network/vonix/guardian/mc/v26_3/fabric/FabricWorldMutator.java",
            "mc-26.3/neoforge/src/main/java/network/vonix/guardian/mc/v26_3/neoforge/NeoForgeWorldMutator.java");

    private GuardianDao dao;
    private RecordingMutator mutator;
    private RollbackEngine engine;

    /** Records which mutator path each inverse dispatched through. */
    private static class RecordingMutator implements WorldMutator {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile boolean clearReturns = true;

        @Override
        public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
            calls.add("setBlock|" + x + "|" + y + "|" + z + "|" + targetId);
            return true;
        }

        @Override
        public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                                   String blockState, byte[] blockEntityNbt) {
            calls.add("setBlockNbt|" + x + "|" + y + "|" + z + "|" + targetId);
            return true;
        }

        @Override
        public boolean tryClearBlock(String worldId, int x, int y, int z) {
            calls.add("clearBlock|" + x + "|" + y + "|" + z);
            return clearReturns;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(GuardianDao.class);
        mutator = new RecordingMutator();
        engine = new RollbackEngine(dao, mutator, Runnable::run);
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(77L);
        when(dao.closeRollbackBatch(anyLong())).thenReturn(1);
    }

    private List<String> callsMatching(String prefix) {
        List<String> out = new ArrayList<>();
        for (String c : mutator.calls) {
            if (c.startsWith(prefix)) out.add(c);
        }
        return out;
    }

    private Action blockAction(ActionType type, long id) {
        return blockActionAt(type, id, (int) id);
    }

    /** Distinct coordinates keep a multi-action batch out of position de-dupe. */
    private Action blockActionAt(ActionType type, long id, int coord) {
        return new Action(
                id, System.currentTimeMillis(), type,
                UUID.randomUUID(), "Player",
                "w", coord, 64, coord,
                "minecraft:oak_planks", null, 1, false, null);
    }

    /** BLOCK_PLACE inverse must clear the block entity, not merely set air. */
    @Test
    void blockPlaceInverseUsesClearBlock() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(blockAction(ActionType.BLOCK_PLACE, 1L)));

        engine.rollback(QueryFilter.builder().sinceMillis(1L).build(), false);

        assertThat(callsMatching("clearBlock|"))
                .as("BLOCK_PLACE inverse must route through tryClearBlock (OE1)")
                .isNotEmpty();
        assertThat(mutator.calls)
                .as("no inverse may use plain setBlock(AIR); that leaves orphaned block entities behind")
                .noneMatch(c -> c.startsWith("setBlock|") && c.endsWith("|" + AIR));
    }

    /** A failed clear must surface as a failure, never be counted as applied. */
    @Test
    void failedClearIsNotReportedAsApplied() throws Exception {
        mutator.clearReturns = false;
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(blockAction(ActionType.BLOCK_PLACE, 2L)));

        RollbackResult result = engine.rollback(QueryFilter.builder().sinceMillis(1L).build(), false);

        assertThat(callsMatching("clearBlock|"))
                .as("the clear path must actually have been dispatched")
                .isNotEmpty();
        assertThat(result.failedCount() + result.repairRequiredCount())
                .as("a false from tryClearBlock must be reported, never counted as applied")
                .isGreaterThan(0);
    }

    /** Removing inverses other than BLOCK_PLACE must also avoid plain setBlock(AIR). */
    @Test
    void removingInversesNeverUsePlainSetAir() throws Exception {
        // BLOCK_BREAK's INVERSE restores the broken block, and ENTITY_CHANGE_BLOCK's
        // INVERSE restores the prior block; blast/expansion removals must clear.
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(
                blockActionAt(ActionType.BLOCK_PLACE, 11L, 111),
                blockActionAt(ActionType.BLOCK_BREAK, 12L, 222),
                blockActionAt(ActionType.ENTITY_CHANGE_BLOCK, 13L, 333)));

        engine.rollback(QueryFilter.builder().sinceMillis(1L).build(), false);

        assertThat(mutator.calls)
                .as("no removing/restoring inverse may dispatch plain setBlock(AIR) — OE1")
                .noneMatch(c -> c.startsWith("setBlock|") && c.endsWith("|" + AIR));
        assertThat(callsMatching("clearBlock|"))
                .as("the BLOCK_PLACE inverse in this batch must clear through tryClearBlock")
                .isNotEmpty();
    }

    /** Every action type whose inverse clears a position must not use plain setBlock(AIR). */
    @Test
    void engineSourceHasNoPlainAirSetBlockInverse() throws Exception {
        Path root = locateRepoRoot();
        Path engineSrc = root.resolve(
                "core/src/main/java/network/vonix/guardian/core/rollback/RollbackEngine.java");
        assertThat(engineSrc).exists();
        String src = Files.readString(engineSrc);
        assertThat(src)
                .as("RollbackEngine must not contain a plain trySetBlock(..., AIR, null) "
                        + "inverse; every removal must route through tryClearBlock (OE1)")
                .doesNotContain("mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null)");
        assertThat(src).contains("tryClearBlock(");
    }

    /** Every shipped loader cell must implement the clearing path. */
    @Test
    void everyLoaderCellImplementsClearBlock() throws Exception {
        Path root = locateRepoRoot();
        List<String> missing = new ArrayList<>();
        for (String rel : LOADER_MUTATORS) {
            Path p = root.resolve(rel);
            if (!Files.exists(p)) {
                missing.add(rel + " (absent)");
                continue;
            }
            String src = Files.readString(p);
            if (!src.contains("public boolean tryClearBlock(")) {
                missing.add(rel + " (no tryClearBlock override)");
            } else if (!src.contains("removeBlockEntity(")) {
                missing.add(rel + " (tryClearBlock does not remove the block entity)");
            }
        }
        assertThat(missing)
                .as("every loader cell must implement a block-entity-clearing tryClearBlock")
                .isEmpty();
    }

    /** No loader cell may omit the stale-BE clear from the NBT-less setBlock path. */
    @Test
    void loaderCellsClearStaleBlockEntityOnPlainSetBlock() throws Exception {
        Path root = locateRepoRoot();
        List<String> missing = new ArrayList<>();
        for (String rel : LOADER_MUTATORS) {
            Path p = root.resolve(rel);
            if (!Files.exists(p)) continue;
            if (!Files.readString(p).contains("could not clear stale block entity")) {
                missing.add(rel);
            }
        }
        assertThat(missing)
                .as("a plain setBlock over a stale block entity must clear it explicitly (OE1)")
                .isEmpty();
    }

    /** Resolve the repo root by walking up from the module working directory. */
    private static Path locateRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))) return p;
        }
        return dir;
    }
}
