package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActionType#CLICK} must be reachable end to end, not just declared.
 *
 * <h3>Why this test exists</h3>
 * <p>A review of this tree recorded CLICK as "declared and never produced", and
 * assigned wiring it as outstanding work. That premise is stale: every one of the
 * thirteen shipping cell event classes calls {@code submitClick}, and
 * {@code Guardian.submitClick} seeds {@code ActionType.CLICK} — so the type is
 * produced today on 1.18.2/1.19.2/1.20.1/1.21.1/26.1.2/26.2/26.3 for both loaders
 * where they exist.
 *
 * <p>What was genuinely unproven is that a CLICK row can survive the admission
 * gate: a producer that fires and is then dropped by {@link EventGate} would
 * still be indistinguishable from an unproduced type in the audit log. These
 * tests pin that half — CLICK is admitted under the shipped configuration, and
 * it is dropped exactly when {@code actions.logInteractions} says so, with no
 * other toggle able to suppress it.
 */
class ActionTypeClickGateTest {

    private static GuardianConfig.Actions actions(boolean logInteractions) {
        return new GuardianConfig.Actions(
            /* logBlocks               */ true,
            /* logContainers           */ true,
            /* logItems                */ true,
            /* logEntities             */ true,
            /* logExplosions           */ true,
            /* logChat                 */ true,
            /* logCommands             */ true,
            /* logSessions             */ true,
            /* logSigns                */ true,
            /* logInteractions         */ logInteractions,
            /* logWorldEvents          */ true,
            /* worldBlacklist          */ List.of(),
            /* blockBlacklist          */ List.of(),
            /* sourceBlacklist         */ List.of(),
            /* itemBlacklist           */ List.of(),
            /* entityBlacklist         */ List.of(),
            /* entityBlockChangeCoalesceWindowMs */ 500L,
            /* entityBlockChangeMaxTracked       */ 8192,
            /* entityChangeAllowlist   */ List.of(),
            /* entityChangeLogAllEntities */ false,
            // W5-07 CP-parity defaults, matching GuardianConfig.defaults():
            /* logNaturalBreaks        */ true,
            /* logTreeGrowth           */ true,
            /* logMushroomGrowth       */ true,
            /* logVineGrowth           */ true,
            /* logSculkSpread          */ true,
            /* logPortals              */ true,
            /* logWaterFlow            */ false,
            /* logLavaFlow             */ false,
            /* logFireExtinguish       */ true,
            /* logCampfireStart        */ true,
            /* logHopperMetaFilter     */ false,
            /* logDuplicateSuppression */ true,
            /* logCancelledChat        */ false,
            /* mixinHotEvents          */ true
        );
    }

    private static Action click(String blockId) {
        return new Action(-1L, System.currentTimeMillis(), ActionType.CLICK,
            UUID.nameUUIDFromBytes("clicker".getBytes()), "Clicker", "minecraft:overworld",
            10, 64, -3, blockId, null, 1, false, null);
    }

    @Test
    void clickIsAdmittedUnderTheShippedDefaultConfiguration() {
        // defaults() has logInteractions = true; use it rather than a hand-built
        // block so this test tracks the shipped config, not a local guess.
        GuardianConfig.Actions shipped = GuardianConfig.defaults().actions();
        assertThat(shipped.logInteractions())
            .as("CLICK can only ever be persisted when interactions are logged")
            .isTrue();

        EventGate gate = new EventGate(shipped);
        assertThat(gate.shouldLog(click("minecraft:oak_door")))
            .as("a right-click on a door must reach the write queue, not be dropped")
            .isTrue();
        assertThat(gate.shouldLog(click("minecraft:lever"))).isTrue();
        assertThat(gate.shouldLog(click("minecraft:stone_button"))).isTrue();
    }

    @Test
    void clickIsDroppedExactlyWhenInteractionsAreDisabled() {
        EventGate off = new EventGate(actions(false));
        assertThat(off.shouldLog(click("minecraft:oak_door"))).isFalse();

        EventGate on = new EventGate(actions(true));
        assertThat(on.shouldLog(click("minecraft:oak_door"))).isTrue();
    }

    @Test
    void noOtherToggleCanSuppressClick() {
        // logInteractions is the only switch CLICK answers to. Turning off every
        // other family must not affect it, or an operator who disabled e.g. block
        // logging would silently lose interaction audit too.
        GuardianConfig.Actions onlyInteractions = new GuardianConfig.Actions(
            /* logBlocks               */ false,
            /* logContainers           */ false,
            /* logItems                */ false,
            /* logEntities             */ false,
            /* logExplosions           */ false,
            /* logChat                 */ false,
            /* logCommands             */ false,
            /* logSessions             */ false,
            /* logSigns                */ false,
            /* logInteractions         */ true,
            /* logWorldEvents          */ false,
            /* worldBlacklist          */ List.of(),
            /* blockBlacklist          */ List.of("minecraft:air"),
            /* sourceBlacklist         */ List.of(),
            /* itemBlacklist           */ List.of(),
            /* entityBlacklist         */ List.of(),
            /* entityBlockChangeCoalesceWindowMs */ 500L,
            /* entityBlockChangeMaxTracked       */ 8192,
            /* entityChangeAllowlist   */ List.of(),
            /* entityChangeLogAllEntities */ false,
            /* logNaturalBreaks        */ false,
            /* logTreeGrowth           */ false,
            /* logMushroomGrowth       */ false,
            /* logVineGrowth           */ false,
            /* logSculkSpread          */ false,
            /* logPortals              */ false,
            /* logWaterFlow            */ false,
            /* logLavaFlow             */ false,
            /* logFireExtinguish       */ false,
            /* logCampfireStart        */ false,
            /* logHopperMetaFilter     */ false,
            /* logDuplicateSuppression */ false,
            /* logCancelledChat        */ false,
            /* mixinHotEvents          */ false
        );
        EventGate gate = new EventGate(onlyInteractions);
        assertThat(gate.shouldLog(click("minecraft:oak_door"))).isTrue();
    }

    @Test
    void clickCarriesTheInteractFamilyAndItsOwnPermissionNode() {
        assertThat(ActionType.CLICK.category()).isEqualTo(ActionType.Category.INTERACT);
        assertThat(ActionType.CLICK.token()).isEqualTo("click");
        // The id is a stable database key — renumbering it would silently
        // reinterpret every historical row.
        assertThat(ActionType.CLICK.id()).isEqualTo(39);
        assertThat(ActionType.CLICK).isSameAs(ActionType.byId(39));
        assertThat(ActionType.CLICK).isSameAs(ActionType.byToken("click"));
    }
}
