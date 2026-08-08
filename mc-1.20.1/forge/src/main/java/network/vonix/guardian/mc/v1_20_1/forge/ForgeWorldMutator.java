/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
 * {@link WorldMutator} implementation for Forge 1.20.1.
 *
 * <p>1.20.1 differences vs 1.21.1 NeoForge: {@code ItemStack.isSameItemSameTags}
 * instead of {@code isSameItemSameComponents}; {@code EntityType.create(Level)}
 * unchanged.
 */
public final class ForgeWorldMutator implements WorldMutator {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeWorldMutator.class);

    private final MinecraftServer server;

    public ForgeWorldMutator(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }


    // Stable ABI bridges: pre-1.3.10 integrations retain the original void
    // descriptors while rollback/restore uses the checked try* methods.
    @Override
    public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        trySetBlock(worldId, x, y, z, targetId, targetMeta);
    }

    @Override
    public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                         String blockState, byte[] blockEntityNbt) {
        trySetBlock(worldId, x, y, z, targetId, targetMeta, blockState, blockEntityNbt);
    }

    @Override
    public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
        tryGiveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
    }

    @Override
    public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                           String targetMeta, byte[] itemNbt) {
        tryGiveOrDrop(worldId, x, y, z, itemId, amount, targetMeta, itemNbt);
    }

    @Override
    public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        tryRemoveFromContainer(worldId, x, y, z, itemId, amount);
    }

    @Override
    public void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {
        tryRespawnEntity(worldId, x, y, z, entityType, targetMeta);
    }

    @Override
    public void respawnEntity(String worldId, int x, int y, int z, String entityType,
                              String targetMeta, byte[] entityNbt) {
        tryRespawnEntity(worldId, x, y, z, entityType, targetMeta, entityNbt);
    }

    @Override
    public void removeEntity(String worldId, int x, int y, int z, String entityType) {
        tryRemoveEntity(worldId, x, y, z, entityType);
    }

    @Override
    public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return false;
            ResourceLocation rl = ResourceLocation.tryParse(targetId);
            if (rl == null) return false;
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block == null || !rl.equals(BuiltInRegistries.BLOCK.getKey(block))) return false;
            BlockState state = applyMeta(block.defaultBlockState(), targetMeta);
            return level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "setBlock failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
        }
    }

    @Override
    public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return false;
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return false;
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null || !rl.equals(BuiltInRegistries.ITEM.getKey(item))) return false;
            ItemStack stack = new ItemStack(item, Math.max(1, amount));

            BlockPos pos = new BlockPos(x, y, z);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c && tryInsert(c, stack)) {
                return true;
            }
            ItemEntity drop = new ItemEntity(level, x + 0.5, y + 0.5, z + 0.5, stack);
            drop.setDefaultPickUpDelay();
            return level.addFreshEntity(drop);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "giveOrDrop failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
        }
    }

    @Override
    public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return false;
            BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
            if (!(be instanceof Container c)) return false;
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) return false;
            Item want = BuiltInRegistries.ITEM.get(rl);
            if (want == null || !rl.equals(BuiltInRegistries.ITEM.getKey(want))) return false;
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
            return remaining == 0;
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "removeFromContainer failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
        }
    }

    @Override
    public boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return false;
            ResourceLocation rl = ResourceLocation.tryParse(entityType);
            if (rl == null) return false;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
            if (type == null || !rl.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))) return false;
            Entity e = type.create(level);
            if (e == null) return false;
            e.moveTo(x + 0.5, y, z + 0.5, 0f, 0f);
            return level.addFreshEntity(e);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "respawnEntity failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
        }
    }


    // ------------------------------------------------------------------ v1.3.2 Y1: NBT-aware overrides
    //
    // NbtIo.read runs on the main-thread executor (RollbackEngine dispatches
    // WorldMutator calls there), so it is safe to touch ServerLevel /
    // BlockEntity / EntityType.loadEntityRecursive here. Decode + registry
    // lookup failures log at DEBUG and return false from the checked path —
    // never throw or perform a second mutation.

    @Override
    public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                         String blockState, byte[] blockEntityNbt) {
        ServerLevel level = null;
        BlockState previousState = null;
        CompoundTag previousBlockEntityNbt = null;
        boolean mutationStarted = false;
        try {
            level = level(worldId);
            if (level == null) return false;
            ResourceLocation rl = ResourceLocation.tryParse(targetId);
            if (rl == null) return false;
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block == null || !rl.equals(BuiltInRegistries.BLOCK.getKey(block))) return false;
            BlockState state = block.defaultBlockState();
            if (blockState != null && !blockState.isEmpty()) {
                state = applyMeta(state, blockState);
            } else if (targetMeta != null && !targetMeta.isEmpty()) {
                state = applyMeta(state, targetMeta);
            }
            // Decode before touching the world. A malformed block-entity payload
            // must not leave a placed block behind while the checked path returns false.
            CompoundTag decodedBlockEntityNbt = null;
            if (blockEntityNbt != null && blockEntityNbt.length > 0) {
                decodedBlockEntityNbt = decodeNbt(blockEntityNbt);
                if (decodedBlockEntityNbt == null) return false;
            }

            BlockPos pos = new BlockPos(x, y, z);
            if (decodedBlockEntityNbt != null) {
                previousState = level.getBlockState(pos);
                BlockEntity previousBlockEntity = level.getBlockEntity(pos);
                if (previousBlockEntity != null) {
                    previousBlockEntityNbt = previousBlockEntity.saveWithoutMetadata();
                }
            }

            mutationStarted = true;
            boolean placed = level.setBlock(pos, state, Block.UPDATE_ALL);

            if (!placed) {
                restoreBlockMutation(level, pos, previousState, previousBlockEntityNbt);
                return false;
            }

            if (decodedBlockEntityNbt != null) {
                CompoundTag tag = decodedBlockEntityNbt;
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null) {
                    restoreBlockMutation(level, pos, previousState, previousBlockEntityNbt);
                    return false;
                }
                try {
                    be.load(tag);
                    be.setChanged();
                } catch (Throwable t) {
                    LOG.debug(Guardian.MARKER,
                        "setBlock NBT apply failed at {} {},{},{}; restoring prior block state",
                        worldId, x, y, z, t);
                    restoreBlockMutation(level, pos, previousState, previousBlockEntityNbt);
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            if (mutationStarted && level != null && previousState != null) {
                restoreBlockMutation(level, new BlockPos(x, y, z), previousState, previousBlockEntityNbt);
            }
            LOG.warn(Guardian.MARKER, "setBlock (nbt) failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
        }
    }


    private static boolean restoreBlockMutation(ServerLevel level, BlockPos pos, BlockState previousState,
                                                CompoundTag previousBlockEntityNbt) {
        if (level == null || pos == null || previousState == null) return false;
        try {
            if (!level.setBlock(pos, previousState, Block.UPDATE_ALL)) return false;
            if (!level.getBlockState(pos).equals(previousState)) return false;
            if (previousBlockEntityNbt != null) {
                BlockEntity restored = level.getBlockEntity(pos);
                if (restored == null) return false;
                restored.load(previousBlockEntityNbt);
                restored.setChanged();
            }
            return true;
        } catch (Throwable restoreFailure) {
            LOG.warn(Guardian.MARKER, "setBlock NBT rollback failed at {}", pos, restoreFailure);
            return false;
        }
    }


    @Override
    public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                           String targetMeta, byte[] itemNbt) {
        try {
            if (itemNbt == null || itemNbt.length == 0) {
                return tryGiveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
            }
            ServerLevel level = level(worldId);
            if (level == null) return false;
            ResourceLocation itemRl = ResourceLocation.tryParse(itemId);
            if (itemRl == null) return false;
            Item requestedItem = BuiltInRegistries.ITEM.get(itemRl);
            if (requestedItem == null || !itemRl.equals(BuiltInRegistries.ITEM.getKey(requestedItem))) return false;
            CompoundTag tag = decodeNbt(itemNbt);
            if (tag == null) {
                return false;
            }
            ItemStack stack;
            try {
                stack = ItemStack.of(tag);
            } catch (Throwable t) {
                LOG.debug(Guardian.MARKER,
                    "giveOrDrop NBT parse failed at {} {},{},{}; rejecting NBT mutation",
                    worldId, x, y, z, t);
                return false;
            }
            if (stack == null || stack.isEmpty()
                    || !itemRl.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                return false;
            }
            if (amount > 0) stack.setCount(Math.max(1, amount));
            BlockPos pos = new BlockPos(x, y, z);
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c && tryInsert(c, stack)) {
                return true;
            }
            ItemEntity drop = new ItemEntity(level, x + 0.5, y + 0.5, z + 0.5, stack);
            drop.setDefaultPickUpDelay();
            return level.addFreshEntity(drop);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "giveOrDrop (nbt) failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
        }
    }

    @Override
    public boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType,
                              String targetMeta, byte[] entityNbt) {
        try {
            if (entityNbt == null || entityNbt.length == 0) {
                return tryRespawnEntity(worldId, x, y, z, entityType, targetMeta);
            }
            ServerLevel level = level(worldId);
            if (level == null) return false;
            ResourceLocation entityRl = ResourceLocation.tryParse(entityType);
            if (entityRl == null) return false;
            EntityType<?> requestedType = BuiltInRegistries.ENTITY_TYPE.get(entityRl);
            if (requestedType == null || !entityRl.equals(BuiltInRegistries.ENTITY_TYPE.getKey(requestedType))) return false;
            CompoundTag tag = decodeNbt(entityNbt);
            if (tag == null) {
                return false;
            }
            Entity e = EntityType.loadEntityRecursive(tag, level, x0 -> {
                x0.moveTo(x + 0.5, y, z + 0.5, x0.getYRot(), x0.getXRot());
                return x0;
            });
            if (e == null || e.getType() != requestedType) {
                // The row's entity id is an integrity boundary; never spawn a
                // decoded entity whose registry type differs from the request.
                return false;
            }
            return level.addFreshEntity(e);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "respawnEntity (nbt) failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
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
    public boolean tryRemoveEntity(String worldId, int x, int y, int z, String entityType) {
        try {
            ServerLevel level = level(worldId);
            if (level == null) return false;
            ResourceLocation rl = ResourceLocation.tryParse(entityType);
            if (rl == null) return false;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
            if (type == null || !rl.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))) return false;
            BlockPos pos = new BlockPos(x, y, z);
            AABB box = new AABB(x - 1.0, y - 1.0, z - 1.0, x + 2.0, y + 2.0, z + 2.0);
            for (Entity e : level.getEntitiesOfClass(Entity.class, box,
                    e -> e.getType() == type && e.blockPosition().equals(pos))) {
                e.discard();
                return true;
            }
            return false;
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "removeEntity failed at {} {},{},{}", worldId, x, y, z, t);
            return false;
        }
    }

    private ServerLevel level(String worldId) {
        if (worldId == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(worldId);
        if (rl == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, rl);
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
