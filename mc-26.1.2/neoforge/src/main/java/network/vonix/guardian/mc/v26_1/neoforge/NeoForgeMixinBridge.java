/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.FluidSourceMemory;
import network.vonix.guardian.core.diagnostics.MixinHotEventFilter;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.mc.v26_1.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.entity.Entity;
import network.vonix.guardian.mc.v26_1.common.EntitySentinel;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.HashMap;
import java.util.Map;

/**
 * Non-mixin bridge for NeoForge mixins.
 *
 * <p>Mixin classes intentionally call only this bridge and never reference core
 * classes directly. Mixin pre-processing happens before the shaded core classes
 * are visible in the NeoForge dev-launch classpath; direct method references to
 * {@code network.vonix.guardian.core.*} crash boot. Keeping those references in
 * this normal mod class makes the mixin config boot-safe while preserving the
 * same EventSubmitter contract.</p>
 */
public final class NeoForgeMixinBridge {
    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeMixinBridge.class);

    private NeoForgeMixinBridge() {}

    private static EventSubmitter sub() {
        Guardian g = VonixGuardianNeoForge.guardian();
        return g == null ? null : g.submitter();
    }

    private static boolean persistNbt() {
        Guardian g = VonixGuardianNeoForge.guardian();
        return g != null && g.config() != null && g.config().storage() != null
                && g.config().storage().persistNbt();
    }

    public static void fireBurn(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            String worldId = WorldKey.of(level);
            FireCauserResolution r = resolveFire(level, pos);
            if (r.suppress) return; // orphan fire from a non-allowlisted entity
            s.submitBurn(r.actorUuid, r.actorName != null ? r.actorName : "#fire", worldId,
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state),
                    r.sourceTag != null ? r.sourceTag : "world:burn");
        } catch (Throwable t) {
            warn("fireBurn", t);
        }
    }

    public static void fireIgnite(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            String worldId = WorldKey.of(level);
            FireCauserResolution r = resolveFire(level, pos);
            if (r.suppress) return; // orphan fire from a non-allowlisted entity
            s.submitIgnite(r.actorUuid, r.actorName != null ? r.actorName : "#fire", worldId,
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state),
                    r.sourceTag != null ? r.sourceTag : "world:ignite");
        } catch (Throwable t) {
            warn("fireIgnite", t);
        }
    }

    /**
     * C2: consult the shared {@code FireCauserMemory} for a recent nearby
     * entity block change that caused this fire. Returns a small resolution
     * carrying either the pairing attribution (allowlisted causer), a suppress
     * flag (non-allowlisted causer), or all-null passthrough (genuine world
     * fire: player F&amp;S, lightning, lava, natural spread).
     */
    private static FireCauserResolution resolveFire(Level level, BlockPos pos) {
        try {
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g == null) return FireCauserResolution.PASSTHROUGH;
            network.vonix.guardian.core.attribution.UniversalAttribution.FireCauser v =
                    network.vonix.guardian.core.attribution.UniversalAttribution.resolveFireCauser(
                            g.fireCauserMemory(), WorldKey.of(level),
                            pos.getX(), pos.getY(), pos.getZ());
            switch (v.verdict) {
                case SUPPRESS:
                    return FireCauserResolution.SUPPRESS;
                case PAIR:
                    return new FireCauserResolution(false, v.actorUuid, v.actorName,
                            v.sourceTag != null ? "entity:" + v.sourceTag : "entity:#entity");
                case PASSTHROUGH:
                default:
                    return FireCauserResolution.PASSTHROUGH;
            }
        } catch (Throwable t) {
            warn("resolveFire", t);
            return FireCauserResolution.PASSTHROUGH;
        }
    }

    /** Small value carrier for {@link #resolveFire}. */
    private static final class FireCauserResolution {
        final boolean suppress;
        final java.util.UUID actorUuid;
        final String actorName;
        final String sourceTag;

        FireCauserResolution(boolean suppress, java.util.UUID actorUuid,
                             String actorName, String sourceTag) {
            this.suppress = suppress;
            this.actorUuid = actorUuid;
            this.actorName = actorName;
            this.sourceTag = sourceTag;
        }

        static final FireCauserResolution PASSTHROUGH =
                new FireCauserResolution(false, null, null, null);
        static final FireCauserResolution SUPPRESS =
                new FireCauserResolution(true, null, null, null);
    }

    public static void blockFade(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitFade(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:fade");
        } catch (Throwable t) {
            warn("blockFade", t);
        }
    }

    public static void leavesDecay(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitLeavesDecay(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:leavesdecay");
        } catch (Throwable t) {
            warn("leavesDecay", t);
        }
    }

    public static void blockSpread(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitSpread(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:spread");
        } catch (Throwable t) {
            warn("blockSpread", t);
        }
    }

    public static void blockForm(Level level, BlockPos pos, BlockState state, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitForm(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state),
                    sourceTag == null ? "world:form" : sourceTag);
        } catch (Throwable t) {
            warn("blockForm", t);
        }
    }

    public static void dispense(Level level, BlockPos pos) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null) return;
            s.submitDispense(null, "#dispenser", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), "minecraft:dispenser", "world:dispense");
        } catch (Throwable t) {
            warn("dispense", t);
        }
    }

    // ==================================================================== X7 TNT-prime memory
    // v1.3.1 X7: record the actor priming a TNT block so the eventual
    // explosion-detonate handler can attribute correctly. See
    // network.vonix.guardian.core.attribution.TntPrimeMemory.

    /**
     * Record a player as the priming actor for the TNT block at {@code pos}.
     *
     * @param player the priming player (never {@code null}); called from
     *               {@code TntBlockMixin.explode(Level,BlockPos,LivingEntity)}
     *               HEAD when the igniter is a Player, and from
     *               {@code PrimedTntEntityMixin.<init>} TAIL when the igniter
     *               argument on the constructor is a Player.
     */
    public static void recordTntPrimePlayer(Level level, BlockPos pos, Player player) {
        try {
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g == null || level == null || pos == null || player == null) return;
            long now = System.currentTimeMillis();
            g.tntPrimeMemory().record(
                    WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                    network.vonix.guardian.core.attribution.TntPrimeMemory.PrimeRecord.player(
                            player.getUUID(), player.getName().getString(), now));
        } catch (Throwable t) {
            warn("recordTntPrimePlayer", t);
        }
    }

    public static void bucketFill(Player player, BlockPos pos, String fluidId) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || pos == null) return;
            s.submitBucketFill(player.getUUID(), player.getName().getString(),
                    WorldKey.of(player.level()), pos.getX(), pos.getY(), pos.getZ(),
                    fluidId == null ? "minecraft:water" : fluidId, null);
        } catch (Throwable t) {
            warn("bucketFill", t);
        }
    }

    public static void bucketEmpty(Player player, BlockPos pos, String fluidId) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || pos == null) return;
            String worldId = WorldKey.of(player.level());
            s.submitBucketEmpty(player.getUUID(), player.getName().getString(),
                    worldId, pos.getX(), pos.getY(), pos.getZ(),
                    fluidId == null ? "minecraft:water" : fluidId, null);
            // v1.3.1 X3: seed the 2-min traceback so downstream fluid-flow
            // rows within radius can attribute back to this player.
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g != null) {
                FluidSourceMemory mem = g.fluidSourceMemory();
                if (mem != null) {
                    mem.recordBucketEmpty(worldId, pos.getX(), pos.getY(), pos.getZ(),
                            player.getUUID(), player.getName().getString(),
                            System.currentTimeMillis());
                }
            }
        } catch (Throwable t) {
            warn("bucketEmpty", t);
        }
    }

    /**
     * v1.3.1 X3: fluid-flow producer entry.
     *
     * @param level        the server level (spread cell world)
     * @param pos          the destination position that will now hold the
     *                     flowing fluid
     * @param flowingFluid the fluid that is spreading; used to resolve the
     *                     water/lava registry id
     */
    public static void fluidFlow(ServerLevel level, BlockPos pos, FlowingFluid flowingFluid) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || flowingFluid == null) return;
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g == null) return;
            String worldId = WorldKey.of(level);
            String fluidBlockId = fluidBlockId(flowingFluid);
            String kind = fluidKind(flowingFluid); // "water" or "lava"
            String sourceTag = MixinHotEventFilter.PREFIX_FLUID + ":" + kind;

            // Attribution: try 2-min bucket traceback first.
            FluidSourceMemory mem = g.fluidSourceMemory();
            java.util.UUID actorUuid = null;
            String actorName = Sentinel.FLUID;
            if (mem != null) {
                FluidSourceMemory.Record rec = mem.lookup(worldId, pos.getX(), pos.getY(), pos.getZ(),
                        System.currentTimeMillis());
                if (rec != null && rec.actorUuid != null) {
                    actorUuid = rec.actorUuid;
                    actorName = rec.actorName != null ? rec.actorName : Sentinel.FLUID;
                }
            }
            s.submitFluidFlow(actorUuid, actorName, worldId,
                    pos.getX(), pos.getY(), pos.getZ(), fluidBlockId, sourceTag);
        } catch (Throwable t) {
            warn("fluidFlow", t);
        }
    }

    private static String fluidBlockId(Fluid fluid) {
        try {
            Identifier rl = BuiltInRegistries.FLUID.getKey(fluid);
            if (rl == null) return "minecraft:water";
            String path = rl.getPath();
            if (path.contains("lava")) return "minecraft:lava";
            return "minecraft:water";
        } catch (Throwable t) {
            return "minecraft:water";
        }
    }

    private static String fluidKind(Fluid fluid) {
        try {
            Identifier rl = BuiltInRegistries.FLUID.getKey(fluid);
            if (rl == null) return "water";
            return rl.getPath().contains("lava") ? "lava" : "water";
        } catch (Throwable t) {
            return "water";
        }
    }

    public static String blockId(BlockState state) {
        try {
            Identifier rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    public static String itemId(Item item) {
        try {
            Identifier rl = BuiltInRegistries.ITEM.getKey(item);
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    public static String itemId(ItemStack stack) {
        return stack == null ? "minecraft:air" : itemId(stack.getItem());
    }

    /** CoreProtect-parity producer for one player-inventory slot mutation. */
    public static boolean inventoryMetadataChanged(Player player, ItemStack before, ItemStack after) {
        try {
            if (player == null || before == null || after == null || !persistNbt()
                    || before.isEmpty() || after.isEmpty()) return false;
            byte[] beforeNbt = NbtCapture.itemStackComparison(before, player.level().registryAccess());
            byte[] afterNbt = NbtCapture.itemStackComparison(after, player.level().registryAccess());
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
            byte[] beforeNbt = nbtOn && !before.isEmpty() ? NbtCapture.itemStack(before, player.level().registryAccess()) : null;;
            byte[] afterNbt = nbtOn && !after.isEmpty() ? NbtCapture.itemStack(after, player.level().registryAccess()) : null;;
            byte[] beforeComparisonNbt = nbtOn && !before.isEmpty() ? NbtCapture.itemStackComparison(before, player.level().registryAccess()) : null;;
            byte[] afterComparisonNbt = nbtOn && !after.isEmpty() ? NbtCapture.itemStackComparison(after, player.level().registryAccess()) : null;;
            java.util.List<network.vonix.guardian.core.event.InventoryDelta> deltas =
                    network.vonix.guardian.core.event.InventoryDelta.betweenAll(
                            beforeId, before.isEmpty() ? 0 : before.getCount(),
                            afterId, after.isEmpty() ? 0 : after.getCount(), beforeComparisonNbt, afterComparisonNbt);
            if (deltas.isEmpty()) return;
            EventSubmitter s = sub();
            if (s == null) return;
            BlockPos pos = player.blockPosition();
            String world = WorldKey.of(player.level());
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

    /**
     * v1.3.1 X4 — Hopper push (item moved into a container). Attribution is
     * {@link Sentinel#HOPPER}: no player owner exists for a vanilla hopper.
     */
    public static void hopperPush(Level level, BlockPos pos, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || stack == null || stack.isEmpty()) return;
            s.submitHopperPush(null, Sentinel.HOPPER, WorldKey.of(level),
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
            s.submitHopperPull(null, Sentinel.HOPPER, WorldKey.of(level),
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
        var registries = level.registryAccess();
        var hopperAfter = toSlotStacks(snapshotContainer(hopperNow), nbtOn, registries);
        var hopperBeforeStacks = toSlotStacks(hopperBefore, nbtOn, registries);
        var otherAfter = toSlotStacks(snapshotContainer(otherNow), nbtOn, registries);
        var otherBeforeStacks = toSlotStacks(otherBefore, nbtOn, registries);
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

        String worldId = WorldKey.of(level);
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
        return be == null ? null : NbtCapture.blockEntity(be, level.registryAccess());
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
            Map<Integer, ItemStack> snap, boolean nbtOn, net.minecraft.core.HolderLookup.Provider registries) {
        java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotStack> out = new java.util.ArrayList<>();
        if (snap == null) return out;
        for (Map.Entry<Integer, ItemStack> e : snap.entrySet()) {
            ItemStack stack = e.getValue();
            if (stack == null || stack.isEmpty()) {
                out.add(new network.vonix.guardian.core.event.ContainerTransport.SlotStack(
                        e.getKey(), null, 0, null, null));
                continue;
            }
            byte[] full = nbtOn && registries != null ? NbtCapture.itemStack(stack, registries) : null;
            byte[] cmp = nbtOn && registries != null ? NbtCapture.itemStackComparison(stack, registries) : null;
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
            var registries = player.level() != null ? player.level().registryAccess() : null;
            java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotStack> before =
                    toSlotStacks(snap, nbtOn, registries);
            java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotStack> after =
                    toSlotStacks(snapshotContainer(container), nbtOn, registries);
            java.util.List<network.vonix.guardian.core.event.ContainerTransport.SlotChange> changes =
                    network.vonix.guardian.core.event.ContainerTransport.diff(before, after);
            String blockState = null;
            byte[] beNbt = null;
            if (nbtOn && container instanceof BlockEntity be && be.getLevel() != null) {
                blockState = NbtCapture.blockStateProps(be.getBlockState());
                beNbt = NbtCapture.blockEntity(be, be.getLevel().registryAccess());
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
        Guardian g = VonixGuardianNeoForge.guardian();
        if (g == null || g.config() == null || g.config().actions() == null) return true;
        return g.config().actions().logDuplicateSuppression();
    }

    /**
     * v1.3.1 X4 — Portal-frame block placement. Emitted as {@code PORTAL_CREATE}
     * with {@link Sentinel#PORTAL} attribution and source tag {@code #portal};
     * rollback treats these rows as world-events.
     */
    public static void portalCreate(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitPortalCreate(null, Sentinel.PORTAL, WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(state), Sentinel.PORTAL);
        } catch (Throwable t) {
            warn("portalCreate", t);
        }
    }

    /**
     * v1.3.1 X4 — {@code /fill} / {@code /setblock} per-block old-state break
     * row. Attributed to the player when a player invoked the command, else to
     * {@link Sentinel#COMMAND}.
     */
    public static void commandBlockBreak(Player player, Level level, BlockPos pos,
                                         BlockState oldState, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || oldState == null || oldState.isAir()) return;
            java.util.UUID uuid = player != null ? player.getUUID() : null;
            String name = player != null ? player.getName().getString() : Sentinel.COMMAND;
            s.submitBlockBreak(uuid, name, WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(oldState), sourceTag != null ? sourceTag : "cmd");
        } catch (Throwable t) {
            warn("commandBlockBreak", t);
        }
    }

    /** v1.3.1 X4 — {@code /fill} / {@code /setblock} per-block new-state place. */
    public static void commandBlockPlace(Player player, Level level, BlockPos pos,
                                         BlockState newState, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || newState == null) return;
            java.util.UUID uuid = player != null ? player.getUUID() : null;
            String name = player != null ? player.getName().getString() : Sentinel.COMMAND;
            s.submitBlockPlace(uuid, name, WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(newState), sourceTag != null ? sourceTag : "cmd");
        } catch (Throwable t) {
            warn("commandBlockPlace", t);
        }
    }

    private static void warn(String path, Throwable t) {
        LOG.warn("NeoForgeMixinBridge {} failed: {}", path, t.toString());
    }
    // ================================================================== v1.3.1 X2 dispatchers
    
        /**
         * Entity-caused block break — used by the 6 X2 mixins (EnderDragon, Ravager,
         * FallingBlockEntity fall side, Silverfish infest, LightningBolt fire spread
         * cleanup path if applicable). Produces an ENTITY_CHANGE_BLOCK action with
         * a mob-scoped {@code actorName} derived from the entity's registry type and
         * a stable {@code sourceTag} that mirrors the Ledger source constants.
         *
         * @param entity     the entity that caused the break; may be {@code null}
         * @param level      dimension the break happened in
         * @param pos        block position
         * @param oldState   block state before removal (used to produce {@code oldBlockId})
         * @param sourceTag  one of the {@code EntitySentinel.SRC_*} constants
         */
        public static void entityBreak(Entity entity, Level level, BlockPos pos, BlockState oldState, String sourceTag) {
            try {
                EventSubmitter s = sub();
                if (s == null || level == null || pos == null || oldState == null) return;
                String actor = EntitySentinel.of(entity);
                s.submitEntityChangeBlock(null, actor,
                        WorldKey.of(level),
                        pos.getX(), pos.getY(), pos.getZ(),
                        blockId(oldState), "minecraft:air",
                        sourceTag == null ? actor : sourceTag);
            } catch (Throwable t) {
                warn("entityBreak", t);
            }
        }

    /**
         * Full old→new state change — used by Silverfish infest, which swaps stone→
         * infested_stone (i.e. block-id changes, not break-then-place).
         */
        public static void entityChange(Entity entity, Level level, BlockPos pos, BlockState oldState, BlockState newState, String sourceTag) {
            try {
                EventSubmitter s = sub();
                if (s == null || level == null || pos == null || oldState == null || newState == null) return;
                String actor = EntitySentinel.of(entity);
                s.submitEntityChangeBlock(null, actor,
                        WorldKey.of(level),
                        pos.getX(), pos.getY(), pos.getZ(),
                        blockId(oldState), blockId(newState),
                        sourceTag == null ? actor : sourceTag);
            } catch (Throwable t) {
                warn("entityChange", t);
            }
        }

    /**
         * Entity-caused block place — used by SnowGolem (aiStep snow trail),
         * FallingBlockEntity landing, and LightningBolt spawnFire.
         *
         * @param entity     the entity that placed the block; may be {@code null}
         * @param level      dimension
         * @param pos        block position
         * @param newState   block state placed (used to produce {@code newBlockId})
         * @param sourceTag  one of the {@code EntitySentinel.SRC_*} constants
         */
        public static void entityPlace(Entity entity, Level level, BlockPos pos, BlockState newState, String sourceTag) {
            try {
                EventSubmitter s = sub();
                if (s == null || level == null || pos == null || newState == null) return;
                String actor = EntitySentinel.of(entity);
                s.submitEntityChangeBlock(null, actor,
                        WorldKey.of(level),
                        pos.getX(), pos.getY(), pos.getZ(),
                        "minecraft:air", blockId(newState),
                        sourceTag == null ? actor : sourceTag);
            } catch (Throwable t) {
                warn("entityPlace", t);
            }
        }

}
