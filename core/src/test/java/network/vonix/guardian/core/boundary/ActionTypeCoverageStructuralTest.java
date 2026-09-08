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
            Map.entry("submitHopperTransfer", EnumSet.of(ActionType.HOPPER_PUSH, ActionType.HOPPER_PULL)),
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
     * Published not-applicable types: no producer on any of the nine cells.
     * Lookup/rollback API remains for historical rows.
     */
    private static final java.util.List<CellSpec> CELLS = java.util.List.of(
            new CellSpec("1.18.2-fabric", "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric"),
            new CellSpec("1.18.2-forge", "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge"),
            new CellSpec("1.19.2-fabric", "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric"),
            new CellSpec("1.19.2-forge", "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge"),
            new CellSpec("1.20.1-fabric", "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric"),
            new CellSpec("1.20.1-forge", "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge"),
            new CellSpec("1.21.1-fabric", "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric"),
            new CellSpec("1.21.1-neoforge", "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge"),
            new CellSpec("26.1.2-neoforge", "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge")
    );

    private record CellSpec(String name, String path) {}

    @Test
    void requestedCellsShareActionTypeSubmitCoverage() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        java.util.LinkedHashMap<String, EnumSet<ActionType>> byCell = new java.util.LinkedHashMap<>();
        EnumSet<ActionType> union = EnumSet.noneOf(ActionType.class);
        for (CellSpec cell : CELLS) {
            EnumSet<ActionType> types = submittedTypes(root.resolve(cell.path()));
            assertFalse(types.isEmpty(), cell.name());
            assertTrue(types.contains(ActionType.SIGN), cell.name() + " must capture SIGN");
            assertTrue(types.contains(ActionType.FORM), cell.name() + " must capture FORM");
            assertTrue(types.contains(ActionType.CHAT), cell.name() + " must capture CHAT");
            assertTrue(types.contains(ActionType.COMMAND), cell.name() + " must capture COMMAND");
            byCell.put(cell.name(), types);
            union.addAll(types);
        }

        StringBuilder drift = new StringBuilder();
        for (var a : byCell.entrySet()) {
            for (var b : byCell.entrySet()) {
                if (a.getKey().equals(b.getKey())) continue;
                EnumSet<ActionType> missing = EnumSet.copyOf(a.getValue());
                missing.removeAll(b.getValue());
                missing.removeAll(NO_PRODUCER_ON_REQUESTED_CELLS);
                if (!missing.isEmpty()) {
                    drift.append('\n').append(a.getKey()).append(" only vs ").append(b.getKey())
                            .append(": ").append(missing);
                }
            }
        }
        if (drift.length() > 0) {
            fail("ActionType submit coverage diverges across the nine cells" + drift);
        }

        EnumSet<ActionType> unexplained = EnumSet.allOf(ActionType.class);
        unexplained.removeAll(union);
        unexplained.removeAll(NO_PRODUCER_ON_REQUESTED_CELLS);
        if (!unexplained.isEmpty()) {
            fail("ActionTypes have neither a requested-cell producer nor a documented absence: "
                    + unexplained);
        }
    }

    @Test
    void formAndSignHaveCallSitesNotJustDeadHelpers() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (CellSpec cell : CELLS) {
            Path loader = root.resolve(cell.path());
            assertTrue(hasExternalCall(loader, "blockForm(") || hasCallOutsideBridge(loader, "submitForm("),
                    cell.name() + " FORM must have a mixin/event call site");
            assertTrue(hasExternalCall(loader, "submitSign(") || hasExternalCall(loader, "signChange("),
                    cell.name() + " SIGN must have a submitSign or signChange call site");
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
        assertTrue(neo1211Mixins.contains("\"SignChangeMixin\""));
        assertTrue(neo2612Mixins.contains("\"SignChangeMixin\""));
        assertTrue(neo1211Mixins.contains("\"ConcretePowderBlockMixin\""));
        assertTrue(neo2612Mixins.contains("\"ConcretePowderBlockMixin\""));
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

    private static boolean hasCallOutsideBridge(Path loaderRoot, String token) throws IOException {
        try (Stream<Path> stream = Files.walk(loaderRoot)) {
            return stream.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().contains("MixinBridge"))
                    .anyMatch(file -> {
                        try {
                            return Files.readString(file).contains(token);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private static boolean hasExternalCall(Path loaderRoot, String token) throws IOException {
        try (Stream<Path> stream = Files.walk(loaderRoot)) {
            return stream.filter(p -> p.toString().endsWith(".java")).anyMatch(file -> {
                try {
                    String text = Files.readString(file);
                    int idx = 0;
                    while ((idx = text.indexOf(token, idx)) >= 0) {
                        int lineStart = text.lastIndexOf('\n', idx) + 1;
                        String line = text.substring(lineStart, Math.min(text.length(), idx + token.length() + 40));
                        if (!line.contains("void " + token.replace("(", ""))
                                && !line.contains("public static void " + token.replace("(", ""))) {
                            return true;
                        }
                        idx += token.length();
                    }
                    return false;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
