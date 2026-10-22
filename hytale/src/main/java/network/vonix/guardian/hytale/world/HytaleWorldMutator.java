/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.world;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.action.NbtPayload;
import network.vonix.guardian.core.rollback.WorldMutator;
import network.vonix.guardian.hytale.spi.BlockSnapshot;
import network.vonix.guardian.hytale.world.HytaleBlockAccess.SectionView;
import org.bson.BsonDocument;
import org.joml.Vector3d;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link WorldMutator} for one Hytale server.
 *
 * <p>Thread contract: every method assumes the caller is already on the
 * owning world's server thread ({@link com.hypixel.hytale.server.core.universe.world.World#isInThread()}).
 * Off-thread, unknown world, unloaded chunk, unknown item or block id,
 * failed mutation, and block read-back mismatch all return {@code false}.
 * This class does not enqueue work.
 *
 * <p>Checked {@code try*} results are the success evidence. The legacy
 * {@code void} methods only forward to those checked paths. Slot-exact
 * container edits, player inventory slots, and entity respawn or removal
 * stay on the interface defaults, which return {@code false}.
 */
public final class HytaleWorldMutator implements WorldMutator {

    private static final Logger LOG = Logger.getLogger(HytaleWorldMutator.class.getName());
    private static final Pattern FILLER_JSON = Pattern.compile(
        "^\\{\\s*\"filler\"\\s*:\\s*(-?\\d+)\\s*}$");

    private final HytaleBlockAccess blocks;

    public HytaleWorldMutator() {
        this(new HytaleBlockAccess());
    }

    public HytaleWorldMutator(HytaleBlockAccess blocks) {
        this.blocks = Objects.requireNonNull(blocks, "blocks");
    }

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
    public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        return trySetBlock(worldId, x, y, z, targetId, targetMeta, null, null);
    }

    @Override
    public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                               String blockState, byte[] blockEntityNbt) {
        try {
            if (targetId == null || targetId.isBlank()) {
                return false;
            }
            if (NbtPayload.tooLarge(blockEntityNbt)) {
                return false;
            }
            MetaParts meta = splitMeta(targetMeta);
            MetaParts state = splitState(blockState);
            if (meta.invalid || state.invalid) {
                return false;
            }
            if (meta.filler != null && state.filler != null
                    && HytaleBlockAccess.parseFiller(meta.filler) != HytaleBlockAccess.parseFiller(state.filler)) {
                return false;
            }
            String fillerJson = state.filler != null ? state.filler : meta.filler;
            byte[] components = (blockEntityNbt == null || blockEntityNbt.length == 0) ? null : blockEntityNbt;
            BlockSnapshot snapshot = new BlockSnapshot(targetId, meta.rotation, fillerJson, components);
            if (state.interaction == null) {
                return blocks.write(worldId, x, y, z, snapshot);
            }
            BlockSnapshot before = blocks.read(worldId, x, y, z);
            if (before == null) {
                return false;
            }
            if (!blocks.write(worldId, x, y, z, snapshot)) {
                return false;
            }
            if (!blocks.applyInteraction(worldId, x, y, z, snapshot, state.interaction)) {
                if (!blocks.write(worldId, x, y, z, before)) {
                    LOG.warning("interaction state moved the block id and restore failed at "
                        + worldId + " " + x + "," + y + "," + z);
                }
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "setBlock failed at " + worldId + " " + x + "," + y + "," + z, e);
            return false;
        }
    }

    @Override
    public boolean tryClearBlock(String worldId, int x, int y, int z) {
        try {
            return blocks.clearToAir(worldId, x, y, z);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "clearBlock failed at " + worldId + " " + x + "," + y + "," + z, e);
            return false;
        }
    }

    @Override
    public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
        return tryGiveOrDrop(worldId, x, y, z, itemId, amount, targetMeta, null);
    }

    @Override
    public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                                 String targetMeta, byte[] itemNbt) {
        try {
            if (targetMeta != null && !targetMeta.isBlank()) {
                return false;
            }
            ItemStack stack = stackFor(itemId, amount, itemNbt);
            if (stack == null) {
                return false;
            }
            SectionView view = blocks.sectionAt(worldId, x, y, z);
            if (view == null) {
                return false;
            }
            ItemContainerBlock containerBlock = blocks.itemContainer(view);
            if (containerBlock != null) {
                SimpleItemContainer container = containerBlock.getItemContainer();
                if (container == null) {
                    return false;
                }
                return insertedFully(container, stack);
            }
            return drop(view, stack);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "giveOrDrop failed at " + worldId + " " + x + "," + y + "," + z, e);
            return false;
        }
    }

    @Override
    public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        try {
            ItemStack stack = stackFor(itemId, amount, null);
            if (stack == null) {
                return false;
            }
            SectionView view = blocks.sectionAt(worldId, x, y, z);
            if (view == null) {
                return false;
            }
            ItemContainerBlock containerBlock = blocks.itemContainer(view);
            if (containerBlock == null) {
                return false;
            }
            SimpleItemContainer container = containerBlock.getItemContainer();
            if (container == null) {
                return false;
            }
            ItemStackTransaction tx = container.removeItemStack(stack, true, true);
            return consumedFully(tx);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "removeFromContainer failed at " + worldId + " " + x + "," + y + "," + z, e);
            return false;
        }
    }

    /**
     * {@code addItemStack(stack, allOrNothing=true, fullStacks=false, filter=true)}.
     * The one-argument overload is not all-or-nothing. A container insert never
     * also drops the remainder.
     */
    private static boolean insertedFully(ItemContainer container, ItemStack stack) {
        ItemStackTransaction tx = container.addItemStack(stack, true, false, true);
        return consumedFully(tx);
    }

    private static boolean consumedFully(ItemStackTransaction tx) {
        if (tx == null || !tx.succeeded()) {
            return false;
        }
        ItemStack remainder = tx.getRemainder();
        return remainder == null || remainder.isEmpty();
    }

    /**
     * Spawn one dropped stack at the block center. The three floats on
     * {@link ItemComponent#generateItemDrop} are a velocity, so they are zero.
     * Success is a valid spawned ref. Whether that entity actually ticks is
     * unverified without a live server.
     */
    private static boolean drop(SectionView view, ItemStack stack) {
        if (view.world.getEntityStore() == null || view.world.getEntityStore().getStore() == null) {
            return false;
        }
        var entityStore = view.world.getEntityStore().getStore();
        Holder<EntityStore> holder = ItemComponent.generateItemDrop(
            entityStore,
            stack,
            new Vector3d(view.x + 0.5, view.y + 0.5, view.z + 0.5),
            Rotation3f.ZERO,
            0f, 0f, 0f);
        if (holder == null) {
            return false;
        }
        ItemComponent component = holder.getComponent(ItemComponent.getComponentType());
        if (component != null) {
            component.setPickupDelay(ItemComponent.PICKUP_DELAY_DROPPED);
        }
        Ref<EntityStore> spawned = entityStore.addEntity(holder, AddReason.SPAWN);
        return spawned != null && spawned.isValid();
    }

    /**
     * Build a stack of exactly {@code amount}. NBT bytes are BSON JSON for
     * {@link ItemStack#CODEC}, not Minecraft NBT. A decode failure returns
     * {@code null} and the caller does not drop a default stack.
     */
    private static ItemStack stackFor(String itemId, int amount, byte[] itemNbt) {
        if (itemId == null || itemId.isBlank() || amount <= 0 || NbtPayload.tooLarge(itemNbt)) {
            return null;
        }
        var map = Item.getAssetMap();
        if (map == null) {
            return null;
        }
        Item item = map.getAsset(itemId);
        if (item == null || item == Item.UNKNOWN || !itemId.equals(item.getId())) {
            return null;
        }
        ItemStack stack;
        if (itemNbt != null && itemNbt.length > 0) {
            BsonDocument doc = BsonDocument.parse(new String(itemNbt, StandardCharsets.UTF_8));
            ExtraInfo extra = new ExtraInfo();
            stack = ItemStack.CODEC.decode(doc, extra);
            if (extra.getValidationResults() != null && extra.getValidationResults().hasFailed()) {
                return null;
            }
            if (stack == null || stack.isEmpty() || !itemId.equals(stack.getItemId())) {
                return null;
            }
            stack = stack.withQuantity(amount);
        } else {
            stack = new ItemStack(itemId, amount);
        }
        if (stack == null || stack.isEmpty() || !stack.isValid() || !itemId.equals(stack.getItemId())) {
            return null;
        }
        if (stack.getQuantity() != amount) {
            return null;
        }
        return stack;
    }

    /** targetMeta is a rotation string, or a filler object when it starts with '{'. */
    private static MetaParts splitMeta(String targetMeta) {
        if (targetMeta == null || targetMeta.isBlank()) {
            return MetaParts.EMPTY;
        }
        String trimmed = targetMeta.trim();
        if (trimmed.startsWith("{")) {
            if (!FILLER_JSON.matcher(trimmed).matches()) {
                return MetaParts.INVALID;
            }
            return new MetaParts(null, trimmed, null, false);
        }
        return new MetaParts(trimmed, null, null, false);
    }

    /**
     * blockState is filler JSON when it starts with '{', otherwise an
     * interaction-state key passed to BlockOperations.setBlockInteractionState.
     */
    private static MetaParts splitState(String blockState) {
        if (blockState == null || blockState.isBlank()) {
            return MetaParts.EMPTY;
        }
        String trimmed = blockState.trim();
        if (trimmed.startsWith("{")) {
            Matcher matcher = FILLER_JSON.matcher(trimmed);
            if (!matcher.matches()) {
                return MetaParts.INVALID;
            }
            return new MetaParts(null, trimmed, null, false);
        }
        return new MetaParts(null, null, trimmed, false);
    }

    private record MetaParts(String rotation, String filler, String interaction, boolean invalid) {
        static final MetaParts EMPTY = new MetaParts(null, null, null, false);
        static final MetaParts INVALID = new MetaParts(null, null, null, true);
    }
}
