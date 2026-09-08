/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.HashMap;
import java.util.Map;

/**
 * v1.3.1 X4 — Forge-side bridge for the new hopper/portal/command mixins.
 *
 * <p>Existing Forge mixins (FireBlockMixin, LeavesBlockMixin, etc.) resolve the
 * Guardian facade inline via {@link VonixGuardianForge#guardian()}. The X4
 * mixins share more dispatch surface (5+ methods), so we consolidate them into
 * this bridge to avoid duplicating the sub()/warn() plumbing across four
 * mixins per cell.</p>
 *
 * <p>All entry points swallow {@link Throwable}: mixins must NEVER escape into
 * the server thread.</p>
 */
public final class ForgeMixinBridge {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeMixinBridge.class);

    private ForgeMixinBridge() {}

    private static EventSubmitter sub() {
        Guardian g = VonixGuardianForge.guardian();
        return g == null ? null : g.submitter();
    }

    private static boolean persistNbt() {
        Guardian g = VonixGuardianForge.guardian();
        return g != null && g.config() != null && g.config().storage() != null
                && g.config().storage().persistNbt();
    }

    private static String worldKey(Level level) {
        try {
            return level.dimension().location().toString();
        } catch (Throwable t) {
            return "minecraft:overworld";
        }
    }

    public static String blockId(BlockState state) {
        try {
            ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    public static String itemId(ItemStack stack) {
        try {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    /** CoreProtect-parity producer for one player-inventory slot mutation. */
    public static boolean inventoryMetadataChanged(Player player, ItemStack before, ItemStack after) {
        try {
            if (player == null || before == null || after == null || !persistNbt()
                    || before.isEmpty() || after.isEmpty()) return false;
            byte[] beforeNbt = NbtCapture.itemStackComparison(before);
            byte[] afterNbt = NbtCapture.itemStackComparison(after);
            return !java.util.Arrays.equals(beforeNbt, afterNbt);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void playerInventorySlotChange(Player player, ItemStack before, ItemStack after, Integer slot) {
        try {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer)
                    || before == null || after == null || player.level() == null) return;
            String beforeId = before.isEmpty() ? null : itemId(before);
            String afterId = after.isEmpty() ? null : itemId(after);
            boolean nbtOn = persistNbt();
            byte[] beforeNbt = nbtOn && !before.isEmpty() ? NbtCapture.itemStack(before) : null;
            byte[] afterNbt = nbtOn && !after.isEmpty() ? NbtCapture.itemStack(after) : null;
            byte[] beforeComparisonNbt = nbtOn && !before.isEmpty() ? NbtCapture.itemStackComparison(before) : null;
            byte[] afterComparisonNbt = nbtOn && !after.isEmpty() ? NbtCapture.itemStackComparison(after) : null;
            java.util.List<network.vonix.guardian.core.event.InventoryDelta> deltas =
                    network.vonix.guardian.core.event.InventoryDelta.betweenAll(
                            beforeId, before.isEmpty() ? 0 : before.getCount(),
                            afterId, after.isEmpty() ? 0 : after.getCount(), beforeComparisonNbt, afterComparisonNbt);
            if (deltas.isEmpty()) return;
            EventSubmitter s = sub();
            if (s == null) return;
            BlockPos pos = player.blockPosition();
            String world = worldKey(player.level());
            if (network.vonix.guardian.core.event.InventoryReplacementPairs.isReplacement(deltas)) {
                s.submitInventoryReplacement(player.getUUID(), player.getName().getString(), world,
                        pos.getX(), pos.getY(), pos.getZ(),
                        deltas.get(0).itemId(), deltas.get(0).amount(), beforeNbt,
                        deltas.get(1).itemId(), deltas.get(1).amount(), afterNbt,
                        slot);
                return;
            }
            for (network.vonix.guardian.core.event.InventoryDelta delta : deltas) {
                byte[] itemNbt = delta.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.DEPOSIT
                        ? afterNbt : beforeNbt;
                if (delta.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.DEPOSIT) {
                    if (itemNbt != null) s.submitInventoryDeposit(player.getUUID(), player.getName().getString(), world,
                            pos.getX(), pos.getY(), pos.getZ(), delta.itemId(), delta.amount(), null, itemNbt, slot);
                    else s.submitInventoryDeposit(player.getUUID(), player.getName().getString(), world,
                            pos.getX(), pos.getY(), pos.getZ(), delta.itemId(), delta.amount(), null, slot);
                } else if (delta.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.WITHDRAW) {
                    if (itemNbt != null) s.submitInventoryWithdraw(player.getUUID(), player.getName().getString(), world,
                            pos.getX(), pos.getY(), pos.getZ(), delta.itemId(), delta.amount(), null, itemNbt, slot);
                    else s.submitInventoryWithdraw(player.getUUID(), player.getName().getString(), world,
                            pos.getX(), pos.getY(), pos.getZ(), delta.itemId(), delta.amount(), null, slot);
                }
            }
        } catch (Throwable t) {
            warn("playerInventorySlotChange", t);
        }
    }

    // ================================================================== v1.3.1 X4 dispatchers

    /** v1.3.1 X4 — Hopper push (item moved into a container). */
    public static void hopperPush(Level level, BlockPos pos, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || stack == null || stack.isEmpty()) return;
            s.submitHopperPush(null, Sentinel.HOPPER, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), Sentinel.HOPPER);
        } catch (Throwable t) {
            warn("hopperPush", t);
        }
    }

    /** v1.3.1 X4 — Hopper pull (item moved out of a container). */
    public static void hopperPull(Level level, BlockPos pos, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || stack == null || stack.isEmpty()) return;
            s.submitHopperPull(null, Sentinel.HOPPER, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), Sentinel.HOPPER);
        } catch (Throwable t) {
            warn("hopperPull", t);
        }
    }

    private static final ThreadLocal<HopperCapture> HOPPER_CAPTURE = new ThreadLocal<>();
    private static final int MAX_HOPPER_SLOTS = 216;

    private static final class HopperCapture {
        Level level;
        BlockPos hopperPos;
        BlockPos otherPos;
        Map<Integer, ItemStack> hopperBefore;
        Map<Integer, ItemStack> otherBefore;
        boolean pullFromOther;
        final java.util.Set<Integer> destSlots = new java.util.HashSet<>();
    }

    public static Map<Integer, ItemStack> snapshotContainer(net.minecraft.world.Container container) {
        Map<Integer, ItemStack> snap = new HashMap<>();
        if (container == null) return snap;
        int size = Math.min(container.getContainerSize(), MAX_HOPPER_SLOTS);
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            snap.put(i, stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return snap;
    }

    public static void hopperEjectBegin(Level level, BlockPos pos, HopperBlockEntity hopper) {
        try {
            HopperCapture cap = new HopperCapture();
            cap.level = level;
            cap.hopperPos = pos == null ? null : pos.immutable();
            cap.hopperBefore = snapshotContainer(hopper);
            cap.pullFromOther = false;
            if (level != null && pos != null) {
                BlockState state = level.getBlockState(pos);
                Direction facing = state.hasProperty(net.minecraft.world.level.block.HopperBlock.FACING)
                        ? state.getValue(net.minecraft.world.level.block.HopperBlock.FACING)
                        : Direction.DOWN;
                cap.otherPos = pos.relative(facing);
                cap.otherBefore = snapshotContainer(HopperBlockEntity.getContainerAt(level, cap.otherPos));
            }
            HOPPER_CAPTURE.set(cap);
        } catch (Throwable t) {
            warn("hopperEjectBegin", t);
        }
    }

    public static void hopperEjectCommit(Level level, BlockPos pos, HopperBlockEntity hopper) {
        try {
            HopperCapture cap = HOPPER_CAPTURE.get();
            hopperAbort();
            if (cap == null) return;
            emitHopperDiff(level != null ? level : cap.level,
                    cap.hopperPos != null ? cap.hopperPos : pos,
                    hopper,
                    cap.otherPos,
                    cap.hopperBefore,
                    cap.otherBefore,
                    false);
        } catch (Throwable t) {
            hopperAbort();
            warn("hopperEjectCommit", t);
        }
    }

    public static void hopperSuckBegin(Level level, Hopper hopper) {
        try {
            HopperCapture cap = new HopperCapture();
            cap.level = level;
            cap.pullFromOther = true;
            BlockPos pos = hopperPos(hopper);
            cap.hopperPos = pos;
            cap.hopperBefore = snapshotContainer((Container) hopper);
            if (level != null && pos != null) {
                cap.otherPos = pos.above();
                cap.otherBefore = snapshotContainer(HopperBlockEntity.getContainerAt(level, cap.otherPos));
            }
            HOPPER_CAPTURE.set(cap);
        } catch (Throwable t) {
            warn("hopperSuckBegin", t);
        }
    }

    public static void hopperSuckCommit(Level level, Hopper hopper) {
        try {
            HopperCapture cap = HOPPER_CAPTURE.get();
            hopperAbort();
            if (cap == null) return;
            emitHopperDiff(level != null ? level : cap.level,
                    cap.hopperPos,
                    hopper instanceof HopperBlockEntity hbe ? hbe : null,
                    cap.otherPos,
                    cap.hopperBefore,
                    cap.otherBefore,
                    true);
        } catch (Throwable t) {
            hopperAbort();
            warn("hopperSuckCommit", t);
        }
    }

    public static void hopperMoveSlot(int destSlot) {
        HopperCapture cap = HOPPER_CAPTURE.get();
        if (cap != null && destSlot >= 0) {
            cap.destSlots.add(destSlot);
        }
    }

    public static void hopperAbort() {
        HOPPER_CAPTURE.remove();
    }

    private static void emitHopperDiff(Level level, BlockPos hopperPos, HopperBlockEntity hopper,
                                       BlockPos otherPos,
                                       Map<Integer, ItemStack> hopperBefore,
                                       Map<Integer, ItemStack> otherBefore,
                                       boolean suck) {
        EventSubmitter s = sub();
        if (s == null || level == null || hopperPos == null) return;
        boolean nbtOn = persistNbt();
        Container hopperNow = hopper != null ? hopper : HopperBlockEntity.getContainerAt(level, hopperPos);
        Container otherNow = otherPos == null ? null : HopperBlockEntity.getContainerAt(level, otherPos);
        var hopperAfter = toSlotStacks(snapshotContainer(hopperNow), nbtOn);
        var hopperBeforeStacks = toSlotStacks(hopperBefore, nbtOn);
        var otherAfter = toSlotStacks(snapshotContainer(otherNow), nbtOn);
        var otherBeforeStacks = toSlotStacks(otherBefore, nbtOn);
        var hopperChanges = network.vonix.guardian.core.event.ContainerTransport.diff(hopperBeforeStacks, hopperAfter);
        var otherChanges = network.vonix.guardian.core.event.ContainerTransport.diff(otherBeforeStacks, otherAfter);

        java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotChange> pulls = new java.util.ArrayList<>();
        java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotChange> pushes = new java.util.ArrayList<>();
        BlockPos pullPos;
        BlockPos pushPos;
        if (suck) {
            for (var c : otherChanges) {
                if (c.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.WITHDRAW) pulls.add(c);
            }
            for (var c : hopperChanges) {
                if (c.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.DEPOSIT) pushes.add(c);
            }
            pullPos = otherPos != null ? otherPos : hopperPos.above();
            pushPos = hopperPos;
        } else {
            for (var c : hopperChanges) {
                if (c.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.WITHDRAW) pulls.add(c);
            }
            for (var c : otherChanges) {
                if (c.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.DEPOSIT) pushes.add(c);
            }
            pullPos = hopperPos;
            pushPos = otherPos != null ? otherPos : hopperPos;
        }
        if (pulls.isEmpty() && pushes.isEmpty()) return;

        String worldId = worldKey(level);
        String pullState = nbtOn ? NbtCapture.blockStateProps(level.getBlockState(pullPos)) : null;
        String pushState = nbtOn ? NbtCapture.blockStateProps(level.getBlockState(pushPos)) : null;
        byte[] pullBe = nbtOn ? blockEntityNbtAt(level, pullPos) : null;
        byte[] pushBe = nbtOn ? blockEntityNbtAt(level, pushPos) : null;

        if (pulls.size() == 1 && pushes.size() == 1) {
            var pull = pulls.get(0);
            var push = pushes.get(0);
            s.submitHopperTransfer(null, Sentinel.HOPPER, worldId,
                    new network.vonix.guardian.core.event.ContainerTransport.TransferSide(
                            pullPos.getX(), pullPos.getY(), pullPos.getZ(),
                            pull.itemId(), pull.amount(), pull.itemNbt(), pull.slot(),
                            pullState, pullState, pullBe),
                    new network.vonix.guardian.core.event.ContainerTransport.TransferSide(
                            pushPos.getX(), pushPos.getY(), pushPos.getZ(),
                            push.itemId(), push.amount(), push.itemNbt(), push.slot(),
                            pushState, pushState, pushBe),
                    Sentinel.HOPPER);
            return;
        }
        for (var pull : pulls) {
            s.submitHopperPull(null, Sentinel.HOPPER, worldId,
                    pullPos.getX(), pullPos.getY(), pullPos.getZ(),
                    pull.itemId(), pull.amount(), Sentinel.HOPPER,
                    pull.itemNbt(), pull.slot(), pullState, pullState, pullBe, null);
        }
        for (var push : pushes) {
            s.submitHopperPush(null, Sentinel.HOPPER, worldId,
                    pushPos.getX(), pushPos.getY(), pushPos.getZ(),
                    push.itemId(), push.amount(), Sentinel.HOPPER,
                    push.itemNbt(), push.slot(), pushState, pushState, pushBe, null);
        }
    }

    private static byte[] blockEntityNbtAt(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : NbtCapture.blockEntity(be);
    }

    private static BlockPos hopperPos(Hopper hopper) {
        if (hopper instanceof HopperBlockEntity hbe) {
            return hbe.getBlockPos();
        }
        if (hopper == null) return null;
        return new BlockPos((int) Math.floor(hopper.getLevelX()),
                (int) Math.floor(hopper.getLevelY()),
                (int) Math.floor(hopper.getLevelZ()));
    }

    private static java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotStack> toSlotStacks(
            Map<Integer, ItemStack> snap, boolean nbtOn) {
        java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotStack> out = new java.util.ArrayList<>();
        if (snap == null) return out;
        for (Map.Entry<Integer, ItemStack> e : snap.entrySet()) {
            ItemStack stack = e.getValue();
            if (stack == null || stack.isEmpty()) {
                out.add(new network.vonix.guardian.core.event.ContainerTransport.SlotStack(
                        e.getKey(), null, 0, null, null));
                continue;
            }
            byte[] full = nbtOn ? NbtCapture.itemStack(stack) : null;
            byte[] cmp = nbtOn ? NbtCapture.itemStackComparison(stack) : null;
            out.add(new network.vonix.guardian.core.event.ContainerTransport.SlotStack(
                    e.getKey(), itemId(stack), stack.getCount(), cmp, full));
        }
        return out;
    }

    public static void emitContainerClose(Player player, net.minecraft.world.Container container,
                                          Map<Integer, ItemStack> snap, BlockPos pos, String worldId) {
        try {
            if (player == null || container == null || snap == null || pos == null || worldId == null) return;
            EventSubmitter s = sub();
            if (s == null) return;
            boolean nbtOn = persistNbt();
            boolean dedupe = duplicateSuppressionEnabled();
            java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotStack> before =
                    toSlotStacks(snap, nbtOn);
            java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotStack> after =
                    toSlotStacks(snapshotContainer(container), nbtOn);
            java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotChange> changes =
                    network.vonix.guardian.core.event.ContainerTransport.diff(before, after);
            String blockState = null;
            byte[] beNbt = null;
            if (nbtOn && container instanceof BlockEntity be && be.getLevel() != null) {
                blockState = NbtCapture.blockStateProps(be.getBlockState());
                beNbt = NbtCapture.blockEntity(be);
            }
            Long pair = network.vonix.guardian.core.event.ContainerTransport.isReplacement(changes)
                    ? network.vonix.guardian.core.event.HopperTransportPairs.nextPairId()
                    : null;
            long now = System.currentTimeMillis();
            for (var change : changes) {
                int delta = change.kind() == network.vonix.guardian.core.event.InventoryDelta.Kind.DEPOSIT
                        ? change.amount() : -change.amount();
                if (dedupe) {
                    network.vonix.guardian.core.action.Action probe =
                            new network.vonix.guardian.core.action.ActionBuilder()
                                    .type(delta > 0
                                            ? network.vonix.guardian.core.action.ActionType.CONTAINER_DEPOSIT
                                            : network.vonix.guardian.core.action.ActionType.CONTAINER_WITHDRAW)
                                    .worldId(worldId)
                                    .position(pos.getX(), pos.getY(), pos.getZ())
                                    .targetId(change.itemId())
                                    .amount(change.amount())
                                    .inventorySlot(change.slot())
                                    .build();
                    if (network.vonix.guardian.core.event.ContainerTransport.suppressDuplicate(probe, now)) {
                        continue;
                    }
                }
                s.submitContainerChange(player.getUUID(), player.getName().getString(), worldId,
                        pos.getX(), pos.getY(), pos.getZ(), change.itemId(), delta, null,
                        change.itemNbt(), change.slot(), blockState, blockState, beNbt, pair);
            }
        } catch (Throwable t) {
            warn("emitContainerClose", t);
        }
    }

    private static boolean duplicateSuppressionEnabled() {
        Guardian g = VonixGuardianForge.guardian();
        if (g == null || g.config() == null || g.config().actions() == null) return true;
        return g.config().actions().logDuplicateSuppression();
    }

    // portalCreate() removed in v1.3.2 Y2 — Nether portal frame creation is
    // now captured directly via BlockEvent.PortalSpawnEvent in ForgeEvents.

    /** v1.3.1 X4 — /fill or /setblock per-block old-state break row. */
    public static void commandBlockBreak(Player player, Level level, BlockPos pos,
                                         BlockState oldState, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || oldState == null || oldState.isAir()) return;
            UUID uuid = player != null ? player.getUUID() : null;
            String name = player != null ? player.getName().getString() : Sentinel.COMMAND;
            s.submitBlockBreak(uuid, name, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(oldState), sourceTag != null ? sourceTag : "cmd");
        } catch (Throwable t) {
            warn("commandBlockBreak", t);
        }
    }

    /** v1.3.1 X4 — /fill or /setblock per-block new-state place row. */
    public static void commandBlockPlace(Player player, Level level, BlockPos pos,
                                         BlockState newState, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || newState == null) return;
            UUID uuid = player != null ? player.getUUID() : null;
            String name = player != null ? player.getName().getString() : Sentinel.COMMAND;
            s.submitBlockPlace(uuid, name, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(newState), sourceTag != null ? sourceTag : "cmd");
        } catch (Throwable t) {
            warn("commandBlockPlace", t);
        }
    }

    /** DispenserBlock#dispenseFrom → DISPENSE. Matches Fabric/NeoForge contract. */
    public static void dispense(Level level, BlockPos pos) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null) return;
            s.submitDispense(null, "#dispenser", worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(), "minecraft:dispenser", "world:dispense");
        } catch (Throwable t) {
            warn("dispense", t);
        }
    }


    public static void signChange(Player player, Level level, BlockPos pos, String[] lines, boolean isFront) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || level == null || pos == null || lines == null) return;
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) joined.append('\n');
                joined.append(lines[i] == null ? "" : lines[i]);
            }
            String side = isFront ? "front" : "back";
            String dye = null;
            Boolean waxed = null;
            try {
                var be = level.getBlockEntity(pos);
                var meta = isFront
                        ? network.vonix.guardian.mc.v1_20_1.common.SignMetadataExtractor.front(be)
                        : network.vonix.guardian.mc.v1_20_1.common.SignMetadataExtractor.back(be);
                dye = meta.dyeColor();
                waxed = meta.waxed();
            } catch (Throwable ignored) { }
            s.submitSign(player.getUUID(), player.getName().getString(),
                    worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(), joined.toString(),
                    side, dye, waxed);
        } catch (Throwable t) {
            warn("signChange", t);
        }
    }

    private static void warn(String label, Throwable t) {
        LOG.warn(Guardian.MARKER, "ForgeMixinBridge {} failed", label, t);
    }
}
