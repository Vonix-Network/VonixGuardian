package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryDeltaTest {

    @Test
    void identifiesAVisibleDeposit() {
        assertThat(InventoryDelta.between(null, 0, "minecraft:diamond", 3))
                .isEqualTo(new InventoryDelta(InventoryDelta.Kind.DEPOSIT, "minecraft:diamond", 3));
    }

    @Test
    void identifiesAVisibleWithdraw() {
        assertThat(InventoryDelta.between("minecraft:diamond", 5, "minecraft:diamond", 2))
                .isEqualTo(new InventoryDelta(InventoryDelta.Kind.WITHDRAW, "minecraft:diamond", 3));
    }

    @Test
    void metadataOnlyMutationProducesWithdrawalAndDeposit() {
        assertThat(InventoryDelta.betweenAll("minecraft:diamond", 1,
                "minecraft:diamond", 1, new byte[]{1}, new byte[]{2}))
                .extracting(InventoryDelta::kind)
                .containsExactly(InventoryDelta.Kind.WITHDRAW, InventoryDelta.Kind.DEPOSIT);
    }

    @Test
    void countOnlyChangeWithNormalizedMetadataRemainsQuantityDelta() {
        assertThat(InventoryDelta.betweenAll("minecraft:diamond", 2,
                "minecraft:diamond", 3, new byte[]{9}, new byte[]{9}))
                .containsExactly(new InventoryDelta(InventoryDelta.Kind.DEPOSIT, "minecraft:diamond", 1));
    }

    @Test
    void countAndMetadataMutationPreservesBothStacks() {
        assertThat(InventoryDelta.betweenAll("minecraft:diamond", 2,
                "minecraft:diamond", 1, new byte[]{1}, new byte[]{2}))
                .extracting(InventoryDelta::amount)
                .containsExactly(2, 1);
    }

    @Test
    void representsIdentityReplacementAsWithdrawalThenDeposit() {
        assertThat(InventoryDelta.betweenAll("minecraft:diamond", 2, "minecraft:emerald", 1))
                .containsExactly(
                        new InventoryDelta(InventoryDelta.Kind.WITHDRAW, "minecraft:diamond", 2),
                        new InventoryDelta(InventoryDelta.Kind.DEPOSIT, "minecraft:emerald", 1));
    }

    @Test
    void ignoresUnchangedEmptySlots() {
        assertThat(InventoryDelta.betweenAll("minecraft:diamond", 2, "minecraft:diamond", 2)).isEmpty();
        assertThat(InventoryDelta.betweenAll(null, 0, null, 0)).isEmpty();
    }

    @Test
    void ignoresNonPositiveCountsAndClampsOverflow() {
        assertThat(InventoryDelta.between(null, -1, "minecraft:diamond", 0)).isEqualTo(InventoryDelta.NONE);
        assertThat(InventoryDelta.between("minecraft:diamond", Integer.MAX_VALUE, "minecraft:diamond", 0))
                .isEqualTo(new InventoryDelta(InventoryDelta.Kind.WITHDRAW, "minecraft:diamond", Integer.MAX_VALUE));
    }
}
