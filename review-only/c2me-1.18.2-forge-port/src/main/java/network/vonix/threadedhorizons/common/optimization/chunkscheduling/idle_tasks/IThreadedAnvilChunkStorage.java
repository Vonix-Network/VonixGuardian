package network.vonix.threadedhorizons.common.optimization.chunkscheduling.idle_tasks;

import net.minecraft.world.level.ChunkPos;

public interface IThreadedAnvilChunkStorage {

    void enqueueDirtyChunkPosForAutoSave(ChunkPos chunkPos);

    boolean runOneChunkAutoSave();

}
