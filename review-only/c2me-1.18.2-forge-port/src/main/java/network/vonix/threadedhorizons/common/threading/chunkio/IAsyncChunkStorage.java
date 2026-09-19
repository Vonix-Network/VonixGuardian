package network.vonix.threadedhorizons.common.threading.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;

public interface IAsyncChunkStorage {

    CompletableFuture<CompoundTag> getNbtAtAsync(ChunkPos pos);

}
