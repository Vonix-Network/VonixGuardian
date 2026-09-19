package network.vonix.threadedhorizons.common.threading.scheduler;

import network.vonix.threadedhorizons.mixin.access.IChunkTicketManager;
import network.vonix.threadedhorizons.mixin.access.IChunkTicketManagerNearbyChunkTicketUpdater;
import network.vonix.threadedhorizons.mixin.access.IThreadedAnvilChunkStorage;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

public class PriorityUtils {

    private static final int BITS_8 = 0b11111111;

    public static IntSupplier getChunkPriority(ServerLevel serverWorld, @Nullable ChunkHolder holder, ChunkPos chunkPos) {
        final DistanceManager distanceManager = serverWorld.getChunkSource().chunkMap.getDistanceManager();
        final DistanceManager.PlayerTicketTracker playerTicketManager = ((IChunkTicketManager) distanceManager).getNearbyChunkTicketUpdater();
        final Long2IntMap distanceFromPlayers = ((IChunkTicketManagerNearbyChunkTicketUpdater) playerTicketManager).getDistances();
        final long pos = chunkPos.toLong();
        if (holder == null) {
            SchedulerThread.LOGGER.warn("Failed to retrieve ChunkHolder for chunk {}, assuming load level to 0", chunkPos);
        }
        return () -> (((holder != null ? holder.getTicketLevel() : 0) & BITS_8) << 8)
                | (distanceFromPlayers.get(pos) & BITS_8);
    }

    public static IntSupplier getChunkPriority(ServerLevel serverWorld, ChunkAccess chunk) {
        final Long2ObjectLinkedOpenHashMap<ChunkHolder> chunkHolders = ((IThreadedAnvilChunkStorage) serverWorld.getChunkSource().chunkMap).getChunkHolders();
        ChunkHolder chunkHolder = chunkHolders.get(chunk.getPos().toLong());
        return getChunkPriority(serverWorld, chunkHolder, chunk.getPos());
    }

}
