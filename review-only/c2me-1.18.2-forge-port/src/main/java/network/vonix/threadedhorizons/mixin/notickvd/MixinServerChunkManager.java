package network.vonix.threadedhorizons.mixin.notickvd;

import it.unimi.dsi.fastutil.longs.LongSet;
import network.vonix.threadedhorizons.common.notickvd.IChunkTicketManager;
import network.vonix.threadedhorizons.common.util.FilteringIterable;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkManager {

    @Shadow @Final private DistanceManager distanceManager;

    /**
     * Official 1.18.2 {@code ServerChunkCache.tickChunks} collects the spawn/tick
     * list with {@code ChunkHolder.getTickingChunk()} (one invoke). It does not
     * call {@code getTickingChunkFuture()}; that method is used by
     * {@code isPositionTicking(long)} instead. Skip no-tick-only chunks so they
     * stay loaded for sending but are not random-ticked.
     */
    @Redirect(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk redirectTickingChunk(ChunkHolder chunkHolder) {
        LongSet noTickOnlyChunks = ((IChunkTicketManager) this.distanceManager).getNoTickOnlyChunks();
        if (noTickOnlyChunks != null && noTickOnlyChunks.contains(chunkHolder.getPos().toLong())) {
            return null;
        }
        return chunkHolder.getTickingChunk();
    }

    @Redirect(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getAllEntities()Ljava/lang/Iterable;"))
    private Iterable<Entity> redirectIterateEntities(ServerLevel serverWorld) {
        LongSet noTickOnlyChunks = ((IChunkTicketManager) this.distanceManager).getNoTickOnlyChunks();
        if (noTickOnlyChunks == null) {
            return serverWorld.getAllEntities();
        }
        return new FilteringIterable<>(serverWorld.getAllEntities(), entity ->
                !noTickOnlyChunks.contains(entity.chunkPosition().toLong()));
    }
}
