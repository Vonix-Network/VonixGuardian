/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.mc.v1_19_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_19_2.common.SourceTagger;
import network.vonix.guardian.mc.v1_19_2.common.WorldKey;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricBootstrap;
import network.vonix.guardian.mc.v1_19_2.fabric.VonixGuardianFabric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared bridge for Fabric mixins on MC 1.20.1 (W5-08).
 *
 * <p>Each mixin performs the minimum reflection into the target vanilla class,
 * then hands attribution + submitter dispatch off to this bridge so we keep the
 * per-mixin surface small and reuse the existing {@link EventSubmitter} contract.
 *
 * <p>All entry points swallow {@link Throwable}: mixins must NEVER escape into
 * the server thread.
 */
public final class FabricMixinBridge {

    private static final Logger LOG = LoggerFactory.getLogger(FabricMixinBridge.class);

    private FabricMixinBridge() {}

    private static EventSubmitter sub() {
        Guardian g = VonixGuardianFabric.guardian();
        return g == null ? null : g.submitter();
    }

    // ================================================================== W5-08 dispatchers

    /** BlockItem#place → BLOCK_PLACE */
    public static void blockPlace(Player player, Level level, BlockPos pos, BlockState placed) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || level == null || pos == null || placed == null) return;
            s.submitBlockPlace(player.getUUID(), player.getName().getString(),
                    WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                    blockId(placed), null);
        } catch (Throwable t) {
            warn("blockPlace", t);
        }
    }

    /** Level#destroyBlock with LivingEntity source → ENTITY_CHANGE_BLOCK. */
    public static void livingDestroyBlock(LivingEntity mob, Level level, BlockPos pos, BlockState oldState) {
        try {
            EventSubmitter s = sub();
            if (s == null || mob == null || level == null || pos == null) return;
            Attribution attr = FabricBootstrap.resolver != null
                    ? FabricBootstrap.resolver.resolve(mob, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.of(mob));
            s.submitEntityChangeBlock(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(oldState), "minecraft:air", attr.entitySentinel());
        } catch (Throwable t) {
            warn("livingDestroyBlock", t);
        }
    }

    /**
     * Explosion#finalizeExplosion → EXPLOSION.
     *
     * <p><b>v1.3.0 W3:</b> server thread does the (unavoidable) per-pos
     * {@code getBlockState} + registry key lookup into pooled scratch arrays,
     * then hands the {@link StringBuilder} join + queue enqueue to
     * {@link network.vonix.guardian.core.event.ExplosionJoinWorker}. A 5,000-block
     * TNT chain no longer builds a 4 KiB {@link StringBuilder} on the tick.</p>
     */
    public static void explosion(Level level, Entity source,
                                 double cx, double cy, double cz,
                                 List<BlockPos> affected) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || affected == null || affected.isEmpty()) return;
            ExplosionScratch scratch = EXPLOSION_SCRATCH.get();
            int n = affected.size();
            scratch.grow(n);
            int idx = 0;
            for (BlockPos p : affected) {
                scratch.xs[idx] = p.getX();
                scratch.ys[idx] = p.getY();
                scratch.zs[idx] = p.getZ();
                scratch.ids[idx] = blockId(level.getBlockState(p));
                idx++;
            }
            Attribution attr = (source != null && FabricBootstrap.resolver != null)
                    ? FabricBootstrap.resolver.resolve(source, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
            BlockPos center = new BlockPos((int) Math.floor(cx), (int) Math.floor(cy), (int) Math.floor(cz));
            String worldId = WorldKey.of(level);
            String sourceTag = source != null ? SourceTagger.tag(source) : Sentinel.EXPLOSION;
            String actorName = attr.actorName() != null ? attr.actorName() : Sentinel.EXPLOSION;
            network.vonix.guardian.core.Guardian g = VonixGuardianFabric.guardian();
            if (g == null) return;
            g.explosionJoinWorker().submit(s, attr.actorUuid(), actorName, worldId,
                    center.getX(), center.getY(), center.getZ(), sourceTag,
                    scratch.xs, scratch.ys, scratch.zs, scratch.ids, idx);
        } catch (Throwable t) {
            warn("explosion", t);
        }
    }

    /** Piston pre-move → PISTON_EXTEND / PISTON_RETRACT (extending = true means extend). */
    public static void piston(Level level, BlockPos pos, boolean extending) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null) return;
            String worldId = WorldKey.of(level);
            BlockState state = level.getBlockState(pos);
            String bid = blockId(state);
            if (extending) {
                s.submitPistonExtend(null, Sentinel.PISTON, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), bid, Sentinel.PISTON);
            } else {
                s.submitPistonRetract(null, Sentinel.PISTON, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), bid, Sentinel.PISTON);
            }
        } catch (Throwable t) {
            warn("piston", t);
        }
    }

    // ---- containers: snapshot on open, diff on close ---------------------------------

    private static final Map<UUID, Map<Integer, ItemStack>> SNAP = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> SNAP_POS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SNAP_WORLD = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SNAP_TIME = new ConcurrentHashMap<>();
    private static final long SNAP_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_CONTAINER_SNAPSHOTS = 512;
    private static final int MAX_CONTAINER_SLOTS = 216;

    /**
     * v1.3.0 W3: counter for cleanupContainerSnapshots amortization. Old path
     * scanned the full SNAP_TIME map on EVERY container open — O(n) per open.
     * Now we only run the TTL scan every {@link #CONTAINER_CLEANUP_EVERY} opens
     * OR when the map exceeds {@link #MAX_CONTAINER_SNAPSHOTS}.
     */
    private static final java.util.concurrent.atomic.AtomicInteger CONTAINER_CLEANUP_TICK
            = new java.util.concurrent.atomic.AtomicInteger();
    /** Full TTL scan every N opens; power-of-two so we can mask. */
    private static final int CONTAINER_CLEANUP_EVERY = 32;

    /**
     * v1.3.0 W3: pooled scratch buffer for explosion capture. One per server
     * thread — reused across explosions to avoid re-allocating 3× int[] + 1×
     * String[] per detonation.
     */
    private static final ThreadLocal<ExplosionScratch> EXPLOSION_SCRATCH =
            ThreadLocal.withInitial(ExplosionScratch::new);

    private static final class ExplosionScratch {
        int[] xs = new int[512];
        int[] ys = new int[512];
        int[] zs = new int[512];
        String[] ids = new String[512];
        void grow(int need) {
            if (xs.length >= need) return;
            int n = xs.length;
            while (n < need) n <<= 1;
            xs = new int[n]; ys = new int[n]; zs = new int[n]; ids = new String[n];
        }
    }

    /**
     * v1.3.0 W3: amortized cleanup. Old path scanned the full SNAP_TIME map on
     * every container open (O(n) per open). Now we run the TTL scan only every
     * {@link #CONTAINER_CLEANUP_EVERY} opens, PLUS an immediate fast-path
     * eviction when the map exceeds {@link #MAX_CONTAINER_SNAPSHOTS}
     * (bounded work per open).
     */
    private static void cleanupContainerSnapshots() {
        // Fast-path: bounded eviction if we're over cap.
        while (SNAP.size() > MAX_CONTAINER_SNAPSHOTS) {
            evictOldestContainerSnapshot();
        }
        // Amortized: only run the TTL scan every N opens (mask; N is power-of-two).
        int tick = CONTAINER_CLEANUP_TICK.incrementAndGet();
        if ((tick & (CONTAINER_CLEANUP_EVERY - 1)) != 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - SNAP_TTL_MS;
        SNAP_TIME.entrySet().removeIf(e -> {
            Long ts = e.getValue();
            if (ts != null && ts >= cutoff) return false;
            UUID id = e.getKey();
            SNAP.remove(id);
            SNAP_POS.remove(id);
            SNAP_WORLD.remove(id);
            return true;
        });
    }

    private static void evictOldestContainerSnapshot() {
        UUID oldest = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<UUID, Long> e : SNAP_TIME.entrySet()) {
            long ts = e.getValue() != null ? e.getValue() : Long.MIN_VALUE;
            if (ts < oldestTs) {
                oldestTs = ts;
                oldest = e.getKey();
            }
        }
        if (oldest == null && !SNAP.isEmpty()) {
            oldest = SNAP.keySet().iterator().next();
        }
        if (oldest != null) {
            SNAP.remove(oldest);
            SNAP_POS.remove(oldest);
            SNAP_WORLD.remove(oldest);
            SNAP_TIME.remove(oldest);
        }
    }

    /** Chest-open snapshot for close-time diffing. */
    public static void containerOpen(Player player, Level level, BlockPos pos,
                                     net.minecraft.world.Container container) {
        try {
            if (player == null || level == null || pos == null || container == null) return;
            cleanupContainerSnapshots();
            UUID id = player.getUUID();
            if (!SNAP.containsKey(id) && SNAP.size() >= MAX_CONTAINER_SNAPSHOTS) {
                evictOldestContainerSnapshot();
            }
            Map<Integer, ItemStack> snap = new HashMap<>();
            int size = Math.min(container.getContainerSize(), MAX_CONTAINER_SLOTS);
            for (int i = 0; i < size; i++) {
                snap.put(i, container.getItem(i).copy());
            }
            SNAP.put(id, snap);
            SNAP_POS.put(id, pos.immutable());
            SNAP_WORLD.put(id, WorldKey.of(level));
            SNAP_TIME.put(id, System.currentTimeMillis());
        } catch (Throwable t) {
            warn("containerOpen", t);
        }
    }

    /** Chest-close diff → CONTAINER_DEPOSIT / CONTAINER_WITHDRAW per slot. */
    public static void containerClose(Player player, net.minecraft.world.Container container) {
        try {
            if (player == null || container == null) return;
            UUID id = player.getUUID();
            Map<Integer, ItemStack> snap = SNAP.remove(id);
            BlockPos pos = SNAP_POS.remove(id);
            String worldId = SNAP_WORLD.remove(id);
            SNAP_TIME.remove(id);
            if (snap == null || pos == null || worldId == null) return;
            EventSubmitter s = sub();
            if (s == null) return;
            int size = Math.min(container.getContainerSize(), MAX_CONTAINER_SLOTS);
            for (int slot = 0; slot < size; slot++) {
                ItemStack before = snap.getOrDefault(slot, ItemStack.EMPTY);
                ItemStack after = container.getItem(slot);
                int beforeCount = before.isEmpty() ? 0 : before.getCount();
                int afterCount = after.isEmpty() ? 0 : after.getCount();
                String itemId = !before.isEmpty() ? itemId(before) : (!after.isEmpty() ? itemId(after) : null);
                if (itemId == null) continue;
                int delta = afterCount - beforeCount;
                if (delta == 0) continue;
                s.submitContainerChange(player.getUUID(), player.getName().getString(), worldId,
                        pos.getX(), pos.getY(), pos.getZ(), itemId, delta, null);
            }
        } catch (Throwable t) {
            warn("containerClose", t);
        }
    }

    /** BucketItem#use ray-target → BUCKET_FILL / BUCKET_EMPTY. */
    public static void bucketUse(Player player, Level level, BlockPos pos, String heldItemId, boolean empty) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || level == null || pos == null) return;
            String worldId = WorldKey.of(level);
            if (empty) {
                String fluid = heldItemId != null && heldItemId.endsWith("_bucket")
                        ? "minecraft:" + heldItemId.substring(heldItemId.lastIndexOf(':') + 1).replace("_bucket", "")
                        : (heldItemId != null ? heldItemId : "minecraft:water");
                s.submitBucketEmpty(player.getUUID(), player.getName().getString(), worldId,
                        pos.getX(), pos.getY(), pos.getZ(), fluid, null);
                // v1.3.1 X3: seed the 2-min traceback so downstream FLUID_FLOW rows attribute back.
                Guardian g = network.vonix.guardian.mc.v1_19_2.fabric.VonixGuardianFabric.guardian();
                if (g != null) {
                    network.vonix.guardian.core.attribution.FluidSourceMemory mem = g.fluidSourceMemory();
                    if (mem != null) {
                        mem.recordBucketEmpty(worldId, pos.getX(), pos.getY(), pos.getZ(),
                                player.getUUID(), player.getName().getString(),
                                System.currentTimeMillis());
                    }
                }
            } else {
                String fluidId = blockId(level.getBlockState(pos));
                s.submitBucketFill(player.getUUID(), player.getName().getString(), worldId,
                        pos.getX(), pos.getY(), pos.getZ(), fluidId, null);
            }
        } catch (Throwable t) {
            warn("bucketUse", t);
        }
    }

    /**
     * v1.3.1 X3: fluid-flow producer entry
     * (parity with CoreProtect {@code BlockFromToListener}).
     */
    public static void fluidFlow(net.minecraft.server.level.ServerLevel level, BlockPos pos,
                                 net.minecraft.world.level.material.FlowingFluid flowingFluid) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || flowingFluid == null) return;
            Guardian g = network.vonix.guardian.mc.v1_19_2.fabric.VonixGuardianFabric.guardian();
            if (g == null) return;
            String worldId = WorldKey.of(level);
            String path;
            try {
                ResourceLocation rl = Registry.FLUID.getKey(flowingFluid);
                path = rl == null ? "water" : rl.getPath();
            } catch (Throwable t) {
                path = "water";
            }
            String kind = path.contains("lava") ? "lava" : "water";
            String fluidBlockId = "minecraft:" + kind;
            String sourceTag =
                    network.vonix.guardian.core.diagnostics.MixinHotEventFilter.PREFIX_FLUID + ":" + kind;

            network.vonix.guardian.core.attribution.FluidSourceMemory mem = g.fluidSourceMemory();
            UUID actorUuid = null;
            String actorName = Sentinel.FLUID;
            if (mem != null) {
                network.vonix.guardian.core.attribution.FluidSourceMemory.Record rec =
                        mem.lookup(worldId, pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
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

    /** Player#drop → ITEM_DROP. */
    public static void itemDrop(Player player, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || stack == null || stack.isEmpty()) return;
            BlockPos pos = player.blockPosition();
            s.submitItemDrop(player.getUUID(), player.getName().getString(),
                    WorldKey.of(player.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
        } catch (Throwable t) {
            warn("itemDrop", t);
        }
    }

    /** ItemEntity#playerTouch → ITEM_PICKUP. */
    public static void itemPickup(Player player, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || stack == null || stack.isEmpty()) return;
            BlockPos pos = player.blockPosition();
            s.submitItemPickup(player.getUUID(), player.getName().getString(),
                    WorldKey.of(player.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
        } catch (Throwable t) {
            warn("itemPickup", t);
        }
    }

    /** ResultSlot#onTake → ITEM_CRAFT. */
    public static void itemCraft(Player player, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || stack == null || stack.isEmpty()) return;
            BlockPos pos = player.blockPosition();
            s.submitItemCraft(player.getUUID(), player.getName().getString(),
                    WorldKey.of(player.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
        } catch (Throwable t) {
            warn("itemCraft", t);
        }
    }

    /** FireBlock#tick → BURN. */
    public static void fireBurn(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitBurn(null, "#fire", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:burn");
        } catch (Throwable t) {
            warn("fireBurn", t);
        }
    }

    /** FireBlock#onPlace → IGNITE. */
    public static void fireIgnite(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitIgnite(null, "#fire", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:ignite");
        } catch (Throwable t) {
            warn("fireIgnite", t);
        }
    }

    /** IceBlock#melt → FADE. */
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

    /** LeavesBlock#tick decay → LEAVES_DECAY. */
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

    /** SpreadingSnowyDirtBlock#randomTick → SPREAD. */
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

    /** ConcretePowderBlock solidification and other natural formation → FORM. */
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

    /** DispenserBlock#dispenseFrom → DISPENSE. */
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

    /** Sign packet → SIGN row with metadata. */
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
                        ? network.vonix.guardian.mc.v1_19_2.common.SignMetadataExtractor.front(be)
                        : network.vonix.guardian.mc.v1_19_2.common.SignMetadataExtractor.front(be);
                dye = meta.dyeColor();
                waxed = meta.waxed();
            } catch (Throwable ignored) { /* metadata read failure must not poison */ }
            s.submitSign(player.getUUID(), player.getName().getString(),
                    WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), joined.toString(),
                    side, dye, waxed);
        } catch (Throwable t) {
            warn("signChange", t);
        }
    }

    // ================================================================== helpers

    // ================================================================== v1.3.1 X2 entity dispatchers

    /**
     * v1.3.1 X2: entity-caused block break (EnderDragon, Ravager,
     * FallingBlockEntity fall-side). Produces ENTITY_CHANGE_BLOCK with a
     * mob-scoped {@code actorName} from {@link EntitySentinel} and one of the
     * {@code EntitySentinel.SRC_*} source tags.
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
     * v1.3.1 X2: entity-caused block place (SnowGolem trail,
     * FallingBlockEntity land, LightningBolt fire).
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

    /**
     * v1.3.1 X2: Silverfish infest — full old→new state change.
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

    public static String blockId(BlockState state) {
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    public static String itemId(ItemStack stack) {
        try {
            ResourceLocation rl = Registry.ITEM.getKey(stack.getItem());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    private static void warn(String label, Throwable t) {
        LOG.warn(Guardian.MARKER, "mixin bridge {} failed", label, t);
    }
}
