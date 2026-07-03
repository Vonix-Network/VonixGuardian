package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X1 regression: end-to-end round-trip of the five NBT-fidelity fields
 * through {@link ActionBuilder} → {@link Action} record → the NBT-aware
 * {@link WorldMutator} overloads.
 *
 * <p>Core has no Minecraft types so we can't build a real {@code CompoundTag}
 * or {@code BlockState} here — those are exercised by the loader-cell tests in
 * X2/X4/X7/X9. What this test guarantees is that the byte[] / property-string
 * payload the loader hands the ActionBuilder survives byte-for-byte through the
 * Action record and lands on the WorldMutator override the loader cells will
 * eventually implement.
 *
 * <p>Three scenarios (matches the prompt's regression triple):
 * <ul>
 *   <li>waterlogged fence block-state property string round-trips
 *       (BLOCK_BREAK / BLOCK_PLACE);</li>
 *   <li>named + enchanted item NBT bytes round-trip (ITEM_DROP);</li>
 *   <li>chest block-entity NBT bytes with contents round-trip
 *       (BLOCK_BREAK).</li>
 * </ul>
 */
class NbtRollbackFidelityTest {

    @Test
    void waterlogged_fence_block_state_survives_the_round_trip() {
        Action a = new ActionBuilder()
                .type(ActionType.BLOCK_PLACE)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(10, 64, 20)
                .targetId("minecraft:oak_fence")
                .newBlockState("waterlogged=true,north=true,south=true")
                .build();

        assertThat(a.newBlockState()).isEqualTo("waterlogged=true,north=true,south=true");
        assertThat(a.hasNbt()).isTrue();

        RecordingMutator m = new RecordingMutator();
        // Simulate the rollback engine handing the NBT-aware overload to the mutator.
        m.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                  a.newBlockState(), a.blockEntityNbt());

        assertThat(m.setBlockCalls).hasSize(1);
        RecordingMutator.SetBlockCall c = m.setBlockCalls.get(0);
        assertThat(c.blockState).isEqualTo("waterlogged=true,north=true,south=true");
        assertThat(c.blockEntityNbt).isNull();
    }

    @Test
    void named_and_enchanted_item_nbt_bytes_survive_the_round_trip() {
        // Producer captures NbtIo.write(itemStack.getTag()) into a byte[].
        // We pretend "the tag says Nietzsche + sharpness-V" via a stable marker.
        byte[] itemNbt = utf8("{display:{Name:'Nietzsche'},Enchantments:[{id:'sharpness',lvl:5}]}");

        Action a = new ActionBuilder()
                .type(ActionType.ITEM_DROP)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(0, 64, 0)
                .targetId("minecraft:netherite_sword")
                .amount(1)
                .itemNbt(itemNbt)
                .build();

        // Reference identity is enough: the builder must not copy the array
        // (which would double allocations on the hot path).
        assertThat(a.itemNbt()).isSameAs(itemNbt);

        RecordingMutator m = new RecordingMutator();
        m.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.amount(),
                     a.targetMeta(), a.itemNbt());

        assertThat(m.giveOrDropCalls).hasSize(1);
        RecordingMutator.GiveOrDropCall c = m.giveOrDropCalls.get(0);
        assertThat(c.itemNbt).isSameAs(itemNbt);
    }

    @Test
    void chest_break_carries_block_entity_nbt_with_contents() {
        // Producer captures a full BE snapshot: chest slots, custom name, lock.
        byte[] beNbt = utf8("{Items:[{Slot:0,id:'diamond',Count:64},{Slot:1,id:'emerald',Count:32}],"
                          + "CustomName:'\"Fort Knox\"'}");

        Action a = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(-5, 62, 12)
                .targetId("minecraft:chest")
                .oldBlockState("facing=north,type=single,waterlogged=false")
                .blockEntityNbt(beNbt)
                .build();

        assertThat(a.blockEntityNbt()).isSameAs(beNbt);
        assertThat(a.oldBlockState()).isEqualTo("facing=north,type=single,waterlogged=false");
        assertThat(a.hasNbt()).isTrue();

        RecordingMutator m = new RecordingMutator();
        // Rollback path: put back the chest at the recorded state + BE.
        m.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                  a.oldBlockState(), a.blockEntityNbt());

        assertThat(m.setBlockCalls).hasSize(1);
        RecordingMutator.SetBlockCall c = m.setBlockCalls.get(0);
        assertThat(c.blockState).isEqualTo("facing=north,type=single,waterlogged=false");
        assertThat(c.blockEntityNbt).isSameAs(beNbt);
    }

    @Test
    void null_nbt_action_takes_the_delegated_legacy_path() {
        // When persistNbt=false (the default) or the producer just doesn't
        // populate NBT (chat, session), the NBT-aware overload's default impl
        // in the WorldMutator interface delegates to the non-NBT overload.
        // That default MUST route into the same underlying legacy call the
        // pre-1.3.1 rollback engine used, otherwise loader cells that never
        // override the new overload silently break.
        Action a = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(0, 0, 0)
                .targetId("minecraft:stone")
                .build();

        assertThat(a.hasNbt()).isFalse();
        assertThat(a.blockEntityNbt()).isNull();
        assertThat(a.oldBlockState()).isNull();

        // A mutator that only implements the legacy (non-NBT) overload — this
        // is the state of every loader cell in the repo at X1 time. The default
        // NBT overload from the interface must delegate here.
        LegacyOnlyMutator legacy = new LegacyOnlyMutator();
        legacy.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                       a.oldBlockState(), a.blockEntityNbt());
        assertThat(legacy.legacyCalls).isEqualTo(1);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Overrides the NBT-aware overloads so we can inspect the plumbed args. */
    private static final class RecordingMutator implements WorldMutator {
        static final class SetBlockCall {
            final String blockState;
            final byte[] blockEntityNbt;
            SetBlockCall(String s, byte[] b) { this.blockState = s; this.blockEntityNbt = b; }
        }
        static final class GiveOrDropCall {
            final byte[] itemNbt;
            GiveOrDropCall(byte[] b) { this.itemNbt = b; }
        }
        final List<SetBlockCall> setBlockCalls = new ArrayList<>();
        final List<GiveOrDropCall> giveOrDropCalls = new ArrayList<>();

        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {
            throw new AssertionError("legacy setBlock should not be called when NBT overload is implemented");
        }
        @Override public void setBlock(String w, int x, int y, int z, String t, String m,
                                       String blockState, byte[] blockEntityNbt) {
            setBlockCalls.add(new SetBlockCall(blockState,
                blockEntityNbt == null ? null : Arrays.copyOf(blockEntityNbt, blockEntityNbt.length)));
            // Also stash by-reference for the identity assertions.
            setBlockCalls.set(setBlockCalls.size() - 1, new SetBlockCall(blockState, blockEntityNbt));
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m) {
            throw new AssertionError("legacy giveOrDrop should not be called when NBT overload is implemented");
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m,
                                         byte[] itemNbt) {
            giveOrDropCalls.add(new GiveOrDropCall(itemNbt));
        }
        @Override public void removeFromContainer(String w, int x, int y, int z, String t, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String m) {}
    }

    /**
     * Simulates a loader cell that has NOT yet been rebased to the X1 API — it
     * only overrides the pre-1.3.1 non-NBT methods. The interface's default
     * NBT overload must delegate to these.
     */
    private static final class LegacyOnlyMutator implements WorldMutator {
        int legacyCalls = 0;

        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {
            legacyCalls++;
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m) {}
        @Override public void removeFromContainer(String w, int x, int y, int z, String t, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String m) {}
    }
}
