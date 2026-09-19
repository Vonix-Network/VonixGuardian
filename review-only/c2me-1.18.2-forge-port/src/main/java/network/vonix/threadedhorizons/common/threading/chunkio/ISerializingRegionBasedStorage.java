package network.vonix.threadedhorizons.common.threading.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

public interface ISerializingRegionBasedStorage {

    void update(ChunkPos pos, CompoundTag tag);

}
