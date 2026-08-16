package network.vonix.threadedhorizons.common.config;

import network.vonix.threadedhorizons.ThreadedHorizonsMod;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

// Don't load this too early
public class ThreadedHorizonsConfigConstants {

    public static final RegionFileVersion CHUNK_STREAM_VERSION;

    static {
        if (ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion == -1) {
            CHUNK_STREAM_VERSION = RegionFileVersion.VERSION_DEFLATE;
        } else {
            final RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion);
            if (chunkStreamVersion == null) {
                ThreadedHorizonsMod.LOGGER.warn("Unknown compression {}, using vanilla default instead", ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion);
                CHUNK_STREAM_VERSION = RegionFileVersion.VERSION_DEFLATE;
            } else {
                CHUNK_STREAM_VERSION = chunkStreamVersion;
            }
        }
    }

}
