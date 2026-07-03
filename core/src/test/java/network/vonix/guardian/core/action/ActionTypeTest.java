package network.vonix.guardian.core.action;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionTypeTest {

    @Test
    void stableIdsAreFrozen() {
        // These ids are persisted in the DB; any change here is a breaking schema change.
        assertThat(ActionType.BLOCK_PLACE.id()).isEqualTo(1);
        assertThat(ActionType.BLOCK_BREAK.id()).isEqualTo(2);
        assertThat(ActionType.CONTAINER_DEPOSIT.id()).isEqualTo(3);
        assertThat(ActionType.CONTAINER_WITHDRAW.id()).isEqualTo(4);
        assertThat(ActionType.ITEM_DROP.id()).isEqualTo(5);
        assertThat(ActionType.ITEM_PICKUP.id()).isEqualTo(6);
        assertThat(ActionType.ENTITY_KILL.id()).isEqualTo(7);
        assertThat(ActionType.EXPLOSION.id()).isEqualTo(8);
        assertThat(ActionType.CHAT.id()).isEqualTo(9);
        assertThat(ActionType.COMMAND.id()).isEqualTo(10);
        assertThat(ActionType.SIGN.id()).isEqualTo(11);
        assertThat(ActionType.SESSION_JOIN.id()).isEqualTo(12);
        assertThat(ActionType.SESSION_LEAVE.id()).isEqualTo(13);
        assertThat(ActionType.USERNAME_CHANGE.id()).isEqualTo(14);
    }

    @Test
    void tokensMatchContract() {
        assertThat(ActionType.BLOCK_PLACE.token()).isEqualTo("+block");
        assertThat(ActionType.BLOCK_BREAK.token()).isEqualTo("-block");
        assertThat(ActionType.CONTAINER_DEPOSIT.token()).isEqualTo("+container");
        assertThat(ActionType.CONTAINER_WITHDRAW.token()).isEqualTo("-container");
        assertThat(ActionType.ITEM_DROP.token()).isEqualTo("-item");
        assertThat(ActionType.ITEM_PICKUP.token()).isEqualTo("+item");
        assertThat(ActionType.ENTITY_KILL.token()).isEqualTo("kill");
        assertThat(ActionType.EXPLOSION.token()).isEqualTo("explosion");
        assertThat(ActionType.CHAT.token()).isEqualTo("chat");
        assertThat(ActionType.COMMAND.token()).isEqualTo("command");
        assertThat(ActionType.SIGN.token()).isEqualTo("sign");
        assertThat(ActionType.SESSION_JOIN.token()).isEqualTo("+session");
        assertThat(ActionType.SESSION_LEAVE.token()).isEqualTo("-session");
        assertThat(ActionType.USERNAME_CHANGE.token()).isEqualTo("username");
    }

    @Test
    void byIdRoundTripsAllConstants() {
        for (ActionType t : ActionType.values()) {
            assertThat(ActionType.byId(t.id())).isSameAs(t);
        }
    }

    @Test
    void byTokenRoundTripsAllConstants() {
        for (ActionType t : ActionType.values()) {
            assertThat(ActionType.byToken(t.token())).isSameAs(t);
        }
    }

    @Test
    void byIdThrowsOnMiss() {
        assertThatThrownBy(() -> ActionType.byId(999))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("999");
        assertThatThrownBy(() -> ActionType.byId(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byTokenThrowsOnMissAndNull() {
        assertThatThrownBy(() -> ActionType.byToken("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nope");
        assertThatThrownBy(() -> ActionType.byToken(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsAreUniqueContiguousAndExpanded() {
        ActionType[] values = ActionType.values();
        assertThat(values).hasSize(40);
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < values.length; i++) {
            assertThat(values[i].id()).isEqualTo(i + 1);
            assertThat(ids.add(values[i].id())).as("id %d must be unique", values[i].id()).isTrue();
        }
    }

    @Test
    void allTokensAreUnique() {
        Set<String> tokens = new HashSet<>();
        for (ActionType t : ActionType.values()) {
            assertThat(tokens.add(t.token()))
                .as("token '%s' must be unique", t.token()).isTrue();
        }
    }

    @Test
    void allValuesHaveNonNullCategoryAndSign() {
        for (ActionType t : ActionType.values()) {
            assertThat(t.category()).as("%s.category()", t).isNotNull();
            assertThat(t.sign()).as("%s.sign()", t).isNotNull();
        }
    }

    @Test
    void familyReturnsAllValuesInCategory() {
        for (ActionType.Category c : ActionType.Category.values()) {
            Set<ActionType> fam = ActionType.family(c);
            assertThat(fam).as("family(%s) must be non-null", c).isNotNull();
            for (ActionType t : fam) {
                assertThat(t.category()).isEqualTo(c);
            }
            for (ActionType t : ActionType.values()) {
                if (t.category() == c) {
                    assertThat(fam).contains(t);
                }
            }
        }
    }

    @Test
    void familyThrowsOnNull() {
        assertThatThrownBy(() -> ActionType.family(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
