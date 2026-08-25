package network.vonix.guardian.core.perms;

import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionNodeInventoryTest {

    @Test
    void inventoryActionsUseDedicatedLookupNode() {
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.INVENTORY_DEPOSIT))
                .isEqualTo(PermissionNode.LOOKUP_INVENTORY);
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.INVENTORY_WITHDRAW))
                .isEqualTo(PermissionNode.LOOKUP_INVENTORY);
        assertThat(PermissionNode.LOOKUP_INVENTORY.node())
                .isEqualTo("vonixguardian.lookup.inventory");
    }

    @Test
    void inventoryActionsUseDedicatedMutationNodes() {
        assertThat(PermissionNode.childForAction(PermissionNode.ROLLBACK, ActionType.INVENTORY_DEPOSIT))
                .isEqualTo(PermissionNode.ROLLBACK_INVENTORY);
        assertThat(PermissionNode.childForAction(PermissionNode.RESTORE, ActionType.INVENTORY_WITHDRAW))
                .isEqualTo(PermissionNode.RESTORE_INVENTORY);
    }

    @Test
    void lookupClickAndUsernameUseDedicatedNodes() {
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.CLICK))
                .isEqualTo(PermissionNode.LOOKUP_CLICK);
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.USERNAME_CHANGE))
                .isEqualTo(PermissionNode.LOOKUP_USERNAME);
    }

    @Test
    void unrelatedContainerActionsRemainContainerScoped() {
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.HOPPER_PUSH))
                .isEqualTo(PermissionNode.LOOKUP_CONTAINER);
    }
}
