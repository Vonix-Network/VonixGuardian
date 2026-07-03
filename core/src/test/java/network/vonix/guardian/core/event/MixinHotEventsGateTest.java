package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W2 — regression test for the mixin-hot-events kill-switch folded from
 * {@code Guardian.submit} into {@link EventGate#shouldLog(Action)}.
 *
 * <p>Contract (reserved sourceTag prefixes established by
 * {@link network.vonix.guardian.core.diagnostics.MixinHotEventFilter}):
 * when {@code actions.mixinHotEvents=false}, {@code EventGate.shouldLog}
 * returns {@code false} for any action whose sourceTag starts with
 * {@code "#fire"}, {@code "#natural"}, or {@code "#dispenser"}, without
 * consulting the hook chain.</p>
 */
class MixinHotEventsGateTest {

    private static GuardianConfig.Actions cfgMixin(boolean mixinHotEvents) {
        return new GuardianConfig.Actions(
            // Log everything else so we isolate the mixin kill-switch:
            /* logBlocks               */ true,
            /* logContainers           */ true,
            /* logItems                */ true,
            /* logEntities             */ true,
            /* logExplosions           */ true,
            /* logChat                 */ true,
            /* logCommands             */ true,
            /* logSessions             */ true,
            /* logSigns                */ true,
            /* logInteractions         */ true,
            /* logWorldEvents          */ true,
            /* worldBlacklist          */ List.of(),
            /* blockBlacklist          */ List.of(),
            /* sourceBlacklist         */ List.of(),
            /* entityBlockChangeCoalesceWindowMs */ 60_000L,
            /* entityBlockChangeMaxTracked       */ 512,
            /* entityChangeAllowlist   */ List.of(),
            /* entityChangeLogAllEntities */ false,
            // W5-07 CP-parity defaults:
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
            /* mixinHotEvents          */ mixinHotEvents
        );
    }

    private static Action action(ActionType type, String sourceTag) {
        return new Action(-1L, System.currentTimeMillis(), type,
            UUID.randomUUID(), "tester", "minecraft:overworld",
            0, 64, 0, "minecraft:oak_log", null, 1, false, sourceTag);
    }

    @Test
    void mixinHotEventsDisabled_fireTag_returnsFalse() {
        EventGate gate = new EventGate(cfgMixin(false));
        assertThat(gate.shouldLog(action(ActionType.BURN,   "#fire:spread"))).isFalse();
        assertThat(gate.shouldLog(action(ActionType.IGNITE, "#fire"))).isFalse();
    }

    @Test
    void mixinHotEventsDisabled_naturalTag_returnsFalse() {
        EventGate gate = new EventGate(cfgMixin(false));
        assertThat(gate.shouldLog(action(ActionType.SPREAD, "#natural:grass"))).isFalse();
        assertThat(gate.shouldLog(action(ActionType.FADE,   "#natural:ice"))).isFalse();
        assertThat(gate.shouldLog(action(ActionType.LEAVES_DECAY, "#natural:leaves"))).isFalse();
    }

    @Test
    void mixinHotEventsDisabled_dispenserTag_returnsFalse() {
        EventGate gate = new EventGate(cfgMixin(false));
        assertThat(gate.shouldLog(action(ActionType.DISPENSE, "#dispenser"))).isFalse();
        assertThat(gate.shouldLog(action(ActionType.DISPENSE, "#dispenser:item"))).isFalse();
    }

    @Test
    void mixinHotEventsDisabled_nonMixinTag_stillLogs() {
        EventGate gate = new EventGate(cfgMixin(false));
        // Player-sourced burn (no "#fire" prefix) — should still be logged even
        // with the mixin kill-switch on. A "player" or explicit tag must pass.
        assertThat(gate.shouldLog(action(ActionType.BURN, "player:notch"))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.SPREAD, null))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.DISPENSE, "explosion:tnt"))).isTrue();
    }

    @Test
    void mixinHotEventsEnabled_allTagsPass() {
        EventGate gate = new EventGate(cfgMixin(true));
        assertThat(gate.shouldLog(action(ActionType.BURN, "#fire:spread"))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.SPREAD, "#natural:grass"))).isTrue();
        assertThat(gate.shouldLog(action(ActionType.DISPENSE, "#dispenser"))).isTrue();
    }
}
