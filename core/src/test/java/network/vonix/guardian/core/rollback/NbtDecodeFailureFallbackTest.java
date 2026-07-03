package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.3.2 Y1 regression: log-and-fallback on NBT decode failure.
 *
 * <p>The engine's contract is that a broken NBT payload (unparseable byte[],
 * unknown block/entity registry key, mixed-mode rollback with a modded
 * BlockEntity) MUST NOT throw. The core-side contract is that the engine wraps
 * every mutator dispatch in a try/catch and continues; the loader-side
 * contract (implemented by the WorldMutator NBT overrides in each cell) is
 * that a decode failure logs at DEBUG and falls through to the legacy
 * non-NBT setBlock/giveOrDrop/respawnEntity path.
 *
 * <p>Because the core has no Minecraft types we can only exercise the engine
 * side here: we install a mutator whose NBT overload throws RuntimeException,
 * and prove the engine caught it and moved on to the next row (no unhandled
 * throw + next row still applied through the legacy overload).
 */
class NbtDecodeFailureFallbackTest {

    private GuardianDao dao;
    private RollbackEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(GuardianDao.class);
        Executor sync = Runnable::run;
        engine = new RollbackEngine(dao, new NbtFailingMutator(), sync);
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(1L);
    }

    @Test
    void nbt_overload_throws_and_engine_continues_to_next_row() throws Exception {
        NbtFailingMutator m = new NbtFailingMutator();
        engine = new RollbackEngine(dao, m, Runnable::run);

        byte[] borkedNbt = new byte[] {0x00, 0x01, 0x02}; // definitely not valid NBT
        Action broken = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(0, 64, 0)
                .targetId("minecraft:chest")
                .blockEntityNbt(borkedNbt)
                .build();
        Action fine = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(1, 64, 1)
                .targetId("minecraft:stone")
                .build();

        when(dao.query(any(), anyInt(), anyInt()))
                .thenReturn(List.of(broken, fine))
                .thenReturn(List.of());

        // Must not throw.
        engine.rollback(rangeFilter(), false);

        // The NBT overload was called for the broken row and threw.
        assertThat(m.nbtCalls.get()).isEqualTo(1);
        assertThat(m.nbtThrown.get()).isEqualTo(1);
        // The fine row landed on the legacy overload (no NBT) and applied cleanly.
        assertThat(m.legacyCalls.get()).isEqualTo(1);
    }

    @Test
    void loader_side_fallback_from_broken_nbt_to_legacy_still_places_the_block() {
        // Model the loader-side contract: a cell's NBT setBlock override that
        // catches a decode failure and delegates to the legacy setBlock. We
        // prove the delegate path lands on the plain overload.
        FallbackDelegatingMutator m = new FallbackDelegatingMutator();

        // Simulate the engine calling the NBT overload directly.
        m.setBlock("minecraft:overworld", 0, 64, 0, "minecraft:chest", null,
                "facing=north", new byte[] {(byte) 0xFF});

        assertThat(m.legacyCalls).isEqualTo(1);
        assertThat(m.lastLegacyId).isEqualTo("minecraft:chest");
    }

    private QueryFilter rangeFilter() {
        return QueryFilter.builder()
                .sinceMillis(System.currentTimeMillis() - 3_600_000L)
                .build();
    }

    /** Mutator whose NBT setBlock overload throws (simulating a decoder crash). */
    static final class NbtFailingMutator implements WorldMutator {
        final AtomicInteger nbtCalls = new AtomicInteger();
        final AtomicInteger nbtThrown = new AtomicInteger();
        final AtomicInteger legacyCalls = new AtomicInteger();

        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {
            legacyCalls.incrementAndGet();
        }
        @Override public void setBlock(String w, int x, int y, int z, String t, String m,
                                       String bs, byte[] nbt) {
            nbtCalls.incrementAndGet();
            nbtThrown.incrementAndGet();
            throw new RuntimeException("simulated NBT decoder failure");
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m) {}
        @Override public void removeFromContainer(String w, int x, int y, int z, String t, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String m) {}
    }

    /**
     * Loader-side pattern: NBT overload catches decode failure internally and
     * delegates to the legacy overload. Mirrors what every cell's
     * WorldMutator NBT override does (see NeoForgeWorldMutator etc).
     */
    static final class FallbackDelegatingMutator implements WorldMutator {
        int legacyCalls = 0;
        String lastLegacyId = null;

        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {
            legacyCalls++;
            lastLegacyId = t;
        }
        @Override public void setBlock(String w, int x, int y, int z, String t, String m,
                                       String bs, byte[] nbt) {
            // Simulate NbtIo.read failing → catch → delegate.
            try {
                if (nbt != null) throw new RuntimeException("bad NBT");
            } catch (Throwable ex) {
                // Log-and-fallback: never throw, delegate to legacy.
                setBlock(w, x, y, z, t, m);
                return;
            }
            setBlock(w, x, y, z, t, m);
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m) {}
        @Override public void removeFromContainer(String w, int x, int y, int z, String t, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String m) {}
    }
}
