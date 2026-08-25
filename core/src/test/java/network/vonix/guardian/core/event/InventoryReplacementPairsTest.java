package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryReplacementPairsTest {

    @Test
    void nextPairIdIsNeverZeroAndVaries() {
        long a = InventoryReplacementPairs.nextPairId();
        long b = InventoryReplacementPairs.nextPairId();
        assertThat(a).isNotZero();
        assertThat(b).isNotZero().isNotEqualTo(a);
    }

    @Test
    void firePairIsNotAnInventoryReplacement() {
        long pair = 99L;
        Action breakRow = action(ActionType.ENTITY_CHANGE_BLOCK, pair);
        Action fire = action(ActionType.IGNITE, pair);
        assertThat(InventoryReplacementPairs.isMember(breakRow)).isFalse();
        assertThat(InventoryReplacementPairs.isPair(breakRow, fire)).isFalse();
    }

    @Test
    void withdrawAndDepositWithSharedPairIdAreAReplacement() {
        long pair = InventoryReplacementPairs.nextPairId();
        Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, pair, 1L);
        Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, pair, 2L);
        assertThat(InventoryReplacementPairs.isPair(withdraw, deposit)).isTrue();
        assertThat(InventoryReplacementPairs.siblingOf(withdraw, List.of(deposit, withdraw)))
                .isSameAs(deposit);
    }

    @Test
    void unpairedInventoryRowsAreNotMembers() {
        Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, null, 1L);
        assertThat(InventoryReplacementPairs.isMember(deposit)).isFalse();
        assertThat(InventoryReplacementPairs.isReplacement(List.of(
                new InventoryDelta(InventoryDelta.Kind.WITHDRAW, "minecraft:diamond", 1),
                new InventoryDelta(InventoryDelta.Kind.DEPOSIT, "minecraft:emerald", 1)))).isTrue();
        assertThat(InventoryReplacementPairs.isReplacement(List.of(
                new InventoryDelta(InventoryDelta.Kind.DEPOSIT, "minecraft:diamond", 1)))).isFalse();
    }

    private static Action action(ActionType type, Long pairId) {
        return new ActionBuilder()
                .type(type)
                .worldId("minecraft:overworld")
                .actorUuid(UUID.randomUUID())
                .actorName("Player")
                .targetId("minecraft:stone")
                .pairId(pairId)
                .build();
    }

    private static Action inventory(ActionType type, Long pairId, long id) {
        return new ActionBuilder()
                .id(id)
                .type(type)
                .worldId("minecraft:overworld")
                .actorUuid(UUID.randomUUID())
                .actorName("Player")
                .targetId("minecraft:diamond")
                .amount(1)
                .itemNbt(new byte[]{1})
                .inventorySlot(5)
                .pairId(pairId)
                .build();
    }
}
