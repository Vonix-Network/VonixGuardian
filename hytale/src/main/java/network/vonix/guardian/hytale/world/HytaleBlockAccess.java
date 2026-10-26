/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.world;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import network.vonix.guardian.core.action.NbtPayload;
import network.vonix.guardian.core.rollback.BlockEntityRestore;
import network.vonix.guardian.hytale.spi.BlockSnapshot;
import network.vonix.guardian.hytale.spi.WorldBlockAccess;
import org.bson.BsonDocument;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link WorldBlockAccess} over the Hytale chunk store.
 *
 * <p>Thread contract: {@link #read}, {@link #write}, {@link #clearToAir}, and
 * {@link #isChunkLoaded} must be called on the owning world's server thread.
 * Off-thread calls fail closed ({@code null} or {@code false}). They do not
 * call {@link World#execute(Runnable)}. That method always enqueues, including
 * when the caller is already on the world thread, so waiting on it from that
 * thread can deadlock. World-id lookup only reads {@link Universe}'s world map.
 *
 * <p>There is no {@code getBlock} or {@code setBlock} on {@code World},
 * {@code WorldChunk}, or {@code BlockChunk}. A read is
 * {@link BlockSection#get(int, int, int)} on the loaded section. A write is
 * {@link BlockOperations#setBlock} with {@link SetBlockSettings#FORCE_CHANGED}.
 * That boolean means the palette entry changed, not that the write succeeded,
 * so it is ignored. {@code true} is returned only after a read-back of the
 * block id, rotation index, and filler.
 *
 * <p>{@link BlockSnapshot#AIR} uses the id {@code "Air"}. The engine empty
 * block is {@link BlockType#EMPTY_KEY} ({@code "Empty"}, id {@code 0}).
 * Air-equivalent writes place {@link BlockType#EMPTY} and accept either id
 * on read-back. An unloaded position is never reported as air.
 *
 * <p>Component bytes are UTF-8 JSON from {@link ChunkStore#REGISTRY}
 * {@code serialize}/{@code deserialize} ({@link BsonDocument}). They are not
 * Minecraft NBT. {@code BlockEntity.ensureBlockEntity} creates an entity and
 * is never called.
 */
public final class HytaleBlockAccess implements WorldBlockAccess {

    private static final Logger LOG = Logger.getLogger(HytaleBlockAccess.class.getName());
    private static final Pattern ROTATION_INDEX = Pattern.compile("index=(-?\\d+)");
    private static final Pattern ROTATION_AXES = Pattern.compile(
        "yaw=([A-Za-z]+)(?:\\s|,|$).*pitch=([A-Za-z]+)(?:\\s|,|$).*roll=([A-Za-z]+)");
    private static final Pattern FILLER_JSON = Pattern.compile(
        "^\\{\\s*\"filler\"\\s*:\\s*(-?\\d+)\\s*}$");

    public HytaleBlockAccess() {}

    @Override
    public List<String> worldIds() {
        try {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorlds() == null) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (String id : universe.getWorlds().keySet()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            ids.sort(Comparator.naturalOrder());
            return List.copyOf(ids);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "worldIds failed", e);
            return List.of();
        }
    }

    @Override
    public String worldIdOf(UUID worldUuid) {
        World world = HytaleWorlds.worldByUuid(worldUuid);
        if (world == null) {
            return null;
        }
        String name = world.getName();
        return (name == null || name.isBlank()) ? null : name;
    }

    @Override
    public UUID worldUuidOf(String worldId) {
        World world = HytaleWorlds.worldById(worldId);
        if (world == null || world.getWorldConfig() == null) {
            return null;
        }
        return world.getWorldConfig().getUuid();
    }

    @Override
    public boolean isChunkLoaded(String worldId, int x, int z) {
        try {
            World world = HytaleWorlds.worldById(worldId);
            if (!HytaleWorlds.onWorldThread(world) || world.getChunkStore() == null) {
                return false;
            }
            long index = ChunkUtil.indexChunkFromBlock(x, z);
            Ref<ChunkStore> column = world.getChunkStore().getChunkReference(index);
            return column != null && column.isValid();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "isChunkLoaded failed for " + worldId, e);
            return false;
        }
    }

    @Override
    public BlockSnapshot read(String worldId, int x, int y, int z) {
        try {
            SectionView view = sectionAt(worldId, x, y, z);
            if (view == null) {
                return null;
            }
            return readView(view);
        } catch (OversizedComponent e) {
            LOG.warning("component payload exceeds cap at " + worldId + " " + x + "," + y + "," + z
                + "; refusing a lossy snapshot");
            return null;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "read failed at " + worldId + " " + x + "," + y + "," + z, e);
            return null;
        }
    }

    @Override
    public boolean write(String worldId, int x, int y, int z, BlockSnapshot snapshot) {
        try {
            if (snapshot == null) {
                return false;
            }
            SectionView view = sectionAt(worldId, x, y, z);
            if (view == null) {
                return false;
            }
            BlockSnapshot previous;
            try {
                previous = readView(view);
            } catch (OversizedComponent e) {
                return false;
            }
            if (previous == null) {
                return false;
            }
            Resolved resolved = resolve(snapshot);
            if (resolved == null) {
                return false;
            }
            Holder<ChunkStore> custom = null;
            if (snapshot.hasComponentData()) {
                if (!resolved.ownsEntity() || view.components == null) {
                    return false;
                }
                custom = decodeHolder(snapshot.componentData());
                if (custom == null) {
                    return false;
                }
            }
            boolean placed = place(view, resolved, custom, previous);
            if (placed) {
                recordPending(worldId, x, y, z, snapshot);
            }
            return placed;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "write failed at " + worldId + " " + x + "," + y + "," + z, e);
            return false;
        }
    }

    /**
     * Place {@link BlockType#EMPTY} first. {@code BlockOperations.setBlock}
     * clears a block entity in that same call when the new type does not own
     * one, so a failed air write may already have dropped component data.
     * The explicit remove runs only after the position reads back as air.
     * {@code true} requires air and a non-creating component read of null.
     */
    @Override
    public boolean clearToAir(String worldId, int x, int y, int z) {
        try {
            SectionView view = sectionAt(worldId, x, y, z);
            if (view == null) {
                return false;
            }
            BlockSnapshot previous;
            try {
                previous = readView(view);
            } catch (OversizedComponent e) {
                return false;
            }
            if (previous == null) {
                return false;
            }
            boolean[] mutated = {false};
            boolean[] failed = {false};
            boolean ok;
            try {
                ok = clearOps(worldId, x, y, z, view, mutated, failed);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "clearToAir failed at " + worldId + " " + x + "," + y + "," + z, e);
                ok = false;
            }
            if (!ok && mutated[0]) {
                restore(view, previous);
            }
            return ok;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "clearToAir failed at " + worldId + " " + x + "," + y + "," + z, e);
            return false;
        }
    }

    private boolean clearOps(String worldId, int x, int y, int z, SectionView view,
                             boolean[] mutated, boolean[] failed) {
        return BlockEntityRestore.clearToAir(new BlockEntityRestore.ClearOps() {
                @Override
                public void setAir() {
                    Resolved air = resolve(BlockSnapshot.AIR);
                    if (air == null) {
                        failed[0] = true;
                        return;
                    }
                    mutated[0] = true;
                    try {
                        setBlock(view, air);
                    } catch (RuntimeException e) {
                        failed[0] = true;
                        LOG.log(Level.WARNING, "air write failed at " + worldId + " " + x + "," + y + "," + z, e);
                    }
                }

                @Override
                public boolean isAir() {
                    return !failed[0] && positionIsAir(view);
                }

                @Override
                public boolean blockEntityPresent() {
                    return componentPresent(view);
                }

                @Override
                public void removeBlockEntity() {
                    clearComponents(view);
                }
            });
    }

    /**
     * Apply an interaction-state key with the official command's {@code true}
     * flag. Returns {@code true} only when the block id, rotation index, and
     * filler still match {@code requested}. A state change that replaces the
     * asset id returns {@code false} and does not try to keep the new id.
     */
    boolean applyInteraction(String worldId, int x, int y, int z, BlockSnapshot requested, String stateKey) {
        try {
            if (stateKey == null || stateKey.isBlank()) {
                return false;
            }
            SectionView view = sectionAt(worldId, x, y, z);
            if (view == null) {
                return false;
            }
            Resolved resolved = resolve(requested);
            if (resolved == null) {
                return false;
            }
            BlockOperations.setBlockInteractionState(
                view.chunkStore, view.sectionRef, view.x, view.y, view.z, resolved.type(), stateKey, true);
            return matches(view, resolved);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "interaction state failed at " + worldId + " " + x + "," + y + "," + z, e);
            return false;
        }
    }

    /** Loaded section at the block, or {@code null} when unavailable or off-thread. */
    SectionView sectionAt(String worldId, int x, int y, int z) {
        World world = HytaleWorlds.worldById(worldId);
        if (!HytaleWorlds.onWorldThread(world)) {
            return null;
        }
        ChunkStore chunkStore = world.getChunkStore();
        if (chunkStore == null) {
            return null;
        }
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        Store<ChunkStore> store = chunkStore.getStore();
        if (store == null) {
            return null;
        }
        BlockSection blocks = store.getComponent(sectionRef, BlockSection.getComponentType());
        if (blocks == null) {
            return null;
        }
        BlockComponentSection components = store.getComponent(sectionRef, BlockComponentSection.getComponentType());
        return new SectionView(world, chunkStore, store, sectionRef, blocks, components, x, y, z);
    }

    /** Non-creating container component, or {@code null} when the block has none. */
    ItemContainerBlock itemContainer(SectionView view) {
        if (view.components == null) {
            return null;
        }
        int index = ChunkUtil.indexBlock(view.x, view.y, view.z);
        var type = ItemContainerBlock.getComponentType();
        Ref<ChunkStore> ref = view.components.getBlockReference(index);
        if (ref != null && ref.isValid()) {
            ItemContainerBlock onEntity = view.store.getComponent(ref, type);
            if (onEntity != null) {
                return onEntity;
            }
        }
        Holder<ChunkStore> holder = view.components.getBlockHolder(index);
        if (holder == null) {
            return null;
        }
        return holder.getComponent(type);
    }

    private boolean place(SectionView view, Resolved resolved, Holder<ChunkStore> custom, BlockSnapshot previous) {
        boolean[] mutated = {false};
        boolean[] failed = {false};
        try {
            return placeOps(view, resolved, custom, previous, mutated, failed);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "place failed at " + view.x + "," + view.y + "," + view.z, e);
            if (mutated[0]) {
                restore(view, previous);
            }
            return false;
        }
    }

    private boolean placeOps(SectionView view, Resolved resolved, Holder<ChunkStore> custom, BlockSnapshot previous,
                             boolean[] mutated, boolean[] failed) {
        boolean ok = BlockEntityRestore.place(new BlockEntityRestore.PlaceOps() {
            @Override
            public void setState() {
                mutated[0] = true;
                try {
                    setBlock(view, resolved);
                } catch (RuntimeException e) {
                    failed[0] = true;
                    LOG.log(Level.WARNING, "setBlock threw", e);
                }
            }

            @Override
            public boolean stateMatches() {
                return !failed[0] && matches(view, resolved);
            }

            @Override
            public boolean targetOwnsBlockEntity() {
                return resolved.ownsEntity();
            }

            @Override
            public boolean blockEntityPresent() {
                return componentPresent(view);
            }

            @Override
            public void removeBlockEntity() {
                clearComponents(view, resolved.type(), resolved.rotationIndex());
            }

            @Override
            public boolean hasNbt() {
                return custom != null;
            }

            @Override
            public boolean applyNbt() {
                if (custom == null || view.components == null) {
                    return false;
                }
                try {
                    BlockEntity.setBlockEntity(
                        view.store, view.sectionRef, view.components,
                        view.x, view.y, view.z, resolved.type(), resolved.rotationIndex(), custom);
                    return componentPresent(view) && matches(view, resolved);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "component apply failed", e);
                    return false;
                }
            }

            @Override
            public void restorePrevious() {
                if (mutated[0]) {
                    restore(view, previous);
                }
            }

            @Override
            public void onStaleClearFailed() {
                LOG.warning("stale block components remained after the write");
            }
        });
        return ok;
    }

    private void restore(SectionView view, BlockSnapshot previous) {
        try {
            Resolved resolved = resolve(previous);
            if (resolved == null) {
                LOG.warning("restore could not resolve the previous block at "
                    + view.x + "," + view.y + "," + view.z);
                return;
            }
            setBlock(view, resolved);
            if (previous.hasComponentData() && resolved.ownsEntity() && view.components != null) {
                Holder<ChunkStore> holder = decodeHolder(previous.componentData());
                if (holder == null) {
                    LOG.warning("restore could not decode the previous components at "
                        + view.x + "," + view.y + "," + view.z);
                    return;
                }
                BlockEntity.setBlockEntity(
                    view.store, view.sectionRef, view.components,
                    view.x, view.y, view.z, resolved.type(), resolved.rotationIndex(), holder);
            } else if (view.components != null && componentPresent(view) && !resolved.ownsEntity()) {
                clearComponents(view, resolved.type(), resolved.rotationIndex());
            }
            if (!matches(view, resolved)) {
                LOG.warning("restore read-back mismatch at " + view.x + "," + view.y + "," + view.z);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "restore failed at " + view.x + "," + view.y + "," + view.z, e);
        }
    }

    /** Palette write. The boolean result is not success and is discarded. */
    private static void setBlock(SectionView view, Resolved resolved) {
        BlockOperations.setBlock(
            view.chunkStore,
            view.sectionRef,
            view.x, view.y, view.z,
            resolved.blockIndex(),
            resolved.type(),
            resolved.rotationIndex(),
            resolved.filler(),
            // PERFORM_BLOCK_UPDATE makes BlockOperations push the change to clients;
            // FORCE_CHANGED marks the palette entry as changed. Without the former the
            // write is server-state only and clients keep rendering the old block.
            SetBlockSettings.FORCE_CHANGED | SetBlockSettings.PERFORM_BLOCK_UPDATE);
    }

    /** One position a rollback intended to write, kept for post-batch verification. */
    record PendingWrite(String worldId, int x, int y, int z, BlockSnapshot expected) {
    }

    /** Intents recorded by {@link #write} since the last drain. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingWrite> PENDING_WRITES =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final AtomicLong PENDING_DROPS = new AtomicLong();

    private static final int PENDING_CAP = 200_000;

    static void recordPending(String worldId, int x, int y, int z, BlockSnapshot expected) {
        if (PENDING_WRITES.size() >= PENDING_CAP) {
            PENDING_DROPS.incrementAndGet();
            return;
        }
        PENDING_WRITES.add(new PendingWrite(worldId, x, y, z, expected));
    }

    /**
     * Drain the recorded intents so the caller can verify them after the batch.
     *
     * @return recorded writes in insertion order
     */
    public static java.util.List<PendingWrite> drainPending() {
        java.util.List<PendingWrite> out = new ArrayList<>();
        PendingWrite w;
        while ((w = PENDING_WRITES.poll()) != null) {
            out.add(w);
        }
        return out;
    }

    /** Intents dropped because the record cap was hit; non-zero means the check is partial. */
    public static long drainPendingDrops() {
        return PENDING_DROPS.getAndSet(0L);
    }

    private BlockSnapshot readView(SectionView view) throws OversizedComponent {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        if (map == null) {
            return null;
        }
        BlockType type = map.getAsset(view.blocks.get(view.x, view.y, view.z));
        if (type == null || type.getId() == null || type.getId().isBlank()) {
            return null;
        }
        int filler = view.blocks.getFiller(view.x, view.y, view.z);
        String stateJson = filler == 0 ? null : "{\"filler\":" + filler + "}";
        byte[] components = componentBytes(view);
        return new BlockSnapshot(type.getId(), rotationText(view), stateJson, components);
    }

    private static String rotationText(SectionView view) {
        int index = view.blocks.getRotationIndex(view.x, view.y, view.z);
        if (index == RotationTuple.NONE_INDEX) {
            return null;
        }
        RotationTuple[] values = RotationTuple.VALUES;
        if (values != null && index >= 0 && index < values.length && values[index] != null) {
            return values[index].toString();
        }
        return Integer.toString(index);
    }

    private byte[] componentBytes(SectionView view) throws OversizedComponent {
        if (view.components == null) {
            return null;
        }
        int index = ChunkUtil.indexBlock(view.x, view.y, view.z);
        Holder<ChunkStore> holder = null;
        Ref<ChunkStore> ref = view.components.getBlockReference(index);
        if (ref != null && ref.isValid()) {
            holder = view.store.copySerializableEntity(ref);
        }
        if (holder == null) {
            Holder<ChunkStore> stored = view.components.getBlockHolder(index);
            if (stored != null) {
                holder = stored.clone();
            }
        }
        if (holder == null) {
            return null;
        }
        BsonDocument document = ChunkStore.REGISTRY.serialize(holder);
        if (document == null) {
            return null;
        }
        byte[] bytes = document.toJson().getBytes(StandardCharsets.UTF_8);
        if (NbtPayload.tooLarge(bytes)) {
            throw new OversizedComponent();
        }
        return bytes.length == 0 ? null : bytes;
    }

    private Holder<ChunkStore> decodeHolder(byte[] data) {
        if (data == null || data.length == 0 || NbtPayload.tooLarge(data)) {
            return null;
        }
        BsonDocument document = BsonDocument.parse(new String(data, StandardCharsets.UTF_8));
        return ChunkStore.REGISTRY.deserialize(document);
    }

    private boolean matches(SectionView view, Resolved resolved) {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        if (map == null) {
            return false;
        }
        BlockType now = map.getAsset(view.blocks.get(view.x, view.y, view.z));
        if (now == null || now.getId() == null) {
            return false;
        }
        boolean idOk = resolved.requestedId().equals(now.getId())
            || (isAirId(resolved.requestedId()) && isAirId(now.getId()));
        return idOk
            && view.blocks.getRotationIndex(view.x, view.y, view.z) == resolved.rotationIndex()
            && view.blocks.getFiller(view.x, view.y, view.z) == resolved.filler();
    }

    private boolean positionIsAir(SectionView view) {
        BlockTypeAssetMap<String, BlockType> map = BlockType.getAssetMap();
        if (map == null) {
            return false;
        }
        BlockType now = map.getAsset(view.blocks.get(view.x, view.y, view.z));
        return now != null && isAirId(now.getId());
    }

    private boolean componentPresent(SectionView view) {
        if (view.components == null) {
            return false;
        }
        int index = ChunkUtil.indexBlock(view.x, view.y, view.z);
        if (view.components.getBlockHolder(index) != null) {
            return true;
        }
        Ref<ChunkStore> ref = view.components.getBlockReference(index);
        return ref != null && ref.isValid();
    }

    private void clearComponents(SectionView view) {
        BlockType empty = BlockType.EMPTY != null ? BlockType.EMPTY : resolveType(BlockType.EMPTY_KEY);
        if (empty == null) {
            return;
        }
        clearComponents(view, empty, RotationTuple.NONE_INDEX);
    }

    private void clearComponents(SectionView view, BlockType type, int rotationIndex) {
        if (view.components == null || type == null) {
            return;
        }
        BlockEntity.setBlockEntity(
            view.store, view.sectionRef, view.components,
            view.x, view.y, view.z, type, rotationIndex, null);
    }

    private Resolved resolve(BlockSnapshot snapshot) {
        if (snapshot == null || snapshot.blockId() == null || snapshot.blockId().isBlank()) {
            return null;
        }
        int rotation;
        int filler;
        try {
            rotation = parseRotation(snapshot.rotation());
            filler = parseFiller(snapshot.stateJson());
        } catch (IllegalArgumentException e) {
            return null;
        }
        BlockType type = resolveType(snapshot.blockId());
        if (type == null) {
            return null;
        }
        int blockIndex = blockIndex(type);
        if (blockIndex < 0) {
            return null;
        }
        boolean owns = filler == 0 && type.getBlockEntity() != null;
        return new Resolved(snapshot.blockId(), type, blockIndex, rotation, filler, owns);
    }

    private static BlockType resolveType(String blockId) {
        if (isAirId(blockId)) {
            BlockType empty = BlockType.EMPTY;
            BlockTypeAssetMap<String, BlockType> map = assetMap();
            if (empty == null && map != null) {
                empty = map.getAsset(BlockType.EMPTY_ID);
            }
            if (empty == null) {
                empty = BlockType.fromString(BlockType.EMPTY_KEY);
            }
            if (empty == null || (empty.getId() != null && !isAirId(empty.getId()))) {
                return null;
            }
            return empty;
        }
        BlockTypeAssetMap<String, BlockType> map = assetMap();
        if (map == null) {
            return null;
        }
        BlockType type = map.getAsset(blockId);
        if (type == null) {
            type = BlockType.fromString(blockId);
        }
        if (type == null || type.getId() == null || !blockId.equals(type.getId())) {
            return null;
        }
        if (type == BlockType.UNKNOWN && !BlockType.UNKNOWN_KEY.equals(blockId)) {
            return null;
        }
        return type;
    }

    private static int blockIndex(BlockType type) {
        if (type.getId() == null) {
            return isAirType(type) ? BlockType.EMPTY_ID : -1;
        }
        BlockTypeAssetMap<String, BlockType> map = assetMap();
        if (map == null) {
            return isAirType(type) ? BlockType.EMPTY_ID : -1;
        }
        int index = map.getIndex(type.getId());
        if (index == AssetMapWithIndexes.NOT_FOUND) {
            return isAirType(type) ? BlockType.EMPTY_ID : -1;
        }
        BlockType byIndex = map.getAsset(index);
        if (byIndex == null || !type.getId().equals(byIndex.getId())) {
            return -1;
        }
        return index;
    }

    private static BlockTypeAssetMap<String, BlockType> assetMap() {
        return BlockType.getAssetMap();
    }

    private static boolean isAirType(BlockType type) {
        return type == BlockType.EMPTY || (type.getId() != null && isAirId(type.getId()));
    }

    static boolean isAirId(String blockId) {
        return "Air".equals(blockId) || "Empty".equals(blockId) || BlockType.EMPTY_KEY.equals(blockId);
    }

    static int parseRotation(String rotation) {
        if (rotation == null || rotation.isBlank()) {
            return RotationTuple.NONE_INDEX;
        }
        String trimmed = rotation.trim();
        if (trimmed.startsWith("{")) {
            throw new IllegalArgumentException("rotation is not a tuple");
        }
        if (trimmed.matches("-?\\d+")) {
            return checkedIndex(Integer.parseInt(trimmed));
        }
        Matcher index = ROTATION_INDEX.matcher(trimmed);
        if (index.find()) {
            return checkedIndex(Integer.parseInt(index.group(1)));
        }
        Matcher axes = ROTATION_AXES.matcher(trimmed);
        if (axes.find()) {
            RotationTuple tuple = RotationTuple.of(
                Rotation.valueOf(axes.group(1)),
                Rotation.valueOf(axes.group(2)),
                Rotation.valueOf(axes.group(3)));
            if (tuple == null) {
                throw new IllegalArgumentException("rotation tuple was null");
            }
            return checkedIndex(tuple.index());
        }
        throw new IllegalArgumentException("unparseable rotation");
    }

    private static int checkedIndex(int index) {
        RotationTuple[] values = RotationTuple.VALUES;
        if (values == null || index < 0 || index >= values.length) {
            throw new IllegalArgumentException("rotation index out of range");
        }
        return index;
    }

    static int parseFiller(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) {
            return 0;
        }
        Matcher matcher = FILLER_JSON.matcher(stateJson.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("stateJson is not a filler object");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private record Resolved(
        String requestedId,
        BlockType type,
        int blockIndex,
        int rotationIndex,
        int filler,
        boolean ownsEntity
    ) {}

    private static final class OversizedComponent extends Exception {
        OversizedComponent() {}
    }

    /** One loaded block section. Package-visible for the mutator. */
    static final class SectionView {
        final World world;
        final ChunkStore chunkStore;
        final Store<ChunkStore> store;
        final Ref<ChunkStore> sectionRef;
        final BlockSection blocks;
        final BlockComponentSection components;
        final int x;
        final int y;
        final int z;

        SectionView(World world, ChunkStore chunkStore, Store<ChunkStore> store, Ref<ChunkStore> sectionRef,
                    BlockSection blocks, BlockComponentSection components, int x, int y, int z) {
            this.world = world;
            this.chunkStore = chunkStore;
            this.store = store;
            this.sectionRef = sectionRef;
            this.blocks = blocks;
            this.components = components;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
