/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Objects;

/**
 * {@link WorldMutator} implementation for Fabric 1.18.2.
 *
 * <p>1.18.2 quirks vs 1.20.1:
 * <ul>
 *   <li>{@code BuiltInRegistries} doesn't exist — uses {@link Registry#BLOCK} /
 *       {@link Registry#ITEM} / {@link Registry#ENTITY_TYPE}.</li>
 *   <li>Dimension registry key is {@code Registry.DIMENSION_REGISTRY}
 *       (renamed to {@code Registries.DIMENSION} in 1.20.x).</li>
 * </ul>
 */
public final class FabricWorldMutator implements WorldMutator {

    private static final Logger LOG = LoggerFactory.getLogger(FabricWorldMutator.class);

    private final MinecraftServer server;

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


    // ------------------------------------------------------------------ v1.3.2 Y1: NBT-aware overrides
    //
    // NbtIo.read runs on the main-thread executor (RollbackEngine dispatches
    // WorldMutator calls there), so it is safe to touch ServerLevel /
    // BlockEntity / EntityType.loadEntityRecursive here. Decode + registry
    // lookup failures log at DEBUG and fall back to the legacy behaviour —
    // never throw.

    @Override
    public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                         String blockState, byte[] blockEntityNbt) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            ResourceLocation rl = ResourceLocation.tryParse(targetId);
            if (rl == null) return;
            Block block = Registry.BLOCK.get(rl);
            if (block == null) return;
            BlockState state = block.defaultBlockState();
            if (blockState != null && !blockState.isEmpty()) {
                state = applyMeta(state, blockState);
            } else if (targetMeta != null && !targetMeta.isEmpty()) {
                state = applyMeta(state, targetMeta);
            }
            BlockPos pos = new BlockPos(x, y, z);
            level.setBlock(pos, state, Block.UPDATE_ALL);

            if (blockEntityNbt != null && blockEntityNbt.length > 0) {
                CompoundTag tag = decodeNbt(blockEntityNbt);
                if (tag == null) return;
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null) return;
                try {
                    be.load(tag);
                    be.setChanged();
                } catch (Throwable t) {
                    LOG.debug(Guardian.MARKER,
                        "setBlock NBT apply failed at {} {},{},{}; block placed without BE contents",
                        worldId, x, y, z, t);
                }
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "setBlock (nbt) failed at {} {},{},{}", worldId, x, y, z, t);
            setBlock(worldId, x, y, z, targetId, targetMeta);
        }
    }

    @Override
    public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                           String targetMeta, byte[] itemNbt) {
        try {
            if (itemNbt == null || itemNbt.length == 0) {
                giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
                return;
            }
            ServerLevel level = level(worldId);
            if (level == null) return;
            CompoundTag tag = decodeNbt(itemNbt);
            if (tag == null) {
                giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
                return;
            }
            ItemStack stack;
            try {
                stack = ItemStack.of(tag);
            } catch (Throwable t) {
                LOG.debug(Guardian.MARKER,
                    "giveOrDrop NBT parse failed at {} {},{},{}; falling back to legacy",
                    worldId, x, y, z, t);
                giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
                return;
            }
            if (stack == null || stack.isEmpty()) {
                giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
                return;
            }
            if (amount > 0) stack.setCount(Math.max(1, amount));
            BlockPos pos = new BlockPos(x, y, z);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c && tryInsert(c, stack)) {
                return;
            }
            ItemEntity drop = new ItemEntity(level, x + 0.5, y + 0.5, z + 0.5, stack);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "giveOrDrop (nbt) failed at {} {},{},{}", worldId, x, y, z, t);
            giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
        }
    }

    @Override
    public void respawnEntity(String worldId, int x, int y, int z, String entityType,
                              String targetMeta, byte[] entityNbt) {
        try {
            if (entityNbt == null || entityNbt.length == 0) {
                respawnEntity(worldId, x, y, z, entityType, targetMeta);
                return;
            }
            ServerLevel level = level(worldId);
            if (level == null) return;
            CompoundTag tag = decodeNbt(entityNbt);
            if (tag == null) {
                respawnEntity(worldId, x, y, z, entityType, targetMeta);
                return;
            }
            Entity e = EntityType.loadEntityRecursive(tag, level, x0 -> {
                x0.moveTo(x + 0.5, y, z + 0.5, x0.getYRot(), x0.getXRot());
                return x0;
            });
            if (e == null) {
                respawnEntity(worldId, x, y, z, entityType, targetMeta);
                return;
            }
            level.addFreshEntity(e);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "respawnEntity (nbt) failed at {} {},{},{}", worldId, x, y, z, t);
            respawnEntity(worldId, x, y, z, entityType, targetMeta);
        }
    }

    private static CompoundTag decodeNbt(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in);
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "NbtIo.read failed ({} bytes): {}", bytes.length, t.toString());
            return null;
        }
    }

    @Override
    public void removeEntity(String worldId, int x, int y, int z, String entityType) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return;
            ResourceLocation rl = ResourceLocation.tryParse(entityType);
            if (rl == null) return;
            EntityType<?> type = Registry.ENTITY_TYPE.get(rl);
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
