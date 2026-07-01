package network.vonix.guardian.core.perms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

/**
 * W3-B7: verifies child-node metadata and {@link PermissionNode#childFor} dispatch.
 */
class PermissionNodeChildTest {

    @Test
    void everyNodeStringIsUnique() {
        Set<String> seen = new HashSet<>();
        for (PermissionNode n : PermissionNode.values()) {
            assertThat(seen.add(n.node()))
                    .as("duplicate node string: %s", n.node())
                    .isTrue();
        }
    }

    @Test
    void opLevelsAreInVanillaRange() {
        for (PermissionNode n : PermissionNode.values()) {
            assertThat(n.defaultOpLevel())
                    .as("opLevel for %s", n)
                    .isBetween(0, 4);
        }
    }

    @Test
    void lookupFamilyChildrenAreAllOpLevel2() {
        assertThat(PermissionNode.LOOKUP_BLOCK.defaultOpLevel()).isEqualTo(2);
        assertThat(PermissionNode.LOOKUP_CONTAINER.defaultOpLevel()).isEqualTo(2);
        assertThat(PermissionNode.LOOKUP_ITEM.defaultOpLevel()).isEqualTo(2);
        assertThat(PermissionNode.LOOKUP_KILL.defaultOpLevel()).isEqualTo(2);
        assertThat(PermissionNode.LOOKUP_SESSION.defaultOpLevel()).isEqualTo(2);
        assertThat(PermissionNode.LOOKUP_SIGN.defaultOpLevel()).isEqualTo(2);
    }

    @Test
    void rollbackAndRestoreChildrenAreOpLevel3() {
        for (PermissionNode n : Arrays.asList(
                PermissionNode.ROLLBACK_BLOCK,
                PermissionNode.ROLLBACK_CONTAINER,
                PermissionNode.ROLLBACK_ITEM,
                PermissionNode.RESTORE_BLOCK,
                PermissionNode.RESTORE_CONTAINER,
                PermissionNode.RESTORE_ITEM)) {
            assertThat(n.defaultOpLevel()).as(n.name()).isEqualTo(3);
        }
    }

    @Test
    void childFor_lookupFamily_dispatchesByCategory() {
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.BLOCK))
                .isEqualTo(PermissionNode.LOOKUP_BLOCK);
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.CONTAINER))
                .isEqualTo(PermissionNode.LOOKUP_CONTAINER);
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.ITEM))
                .isEqualTo(PermissionNode.LOOKUP_ITEM);
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.ENTITY))
                .isEqualTo(PermissionNode.LOOKUP_KILL);
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.SESSION))
                .isEqualTo(PermissionNode.LOOKUP_SESSION);
        // MESSAGE ambiguous → fall-open to family
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.MESSAGE))
                .isEqualTo(PermissionNode.LOOKUP);
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.WORLD))
                .isEqualTo(PermissionNode.LOOKUP);
        assertThat(PermissionNode.childFor(PermissionNode.LOOKUP, ActionType.Category.INTERACT))
                .isEqualTo(PermissionNode.LOOKUP);
    }

    @Test
    void childFor_rollbackFamily_onlyBlockContainerItem() {
        assertThat(PermissionNode.childFor(PermissionNode.ROLLBACK, ActionType.Category.BLOCK))
                .isEqualTo(PermissionNode.ROLLBACK_BLOCK);
        assertThat(PermissionNode.childFor(PermissionNode.ROLLBACK, ActionType.Category.CONTAINER))
                .isEqualTo(PermissionNode.ROLLBACK_CONTAINER);
        assertThat(PermissionNode.childFor(PermissionNode.ROLLBACK, ActionType.Category.ITEM))
                .isEqualTo(PermissionNode.ROLLBACK_ITEM);
        // Other categories fall-open
        for (ActionType.Category c : Arrays.asList(
                ActionType.Category.ENTITY,
                ActionType.Category.MESSAGE,
                ActionType.Category.SESSION,
                ActionType.Category.WORLD,
                ActionType.Category.INTERACT)) {
            assertThat(PermissionNode.childFor(PermissionNode.ROLLBACK, c))
                    .as("rollback fall-open for %s", c)
                    .isEqualTo(PermissionNode.ROLLBACK);
        }
    }

    @Test
    void childFor_restoreFamily_onlyBlockContainerItem() {
        assertThat(PermissionNode.childFor(PermissionNode.RESTORE, ActionType.Category.BLOCK))
                .isEqualTo(PermissionNode.RESTORE_BLOCK);
        assertThat(PermissionNode.childFor(PermissionNode.RESTORE, ActionType.Category.CONTAINER))
                .isEqualTo(PermissionNode.RESTORE_CONTAINER);
        assertThat(PermissionNode.childFor(PermissionNode.RESTORE, ActionType.Category.ITEM))
                .isEqualTo(PermissionNode.RESTORE_ITEM);
        assertThat(PermissionNode.childFor(PermissionNode.RESTORE, ActionType.Category.ENTITY))
                .isEqualTo(PermissionNode.RESTORE);
    }

    @Test
    void childFor_nonFamilyNode_returnsSelf() {
        assertThat(PermissionNode.childFor(PermissionNode.STATUS, ActionType.Category.BLOCK))
                .isEqualTo(PermissionNode.STATUS);
    }

    @Test
    void childForAction_messagesDispatchToChatCommandSign() {
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.CHAT))
                .isEqualTo(PermissionNode.LOOKUP_CHAT);
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.COMMAND))
                .isEqualTo(PermissionNode.LOOKUP_COMMAND);
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.SIGN))
                .isEqualTo(PermissionNode.LOOKUP_SIGN);
        assertThat(PermissionNode.childForAction(PermissionNode.LOOKUP, ActionType.BLOCK_PLACE))
                .isEqualTo(PermissionNode.LOOKUP_BLOCK);
        // Non-lookup families: messages fall-open since ROLLBACK/RESTORE don't act on messages
        assertThat(PermissionNode.childForAction(PermissionNode.ROLLBACK, ActionType.CHAT))
                .isEqualTo(PermissionNode.ROLLBACK);
    }

    @Test
    void childFor_rejectsNullArgs() {
        assertThatThrownBy(() -> PermissionNode.childFor(null, ActionType.Category.BLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermissionNode.childFor(PermissionNode.LOOKUP, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
