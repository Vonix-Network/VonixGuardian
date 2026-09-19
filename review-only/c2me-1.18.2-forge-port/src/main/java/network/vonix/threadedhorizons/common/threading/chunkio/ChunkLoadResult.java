package network.vonix.threadedhorizons.common.threading.chunkio;

import net.minecraft.world.level.chunk.ChunkAccess;

public record ChunkLoadResult(boolean present, ChunkAccess chunk, ChunkIoMainThreadTaskUtils.Transaction transaction) {
}
