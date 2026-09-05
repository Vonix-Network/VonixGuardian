package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.action.NbtPayload;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.3.2 Y1 regression: fail-closed checked mutation dispatch on NBT failure.
 *
 * <p>The loader-side contract is fail-closed: a broken NBT payload (unparseable
 * bytes, unknown block/entity registry key, mixed-mode rollback with a modded
 * BlockEntity) returns {@code false} from the checked {@code try*} path. It
 * never falls back to a second world mutation after the checked path has
 * started. The core engine catches a thrown mutator failure, continues
 * dispatching the rest of the batch, and reports the incomplete batch as
 * failed.
 *
 * <p>Because the core has no Minecraft types we exercise the engine-side
 * contract here and separately prove that a legacy {@code void} implementation
 * cannot be treated as checked success evidence.
 */
class NbtDecodeFailureFallbackTest {

    /** Requested cells: 1.21.1 Fabric, 1.21.1 NeoForge, 26.1.2 NeoForge. */
    private static final List<String> REQUESTED_LOADER_MUTATORS = List.of(
            "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricWorldMutator.java",
            "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/NeoForgeWorldMutator.java",
            "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/NeoForgeWorldMutator.java"
    );

    @Test
    void fabricDecodeNbt_rejectsOversizedBeforeNbtIoRead() throws Exception {
        Path root = repoRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "repo root not resolvable");
        String mutator = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricWorldMutator.java"));
        int decodeNbt = mutator.indexOf("private static CompoundTag decodeNbt(byte[] bytes)");
        int tooLarge = mutator.indexOf("NbtPayload.tooLarge", decodeNbt);
        int nbtIo = mutator.indexOf("NbtIo.read", decodeNbt);
        int decodeItem = mutator.indexOf("private ItemStack decodeItemStack");
        int plainItem = mutator.indexOf("itemNbt == null || itemNbt.length == 0", decodeItem);
        int decodeItemNbt = mutator.indexOf("decodeNbt(itemNbt)", decodeItem);
        assertThat(decodeNbt).isGreaterThan(-1);
        assertThat(tooLarge).as("oversized guard before NbtIo.read").isGreaterThan(decodeNbt).isLessThan(nbtIo);
        assertThat(plainItem).as("plain-item branch is only for genuine absent NBT")
                .isGreaterThan(decodeItem).isLessThan(decodeItemNbt);
    }

    @Test
    void fabricNbtCapture_preservesOversizedBytesInsteadOfNull() throws Exception {
        Path root = repoRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "repo root not resolvable");
        String capture = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/NbtCapture.java"));
        int overflow = capture.indexOf("if (bytes.length > MAX_NBT_BYTES)");
        int returnBytes = capture.indexOf("return bytes;", overflow);
        assertThat(overflow).isGreaterThan(-1);
        assertThat(returnBytes).as("size overflow still returns serialized bytes").isGreaterThan(overflow);
        String overflowBlock = capture.substring(overflow, returnBytes);
        assertThat(overflowBlock).doesNotContain("return null");
        assertThat(capture).contains("NbtIo.write failed");
    }

    @Test
    void fabricMixinBridge_routesSlotStackFullPayloadsToNbtAwareSubmitters() throws Exception {
        Path root = repoRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "repo root not resolvable");
        String bridge = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricMixinBridge.java"));
        assertThat(bridge)
                .contains("change.itemNbt()")
                .contains("submitContainerChange")
                .contains("submitHopperTransfer")
                .contains("toSlotStacks")
                .contains("NbtCapture.itemStack")
                .contains("pull.itemNbt()")
                .contains("push.itemNbt()");
        int itemDrop = bridge.indexOf("public static void itemDrop");
        int nbtDrop = bridge.indexOf("submitItemDrop", itemDrop);
        int nullDrop = bridge.indexOf("itemNbt != null", itemDrop);
        assertThat(nullDrop).isGreaterThan(itemDrop).isLessThan(nbtDrop);
    }

    @Test
    void every_loader_decodes_block_nbt_before_setBlock_and_checks_entity_type_before_spawn() throws Exception {
        Path root = repoRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "repo root not resolvable");
        assertThat(Files.isDirectory(root.resolve("mc-26.1.2/fabric"))).isFalse();
        assertThat(Files.isDirectory(root.resolve("mc-1.21.1/forge"))).isFalse();
        for (String rel : REQUESTED_LOADER_MUTATORS) {
            Path path = root.resolve(rel);
            assertThat(path).as("requested cell mutator: %s", rel).exists();
            String source = Files.readString(path);
            int set = source.indexOf("boolean placed = level.setBlock");
            int decode = source.indexOf("decodedBlockEntityNbt = decodeNbt");
            int decodeGuard = source.indexOf("if (decodedBlockEntityNbt == null) return false", decode);
            int entityLoad = source.indexOf("Entity e = EntityType.loadEntityRecursive");
            int entityGuard = source.indexOf("e == null || e.getType() != requestedType", entityLoad);
            int entitySpawn = source.indexOf("return level.addFreshEntity(e);", entityGuard);
            assertThat(decode).as("decode-before-place: %s", rel).isGreaterThan(-1).isLessThan(set);
            assertThat(decodeGuard).as("decode-failure guard: %s", rel).isGreaterThan(decode).isLessThan(set);
            assertThat(entityGuard).as("entity identity guard: %s", rel).isGreaterThan(entityLoad).isLessThan(entitySpawn);
            assertThat(source).as("transactional BE compensation: %s", rel)
                    .contains("restoreBlockMutation(level, pos, previousState, previousBlockEntityNbt)");
        }
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))
                    && Files.exists(p.resolve("mc-1.21.1/fabric"))
                    && Files.exists(p.resolve("mc-26.1.2/neoforge"))) {
                return p;
            }
        }
        return null;
    }

    private GuardianDao dao;
    private RollbackEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(GuardianDao.class);
        Executor sync = Runnable::run;
        engine = new RollbackEngine(dao, new NbtFailingMutator(), sync);
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(1L);
        when(dao.closeRollbackBatch(anyLong())).thenReturn(1);
    }

    @Test
    void oversizedItemNbt_neverFallsThroughToLegacyPlainItemPath() throws Exception {
        OversizedFailClosedMutator m = new OversizedFailClosedMutator();
        engine = new RollbackEngine(dao, m, Runnable::run);
        byte[] oversized = new byte[NbtPayload.MAX_BYTES + 1];
        oversized[0] = 21;
        Action a = new ActionBuilder()
                .id(50L)
                .type(ActionType.CONTAINER_WITHDRAW)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(8, 64, 8)
                .targetId("minecraft:diamond_sword")
                .amount(1)
                .itemNbt(oversized)
                .inventorySlot(7)
                .build();
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(a)).thenReturn(List.of());

        assertThatThrownBy(() -> engine.rollback(rangeFilter(), false))
                .isInstanceOf(RollbackMutationException.class);
        assertThat(m.nbtSlotCalls.get()).isEqualTo(1);
        assertThat(m.legacyCalls.get()).isEqualTo(0);
        assertThat(m.plainItemCalls.get()).isEqualTo(0);
    }

    @Test
    void nbt_overload_throws_and_engine_continues_to_next_row_but_fails_batch() throws Exception {
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

        assertThatThrownBy(() -> engine.rollback(rangeFilter(), false))
                .isInstanceOf(RollbackMutationException.class);

        // The NBT overload was called for the broken row and threw.
        assertThat(m.nbtCalls.get()).isEqualTo(1);
        assertThat(m.nbtThrown.get()).isEqualTo(1);
        // The fine row landed on the legacy overload (no NBT) and applied cleanly.
        assertThat(m.legacyCalls.get()).isEqualTo(1);
    }

    @Test
    void legacy_void_compatibility_path_is_not_checked_success_evidence() {
        LegacyVoidMutator m = new LegacyVoidMutator();
        boolean ok = m.trySetBlock("minecraft:overworld", 0, 64, 0, "minecraft:chest", null);
        assertThat(ok).isFalse();
        assertThat(m.legacyCalls).isEqualTo(1);
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

        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) {
            legacyCalls.incrementAndGet();
            return true;
        }
        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m,
                                       String bs, byte[] nbt) {
            nbtCalls.incrementAndGet();
            nbtThrown.incrementAndGet();
            throw new RuntimeException("simulated NBT decoder failure");
        }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String t, int a, String m) {return true; }
        @Override public boolean tryRemoveFromContainer(String w, int x, int y, int z, String t, int a) {return true; }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String t, String m) {return true; }
    }

    static final class LegacyVoidMutator implements WorldMutator {
        int legacyCalls;

        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {
            legacyCalls++;
        }
    }

    /** Records NBT-aware slot dispatch and refuses legacy/plain-item fallbacks. */
    static final class OversizedFailClosedMutator implements WorldMutator {
        final AtomicInteger nbtSlotCalls = new AtomicInteger();
        final AtomicInteger legacyCalls = new AtomicInteger();
        final AtomicInteger plainItemCalls = new AtomicInteger();

        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String t, int a, String m) {
            legacyCalls.incrementAndGet();
            plainItemCalls.incrementAndGet();
            return true;
        }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String t, int a, String m, byte[] nbt) {
            if (nbt == null || nbt.length == 0) {
                return tryGiveOrDrop(w, x, y, z, t, a, m);
            }
            nbtSlotCalls.incrementAndGet();
            return false;
        }
        @Override public boolean tryAddToContainer(String w, int x, int y, int z, String t, int a,
                                                   String m, byte[] nbt, Integer slot) {
            if (slot == null) {
                return tryGiveOrDrop(w, x, y, z, t, a, m, nbt);
            }
            nbtSlotCalls.incrementAndGet();
            return false;
        }
        @Override public boolean tryRemoveFromContainer(String w, int x, int y, int z, String t, int a) {
            legacyCalls.incrementAndGet();
            return true;
        }
        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) {
            legacyCalls.incrementAndGet();
            return true;
        }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String t, String m) {
            legacyCalls.incrementAndGet();
            return true;
        }
    }
}
