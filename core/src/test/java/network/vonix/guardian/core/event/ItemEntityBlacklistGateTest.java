/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.PerWorldConfigStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Verification of actions.itemBlacklist and actions.entityBlacklist
 * per OWNER-REQUIREMENT-ITEM-ENTITY-ID-FILTER-R1.
 */
class ItemEntityBlacklistGateTest {

    private static GuardianConfig.Actions makeActions(List<String> itemBlacklist, List<String> entityBlacklist) {
        return new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of(), List.of(),
                itemBlacklist, entityBlacklist,
                500L, 8192, List.of(), false,
                true, true, true, true, true, true, false, false,
                true, true, false, true, false, true
        );
    }

    private static Action createAction(ActionType type, String targetId) {
        return new Action(
                -1L, System.currentTimeMillis(), type,
                UUID.randomUUID(), "Tester",
                "minecraft:overworld", 10, 64, 10,
                targetId, null, 1, false, null
        );
    }

    // =========================================================================
    // Item Blacklist Tests
    // =========================================================================

    @Test
    @DisplayName("Exact item match gates ITEM_DROP, ITEM_PICKUP, and ITEM_CRAFT")
    void exactItemMatchGatesAllItemActionTypes() {
        GuardianConfig.Actions cfg = makeActions(List.of("minecraft:diamond", "minecraft:cobblestone"), List.of());
        EventGate gate = new EventGate(cfg);

        for (ActionType type : List.of(ActionType.ITEM_DROP, ActionType.ITEM_PICKUP, ActionType.ITEM_CRAFT)) {
            Action blacklisted = createAction(type, "minecraft:diamond");
            assertThat(gate.shouldLog(blacklisted))
                    .as("Action %s with blacklisted item should be dropped", type)
                    .isFalse();

            Action allowed = createAction(type, "minecraft:iron_ingot");
            assertThat(gate.shouldLog(allowed))
                    .as("Action %s with unlisted item should be allowed", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Item blacklist gates container and hopper actions that carry item IDs")
    void exactItemMatchGatesAllItemBearingContainerTypes() {
        GuardianConfig.Actions cfg = makeActions(List.of("minecraft:diamond"), List.of());
        EventGate gate = new EventGate(cfg);

        for (ActionType type : List.of(
                ActionType.CONTAINER_DEPOSIT,
                ActionType.CONTAINER_WITHDRAW,
                ActionType.INVENTORY_DEPOSIT,
                ActionType.INVENTORY_WITHDRAW,
                ActionType.HOPPER_PUSH,
                ActionType.HOPPER_PULL)) {
            Action blacklisted = createAction(type, "minecraft:diamond");
            assertThat(gate.shouldLog(blacklisted))
                    .as("Action %s with blacklisted item should be dropped", type)
                    .isFalse();

            Action allowed = createAction(type, "minecraft:iron_ingot");
            assertThat(gate.shouldLog(allowed))
                    .as("Action %s with unlisted item should be allowed", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Item blacklist does not broaden into prefix or substring matches")
    void itemBlacklistExactNonBroadening() {
        GuardianConfig.Actions cfg = makeActions(List.of("minecraft:dia"), List.of());
        EventGate gate = new EventGate(cfg);

        // \"minecraft:diamond\" must NOT be matched by \"minecraft:dia\"
        Action diamond = createAction(ActionType.ITEM_DROP, "minecraft:diamond");
        assertThat(gate.shouldLog(diamond)).isTrue();

        Action dia = createAction(ActionType.ITEM_DROP, "minecraft:dia");
        assertThat(gate.shouldLog(dia)).isFalse();
    }

    @Test
    @DisplayName("Item blacklist is case-insensitive and trims whitespace")
    void itemBlacklistCaseAndWhitespaceNormalization() {
        GuardianConfig.Actions cfg = makeActions(List.of("  Minecraft:DIAMOND  "), List.of());
        EventGate gate = new EventGate(cfg);

        Action diamond = createAction(ActionType.ITEM_DROP, "minecraft:diamond");
        assertThat(gate.shouldLog(diamond)).isFalse();

        Action diamondCaps = createAction(ActionType.ITEM_DROP, "  MINECRAFT:DIAMOND  ");
        assertThat(gate.shouldLog(diamondCaps)).isFalse();
    }

    @Test
    @DisplayName("Item blacklist does not affect non-item categories with same targetId")
    void itemBlacklistDoesNotGateBlockCategories() {
        GuardianConfig.Actions cfg = makeActions(List.of("minecraft:diamond_block"), List.of());
        EventGate gate = new EventGate(cfg);

        Action blockBreak = createAction(ActionType.BLOCK_BREAK, "minecraft:diamond_block");
        assertThat(gate.shouldLog(blockBreak)).isTrue();
    }

    // =========================================================================
    // Entity Blacklist Tests
    // =========================================================================

    @Test
    @DisplayName("Entity blacklist gates ENTITY_KILL, ENTITY_SPAWN, ENTITY_INTERACT, HANGING_PLACE, HANGING_BREAK")
    void exactEntityMatchGatesAllEntityActionTypes() {
        GuardianConfig.Actions cfg = makeActions(List.of(), List.of("minecraft:armor_stand", "minecraft:experience_orb"));
        EventGate gate = new EventGate(cfg);

        List<ActionType> entityTypes = List.of(
                ActionType.ENTITY_KILL,
                ActionType.ENTITY_SPAWN,
                ActionType.ENTITY_INTERACT,
                ActionType.HANGING_PLACE,
                ActionType.HANGING_BREAK
        );

        for (ActionType type : entityTypes) {
            Action blacklisted = createAction(type, "#mob:minecraft:armor_stand");
            assertThat(gate.shouldLog(blacklisted))
                    .as("Action %s with blacklisted entity should be dropped", type)
                    .isFalse();

            Action allowed = createAction(type, "#mob:minecraft:cow");
            assertThat(gate.shouldLog(allowed))
                    .as("Action %s with unlisted entity should be allowed", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Entity sentinel normalization matches both #mob: prefix and bare ID symmetrically")
    void entitySentinelNormalizationSymmetric() {
        // Case 1: Config has bare ID, target has #mob:
        GuardianConfig.Actions cfg1 = makeActions(List.of(), List.of("minecraft:zombie"));
        EventGate gate1 = new EventGate(cfg1);
        assertThat(gate1.shouldLog(createAction(ActionType.ENTITY_KILL, "#mob:minecraft:zombie"))).isFalse();
        assertThat(gate1.shouldLog(createAction(ActionType.ENTITY_KILL, "minecraft:zombie"))).isFalse();

        // Case 2: Config has #mob: prefix, target has bare ID
        GuardianConfig.Actions cfg2 = makeActions(List.of(), List.of("#mob:minecraft:skeleton"));
        EventGate gate2 = new EventGate(cfg2);
        assertThat(gate2.shouldLog(createAction(ActionType.ENTITY_KILL, "minecraft:skeleton"))).isFalse();
        assertThat(gate2.shouldLog(createAction(ActionType.ENTITY_KILL, "#mob:minecraft:skeleton"))).isFalse();
    }

    @Test
    @DisplayName("Entity blacklist does not broaden into prefix or substring matches")
    void entityBlacklistExactNonBroadening() {
        GuardianConfig.Actions cfg = makeActions(List.of(), List.of("minecraft:zomb"));
        EventGate gate = new EventGate(cfg);

        Action zombie = createAction(ActionType.ENTITY_KILL, "#mob:minecraft:zombie");
        assertThat(gate.shouldLog(zombie)).isTrue();
    }

    // =========================================================================
    // Malformed ID Behavior
    // =========================================================================

    @Test
    @DisplayName("Malformed, blank, and null IDs do not throw and do not match valid items/entities")
    void malformedIdsDoNotThrowOrBroaden() {
        assertThatNoException().isThrownBy(() -> {
            GuardianConfig.Actions cfg = makeActions(
                    List.of("", "   ", "\t\n"),
                    List.of("", "   ", "#mob:", "#mob:  ")
            );
            EventGate gate = new EventGate(cfg);

            // Valid actions must NOT be blocked by empty or whitespace blacklist entries
            assertThat(gate.shouldLog(createAction(ActionType.ITEM_DROP, "minecraft:diamond"))).isTrue();
            assertThat(gate.shouldLog(createAction(ActionType.ENTITY_KILL, "#mob:minecraft:zombie"))).isTrue();

            // Actions with null or empty targetId must not crash
            Action nullTarget = createAction(ActionType.ITEM_DROP, null);
            assertThat(gate.shouldLog(nullTarget)).isTrue();

            Action emptyTarget = createAction(ActionType.ITEM_DROP, "");
            assertThat(gate.shouldLog(emptyTarget)).isTrue();
        });
    }

    // =========================================================================
    // Probe Methods: shouldLogItem and shouldLogEntity
    // =========================================================================

    @Test
    @DisplayName("shouldLogItem and shouldLogEntity report gating decisions accurately before Action construction")
    void shouldLogProbesAccurate() {
        GuardianConfig.Actions cfg = makeActions(List.of("minecraft:sand"), List.of("minecraft:creeper"));
        EventGate gate = new EventGate(cfg);

        assertThat(gate.shouldLogItem("minecraft:overworld", "minecraft:sand")).isFalse();
        assertThat(gate.shouldLogItem("minecraft:overworld", "minecraft:gravel")).isTrue();

        assertThat(gate.shouldLogEntity("minecraft:overworld", "minecraft:creeper")).isFalse();
        assertThat(gate.shouldLogEntity("minecraft:overworld", "#mob:minecraft:creeper")).isFalse();
        assertThat(gate.shouldLogEntity("minecraft:overworld", "minecraft:cow")).isTrue();
    }

    // =========================================================================
    // Per-World Override & Config Round-Trip
    // =========================================================================

    @Test
    @DisplayName("Per-world override respects item and entity blacklists")
    void perWorldOverrideGating(@TempDir Path tmp) throws Exception {
        GuardianConfig.Actions root = makeActions(List.of(), List.of());
        Files.writeString(tmp.resolve("minecraft__the_end.json"),
                "{\n" +
                "  \"itemBlacklist\": [\"minecraft:elytra\"],\n" +
                "  \"entityBlacklist\": [\"minecraft:enderman\"]\n" +
                "}\n");

        PerWorldConfigStore store = new PerWorldConfigStore(root);
        store.reload(tmp);
        PerWorldEventHook hook = new PerWorldEventHook(store, root);

        // In the_end: elytra and enderman are blacklisted
        Action endElytra = new Action(-1L, System.currentTimeMillis(), ActionType.ITEM_DROP,
                UUID.randomUUID(), "P", "minecraft:the_end", 0, 0, 0, "minecraft:elytra", null, 1, false, null);
        assertThat(hook.test(endElytra)).isEqualTo(EventHook.Decision.DENY);

        Action endEnderman = new Action(-1L, System.currentTimeMillis(), ActionType.ENTITY_KILL,
                UUID.randomUUID(), "P", "minecraft:the_end", 0, 0, 0, "#mob:minecraft:enderman", null, 1, false, null);
        assertThat(hook.test(endEnderman)).isEqualTo(EventHook.Decision.DENY);

        // In overworld: not blacklisted
        Action owElytra = new Action(-1L, System.currentTimeMillis(), ActionType.ITEM_DROP,
                UUID.randomUUID(), "P", "minecraft:overworld", 0, 0, 0, "minecraft:elytra", null, 1, false, null);
        assertThat(hook.test(owElytra)).isEqualTo(EventHook.Decision.PASS);

        Action endContainer = new Action(-1L, System.currentTimeMillis(), ActionType.HOPPER_PUSH,
                UUID.randomUUID(), "P", "minecraft:the_end", 0, 0, 0, "minecraft:elytra", null, 1, false, null);
        assertThat(hook.test(endContainer)).isEqualTo(EventHook.Decision.DENY);

        // Reload must replace the per-world snapshot rather than retaining stale IDs.
        Files.writeString(tmp.resolve("minecraft__the_end.json"),
                "{\n" +
                "  \"itemBlacklist\": [\"minecraft:shulker_box\"],\n" +
                "  \"entityBlacklist\": [\"minecraft:shulker\"]\n" +
                "}\n");
        store.reload(tmp);
        Action reloadedAllowed = new Action(-1L, System.currentTimeMillis(), ActionType.ITEM_DROP,
                UUID.randomUUID(), "P", "minecraft:the_end", 0, 0, 0, "minecraft:elytra", null, 1, false, null);
        assertThat(hook.test(reloadedAllowed)).isEqualTo(EventHook.Decision.PASS);
        Action reloadedDenied = new Action(-1L, System.currentTimeMillis(), ActionType.ITEM_DROP,
                UUID.randomUUID(), "P", "minecraft:the_end", 0, 0, 0, "minecraft:shulker_box", null, 1, false, null);
        assertThat(hook.test(reloadedDenied)).isEqualTo(EventHook.Decision.DENY);
    }

    @Test
    @DisplayName("Per-world override rejects malformed blacklist element types without aborting reload")
    void malformedPerWorldBlacklistEntryIsSkipped(@TempDir Path tmp) throws Exception {
        GuardianConfig.Actions root = makeActions(List.of(), List.of());
        Files.writeString(tmp.resolve("minecraft__the_end.json"),
                "{\"itemBlacklist\":[{}],\"entityBlacklist\":[true]}\n");

        PerWorldConfigStore store = new PerWorldConfigStore(root);
        assertThatNoException().isThrownBy(() -> store.reload(tmp));
        assertThat(store.overriddenWorlds()).isEmpty();
    }

    @Test
    @DisplayName("Malformed scalar override fields skip only that file and still publish a replacement snapshot")
    void malformedPerWorldScalarEntryIsSkipped(@TempDir Path tmp) throws Exception {
        GuardianConfig.Actions root = makeActions(List.of(), List.of());
        Files.writeString(tmp.resolve("minecraft__the_end.json"),
                "{\"logItems\":{},\"entityBlockChangeCoalesceWindowMs\":{}}\n");
        Files.writeString(tmp.resolve("minecraft__overworld.json"),
                "{\"itemBlacklist\":[\"minecraft:diamond\"]}\n");

        PerWorldConfigStore store = new PerWorldConfigStore(root);
        assertThatNoException().isThrownBy(() -> store.reload(tmp));
        assertThat(store.overriddenWorlds()).containsExactly("minecraft:overworld");
        assertThat(store.overrideFor("minecraft:overworld").itemBlacklist())
                .containsExactly("minecraft:diamond");
        assertThat(store.overrideFor("minecraft:the_end")).isNull();
    }

    @Test
    @DisplayName("ConfigLoader forward-compat backfills empty lists when missing from YAML/JSON")
    void configLoaderForwardCompat(@TempDir Path tmp) throws Exception {
        GuardianConfig defaults = GuardianConfig.defaults();
        assertThat(defaults.actions().itemBlacklist()).isEmpty();
        assertThat(defaults.actions().entityBlacklist()).isEmpty();

        GuardianConfig configured = new GuardianConfig(
                defaults.database(), defaults.queue(), defaults.logFile(),
                defaults.actions().withItemBlacklist(List.of("minecraft:diamond"))
                        .withEntityBlacklist(List.of("#mob:minecraft:zombie")),
                defaults.permissions(), defaults.lookup(), defaults.privacy(), defaults.purge(),
                defaults.storage(), defaults.rollback(), defaults.theme(), defaults.language());
        Path config = tmp.resolve("config.json");
        ConfigLoader.save(config, configured);
        GuardianConfig loaded = ConfigLoader.load(config);
        assertThat(loaded.actions().itemBlacklist()).containsExactly("minecraft:diamond");
        assertThat(loaded.actions().entityBlacklist()).containsExactly("#mob:minecraft:zombie");
    }
}
