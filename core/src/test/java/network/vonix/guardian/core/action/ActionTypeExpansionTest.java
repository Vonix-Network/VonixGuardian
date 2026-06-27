package network.vonix.guardian.core.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive coverage of the v0.1.0 ActionType expansion (ids 15-39).
 *
 * <p>Locks in: id assignments, token strings, category + sign mapping, and
 * {@link ActionType#family(ActionType.Category)} memberships.</p>
 */
class ActionTypeExpansionTest {

    @Test
    void expansionIdsAreStable() {
        assertThat(ActionType.BURN.id()).isEqualTo(15);
        assertThat(ActionType.IGNITE.id()).isEqualTo(16);
        assertThat(ActionType.FADE.id()).isEqualTo(17);
        assertThat(ActionType.FORM.id()).isEqualTo(18);
        assertThat(ActionType.SPREAD.id()).isEqualTo(19);
        assertThat(ActionType.DISPENSE.id()).isEqualTo(20);
        assertThat(ActionType.PISTON_EXTEND.id()).isEqualTo(21);
        assertThat(ActionType.PISTON_RETRACT.id()).isEqualTo(22);
        assertThat(ActionType.BUCKET_EMPTY.id()).isEqualTo(23);
        assertThat(ActionType.BUCKET_FILL.id()).isEqualTo(24);
        assertThat(ActionType.LEAVES_DECAY.id()).isEqualTo(25);
        assertThat(ActionType.ENTITY_CHANGE_BLOCK.id()).isEqualTo(26);
        assertThat(ActionType.INVENTORY_DEPOSIT.id()).isEqualTo(27);
        assertThat(ActionType.INVENTORY_WITHDRAW.id()).isEqualTo(28);
        assertThat(ActionType.HOPPER_PUSH.id()).isEqualTo(29);
        assertThat(ActionType.HOPPER_PULL.id()).isEqualTo(30);
        assertThat(ActionType.ITEM_CRAFT.id()).isEqualTo(31);
        assertThat(ActionType.ENTITY_SPAWN.id()).isEqualTo(32);
        assertThat(ActionType.ENTITY_INTERACT.id()).isEqualTo(33);
        assertThat(ActionType.HANGING_PLACE.id()).isEqualTo(34);
        assertThat(ActionType.HANGING_BREAK.id()).isEqualTo(35);
        assertThat(ActionType.STRUCTURE_GROW.id()).isEqualTo(36);
        assertThat(ActionType.PORTAL_CREATE.id()).isEqualTo(37);
        assertThat(ActionType.CHUNK_POPULATE.id()).isEqualTo(38);
        assertThat(ActionType.CLICK.id()).isEqualTo(39);
    }

    @Test
    void expansionTokensMatchContract() {
        assertThat(ActionType.BURN.token()).isEqualTo("burn");
        assertThat(ActionType.IGNITE.token()).isEqualTo("ignite");
        assertThat(ActionType.FADE.token()).isEqualTo("fade");
        assertThat(ActionType.FORM.token()).isEqualTo("form");
        assertThat(ActionType.SPREAD.token()).isEqualTo("spread");
        assertThat(ActionType.DISPENSE.token()).isEqualTo("dispense");
        assertThat(ActionType.PISTON_EXTEND.token()).isEqualTo("+piston");
        assertThat(ActionType.PISTON_RETRACT.token()).isEqualTo("-piston");
        assertThat(ActionType.BUCKET_EMPTY.token()).isEqualTo("+bucket");
        assertThat(ActionType.BUCKET_FILL.token()).isEqualTo("-bucket");
        assertThat(ActionType.LEAVES_DECAY.token()).isEqualTo("decay");
        assertThat(ActionType.ENTITY_CHANGE_BLOCK.token()).isEqualTo("entityblock");
        assertThat(ActionType.INVENTORY_DEPOSIT.token()).isEqualTo("+inventory");
        assertThat(ActionType.INVENTORY_WITHDRAW.token()).isEqualTo("-inventory");
        assertThat(ActionType.HOPPER_PUSH.token()).isEqualTo("+hopper");
        assertThat(ActionType.HOPPER_PULL.token()).isEqualTo("-hopper");
        assertThat(ActionType.ITEM_CRAFT.token()).isEqualTo("craft");
        assertThat(ActionType.ENTITY_SPAWN.token()).isEqualTo("spawn");
        assertThat(ActionType.ENTITY_INTERACT.token()).isEqualTo("einteract");
        assertThat(ActionType.HANGING_PLACE.token()).isEqualTo("+hanging");
        assertThat(ActionType.HANGING_BREAK.token()).isEqualTo("-hanging");
        assertThat(ActionType.STRUCTURE_GROW.token()).isEqualTo("grow");
        assertThat(ActionType.PORTAL_CREATE.token()).isEqualTo("portal");
        assertThat(ActionType.CHUNK_POPULATE.token()).isEqualTo("populate");
        assertThat(ActionType.CLICK.token()).isEqualTo("click");
    }

    @Test
    void expansionByIdRoundTrips() {
        for (int id = 15; id <= 39; id++) {
            ActionType t = ActionType.byId(id);
            assertThat(t.id()).isEqualTo(id);
            assertThat(ActionType.byToken(t.token())).isSameAs(t);
        }
    }

    @Test
    void blockCategoryContainsAllVanillaBlockEvents() {
        Set<ActionType> blocks = ActionType.family(ActionType.Category.BLOCK);
        assertThat(blocks).contains(
                ActionType.BLOCK_PLACE, ActionType.BLOCK_BREAK,
                ActionType.BURN, ActionType.IGNITE,
                ActionType.FADE, ActionType.FORM, ActionType.SPREAD,
                ActionType.DISPENSE,
                ActionType.PISTON_EXTEND, ActionType.PISTON_RETRACT,
                ActionType.BUCKET_EMPTY, ActionType.BUCKET_FILL,
                ActionType.LEAVES_DECAY,
                ActionType.ENTITY_CHANGE_BLOCK);
    }

    @Test
    void containerCategoryContainsAllInventoryAndHopperEvents() {
        Set<ActionType> c = ActionType.family(ActionType.Category.CONTAINER);
        assertThat(c).containsExactlyInAnyOrder(
                ActionType.CONTAINER_DEPOSIT, ActionType.CONTAINER_WITHDRAW,
                ActionType.INVENTORY_DEPOSIT, ActionType.INVENTORY_WITHDRAW,
                ActionType.HOPPER_PUSH, ActionType.HOPPER_PULL);
    }

    @Test
    void itemCategoryContainsDropPickupCraft() {
        Set<ActionType> c = ActionType.family(ActionType.Category.ITEM);
        assertThat(c).containsExactlyInAnyOrder(
                ActionType.ITEM_DROP, ActionType.ITEM_PICKUP, ActionType.ITEM_CRAFT);
    }

    @Test
    void entityCategoryContainsKillSpawnInteractAndHanging() {
        Set<ActionType> c = ActionType.family(ActionType.Category.ENTITY);
        assertThat(c).containsExactlyInAnyOrder(
                ActionType.ENTITY_KILL, ActionType.ENTITY_SPAWN, ActionType.ENTITY_INTERACT,
                ActionType.HANGING_PLACE, ActionType.HANGING_BREAK);
    }

    @Test
    void worldCategoryContainsExplosionGrowPortalPopulate() {
        Set<ActionType> c = ActionType.family(ActionType.Category.WORLD);
        assertThat(c).containsExactlyInAnyOrder(
                ActionType.EXPLOSION, ActionType.STRUCTURE_GROW,
                ActionType.PORTAL_CREATE, ActionType.CHUNK_POPULATE);
    }

    @Test
    void messageCategoryUnchanged() {
        Set<ActionType> c = ActionType.family(ActionType.Category.MESSAGE);
        assertThat(c).containsExactlyInAnyOrder(
                ActionType.CHAT, ActionType.COMMAND, ActionType.SIGN);
    }

    @Test
    void sessionCategoryUnchanged() {
        Set<ActionType> c = ActionType.family(ActionType.Category.SESSION);
        assertThat(c).containsExactlyInAnyOrder(
                ActionType.SESSION_JOIN, ActionType.SESSION_LEAVE, ActionType.USERNAME_CHANGE);
    }

    @Test
    void interactCategoryHoldsOnlyClick() {
        Set<ActionType> c = ActionType.family(ActionType.Category.INTERACT);
        assertThat(c).containsExactly(ActionType.CLICK);
    }

    @Test
    void familyPartitionsAllValues() {
        EnumSet<ActionType> seen = EnumSet.noneOf(ActionType.class);
        for (ActionType.Category cat : ActionType.Category.values()) {
            for (ActionType t : ActionType.family(cat)) {
                assertThat(seen.add(t)).as("%s appears in multiple families", t).isTrue();
            }
        }
        assertThat(seen).hasSize(ActionType.values().length);
    }

    @Test
    void familySetsAreImmutable() {
        Set<ActionType> blocks = ActionType.family(ActionType.Category.BLOCK);
        assertThat(blocks).isNotEmpty();
        try {
            blocks.add(ActionType.CHAT);
            // If mutation succeeded, the contract is broken.
            org.junit.jupiter.api.Assertions.fail("family() set must be unmodifiable");
        } catch (UnsupportedOperationException ok) {
            // expected
        }
    }

    @ParameterizedTest
    @EnumSource(ActionType.class)
    void everyValueHasCategoryAndSign(ActionType t) {
        assertThat(t.category()).isNotNull();
        assertThat(t.sign()).isNotNull();
        assertThat(t.token()).isNotBlank();
    }

    @Test
    void entityChangeBlockIsNeutralBlockCategory() {
        // The modded griefing path: lives in BLOCK family so logBlocks() gates it,
        // but uses NEUTRAL sign because it isn't strictly a place or break.
        assertThat(ActionType.ENTITY_CHANGE_BLOCK.category()).isEqualTo(ActionType.Category.BLOCK);
        assertThat(ActionType.ENTITY_CHANGE_BLOCK.sign()).isEqualTo(ActionType.Sign.NEUTRAL);
    }

    @Test
    void signsAreCorrectForPairedTokens() {
        assertThat(ActionType.PISTON_EXTEND.sign()).isEqualTo(ActionType.Sign.PLACE);
        assertThat(ActionType.PISTON_RETRACT.sign()).isEqualTo(ActionType.Sign.BREAK);
        assertThat(ActionType.BUCKET_EMPTY.sign()).isEqualTo(ActionType.Sign.PLACE);
        assertThat(ActionType.BUCKET_FILL.sign()).isEqualTo(ActionType.Sign.BREAK);
        assertThat(ActionType.INVENTORY_DEPOSIT.sign()).isEqualTo(ActionType.Sign.PLACE);
        assertThat(ActionType.INVENTORY_WITHDRAW.sign()).isEqualTo(ActionType.Sign.BREAK);
        assertThat(ActionType.HOPPER_PUSH.sign()).isEqualTo(ActionType.Sign.PLACE);
        assertThat(ActionType.HOPPER_PULL.sign()).isEqualTo(ActionType.Sign.BREAK);
        assertThat(ActionType.HANGING_PLACE.sign()).isEqualTo(ActionType.Sign.PLACE);
        assertThat(ActionType.HANGING_BREAK.sign()).isEqualTo(ActionType.Sign.BREAK);
    }
}
