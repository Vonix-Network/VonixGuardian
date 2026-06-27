/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * {@link WorldMutator} implementation for Fabric 1.19.2. Identical business
 * logic to the NeoForge implementation — Mojmap is the same on both loaders.
 */
public final class FabricWorldMutator implements WorldMutator {

    private static final Logger LOG = LoggerFactory.getLogger(FabricWorldMutator.class);

    private final MinecraftServer server;

    /**
     * @param server live server (never {@code null})
     */
    public FabricWorldMutator(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            ResourceLocation rl = ResourceLocation.tryParse(targetId);
            if (rl == null) return;
            Block block = Registry.BLOCK.get(rl);
            if (block == null) return;
            BlockState state = block.defaultBlockState();
            level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
            // TODO: parse targetMeta (block-state properties JSON) when NbtCodec lands.
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "setBlock failed at {} {},{},{}", worldId, x, y, z, t);
        }
    }

    @Override
    public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return;
            Item item = Registry.ITEM.get(rl);
            if (item == null) return;
            ItemStack stack = new ItemStack(item, Math.max(1, amount));

            BlockPos pos = new BlockPos(x, y, z);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c && tryInsert(c, stack)) {
                return;
            }
            ItemEntity drop = new ItemEntity(level, x + 0.5, y + 0.5, z + 0.5, stack);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "giveOrDrop failed at {} {},{},{}", worldId, x, y, z, t);
        }
    }

    @Override
    public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
            if (!(be instanceof Container c)) return;
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return;
            Item want = Registry.ITEM.get(rl);
            if (want == null) return;
            int remaining = Math.max(1, amount);
            for (int slot = 0; slot < c.getContainerSize() && remaining > 0; slot++) {
                ItemStack s = c.getItem(slot);
                if (s.isEmpty() || s.getItem() != want) continue;
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
                if (s.getCount() == 0) {
                    c.setItem(slot, ItemStack.EMPTY);
                }
            }
            c.setChanged();
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "removeFromContainer failed at {} {},{},{}", worldId, x, y, z, t);
        }
    }

    @Override
    public void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            ResourceLocation rl = ResourceLocation.tryParse(entityType);
            if (rl == null) return;
            EntityType<?> type = Registry.ENTITY_TYPE.get(rl);
            if (type == null) return;
            Entity e = type.create(level);
            if (e == null) return;
            e.moveTo(x + 0.5, y, z + 0.5, 0f, 0f);
            level.addFreshEntity(e);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "respawnEntity failed at {} {},{},{}", worldId, x, y, z, t);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ServerLevel level(String worldId) {
        if (worldId == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(worldId);
        if (rl == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registry.DIMENSION_REGISTRY, rl);
        return server.getLevel(key);
    }

    private static boolean tryInsert(Container c, ItemStack stack) {
        int size = c.getContainerSize();
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack cur = c.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameTags(cur, stack)) {
                int space = Math.min(cur.getMaxStackSize(), c.getMaxStackSize()) - cur.getCount();
                if (space <= 0) continue;
                int move = Math.min(space, stack.getCount());
                cur.grow(move);
                stack.shrink(move);
            }
        }
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (c.getItem(i).isEmpty()) {
                c.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
        if (stack.isEmpty()) {
            c.setChanged();
            return true;
        }
        return false;
    }
}
