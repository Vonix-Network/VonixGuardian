/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.phys.AABB;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * {@link WorldMutator} implementation for NeoForge 1.21.1.
 *
 * <p>Resolves world ids via {@link MinecraftServer#getLevel(ResourceKey)},
 * defends against unloaded chunks / unknown registry keys, and silently no-ops
 * on any failure per the contract.
 */
public final class NeoForgeWorldMutator implements WorldMutator {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeWorldMutator.class);

    private final MinecraftServer server;

    /**
     * @param server live server (never {@code null})
     */
    public NeoForgeWorldMutator(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            ResourceLocation rl = ResourceLocation.tryParse(targetId);
            if (rl == null) return;
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block == null) return;
            BlockState state = applyMeta(block.defaultBlockState(), targetMeta);
            level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
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
            Item item = BuiltInRegistries.ITEM.get(rl);
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
            Item want = BuiltInRegistries.ITEM.get(rl);
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
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
            if (type == null) return;
            // 1.21.1 EntityType.create(Level) — no EntitySpawnReason yet (added 1.21.2+).
            Entity e = type.create(level);
            if (e == null) return;
            e.moveTo(x + 0.5, y, z + 0.5, 0f, 0f);
            level.addFreshEntity(e);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "respawnEntity failed at {} {},{},{}", worldId, x, y, z, t);
        }
    }


    @Override
    public void removeEntity(String worldId, int x, int y, int z, String entityType) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            ResourceLocation rl = ResourceLocation.tryParse(entityType);
            if (rl == null) return;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
            if (type == null) return;
            BlockPos pos = new BlockPos(x, y, z);
            AABB box = new AABB(x - 1.0, y - 1.0, z - 1.0, x + 2.0, y + 2.0, z + 2.0);
            for (Entity e : level.getEntitiesOfClass(Entity.class, box,
                    e -> e.getType() == type && e.blockPosition().equals(pos))) {
                e.discard();
                return;
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "removeEntity failed at {} {},{},{}", worldId, x, y, z, t);
        }
    }

    // ------------------------------------------------------------------ helpers

    private ServerLevel level(String worldId) {
        if (worldId == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(worldId);
        if (rl == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, rl);
        return server.getLevel(key);
    }

    private static boolean tryInsert(Container c, ItemStack stack) {
        int size = c.getContainerSize();
        // 1) merge into matching stacks
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack cur = c.getItem(i);
            if (!cur.isEmpty() && ItemStack.isSameItemSameComponents(cur, stack)) {
                int space = Math.min(cur.getMaxStackSize(), c.getMaxStackSize()) - cur.getCount();
                if (space <= 0) continue;
                int move = Math.min(space, stack.getCount());
                cur.grow(move);
                stack.shrink(move);
            }
        }
        // 2) fill empty slots
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

    /**
     * Best-effort apply of {@code targetMeta} block-state properties. Accepts
     * either a bare {@code key=value,key=value} list or a JSON object of
     * {@code {"key":"value"}} pairs; unrecognised properties/values are
     * silently skipped so a mismatched meta blob never blocks rollback.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyMeta(BlockState base, String meta) {
        if (meta == null || meta.isEmpty()) return base;
        String body = meta.trim();
        if (body.startsWith("{")) {
            // Strip outer braces; tolerate quoted or bare tokens.
            body = body.substring(1, body.endsWith("}") ? body.length() - 1 : body.length());
        }
        BlockState state = base;
        for (String kv : body.split(",")) {
            int eq = kv.indexOf('=');
            if (eq <= 0 || eq >= kv.length() - 1) {
                int colon = kv.indexOf(':');
                if (colon <= 0 || colon >= kv.length() - 1) continue;
                eq = colon;
            }
            String key = unquote(kv.substring(0, eq).trim());
            String val = unquote(kv.substring(eq + 1).trim());
            if (key.isEmpty() || val.isEmpty()) continue;
            net.minecraft.world.level.block.state.properties.Property property =
                    state.getBlock().getStateDefinition().getProperty(key);
            if (property == null) continue;
            java.util.Optional value = property.getValue(val);
            if (value.isEmpty()) continue;
            try {
                state = state.setValue((net.minecraft.world.level.block.state.properties.Property) property,
                        (Comparable) value.get());
            } catch (Throwable ignored) {
                // property applied to wrong block after registry drift — skip
            }
        }
        return state;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

}
