package network.vonix.threadedhorizons.common.notickvd;

import net.minecraft.world.level.chunk.LevelChunk;

public interface IChunkHolder {

    LevelChunk getAccessibleChunk();

}
