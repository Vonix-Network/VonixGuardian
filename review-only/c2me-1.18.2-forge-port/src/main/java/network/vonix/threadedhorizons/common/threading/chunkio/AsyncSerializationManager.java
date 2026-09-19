package network.vonix.threadedhorizons.common.threading.chunkio;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AsyncSerializationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Async Serialization Manager");

    private static final ThreadLocal<ArrayDeque<Scope>> scopeHolder = ThreadLocal.withInitial(ArrayDeque::new);

    public static void push(Scope scope) {
        scopeHolder.get().push(scope);
    }

    public static Scope getScope(ChunkPos pos) {
        final Scope scope = scopeHolder.get().peek();
        if (pos == null) return scope;
        if (scope != null) {
            if (scope.pos.equals(pos))
                return scope;
            LOGGER.error("Scope position mismatch! Expected: {} but got {}. This will impact stability. Incompatible mods?", scope.pos, pos, new Throwable());
        }
        return null;
    }

    public static void pop(Scope scope) {
        if (scope != scopeHolder.get().peek()) throw new IllegalArgumentException("Scope mismatch");
        scopeHolder.get().pop();
    }

    public static class Scope {
        public final ChunkPos pos;
        public final Map<LightLayer, LayerLightEventListener> lighting;
        public final Set<BlockPos> blockEntityPositions;
        public final Map<BlockPos, BlockEntity> blockEntities;
        public final Map<BlockPos, CompoundTag> pendingBlockEntityNbtsPacked;
        private final AtomicBoolean isOpen = new AtomicBoolean(false);

        @SuppressWarnings("unchecked")
        public Scope(ChunkAccess chunk, ServerLevel world) {
            this.pos = chunk.getPos();
            this.lighting = Arrays.stream(LightLayer.values()).map(type -> new CachedLightingView(world.getLightEngine(), chunk.getPos(), type)).collect(Collectors.toMap(CachedLightingView::getLightType, Function.identity()));
            this.blockEntityPositions = chunk.getBlockEntitiesPos();
            this.blockEntities = this.blockEntityPositions.stream().map(chunk::getBlockEntity).filter(Objects::nonNull).filter(blockEntity -> !blockEntity.isRemoved()).collect(Collectors.toMap(BlockEntity::getBlockPos, Function.identity()));
            {
                Map<BlockPos, CompoundTag> pendingBlockEntitiesNbtPacked = new Object2ObjectOpenHashMap<>();
                for (BlockPos blockPos : this.blockEntityPositions) {
                    final CompoundTag blockEntityNbt = chunk.getBlockEntityNbt(blockPos);
                    if (blockEntityNbt == null) continue;
                    final CompoundTag copy = blockEntityNbt.copy();
                    copy.putBoolean("keepPacked", true);
                    pendingBlockEntitiesNbtPacked.put(blockPos, copy);
                }
                this.pendingBlockEntityNbtsPacked = pendingBlockEntitiesNbtPacked;
            }
            final HashSet<BlockPos> blockPos = new HashSet<>(this.blockEntities.keySet());
            blockPos.addAll(this.pendingBlockEntityNbtsPacked.keySet());
            if (this.blockEntityPositions.size() != blockPos.size()) {
                LOGGER.warn("Block entities size mismatch! expected {} but got {}", this.blockEntityPositions.size(), blockPos.size());
            }
        }

        public void open() {
            if (!isOpen.compareAndSet(false, true)) throw new IllegalStateException("Cannot use scope twice");
        }

        private static final class CachedLightingView implements LayerLightEventListener {

            private static final DataLayer EMPTY = new DataLayer();

            private final LightLayer lightType;
            private final Map<SectionPos, DataLayer> cachedData = new Object2ObjectOpenHashMap<>();

            CachedLightingView(LevelLightEngine provider, ChunkPos pos, LightLayer type) {
                this.lightType = type;
                for (int i = provider.getMinLightSection(); i < provider.getMaxLightSection(); i++) {
                    final SectionPos sectionPos = SectionPos.of(pos, i);
                    DataLayer lighting = provider.getLayerListener(type).getDataLayerData(sectionPos);
                    cachedData.put(sectionPos, lighting != null ? lighting.copy() : null);
                }
            }

            public LightLayer getLightType() {
                return this.lightType;
            }

            @Override
            public void checkBlock(BlockPos blockPos) {
            }

            @Override
            public void onBlockEmissionIncrease(BlockPos blockPos, int i) {
            }

            @Override
            public boolean hasLightWork() {
                return false;
            }

            @Override
            public int runUpdates(int i, boolean bl, boolean bl2) {
                return i;
            }

            @Override
            public void updateSectionStatus(SectionPos pos, boolean notReady) {
            }

            @Override
            public void enableLightSources(ChunkPos chunkPos, boolean bl) {
            }

            @NotNull
            @Override
            public DataLayer getDataLayerData(SectionPos pos) {
                DataLayer layer = cachedData.getOrDefault(pos, EMPTY);
                return layer == null ? EMPTY : layer;
            }

            @Override
            public int getLightValue(BlockPos pos) {
                DataLayer layer = getDataLayerData(SectionPos.of(pos));
                return layer.get(
                        SectionPos.sectionRelative(pos.getX()),
                        SectionPos.sectionRelative(pos.getY()),
                        SectionPos.sectionRelative(pos.getZ())
                );
            }
        }
    }

}
