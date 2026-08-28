package network.vonix.guardian.core.boundary;

import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins loader-specific ActionType capture without requiring identical mixins.
 *
 * <p>Fabric 1.21.1 uses mixins for several vanilla surfaces that NeoForge
 * captures on the event bus (explosions, pistons, signs, item toss/pickup,
 * crafting, living-destroy). NeoForge additionally registers
 * {@code MilkBucketItemMixin}. Those mechanism differences are documented, not
 * copied. This test fails only when a requested cell submits an ActionType that
 * another requested cell never submits, except for types that have no producer
 * on any requested cell.
 */
class ActionTypeCoverageStructuralTest {

    private static final Pattern SUBMIT = Pattern.compile("\\.(submit[A-Z][A-Za-z0-9]*)\\s*\\(");

    private static final Map<String, Set<ActionType>> SUBMIT_TO_TYPES = Map.ofEntries(
            Map.entry("submitBlockBreak", EnumSet.of(ActionType.BLOCK_BREAK)),
            Map.entry("submitBlockPlace", EnumSet.of(ActionType.BLOCK_PLACE)),
            Map.entry("submitContainerChange", EnumSet.of(ActionType.CONTAINER_DEPOSIT, ActionType.CONTAINER_WITHDRAW)),
            Map.entry("submitItemDrop", EnumSet.of(ActionType.ITEM_DROP)),
            Map.entry("submitItemPickup", EnumSet.of(ActionType.ITEM_PICKUP)),
            Map.entry("submitEntityKill", EnumSet.of(ActionType.ENTITY_KILL)),
            Map.entry("submitExplosion", EnumSet.of(ActionType.EXPLOSION)),
            Map.entry("submitChat", EnumSet.of(ActionType.CHAT)),
            Map.entry("submitCommand", EnumSet.of(ActionType.COMMAND)),
            Map.entry("submitSign", EnumSet.of(ActionType.SIGN)),
            Map.entry("submitSessionJoin", EnumSet.of(ActionType.SESSION_JOIN)),
            Map.entry("submitSessionLeave", EnumSet.of(ActionType.SESSION_LEAVE)),
            Map.entry("submitUsernameChange", EnumSet.of(ActionType.USERNAME_CHANGE)),
            Map.entry("submitBurn", EnumSet.of(ActionType.BURN)),
            Map.entry("submitIgnite", EnumSet.of(ActionType.IGNITE)),
            Map.entry("submitFade", EnumSet.of(ActionType.FADE)),
            Map.entry("submitForm", EnumSet.of(ActionType.FORM)),
            Map.entry("submitSpread", EnumSet.of(ActionType.SPREAD)),
            Map.entry("submitDispense", EnumSet.of(ActionType.DISPENSE)),
            Map.entry("submitPistonExtend", EnumSet.of(ActionType.PISTON_EXTEND)),
            Map.entry("submitPistonRetract", EnumSet.of(ActionType.PISTON_RETRACT)),
            Map.entry("submitBucketEmpty", EnumSet.of(ActionType.BUCKET_EMPTY)),
            Map.entry("submitBucketFill", EnumSet.of(ActionType.BUCKET_FILL)),
            Map.entry("submitFluidFlow", EnumSet.of(ActionType.FLUID_FLOW)),
            Map.entry("submitLeavesDecay", EnumSet.of(ActionType.LEAVES_DECAY)),
            Map.entry("submitEntityChangeBlock", EnumSet.of(ActionType.ENTITY_CHANGE_BLOCK)),
            Map.entry("submitInventoryDeposit", EnumSet.of(ActionType.INVENTORY_DEPOSIT)),
            Map.entry("submitInventoryWithdraw", EnumSet.of(ActionType.INVENTORY_WITHDRAW)),
            Map.entry("submitInventoryReplacement", EnumSet.of(ActionType.INVENTORY_DEPOSIT, ActionType.INVENTORY_WITHDRAW)),
            Map.entry("submitHopperPush", EnumSet.of(ActionType.HOPPER_PUSH)),
            Map.entry("submitHopperPull", EnumSet.of(ActionType.HOPPER_PULL)),
            Map.entry("submitItemCraft", EnumSet.of(ActionType.ITEM_CRAFT)),
            Map.entry("submitEntitySpawn", EnumSet.of(ActionType.ENTITY_SPAWN)),
            Map.entry("submitEntityInteract", EnumSet.of(ActionType.ENTITY_INTERACT)),
            Map.entry("submitHangingPlace", EnumSet.of(ActionType.HANGING_PLACE)),
            Map.entry("submitHangingBreak", EnumSet.of(ActionType.HANGING_BREAK)),
            Map.entry("submitStructureGrow", EnumSet.of(ActionType.STRUCTURE_GROW)),
            Map.entry("submitPortalCreate", EnumSet.of(ActionType.PORTAL_CREATE)),
            Map.entry("submitClick", EnumSet.of(ActionType.CLICK))
    );

    /** Types with no producer on any requested cell; kept as lookup/rollback API. */
    private static final Set<ActionType> NO_PRODUCER_ON_REQUESTED_CELLS = EnumSet.of(
            ActionType.USERNAME_CHANGE,
            ActionType.STRUCTURE_GROW,
            ActionType.CHUNK_POPULATE
    );

    /**
     * Fabric-only capture. NeoForge 1.21.1/26.1.2 have no SignChangeEvent (removed
     * in 1.20+) and no sign mixin; copying Fabric {@code SignChangeMixin} onto
     * NeoForge is out of scope for this slice.
     */
    private static final Set<ActionType> FABRIC_ONLY_DOCUMENTED = EnumSet.of(ActionType.SIGN);

    @Test
    void requestedCellsShareActionTypeSubmitCoverage() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        EnumSet<ActionType> fabric1211 = submittedTypes(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric"));
        EnumSet<ActionType> neo1211 = submittedTypes(root.resolve(
                "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge"));
        EnumSet<ActionType> neo2612 = submittedTypes(root.resolve(
                "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge"));

        assertFalse(fabric1211.isEmpty());
        assertFalse(neo1211.isEmpty());
        assertFalse(neo2612.isEmpty());

        EnumSet<ActionType> missingOnNeo2612 = EnumSet.copyOf(fabric1211);
        missingOnNeo2612.removeAll(neo2612);
        missingOnNeo2612.removeAll(FABRIC_ONLY_DOCUMENTED);
        EnumSet<ActionType> missingOnFabric = EnumSet.copyOf(neo2612);
        missingOnFabric.removeAll(fabric1211);
        EnumSet<ActionType> missingOnNeo1211 = EnumSet.copyOf(fabric1211);
        missingOnNeo1211.removeAll(neo1211);
        missingOnNeo1211.removeAll(FABRIC_ONLY_DOCUMENTED);

        if (!missingOnNeo2612.isEmpty() || !missingOnFabric.isEmpty() || !missingOnNeo1211.isEmpty()) {
            fail("ActionType submit coverage diverges across requested cells"
                    + "\n  Fabric 1.21.1 only vs 26.1.2 NeoForge: " + missingOnNeo2612
                    + "\n  26.1.2 NeoForge only vs Fabric 1.21.1: " + missingOnFabric
                    + "\n  Fabric 1.21.1 only vs 1.21.1 NeoForge: " + missingOnNeo1211);
        }

        assertTrue(fabric1211.contains(ActionType.SIGN));
        assertFalse(neo1211.contains(ActionType.SIGN));
        assertFalse(neo2612.contains(ActionType.SIGN));

        EnumSet<ActionType> unexplained = EnumSet.allOf(ActionType.class);
        unexplained.removeAll(fabric1211);
        unexplained.removeAll(NO_PRODUCER_ON_REQUESTED_CELLS);
        if (!unexplained.isEmpty()) {
            fail("ActionTypes have neither a requested-cell producer nor a documented absence: "
                    + unexplained);
        }
    }

    @Test
    void loaderSpecificCaptureMechanismsArePreservedNotCopied() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        String fabricMixins = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/resources/vg.mixins.json"));
        String neo1211Mixins = Files.readString(root.resolve(
                "mc-1.21.1/neoforge/src/main/resources/vg-neoforge.mixins.json"));
        String neo2612Mixins = Files.readString(root.resolve(
                "mc-26.1.2/neoforge/src/main/resources/vg-neoforge.mixins.json"));
        String neo1211Events = Files.readString(root.resolve(
                "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/NeoForgeEvents.java"));
        String neo2612Events = Files.readString(root.resolve(
                "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/NeoForgeEvents.java"));

        assertTrue(fabricMixins.contains("\"ExplosionMixin\""));
        assertTrue(fabricMixins.contains("\"PistonMixin\""));
        assertTrue(fabricMixins.contains("\"SignChangeMixin\""));
        assertTrue(fabricMixins.contains("\"ContainerMixin\""));
        assertTrue(fabricMixins.contains("\"BaseContainerBlockEntityMixin\""));
        assertFalse(fabricMixins.contains("\"MilkBucketItemMixin\""));

        assertTrue(neo1211Mixins.contains("\"MilkBucketItemMixin\""));
        assertTrue(neo2612Mixins.contains("\"MilkBucketItemMixin\""));
        assertTrue(neo1211Mixins.contains("\"RavagerMixin\""));
        assertTrue(neo2612Mixins.contains("\"RavagerMixin\""));
        assertFalse(neo1211Mixins.contains("\"ExplosionMixin\""));
        assertFalse(neo2612Mixins.contains("\"ExplosionMixin\""));
        assertFalse(neo1211Mixins.contains("\"LocationalInventory\""));
        assertFalse(neo2612Mixins.contains("\"LocationalInventory\""));

        assertTrue(neo1211Events.contains("@SubscribeEvent"));
        assertTrue(neo2612Events.contains("@SubscribeEvent"));
        assertTrue(neo1211Events.contains("ExplosionEvent"));
        assertTrue(neo2612Events.contains("ExplosionEvent"));
        assertTrue(neo1211Events.contains("PistonEvent"));
        assertTrue(neo2612Events.contains("PistonEvent"));
    }

    private static EnumSet<ActionType> submittedTypes(Path loaderRoot) throws IOException {
        assertTrue(Files.isDirectory(loaderRoot), "missing loader sources " + loaderRoot);
        EnumMap<ActionType, Boolean> found = new EnumMap<>(ActionType.class);
        Set<String> unknown = new TreeSet<>();
        try (Stream<Path> stream = Files.walk(loaderRoot)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    String text = Files.readString(file);
                    if (text.contains("explosionJoinWorker()")) {
                        found.put(ActionType.EXPLOSION, Boolean.TRUE);
                    }
                    Matcher matcher = SUBMIT.matcher(text);
                    while (matcher.find()) {
                        String method = matcher.group(1);
                        Set<ActionType> types = SUBMIT_TO_TYPES.get(method);
                        if (types == null) {
                            if (!"submit".equals(method) && !"submitLookup".equals(method)
                                    && !"submitAsync".equals(method)) {
                                unknown.add(method + " in " + loaderRoot.relativize(file));
                            }
                            continue;
                        }
                        for (ActionType type : types) {
                            found.put(type, Boolean.TRUE);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        if (!unknown.isEmpty()) {
            fail("unmapped submit* calls in " + loaderRoot + ": " + unknown);
        }
        EnumSet<ActionType> types = EnumSet.noneOf(ActionType.class);
        types.addAll(found.keySet());
        return types;
    }
}
