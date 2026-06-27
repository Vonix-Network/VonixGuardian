package network.vonix.guardian.core.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionBuilderTest {

    @Test
    void buildsFullyPopulatedAction() {
        UUID actor = UUID.randomUUID();
        Action a = new ActionBuilder()
            .id(42L)
            .timestamp(1_735_200_000_000L)
            .type(ActionType.BLOCK_BREAK)
            .actorUuid(actor)
            .actorName("Notch")
            .worldId("minecraft:overworld")
            .position(123, 64, -456)
            .targetId("minecraft:diamond_ore")
            .targetMeta("{\"state\":\"facing=north\"}")
            .amount(1)
            .rolledBack(false)
            .sourceTag("manual")
            .build();

        assertThat(a.id()).isEqualTo(42L);
        assertThat(a.timestamp()).isEqualTo(1_735_200_000_000L);
        assertThat(a.type()).isEqualTo(ActionType.BLOCK_BREAK);
        assertThat(a.actorUuid()).isEqualTo(actor);
        assertThat(a.actorName()).isEqualTo("Notch");
        assertThat(a.worldId()).isEqualTo("minecraft:overworld");
        assertThat(a.x()).isEqualTo(123);
        assertThat(a.y()).isEqualTo(64);
        assertThat(a.z()).isEqualTo(-456);
        assertThat(a.targetId()).isEqualTo("minecraft:diamond_ore");
        assertThat(a.targetMeta()).isEqualTo("{\"state\":\"facing=north\"}");
        assertThat(a.amount()).isEqualTo(1);
        assertThat(a.rolledBack()).isFalse();
        assertThat(a.sourceTag()).isEqualTo("manual");
        assertThat(a.isPositional()).isTrue();
    }

    @Test
    void appliesDefaultsWhenUnset() {
        long before = System.currentTimeMillis();
        Action a = new ActionBuilder()
            .type(ActionType.BLOCK_PLACE)
            .worldId("minecraft:overworld")
            .build();
        long after = System.currentTimeMillis();

        assertThat(a.id()).isEqualTo(-1L);
        assertThat(a.timestamp()).isBetween(before, after);
        assertThat(a.amount()).isEqualTo(1);
        assertThat(a.rolledBack()).isFalse();
        assertThat(a.actorUuid()).isNull();
        assertThat(a.actorName()).isEqualTo("#unknown");
        assertThat(a.targetId()).isNull();
        assertThat(a.targetMeta()).isNull();
        assertThat(a.sourceTag()).isNull();
        assertThat(a.x()).isZero();
        assertThat(a.y()).isZero();
        assertThat(a.z()).isZero();
    }

    @Test
    void doesNotForceUnknownNameWhenUuidPresent() {
        UUID actor = UUID.randomUUID();
        Action a = new ActionBuilder()
            .type(ActionType.CHAT)
            .worldId("minecraft:overworld")
            .actorUuid(actor)
            .build();

        assertThat(a.actorUuid()).isEqualTo(actor);
        assertThat(a.actorName()).isNull();
    }

    @Test
    void keepsExplicitlySetActorName() {
        Action a = new ActionBuilder()
            .type(ActionType.BLOCK_BREAK)
            .worldId("minecraft:overworld")
            .actorName("#creeper")
            .build();

        assertThat(a.actorUuid()).isNull();
        assertThat(a.actorName()).isEqualTo("#creeper");
    }

    @Test
    void typeIsRequired() {
        assertThatThrownBy(() -> new ActionBuilder()
                .worldId("minecraft:overworld")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("type");
    }

    @Test
    void worldIdIsRequired() {
        assertThatThrownBy(() -> new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worldId");
    }

    @Test
    void typeSetterRejectsNull() {
        assertThatThrownBy(() -> new ActionBuilder().type(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void worldIdSetterRejectsNull() {
        assertThatThrownBy(() -> new ActionBuilder().worldId(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void individualPositionSettersWork() {
        Action a = new ActionBuilder()
            .type(ActionType.BLOCK_PLACE)
            .worldId("minecraft:overworld")
            .x(1).y(2).z(3)
            .build();
        assertThat(a.x()).isEqualTo(1);
        assertThat(a.y()).isEqualTo(2);
        assertThat(a.z()).isEqualTo(3);
    }

    @Test
    void isPositionalIsFalseForNonSpatialEvents() {
        for (ActionType t : new ActionType[]{
            ActionType.CHAT, ActionType.COMMAND, ActionType.SESSION_JOIN,
            ActionType.SESSION_LEAVE, ActionType.USERNAME_CHANGE
        }) {
            Action a = new ActionBuilder().type(t).worldId("minecraft:overworld").build();
            assertThat(a.isPositional()).as("type %s", t).isFalse();
        }
    }

    @Test
    void isPositionalIsTrueForSpatialEvents() {
        for (ActionType t : new ActionType[]{
            ActionType.BLOCK_PLACE, ActionType.BLOCK_BREAK,
            ActionType.CONTAINER_DEPOSIT, ActionType.CONTAINER_WITHDRAW,
            ActionType.ITEM_DROP, ActionType.ITEM_PICKUP,
            ActionType.ENTITY_KILL, ActionType.EXPLOSION, ActionType.SIGN
        }) {
            Action a = new ActionBuilder().type(t).worldId("minecraft:overworld").build();
            assertThat(a.isPositional()).as("type %s", t).isTrue();
        }
    }

    @Test
    void buildersAreIndependent() {
        ActionBuilder b1 = new ActionBuilder()
            .type(ActionType.BLOCK_PLACE).worldId("w").amount(5);
        ActionBuilder b2 = new ActionBuilder()
            .type(ActionType.BLOCK_BREAK).worldId("w").amount(9);
        assertThat(b1.build().amount()).isEqualTo(5);
        assertThat(b2.build().amount()).isEqualTo(9);
    }
}
