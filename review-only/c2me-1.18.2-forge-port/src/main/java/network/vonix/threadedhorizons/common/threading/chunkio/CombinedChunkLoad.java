package network.vonix.threadedhorizons.common.threading.chunkio;

import net.minecraft.nbt.CompoundTag;

public record CombinedChunkLoad(ChunkLoadResult result, CompoundTag poiTag) {
}
