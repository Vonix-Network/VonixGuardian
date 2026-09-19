package network.vonix.threadedhorizons.common.optimization.chunkscheduling;

import java.util.concurrent.Executor;

public interface IThreadedAnvilChunkStorage {

    Executor getMainInvokingExecutor();

}
