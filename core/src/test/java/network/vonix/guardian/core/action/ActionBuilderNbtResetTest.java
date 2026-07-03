package network.vonix.guardian.core.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X1 regression: {@link ActionBuilder#reset()} MUST clear every NBT
 * byte[] / block-state string reference so the next event routed through the
 * per-thread scratch builder does not carry a dangling pointer to the previous
 * event's payload.
 *
 * <p>Without reset() clearing these fields, the scratch-builder amortization
 * from v1.3.0 W2 (a single {@code ActionBuilder} per thread, {@code reset()}
 * called at the top of every {@code Guardian.seed}) would leak the previous
 * submit's block-entity NBT into the next unrelated event. On the hot path
 * (piston / hopper / entity spam) that would corrupt {@code /vg rollback}
 * output AND double heap pressure since the DAO batch still holds a reference
 * to the previous byte[] via its already-built Action.
 */
class ActionBuilderNbtResetTest {

    @Test
    void reset_clears_all_five_nbt_fields() {
        ActionBuilder b = new ActionBuilder();

        byte[] be = "chest-contents".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] item = "sword-nbt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] entity = "zombie-nbt".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Action first = b
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(1, 2, 3)
                .targetId("minecraft:chest")
                .oldBlockState("facing=north")
                .newBlockState("facing=north,waterlogged=true")
                .blockEntityNbt(be)
                .itemNbt(item)
                .entityNbt(entity)
                .build();

        // The immutable Action carries the byte[] by reference.
        assertThat(first.oldBlockState()).isEqualTo("facing=north");
        assertThat(first.newBlockState()).isEqualTo("facing=north,waterlogged=true");
        assertThat(first.blockEntityNbt()).isSameAs(be);
        assertThat(first.itemNbt()).isSameAs(item);
        assertThat(first.entityNbt()).isSameAs(entity);

        // Reset: recycle the mutable scratch, keep the immutable Action.
        b.reset();

        // Now build a fully unrelated event and verify NONE of the previous
        // NBT payload leaks in.
        Action second = b
                .type(ActionType.CHAT)
                .worldId("minecraft:overworld")
                .actorName("Herobrine")
                .targetId("hello")
                .build();

        assertThat(second.oldBlockState()).isNull();
        assertThat(second.newBlockState()).isNull();
        assertThat(second.blockEntityNbt()).isNull();
        assertThat(second.itemNbt()).isNull();
        assertThat(second.entityNbt()).isNull();
        assertThat(second.hasNbt()).isFalse();

        // First action must still hold its original bytes — reset() does NOT
        // mutate the already-built Action, only the mutable builder scaffolding.
        assertThat(first.blockEntityNbt()).isSameAs(be);
        assertThat(first.itemNbt()).isSameAs(item);
        assertThat(first.entityNbt()).isSameAs(entity);
    }

    @Test
    void reset_returns_same_builder_for_fluent_reuse() {
        ActionBuilder b = new ActionBuilder();
        assertThat(b.reset()).isSameAs(b);
    }

    @Test
    void reused_builder_produces_byte_parity_with_fresh_builder() {
        // The invariant Guardian.seed relies on: reset() + fluent-chain must
        // produce an Action byte-identical to `new ActionBuilder().fluent().build()`.
        ActionBuilder shared = new ActionBuilder();

        byte[] nbt = "nbt".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Warm the shared builder with garbage the reset must scrub.
        shared.type(ActionType.BLOCK_BREAK)
              .worldId("minecraft:overworld")
              .position(999, 999, 999)
              .targetId("minecraft:garbage")
              .oldBlockState("junk=true")
              .blockEntityNbt(new byte[]{9, 9, 9})
              .itemNbt(new byte[]{7, 7, 7})
              .entityNbt(new byte[]{5, 5, 5})
              .build();

        Action fromShared = shared.reset()
                .type(ActionType.BLOCK_PLACE)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(0, 64, 0)
                .targetId("minecraft:oak_fence")
                .newBlockState("waterlogged=true")
                .blockEntityNbt(nbt)
                .build();

        Action fromFresh = new ActionBuilder()
                .type(ActionType.BLOCK_PLACE)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(0, 64, 0)
                .targetId("minecraft:oak_fence")
                .newBlockState("waterlogged=true")
                .blockEntityNbt(nbt)
                .build();

        // Timestamp is default-set to System.currentTimeMillis() at build time
        // when unspecified; equalize before comparing.
        Action fromSharedNormalized = new Action(
                fromShared.id(), 0L, fromShared.type(), fromShared.actorUuid(),
                fromShared.actorName(), fromShared.worldId(),
                fromShared.x(), fromShared.y(), fromShared.z(),
                fromShared.targetId(), fromShared.targetMeta(),
                fromShared.amount(), fromShared.rolledBack(), fromShared.sourceTag(),
                fromShared.signSide(), fromShared.signDyeColor(), fromShared.signWaxed(),
                fromShared.oldBlockState(), fromShared.newBlockState(),
                fromShared.blockEntityNbt(), fromShared.itemNbt(), fromShared.entityNbt());
        Action fromFreshNormalized = new Action(
                fromFresh.id(), 0L, fromFresh.type(), fromFresh.actorUuid(),
                fromFresh.actorName(), fromFresh.worldId(),
                fromFresh.x(), fromFresh.y(), fromFresh.z(),
                fromFresh.targetId(), fromFresh.targetMeta(),
                fromFresh.amount(), fromFresh.rolledBack(), fromFresh.sourceTag(),
                fromFresh.signSide(), fromFresh.signDyeColor(), fromFresh.signWaxed(),
                fromFresh.oldBlockState(), fromFresh.newBlockState(),
                fromFresh.blockEntityNbt(), fromFresh.itemNbt(), fromFresh.entityNbt());

        assertThat(fromSharedNormalized).isEqualTo(fromFreshNormalized);
        // And the NBT byte[] reference identity survived the pool.
        assertThat(fromShared.blockEntityNbt()).isSameAs(nbt);
    }
}
