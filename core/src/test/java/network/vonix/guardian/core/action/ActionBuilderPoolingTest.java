package network.vonix.guardian.core.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W2 — regression test for the {@link ActionBuilder#reset()} pooling
 * contract used by {@code Guardian}'s server-thread scratch builder.
 *
 * <p>Guardian's {@code seed(...)} hot path pulls a {@link ThreadLocal
 * ThreadLocal&lt;ActionBuilder&gt;} scratch, calls {@code reset()}, then fills
 * the fields. If reset misses a field, stale state leaks into the next
 * submission — a subtle cross-event corruption that only shows up under
 * mixed-load bursts. This suite asserts:</p>
 * <ol>
 *   <li>{@code reset()} clears every field back to a freshly-constructed
 *       state, verified by rebuilding an {@link Action} with the required
 *       fields only and asserting all optional fields have their default.</li>
 *   <li>Two back-to-back build/reset cycles on the SAME builder produce
 *       byte-identical results to two fresh {@code new ActionBuilder()}
 *       cycles — i.e. pooling is transparent.</li>
 *   <li>10k reset/populate/build cycles run without accumulating any state
 *       (the builder returned each time is the same instance).</li>
 * </ol>
 */
class ActionBuilderPoolingTest {

    @Test
    void resetClearsEveryField() {
        ActionBuilder b = new ActionBuilder()
            .type(ActionType.BLOCK_BREAK)
            .actorUuid(UUID.randomUUID())
            .actorName("alice")
            .worldId("minecraft:overworld")
            .position(1, 64, 2)
            .targetId("minecraft:stone")
            .targetMeta("facing=north")
            .amount(3)
            .rolledBack(true)
            .sourceTag("#fire")
            .signSide("front")
            .signDyeColor("red")
            .signWaxed(Boolean.TRUE)
            .timestamp(12345L)
            .id(42L);

        // Reset then rebuild with only required fields; every optional should
        // be back to the default.
        b.reset()
            .type(ActionType.BLOCK_PLACE)
            .worldId("minecraft:overworld");
        Action out = b.build();

        assertThat(out.id()).isEqualTo(-1L);
        assertThat(out.type()).isEqualTo(ActionType.BLOCK_PLACE);
        assertThat(out.actorUuid()).isNull();
        assertThat(out.actorName()).isEqualTo(ActionBuilder.UNKNOWN_ACTOR_NAME);
        assertThat(out.worldId()).isEqualTo("minecraft:overworld");
        assertThat(out.x()).isZero();
        assertThat(out.y()).isZero();
        assertThat(out.z()).isZero();
        assertThat(out.targetId()).isNull();
        assertThat(out.targetMeta()).isNull();
        assertThat(out.amount()).isEqualTo(1);
        assertThat(out.rolledBack()).isFalse();
        assertThat(out.sourceTag()).isNull();
        assertThat(out.signSide()).isNull();
        assertThat(out.signDyeColor()).isNull();
        assertThat(out.signWaxed()).isNull();
    }

    @Test
    void pooledBuilderProducesSameActionAsFreshBuilder() {
        UUID actor = UUID.randomUUID();

        ActionBuilder pooled = new ActionBuilder();
        Action first = pooled.reset()
            .type(ActionType.BLOCK_PLACE).actorUuid(actor).actorName("alice")
            .worldId("minecraft:overworld").position(10, 64, 20)
            .targetId("minecraft:oak_log").timestamp(1_000L)
            .build();

        Action fresh1 = new ActionBuilder()
            .type(ActionType.BLOCK_PLACE).actorUuid(actor).actorName("alice")
            .worldId("minecraft:overworld").position(10, 64, 20)
            .targetId("minecraft:oak_log").timestamp(1_000L)
            .build();

        // Pooled + fresh must be equal.
        assertThat(first).isEqualTo(fresh1);

        // Second cycle on the pooled instance: different type + amount, must
        // not leak the previous position/actor/etc.
        Action second = pooled.reset()
            .type(ActionType.ITEM_DROP).worldId("minecraft:the_nether")
            .position(1, 2, 3).targetId("minecraft:diamond").amount(5)
            .timestamp(2_000L).build();

        Action fresh2 = new ActionBuilder()
            .type(ActionType.ITEM_DROP).worldId("minecraft:the_nether")
            .position(1, 2, 3).targetId("minecraft:diamond").amount(5)
            .timestamp(2_000L).build();

        assertThat(second).isEqualTo(fresh2);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void tenThousandCyclesOnPooledBuilderRemainStable() {
        // Observable side-effect that the pooling is transparent: 10k cycles
        // on the SAME ActionBuilder should always yield the same Action as a
        // freshly-constructed builder given the same inputs, with no state
        // bleed.
        ActionBuilder pooled = new ActionBuilder();
        for (int i = 0; i < 10_000; i++) {
            Action pooledA = pooled.reset()
                .type(ActionType.ENTITY_SPAWN)
                .worldId("minecraft:overworld")
                .position(i, 64, i)
                .targetId("minecraft:zombie")
                .sourceTag("spawn:join")
                .timestamp(i)
                .build();

            Action freshA = new ActionBuilder()
                .type(ActionType.ENTITY_SPAWN)
                .worldId("minecraft:overworld")
                .position(i, 64, i)
                .targetId("minecraft:zombie")
                .sourceTag("spawn:join")
                .timestamp(i)
                .build();

            assertThat(pooledA).isEqualTo(freshA);
        }
    }
}
