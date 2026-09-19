package network.vonix.threadedhorizons.common.threading.worldgen;

import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import net.minecraft.world.level.ChunkPos;

public interface IWorldGenLockable {

    AsyncLock getWorldGenSingleThreadedLock();

    AsyncNamedLock<ChunkPos> getWorldGenChunkLock();

}
